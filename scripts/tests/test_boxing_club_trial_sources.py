"""核对首季试用角色世界等级、原始技能预设及完整来源行。"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


class BoxingClubTrialSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / 'src/test/resources/fixtures/boxing-club-trials-4.6.json').read_text('utf-8'))

    def test_all_rows_and_presets_match_thirteen_pinned_original_git_blobs(self):
        self.assertEqual(PIN, self.fixture['sourceCommit'])
        self.assertEqual(7, len(self.fixture['inputs']))
        self.assertEqual(6, len(self.fixture['presets']))
        total = 0
        for entry in self.fixture['inputs'] + self.fixture['presets']:
            raw = subprocess.check_output(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', PIN + ':' + entry['path']])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), entry['blobSHA256'])
            original = json.loads(raw)
            if 'rows' in entry:
                for row in entry['rows']:
                    self.assertIn(row, original)
                total += len(entry['rows'])
            else:
                self.assertEqual(raw.hex(), entry['rawHex'])
                self.assertEqual(original, entry['row'])
        self.assertEqual(352, total)

    def test_runtime_hashes_bind_all_thirty_five_world_rows_and_six_presets(self):
        source = (ROOT / 'src/main/java/org/hkrpg/server/gameplay/BoxingClubTrials.java').read_text('utf-8')
        actual = dict(re.findall(r'Map\.entry\(\s*"([^"]+)"\s*,\s*"([a-f0-9]{64})"', source))
        rows = next(e['rows'] for e in self.fixture['inputs'] if e['path'] == 'ExcelOutput/SpecialAvatar.json')
        expected = {str(row['SpecialAvatarID']) + '/' + str(row.get('WorldLevel', 0)): hashlib.sha256(json.dumps(row, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest() for row in rows}
        expected.update({e['path']: e['blobSHA256'] for e in self.fixture['presets']})
        self.assertEqual(41, len(expected))
        self.assertEqual(expected, actual)

    def test_original_trials_scale_exactly_and_have_matching_skill_and_equipment_resources(self):
        tables = {Path(e['path']).stem: e['rows'] for e in self.fixture['inputs']}
        rows = tables['SpecialAvatar']
        ids = {3051204, 3051109, 3051103, 3051104, 3051003}
        self.assertEqual({(i, w) for i in ids for w in range(7)}, {(r['SpecialAvatarID'], r.get('WorldLevel', 0)) for r in rows})
        presets = {e['row']['Name'] for e in self.fixture['presets']}
        equipment = {r['ID'] for r in tables['ItemConfigEquipment']}
        actors = {r['AvatarID'] for r in tables['AvatarConfig']}
        for row in rows:
            self.assertTrue(row['IsUseWorldLevel'])
            self.assertEqual('TYPE_TRIAL', row['Type'])
            self.assertEqual('TYPE_CUSTOM', row['SkillTreeTemplate'])
            self.assertIn(row['CustomSkillTreeKey'], presets)
            self.assertIn(row['EquipmentID'], equipment)
            self.assertIn(row['AvatarID'], actors)


if __name__ == '__main__':
    unittest.main()
