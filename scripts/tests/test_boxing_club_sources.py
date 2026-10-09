"""拳馆夹具和逐行来源必须能从锁定Git blob独立复核，不以候选代码作为来源。"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


class BoxingClubSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / 'src/test/resources/fixtures/boxing-club-rounds-4.6.json').read_text('utf-8'))

    def test_all_selected_rows_are_complete_members_of_twelve_original_git_blobs(self):
        self.assertEqual(PIN, self.fixture['sourceCommit'])
        self.assertEqual(12, len(self.fixture['inputs']))
        count = 0
        for entry in self.fixture['inputs']:
            raw = subprocess.check_output(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', PIN + ':' + entry['path']])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), entry['blobSHA256'])
            original = json.loads(raw)
            for row in entry['rows']:
                self.assertIn(row, original, entry['path'])
            count += len(entry['rows'])
        self.assertEqual(394, count)

    def test_all_305_runtime_fingerprints_match_complete_original_binding_rows(self):
        source = (ROOT / 'src/main/java/org/hkrpg/server/gameplay/BoxingClubSourceHashes.java').read_text('utf-8')
        actual = dict(re.findall(r'Map\.entry\(\s*"([^"]+)"\s*,\s*"([a-f0-9]{64})"\)', source))
        expected = {}
        keys = {'PlaneEvent': ('EventID', 'WorldLevel'), 'StageConfig': ('StageID', None), 'MazeBuff': ('ID', 'Lv'), 'RewardData': ('RewardID', None), 'ActivityPanel': ('PanelID', None), 'ActivityConfig': ('ActivityID', None)}
        for entry in self.fixture['inputs']:
            table = Path(entry['path']).stem
            if table not in keys:
                continue
            identity, second = keys[table]
            for row in entry['rows']:
                key = table + ':' + str(row[identity])
                if second:
                    key += '/' + str(row.get(second, 1 if second == 'Lv' else 0))
                expected[key] = hashlib.sha256(json.dumps(row, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()
        self.assertEqual(305, len(expected))
        self.assertEqual(expected, actual)

    def test_original_event_world_and_party_reset_settings_are_complete(self):
        tables = {Path(entry['path']).stem: entry['rows'] for entry in self.fixture['inputs']}
        self.assertEqual(list(range(1, 6)), [row['ChallengeID'] for row in tables['BoxingClubChallenge']])
        self.assertTrue(all(row['Type'] == 'First' and row['ChallengeTurnLimit'] == 20 for row in tables['BoxingClubChallenge']))
        self.assertEqual(140, len(tables['PlaneEvent']))
        self.assertEqual(140, len(tables['StageConfig']))
        self.assertEqual({row['StageID'] for row in tables['PlaneEvent']}, {row['StageID'] for row in tables['StageConfig']})
        for row in tables['StageConfig']:
            self.assertEqual('BoxingClub', row['StageType'])
            self.assertTrue(row['ForbidExitBattle'])
            self.assertEqual(['StageAbility_SetFullHPandHalfSP'], row['StageAbilityConfig'])
        self.assertEqual({1, 2, 213, 222}, {row['ID'] for row in tables['ItemConfig']})


if __name__ == '__main__':
    unittest.main()
