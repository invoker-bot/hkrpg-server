"""本机固定客户端的只读回归；没有该客户端时明确跳过，不代表游戏验收。"""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定的本机客户端，跳过只读元数据回归')
class Client46MetadataTest(unittest.TestCase):
    def inspect(self, *arguments):
        output = ROOT / 'build' / ('client46-regression-' + self._testMethodName + '.json')
        output.parent.mkdir(parents=True, exist_ok=True)
        result = subprocess.run(
            [sys.executable, '-s', '-X', 'utf8', str(ROOT / 'scripts/analyze-client-46.py'),
             '--client-dir', str(CLIENT), '--output', str(output), *arguments],
            cwd=ROOT, capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(0, result.returncode, result.stderr)
        data = json.loads(output.read_text(encoding='utf-8'))
        self.assertFalse(data['clientExecutableStarted'])
        self.assertFalse(data['clientModified'])
        self.assertFalse(data['clientAccepted'])
        return data

    def test_arrays_resolve_cached_element_type_instead_of_treating_it_as_a_definition(self):
        data = self.inspect('--types', 'WolfBroConfig', 'LevelGraphConfig', 'SequenceConfig', '--fields')
        types = {row['name']: row for row in data['methods']}
        expected = {'WolfBroConfig': {'BulletList': 'MVector3', 'CartoonGunList': 'WolfBroCartoonGunConfig'},
                    'LevelGraphConfig': {'OnInitSequece': 'LevelInitSequeceConfig',
                                        'OnStartSequece': 'LevelStartSequeceConfig'},
                    'SequenceConfig': {'TaskList': 'TaskConfig'}}
        for owner, fields in expected.items():
            by_name = {row['name']: row for row in types[owner]['fields']}
            for field, name in fields.items():
                self.assertEqual(name, by_name[field]['nativeType'].get('elementType', {}).get('name'))

    def test_unsigned_client_bullet_prop_literal_is_preserved(self):
        data = self.inspect('--types', 'WolfBroPuzzleBoard', '--fields')
        fields = {row['name']: row for row in data['methods'][0]['fields']}
        self.assertEqual(103069, fields['BulletPropID']['constant'].get('value'))

    def test_inherited_fields_are_read_from_the_verified_parent_chain(self):
        data = self.inspect('--types', 'WaitEntityEvent', 'ByCompareGroupState', '--fields', '--inheritance')
        types = {row['name']: row for row in data['methods']}
        self.assertEqual(['TaskEnabled', 'IsLoop', 'EventName', 'OnEvent', 'InstanceID'],
                         [row['name'] for row in types['WaitEntityEvent']['instanceFields']])
        self.assertEqual(['TaskEnabled', 'Inverse', 'EquationType', 'Value'],
                         [row['name'] for row in types['ByCompareGroupState']['instanceFields']])

    def test_method_slots_use_the_method_table_and_exact_usage_kind(self):
        slots = ['0x94500e0', '0x9522af8', '0x9522b08', '0x9531828']
        data = self.inspect('--method-slots', *slots)
        methods = {row['slot']: row['method']['index'] for row in data['methodInfoSlots']}
        self.assertEqual(dict(zip(slots, [133908, 110356, 110358, 130212])), methods)

    def test_path_literals_use_the_string_table_and_native_literal_cipher(self):
        data = self.inspect('--string-slots', '0x96f5968', '0x9729e18')
        strings = {row['slot']: row['value'] for row in data['stringLiteralSlots']}
        self.assertEqual({'0x96f5968': '.bytes', '0x9729e18': 'BakedConfig/'}, strings)

    def test_native_instance_offsets_match_getters_and_not_other_builds_proto_comments(self):
        data = self.inspect('--types', 'LineUpContext', 'NNPGLCGNICI', 'COMGNGLLCFN',
                            'PBNONKAIAMK', 'BattleTargetConfigRow', '--field-offsets',
                            '--method-indices', '521281', '521283', '--disassemble', '7')
        types = {row['name']: {field['name']: field.get('instanceOffset')
                              for field in row['fields']} for row in data['methods']}
        self.assertEqual(0xd0, types['LineUpContext']['_StageID'])
        self.assertEqual(0xcc, types['LineUpContext']['<StageType>k__BackingField'])
        self.assertEqual(0xa0, types['LineUpContext']['BattleTargetList'])
        self.assertEqual(0x10, types['NNPGLCGNICI']['IFPIHNGNPGF'])
        self.assertEqual(0x18, types['COMGNGLLCFN']['CEHGEDLBFEI'])
        self.assertEqual(0x1c, types['COMGNGLLCFN']['GEMFJILOPLL'])
        self.assertEqual(0x20, types['COMGNGLLCFN']['NDFIDCNNGMJ'])
        self.assertEqual(0x10, types['PBNONKAIAMK']['KKCKMCDGCGK'])
        self.assertEqual(0x14, types['PBNONKAIAMK']['MBCACNKDMFG'])
        self.assertEqual(0x66, types['BattleTargetConfigRow']['SkipWhenSuccessOnEnterBattle'])
        self.assertEqual(0x44, types['BattleTargetConfigRow']['TargetParam'])
        methods = {row['index']: row for row in data['selectedMethods']}
        self.assertIn('dword ptr [rcx + 0xd0]', methods[521281]['instructions'][0])
        self.assertIn('dword ptr [rcx + 0xcc]', methods[521283]['instructions'][0])

    def test_native_battle_categories_include_separate_client_achievement_key_six(self):
        output = ROOT / 'build/client46-category-regression.json'
        result = subprocess.run(
            [sys.executable, '-s', '-X', 'utf8',
             str(ROOT / 'scripts/verify-client-battle-target-categories.py'),
             '--client-dir', str(CLIENT), '--output', str(output)],
            cwd=ROOT, capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(0, result.returncode, result.stderr)
        data = json.loads(output.read_text('utf-8'))
        self.assertTrue(data['verified'])
        self.assertFalse(data['clientExecutableStarted'])
        self.assertFalse(data['clientModified'])
        self.assertFalse(data['clientAccepted'])
        self.assertEqual({'battleScoringList': 1, 'battleAchievementList': 2,
                          'battleClientAchievementList': 6, 'battleRaidTargetList': 3,
                          'battleChallengeScoreList': 4, 'battleTargetList': 5}, data['categories'])
        self.assertEqual('0xe6b5de0', data['functions'][0]['beginRVA'])
        self.assertEqual('0xe6b6861', data['functions'][0]['endRVA'])
        self.assertEqual(34, len(data['functions'][0]['points']))

    def test_native_dice_homepage_report_has_no_result_payload_and_uses_source_cooldown(self):
        (ROOT / 'build').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='client46-dice-regression-') as directory:
            output = Path(directory) / 'proof.json'
            result = subprocess.run(
                [sys.executable, '-s', '-X', 'utf8', str(ROOT / 'scripts/verify-client-dice-mainpage.py'),
                 '--client-dir', str(CLIENT), '--output', str(output)],
                cwd=ROOT, capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads(output.read_text('utf-8'))
        self.assertTrue(data['verified'])
        self.assertTrue(data['emptyRequest'])
        self.assertEqual((9262,8,0,1500), (data['requestCommand'],data['responseField'],
                                          data['responseWireType'],data['cooldownMillis']))
        self.assertEqual(3,len(data['functions']))
        self.assertEqual(16,sum(len(f['points']) for f in data['functions']))
        self.assertFalse(data['clientExecutableStarted'])
        self.assertFalse(data['clientModified'])
        self.assertFalse(data['clientAccepted'])

    def test_native_achievement_omitted_arguments_default_to_add_one(self):
        (ROOT / 'build').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='client46-default-regression-') as directory:
            output = Path(directory) / 'proof.json'
            result = subprocess.run(
                [sys.executable, '-s', '-X', 'utf8',
                 str(ROOT / 'scripts/verify-client-local-legend-achievement-default.py'),
                 '--client-dir', str(CLIENT), '--output', str(output)],
                cwd=ROOT, capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads(output.read_text('utf-8'))
        self.assertTrue(data['verified'])
        self.assertFalse(data['clientExecutableStarted'])
        self.assertFalse(data['clientModified'])
        self.assertFalse(data['clientAccepted'])
        self.assertEqual(('Add', 2, 1, '0x200000000'),
                         (data['modifyFunction'], data['modifyFunctionValue'],
                          data['defaultModifyValue'], data['encodedDefault']))
        self.assertEqual('0x1da68ac0', data['functions'][0]['beginRVA'])
        self.assertEqual(9, len(data['functions'][0]['points']))
        self.assertEqual('0x1dccc170', data['conversion']['declaredSpanEndRVA'])
        self.assertEqual(7, len(data['conversion']['instructions']))
        self.assertEqual(('Add', 2), (data['targetModifyFunction'], data['targetModifyFunctionValue']))
        self.assertEqual('0x1da6a4b0', data['targetConstructor']['declaredSpanEndRVA'])
        self.assertEqual(3, len(data['targetConstructor']['instructions']))


if __name__ == '__main__':
    unittest.main()
