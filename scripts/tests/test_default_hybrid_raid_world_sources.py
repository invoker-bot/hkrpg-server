"""40431 七档原始事件、试用角色与技能预设的来源核对。"""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


class DefaultHybridRaidWorldSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / 'src/test/resources/fixtures/default-hybrid-raid-worlds-4.6.json').read_text(encoding='utf-8'))
        cls.raw = {p: subprocess.check_output(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', PIN + ':' + p]) for p in cls.fixture['sourceFiles']}

    def test_all_twenty_nine_source_blobs_and_whole_records_match_installed_resources(self):
        self.assertEqual(self.fixture['sourceCommit'], PIN)
        self.assertEqual(len(self.raw), 29)
        self.assertEqual(sum(len(v) for v in self.fixture['records'].values()), 126)
        for path, expected in self.fixture['sourceFiles'].items():
            self.assertEqual(hashlib.sha256(self.raw[path]).hexdigest(), expected['gitBlobSHA256'])
            local = (ROOT / 'resources/4.6' / path).read_bytes()
            self.assertEqual(hashlib.sha256(local).hexdigest(), expected['checkoutSHA256'])
            original = json.loads(self.raw[path])
            self.assertEqual(json.loads(local), original)
            selected = self.fixture['records'][path]
            if path.startswith('ExcelOutput/'):
                for row in selected:
                    self.assertIn(row, original)
            else:
                self.assertEqual(selected, [original])

    def test_seven_actual_worlds_have_their_matching_original_event_and_stage(self):
        records = self.fixture['records']
        events = {r.get('WorldLevel', 0): r for r in records['ExcelOutput/PlaneEvent.json']}
        stages = {r['StageID']: r for r in records['ExcelOutput/StageConfig.json']}
        self.assertEqual(set(events), set(range(7)))
        self.assertEqual(set(stages), set(range(204310700, 204310707)))
        levels = [24, 32, 40, 48, 56, 66, 88]
        for world, row in events.items():
            self.assertEqual(row['EventID'], 20431070)
            self.assertEqual(row['StageID'], 204310700 + world)
            self.assertEqual(stages[row['StageID']]['Level'], levels[world])
            self.assertEqual(stages[row['StageID']]['LevelWinCondition'], ['[CDT_WaitCustomString:Level_SpecialWin]'])

    def test_trial_actual_world_levels_and_all_six_skill_presets_are_original(self):
        records = self.fixture['records']
        rows = {r.get('WorldLevel', 0): r for r in records['ExcelOutput/SpecialAvatar.json']}
        self.assertEqual(set(rows), set(range(7)))
        presets = {v[0]['Name']: v[0] for p, v in records.items() if p.startswith('Config/BattleLineupPage/SkillTreePointPreset/')}
        self.assertEqual(len(presets), 6)
        levels = [40, 40, 50, 58, 68, 78, 80]
        for world, row in rows.items():
            self.assertEqual(row['SpecialAvatarID'], 1021409)
            self.assertEqual(row['AvatarID'], 1409)
            self.assertEqual(row['Level'], levels[world])
            self.assertIn(row['CustomSkillTreeKey'], presets)

    def test_adjustable_default_hybrid_entry_and_role_gate_remain_unmodified(self):
        row, = self.fixture['records']['ExcelOutput/RaidConfig.json']
        self.assertEqual(row['RaidID'], 40431)
        self.assertNotIn('EnterType', row)
        self.assertEqual(row['DifficultyAdjustmentType'], 1)
        self.assertEqual(row['TeamType'], 'TrialOrPlayer')
        self.assertEqual(row['TrialAvatarList'], [1021409])
        self.assertEqual(row['TeamLimitIDList'], [43])
        self.assertFalse(self.fixture['clientGameplayVerified'])


if __name__ == '__main__':
    unittest.main()
