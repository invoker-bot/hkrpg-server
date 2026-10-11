"""核对40398原始文本/完整Git字节，并区分本服配方与官方缺失效果。"""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


class RogueDialogueRewardsSourcesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / 'src/test/resources/fixtures/rogue-dialogue-rewards-4.6.json').read_text('utf-8'))

    def test_complete_event_blobs_and_windows_resources_match_the_fixed_git_source(self):
        self.assertEqual(PIN, self.fixture['sourceCommit'])
        self.assertEqual(3, len(self.fixture['files']))
        for row in self.fixture['files']:
            raw = base64.b64decode(row['rawBase64'], validate=True)
            self.assertEqual(row['sha256'], hashlib.sha256(raw).hexdigest())
            installed = ROOT / 'resources/4.6' / row['path']
            if installed.is_file():
                self.assertEqual(raw, installed.read_bytes().replace(b'\r\n', b'\n'))
            reference = ROOT / 'references/TurnBasedGameData'
            if (reference / '.git').exists():
                original = subprocess.check_output(['git', 'show', f'{PIN}:{row["path"]}'], cwd=reference)
                self.assertEqual(raw, original)

    def test_source_option_display_rows_and_six_literal_chinese_texts_preserve_reward_categories(self):
        expected = [('给我一些启动资金吧', '获得一些宇宙碎片。'),
                    ('给我点好东西吧', '获得1个随机祝福。'), ('什么也不做', '你不需要。')]
        self.assertEqual([14101, 14102, 14103], [row['OptionDisplayID'] for row in self.fixture['displayRows']])
        actual = [(self.fixture['texts'][str(row['OptionTitle']['Hash'])],
                   self.fixture['texts'][str(row['OptionDesc']['Hash'])]) for row in self.fixture['displayRows']]
        self.assertEqual(expected, actual)
        reference = ROOT / 'references/TurnBasedGameData'
        if not (reference / '.git').exists():
            self.skipTest('Pinned reference checkout is not installed; intrinsic fixture checks still ran')
        raw = subprocess.check_output(['git', 'show', f'{PIN}:{self.fixture["textMapPath"]}'], cwd=reference)
        self.assertEqual(self.fixture['textMapSha256'], hashlib.sha256(raw).hexdigest())
        original = json.loads(raw)
        for key, text in self.fixture['texts'].items():
            self.assertEqual(text, original[key])
        displays = json.loads(subprocess.check_output(
            ['git', 'show', f'{PIN}:ExcelOutput/RogueDialogueOptionDisplay.json'], cwd=reference))
        retained = [row for row in displays if row['OptionDisplayID'] in {14101, 14102, 14103}]
        self.assertEqual(self.fixture['displayRows'], retained)

    def test_the_original_three_options_do_not_contain_a_recoverable_reward_formula(self):
        source = next(row for row in self.fixture['files'] if row['path'].endswith('/Opt40398.json'))
        options = json.loads(base64.b64decode(source['rawBase64']))['OptionList']
        self.assertEqual([{'OptionID': option, 'DisplayID': option} for option in [14101, 14102, 14103]], options)
        recipe = json.loads((ROOT / 'src/main/resources/defaults/RogueDialogueRewards.json').read_text('utf-8'))
        self.assertEqual({'schema': 1, 'eventId': 40398, 'enabled': True, 'fragmentReward': 100}, recipe)
        self.assertNotIn('miracle', recipe)


if __name__ == '__main__':
    unittest.main()
