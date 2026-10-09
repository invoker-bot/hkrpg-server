"""共享能力表新增字段、动作注册和参数查询的回归。"""
import importlib.util
import copy
import hashlib
import json
from pathlib import Path
import re
import tempfile
from types import SimpleNamespace
import unittest
import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('shared_parent', ROOT / 'scripts/read-rogue-resonance-shared-parent.py')
parent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parent)


def uv(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result + bytes([value]))


class SharedParentFormatTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = parent.load_profile()
        cls.work = tempfile.TemporaryDirectory(prefix='parent-test-', dir=ROOT / 'build')
        design = parent.camera.abilities.sibling('shared_parent_test_design', 'read-client-design-data.py')
        camera_profile = parent.camera.load_profile()
        records = [cls.profile['parentRecord'], next(r for r in camera_profile['records'] if '.layout.' in r['sourcePath'])]
        source = design.extract(SimpleNamespace(client_dir=CLIENT, paths_file=None,
            paths=[r['sourcePath'] for r in records], extract_dir=Path(cls.work.name) / 'raw',
            output=Path(cls.work.name) / 'source.json'))
        cls.raw = {r['sourcePath']: Path(r['extractedFile']).read_bytes() for r in source['records']}
        cls.parent_raw = cls.raw[cls.profile['parentRecord']['sourcePath']]
        cls.layouts = parent.camera.LayoutReader(next(v for k, v in cls.raw.items() if '.layout.' in k)).layout()
        cls.schema = parent.ability_profile(cls.profile)

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def test_missing_formats_restore_actual_parent_and_new_fields(self):
        cases = [('parent', self.parent_raw, lambda r: len(r.ability()['AbilityList']), 76),
            ('OnAdd', uv(4) + b'\x00', lambda r: r.obj('TurnBasedAbilityConfig')['OnAdd'], []),
            ('hit slow type', uv(1 << 37) + b'\x06', lambda r: r.obj('AttackData')['HitTimeSlowType'], 3),
            ('healing task', uv(1751) + uv(32) + b'\x00\x06',
                lambda r: r.read({'kind': 18, 'name': 'TaskConfig'})['Healer_HealRatio']['fixedSignedRaw'], 3),
            ('buff parameter query', uv(2847) + uv(4) + b'\x02\x01a\x05\x03\x02',
                lambda r: r.read({'kind': 18, 'name': 'TaskConfig'})['QueryList']['entries'][0][1]['Index'], 2)]
        for name, raw, decode, expected in cases:
            with self.subTest(name=name):
                try:
                    actual = decode(parent.Reader(raw))
                except ValueError:
                    actual = None
                self.assertEqual(expected, actual)

    def test_whole_parent_and_all_76_layout_offsets_match_without_defaults(self):
        reader = parent.Reader(self.parent_raw, self.profile, self.schema)
        value = reader.ability()
        self.assertEqual((21718, 246, 76), (reader.pos, reader.dynamic_expressions, len(value['AbilityList'])))
        records = parent.verify_layout(value, self.layouts, reader.pos)
        self.assertEqual(76, len(records))
        self.assertEqual(3, records[0]['offset'])
        self.assertEqual(21715, sum(r['length'] for r in records))
        self.assertEqual({'$type', '$mask', '$offset', 'AbilityList'}, set(value))
        camera = next(a for a in value['AbilityList'] if a['Name'] == 'BattleEventAbility_620101_Camera')
        self.assertEqual(15, len(camera['OnStart']))
        self.assertEqual(1617, camera['$offset'])
        self.assertEqual(self.profile['parentRecord']['sha256'], hashlib.sha256(self.parent_raw).hexdigest())

    def test_entire_parent_truncated_prefixes_and_trailing_byte_are_rejected(self):
        for length in range(len(self.parent_raw)):
            with self.subTest(prefix=length), self.assertRaises(ValueError):
                parent.Reader(self.parent_raw[:length], self.profile, self.schema).ability()
        with self.assertRaisesRegex(ValueError, '未完整读取'):
            parent.Reader(self.parent_raw + b'\x00', self.profile, self.schema).ability()

    def test_parameter_keys_preserve_uint32_and_missing_level(self):
        raw = b'\x05' + uv(0xffffffff) + uv(0xffffffff)
        value = parent.Reader(raw, self.profile, self.schema).obj('MazeBuffParamKey')
        self.assertEqual(0xffffffff, value['MazeBuffID'])
        self.assertEqual(0xffffffff, value['Index'])
        self.assertNotIn('Level', value)
        with self.assertRaisesRegex(ValueError, '未匹配的序列化字段'):
            parent.Reader(b'\x02\x01', self.profile, self.schema).obj('MazeBuffParamKey')
        with self.assertRaisesRegex(ValueError, '32 位整数溢出'):
            parent.Reader(b'\x01' + uv(1 << 32), self.profile, self.schema).obj('MazeBuffParamKey')

    def test_query_dictionary_rejects_duplicate_keys_bad_sizes_and_unknown_fields(self):
        prefix = uv(2847) + b'\x04'
        for payload in [b'\x01', uv(1026), b'\x04\x01a\x00\x01a\x00',
                        b'\x02\x01a\x08', b'\x02\x01a\x05\x03', b'\x02\x01a\x02\x01']:
            with self.subTest(payload=payload.hex()), self.assertRaises(ValueError):
                parent.Reader(prefix + payload, self.profile, self.schema).read({'kind': 18, 'name': 'TaskConfig'})
        with self.assertRaisesRegex(ValueError, '未知动作类型'):
            parent.Reader(uv(65535) + b'\x00', self.profile, self.schema).read({'kind': 18, 'name': 'TaskConfig'})

    def test_finite_enums_and_original_reader_scopes_remain_independent(self):
        self.assertEqual((124, 355, 38, 76), (len(self.schema['schemas']),
            sum(len(s['allowedFields']) for s in self.schema['schemas'].values()),
            len(self.schema['enums']), len(self.schema['bindings']['task'])))
        base = parent.camera.abilities.load_profile()
        self.assertEqual((121, 348, 37, 74), (len(base['schemas']),
            sum(len(s['allowedFields']) for s in base['schemas'].values()), len(base['enums']), len(base['bindings']['task'])))
        self.assertEqual([3], self.schema['enums']['18216']['allowedRawValues'])
        for raw in [uv(1 << 37) + b'\x00', uv(1 << 37) + b'\x08']:
            with self.assertRaisesRegex(ValueError, '尚未匹配的枚举值'):
                parent.Reader(raw, self.profile, self.schema).obj('AttackData')
        with self.assertRaisesRegex(ValueError, '未匹配的序列化字段'):
            parent.Reader(b'\x40\x00\x00', self.profile, self.schema).obj('ModifyHealData')
        with self.assertRaisesRegex(ValueError, '未知字段掩码'):
            parent.Reader(b'\x08', self.profile, self.schema).obj('MazeBuffParamKey')

    def test_all_six_field_bindings_match_complete_native_instruction_anchors(self):
        base = parent.camera.abilities.load_profile()
        methods = {m['rva']: m for m in base['nativeMethods'] + self.profile['nativeMethods']}
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        self.assertEqual(6, len(self.profile['fieldBindings']))
        for binding in self.profile['fieldBindings']:
            schema = self.schema['schemas'][binding['name']]
            self.assertEqual(binding['metadata'], schema['fields'][binding['ordinal']])
            self.assertIn(binding['field'], schema['allowedFields'])
            method = methods[binding['nativeReaderRva']]
            instructions = {(hex(i.address - 0x180000000), i.mnemonic, i.op_str)
                for i in decoder.disasm(bytes.fromhex(method['bytesHex']), 0x180000000 + int(method['rva'], 16))}
            for anchor in binding['instructionAnchors']:
                self.assertIn(tuple(anchor), instructions)

    def test_task_registrations_factories_and_parameter_delegate_are_matched(self):
        base = parent.camera.abilities.load_profile()
        methods = {m['index']: m for m in base['nativeMethods'] + self.profile['nativeMethods'] if m['index'] is not None}
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        registry = methods[129950]
        instructions = list(decoder.disasm(bytes.fromhex(registry['bytesHex']), 0x180000000 + int(registry['rva'], 16)))
        positions = {i.address - 0x180000000: n for n, i in enumerate(instructions)}
        for tag, binding in self.profile['additionalTaskBindings'].items():
            registration = binding['registration']
            index = positions[int(registration['registrationRva'], 16)]
            write, load = instructions[index], instructions[index - 1]
            match = re.fullmatch(r'qword ptr \[rax \+ (0x[0-9a-f]+)\], (rdx|rcx)', write.op_str)
            self.assertEqual(32 + int(tag) * 8, int(match[1], 16))
            rip = re.fullmatch(match[2] + r', qword ptr \[rip ([+-]) (0x[0-9a-f]+)\]', load.op_str)
            self.assertEqual(int(registration['methodSlotRva'], 16), write.address - 0x180000000
                + (1 if rip[1] == '+' else -1) * int(rip[2], 16))
            factory = methods[binding['factoryMethod']['index']]
            calls = {(i.mnemonic, i.op_str) for i in decoder.disasm(bytes.fromhex(factory['bytesHex']), 0x180000000 + int(factory['rva'], 16))}
            self.assertIn(('call', hex(0x180000000 + int(binding['readerRva'], 16))), calls)
        query = self.profile['queryDictionary']
        self.assertEqual((110276, 18262, '0x1d8350b0'), tuple(query['valueReader'][k] for k in ['index', 'typeIndex', 'rva']))
        self.assertEqual('0x947b978', query['valueReaderSlotRva'])

    def test_layout_rejects_missing_duplicate_or_mismatched_ability_objects(self):
        value = parent.Reader(self.parent_raw, self.profile, self.schema).ability()
        changes = [lambda v: v['AbilityList'].pop(), lambda v: v['AbilityList'][3].update(Name='Unknown'),
            lambda v: v['AbilityList'][3].update(**{'$offset': 1618}),
            lambda v: v['AbilityList'][4].update(**{'$offset': 1617}),
            lambda v: v['AbilityList'][3].update(Name=v['AbilityList'][2]['Name']),
            lambda v: v['AbilityList'][3].update(**{'$offset': True})]
        for index, mutate in enumerate(changes):
            with self.subTest(index=index), self.assertRaises(ValueError):
                invalid = copy.deepcopy(value)
                mutate(invalid)
                parent.verify_layout(invalid, self.layouts, len(self.parent_raw))
        for mutate in [lambda r: r[3].update(TypeId=0), lambda r: r[3].update(Offset=1618),
                       lambda r: r[-1].update(UniqueName=r[0]['UniqueName'])]:
            with self.assertRaises(ValueError):
                invalid = copy.deepcopy(self.layouts)
                mutate(invalid)
                parent.verify_layout(value, invalid, len(self.parent_raw))

    def test_native_constants_and_readonly_static_report(self):
        self.assertEqual((5, 1855), parent.verify_native(CLIENT))
        self.assertEqual(9, len(self.profile['enumConstants']))
        for method in self.profile['nativeMethods']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual('PE Exception Directory', method['bounds'])
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual(int(method['endRva'], 16) - int(method['rva'], 16), len(raw))
        report = parent.analyze(SimpleNamespace(client_dir=CLIENT, output=Path(self.work.name) / 'report.json'))
        self.assertEqual((2, 23717, 23717, 76, 246), tuple(report[k] for k in ['recordCount', 'recordBytes',
            'parsedBytes', 'abilityCount', 'expressionCount']))
        self.assertTrue(report['parentAbilityRecordFullyDecoded'])
        self.assertTrue(report['parentFullyConsumed'] and report['layoutFullyConsumed'])
        for key in ['queryParametersEvaluated', 'expressionEvaluationVerified', 'abilityExecutionVerified',
                    'jsonInstalled', 'allResonanceAbilitiesImplemented', 'clientGameplayVerified',
                    'clientExecutableStarted', 'clientModified']:
            self.assertIs(False, report[key])
        with self.assertRaises(ValueError):
            parent.analyze(SimpleNamespace(client_dir=CLIENT, output=CLIENT / 'parent-evidence.json'))


if __name__ == '__main__':
    unittest.main()
