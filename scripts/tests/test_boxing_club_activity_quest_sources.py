"""两季50项入口关联任务、非周期模块归属、隐藏前置与原始奖励完整来源核对。"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
IDS = set(range(6000060, 6000081)) | set(range(6000369, 6000398))


class BoxingClubActivityQuestSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / 'src/test/resources/fixtures/boxing-club-activity-quests-4.6.json').read_text('utf-8'))
        cls.tables = {Path(e['path']).stem: e['rows'] for e in cls.fixture['inputs']}

    def test_eleven_fixed_git_blobs_contain_all_two_hundred_twenty_six_complete_rows(self):
        self.assertEqual(PIN, self.fixture['sourceCommit'])
        self.assertEqual(11, len(self.fixture['inputs']))
        self.assertEqual(226, sum(len(e['rows']) for e in self.fixture['inputs']))
        for entry in self.fixture['inputs']:
            raw = subprocess.check_output(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', PIN + ':' + entry['path']])
            self.assertEqual(entry['blobSHA256'], hashlib.sha256(raw).hexdigest())
            original = json.loads(raw)
            for row in entry['rows']:
                self.assertIn(row, original)

    def test_runtime_fingerprints_preserve_every_complete_original_row(self):
        keys = {'QuestData': 'QuestID', 'FinishWay': 'ID', 'CycleQuest': 'CycleID', 'RewardData': 'RewardID', 'ItemConfig': 'ID', 'ActivityConfig': 'ActivityID', 'ActivityPanel': 'PanelID', 'BoxingClubActivityQuest': 'ID', 'BoxingClubChallengeSeason': 'SeasonID', 'BoxingClubConstValueClient': 'ConstValueName', 'ConstValueClient': 'ConstValueName'}
        expected = {}
        for table, rows in self.tables.items():
            for row in rows:
                expected[table + ':' + str(row[keys[table]])] = hashlib.sha256(json.dumps(row, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()
        text = (ROOT / 'src/main/java/org/hkrpg/server/gameplay/BoxingClubActivityQuestSources.java').read_text('utf-8')
        actual = dict(re.findall(r'Map\.entry\(\s*"([^"]+)"\s*,\s*"([a-f0-9]{64})"', text))
        self.assertEqual(226, len(expected))
        self.assertEqual(expected, actual)

    def test_all_fifty_quests_belong_to_their_original_nonperiodic_temporary_modules(self):
        quests = {r['QuestID']: r for r in self.tables['QuestData']}
        cycles = {r['CycleID']: r for r in self.tables['CycleQuest']}
        self.assertEqual(IDS, set(quests))
        self.assertEqual(IDS, set(cycles))
        configs = {r['ActivityID']: r for r in self.tables['ActivityConfig']}
        for id in IDS:
            q, c = quests[id], cycles[id]
            self.assertEqual(3, q['QuestType'])
            self.assertEqual([id], c['QuestList'])
            self.assertTrue(c['IsNonPeriodic'])
            self.assertNotIn('Cycledays', c)
            self.assertNotIn('ScheduleDataID', c)
            self.assertEqual((1, 999), (c['MinLevel'], c['MaxLevel']))
            self.assertEqual(list(range(1, 8)), c['WeekDayList'])
            panel, module = (50000, 5000001) if id < 6000300 else (40003, 4000308)
            self.assertEqual(module, c['ActivityModuleID'])
            self.assertIn(module, configs[panel]['ActivityModuleIDList'])
            self.assertNotIn(module, configs[panel]['ResidentModuleList'])
        self.assertNotIn(6000399, quests)

    def test_original_entry_lists_and_both_finals_include_the_actual_hidden_children(self):
        finishes = {r['ID']: r for r in self.tables['FinishWay']}
        self.assertEqual(6000075, self.tables['ConstValueClient'][0]['Value']['IntValue'])
        self.assertEqual(6000397, self.tables['BoxingClubConstValueClient'][0]['Value']['IntValue'])
        for id, children in [(6000075, list(range(6000076, 6000081))), (6000397, list(range(6000369, 6000394, 4)))]:
            f = finishes[id]
            self.assertEqual(('FinishQuest', 'ListContain', len(children)), (f['FinishType'], f['ParamType'], f['Progress']))
            self.assertEqual(children, f['ParamIntList'])
        for i in range(5):
            f = finishes[6000076 + i]
            self.assertEqual(i + 1, f['ParamInt2'])
            self.assertNotIn('ParamInt1', f)
        for i in range(7):
            f = finishes[6000369 + i * 4]
            self.assertEqual((9, 6 + i), (f['ParamInt1'], f['ParamInt2']))
        self.assertEqual(12, len(self.tables['BoxingClubActivityQuest']))
        for row in self.tables['BoxingClubActivityQuest']:
            challenge = row['ChallengeID']
            base = 6000060 + (challenge - 1) * 3 if challenge <= 5 else 6000370 + (challenge - 6) * 4
            self.assertEqual([base, base + 1, base + 2], row['QuestList'])

    def test_all_original_nonempty_reward_recipes_have_exact_separate_season_totals(self):
        rewards = {r['RewardID']: r for r in self.tables['RewardData']}
        self.assertEqual({'RewardID': 100}, rewards[100])
        expected = [{1: 500, 2: 100000, 213: 24, 222: 18, 232: 29, 223: 16, 212: 12, 241: 1},
                    {1: 500, 2: 210000, 213: 21, 223: 21, 233: 21, 235: 140, 241: 1} | {i: 3 for i in [110112,110122,110132,110142,110152,110162,110172]}]
        for first, totals, nonempty in [(True, expected[0], 16), (False, expected[1], 22)]:
            actual, count = {}, 0
            for q in self.tables['QuestData']:
                if (q['QuestID'] < 6000300) != first:
                    continue
                row = rewards[q['RewardID']]
                count += len(row) > 1
                if row.get('Hcoin', 0):
                    actual[1] = actual.get(1, 0) + row['Hcoin']
                for key, item in row.items():
                    if key.startswith('ItemID_') and item:
                        actual[item] = actual.get(item, 0) + row['Count_' + key.split('_')[1]]
            self.assertEqual(nonempty, count)
            self.assertEqual(totals, actual)
        self.assertEqual(set().union(*[set(e) for e in expected]), {r['ID'] for r in self.tables['ItemConfig']})


if __name__ == '__main__':
    unittest.main()
