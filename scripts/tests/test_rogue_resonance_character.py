"""九条原始角色记录的有限解析、原生交叉证据及损坏/未知格式边界。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_character', ROOT / 'scripts/read-rogue-resonance-character.py')
character = importlib.util.module_from_spec(spec)
spec.loader.exec_module(character)


def uv(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


class ResonanceCharacterFormatTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = character.load_profile()

    def test_complete_pe_methods_keep_exact_bounds_and_hashes(self):
        methods = self.profile['nativeMethods']
        self.assertEqual({106272, 106296, 110182, 104261, 110162, 74469,
                          106108, 110164, 104080, 104078, 104077}, {m['index'] for m in methods})
        self.assertEqual(25520, sum(len(bytes.fromhex(m['bytesHex'])) for m in methods))
        for method in methods:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual('PE Exception Directory', method['bounds'])
            self.assertEqual(int(method['endRva'], 16) - int(method['rva'], 16), len(raw))
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())

    def test_root_factory_one_points_to_actual_battle_event_reader(self):
        method = next(m for m in self.profile['nativeMethods'] if m['index'] == 106272)
        raw = bytes.fromhex(method['bytesHex'])
        base, table = int(method['rva'], 16), int(self.profile['rootFactoryJumpTableRva'], 16)
        targets = [table + offset for offset in struct.unpack_from('<5i', raw, table - base)]
        self.assertEqual(0x1d53d517, targets[1])
        cs = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        instructions = {i.address: (i.mnemonic, i.op_str) for i in cs.disasm(raw, 0x180000000 + base)}
        self.assertEqual(('jmp', '0x19d42a8a0'), instructions[0x19d53d65a])

    def test_serialized_field_89_and_native_signed_int32_set_max_sp_at_248(self):
        fields = self.profile['schemas']['BattleEventConfig']['fields']
        self.assertEqual(95, len(fields))
        self.assertNotIn('ApplyDLCPaths', [f['name'] for f in fields])
        self.assertEqual(('MaxSP', 0x248, 8),
                         (fields[89]['name'], fields[89]['instanceOffset'], fields[89]['nativeType']['kind']))
        method = next(m for m in self.profile['nativeMethods'] if m['index'] == 106296)
        cs = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        instructions = {i.address: (i.mnemonic, i.op_str) for i in cs.disasm(
            bytes.fromhex(method['bytesHex']), 0x180000000 + int(method['rva'], 16))}
        self.assertEqual(('test', 'r14d, 0x2000000'), instructions[0x19d42c126])
        self.assertEqual(('call', '0x19d1aed60'), instructions[0x19d42c153])
        self.assertEqual(('mov', 'dword ptr [rsi + 0x248], eax'), instructions[0x19d42c163])

    def test_unknown_mask_or_known_but_unmatched_field_and_tag_stop(self):
        for raw in (uv(1) + uv(0) + uv(1 << 31),
                    uv(1) + uv(1) + uv(0), uv(0) + uv(0) + uv(0)):
            with self.subTest(raw=raw.hex()), self.assertRaises(ValueError):
                character.Reader(raw, self.profile).character()

    def test_unsigned_overflow_overlong_and_field_width_stop(self):
        for raw, bits in [(b'\xff' * 9 + b'\x02', 64), (b'\x80\x00', 64), (uv(1 << 32), 32)]:
            with self.subTest(raw=raw.hex()), self.assertRaises(ValueError):
                character.Reader(raw, self.profile).unsigned(bits)

    def test_unmatched_dynamic_dictionary_modes_and_duplicate_keys_stop(self):
        # Packed count, signed key, expression byte, raw fixed integer, range byte, ReadInfo byte.
        for raw in (uv(1 << 16), b'\x01\x00\x01', b'\x01\x00\x00\x00\x01',
                    b'\x01\x00\x00\x00\x00\x01', b'\x02' + b'\x00' * 10):
            with self.subTest(raw=raw.hex()), self.assertRaises(ValueError):
                character.Reader(raw, self.profile).dynamic_values()

    def test_unknown_dynamic_float_ai_variant_value_struct_and_enum_stop(self):
        for raw, kind in [(b'\x01', {'kind': 17, 'name': 'DynamicFloat'}),
                          (b'\x01', {'kind': 18, 'name': 'ComplexSkillAIFactorGroupWeight'}),
                          (b'\x00', {'kind': 17, 'name': 'UnknownValueType'}),
                          (uv(1998), {'kind': 17, 'name': 'SkillType'})]:
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                character.Reader(raw, self.profile).read(kind)

    def test_invalid_boolean_array_size_utf8_and_trailing_bytes_stop(self):
        for raw, kind in [(b'\x02', {'kind': 2}),
                          (uv(1026), {'kind': 29, 'elementType': {'kind': 2}}),
                          (b'\x01\xff', {'kind': 14})]:
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                character.Reader(raw, self.profile).read(kind)
        with self.assertRaises(ValueError):
            character.Reader(b'\x01\x00\x00\x00', self.profile).character()

    def test_nonfinite_float_preserves_bits_with_standard_json(self):
        reader = character.Reader(bytes.fromhex('0000807f'), self.profile)
        value = reader.read({'kind': 12})
        self.assertEqual({'floatBits': '0000807f', 'nonFinite': 'Infinity'}, value)
        self.assertEqual(value, json.loads(json.dumps(value, allow_nan=False)))

    def test_depth_and_node_limits_stop(self):
        reader = character.Reader(b'\x00', self.profile)
        reader.depth = 32
        with self.assertRaises(ValueError):
            reader.object('VCameraConfig')
        reader = character.Reader(b'\x00', self.profile)
        reader.nodes = 4096
        with self.assertRaises(ValueError):
            reader.read({'kind': 2})

    def test_evidence_output_cannot_target_client_or_operator_resources(self):
        for output in [CLIENT / 'result.json', ROOT / 'resources/config.json', ROOT / 'config.json']:
            with self.subTest(output=str(output)), self.assertRaises(ValueError):
                character.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定客户端，跳过原始记录核对')
class ResonanceCharacterOriginalRecordsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.TemporaryDirectory(prefix='resonance-character-test-', dir=ROOT / 'build')
        cls.output = Path(cls.work.name) / 'report.json'
        cls.report = character.analyze(SimpleNamespace(client_dir=CLIENT, output=cls.output))
        cls.original = json.loads((ROOT / 'src/test/resources/fixtures/rogue-resonance-4.6.json').read_text('utf-8'))
        cls.profile = character.load_profile()
        spec = importlib.util.spec_from_file_location('character_test_design', ROOT / 'scripts/read-client-design-data.py')
        design = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(design)
        cls.extraction = design.extract(SimpleNamespace(client_dir=CLIENT, paths_file=None,
            paths=[r['sourcePath'] for r in cls.profile['records']],
            extract_dir=Path(cls.work.name) / 'raw', output=Path(cls.work.name) / 'index.json'))

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def test_nine_original_records_fully_consumed_with_max_sp_and_two_skill_bindings(self):
        self.assertEqual((9, 5395, 11, 25520), tuple(self.report[k] for k in
                         ['recordCount', 'recordBytes', 'matchedNativeMethods', 'matchedNativeBytes']))
        self.assertEqual(141295, self.report['indexEntryCount'])
        for row in self.report['records']:
            event = next(e for e in self.original['events'] if e['Config'] == row['sourcePath'])
            config = row['decoded']
            self.assertIs(True, row['fullyConsumed'])
            self.assertIs(True, config['IsPlayerControlled'])
            self.assertIs(False, config['HaveActionDelay'])
            self.assertEqual(100, config['MaxSP'])
            skills = {s['Name']: s for s in config['SkillList']}
            bindings = {s['Skill']: s['AbilityList'] for s in config['SkillAbilityList']}
            self.assertEqual({'Skill03', 'SkillP01'}, skills.keys())
            for trigger, identity in zip(['Skill03', 'SkillP01'], event['SkillIDList'], strict=True):
                source = next(s for s in self.original['skills'] if s['SkillID'] == identity)
                self.assertEqual(trigger, source['SkillTriggerKey'])
                self.assertIn(skills[trigger]['EntryAbility'], bindings[trigger])
            active = next(s for s in self.original['skills'] if s['SkillID'] == event['SkillIDList'][0])
            self.assertEqual(config['MaxSP'], active['SPNeed']['Value'])

    def test_report_preserves_absent_defaults_and_partial_gameplay_boundaries(self):
        parsed = json.loads(self.output.read_text('utf-8'), parse_constant=lambda value: self.fail(value))
        for key in ['jsonInstalled', 'clientExecutableStarted', 'clientModified',
                    'allResonanceAbilitiesImplemented', 'clientGameplayVerified']:
            self.assertIs(False, parsed[key])
        self.assertIs(True, parsed['defaultsNotMaterialized'])
        erudition = next(r['decoded'] for r in parsed['records'] if '127_Config' in r['sourcePath'])
        self.assertNotIn('DynamicValues', erudition)
        self.assertEqual('LightSelect', erudition['SkillList'][0]['TargetInfo']['TargetType']['name'])
        self.assertIs(True, erudition['SkillList'][0]['TargetInfo']['AvoidSelf'])

    def test_every_truncated_original_prefix_and_each_trailing_byte_stop(self):
        for record in self.extraction['records']:
            raw = Path(record['extractedFile']).read_bytes()
            self.assertEqual(record['sha256'], hashlib.sha256(raw).hexdigest())
            for end in range(len(raw)):
                with self.subTest(record=record['sourcePath'], end=end), self.assertRaises(ValueError):
                    character.Reader(raw[:end], self.profile).character()
            with self.assertRaises(ValueError):
                character.Reader(raw + b'\x00', self.profile).character()


if __name__ == '__main__':
    unittest.main()
