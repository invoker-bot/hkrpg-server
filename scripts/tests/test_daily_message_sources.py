"""3000602 的有限来源证明；不把脚本存在或源码核对计作运行/客户端验收。"""
import hashlib
from decimal import Decimal
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
MISSING = {
    'Config/Level/NPCDialogue/P20002/F20002001_G61/DialogueMain_F20002001_G61_N400001.json',
    'Config/Level/NPCDialogue/P20002/F20002001_G61/DialoguePack_Transferred_F20002001_G61_N400001.json',
}


class DailyMessageSourcesTest(unittest.TestCase):
    def setUp(self):
        self.records = json.loads((ROOT / 'src/test/resources/fixtures/daily-message-3000602-4.6.json').read_text('utf-8'))

    def rows(self, kind):
        return [r['row'] for r in self.records if r['kind'] == kind]

    def test_full_blobs_fixed_commit_and_installed_missing_are_recorded_separately(self):
        self.assertEqual(124, len(self.records))
        cache = {}
        historical_missing = set()
        current_present = set()
        number = lambda value: ('json-number', Decimal(value))
        for record in self.records:
            self.assertEqual(PIN, record['sourceCommit'])
            relative = record['source'].removeprefix('resources/4.6/')
            if relative not in cache:
                blob = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'),
                                       'show', f'{PIN}:{relative}'], check=True, capture_output=True).stdout
                self.assertEqual(record['sourceBlobSHA256'], hashlib.sha256(blob).hexdigest())
                original = json.loads(blob)
                installed = ROOT / record['source']
                if record['installation'] == 'present':
                    self.assertTrue(installed.is_file())
                    raw = installed.read_bytes()
                    self.assertEqual(record['sha256'], hashlib.sha256(raw).hexdigest())
                else:
                    self.assertEqual('missing', record['installation'])
                    self.assertIsNone(record['sha256'])
                    self.assertIn(relative, MISSING)
                    historical_missing.add(relative)
                    if installed.is_file():
                        current_present.add(relative)
                if installed.is_file():
                    # Historical missing stays unchanged after add-only installation. Current data must
                    # still equal the entire original object, with bool distinct from JSON numbers.
                    self.assertEqual(json.loads(blob, parse_int=number, parse_float=number),
                                     json.loads(installed.read_bytes(), parse_int=number, parse_float=number), relative)
                cache[relative] = original, record['sourceBlobSHA256'], record['sha256']
            original, blob_sha, installed_sha = cache[relative]
            self.assertEqual((blob_sha, installed_sha), (record['sourceBlobSHA256'], record['sha256']))
            selected = original
            for component in record['path']:
                selected = selected[component]
            self.assertEqual(record['row'], selected, relative)
        self.assertEqual(39, len(cache))
        self.assertEqual(MISSING, historical_missing)
        self.assertIn(current_present, (set(), MISSING), 'The pair must remain absent or be completely installed and matching')

    def test_manual_quota_and_two_original_goals_preserve_the_authored_script_name(self):
        header = next(r for r in self.rows('main') if r['MainMissionID'] == 3000602)
        self.assertEqual(('Daily', 231, [{'Type': 'Manual'}], [{'Type': 'Auto'}]),
                         (header['Type'], header['RewardID'], header['TakeParam'], header['BeginParam']))
        catalog = next(r for r in self.rows('daily') if r['ID'] == 3000602)
        self.assertEqual((1, 30003, 1010201, 2100003),
                         (catalog['DailyMissionType'], catalog['GroupID'], catalog['UnlockMainMission'], catalog['QuestID']))
        self.assertEqual([{'ID': 1, 'DailyMissionType': 1, 'DailyCount': 1}], self.rows('count'))
        graph = next(r for r in self.rows('definition') if r['MainMissionID'] == 3000602)
        self.assertEqual({'MainMissionID': 3000602, 'StartSubMissionList': [300060201],
                          'FinishSubMissionList': [300060202]}, graph)
        subs = {r['ID']: r for r in self.rows('sub') if r['MainMissionID'] == 3000602}
        self.assertEqual({300060201, 300060202}, set(subs))
        self.assertEqual(('Auto', 'Talk', 'Mission_300060201', 1),
                         (subs[300060201]['TakeType'], subs[300060201]['FinishType'],
                          subs[300060201]['ParamStr1'], subs[300060201]['Progress']))
        goal = subs[300060202]
        self.assertEqual(('AnySequence', [300060201], 'MessageSectionFinish', 'Equal', 1211401, 1, True),
                         (goal['TakeType'], goal['TakeParamIntList'], goal['FinishType'], goal['ParamType'],
                          goal['ParamInt1'], goal['Progress'], goal['IsBackTrack']))
        self.assertTrue(goal['MissionJsonPath'].endswith('/Mission_300060203.json'))
        self.assertEqual((20002, 20002001, 20002001, 61, 400001),
                         (goal['LevelPlaneID'], goal['LevelFloorID'], goal['WayPointFloorID'],
                          goal['WayPointGroupID'], goal['WayPointEntityID']))

    def test_all_twelve_phone_nodes_have_exactly_four_complete_reply_paths(self):
        group = self.rows('messageGroup')[0]
        self.assertEqual({'ID': 11125, 'MessageContactsID': 48,
                          'MessageSectionIDList': [1211400, 1211401, 1211402]}, group)
        section = next(r for r in self.rows('messageSection') if r['ID'] == 1211401)
        self.assertEqual({'ID': 1211401, 'StartMessageItemIDList': [121140201]}, section)
        items = {r['ID']: r for r in self.rows('messageItem') if r['SectionID'] == 1211401}
        self.assertEqual(12, len(items))
        paths = []

        def visit(item_id, walked):
            self.assertNotIn(item_id, walked)
            row = items[item_id]
            self.assertEqual('Text', row['ItemType'])
            self.assertIn(row['Sender'], {'NPC', 'Player'})
            next_ids = row['NextItemIDList']
            self.assertEqual(len(next_ids), len(set(next_ids)))
            walked = walked + [item_id]
            if not next_ids:
                paths.append(walked)
            for next_id in next_ids:
                visit(next_id, walked)

        visit(121140201, [])
        self.assertEqual(4, len(paths))
        self.assertEqual({(121140202, 121140205), (121140202, 121140207),
                          (121140203, 121140205), (121140203, 121140207)},
                         {tuple(i for i in path if items[i]['Sender'] == 'Player') for path in paths})
        self.assertTrue(all(path[-1] == 121140210 and len(path) == 9 for path in paths))
        self.assertEqual(set(items), set().union(*(set(path) for path in paths)))

    def test_shared_npc_group_conditions_and_complete_original_scripts_are_distinct_from_phone_history(self):
        group = next(r for r in self.rows('group') if r['GroupName'] == 'NPCAML')
        self.assertEqual('Reset', group['SaveType'])
        self.assertNotIn('Category', group)
        self.assertNotIn('OwnerMainMissionID', group)
        self.assertEqual({'Conditions': [{'Phase': 'Finish', 'ID': 1000401}]}, group['LoadCondition'])
        self.assertEqual({'Conditions': [{'ID': 3000604}], 'DelayToLevelReload': True}, group['UnloadCondition'])
        self.assertEqual(1, len(group['NPCList']))
        npc = group['NPCList'][0]
        self.assertEqual((10206, 400001), (npc['NPCID'], npc['ID']))
        floor = self.rows('floor')[0]
        self.assertEqual(61, floor['GroupInstanceList'][49]['ID'])
        dimension = next(r for r in floor['DimensionList'] if r.get('ID', 0) == 0)
        self.assertIn(49, dimension['GroupIndexList'])
        first = next(r['row'] for r in self.records if r['kind'] == 'script'
                     and r['source'].endswith('/Mission_300060201.json'))
        self.assertEqual('RPG.GameCore.CreateNPC', first['OnStartSequece'][0]['TaskList'][0]['$type'])
        tail = first['OnStartSequece'][1]['TaskList']
        self.assertEqual(('RPG.GameCore.WaitCustomString', {'Value': '300060201_Finish'}),
                         (tail[0]['$type'], tail[0]['CustomString']))
        self.assertEqual(('RPG.GameCore.FinishPerformanceMission', 'Mission_300060201'),
                         (tail[-1]['$type'], tail[-1]['Key']))
        second = next(r['row'] for r in self.records if r['kind'] == 'script'
                      and r['source'].endswith('/Mission_300060203.json'))
        self.assertEqual([{'Order': 1, 'TaskList': [{'\u0024type': 'RPG.GameCore.DestroyNPC',
                                                    'GroupID': 61, 'GroupNpcID': 400001}]}], second['OnStartSequece'])
        self.assertEqual({'ID': 300060201, 'Type': 'submission_state_equal', 'Param1': 300060201, 'Param2': 1},
                         self.rows('dialogueCondition')[0])
        pack = next(r['row'] for r in self.records if r['kind'] == 'dialogueGraphSourceOnly'
                    and 'DialoguePack_Transferred' in r['source'])
        entries = pack['OnStartSequece'][0]['TaskList']
        source_menu = next(task for task in entries if task.get('Predicate', {}).get('SubMissionID') == 300060201)
        self.assertEqual('Started', source_menu['Predicate']['SubMissionState'])
        menu = source_menu['SuccessTaskList'][0]
        self.assertEqual((300060201, 300060201, 'Config/Level/Mission/3000602/Talk/Talk_300060201.json'),
                         (menu['MenuItemID'], menu['MissionID'], menu['DialoguePath']))
        act = self.rows('performanceGraph')[0]
        serialized = json.dumps(act)
        self.assertIn('300060201_Finish', serialized)
        self.assertIn('RPG.GameCore.TriggerCustomStringOnDialogEnd', serialized)
        self.assertIn('RPG.GameCore.EndPerformance', serialized)


if __name__ == '__main__':
    unittest.main()
