"""回归：使用固定资源中的目标，不以同组或编号顺序猜测奖励。"""
import importlib.util
from pathlib import Path
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("world_catalog", SCRIPTS / "generate-world-mechanisms.py")
catalog = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = catalog
spec.loader.exec_module(catalog)


class WorldMechanismCatalogTest(unittest.TestCase):
    def test_drone_owner_group_target_is_not_the_helper_actor(self):
        node = {"TargetType": {
            "$type": "RPG.GameCore.TargetFetchAdvPropEx",
            "FetchType": "SinglePropByOwnerGroupAndID",
            "PropIDInOwnerGroup": {"FixedValue": {"Value": 300005}},
        }}
        self.assertEqual((261, 300005), catalog.target(node, 300001, 261, {}))
        node["TargetType"]["PropIDInOwnerGroup"]["IsDynamic"] = True
        self.assertIsNone(catalog.target(node, 300001, 261, {}))

    def test_cable_finish_has_an_explicit_chest_binding(self):
        self.assertEqual("Chest", catalog.LINKED_GRAPHS.get(
            "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_CablePuzzle_05.json"))


if __name__ == "__main__":
    unittest.main()
