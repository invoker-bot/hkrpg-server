"""三条原始击怪委托的完整固定源、主维度组及客户端声明边界；不运行参考二进制。"""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
MAINS = {3000211, 3000212, 3000213}
GROUPS = {3000211: (20002, 20002001, 78, 62, 8012010, 20003221),
          3000212: (20002, 20002001, 79, 63, 8012020, 20003222),
          3000213: (20003, 20003001, 95, 54, 8012030, 20003223)}


class DailyKillMonsterSourcesTest(unittest.TestCase):
    def setUp(self):
        self.records = json.loads((ROOT / 'src/test/resources/fixtures/daily-killmonster-4.6.json').read_text('utf-8'))

    def rows(self, kind):
        return [record['row'] for record in self.records if record['kind'] == kind]

    def test_every_retained_row_has_its_full_source_resource_and_fixed_commit_proof(self):
        self.assertEqual(102, len(self.records))
        cached = {}
        reference = ROOT / 'references/TurnBasedGameData'
        for record in self.records:
            self.assertEqual(PIN, record['sourceCommit'])
            relative = record['source']
            if relative not in cached:
                raw = (ROOT / relative).read_bytes()
                self.assertEqual(record['sha256'], hashlib.sha256(raw).hexdigest())
                path = (ROOT / relative).relative_to(ROOT / 'resources/4.6').as_posix()
                blob = subprocess.run(['git', '-C', str(reference), 'show', f'{PIN}:{path}'],
                                      check=True, capture_output=True).stdout
                self.assertEqual(record['sourceBlobSHA256'], hashlib.sha256(blob).hexdigest())
                installed = json.loads(raw)
                self.assertEqual(installed, json.loads(blob), relative)
                cached[relative] = installed, record['sha256'], record['sourceBlobSHA256']
            installed, installed_sha, blob_sha = cached[relative]
            self.assertEqual((installed_sha, blob_sha), (record['sha256'], record['sourceBlobSHA256']))
            row = installed
            for key in record['path']:
                row = row[key]
            self.assertEqual(record['row'], row, relative)
        self.assertEqual(37, len(cached))

    def test_each_source_kill_target_is_the_unique_original_owned_main_dimension_monster(self):
        graphs = {row['MainMissionID']: row for row in self.rows('definition')}
        children = {row['ID']: row for row in self.rows('sub')}
        headers = {row['MainMissionID']: row for row in self.rows('main')}
        groups = {row['OwnerMainMissionID']: row for row in self.rows('group')}
        floors = {row['FloorID']: row for row in self.rows('floor')}
        self.assertEqual(MAINS, set(graphs))
        self.assertEqual(MAINS, set(groups))
        self.assertEqual(MAINS, {row['ID'] for row in self.rows('daily')})
        for main, (plane, floor, group, index, npc, event) in GROUPS.items():
            goal = children[main * 100 + 2]
            self.assertEqual(('KillMonster', 'Equal', 1, group, 200001),
                             (goal['FinishType'], goal['ParamType'], goal['Progress'], goal['ParamInt1'], goal['ParamInt2']))
            self.assertTrue(goal['IsBackTrack'])
            self.assertEqual((plane, floor, plane, floor),
                             (goal['LevelPlaneID'], goal['LevelFloorID'], goal['MazePlaneID'], goal['MazeFloorID']))
            original = groups[main]
            self.assertEqual(('Mission', 'Server', main),
                             (original['Category'], original['LoadSide'], original['OwnerMainMissionID']))
            self.assertEqual(1, len(original['MonsterList']))
            monster = original['MonsterList'][0]
            self.assertEqual((200001, npc, event), (monster['ID'], monster['NPCMonsterID'], monster['EventID']))
            self.assertEqual(group, floors[floor]['GroupInstanceList'][index]['ID'])
            dim = next(row for row in floors[floor]['DimensionList'] if row.get('ID', 0) == 0)
            self.assertIn(index, dim['GroupIndexList'])
            self.assertEqual([{'Type': 'Manual'}], headers[main]['TakeParam'])
            self.assertEqual(('Daily', 231), (headers[main]['Type'], headers[main]['RewardID']))
            self.assertEqual(7, len([row for row in self.rows('event') if row['EventID'] == event]))
            self.assertTrue(all(row['DropList'] == [] for row in self.rows('event') if row['EventID'] == event))

    def test_source_group_accept_conditions_and_ui_script_do_not_fabricate_server_trigger_battle(self):
        groups = {row['OwnerMainMissionID']: row for row in self.rows('group')}
        for main in MAINS:
            load = groups[main]['LoadCondition']
            if main == 3000213:
                self.assertEqual({'Conditions': [{'ID': main}]}, load)
            else:
                self.assertEqual('Or', load['Operation'])
                self.assertEqual([{'Type': 'SubMission', 'ID': main * 100 + 2},
                                  {'Type': 'SubMission', 'ID': main * 100 + 3}], load['Conditions'])
            self.assertEqual({'Conditions': [{'Phase': 'Finish', 'ID': main}]}, groups[main]['UnloadCondition'])
            self.assertEqual({'Conditions': [{'Phase': 'Cancel', 'ID': main}]}, groups[main]['ForceUnloadCondition'])
            record = next(row for row in self.records if row['kind'] == 'script'
                          and row['source'].endswith(f'Mission_{main * 100 + 2}.json'))
            self.assertIn('/3000201/', record['source'])
            text = json.dumps(record['row'])
            self.assertIn('RPG.GameCore.PropSetupUITrigger', text)
            self.assertIn('RPG.GameCore.TriggerPerformance', text)
            self.assertNotIn('RPG.GameCore.TriggerBattle', text)


if __name__ == '__main__':
    unittest.main()
