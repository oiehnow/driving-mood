"""ONNX audit must distinguish text from numeric weights without skipping metadata."""
import importlib.util
from pathlib import Path
import unittest
import onnx
from onnx import TensorProto, helper

spec = importlib.util.spec_from_file_location("apk_audit", Path(__file__).with_name("audit-apk-text.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class OnnxTextAuditTest(unittest.TestCase):
    def model(self):
        return helper.make_model(helper.make_graph([], "test", [], []))

    def test_numeric_storage_is_not_prose(self):
        model = self.model()
        # Numeric raw bytes may coincidentally resemble a path; preserve their exact bytes.
        value = TensorProto(name="weight", data_type=TensorProto.UINT8, dims=[10], raw_data=b"C:/example")
        model.graph.initializer.append(value)
        strings = list(audit.onnx_strings(model.SerializeToString()))
        self.assertNotIn("C:/example", strings)
        self.assertFalse(set().union(*(audit.sensitive_categories(s) for s in strings)))

    def test_metadata_and_nested_documentation_are_checked(self):
        model = self.model()
        model.metadata_props.add(key="description", value="C:/example/source")
        model.graph.node.add(op_type="Identity", doc_string="/home/example/source")
        categories = set().union(*(audit.sensitive_categories(s) for s in audit.onnx_strings(model.SerializeToString())))
        self.assertEqual({"windows_absolute_path", "user_home_path"}, categories)

    def test_string_tensor_is_checked(self):
        model = self.model()
        model.graph.initializer.append(helper.make_tensor("text", TensorProto.STRING, [1], [b"C:/example/source"]))
        self.assertIn("windows_absolute_path", set().union(*(audit.sensitive_categories(s) for s in audit.onnx_strings(model.SerializeToString()))))

    def test_nested_string_attributes_are_checked(self):
        model = self.model()
        nested = helper.make_graph([helper.make_node("Custom", [], [], single="C:/example/source",
            multiple=["/home/example/source", "harmless"])], "nested", [], [])
        model.graph.node.append(helper.make_node("If", [], [], then_branch=nested))
        categories = set().union(*(audit.sensitive_categories(s) for s in audit.onnx_strings(model.SerializeToString())))
        self.assertEqual({"windows_absolute_path", "user_home_path"}, categories)


if __name__ == "__main__":
    unittest.main()
