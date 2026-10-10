"""40431 的完整入口、34 项剧情目标和试用角色来源；原生请求与真实游戏验收分开。"""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


class DefaultHybridRaidSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / 'src/test/resources/fixtures/default-hybrid-mission-raid-4.6.json').read_text('utf-8'))
        cls.raw = {p: subprocess.check_output(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', PIN + ':' + p]) for p in cls.fixture['sourceFiles']}
        cls.data = {p: json.loads(raw) for p, raw in cls.raw.items()}

    def test_all_twenty_three_original_blobs_local_resources_and_whole_records_match(self):
        self.assertEqual(self.fixture['sourceCommit'], PIN)
        self.assertEqual(len(self.raw), 23)
        for path, expected in self.fixture['sourceFiles'].items():
            self.assertEqual(hashlib.sha256(self.raw[path]).hexdigest(), expected['gitBlobSHA256'])
            local = (ROOT / 'resources/4.6' / path).read_bytes()
            self.assertEqual(hashlib.sha256(local).hexdigest(), expected['checkoutSHA256'])
            self.assertEqual(json.loads(local), self.data[path])
            records = self.fixture['records'][path]
            if path.startswith('ExcelOutput/'):
                for row in records:
                    self.assertIn(row, self.data[path])
            else:
                self.assertEqual(records, [self.data[path]])

    def test_default_hybrid_entrance_and_role_gate_are_not_rewritten_to_skip_ui(self):
        row, = self.fixture['records']['ExcelOutput/RaidConfig.json']
        self.assertEqual(row['RaidID'], 40431)
        self.assertNotIn('EnterType', row)
        self.assertEqual(row['TeamType'], 'TrialOrPlayer')
        self.assertEqual(row['MainMissionIDList'], [1040408])
        self.assertEqual(row['TrialAvatarList'], [1021409])
        self.assertEqual(row['TeamLimitIDList'], [43])
        rule, = self.fixture['records']['ExcelOutput/TeamLimitCondition.json']
        self.assertEqual(rule['LimitType'], 'IncludeAvatar')
        self.assertEqual(rule['ParamInt1'], 1409)

    def test_whole_thirty_four_goals_transfer_script_and_timeline_target_are_retained(self):
        base = 'Config/Level/Mission/1040408/'
        header, = self.fixture['records'][base + 'MissionInfo_1040408.json']
        self.assertEqual(header['StartSubMissionList'], [104040830])
        self.assertEqual(header['FinishSubMissionList'], [104040824])
        rows = self.fixture['records'][base + 'MissionInfo_1040408.partial.json'][0]['SubMissionList']
        self.assertEqual(len(rows), 34)
        target = next(r for r in rows if r['ID'] == 104040816)
        self.assertEqual((target['FinishType'], target['ParamInt1'], target['ParamInt2'], target['ParamStr1']), ('TimeLineSetState', 38, 300001, 'L0_Idle'))
        paths = {r['MissionJsonPath'] for r in rows if r.get('MissionJsonPath')}
        self.assertEqual(len(paths), 3)
        self.assertTrue(paths.issubset(self.fixture['records']))
        transfer = self.fixture['records'][base + 'Mission_104040829.json'][0]
        self.assertEqual(transfer['OnStartSequece'][0]['TaskList'], [{'$type': 'RPG.GameCore.RaidSceneTransfer'}])
        stages = self.fixture['records']['ExcelOutput/StageConfig.json']
        self.assertEqual({r['StageID'] for r in stages}, set(range(204310700, 204310707)))

    def test_original_trial_properties_and_custom_skill_preset_have_fixed_sources(self):
        trials = self.fixture['records']['ExcelOutput/SpecialAvatar.json']
        self.assertEqual({r.get('WorldLevel', 0) for r in trials}, set(range(7)))
        trial, = [r for r in trials if r.get('WorldLevel', 0) == 0]
        self.assertEqual((trial['SpecialAvatarID'], trial['AvatarID'], trial['EquipmentID']), (1021409, 1409, 23042))
        self.assertEqual(trial['CustomSkillTreeKey'], 'W1_Standard_30-40')
        preset, = self.fixture['records']['Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_1.json']
        self.assertEqual(preset['Name'], trial['CustomSkillTreeKey'])
        self.assertFalse(self.fixture['clientGameplayVerified'])


if __name__ == '__main__':
    unittest.main()
