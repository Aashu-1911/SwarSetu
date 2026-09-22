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

def parse_type_proto(data):
    # TypeProto field 1: tensor_type (Tensor)
    tf = parse_fields(data)
    for fn, wt, v in tf:
        if fn == 1 and wt == 2: # Tensor
            # Tensor: 1: elem_type, 2: shape (TensorShapeProto)
            ttf = parse_fields(v)
            elem_type = None
            shape = []
            for tfn, twt, tv in ttf:
                if tfn == 1:
                    elem_type = tv
                elif tfn == 2 and twt == 2:
                    # TensorShapeProto: 1: dim (Dimension)
                    sf = parse_fields(tv)
                    for sfn, swt, sv in sf:
                        if sfn == 1 and swt == 2:
                            # Dimension: 1: dim_value, 2: dim_param
                            df = parse_fields(sv)
                            for dfn, _, dv in df:
                                if dfn == 1:
                                    shape.append(dv)
                                elif dfn == 2:
                                    shape.append(dv.decode('utf-8', errors='ignore'))
            return elem_type, shape
    return None, None

def scan_shapes(path):
    print("=" * 60)
    print("Scanning shapes for", path)
    with open(path, "rb") as f:
        while True:
            tag = parse_varint_from_file(f)
            if tag is None:
                break
            field_num = tag >> 3
            wire_type = tag & 0x07
            if wire_type == 2:
                length = parse_varint_from_file(f)
                if field_num == 7: # GraphProto
                    graph_end = f.tell() + length
                    while f.tell() < graph_end:
                        gtag = parse_varint_from_file(f)
                        if gtag is None: break
                        gfn = gtag >> 3
                        gwt = gtag & 0x07
                        if gwt == 2:
                            glen = parse_varint_from_file(f)
                            if gfn == 11: # input
                                val = f.read(glen)
                                vf = parse_fields(val)
                                name = ""
                                tp = None
                                for fn, wt, v in vf:
                                    if fn == 1 and wt == 2: name = v.decode('utf-8')
                                    elif fn == 2 and wt == 2: tp = v
                                elem_type, shape = parse_type_proto(tp)
                                print(f"INPUT: name='{name}', elem_type={elem_type}, shape={shape}")
                            elif gfn == 12: # output
                                val = f.read(glen)
                                vf = parse_fields(val)
                                name = ""
                                tp = None
                                for fn, wt, v in vf:
                                    if fn == 1 and wt == 2: name = v.decode('utf-8')
                                    elif fn == 2 and wt == 2: tp = v
                                elem_type, shape = parse_type_proto(tp)
                                print(f"OUTPUT: name='{name}', elem_type={elem_type}, shape={shape}")
                            else:
                                f.seek(glen, 1)
                        elif gwt == 0: parse_varint_from_file(f)
                        elif gwt == 1: f.seek(8, 1)
                        elif gwt == 5: f.seek(4, 1)
                    break
                else:
                    f.seek(length, 1)
            elif wire_type == 0: parse_varint_from_file(f)
            elif wire_type == 1: f.seek(8, 1)
            elif wire_type == 5: f.seek(4, 1)

scan_shapes("app/src/main/assets/sravaani/sravaani_encoder.onnx")
scan_shapes("app/src/main/assets/sravaani/sravaani_ctc.onnx")
