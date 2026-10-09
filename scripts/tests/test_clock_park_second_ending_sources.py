"""核对完整固定来源中的第二剧本结局、奖励及唯一物品；不代表客户端验收。"""
import base64
import gzip
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ClockParkSecondEndingSourcesTest(unittest.TestCase):
    def setUp(self):
        fixture = ROOT / 'src/test/resources/fixtures'
        self.catalog = json.loads((fixture / 'clock-park-catalog-4.6.json').read_text('utf-8'))
        self.rewards = json.loads((fixture / 'clock-park-ending-reward-4.6.json').read_text('utf-8'))

    def table(self, name):
        proof = self.rewards['files']['ExcelOutput/' + name + '.json']
        return json.loads(gzip.decompress(base64.b64decode(proof['gzipBase64'], validate=True)))

    def test_original_two_outcomes_have_their_own_story_and_reward_identity(self):
        rows = json.loads(bytes.fromhex(self.catalog['files']['ExcelOutput/ClockParkChapterConfig.json']['rawHex']))
        for ending, kind, reward, progress, story in [(205, 'TrueEnding', 8008002, 2000, 20501), (206, 'FalseEnding', 100, 0, 20601)]:
            row = next(v for v in rows if v['ChapterID'] == ending)
            with self.subTest(ending=ending):
                self.assertEqual(kind, row['ChapterType'])
                self.assertEqual(reward, row['RewardID'])
                self.assertEqual(progress, row.get('RewardProgress', 0))
                self.assertEqual([story], row['ChapterStoryIDList'])

    def test_original_true_reward_is_exactly_one_140483_and_false_reward_is_empty(self):
        rows = self.table('RewardData')
        true = next(v for v in rows if v['RewardID'] == 8008002)
        false = next(v for v in rows if v['RewardID'] == 100)
        items = lambda row: [(value, row.get('Count_' + key.split('_')[1], 0)) for key, value in row.items() if key.startswith('ItemID_') and value]
        self.assertEqual([(140483, 1)], items(true))
        self.assertEqual([], items(false))

    def test_second_true_ending_item_is_original_unique_mission_material(self):
        row = next(v for v in self.table('ItemConfig') if v['ID'] == 140483)
        self.assertEqual('Mission', row['ItemMainType'])
        self.assertEqual('Mission', row['ItemSubType'])
        self.assertEqual(1, row['PileLimit'])
        self.assertFalse(row.get('UseMethod'))


if __name__ == '__main__':
    unittest.main()
