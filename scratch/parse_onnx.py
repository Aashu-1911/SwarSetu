import struct
import sys

def parse_varint_stream(f):
    res = 0
    shift = 0
    while True:
        b_bytes = f.read(1)
        if not b_bytes:
            return None
        b = b_bytes[0]
        res |= (b & 0x7f) << shift
        shift += 7
        if not (b & 0x80):
            break
    return res

def parse_varint(data, pos):
    res = 0
    shift = 0
    while pos < len(data):
        b = data[pos]
        pos += 1
        res |= (b & 0x7f) << shift
        shift += 7
        if not (b & 0x80):
            break
    return res, pos

def parse_value_info(kind, val_bytes):
    def parse_proto(b):
        p = 0
        name = ""
        elem_type = 0
        shape = []
        while p < len(b):
            tw, p = parse_varint(b, p)
            fn = tw >> 3
            wt = tw & 7
            if wt == 2:
                l, p = parse_varint(b, p)
                val = b[p:p+l]
                p += l
                if fn == 1:
                    name = val.decode('utf-8', errors='ignore')
                elif fn == 2:
                    tp = 0
                    while tp < len(val):
                        ttw, tp = parse_varint(val, tp)
                        tfn = ttw >> 3
                        twt = ttw & 7
                        if twt == 2:
                            tl, tp = parse_varint(val, tp)
                            tv = val[tp:tp+tl]
                            tp += tl
                            if tfn == 1:
                                sp = 0
                                while sp < len(tv):
                                    stw, sp = parse_varint(tv, sp)
                                    sfn = stw >> 3
                                    swt = stw & 7
                                    if swt == 0 and sfn == 1:
                                        elem_type, sp = parse_varint(tv, sp)
                                    elif swt == 2 and sfn == 2:
                                        sl, sp = parse_varint(tv, sp)
                                        sv = tv[sp:sp+sl]
                                        sp += sl
                                        dp = 0
                                        while dp < len(sv):
                                            dtw, dp = parse_varint(sv, dp)
                                            dfn = dtw >> 3
                                            dwt = dtw & 7
                                            if dwt == 2 and dfn == 1:
                                                dl, dp = parse_varint(sv, dp)
                                                dv = sv[dp:dp+dl]
                                                dp += dl
                                                dim_val = None
                                                dim_p = 0
                                                while dim_p < len(dv):
                                                    dim_tw, dim_p = parse_varint(dv, dim_p)
                                                    dim_fn = dim_tw >> 3
                                                    dim_wt = dim_tw & 7
                                                    if dim_wt == 0 and dim_fn == 1:
                                                        dim_val, dim_p = parse_varint(dv, dim_p)
                                                    elif dim_wt == 2 and dim_fn == 2:
                                                        dml, dim_p = parse_varint(dv, dim_p)
                                                        dim_val = dv[dim_p:dim_p+dml].decode('utf-8', errors='ignore')
                                                        dim_p += dml
                                                    else:
                                                        break
                                                shape.append(dim_val)
                                            else:
                                                if dwt == 0: _, dp = parse_varint(sv, dp)
                                                elif dwt == 1: dp += 8
                                                elif dwt == 2:
                                                    dl, dp = parse_varint(sv, dp)
                                                    dp += dl
                                                elif dwt == 5: dp += 4
                                                else: break
            elif wt == 0:
                _, p = parse_varint(b, p)
            elif wt == 1:
                p += 8
            elif wt == 5:
                p += 4
            else:
                break
        types_map = {1: 'FLOAT', 2: 'UINT8', 3: 'INT8', 6: 'INT32', 7: 'INT64', 9: 'BOOL', 10: 'FLOAT16'}
        print(f"  {kind}: name='{name}' dtype={types_map.get(elem_type, elem_type)} shape={shape}")

    parse_proto(val_bytes)

def inspect_file(filepath):
    print(f"\n==========================================")
    print(f"Inspecting: {filepath}")
    print(f"==========================================")
    with open(filepath, 'rb') as f:
        while True:
            tw = parse_varint_stream(f)
            if tw is None:
                break
            fn = tw >> 3
            wt = tw & 7
            if wt == 0:
                parse_varint_stream(f)
            elif wt == 1:
                f.seek(8, 1)
            elif wt == 5:
                f.seek(4, 1)
            elif wt == 2:
                length = parse_varint_stream(f)
                if fn == 7: # GraphProto
                    print("Found GraphProto, length:", length)
                    end_graph = f.tell() + length
                    while f.tell() < end_graph:
                        gtw = parse_varint_stream(f)
                        if gtw is None: break
                        gfn = gtw >> 3
                        gwt = gtw & 7
                        if gwt == 0:
                            parse_varint_stream(f)
                        elif gwt == 1:
                            f.seek(8, 1)
                        elif gwt == 5:
                            f.seek(4, 1)
                        elif gwt == 2:
                            glen = parse_varint_stream(f)
                            if gfn == 11: # input
                                val_bytes = f.read(glen)
                                parse_value_info("INPUT", val_bytes)
                            elif gfn == 12: # output
                                val_bytes = f.read(glen)
                                parse_value_info("OUTPUT", val_bytes)
                            else:
                                f.seek(glen, 1)
                else:
                    f.seek(length, 1)

if __name__ == '__main__':
    inspect_file('app/src/main/assets/sravaani/sravaani_encoder.onnx')
    inspect_file('app/src/main/assets/sravaani/sravaani_ctc.onnx')
