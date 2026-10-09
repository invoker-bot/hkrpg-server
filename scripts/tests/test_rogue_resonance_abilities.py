"""原始共鸣能力、字段边界和完整原生函数证据的回归。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tempfile
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_abilities', ROOT / 'scripts/read-rogue-resonance-abilities.py')
abilities = importlib.util.module_from_spec(spec)
spec.loader.exec_module(abilities)


def uv(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result + bytes([value]))


class ResonanceAbilityFormatTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = abilities.load_profile()

    def test_known_but_unmatched_fields_stop_before_interpreting_payload(self):
        # The metadata knows these primitive fields. Their serialization was not matched
        # in the nine original abilities, so accepting them would overstate the reader.
        for name, ordinal, raw in [('TargetConfig', 1, b'\x00'),
                                   ('TurnBasedAbilityConfig', 11, b'\x01'),
                                   ('DynamicString', 0, b'\x00'),
                                   ('TurnBasedModifierConfig', 0, b'\x00')]:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, '未匹配的序列化字段'):
                abilities.Reader(uv(1 << ordinal) + raw, self.profile).obj(name)

    def test_complete_native_methods_and_finite_source_scope(self):
        self.assertEqual((121, 1061, 348, 37), (len(self.profile['schemas']),
            sum(len(s['fields']) for s in self.profile['schemas'].values()),
            sum(len(s['allowedFields']) for s in self.profile['schemas'].values()),
            len(self.profile['enums'])))
        methods = self.profile['nativeMethods']
        self.assertEqual((248, 253291), (len(methods), self.profile['nativeBytes']))
        self.assertEqual(len(methods), len({m['rva'] for m in methods}))
        for method in methods:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual('PE Exception Directory', method['bounds'])
            self.assertEqual(int(method['endRva'], 16) - int(method['rva'], 16), len(raw))
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
        for name, schema in self.profile['schemas'].items():
            self.assertIn(schema['nativeReaderRva'], {m['rva'] for m in methods})
            self.assertEqual(len(schema['allowedFields']), len(set(schema['allowedFields'])))
            self.assertLessEqual(set(schema['allowedFields']), {f['name'] for f in schema['fields']})

    def test_each_factory_tag_matches_the_complete_registration_instruction(self):
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        methods = {m['index']: m for m in self.profile['nativeMethods'] if m['index'] is not None}
        indices = {'task': 129950, 'predicate': 133912, 'target': 134765, 'sequence': 134565}
        for family, table in self.profile['bindings'].items():
            method = methods[indices[family]]
            instructions = list(decoder.disasm(bytes.fromhex(method['bytesHex']),
                                0x180000000 + int(method['rva'], 16)))
            positions = {i.address - 0x180000000: j for j, i in enumerate(instructions)}
            for tag, binding in table.items():
                registration = binding['registration']
                index = positions[int(registration['registrationRva'], 16)]
                write, load = instructions[index], instructions[index - 1]
                offset = 32 + int(tag) * 8
                match = re.fullmatch(r'qword ptr \[rax \+ (0x[0-9a-f]+)\], (rdx|rcx)', write.op_str)
                self.assertEqual(('mov', offset), (write.mnemonic, int(match[1], 16)))
                rip = re.fullmatch(match[2] + r', qword ptr \[rip ([+-]) (0x[0-9a-f]+)\]', load.op_str)
                self.assertEqual(int(registration['methodSlotRva'], 16),
                    write.address - 0x180000000 + (1 if rip[1] == '+' else -1) * int(rip[2], 16))
                factory = methods[binding['factoryMethod']['index']]
                body = hex(0x180000000 + int(binding['readerRva'], 16))
                calls = {(i.mnemonic, i.op_str) for i in decoder.disasm(bytes.fromhex(factory['bytesHex']),
                                     0x180000000 + int(factory['rva'], 16))}
                self.assertTrue(('call', body) in calls or ('jmp', body) in calls)

    def test_damage_modifier_has_two_masks_and_preserves_bit_65(self):
        schema = self.profile['schemas']['ModifyDamageData']
        self.assertEqual((99, 2), (len(schema['fields']), schema['maskWords']))
        # The received-damage ratio lives in the second word, at serialized bit 65.
        ordinal = next(i for i, f in enumerate(schema['fields'])
                       if f['name'] == 'Defender_AllDamageTypeTakenRatio')
        self.assertGreaterEqual(ordinal, 64)
        raw = uv(0) + uv(1 << (ordinal - 64)) + b'\x00\x06'
        value = abilities.Reader(raw, self.profile).obj('ModifyDamageData')
        self.assertEqual({'mode': 0, 'fixedSignedRaw': 3}, value['Defender_AllDamageTypeTakenRatio'])

    def test_three_unsigned_enum_widths_preserve_original_flag_values(self):
        for index, kind in [(10437, 11), (10502, 7), (10505, 9)]:
            enum = self.profile['enums'][str(index)]
            self.assertEqual(kind, enum['underlyingType']['kind'])
            for raw in enum['allowedRawValues']:
                with self.subTest(enum=enum['name'], raw=raw):
                    self.assertEqual(raw, abilities.Reader(uv(raw), self.profile).read(
                                     {'kind': 17, 'typeIndex': index, 'name': enum['name']}))

    def test_postfix_framing_preserves_bytes_constants_and_variables_without_evaluation(self):
        raw = b'\x01\x03\x01\x00\x11\x02\x06\x01\x02\x00\x03'
        reader = abilities.Reader(raw, self.profile)
        value = reader.read({'kind': 18, 'name': 'DynamicFloat'})
        self.assertEqual({'mode': 1, 'rawPostfixBytes': '010011',
            'fixedSignedConstants': [3, -1], 'variableKeys': [0, -2],
            'expressionEvaluated': False}, value)
        self.assertEqual(len(raw), reader.pos)
        for end in range(len(raw)):
            with self.subTest(end=end), self.assertRaises(ValueError):
                abilities.Reader(raw[:end], self.profile).read({'kind': 18, 'name': 'DynamicFloat'})

    def test_dictionary_duplicates_unsupported_generics_and_sizes_stop(self):
        for raw in [b'\x01', uv(1026), b'\x04\x01x\x00\x00\x01x\x00\x00']:
            with self.subTest(raw=raw.hex()), self.assertRaises(ValueError):
                abilities.Reader(raw, self.profile).dictionary({'kind': 18, 'name': 'DynamicFloat'})
        with self.assertRaisesRegex(ValueError, '泛型字典'):
            abilities.Reader(b'\x00', self.profile).read({'kind': 21})

    def test_framed_but_invalid_postfix_programs_are_rejected(self):
        # Every record has a valid binary envelope. The native VM would use an
        # invalid opcode/index/stack or read past it, so the exporter must stop.
        programs = ['', 'ff11', '000000000611', '00', '000111', '010111',
                    '0211', '0e11', '100111', '01000000100211', '0000',
                    '0000110000', '0000000011', '11']
        for program in programs:
            expression = bytes.fromhex(program)
            raw = b'\x01' + bytes([len(expression)]) + expression + b'\x01\x00\x01\x00'
            with self.subTest(program=program), self.assertRaisesRegex(ValueError, '表达式'):
                abilities.Reader(raw, self.profile).read({'kind': 18, 'name': 'DynamicFloat'})

    def test_unknown_masks_tasks_predicates_modes_values_and_identity_stop(self):
        for raw, description in [(uv(4000), {'kind': 18, 'name': 'TaskConfig'}),
            (uv(647), {'kind': 18, 'name': 'PredicateConfig'}),
            (b'\x02', {'kind': 18, 'name': 'DynamicFloat'}),
            (b'\x01', {'kind': 18, 'name': 'AttackData'}),
            (uv(1998), {'kind': 17, 'typeIndex': 18221, 'name': 'ValueFromVariateType'}),
            (b'\x00', {'kind': 18, 'name': 'EffectConfig', 'typeIndex': 74136}),
            (b'\x00', {'kind': 18, 'name': 'UnknownObject'})]:
            with self.subTest(description=description), self.assertRaises(ValueError):
                abilities.Reader(raw, self.profile).read(description)
        with self.assertRaisesRegex(ValueError, '未知字段掩码'):
            abilities.Reader(uv(1 << 6), self.profile).ability()

    def test_noncanonical_overflow_booleans_utf8_depth_and_nodes_stop(self):
        for raw, description in [(b'\x80\x00', {'kind': 11}),
            (b'\xff' * 9 + b'\x02', {'kind': 11}),
            (b'\x02', {'kind': 2}), (b'\x01\xff', {'kind': 14})]:
            with self.subTest(raw=raw.hex()), self.assertRaises(ValueError):
                abilities.Reader(raw, self.profile).read(description)
        reader = abilities.Reader(b'\x00', self.profile)
        reader.depth = 32
        with self.assertRaises(ValueError):
            reader.ability()
        reader = abilities.Reader(b'\x00', self.profile)
        reader.nodes = 100000
        with self.assertRaises(ValueError):
            reader.read({'kind': 2})

    def test_report_target_rejects_client_and_operator_files(self):
        for output in [CLIENT / 'result.json', ROOT / 'resources/Config/test.json', ROOT / 'config.json']:
            with self.subTest(output=str(output)), self.assertRaises(ValueError):
                abilities.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定客户端，跳过原始能力读取')
class ResonanceAbilityOriginalRecordsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.TemporaryDirectory(prefix='resonance-ability-test-', dir=ROOT / 'build')
        cls.output = Path(cls.work.name) / 'report.json'
        cls.profile = abilities.load_profile()
        cls.report = abilities.analyze(SimpleNamespace(client_dir=CLIENT, output=cls.output))
        design = abilities.sibling('resonance_ability_test_design', 'read-client-design-data.py')
        cls.source = design.extract(SimpleNamespace(client_dir=CLIENT, paths_file=None,
            paths=[r['sourcePath'] for r in cls.profile['records']],
            extract_dir=Path(cls.work.name) / 'raw', output=Path(cls.work.name) / 'index.json'))
        cls.character_report = abilities.characters.analyze(SimpleNamespace(client_dir=CLIENT,
                                            output=Path(cls.work.name) / 'characters.json'))

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def test_all_nine_original_records_and_skill_entry_bindings(self):
        self.assertEqual((9, 71510, 248, 253291, 346), tuple(self.report[k] for k in
            ['recordCount', 'recordBytes', 'matchedNativeMethods', 'matchedNativeBytes',
             'postfixExpressionsPreserved']))
        self.assertEqual({'task': 74, 'predicate': 26, 'target': 4, 'sequence': 2}, self.report['bindings'])
        self.assertEqual(141295, self.report['indexEntryCount'])
        for row in self.report['records']:
            self.assertIs(True, row['fullyConsumed'])
            names = {a['Name'] for a in row['decoded']['AbilityList']}
            basename = Path(row['sourcePath']).name.replace('_Ability', '_Config')
            config = next(r['decoded'] for r in self.character_report['records']
                          if Path(r['sourcePath']).name == basename)
            for skill in config['SkillList']:
                self.assertIn(skill['EntryAbility'], names)
            external = {name for skill in config['SkillAbilityList'] for name in skill['AbilityList']} - names
            # The destruction config has one separate shared camera dependency.
            self.assertEqual({'BattleEventAbility_620101_Camera'} if '120_Config' in basename else set(), external)

    def test_report_does_not_claim_expression_or_runtime_or_client_acceptance(self):
        parsed = json.loads(self.output.read_text('utf-8'), parse_constant=lambda v: self.fail(v))
        for key in ['expressionEvaluationVerified', 'jsonInstalled', 'clientExecutableStarted',
                    'clientModified', 'allResonanceAbilitiesImplemented', 'clientGameplayVerified']:
            self.assertIs(False, parsed[key])
        self.assertIs(True, parsed['defaultsNotMaterialized'])
        for row in parsed['records']:
            for ability in row['decoded']['AbilityList']:
                self.assertNotIn('ServantWhiteList', ability)

    def test_every_truncated_original_prefix_and_trailing_byte_stop(self):
        for record in self.source['records']:
            raw = Path(record['extractedFile']).read_bytes()
            self.assertEqual(record['sha256'], hashlib.sha256(raw).hexdigest())
            for end in range(len(raw)):
                with self.subTest(record=record['sourcePath'], end=end), self.assertRaises(ValueError):
                    abilities.Reader(raw[:end], self.profile).ability()
            with self.assertRaises(ValueError):
                abilities.Reader(raw + b'\x00', self.profile).ability()


if __name__ == '__main__':
    unittest.main()
