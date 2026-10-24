import importlib.util
import io
import unittest
from pathlib import Path
from PIL import Image, PngImagePlugin

spec = importlib.util.spec_from_file_location("apk_audit", Path(__file__).with_name("audit-apk-text.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class ImageMetadataTest(unittest.TestCase):
    def test_png_text_is_checked_without_treating_pixels_as_text(self):
        image = Image.new("RGB", (8, 8), (20, 80, 160))
        plain = io.BytesIO()
        image.save(plain, format="PNG")
        self.assertEqual([], list(audit.image_metadata_strings(plain.getvalue())))
        info = PngImagePlugin.PngInfo()
        info.add_text("Comment", "C:/private/example")
        tagged = io.BytesIO()
        image.save(tagged, format="PNG", pnginfo=info)
        values = list(audit.image_metadata_strings(tagged.getvalue()))
        self.assertIn("windows_absolute_path", audit.sensitive_categories(values[0]))


if __name__ == "__main__":
    unittest.main()
