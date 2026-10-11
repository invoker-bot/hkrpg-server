"""核对十五条原始委托、所属主任务 Auto 门和两条实际 EnterMap 声明。"""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
MAINS = set(range(3020101, 3020109)) | {3020201, 3020202, 3020203, 3020205, 3020206, 3012001, 3020204}


class DailyTravelSourcesTest(unittest.TestCase):
    def setUp(self):
        self.records = json.loads((ROOT / 'src/test/resources/fixtures/daily-travel-4.6.json').read_text('utf-8'))

    def rows(self, kind):
        return [record['row'] for record in self.records if record['kind'] == kind]

    def test_every_retained_row_matches_its_complete_original_resource(self):
        self.assertEqual(142, len(self.records))
        files = {}
        proofs = {}
        reference = ROOT / 'references/TurnBasedGameData'
        for record in self.records:
            self.assertEqual(PIN, record['sourceCommit'])
            self.assertRegex(record['sourceBlobSHA256'], r'^[0-9a-f]{64}$')
            path = ROOT / record['source']
            if not path.is_file():
                self.skipTest('Ignored 4.6 operator resources are not installed in this checkout')
            if path not in files:
                raw = path.read_bytes()
                self.assertEqual(record['sha256'], hashlib.sha256(raw).hexdigest())
                files[path] = json.loads(raw)
                proofs[path] = (record['sha256'], record['sourceBlobSHA256'])
                if (reference / '.git').exists():
                    relative = path.relative_to(ROOT / 'resources/4.6').as_posix()
                    blob = subprocess.run(['git', '-C', str(reference), 'show', f'{PIN}:{relative}'],
                                          check=True, capture_output=True).stdout
                    self.assertEqual(record['sourceBlobSHA256'], hashlib.sha256(blob).hexdigest())
                    self.assertEqual(files[path], json.loads(blob), relative)
            self.assertEqual(proofs[path], (record['sha256'], record['sourceBlobSHA256']))
            value = files[path]
            for key in record['path']:
                value = value[key]
            self.assertEqual(record['row'], value, record['source'])
        self.assertEqual(42, len(files))

    def test_fifteen_source_start_goals_name_only_their_own_manual_main_mission(self):
        graphs = {row['MainMissionID']: row for row in self.rows('definition')}
        children = {row['ID']: row for row in self.rows('sub')}
        headers = {row['MainMissionID']: row for row in self.rows('main')}
        self.assertEqual(MAINS, set(graphs))
        self.assertEqual(MAINS, {row['ID'] for row in self.rows('daily')})
        for main, graph in graphs.items():
            self.assertEqual(1, len(graph['StartSubMissionList']))
            first = children[graph['StartSubMissionList'][0]]
            self.assertEqual(('Auto', [main], main), (first['TakeType'], first['TakeParamIntList'], first['MainMissionID']))
            self.assertEqual('Daily', headers[main]['Type'])
            self.assertEqual([{'Type': 'Manual'}], headers[main]['TakeParam'])
            self.assertEqual(231, headers[main]['RewardID'])
        self.assertEqual([{'ID': 1, 'DailyMissionType': 1, 'DailyCount': 1}], self.rows('count'))
        self.assertEqual([{'QuestID': 2100003, 'Type': 1}], self.rows('pool'))

    def test_travel_goals_have_literal_source_entrances_and_keep_original_completion_floors(self):
        goals = {row['ID']: row for row in self.rows('sub') if row['FinishType'] == 'EnterFloor'}
        entrances = {row['ID']: row for row in self.rows('entrance')}
        self.assertEqual({301200108, 302020403}, set(goals))
        for script in self.rows('script'):
            self.assertEqual([], script['OnInitSequece'])
            self.assertEqual(1, len(script['OnStartSequece']))
            tasks = script['OnStartSequece'][0]['TaskList']
            self.assertEqual(1, len(tasks))
            task = tasks[0]
            self.assertEqual('RPG.GameCore.EnterMap', task['$type'])
            row = goals[301200108 if task['EntranceID'] == 1010203 else 302020403]
            entry = entrances[task['EntranceID']]
            self.assertEqual((row['ParamInt1'], row['ParamInt2']), (entry['PlaneID'], entry['FloorID']))
            self.assertNotEqual(row['LevelFloorID'], row['ParamInt2'])
            self.assertEqual((1, 'Equal'), (row['Progress'], row['ParamType']))
            self.assertFalse(row.get('IsBackTrack', False))
        ending = next(row for row in self.rows('sub') if row['ID'] == 302020404)
        self.assertEqual(10202004, ending['LevelFloorID'])


if __name__ == '__main__':
    unittest.main()
