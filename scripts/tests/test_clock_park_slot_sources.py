"""完整抽奖表来源及中奖权重核验；不代表原版抽卡分配算法验收。"""
import base64
import gzip
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ClockParkSlotSourcesTest(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((ROOT / 'src/test/resources/fixtures/clock-park-slot-4.6.json').read_text('utf-8'))
        self.proof = self.data['files']['ExcelOutput/ClockParkLottery.json']
        self.raw = gzip.decompress(base64.b64decode(self.proof['gzipBase64'], validate=True))

    def test_complete_table_matches_pinned_git_object(self):
        self.assertEqual('https://github.com/DimbreathBot/TurnBasedGameData', self.data['sourceRepository'])
        self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b', self.data['sourceCommit'])
        self.assertEqual({'ExcelOutput/ClockParkLottery.json'}, set(self.data['files']))
        self.assertEqual(self.proof['length'], len(self.raw))
        self.assertEqual(self.proof['sha256'], hashlib.sha256(self.raw).hexdigest())
        blob = b'blob ' + str(len(self.raw)).encode('ascii') + b'\0' + self.raw
        self.assertEqual(self.proof['gitBlobSHA1'], hashlib.sha1(blob).hexdigest())
        self.assertEqual(12, len(json.loads(self.raw)))

    def test_original_four_rewards_and_weights_are_not_placeholder_values(self):
        rows = {r['LotteryID']: r for r in json.loads(self.raw)}
        for identifier, gain, weight, kind in [(42, 4, 1, 4), (32, 6, 9, 3), (22, 8, 640, 2), (12, 12, 350, 1)]:
            with self.subTest(lottery=identifier):
                self.assertEqual({'AttributeB': gain}, rows[identifier]['LotteryAttributeGain'])
                self.assertEqual(weight, rows[identifier]['Weight'])
                self.assertEqual(kind, rows[identifier]['LotteryType'])
        self.assertEqual(1000, sum(rows[i]['Weight'] for i in [42, 32, 22, 12]))


if __name__ == '__main__':
    unittest.main()
