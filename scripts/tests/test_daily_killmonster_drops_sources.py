"""原始 MonsterDrop 完整固定源证明；AvatarExpReward 不等于已验证的官方信用掉率。"""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


class DailyKillMonsterDropsSourcesTest(unittest.TestCase):
    def setUp(self):
        self.records = json.loads((ROOT / 'scripts/fixtures/daily-killmonster-drops-4.6.json').read_text('utf-8'))

    def test_all_retained_drop_rows_match_the_complete_fixed_git_blob(self):
        self.assertEqual(21, len(self.records))
        self.assertEqual({'resources/4.6/ExcelOutput/MonsterDrop.json'}, {r['source'] for r in self.records})
        raw = (ROOT / self.records[0]['source']).read_bytes()
        blob = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show',
            f'{PIN}:ExcelOutput/MonsterDrop.json'], check=True, capture_output=True).stdout
        installed = json.loads(raw)
        self.assertEqual(installed, json.loads(blob))
        seen = set()
        for record in self.records:
            self.assertEqual('monsterDrop', record['kind'])
            self.assertEqual(PIN, record['sourceCommit'])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), record['sha256'])
            self.assertEqual(hashlib.sha256(blob).hexdigest(), record['sourceBlobSHA256'])
            self.assertEqual(1, len(record['path']))
            index = record['path'][0]
            self.assertIsInstance(index, int)
            self.assertNotIn(index, seen)
            seen.add(index)
            self.assertEqual(record['row'], installed[index])

    def test_original_world_zero_has_avatar_exp_and_an_unspecified_coin_count(self):
        by_npc = {}
        for record in self.records:
            row = record['row']
            by_npc.setdefault(row['MonsterTemplateID'], []).append(row)
        self.assertEqual({8012010, 8012020, 8012030}, set(by_npc))
        for rows in by_npc.values():
            self.assertEqual(set(range(7)), {row.get('WorldLevel', 0) for row in rows})
            world_zero = next(row for row in rows if row.get('WorldLevel', 0) == 0)
            self.assertEqual(36, world_zero['AvatarExpReward'])
            self.assertEqual([{'ItemID': 2}, {'ItemID': 111011}, {'ItemID': 181003}], world_zero['DisplayItemList'])
            # The data declares item 2 without a count; it does not declare CoinReward=36.
            self.assertNotIn('CoinReward', world_zero)
            self.assertNotIn('ScoinReward', world_zero)


if __name__ == '__main__':
    unittest.main()
