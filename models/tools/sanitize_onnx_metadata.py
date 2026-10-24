"""Remove absolute machine paths from ONNX metadata/doc strings without changing computation.

No path values are emitted. Unexpected paths in graph identifiers fail for manual review;
numeric tensor bytes are never interpreted as text and are never rewritten.
"""
from pathlib import Path
import argparse
import copy
import hashlib
import json
import re
import shutil

import numpy as np
import onnx
import onnxruntime as ort
from google.protobuf.descriptor import FieldDescriptor

PATH_PATTERNS = (
    re.compile(r"(?i)(?<![A-Za-z])[A-Z]:[\\/][A-Za-z0-9_\uac00-\ud7a3][^\r\n\"'<>]*"),
    re.compile(r"/(?:Users|home)/[A-Za-z0-9_.-]+/[^\s\"'<>]*"),
)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def string_fields(message, prefix="model"):
    for field, value in message.ListFields():
        items = enumerate(value) if field.is_repeated else [(None, value)]
        for index, child in items:
            location = prefix + "." + field.name + (f"[{index}]" if index is not None else "")
            if field.type == FieldDescriptor.TYPE_MESSAGE:
                yield from string_fields(child, location)
            elif field.type == FieldDescriptor.TYPE_STRING:
                yield message, field, index, child, location

def normalized_computation(model):
    clean = copy.deepcopy(model)
    def clear(message):
        for field, value in list(message.ListFields()):
            if field.name in ("doc_string", "metadata_props"):
                message.ClearField(field.name)
            elif field.type == FieldDescriptor.TYPE_MESSAGE:
                for child in value if field.is_repeated else [value]:
                    clear(child)
    clear(clean)
    return clean.SerializeToString(deterministic=True)

def tensor_proofs(model):
    tensors = []
    def walk(message, prefix="model"):
        if message.DESCRIPTOR.full_name == "onnx.TensorProto":
            tensors.append({"field": prefix, "serializedSha256": digest(message.SerializeToString(deterministic=True)),
                            "rawDataSha256": digest(message.raw_data), "rawDataBytes": len(message.raw_data),
                            "dataType": message.data_type})
        for field, value in message.ListFields():
            if field.type != FieldDescriptor.TYPE_MESSAGE:
                continue
            for i, child in enumerate(value if field.is_repeated else [value]):
                walk(child, prefix + "." + field.name + f"[{i}]")
    walk(model)
    return tensors

def nontext_weight_matches(model):
    """Evidence of why decoding numeric weights as UTF-8 is not a string audit."""
    matches = []
    for index, tensor in enumerate(model.graph.initializer):
        if tensor.data_type == onnx.TensorProto.STRING:
            continue
        counts = {encoding: sum(len(list(pattern.finditer(tensor.raw_data.decode(encoding, errors="replace"))))
                               for pattern in PATH_PATTERNS) for encoding in ("utf-8", "utf-16le")}
        if any(counts.values()):
            matches.append({"initializerIndex": index, "dataType": tensor.data_type,
                            "pathLikePatternCountsFromIncorrectTextDecoding": counts})
    return matches

def check_byte_string_fields(message):
    for field, value in message.ListFields():
        for child in value if field.is_repeated else [value]:
            if field.type == FieldDescriptor.TYPE_MESSAGE:
                check_byte_string_fields(child)
            elif field.type == FieldDescriptor.TYPE_BYTES:
                numeric_raw = (message.DESCRIPTOR.full_name == "onnx.TensorProto" and
                               field.name == "raw_data" and message.data_type != onnx.TensorProto.STRING)
                if numeric_raw:
                    continue
                if any(pattern.search(child.decode("utf-8", errors="replace")) for pattern in PATH_PATTERNS):
                    raise ValueError("Path in a computational byte-string attribute/tensor; manual review required")

def redact_model(source):
    check_byte_string_fields(source)
    model = copy.deepcopy(source)
    changes = []
    for owner, field, index, value, location in list(string_fields(model)):
        if not any(pattern.search(value) for pattern in PATH_PATTERNS):
            continue
        metadata_value = owner.DESCRIPTOR.full_name == "onnx.StringStringEntryProto" and field.name == "value"
        if not (field.name == "doc_string" or metadata_value):
            raise ValueError("Machine path in graph identifier or other computational field; manual reference-safe handling required")
        if metadata_value and owner.key.lower() in ("author", "license", "copyright"):
            raise ValueError("A copyright/author/license field needs manual preservation review")
        redacted = value
        for pattern in PATH_PATTERNS:
            redacted = pattern.sub("[machine path removed]", redacted)
        if index is None:
            setattr(owner, field.name, redacted)
        else:
            getattr(owner, field.name)[index] = redacted
        changes.append({"field": location, "metadataKey": owner.key if metadata_value else None,
                        "originalTextSha256": digest(value.encode("utf-8")),
                        "sanitizedTextSha256": digest(redacted.encode("utf-8"))})
    assert not any(pattern.search(value) for _, _, _, value, _ in string_fields(model) for pattern in PATH_PATTERNS)
    return model, changes

def sanitize_file(source_path, destination, tensor):
    original_bytes = source_path.read_bytes()
    source = onnx.load_model_from_string(original_bytes)
    clean, changes = redact_model(source)
    original_compute = normalized_computation(source)
    cleaned_compute = normalized_computation(clean)
    assert original_compute == cleaned_compute, "Computational protobuf changed"
    original_tensors = tensor_proofs(source)
    assert original_tensors == tensor_proofs(clean), "Tensor content or identity changed"
    onnx.checker.check_model(clean)
    # Unchanged files stay byte-identical; avoid needless protobuf reserialization.
    clean_bytes = clean.SerializeToString() if changes else original_bytes
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    outputs = []
    for model_bytes in (original_bytes, clean_bytes):
        session = ort.InferenceSession(model_bytes, sess_options=options, providers=["CPUExecutionProvider"])
        input_info = session.get_inputs()[0]
        assert input_info.shape == list(tensor.shape)
        outputs.append(session.run(None, {input_info.name: tensor}))
        del session
    assert len(outputs[0]) == len(outputs[1])
    output_proofs = []
    for old, new in zip(*outputs):
        assert old.dtype == new.dtype and old.shape == new.shape
        assert old.tobytes() == new.tobytes(), "CPU inference bytes changed"
        output_proofs.append({"shape": list(old.shape), "dtype": str(old.dtype),
                              "sha256": digest(old.tobytes()), "byteDifferences": 0})
    destination.write_bytes(clean_bytes)
    return {"model": destination.name, "originalSha256": digest(original_bytes),
            "packagedSha256": digest(clean_bytes), "originalBytes": len(original_bytes),
            "packagedBytes": len(clean_bytes), "changes": changes,
            "computationalProtoSha256": digest(original_compute),
            "graphSerializedBytesUnchanged": source.graph.SerializeToString() == clean.graph.SerializeToString(),
            "tensorProofs": original_tensors, "inputTensorSha256": digest(tensor.tobytes()),
            "numericWeightTextDecodeFalsePositives": nontext_weight_matches(source),
            "cpuOutputProofs": output_proofs, "actualStringFieldsPathFree": True,
            "authorLicenseUnmodified": True}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("assets", type=Path, nargs="+")
    parser.add_argument("--backup-dir", type=Path, required=True, help="New private backup directory")
    parser.add_argument("--input-320", type=Path, required=True)
    parser.add_argument("--input-640", type=Path, required=True)
    parser.add_argument("--provenance", type=Path, required=True)
    args = parser.parse_args()
    args.backup_dir.mkdir(parents=True, exist_ok=False)
    inputs = {size: np.fromfile(path, dtype="<f4").reshape(1, 3, size, size)
              for size, path in [(320, args.input_320), (640, args.input_640)]}
    proof = {"format": "ONNX metadata sanitization v1", "onnxVersion": onnx.__version__,
             "onnxruntimeVersion": ort.__version__, "executionProvider": "CPUExecutionProvider",
             "numericWeightsRewritten": False, "baselineOrtModified": False, "models": []}
    for asset in args.assets:
        backup = args.backup_dir / asset.name
        shutil.copy2(asset, backup)
        graph = onnx.load(str(backup))
        size = graph.graph.input[0].type.tensor_type.shape.dim[2].dim_value
        proof["models"].append(sanitize_file(backup, asset, inputs[size]))
    args.provenance.parent.mkdir(parents=True, exist_ok=True)
    args.provenance.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    print(json.dumps([{k: row[k] for k in ["model", "originalSha256", "packagedSha256", "packagedBytes"]}
                      for row in proof["models"]], indent=2))

if __name__ == "__main__":
    main()
