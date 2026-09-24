#!/usr/bin/env python3
"""Export official SraVaani 1.0 artifacts to ONNX FP16 deployment graphs.

Exports:
1. encoder.onnx (FP16 FastConformer Encoder)
2. decoder_joint.onnx (FP16/FP32 TDT Predictor + Joint Network)
3. tables.bin (Featurizer Mel filterbank binary)
4. tokens.txt (5000 SentencePiece tokens)
"""
from __future__ import annotations

import hashlib
import json
import struct
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto
import sentencepiece as spm
import torch

HF_DIR = Path("scratch/sravaani10/hf")
OUT_DIR = Path("scratch/sravaani10/onnx")
OPSET = 17

def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()

def extract_tokenizer():
    sp_path = HF_DIR / "tokenizer.model"
    sp = spm.SentencePieceProcessor()
    sp.load(str(sp_path))
    vocab_size = sp.get_piece_size()
    print(f"Loaded tokenizer.model, vocab size = {vocab_size}")
    
    tokens_out = OUT_DIR / "tokens.txt"
    with tokens_out.open("w", encoding="utf-8") as f:
        for i in range(vocab_size):
            piece = sp.id_to_piece(i)
            f.write(f"{piece}\n")
    print(f"Wrote {vocab_size} tokens to {tokens_out} ({tokens_out.stat().st_size:,} bytes)")

def extract_tables():
    pp = torch.load(HF_DIR / "preproc.pt", map_location="cpu")
    fb = pp["fb"].float().numpy().squeeze(0)  # (128, 257)
    window = pp["window"].float().numpy()     # (400,)
    params = pp["params"]
    print(f"Preproc params: {params}")

    tables_out = OUT_DIR / "tables.bin"
    with tables_out.open("wb") as f:
        # Header: magic(4), n_fft(4), hop(4), win(4), mel_bins(4)
        f.write(struct.pack("<5i", 0x53525631, int(params["n_fft"]), int(params["hop_length"]), int(params["win_length"]), int(params.get("feat_in", 128))))
        # Float scalars: preemph(4), guard(4), pad_val(4), constant(4)
        f.write(struct.pack("<4f", float(params["preemph"]), float(params["log_zero_guard_value"]), float(params["pad_value"]), float(params.get("CONSTANT", 1e-5))))
        # Window (400 float32s)
        f.write(window.tobytes())
        # Filterbank (128 x 257 float32s)
        f.write(fb.tobytes())
    print(f"Wrote tables.bin ({tables_out.stat().st_size:,} bytes) to {tables_out}")

def convert_initializers_to_fp16(model_proto: onnx.ModelProto, keep_io_types: bool = True) -> onnx.ModelProto:
    """Convert float32 initializers to float16 in the ONNX graph."""
    graph = model_proto.graph
    
    # 1. Convert initializers
    new_inits = []
    fp16_names = set()
    for init in graph.initializer:
        if init.data_type == TensorProto.FLOAT:
            arr = numpy_helper.to_array(init)
            # Clip values to FP16 range to avoid inf/nan
            arr_f16 = np.clip(arr, -65504.0, 65504.0).astype(np.float16)
            new_init = numpy_helper.from_array(arr_f16, name=init.name)
            new_inits.append(new_init)
            fp16_names.add(init.name)
        else:
            new_inits.append(init)
    
    del graph.initializer[:]
    graph.initializer.extend(new_inits)
    
    # 2. For each node that consumes an FP16 initializer and expects Float32,
    # insert a Cast node if needed or let ONNX Runtime handle mixed precision
    # In standard ONNX, Cast nodes ensure 100% graph validity across all runtimes
    cast_nodes = []
    for node in graph.node:
        for idx, inp_name in enumerate(node.input):
            if inp_name in fp16_names:
                # The node consumes an FP16 initializer
                # Create a Cast to FLOAT node for this initializer
                cast_name = f"{inp_name}_cast_fp32"
                # Check if cast already created
                if not any(c.output[0] == cast_name for c in cast_nodes):
                    cast_node = helper.make_node(
                        "Cast",
                        inputs=[inp_name],
                        outputs=[cast_name],
                        to=TensorProto.FLOAT,
                        name=f"Cast_{inp_name}_to_fp32",
                    )
                    cast_nodes.append(cast_node)
                node.input[idx] = cast_name
                
    # Add cast nodes to graph at the beginning
    all_nodes = cast_nodes + list(graph.node)
    del graph.node[:]
    graph.node.extend(all_nodes)
    
    return model_proto

def export_encoder(model, cfg):
    """Export the FastConformer encoder to FP16 ONNX."""
    encoder = model.encoder.float().eval()

    B, F, T = 1, 128, 128
    feats = torch.randn(B, F, T, dtype=torch.float32)
    flen = torch.tensor([T], dtype=torch.int64)

    raw_enc_path = OUT_DIR / "encoder_raw.onnx"
    print("Tracing encoder graph...")
    torch.onnx.export(
        encoder,
        (feats, flen),
        str(raw_enc_path),
        input_names=["feats", "feature_lengths"],
        output_names=["enc_out", "enc_lengths"],
        dynamic_axes={
            "feats": {0: "batch", 2: "time"},
            "feature_lengths": {0: "batch"},
            "enc_out": {0: "batch", 2: "out_time"},
            "enc_lengths": {0: "batch"},
        },
        opset_version=OPSET,
        do_constant_folding=True,
        dynamo=False,
    )
    print(f"Exported raw encoder to {raw_enc_path} ({raw_enc_path.stat().st_size:,} bytes)")

    print("Converting encoder initializers to FP16...")
    m = onnx.load(str(raw_enc_path))
    m_fp16 = convert_initializers_to_fp16(m, keep_io_types=True)

    fp16_enc_path = OUT_DIR / "encoder.onnx"
    onnx.save(m_fp16, str(fp16_enc_path))
    print(f"Saved FP16 encoder to {fp16_enc_path} ({fp16_enc_path.stat().st_size:,} bytes)")
    if raw_enc_path.exists():
        raw_enc_path.unlink()

def export_decoder_joint(model, cfg):
    """Export the TDT Predictor + Joint Network to ONNX."""
    decoder_joint = model.decoder_joint.float().eval()

    B, D = 1, 1024
    H = int(cfg.pred_hidden)
    f = torch.randn(B, D, 1, dtype=torch.float32)
    tgt = torch.tensor([[5000]], dtype=torch.int32)
    tlen = torch.tensor([1], dtype=torch.int32)
    h = torch.zeros(1, B, H, dtype=torch.float32)
    c = torch.zeros(1, B, H, dtype=torch.float32)

    raw_dec_path = OUT_DIR / "decoder_joint_raw.onnx"
    print("Tracing decoder_joint graph...")
    torch.onnx.export(
        decoder_joint,
        (f, tgt, tlen, h, c),
        str(raw_dec_path),
        input_names=["f", "tgt", "tlen", "h", "c"],
        output_names=["logits", "tlen_out", "h_out", "c_out"],
        dynamic_axes={
            "f": {0: "batch"},
            "tgt": {0: "batch"},
            "h": {1: "batch"},
            "c": {1: "batch"},
            "logits": {0: "batch"},
            "h_out": {1: "batch"},
            "c_out": {1: "batch"},
        },
        opset_version=OPSET,
        do_constant_folding=True,
        dynamo=False,
    )
    print(f"Exported raw decoder_joint to {raw_dec_path} ({raw_dec_path.stat().st_size:,} bytes)")

    print("Converting decoder_joint initializers to FP16...")
    m = onnx.load(str(raw_dec_path))
    m_fp16 = convert_initializers_to_fp16(m, keep_io_types=True)

    fp16_dec_path = OUT_DIR / "decoder_joint.onnx"
    onnx.save(m_fp16, str(fp16_dec_path))
    print(f"Saved FP16 decoder_joint to {fp16_dec_path} ({fp16_dec_path.stat().st_size:,} bytes)")
    if raw_dec_path.exists():
        raw_dec_path.unlink()

def inspect_graph(name, path):
    print(f"\n================ INSPECTING {name} ================")
    m = onnx.load(str(path), load_external_data=False)
    size = path.stat().st_size
    print(f"File: {path.name} ({size:,} bytes / {size/(1024*1024):.2f} MB)")
    print(f"Opset: {[op.version for op in m.opset_import]}")
    
    # Inspect inputs
    print("Inputs:")
    for inp in m.graph.input:
        shape = [d.dim_value if d.dim_value else d.dim_param for d in inp.type.tensor_type.shape.dim]
        dtype = TensorProto.DataType.Name(inp.type.tensor_type.elem_type)
        print(f"  - {inp.name}: shape={shape}, dtype={dtype}")

    # Inspect outputs
    print("Outputs:")
    for out in m.graph.output:
        shape = [d.dim_value if d.dim_value else d.dim_param for d in out.type.tensor_type.shape.dim]
        dtype = TensorProto.DataType.Name(out.type.tensor_type.elem_type)
        print(f"  - {out.name}: shape={shape}, dtype={dtype}")

    # Inspect initializer dtypes
    dtypes = {}
    for init in m.graph.initializer:
        dt = TensorProto.DataType.Name(init.data_type)
        dtypes[dt] = dtypes.get(dt, 0) + 1
    print(f"Weight Initializer Data Types: {dtypes}")
    
    # Check ops
    op_counts = {}
    for node in m.graph.node:
        op_counts[node.op_type] = op_counts.get(node.op_type, 0) + 1
    print(f"Top 5 Ops: {sorted(op_counts.items(), key=lambda x: -x[1])[:5]}")
    print(f"SHA-256: {sha256_of(path)}")

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    
    # 1. Load config
    with (HF_DIR / "config.json").open("r", encoding="utf-8") as f:
        cfg_dict = json.load(f)
    from types import SimpleNamespace
    cfg = SimpleNamespace(**cfg_dict)

    # 2. Extract tokenizer & tables
    extract_tokenizer()
    extract_tables()

    # 3. Load TorchScript model
    ts_path = HF_DIR / "model-asr.fp16.ts"
    print(f"Loading TorchScript model from {ts_path}...")
    model = torch.jit.load(str(ts_path), map_location="cpu").eval()

    # 4. Export encoder & decoder_joint
    export_encoder(model, cfg)
    export_decoder_joint(model, cfg)

    # 5. Programmatic graph inspection
    inspect_graph("Encoder", OUT_DIR / "encoder.onnx")
    inspect_graph("Decoder Joint", OUT_DIR / "decoder_joint.onnx")

    return 0

if __name__ == "__main__":
    sys.exit(main())
