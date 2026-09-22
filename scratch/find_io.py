import sys
import os
sys.path.insert(0, os.path.abspath("."))
from scratch.inspect_onnx import parse_fields

def find_graph_io(path):
    print(f"*** Analyzing {path} ***")
    with open(path, "rb") as f:
        data = f.read(5 * 1024 * 1024)
    
    fields = parse_fields(data)
    for f_num, w_type, val in fields:
        if f_num == 7 and w_type == 2:
            graph_fields = parse_fields(val)
            for gf_num, gw_type, gval in graph_fields:
                if gf_num == 11 and gw_type == 2: # input
                    vi_fields = parse_fields(gval)
                    for fn, _, v in vi_fields:
                        if fn == 1:
                            print("INPUT:", v.decode('utf-8', errors='ignore'))
                elif gf_num == 12 and gw_type == 2: # output
                    vi_fields = parse_fields(gval)
                    for fn, _, v in vi_fields:
                        if fn == 1:
                            print("OUTPUT:", v.decode('utf-8', errors='ignore'))

find_graph_io("app/src/main/assets/sravaani/sravaani_encoder.onnx")
find_graph_io("app/src/main/assets/sravaani/sravaani_ctc.onnx")
