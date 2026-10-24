"""Recreate the pinned YOLO26n ONNX artifact; optionally compare user-supplied RGB images.

Run from any directory. Outputs must go to a new directory; packaged weights are never changed.
Ultralytics APIs and official weights are AGPL-3.0 (see licenses/ultralytics-LICENSE.txt).
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import importlib.util

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path, required=True, help="New output directory")
parser.add_argument("--images", type=Path, nargs="*", default=[], help="Local RGB images you may use; not distributed here")
args = parser.parse_args()
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
os.environ["YOLO_AUTOINSTALL"] = "false"
os.environ["YOLO_CONFIG_DIR"] = str(output / ".config")
os.environ["ORT_DISABLE_TELEMETRY"] = "1"

import cv2
import numpy as np
import onnx
import onnxruntime as ort
import torch
import ultralytics
from ultralytics import YOLO
from ultralytics.data.augment import LetterBox

sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
reference = json.loads((ROOT / "evidence/export-verification.json").read_text(encoding="utf-8"))
assert ultralytics.__version__ == reference["versions"]["ultralytics"]
assert onnx.__version__ == reference["versions"]["onnx"]
assert ort.__version__ == reference["versions"]["onnxruntime"]
assert torch.__version__ == reference["versions"]["torch"]
torch.set_num_threads(2)
cv2.setNumThreads(1)
source = ROOT / "source/yolo26n.pt"
assert sha(source) == reference["ptSha256"]
checkpoint = output / "yolo26n.pt"
shutil.copy2(source, checkpoint)
model = YOLO(str(checkpoint))
exported = Path(model.export(**reference["exportConfig"]))
graph = onnx.load(str(exported))
# Export creation time is not a weight. Fix only this field to the published artifact's timestamp.
# The upstream author, description, license and all graph data remain intact.
for entry in graph.metadata_props:
    if entry.key == "date":
        entry.value = reference["metadata"]["date"]
onnx.checker.check_model(graph)
onnx.save(graph, str(exported))
options = ort.SessionOptions()
options.intra_op_num_threads = 2
options.inter_op_num_threads = 1
session = ort.InferenceSession(str(exported), sess_options=options, providers=["CPUExecutionProvider"])
assert session.get_inputs()[0].shape == [1, 3, 640, 640]
assert session.get_outputs()[0].shape == [1, 300, 6]
assert session.get_modelmeta().custom_metadata_map["end2end"] == "True"
report = {
    "onnxSha256": sha(exported), "onnxBytes": exported.stat().st_size,
    "publishedArtifactSha256": reference["onnxSha256"],
    "byteIdentical": sha(exported) == reference["onnxSha256"],
    "creationDateNormalized": reference["metadata"]["date"],
    "execution": "desktop CPU; no Android timing or new accuracy claim",
    "samples": [],
}

if args.images:
    pt = YOLO(str(checkpoint))
    pt.model.eval()
    pt.model.end2end = True  # Same native one-to-one head selected by nms=False export.
    for path in args.images:
        bgr = cv2.imread(str(path))
        if bgr is None:
            raise ValueError("Image could not be decoded")
        height, width = bgr.shape[:2]
        padded = LetterBox((640, 640), auto=False, scale_fill=False, scaleup=True, center=True)(image=bgr)
        tensor = np.ascontiguousarray(padded[:, :, ::-1].transpose(2, 0, 1)[None], dtype=np.float32) / 255.0
        prediction = session.run(None, {"images": tensor})[0][0]
        with torch.inference_mode():
            raw = pt.model(torch.from_numpy(tensor))
            if isinstance(raw, (tuple, list)):
                raw = raw[0]
            expected = raw.detach().cpu().numpy()[0]
        assert expected.shape == prediction.shape == (300, 6)
        scale = min(640 / width, 640 / height)
        left = (640 - round(width * scale)) // 2
        top = (640 - round(height * scale)) // 2
        def original(rows):
            rows = rows[rows[:, 4] >= .25].copy()
            rows[:, [0, 2]] = np.clip((rows[:, [0, 2]] - left) / scale, 0, width)
            rows[:, [1, 3]] = np.clip((rows[:, [1, 3]] - top) / scale, 0, height)
            return rows
        a, b = original(expected), original(prediction)
        assert len(a) == len(b)
        unused = set(range(len(b)))
        max_box, max_score = 0.0, 0.0
        for row in a:
            matches = [j for j in unused if int(b[j, 5]) == int(row[5])]
            assert matches
            index = min(matches, key=lambda j: float(np.max(np.abs(row[:4] - b[j, :4]))))
            unused.remove(index)
            max_box = max(max_box, float(np.max(np.abs(row[:4] - b[index, :4]))))
            max_score = max(max_score, float(abs(row[4] - b[index, 4])))
        assert max_box < .02 and max_score < .0001
        report["samples"].append({"inputSha256": sha(path), "width": width, "height": height,
            "acceptedBoxes": len(b), "maxBoxAbsPixels": max_box, "maxScoreAbs": max_score})

tool = ROOT.parents[1] / "tools/sanitize_onnx_metadata.py"
spec = importlib.util.spec_from_file_location("sanitize_onnx_metadata", tool)
sanitizer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sanitizer)
packaged = output / "yolo26n-detect-640.onnx"
sanitization = sanitizer.sanitize_file(exported, packaged, np.zeros((1, 3, 640, 640), dtype=np.float32))
packaged_reference = json.loads((ROOT / "package-manifest.json").read_text(encoding="utf-8"))["appAsset"]
report["rawSourceOnnxSha256"] = sha(exported)
report["packagedOnnxSha256"] = sha(packaged)
report["packagedOnnxBytes"] = packaged.stat().st_size
report["packagedByteIdentical"] = sha(packaged) == packaged_reference["sha256"]
report["sanitization"] = {k: sanitization[k] for k in ["graphSerializedBytesUnchanged", "authorLicenseUnmodified", "cpuOutputProofs"]}
(output / "reproduction.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
if not report["byteIdentical"] or not report["packagedByteIdentical"]:
    raise SystemExit("Export completed, but artifact bytes differ. Inspect versions and graph before replacing any asset.")
