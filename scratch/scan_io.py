import sys
import os
sys.path.insert(0, os.path.abspath("."))
from scratch.inspect_onnx import parse_fields

def parse_varint_from_file(f):
    res = 0
    shift = 0
    while True:
        b = f.read(1)
        if not b:
            return None
        val = b[0]
        res |= (val & 0x7f) << shift
        if not (val & 0x80):
            break
        shift += 7
    return res

def scan_model_inputs_outputs(path):
    print("=" * 60)
    print("Scanning", path)
    with open(path, "rb") as f:
        # Read ModelProto
        while True:
            tag = parse_varint_from_file(f)
            if tag is None:
                break
            field_num = tag >> 3
            wire_type = tag & 0x07
            if wire_type == 0:
                parse_varint_from_file(f)
            elif wire_type == 1:
                f.seek(8, 1)
            elif wire_type == 2:
                length = parse_varint_from_file(f)
                if field_num == 7: # GraphProto
                    graph_end = f.tell() + length
                    scan_graph(f, graph_end)
                    break
                else:
                    f.seek(length, 1)
            elif wire_type == 5:
                f.seek(4, 1)

def scan_graph(f, graph_end):
    inputs = []
    outputs = []
    
    while f.tell() < graph_end:
        tag = parse_varint_from_file(f)
        if tag is None:
            break
        field_num = tag >> 3
        wire_type = tag & 0x07
        if wire_type == 0:
            parse_varint_from_file(f)
        elif wire_type == 1:
            f.seek(8, 1)
        elif wire_type == 2:
            length = parse_varint_from_file(f)
            if field_num == 11: # input (ValueInfoProto)
                val = f.read(length)
                vf = parse_fields(val)
                for fn, wt, v in vf:
                    if fn == 1 and wt == 2:
                        inputs.append(v.decode('utf-8', errors='ignore'))
            elif field_num == 12: # output (ValueInfoProto)
                val = f.read(length)
                vf = parse_fields(val)
                for fn, wt, v in vf:
                    if fn == 1 and wt == 2:
                        outputs.append(v.decode('utf-8', errors='ignore'))
            else:
                f.seek(length, 1)
        elif wire_type == 5:
            f.seek(4, 1)
            
    print("ALL INPUTS (field 11):", inputs)
    print("ALL OUTPUTS (field 12):", outputs)

scan_model_inputs_outputs("app/src/main/assets/sravaani/sravaani_encoder.onnx")
scan_model_inputs_outputs("app/src/main/assets/sravaani/sravaani_ctc.onnx")
