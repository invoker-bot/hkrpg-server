"""试用技能预设必须随固定资源安装；既有不同资源不得覆盖。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("trial_resource_install", Path(__file__).parents[1] / "install-resources.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

class TrialPresetInstallTest(unittest.TestCase):
    def source(self, root):
        source, target = root / "reference", root / "runtime"
        (source / "ExcelOutput").mkdir(parents=True)
        (source / "ExcelOutput/AvatarDemoConfig.json").write_text("[]", encoding="utf-8")
        name = "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_5.json"
        preset = source / name
        preset.parent.mkdir(parents=True)
        preset.write_text(json.dumps({"Name": "fixture", "PresetList": [{"Anchor": "Point01", "Level": 2}]}), encoding="utf-8")
        return source, target, name

    def test_actual_preset_is_installed_and_pinned_in_the_resource_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target, name = self.source(Path(directory))
            with patch.object(MODULE.subprocess, "check_output", side_effect=[MODULE.EXPECTED_COMMIT, "OSPRODWin4.6.0_fixture"]):
                result = MODULE.install(source, target)
            self.assertTrue((target / name).is_file())
            self.assertEqual((source / name).read_bytes(), (target / name).read_bytes())
            manifest = json.loads((target / "resource-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(MODULE.digest(source / name), manifest["files"][name])
            self.assertEqual(2, result["installed"])

    def test_conflicting_operator_preset_is_rejected_before_any_other_file_is_copied(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target, name = self.source(Path(directory))
            operator = target / name
            operator.parent.mkdir(parents=True)
            operator.write_bytes(b"operator-preset")
            with patch.object(MODULE.subprocess, "check_output", side_effect=[MODULE.EXPECTED_COMMIT, "OSPRODWin4.6.0_fixture"]):
                with self.assertRaises(RuntimeError):
                    MODULE.install(source, target)
            self.assertEqual(b"operator-preset", operator.read_bytes())
            self.assertFalse((target / "ExcelOutput/AvatarDemoConfig.json").exists())
            self.assertFalse((target / "resource-manifest.json").exists())

if __name__ == "__main__":
    unittest.main()
