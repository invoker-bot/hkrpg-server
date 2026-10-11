"""检查真实本地analyzer参数契约与原完整来源，不运行原生客户端。"""

import ast
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ManualSourceGuardTest(unittest.TestCase):
    def test_actual_analyzer_contract_has_all_options_including_disassemble(self):
        inspector = load("manual_guard_inspector", "scripts/inspect-maze-manual-ability-client.py")
        profile = json.loads((ROOT / "scripts/fixtures/maze-manual-ability-native-4.6.json").read_text("utf-8"))
        options = inspector.native_options(Path("original-client"), profile)
        actual = ast.parse((ROOT / "scripts/analyze-client-46.py").read_text("utf-8"))
        extract = next(node for node in actual.body if isinstance(node, ast.FunctionDef) and node.name == "extract")
        required = {node.attr for node in ast.walk(extract) if isinstance(node, ast.Attribute)
                    and isinstance(node.value, ast.Name) and node.value.id == "args"}
        self.assertLessEqual(required, vars(options).keys())
        self.assertEqual(0, options.disassemble)
        self.assertEqual([], options.method_names)
        self.assertFalse(options.inheritance, "Generic owner ancestry must not be represented as resolved")
        self.assertEqual(15, len(options.method_indices))

    def test_checked_in_whole_blob_fixture_matches_immutable_originals(self):
        source = load("manual_guard_freezer", "scripts/freeze-maze-manual-ability-source.py")
        expected = json.loads((ROOT / "src/test/resources/fixtures/maze-manual-ability-4.6.json").read_text("utf-8"))
        actual = source.source_fixture(ROOT / "references/TurnBasedGameData", ROOT / "resources/4.6")
        self.assertEqual(expected, actual)
        self.assertEqual(355282, sum(row["fullBlobBytes"] for row in actual["sourceFiles"].values()))


if __name__ == "__main__":
    unittest.main()
