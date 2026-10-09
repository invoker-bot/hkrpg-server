"""拳馆三条短信的完整来源、九条可达分支和任务绑定；只读取原件。"""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


class BoxingClubMessageSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = json.loads((ROOT / 'src/test/resources/fixtures/boxing-club-messages-4.6.json').read_text('utf-8'))
        cls.raw = {r['path']: subprocess.check_output(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', PIN + ':' + r['path']]) for r in cls.data['sourceFiles']}
        cls.tables = {p: json.loads(raw) for p, raw in cls.raw.items()}

    def test_fifteen_blobs_and_fifty_whole_objects_match_the_pinned_source(self):
        x = self.data
        self.assertEqual(x['sourceCommit'], PIN)
        self.assertEqual(len(x['sourceFiles']), 15)
        self.assertEqual(sum(len(x[k]) for k in ['sections', 'groups', 'items', 'mainRows', 'headers', 'subs']) + 3, 50)
        for r in x['sourceFiles']:
            resource = (ROOT / 'resources/4.6' / r['path']).read_bytes()
            self.assertEqual(hashlib.sha256(resource).hexdigest(), r['resourceSHA256'])
            self.assertEqual(hashlib.sha256(self.raw[r['path']]).hexdigest(), r['blobSHA256'])
            self.assertEqual(json.loads(resource), self.tables[r['path']])
        for key, table in [('sections', 'MessageSectionConfig'), ('groups', 'MessageGroupConfig'), ('items', 'MessageItemConfig'), ('mainRows', 'MainMission')]:
            for row in x[key]:
                self.assertIn(row, self.tables[f'ExcelOutput/{table}.json'])
        for row in x['subs']:
            main = row['MainMissionID']
            self.assertIn(row, self.tables[f'Config/Level/Mission/{main}/MissionInfo_{main}.partial.json']['SubMissionList'])
        for row in x['headers']:
            main = row['MainMissionID']
            self.assertEqual(row, self.tables[f'Config/Level/Mission/{main}/MissionInfo_{main}.json'])
        self.assertEqual(x['talkScript'], self.tables['Config/Level/Mission/2010702/Mission_201070201.json'])
        self.assertIn(x['rewardRow'], self.tables['ExcelOutput/RewardData.json'])
        self.assertIn(x['entry'], self.tables['ExcelOutput/MapEntrance.json'])
        self.assertEqual(x['entry']['FinishMainMissionList'], [1010302])

    def test_nine_reachable_original_paths_exclude_the_two_unreachable_system_items(self):
        rows = {r['ID']: r for r in self.data['items']}
        found = {}

        def paths(section, id, visited=()):
            self.assertNotIn(id, visited)
            row = rows[id]
            self.assertEqual(row['SectionID'], section)
            at = (*visited, id)
            if not row['NextItemIDList']:
                return [at]
            return [path for next_id in row['NextItemIDList'] for path in paths(section, next_id, at)]

        for r in self.data['sections']:
            found[r['ID']] = [p for id in r['StartMessageItemIDList'] for p in paths(r['ID'], id)]
        self.assertEqual({id: len(p) for id, p in found.items()}, {1110000: 3, 1110700: 4, 1301100: 2})
        reached = {id for paths in found.values() for path in paths for id in path}
        self.assertEqual(set(rows) - reached, {999990041, 999990048})
        self.assertFalse(self.data['clientGameplayVerified'])
        self.assertFalse(self.data['fullStoryScriptExecutorInstalled'])

    def test_original_owner_mismatch_is_resolved_by_an_accepted_goal_and_season_two_keeps_its_window(self):
        sections = {r['ID']: r for r in self.data['sections']}
        subs = {r['ID']: r for r in self.data['subs']}
        self.assertEqual(sections[1110700]['MainMissionLink'], 2010703)
        self.assertEqual(subs[201070202]['MainMissionID'], 2010702)
        self.assertEqual(subs[201070202]['TakeType'], 'AnySequence')
        self.assertEqual(subs[201070202]['TakeParamIntList'], [201070201])
        self.assertEqual(subs[201070202]['ParamInt1'], 1110700)
        self.assertEqual(subs[201070201]['ParamStr1'], 'Mission_201070201')
        self.assertEqual(self.data['talkScript']['OnStartSequece'], [{'TaskList': [{'$type': 'RPG.GameCore.FinishPerformanceMission', 'Key': 'Mission_201070201'}]}])
        self.assertEqual(next(r for r in self.data['groups'] if r['ID'] == 13011)['ActivityModuleID'], 4000301)
        self.assertEqual(self.data['rewardRow'], {'RewardID': 100})


if __name__ == '__main__':
    unittest.main()
