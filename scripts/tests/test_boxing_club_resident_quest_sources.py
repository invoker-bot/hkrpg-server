"""常驻拳馆32项任务、复用结束条件、原始奖励和物品完整来源核对。"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


class BoxingClubResidentQuestSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / 'src/test/resources/fixtures/boxing-club-resident-quests-4.6.json').read_text('utf-8'))
        cls.tables = {Path(e['path']).stem: e['rows'] for e in cls.fixture['inputs']}

    def test_five_pinned_git_blobs_contain_all_seventy_three_complete_rows(self):
        self.assertEqual(PIN, self.fixture['sourceCommit'])
        self.assertEqual(5, len(self.fixture['inputs']))
        self.assertEqual(73, sum(len(e['rows']) for e in self.fixture['inputs']))
        for entry in self.fixture['inputs']:
            raw = subprocess.check_output(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', PIN + ':' + entry['path']])
            self.assertEqual(entry['blobSHA256'], hashlib.sha256(raw).hexdigest())
            original = json.loads(raw)
            for row in entry['rows']:
                self.assertIn(row, original)

    def test_runtime_fingerprints_preserve_complete_original_rows(self):
        keys = {'QuestData': 'QuestID', 'FinishWay': 'ID', 'RewardData': 'RewardID', 'ItemConfig': 'ID', 'BoxingClubConstValueClient': 'ConstValueName'}
        expected = {}
        for table, rows in self.tables.items():
            for row in rows:
                expected[table + ':' + str(row[keys[table]])] = hashlib.sha256(json.dumps(row, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()
        source = (ROOT / 'src/main/java/org/hkrpg/server/gameplay/BoxingClubResidentQuestSources.java').read_text('utf-8')
        actual = dict(re.findall(r'Map\.entry\(\s*"([^"]+)"\s*,\s*"([a-f0-9]{64})"', source))
        self.assertEqual(73, len(expected))
        self.assertEqual(expected, actual)

    def test_resident_goals_use_the_original_shared_finish_ids_and_aggregate_lists(self):
        quests = {r['QuestID']: r for r in self.tables['QuestData']}
        finishes = {r['ID']: r for r in self.tables['FinishWay']}
        self.assertEqual({6000398} | set(range(6000405, 6000436)), set(quests))
        self.assertTrue(all(q['QuestType'] == 1 and q['UnlockType'] == 'AutoUnlock' and q['UnlockParamList'] == [] for q in quests.values()))
        self.assertEqual([6000398, 6000412, 6000413, 6000414], [r['IntValue'] for r in self.tables['BoxingClubConstValueClient'][0]['Value']['ArrayValue']])
        for first, source_ids, limit in [(6000422, [6000372,6000376,6000380,6000384,6000388,6000392,6000396], 6), (6000429, [6000371,6000375,6000379,6000383,6000387,6000391,6000395], 7)]:
            for i, source in enumerate(source_ids):
                self.assertEqual(source, quests[first + i]['FinishWayID'])
                self.assertEqual((limit, 6 + i), (finishes[source]['ParamInt1'], finishes[source]['ParamInt2']))
        for i in range(7):
            self.assertNotIn('ParamInt1', finishes[6000405 + i])
            self.assertEqual((5, 6 + i), (finishes[6000415 + i]['ParamInt1'], finishes[6000415 + i]['ParamInt2']))
        for quest, first, goal in [(6000398,6000405,7), (6000412,6000429,6), (6000413,6000422,5), (6000414,6000415,4)]:
            row = finishes[quest]
            self.assertEqual('FinishQuest', row['FinishType'])
            self.assertEqual('ListContain', row['ParamType'])
            self.assertEqual(goal, row['Progress'])
            self.assertEqual(list(range(first, first + 7)), row['ParamIntList'])

    def test_four_original_reward_recipes_total_exactly_one_hundred_jade_and_twelve_materials(self):
        rewards = {r['RewardID']: r for r in self.tables['RewardData']}
        self.assertEqual({'RewardID': 100}, rewards[100])
        expected = {26000398: (50,40000,4), 26000412: (50,40000,4), 26000413: (0,30000,2), 26000414: (0,10000,2)}
        total = [0,0,0]
        for key, amounts in expected.items():
            row = rewards[key]
            self.assertEqual((2,213), (row['ItemID_1'], row['ItemID_2']))
            self.assertEqual(amounts, (row.get('Hcoin', 0), row['Count_1'], row['Count_2']))
            total = [a + b for a,b in zip(total, amounts)]
        self.assertEqual([100,120000,12], total)
        self.assertEqual({1,2,213}, {r['ID'] for r in self.tables['ItemConfig']})


if __name__ == '__main__':
    unittest.main()
