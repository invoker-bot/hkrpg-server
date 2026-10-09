"""探索技能、召唤和修饰器资源应随固定快照安装，且不得覆盖运营数据。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "adventure_resource_install", Path(__file__).parents[1] / "install-resources.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

PATHS = [
    "Config/ConfigSummonUnit/Story/SummonUnit_Story_Fixture_Config.json",
    "Config/ConfigAdventureModifier/AdventureModifier_Fixture.json",
    "Config/ConfigMazeBuff/MazeBuffToastConfig.json",
    "Config/ConfigAdventureAbility/NPCMonster/NPCMonster_Fixture_Ability.json",
    "Config/ConfigEntity/SummonUnit/SummonUnit_Fixture_Entity.json",
]


class AdventureResourceInstallTest(unittest.TestCase):
    def source(self, root):
        source, target = root / "reference", root / "runtime"
        base = source / "ExcelOutput/SummonUnitData.json"
        base.parent.mkdir(parents=True)
        base.write_text("[]", encoding="utf-8")
        return source, target

    def populate(self, source):
        for index, name in enumerate(PATHS):
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"fixture": index}), encoding="utf-8")

    def install(self, source, target):
        with patch.object(MODULE.subprocess, "check_output", side_effect=[
                MODULE.EXPECTED_COMMIT, "OSPRODWin4.6.0_fixture"]):
            return MODULE.install(source, target)

    def test_summons_nested_abilities_modifiers_and_entity_configs_are_installed_and_pinned(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = self.source(Path(directory))
            self.populate(source)
            result = self.install(source, target)
            for name in PATHS:
                self.assertTrue((target / name).is_file(), name)
                self.assertEqual((source / name).read_bytes(), (target / name).read_bytes())
            manifest = json.loads((target / "resource-manifest.json").read_text("utf-8"))
            self.assertEqual(MODULE.EXPECTED_COMMIT, manifest["sourceCommit"])
            for name in PATHS:
                self.assertEqual(MODULE.digest(source / name), manifest["files"][name])
            self.assertEqual(6, result["installed"])
            self.assertEqual(6, result["verifiedFiles"])

    def test_each_operator_conflict_aborts_before_any_new_resource_is_copied(self):
        for name in PATHS:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                source, target = self.source(Path(directory))
                self.populate(source)
                operator = target / name
                operator.parent.mkdir(parents=True)
                operator.write_bytes(b"operator-data")
                with self.assertRaises(RuntimeError):
                    self.install(source, target)
                self.assertEqual(b"operator-data", operator.read_bytes())
                self.assertFalse((target / "ExcelOutput/SummonUnitData.json").exists())
                for other in set(PATHS) - {name}:
                    self.assertFalse((target / other).exists(), other)
                self.assertFalse((target / "resource-manifest.json").exists())

    def test_additive_install_preserves_the_original_manifest_and_records_new_source_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = self.source(Path(directory))
            self.install(source, target)
            manifest = target / "resource-manifest.json"
            original = manifest.read_bytes()
            self.populate(source)
            result = self.install(source, target)
            self.assertEqual(5, result["installed"])
            self.assertEqual(original, manifest.read_bytes())
            supplements = list(target.glob("resource-supplement-*.json"))
            self.assertEqual(1, len(supplements))
            supplement = json.loads(supplements[0].read_text("utf-8"))
            for name in PATHS:
                self.assertEqual(MODULE.digest(source / name), supplement["files"][name])
            again = self.install(source, target)
            self.assertEqual(0, again["installed"])
            self.assertEqual(original, manifest.read_bytes())
            self.assertEqual(supplements, list(target.glob("resource-supplement-*.json")))

    def test_unselected_entity_configuration_is_not_added_to_the_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = self.source(Path(directory))
            unrelated = source / "Config/ConfigEntity/LocalPlayer/Unselected.json"
            unrelated.parent.mkdir(parents=True)
            unrelated.write_text("{}", encoding="utf-8")
            result = self.install(source, target)
            self.assertEqual(1, result["installed"])
            self.assertFalse((target / unrelated.relative_to(source)).exists())


if __name__ == "__main__":
    unittest.main()
