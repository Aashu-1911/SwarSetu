import io

def parse_varint(data, offset):
    res = 0
    shift = 0
    while True:
        b = data[offset]
        offset += 1
        res |= (b & 0x7f) << shift
        if not (b & 0x80):
            break
        shift += 7
    return res, offset

def parse_fields(data):
    offset = 0
    fields = []
    while offset < len(data):
        tag, offset = parse_varint(data, offset)
        field_num = tag >> 3
        wire_type = tag & 0x07
        if wire_type == 0: # varint
            val, offset = parse_varint(data, offset)
            fields.append((field_num, wire_type, val))
        elif wire_type == 1: # 64-bit
            val = data[offset:offset+8]
            offset += 8
            fields.append((field_num, wire_type, val))
        elif wire_type == 2: # length-delimited
            length, offset = parse_varint(data, offset)
            val = data[offset:offset+length]
            offset += length
            fields.append((field_num, wire_type, val))
        elif wire_type == 5: # 32-bit
            val = data[offset:offset+4]
            offset += 4
            fields.append((field_num, wire_type, val))
        else:
            print(f"Unknown wire type {wire_type} at offset {offset}")
            break
    return fields

def inspect_onnx(path):
    print(f"================== {path} ==================")
    with open(path, "rb") as f:
        data = f.read(5 * 1024 * 1024) # 5MB to inspect graph header
    
    # ModelProto fields:
    # 1: ir_version, 2: opset_import, 3: producer_name, 4: producer_version, 5: domain, 6: model_version, 7: graph (GraphProto)
    model_fields = parse_fields(data)
    graph_bytes = None
    for f_num, w_type, val in model_fields:
        if f_num == 7 and w_type == 2:
            graph_bytes = val
            break
        elif f_num == 1:
            print("ir_version:", val)
        elif f_num == 3:
            print("producer_name:", val.decode('utf-8', errors='ignore') if isinstance(val, bytes) else val)
    
    if not graph_bytes:
        print("Graph not found in first 5MB")
        return
    
    # GraphProto:
    # 1: node, 2: name, 5: initializer, 11: input (ValueInfoProto), 12: output (ValueInfoProto)
    graph_fields = parse_fields(graph_bytes)
    inputs = []
    outputs = []
    nodes = []
    for f_num, w_type, val in graph_fields:
        if f_num == 2 and w_type == 2:
            print("Graph name:", val.decode('utf-8', errors='ignore'))
        elif f_num == 11 and w_type == 2:
            # ValueInfoProto: 1: name (string), 2: type (TypeProto)
            v_fields = parse_fields(val)
            for vf_num, _, v_val in v_fields:
                if vf_num == 1:
                    inputs.append(v_val.decode('utf-8', errors='ignore'))
        elif f_num == 12 and w_type == 2:
            v_fields = parse_fields(val)
            for vf_num, _, v_val in v_fields:
                if vf_num == 1:
                    outputs.append(v_val.decode('utf-8', errors='ignore'))
        elif f_num == 1 and w_type == 2:
            # NodeProto: 1: input, 2: output, 3: name, 4: op_type
            n_fields = parse_fields(val)
            op_type = ""
            n_inputs = []
            n_outputs = []
            for nf_num, _, n_val in n_fields:
                if nf_num == 4:
                    op_type = n_val.decode('utf-8', errors='ignore')
                elif nf_num == 1:
                    n_inputs.append(n_val.decode('utf-8', errors='ignore'))
                elif nf_num == 2:
                    n_outputs.append(n_val.decode('utf-8', errors='ignore'))
            nodes.append((op_type, n_inputs, n_outputs))
            
    print("Graph INPUTS (field 11):", inputs)
    print("Graph OUTPUTS (field 12):", outputs)
    print(f"Total nodes: {len(nodes)}")
    print("First 5 nodes:", nodes[:5])
    print("Last 5 nodes:", nodes[-5:])

inspect_onnx("app/src/main/assets/sravaani/sravaani_encoder.onnx")
inspect_onnx("app/src/main/assets/sravaani/sravaani_ctc.onnx")
