"""回归：使用固定资源中的目标，不以同组或编号顺序猜测奖励。"""
import importlib.util
from pathlib import Path
import sys
import unittest
import copy

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from source_json import loads, fingerprint
from world_mechanism_compass import compass_rule
spec = importlib.util.spec_from_file_location("world_catalog", SCRIPTS / "generate-world-mechanisms.py")
catalog = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = catalog
spec.loader.exec_module(catalog)


class WorldMechanismCatalogTest(unittest.TestCase):
    def test_existing_435_rule_receipts_keep_their_exact_identity(self):
        source = loads((SCRIPTS.parent / 'src/main/resources/gameplay/world-mechanisms-4.6.json').read_bytes())
        previous = {k:v for k,v in source['rules'].items() if k not in ('20211674','20211681','20211682')}
        self.assertEqual(435,len(previous))
        self.assertEqual('58beb31dc44484370bc5b9551a2934fcf16227d191ede100cda3f440c0b896ab',fingerprint(previous))
        self.assertEqual(0,len(source['pending']))
        self.assertEqual('mascot-extension-4.6.json',source['independentOwners']['20433651'])
        compatibility=loads((SCRIPTS.parent/'src/main/resources/gameplay/compatible-mechanisms-4.6.json').read_bytes())
        ids={k for f in compatibility['flows'].values() for k in f['rewards']}
        self.assertEqual(37,len(ids))
        self.assertEqual(ids,set(source['independentOwners'])-{'20433651'})
        self.assertTrue(all(source['independentOwners'][k]=='compatible-mechanisms-4.6.json' for k in ids))

    def compass(self, chest, mutate=lambda files: None):
        fixture = loads((SCRIPTS.parent / 'src/test/resources/fixtures/world-mechanisms-4.6.json').read_bytes())
        files = copy.deepcopy(fixture['groups'] | fixture['headers'])
        mutate(files)
        loc = fixture['locations'][str(chest)]
        index = {}
        for mission, raw in fixture['missions'].items():
            subs = {s['ID']:s for s in raw['SubMissionList']}
            for sub in subs.values():
                if sub.get('FinishType') == 'PropState' and sub.get('ParamInt3') == 13:
                    index.setdefault((sub.get('LevelFloorID'),sub.get('ParamInt1'),sub.get('ParamInt2')),[]).append(
                        (f'Config/Level/Mission/{mission}/MissionInfo_{mission}.partial.json',sub,subs))
        return compass_rule(files[loc['path']],loc,files.get,{},lambda path,used:used.add(path),set(),set(),catalog,index)

    def test_compass_bindings_require_exact_ship_chest_and_controller(self):
        for chest in [20211674,20211681,20211682]:
            with self.subTest(chest=chest): self.assertIsNotNone(self.compass(chest))
        def wrong_ship(files):
            for raw in files.values():
                for prop in raw.get('PropList',[]):
                    for value in prop.get('ValueSource',{}).get('Values',[]):
                        if value.get('Key')=='PuzzleCompass_CargoChest01': value['Value']='130,300003'
        self.assertIsNone(self.compass(20211674,wrong_ship))

    def test_npc_cleanup_requires_the_original_open_controller_predicate(self):
        def wrong_state(files):
            files['Config/Level/Mission/4015102/Mission_401510209.json']['OnStartSequece'][0]['TaskList'][0]['State']='Closed'
        self.assertIsNone(self.compass(20211682,wrong_state))

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
