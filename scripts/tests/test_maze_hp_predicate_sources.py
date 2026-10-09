"""核对原始生命条件、完整监听任务与本机静态字段证据；不宣称监听链或客户端验收。"""
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MazeHpPredicateSourcesTest(unittest.TestCase):
    def setUp(self):
        self.proof = json.loads((ROOT / 'src/test/resources/fixtures/maze-hp-predicate-4.6.json').read_text('utf-8'))

    def test_complete_original_listener_task_and_source_identity_are_retained(self):
        proof = self.proof
        self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b', proof['sourceCommit'])
        self.assertEqual(['GlobalModifiers', 'ADV_StageAbility_Anaxa_00_LineupAttackListener', 'OnAttack', 0], proof['path'])
        self.assertEqual({'$type':'RPG.GameCore.AdventureByCompareHPRatio',
                          'TargetType':{'$type':'RPG.GameCore.TargetAlias','Alias':'Caster'},
                          'CompareType':'Greater'}, proof['task']['Predicate'])
        self.assertRegex(proof['sha256'], r'^[0-9a-f]{64}$')
        self.assertRegex(proof['sourceBlobSHA256'], r'^[0-9a-f]{64}$')

    def test_native_float_field_and_complete_bounded_constructor_have_consistent_identity(self):
        native = self.proof['nativeFieldEvidence']
        self.assertEqual('AdventureByCompareHPRatio', native['typeName'])
        self.assertEqual(20777, native['typeIndex'])
        fields = {r['name']:r for r in native['fields']}
        self.assertEqual({'TargetType','CompareType','CompareValue'},set(fields))
        self.assertEqual({'kind':12},fields['CompareValue']['nativeType'])
        ctor=native['constructor'];raw=bytes.fromhex(ctor['bytesHex'])
        self.assertEqual(64,len(raw));self.assertEqual(120391,ctor['methodIndex'])
        self.assertEqual(len(raw),int(ctor['endRva'],16)-int(ctor['rva'],16))
        self.assertEqual(hashlib.sha256(raw).hexdigest(),ctor['sha256'])
        self.assertEqual('8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3',native['clientFiles']['GameAssembly.dll'])

    def test_unimplemented_target_flag_and_listener_boundaries_remain_explicit(self):
        proof=self.proof;leaf=proof['task']['SuccessTaskList'][0]
        self.assertEqual('ParamEntityList',leaf['TargetType']['Alias'])
        self.assertEqual({'$type':'RPG.GameCore.AdvByContainBehaviorFlag','Flag':'Fear'},leaf['Condition'])
        self.assertEqual(140502,leaf['ID'])
        for key in ['completeAbilityChainVerified','modifierListenerDispatchVerified','behaviorFlagAndTargetListVerified']:
            self.assertIs(False,proof[key])
        for key in ['runtimePredicateEvaluationVerified','wireFieldNumbersInferred','clientExecutableStarted','clientModified']:
            self.assertIs(False,proof['nativeFieldEvidence'][key])


if __name__ == '__main__':
    unittest.main()
