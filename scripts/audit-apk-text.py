"""Read-only wording audit of the built APK, including DEX strings and binary resources.

Usage: python scripts/audit-apk-text.py app/build/outputs/apk/release/app-release.apk
Dependencies: Pillow, fontTools, onnx (ONNX assets are parsed, never treated as prose).
Reports matching entry names and terms, never dumps arbitrary application strings.
Copyright/license originals must be reviewed, not edited to silence a match.
"""

import argparse
import hashlib
import io
import json
import os
import re
import struct
import zipfile
from pathlib import Path


TERMS = (
    "공모전", "제출", "시연", "세션 A", "세션 B", "세션 C", "A/B/C",
    "PHONE PROTOTYPE", "휴대폰 실험", "휴대폰 프로토타입", "Pleos 연동은 이후",
    "실제 기기 검증 필요", "실기기 검증 필요", "ABI/라이브러리 확인",
    "간판/상점/건물 클래스 없음", "HSV·면적 측정",
)

# Report only the category, never the matched credential or local path.
SENSITIVE_PATTERNS = {
    "windows_absolute_path": re.compile(r"(?i)(?<![A-Za-z])[A-Z]:[\\/][A-Za-z0-9_\uac00-\ud7a3]"),
    "user_home_path": re.compile(r"/(?:Users|home)/[A-Za-z0-9_.-]+/"),
    "github_token": re.compile(r"(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})"),
    "api_secret": re.compile(r"sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{24,}"),
    "private_key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
}


def sensitive_categories(value):
    return {name for name, pattern in SENSITIVE_PATTERNS.items() if pattern.search(value)}


def dex_strings(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Unsupported DEX header")
    count, offset = struct.unpack_from("<II", data, 56)
    for i in range(count):
        start = struct.unpack_from("<I", data, offset + i * 4)[0]
        # Skip the UTF-16 character count (ULEB128). Korean BMP text uses normal UTF-8
        # bytes in MUTF-8; the NUL terminator follows the encoded string content.
        while data[start] & 0x80:
            start += 1
        start += 1
        end = data.index(b"\x00", start)
        yield data[start:end].decode("utf-8", errors="replace")


def font_name_strings(data):
    """Read Unicode/Mac Korean names with fontTools; glyph bytes are not prose."""
    from fontTools.ttLib import TTFont

    with TTFont(io.BytesIO(data), lazy=False) as font:
        for record in font["name"].names:
            yield record.toUnicode(errors="strict")


def image_metadata_strings(data):
    """Inspect image text/EXIF, not compressed pixel bytes that resemble paths by chance."""
    from PIL import Image

    with Image.open(io.BytesIO(data)) as image:
        for value in image.info.values():
            if isinstance(value, str):
                yield value
        for value in image.getexif().values():
            if isinstance(value, str):
                yield value
            elif isinstance(value, bytes):
                yield value.decode("utf-8", errors="replace")


def onnx_strings(data):
    """Inspect protobuf text and STRING tensors, excluding numeric tensor storage.

    ONNX TensorProto.raw_data contains little-endian numeric bytes, not UTF-8.
    Missing parser support fails the audit instead of silently skipping the asset.
    """
    import onnx
    from google.protobuf.descriptor import FieldDescriptor

    model = onnx.load_model_from_string(data)

    def walk(message):
        if isinstance(message, onnx.TensorProto) and message.data_type == onnx.TensorProto.STRING and message.raw_data:
            raise ValueError("Unsupported raw STRING tensor; inspect before release")
        for field, value in message.ListFields():
            values = value if field.is_repeated else (value,)
            if field.type == FieldDescriptor.TYPE_MESSAGE:
                for child in values:
                    yield from walk(child)
            elif field.type == FieldDescriptor.TYPE_STRING:
                yield from values
            elif field.name == "string_data" and isinstance(message, onnx.TensorProto):
                for text in values:
                    yield text.decode("utf-8", errors="strict")
            elif isinstance(message, onnx.AttributeProto) and field.name in ("s", "strings"):
                for text in values:
                    yield text.decode("utf-8", errors="strict")

    yield from walk(model)


def audit(path):
    findings = []
    upstream_metadata = []
    evidence_path = Path(__file__).resolve().parent.parent / "docs/upstream-native-metadata.json"
    evidence = json.loads(evidence_path.read_text(encoding="utf-8")) if evidence_path.exists() else []
    known_native = {row["entry"]: row["sha256"] for row in evidence if row.get("identical") is True}
    local_paths = {str(Path.home()), str(Path.cwd())}
    if Path.cwd().parent != Path(Path.cwd().anchor) and len(str(Path.cwd().parent)) > 5:
        local_paths.add(str(Path.cwd().parent))
    local_paths.update(filter(None, [os.environ.get("USERPROFILE")]))
    dex_count = 0
    string_count = 0
    font_string_count = 0
    with zipfile.ZipFile(path) as apk:
        entries = apk.infolist()
        for entry in entries:
            if entry.is_dir():
                continue
            data = apk.read(entry)
            sensitive = set()
            for local in local_paths:
                if any(variant.encode(enc) in data for variant in (local, local.replace("\\", "/"))
                       for enc in ("utf-8", "utf-16le", "utf-16be")):
                    sensitive.add("current_user_or_workspace_path")
            if entry.filename.endswith(".dex"):
                dex_count += 1
                hits = set()
                for value in dex_strings(data):
                    string_count += 1
                    hits.update(term for term in TERMS if term in value)
                    sensitive.update(sensitive_categories(value))
            elif entry.filename.endswith((".ttf", ".otf")):
                hits = set()
                for value in font_name_strings(data):
                    font_string_count += 1
                    hits.update(term for term in TERMS if term in value)
                    sensitive.update(sensitive_categories(value))
                # Continue checking printable token/home-path runs outside the name table.
                # Short drive-letter-like glyph bytes are meaningful only in actual text.
                for value in re.findall(rb"[\x20-\x7e]{6,}", data):
                    sensitive.update(sensitive_categories(value.decode("ascii")) - {"windows_absolute_path"})
            elif entry.filename.lower().endswith((".png", ".jpg", ".jpeg", ".webp", ".gif")):
                hits = set()
                for value in image_metadata_strings(data):
                    hits.update(term for term in TERMS if term in value)
                    sensitive.update(sensitive_categories(value))
            elif entry.filename.lower().endswith(".onnx"):
                hits = set()
                for value in onnx_strings(data):
                    hits.update(term for term in TERMS if term in value)
                    sensitive.update(sensitive_categories(value))
            else:
                # UTF-8 and UTF-16LE cover text assets, XML string pools and resources.arsc.
                hits = {term for term in TERMS if any(term.encode(enc) in data
                        for enc in ("utf-8", "utf-16le"))}
                if entry.filename.endswith((".so", ".tflite", ".ort")):
                    # Random machine code/weights are not text. Inspect actual printable runs.
                    for value in re.findall(rb"[\x20-\x7e]{6,}", data):
                        sensitive.update(sensitive_categories(value.decode("ascii")))
                else:
                    for enc in ("utf-8", "utf-16le"):
                        sensitive.update(sensitive_categories(data.decode(enc, errors="replace")))
            if "user_home_path" in sensitive and known_native.get(entry.filename) == hashlib.sha256(data).hexdigest():
                upstream_metadata.append({"entry": entry.filename, "category": "upstream_build_path",
                                          "sha256": known_native[entry.filename], "original_binary_unchanged": True})
                sensitive.remove("user_home_path")
            if hits or sensitive:
                findings.append({"entry": entry.filename, "terms": sorted(hits),
                                 "sensitive_categories": sorted(sensitive)})
    if not dex_count:
        raise ValueError("No DEX files inspected")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"apk": path.name, "sha256": digest,
            "entries": len(entries), "dex_files": dex_count, "dex_strings": string_count,
            "font_name_strings": font_string_count,
            "findings": findings, "upstream_metadata": upstream_metadata, "passed": not findings}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    result = audit(args.apk)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["passed"] else 1)
