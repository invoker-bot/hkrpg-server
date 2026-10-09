"""第二季三轮、共鸣、可选祝福及试用世界等级的完整原始来源核对。"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


def digest(row):
    return hashlib.sha256(json.dumps(row, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


class BoxingClubResonanceSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        folder = ROOT / 'src/test/resources/fixtures'
        cls.rounds = json.loads((folder / 'boxing-club-resonance-4.6.json').read_text('utf-8'))
        cls.trials = json.loads((folder / 'boxing-club-resonance-trials-4.6.json').read_text('utf-8'))

    def test_both_fixtures_match_twenty_four_pinned_original_git_blobs(self):
        unique = set()
        for fixture, count in [(self.rounds, 414), (self.trials, 1241)]:
            self.assertEqual(PIN, fixture['sourceCommit'])
            self.assertEqual(count, sum(len(entry['rows']) for entry in fixture['inputs']))
            for entry in fixture['inputs'] + fixture.get('presets', []):
                raw = subprocess.check_output(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', PIN + ':' + entry['path']])
                self.assertEqual(entry['blobSHA256'], hashlib.sha256(raw).hexdigest())
                unique.add(entry['path'])
                original = json.loads(raw)
                if 'rows' in entry:
                    for row in entry['rows']:
                        self.assertIn(row, original)
                else:
                    self.assertEqual(entry['rawHex'], raw.hex())
                    self.assertEqual(entry['row'], original)
        self.assertEqual(24, len(unique))

    def test_all_335_runtime_rows_are_complete_original_fingerprints(self):
        keys = {'PlaneEvent': 'EventID', 'StageConfig': 'StageID', 'MazeBuff': 'ID', 'RewardData': 'RewardID', 'ActivityPanel': 'PanelID', 'ActivityConfig': 'ActivityID', 'BoxingClubConstValueClient': 'ConstValueName'}
        expected = {}
        for entry in self.rounds['inputs']:
            table = Path(entry['path']).stem
            if table not in keys:
                continue
            for row in entry['rows']:
                key = table + ':' + str(row[keys[table]])
                if table == 'PlaneEvent': key += '/' + str(row.get('WorldLevel', 0))
                if table == 'MazeBuff': key += '/' + str(row.get('Lv', 1))
                expected[key] = digest(row)
        source = (ROOT / 'src/main/java/org/hkrpg/server/gameplay/BoxingClubResonanceSourceHashes.java').read_text('utf-8')
        actual = dict(re.findall(r'Map\.entry\(\s*"([^"]+)"\s*,\s*"([a-f0-9]{64})"', source))
        self.assertEqual(335, len(expected))
        self.assertEqual(expected, actual)

    def test_all_98_original_trial_world_rows_and_six_full_skill_presets_are_bound(self):
        entry = next(e for e in self.trials['inputs'] if e['path'] == 'ExcelOutput/SpecialAvatar.json')
        expected = {str(row['SpecialAvatarID']) + '/' + str(row.get('WorldLevel', 0)): digest(row) for row in entry['rows']}
        expected.update({e['path']: e['blobSHA256'] for e in self.trials['presets']})
        source = (ROOT / 'src/main/java/org/hkrpg/server/gameplay/BoxingClubResonanceTrials.java').read_text('utf-8')
        actual = dict(re.findall(r'Map\.entry\(\s*"([^"]+)"\s*,\s*"([a-f0-9]{64})"', source))
        self.assertEqual(104, len(expected))
        self.assertEqual(expected, actual)
        ids = {row['SpecialAvatarID'] for row in entry['rows']}
        self.assertEqual(14, len(ids))
        self.assertEqual({(i, w) for i in ids for w in range(7)}, {(row['SpecialAvatarID'], row.get('WorldLevel', 0)) for row in entry['rows']})
        names = {e['row']['Name'] for e in self.trials['presets']}
        for row in entry['rows']:
            self.assertTrue(row['IsUseWorldLevel'])
            self.assertEqual('TYPE_TRIAL', row['Type'])
            self.assertIn(row['CustomSkillTreeKey'], names)

    def test_seven_challenges_use_exact_three_round_choices_and_the_original_empty_reward(self):
        tables = {Path(entry['path']).stem: entry['rows'] for entry in self.rounds['inputs']}
        challenges = tables['BoxingClubChallenge']
        self.assertEqual(set(range(6, 13)), {row['ChallengeID'] for row in challenges})
        groups = {row['StageGroupID']: row for row in tables['BoxingClubStageGroup']}
        stages = {row['EventID']: row for row in tables['BoxingClubStage']}
        all_buffs = set()
        events = set()
        for challenge in challenges:
            self.assertEqual(100, challenge['FirstPassRewardID'])
            self.assertEqual([], challenge['StageGroupList'])
            self.assertEqual(1, len(challenge['StageBuffAndGroupMap']))
            self.assertEqual(20, challenge['ChallengeTurnLimit'])
            for resonance, group in challenge['StageBuffAndGroupMap'].items():
                self.assertEqual(3, len(groups[group]['EventIDList']))
                all_buffs.add(int(resonance))
                for index, event in enumerate(groups[group]['EventIDList']):
                    events.add(event)
                    options = stages[event]['BuffOptionalList']
                    if index == 0:
                        self.assertEqual([0], options)
                    else:
                        self.assertEqual(2, len(options))
                        self.assertTrue(all(v > 0 for v in options))
                        all_buffs.update(options)
        self.assertEqual(35, len(all_buffs))
        self.assertEqual(21, len(events))
        self.assertEqual([{'RewardID': 100}], tables['RewardData'])
        self.assertEqual({(e, w) for e in events for w in range(7)}, {(row['EventID'], row.get('WorldLevel', 0)) for row in tables['PlaneEvent']})


if __name__ == '__main__':
    unittest.main()
