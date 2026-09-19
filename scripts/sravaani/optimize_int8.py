#!/usr/bin/env python3
"""P9 optimization: weight-only int8 candidate for the exported SraVaani encoder.

Method (weight-only MatMul int8 — the only quantization family compatible with this graph
per the P2 op inventory: Conv/Attention/LayerNorm/Squeeze-Excite all run fp32; only MatMul
weights are int8-dequantized inside ORT's QLinearMatMul-free dynamic path):

  For every MatMul with a 2-D constant weight W (the encoder's QKV/FF1/FF2 projections):
      W_int8 = round(W / scale_per_output_channel), scale = max|W_row| / 127
  Graph mutation: replace the Constant tensor and insert DequantizeLinear, so the compute
  graph stays fp32 (activations untouched → minimal accuracy risk, no calibration set).

CTC head is NOT quantized (tiny, 20 MB). Featurizer is not quantized (numerically sensitive
mel weights). Dynamic-range int8 of activations was REJECTED without implementation: it
requires calibration over the exact mel distribution; with a not-yet-staged corpus the
calibration set would be synthetic — fabrication-adjacent (master §5/§23 policy).

Output: scratch/sravaani/onnx/sravaani_encoder.int8.onnx + int8_meta.json
(provenance chain: fp32 graph sha → operator → int8 graph sha).

Equivalence must be proven separately by prove_equivalence.py BEFORE any packaging (P9 gate).
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper, TensorProto


def sha256_of(path: Path) -> str:
    import hashlib

    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def quantize_weight(w: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Per-output-channel symmetric int8: scale = max|row| / 127."""
    if w.ndim != 2:
        return None, None
    scale = np.abs(w).max(axis=1, keepdims=True) / 127.0
    scale = np.where(scale == 0, 1.0, scale).astype(np.float32)
    q = np.clip(np.round(w / scale), -127, 127).astype(np.int8)
    return q, scale.astype(np.float32)


def quantize_matmul_weights(model: onnx.ModelProto) -> dict:
    """Replace 2-D float Constant MatMul weights with int8 + DequantizeLinear."""
    # Collect all initializers and Constant-node outputs for lookup by name.
    consts = {init.name: init for init in model.graph.initializer}
    const_nodes = {}
    for node in model.graph.node:
        if node.op_type == "Constant" and len(node.output) == 1:
            for attr in node.attribute:
                if attr.name == "value":
                    const_nodes[node.output[0]] = attr.t

    stats = {"matmuls_seen": 0, "weights_quantized": 0, "params_moved": 0}
    replaced = set()  # names of float weights replaced by int8 copies — prune these
    new_nodes = []
    for node in model.graph.node:
        if node.op_type != "MatMul":
            new_nodes.append(node)
            continue
        stats["matmuls_seen"] += 1
        # (MatMul is appended after its DequantizeLinear below — never here.)

        quantized = False
        # Input B (index 1) is the weight in inference graphs (X@Wᵀ layout from exports).
        for idx, cand in ((1, node.input[1]), (0, node.input[0])):
            w = None
            if cand in consts:
                w = numpy_helper.to_array(consts[cand])
            elif cand in const_nodes:
                w = numpy_helper.to_array(const_nodes[cand])
            if w is None or w.ndim != 2 or w.dtype not in (np.float32, np.float16):
                continue
            # Weight matrices in these exports are (in, out); per-output-channel = per-column
            # when the MatMul computes X(in) @ W(in, out). Quantize along the OUTPUT axis:
            out_axis = 1 if idx == 1 else 0
            w32 = w.astype(np.float32)
            scale = np.abs(w32).max(axis=1 - out_axis, keepdims=True) / 127.0
            scale = np.where(scale == 0, 1.0, scale).astype(np.float32)
            q = np.clip(np.round(w32 / scale), -127, 127).astype(np.int8)

            q_name = cand + ".int8"
            scale_name = cand + ".scale"
            deq_name = cand + ".dequant"
            model.graph.initializer.extend(
                [
                    numpy_helper.from_array(q, q_name),
                    numpy_helper.from_array(scale.ravel().astype(np.float32), scale_name),
                ]
            )
            deq = onnx.helper.make_node(
                "DequantizeLinear",
                inputs=[q_name, scale_name],
                outputs=[deq_name],
                axis=out_axis,  # scale applies along the weight's OUTPUT axis (len == w.shape[out_axis])
                name=cand + ".deq",
            )
            new_nodes.append(deq)
            node.input[idx] = deq_name
            new_nodes.append(node)  # keep the (mutated) MatMul itself
            replaced.add(cand)
            stats["weights_quantized"] += 1
            stats["params_moved"] += int(w32.size)
            quantized = True
            break  # quantized one input; done with this MatMul
        if not quantized:
            new_nodes.append(node)  # unquantizable MatMul: keep as-is

    # Prune the REPLACED float weights (their Constant nodes lost all users; unused
    # initializers keep the file bloated and can push past protobuf's 2 GB limit).
    used = set()
    for n in list(model.graph.node) + new_nodes:
        used.update(n.input)
    inits = [i for i in model.graph.initializer if i.name not in replaced or i.name in used]
    del model.graph.initializer[:]
    model.graph.initializer.extend(inits)
    model.graph.ClearField("node")
    model.graph.node.extend(new_nodes)
    return stats


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--encoder", default="scratch/sravaani/onnx/sravaani_encoder.onnx")
    ap.add_argument("--out", default="scratch/sravaani/onnx/sravaani_encoder.int8.onnx")
    ap.add_argument("--meta-out", default="scratch/sravaani/onnx/int8_meta.json")
    args = ap.parse_args()

    src = Path(args.encoder)
    if not src.is_file():
        print(f"BLOCKED: fp32 encoder not found at {src} — run scripts/sravaani/export_onnx.py first.")
        return 2

    model = onnx.load(str(src), load_external_data=True)
    stats = quantize_matmul_weights(model)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(out))

    src_size = src.stat().st_size
    out_size = out.stat().st_size
    meta = {
        "method": "weight-only int8 (per-output-channel symmetric, DequantizeLinear in-graph)",
        "source": str(src),
        "source_sha256": sha256_of(src),
        "output": str(out),
        "output_sha256": sha256_of(out),
        "source_bytes": src_size,
        "output_bytes": out_size,
        "compression": round(src_size / max(out_size, 1), 3),
        "stats": stats,
        "onnx_opset": model.opset_import[0].version,
        "source_revision": "29a15303a55673367134eee9e1d18cb1a2dcaabf",
        "note": "Candidate artifact — NOT production until prove_equivalence.py passes.",
    }
    Path(args.meta_out).write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"int8 candidate: {out} ({out_size:,} B, from {src_size:,} B, x{meta['compression']})")
    print(f"stats: {stats}")
    print("NEXT: run prove_equivalence.py before any packaging decision.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
