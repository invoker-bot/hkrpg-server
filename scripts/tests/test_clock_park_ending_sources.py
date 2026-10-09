"""完整来源表与Git对象身份核验；不代表真实游戏结局验收。"""
import base64
import gzip
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / 'src/test/resources/fixtures/clock-park-ending-reward-4.6.json'


class ClockParkEndingSourcesTest(unittest.TestCase):
    def setUp(self):
        self.data = json.loads(FIXTURE.read_text('utf-8'))

    def test_both_complete_tables_match_raw_and_original_git_blobs(self):
        self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b', self.data['sourceCommit'])
        self.assertEqual({'ExcelOutput/RewardData.json', 'ExcelOutput/ItemConfig.json'}, set(self.data['files']))
        for path, proof in self.data['files'].items():
            with self.subTest(path=path):
                raw = gzip.decompress(base64.b64decode(proof['gzipBase64'], validate=True))
                self.assertEqual(proof['length'], len(raw))
                self.assertEqual(proof['sha256'], hashlib.sha256(raw).hexdigest())
                canonical = raw.replace(b'\r\n', b'\n')
                self.assertEqual(proof['gitRawSHA256'], hashlib.sha256(canonical).hexdigest())
                blob = b'blob ' + str(len(canonical)).encode('ascii') + b'\0' + canonical
                self.assertEqual(proof['gitBlobSHA1'], hashlib.sha1(blob).hexdigest())
                self.assertGreater(len(json.loads(raw)), 1000)

    def test_selected_reward_is_a_real_single_original_item_recipe(self):
        path = 'ExcelOutput/RewardData.json'
        proof = self.data['files'][path]
        rows = json.loads(gzip.decompress(base64.b64decode(proof['gzipBase64'])))
        selected = [row for row in rows if row['RewardID'] == 8008001]
        self.assertEqual(1, len(selected))
        row = selected[0]
        self.assertEqual(self.data['rows'][path], row)
        self.assertEqual(140484, row['ItemID_1'])
        self.assertEqual(1, row['Count_1'])
        self.assertTrue(all(not value for key, value in row.items() if key.startswith('ItemID_') and key != 'ItemID_1'))

    def test_original_mission_item_really_has_a_unique_pile_limit(self):
        path = 'ExcelOutput/ItemConfig.json'
        proof = self.data['files'][path]
        rows = json.loads(gzip.decompress(base64.b64decode(proof['gzipBase64'])))
        selected = [row for row in rows if row['ID'] == 140484]
        self.assertEqual(1, len(selected))
        row = selected[0]
        self.assertEqual(self.data['rows'][path], row)
        self.assertEqual(1, row['PileLimit'])
        self.assertEqual('Mission', row['ItemMainType'])
        self.assertEqual('Mission', row['ItemSubType'])


if __name__ == '__main__':
    unittest.main()
