"""机关脚本应随固定资源安装；补装不能覆盖已有文件或清单。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "world_mechanism_install", Path(__file__).parents[1] / "install-resources.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
PATHS = [
    "Config/Level/Maze/MazeGroup/Maze_Group_OpenTreasure_WhenMonsterDie.json",
    "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_BoxMan_01.json",
    "Config/Level/GroupGraph/F20001001/Group_Fixture.json",
    "Config/Level/GroupTemplateGraph/Fixture.json",
    "Config/Level/LevelInstanceGraph/F20241001/LG_Prop_F20241001_G177_300007.json",
    "Config/Props/Common/Prop_Common_MazePuzzle_01_Config.json",
]


class WorldMechanismResourceInstallTest(unittest.TestCase):
    def install(self, source, target):
        with patch.object(MODULE.subprocess, "check_output", side_effect=[
                MODULE.EXPECTED_COMMIT, "OSPRODWin4.6.0_fixture"]):
            return MODULE.install(source, target)

    def populate(self, source, paths):
        for index, name in enumerate(paths):
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"fixture": index}), encoding="utf-8")

    def test_additive_install_covers_nested_graphs_and_preserves_existing_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "source", Path(directory) / "target"
            self.populate(source, ["ExcelOutput/MazeProp.json"])
            self.install(source, target)
            original = (target / "resource-manifest.json").read_bytes()
            self.populate(source, PATHS)
            result = self.install(source, target)
            self.assertEqual(len(PATHS), result["installed"])
            self.assertEqual(original, (target / "resource-manifest.json").read_bytes())
            supplements = list(target.glob("resource-supplement-*.json"))
            self.assertEqual(1, len(supplements))
            manifest = json.loads(supplements[0].read_text("utf-8"))
            for name in PATHS:
                self.assertEqual((source / name).read_bytes(), (target / name).read_bytes())
                self.assertEqual(MODULE.digest(source / name), manifest["files"][name])
            self.assertEqual(0, self.install(source, target)["installed"])

    def test_conflict_in_any_graph_aborts_whole_install_before_copying(self):
        for name in PATHS:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                source, target = Path(directory) / "source", Path(directory) / "target"
                self.populate(source, ["ExcelOutput/MazeProp.json"] + PATHS)
                operator = target / name
                operator.parent.mkdir(parents=True)
                operator.write_bytes(b"operator-data")
                with self.assertRaisesRegex(RuntimeError, "拒绝覆盖已有资源"):
                    self.install(source, target)
                self.assertEqual([operator], [p for p in target.rglob("*") if p.is_file()])
                self.assertEqual(b"operator-data", operator.read_bytes())


if __name__ == "__main__":
    unittest.main()
