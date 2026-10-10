"""完整原始时间轴目标/物件/根定义及本机只读协议证据的固定来源核验。"""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


class PropTimelineSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / 'src/test/resources/fixtures/prop-timeline-4.6.json').read_text('utf-8'))
        cls.raw = {path: subprocess.check_output(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', PIN + ':' + path]) for path in cls.fixture['sourceFiles']}
        cls.data = {path: json.loads(raw) for path, raw in cls.raw.items()}

    def test_twenty_nine_source_blobs_and_local_resources_have_matching_provenance(self):
        self.assertEqual(self.fixture['sourceCommit'], PIN)
        self.assertEqual(len(self.raw), 29)
        for path, entry in self.fixture['sourceFiles'].items():
            self.assertEqual(hashlib.sha256(self.raw[path]).hexdigest(), entry['gitBlobSHA256'])
            resource = (ROOT / 'resources/4.6' / path).read_bytes()
            self.assertEqual(hashlib.sha256(resource).hexdigest(), entry['checkoutSHA256'])
            self.assertEqual(json.loads(resource), self.data[path])

    def test_all_nine_complete_original_goals_and_two_linked_actions_are_preserved(self):
        self.assertEqual({r['ID'] for r in self.fixture['subs']}, {104010366, 104010523, 104010572, 104061121, 104061152, 104061020, 104040816, 104370627, 204020313})
        self.assertEqual({r['ID'] for r in self.fixture['linkedSubs']}, {104061137, 104061038})
        for row in [*self.fixture['subs'], *self.fixture['linkedSubs']]:
            path = f"Config/Level/Mission/{row['MainMissionID']}/MissionInfo_{row['MainMissionID']}.partial.json"
            self.assertEqual(row, next(r for r in self.data[path]['SubMissionList'] if r['ID'] == row['ID']))
        for row in self.fixture['subs']:
            self.assertEqual(row['FinishType'], 'TimeLineSetState')
            self.assertEqual(row['ParamType'], 'Equal')
            self.assertTrue(row['IsBackTrack'])

    def test_whole_headers_main_rows_and_original_guide_script_are_preserved(self):
        self.assertEqual(len(self.fixture['headers']), 7)
        for header in self.fixture['headers']:
            main = header['MainMissionID']
            self.assertEqual(header, self.data[f'Config/Level/Mission/{main}/MissionInfo_{main}.json'])
        for row in [*self.fixture['mainRows'], *self.fixture['raidParentRows']]:
            self.assertEqual(row, next(r for r in self.data['ExcelOutput/MainMission.json'] if r['MainMissionID'] == row['MainMissionID']))
        self.assertEqual(self.fixture['scripts']['104370627'], self.data['Config/Level/Mission/1043706/Mission_104370627.json'])

    def test_all_nine_original_groups_include_the_exact_tagged_target_prop(self):
        self.assertEqual(len(self.fixture['groups']), 9)
        for group in self.fixture['groups']:
            self.assertEqual(group['definition'], self.data[group['path']])
        for goal in self.fixture['subs']:
            path = f"Config/LevelOutput/SharedRuntimeGroup/Groups_P{goal['LevelPlaneID']}_F{goal['LevelFloorID']}/LevelGroup_P{goal['LevelPlaneID']}_F{goal['LevelFloorID']}_G{goal['ParamInt1']}.json"
            props = [p for p in self.data[path]['PropList'] if p['ID'] == goal['ParamInt2']]
            self.assertEqual(len(props), 1)
            tags = [t for t in props[0]['TagOverrideComponents']['TagComponents'] if t['$type'] == 'RPG.GameCore.PropTagTimeRewindEntityComponent']
            self.assertEqual(len(tags), 1)
            self.assertEqual(tags[0]['Tag'], {'Tag': 'Prop.Component.TimeRewindEntity', 'TagHash': 532483231})

    def test_whole_original_tables_and_two_raid_ownership_rows_are_preserved(self):
        for key, table, id_key in [('propRows', 'MazeProp', 'ID'), ('planeRows', 'MazePlane', 'PlaneID'), ('entryRows', 'MapEntrance', 'ID'), ('raidRows', 'RaidConfig', 'RaidID')]:
            for row in self.fixture[key]:
                self.assertIn(row, self.data['ExcelOutput/' + table + '.json'])
        raid_mains = {r['MainMissionID'] for r in self.fixture['mainRows'] if r.get('IsInRaid')}
        self.assertEqual(raid_mains, {1040408, 2040203})

    def test_native_ascii_sender_is_separate_from_client_acceptance(self):
        evidence = self.fixture['clientEvidence']
        self.assertEqual(evidence['nativeSendCommand'], 1483)
        self.assertEqual(evidence['nativeEncoding'], 'ASCIIEncoding')
        self.assertEqual(evidence['nativeSenderMethod'], 635279)
        self.assertEqual(evidence['nativeFieldNumbers']['state'], {'boolAt32': 1, 'bytesAt24': 3})
        self.assertEqual(evidence['nativeRequestInteractIdField'], 8)
        self.assertEqual(evidence['referenceServer']['license'], 'AGPL-3.0')
        self.assertFalse(evidence['clientExecutableStarted'])
        self.assertFalse(evidence['clientModified'])
        self.assertFalse(evidence['clientAccepted'])
        self.assertFalse(self.fixture['clientGameplayVerified'])


if __name__ == '__main__':
    unittest.main()
