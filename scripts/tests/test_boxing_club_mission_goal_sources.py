"""完整原始拳馆剧情目标、根声明、脚本和场景夹具的只读来源核对。"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


class BoxingClubMissionGoalSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / 'src/test/resources/fixtures/boxing-club-mission-goals-4.6.json').read_text('utf-8'))
        cls.raw = {entry['path']: subprocess.check_output(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', PIN + ':' + entry['path']]) for entry in cls.fixture['sourceFiles']}
        cls.tables = {path: json.loads(raw) for path, raw in cls.raw.items()}

    def test_all_twenty_original_blobs_and_twenty_six_complete_objects(self):
        x = self.fixture
        self.assertEqual(x['sourceCommit'], PIN)
        self.assertEqual(len(x['sourceFiles']), 20)
        self.assertEqual(len(self.raw), 20)
        self.assertEqual(len(x['rows']) + len(x['headers']) + len(x['mainRows']) + len(x['scripts']) + 1, 26)
        for entry in x['sourceFiles']:
            self.assertEqual(hashlib.sha256(self.raw[entry['path']]).hexdigest(), entry['blobSHA256'])
            resource = (ROOT / 'resources/4.6' / entry['path']).read_bytes()
            self.assertEqual(hashlib.sha256(resource).hexdigest(), entry['resourceSHA256'])
            self.assertEqual(json.loads(resource), self.tables[entry['path']])
        for row in x['rows']:
            main = row['MainMissionID']
            self.assertIn(row, self.tables[f'Config/Level/Mission/{main}/MissionInfo_{main}.partial.json']['SubMissionList'])
        for header in x['headers']:
            main = header['MainMissionID']
            self.assertEqual(header, self.tables[f'Config/Level/Mission/{main}/MissionInfo_{main}.json'])
        for row in x['mainRows']:
            self.assertIn(row, self.tables['ExcelOutput/MainMission.json'])

    def test_seven_goals_keep_original_zero_limits_and_distinct_backtracking(self):
        rows = {row['ID']: row for row in self.fixture['rows']}
        self.assertEqual(set(rows), {201073101, 201073201, 201073301, 201073401, 201073501, 801520101, 801520301})
        for id, row in rows.items():
            self.assertEqual(row['MainMissionID'], id // 100)
            self.assertEqual(row['FinishType'], 'BoxingClubAccomplish')
            self.assertEqual(row['ParamType'], 'LessEqual')
            self.assertNotIn('ParamInt1', row)
            self.assertEqual(row['Progress'], 1)
            self.assertEqual(row.get('IsBackTrack', False), id < 800000000)
            self.assertEqual(row['ParamInt2'], (id - 201073001) // 100 if id < 800000000 else 12)
            self.assertEqual((row['LevelPlaneID'], row['LevelFloorID']), (10102, 10102004))
        self.assertEqual(rows[801520101]['FinishActionList'], [{'FinishActionType': 'SetFloorSavedValue', 'FinishActionParaString': ['10102', '10102004', 'BoxClub2Hide', '1']}])

    def test_four_omitted_take_types_have_exact_original_start_and_finish_declarations(self):
        headers = {row['MainMissionID']: row for row in self.fixture['headers']}
        parents = {row['MainMissionID']: row for row in self.fixture['mainRows']}
        for row in self.fixture['rows'][:4]:
            id, main = row['ID'], row['MainMissionID']
            self.assertNotIn('TakeType', row)
            self.assertEqual(headers[main], {'MainMissionID': main, 'StartSubMissionList': [id], 'FinishSubMissionList': [id], 'IsShow': False})
            self.assertEqual(parents[main]['RewardID'], 100)
        self.assertEqual(headers[2010735]['FinishSubMissionList'], [201073502])
        self.assertEqual(headers[8015201]['FinishSubMissionList'], [801520105])
        self.assertEqual(headers[8015203]['FinishSubMissionList'], [801520302])

    def test_embedded_full_goal_objects_and_performance_scripts_equal_the_originals(self):
        x = self.fixture
        text = (ROOT / 'src/main/java/org/hkrpg/server/gameplay/BoxingClubMissionGoals.java').read_text('utf-8')
        blocks = re.findall(r'JsonParser\.parseString\(\s*"""\s*(.*?)\s*"""\s*\)', text, re.S)
        self.assertEqual(len(blocks), 2)
        self.assertEqual(json.loads(blocks[0]), x['scripts'])
        self.assertEqual(json.loads(blocks[1]), x['rows'])
        for id, script in x['scripts'].items():
            main = int(id) // 100
            self.assertEqual(script, self.tables[f'Config/Level/Mission/{main}/Mission_{id}.json'])
            self.assertEqual(script['OnInitSequece'], [])
            self.assertEqual(script['OnStartSequece'], [{'TaskList': [
                {'$type': 'RPG.GameCore.WaitCustomString', 'CustomString': {'Value': 'Talk_' + id}},
                {'$type': 'RPG.GameCore.FinishPerformanceMission', 'Key': 'Mission_' + id}]}])

    def test_scope_entry_matches_all_original_goals_and_declares_its_separate_prerequisite(self):
        entry = self.fixture['entry']
        self.assertIn(entry, self.tables['ExcelOutput/MapEntrance.json'])
        self.assertEqual((entry['ID'], entry['PlaneID'], entry['FloorID']), (1010204, 10102, 10102004))
        self.assertEqual(entry['FinishMainMissionList'], [1010302])
        self.assertEqual(entry['BeginMainMissionList'], [])
        self.assertEqual(entry['FinishSubMissionList'], [])
        self.assertFalse(self.fixture['clientGameplayVerified'])
        self.assertFalse(self.fixture['fullStoryScriptExecutorInstalled'])


if __name__ == '__main__':
    unittest.main()
