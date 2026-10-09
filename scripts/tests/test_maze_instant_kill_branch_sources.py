"""核对完整原始白厄动作与逐目标条件；完整技能和客户端验收仍未完成。"""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MazeInstantKillBranchSourcesTest(unittest.TestCase):
    def setUp(self):
        self.proof = json.loads((ROOT / 'src/test/resources/fixtures/maze-instant-kill-branch-4.6.json').read_text('utf-8'))

    def test_complete_original_ability_and_source_identity_are_retained(self):
        p = self.proof
        self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b', p['sourceCommit'])
        self.assertEqual(['AbilityList', 1], p['abilityPath'])
        self.assertEqual('LocalPlayer_Phainon_00_MazeSkill', p['ability']['Name'])
        self.assertEqual(20, len(p['ability']['OnStart']))
        for key in ['resourceSHA256', 'originalBlobSHA256']:
            self.assertRegex(p[key], '^[0-9a-f]{64}$')

    def test_original_param_entity_branch_and_freeze_style_are_unmodified(self):
        attack = self.proof['ability']['OnStart'][11]
        branch = attack['OnAttack'][1]
        self.assertEqual({'$type': 'RPG.GameCore.ByAllowInstantKill',
                          'TargetType': {'$type': 'RPG.GameCore.TargetAlias', 'Alias': 'ParamEntity'}}, branch['Predicate'])
        self.assertEqual([{'$type': 'RPG.GameCore.AdventureSetAttackTargetMonsterDie',
                           'TargetType': {'$type': 'RPG.GameCore.TargetAlias', 'Alias': 'ParamEntity'},
                           'DyingStyleParam': {'DyingStyle': 'Freeze', 'FreezeDuration': 0.5}}], branch['SuccessTaskList'])
        self.assertNotIn('FailedTaskList', branch)

    def test_original_detection_summon_and_battle_callbacks_have_not_been_stripped(self):
        attack = self.proof['ability']['OnStart'][11]
        self.assertEqual({'SummonUnitID': 14082, 'DetectSummonUnitTriggerName': 'PhainonFieldTrigger_Monster'}, attack['SummonUnitTriggerAttackDetectConfig'])
        self.assertEqual(4, attack['AttackDetectConfig']['MaxRadius'])
        self.assertEqual(2, attack['AttackDetectConfig']['Hight'])
        self.assertEqual('AllEnemy', attack['AttackTargetType']['Alias'])
        self.assertEqual('ParamEntity', attack['OnBattle'][0]['TargetType']['Alias'])
        self.assertEqual(140801, attack['OnBattle'][0]['ID'])

    def test_complete_official_ability_and_ui_remain_unverified(self):
        for key in ['completeOriginalAbilityVerified', 'attackDetectionAndSummonTriggerVerified',
                    'dyingStyleAnimationVerified', 'additionalCharacterConfigTagsVerified',
                    'nativeOfficialPredicateEvaluationVerified', 'clientGameplayVerified']:
            self.assertIs(False, self.proof[key])


if __name__ == '__main__':
    unittest.main()
