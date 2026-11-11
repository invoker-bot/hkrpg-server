import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1]/'audit-scene-interactions.py'
spec = importlib.util.spec_from_file_location('scene_interaction_audit', SCRIPT)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class SceneInteractionAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in {n for names in audit.PERFORMANCE_TABLES.values() for n in names} | {
                'RaidConfig', 'RaidConfigLD', 'MazeProp'}:
            self.write(f'ExcelOutput/{name}.json', [])

    def write(self, path, value):
        target = self.root/path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(value), encoding='utf-8')

    def trigger(self, **extra):
        row = dict({'$type': 'RPG.GameCore.TriggerPerformance', 'PerformanceType': 'D',
            'PerformanceID': 101}, **extra)
        self.write('Config/Level/Props/test.json', {'ButtonCallback': [row]})

    def placement(self, deleted=False, prop=3001, dimension=0):
        group = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P1_F1001/LevelGroup_P1_F1001_G1.json'
        self.write(group, {'PropList': [{'ID':300001,'PropID':prop,'RaidID':201,
            'IsOverrideInitLevelGraph':True,'InitLevelGraph':'Config/Level/Props/test.json'}]})
        self.write('Config/LevelOutput/RuntimeFloor/P1_F1001.json', {'FloorID':1001,
            'GroupInstanceList':[{'GroupPath':group,'ID':1,'IsDelete':deleted}],
            'DimensionList':[{'ID':dimension,'GroupIndexList':[0]}]})
        self.write('ExcelOutput/MazeProp.json', [{'ID':3001,'PropType':'PROP_RELIC'},
            {'ID':901,'PropType':'PROP_COCOON'}])

    def test_discussion_table_satisfies_d_reference(self):
        self.trigger()
        self.write('ExcelOutput/PerformanceDS.json', [{'PerformanceID':101}])
        self.assertEqual(0, audit.audit(self.root)['summary']['unresolvedReferences'])

    def test_dynamic_number_is_not_claimed_as_missing_literal(self):
        self.trigger(PerformanceID_DS={'Key':'other'})
        result = audit.audit(self.root)['summary']
        self.assertEqual(1, result['dynamicPerformances'])
        self.assertEqual(0, result['unresolvedReferences'])

    def test_deleted_or_other_dimension_group_is_not_initial_default_candidate(self):
        self.trigger()
        for deleted, dimension in [(True,0),(False,2)]:
            self.placement(deleted=deleted,dimension=dimension)
            result = audit.audit(self.root)['summary']
            self.assertEqual(0,result['initiallyPlacedUnresolvedReferences'])
            self.assertEqual(0,result['activeDefaultMissingRaidPlacements'])

    def test_initial_broken_button_and_portal_are_reported(self):
        self.trigger()
        self.placement()
        result = audit.audit(self.root)['summary']
        self.assertEqual(1,result['initiallyPlacedUnresolvedReferences'])
        self.assertEqual(1,result['activeDefaultMissingRaidPlacements'])

    def test_farm_id_does_not_become_missing_raid(self):
        self.placement(prop=901)
        result = audit.audit(self.root)
        self.assertEqual([],result['missingRaidPlacements'])
        self.assertEqual(1,len(result['otherRaidIdNamespaces']))

    def test_ld_raid_is_recognized(self):
        self.placement()
        self.write('ExcelOutput/RaidConfigLD.json',[{'RaidID':201}])
        self.assertEqual([],audit.audit(self.root)['missingRaidPlacements'])

    def test_output_cannot_write_into_resources(self):
        with self.assertRaises(ValueError):
            audit.write_report(self.root,self.root/'report.json')


if __name__ == '__main__':
    unittest.main()
