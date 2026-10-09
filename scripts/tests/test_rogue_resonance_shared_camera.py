"""共享镜头布局引用和原始动作边界的回归。"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('shared_camera', ROOT / 'scripts/read-rogue-resonance-shared-camera.py')
camera = importlib.util.module_from_spec(spec)
spec.loader.exec_module(camera)


class SharedCameraLayoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = camera.load_profile()
        cls.work = tempfile.TemporaryDirectory(prefix='camera-test-', dir=ROOT / 'build')
        work = Path(cls.work.name)
        design = camera.abilities.sibling('shared_camera_test_design', 'read-client-design-data.py')
        report = design.extract(SimpleNamespace(client_dir=CLIENT, paths_file=None,
            paths=[r['sourcePath'] for r in cls.profile['records']], extract_dir=work / 'raw',
            output=work / 'index.json'))
        cls.raw = {r['sourcePath']: Path(r['extractedFile']).read_bytes() for r in report['records']}
        cls.ability_raw = cls.raw[cls.profile['sharedAbility']['sourcePath']]
        cls.layout_raw = next(v for k, v in cls.raw.items() if '.layout.' in k)
        cls.layouts = camera.LayoutReader(cls.layout_raw).layout()
        cls.caller = camera.abilities.Reader(cls.raw[cls.profile['callerRecord']['sourcePath']]).ability()

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def test_wrong_type_duplicate_identity_offset_and_dependency_are_rejected(self):
        changes = [('wrong type', lambda rows: rows[3].update(TypeId=0)),
                   ('duplicate name', lambda rows: rows[-1].update(UniqueName=rows[3]['UniqueName'])),
                   ('duplicate offset', lambda rows: rows[-1].update(Offset=rows[3]['Offset'])),
                   ('unresolved dependency', lambda rows: rows[3].update(Dependency=['Unresolved_Ability'])),
                   ('negative offset', lambda rows: rows[-1].update(Offset=-1))]
        for name, mutate in changes:
            with self.subTest(name=name), self.assertRaises(ValueError):
                rows = copy.deepcopy(self.layouts)
                mutate(rows)
                camera.resolve_camera(self.ability_raw, rows)

    def test_original_layout_and_camera_are_consumed_without_defaults(self):
        self.assertEqual(76, len(self.layouts))
        self.assertEqual({'0x7'}, {r['$mask'] for r in self.layouts})
        self.assertTrue(all('Dependency' not in r for r in self.layouts))
        resolved = camera.resolve_camera(self.ability_raw, self.layouts)
        self.assertEqual((1617, 2201, 584), tuple(resolved[k] for k in ['offset', 'endOffset', 'length']))
        self.assertEqual(self.profile['sharedAbility']['sha256'], resolved['sha256'])
        actions = resolved['decoded']['OnStart']
        self.assertEqual([1458, 3769, 3907, 3769, 3907, 3769, 3907, 3769,
                          3907, 2108, 3769, 1458, 3907, 3769, 3907], [a['taskId'] for a in actions])
        self.assertEqual('0x590', actions[9]['$mask'])
        self.assertAlmostEqual(0.01, actions[9]['BlurFeather'])
        self.assertNotIn('Active', actions[9])
        self.assertNotIn('OnEnd', resolved['decoded'])
        self.assertTrue(resolved['fullyConsumed'])

    def test_extra_field_scope_does_not_widen_the_nine_ability_reader(self):
        raw = self.ability_raw[1617:2201]
        with self.assertRaisesRegex(ValueError, r'RadialBlurEffect.BlurFeather'):
            camera.abilities.Reader(raw).obj('TurnBasedAbilityConfig')
        original = camera.abilities.load_profile()
        widened = camera.camera_ability_profile()
        differences = {name: sorted(set(s['allowedFields']) - set(original['schemas'][name]['allowedFields']))
            for name, s in widened['schemas'].items() if s['allowedFields'] != original['schemas'][name]['allowedFields']}
        self.assertEqual({'RadialBlurEffect': ['BlurFeather']}, differences)
        self.assertNotIn('BlurFeather', camera.abilities.load_profile()['schemas']['RadialBlurEffect']['allowedFields'])

    def test_every_layout_and_camera_truncated_prefix_and_trailing_byte_is_rejected(self):
        for size in range(len(self.layout_raw)):
            with self.subTest(layoutPrefix=size), self.assertRaises(ValueError):
                camera.LayoutReader(self.layout_raw[:size]).layout()
        with self.assertRaises(ValueError):
            camera.LayoutReader(self.layout_raw + b'\x00').layout()
        raw = self.ability_raw[1617:2201]
        profile = camera.camera_ability_profile()
        for size in range(len(raw)):
            with self.subTest(cameraPrefix=size), self.assertRaises(ValueError):
                reader = camera.abilities.Reader(raw[:size], profile)
                reader.obj('TurnBasedAbilityConfig')
        reader = camera.abilities.Reader(raw + b'\x00', profile)
        reader.obj('TurnBasedAbilityConfig')
        self.assertEqual(1, len(reader.data) - reader.pos)

    def test_layout_root_mask_enum_width_and_array_boundaries(self):
        for raw in [b'\x00', b'\x02', b'\x01\x01', b'\x01\x00',
                    b'\x01\x80\x40', b'\x01\x02\x10',
                    b'\x01\x02\x02\x80\x80\x04']:
            with self.subTest(raw=raw.hex()), self.assertRaises(ValueError):
                camera.LayoutReader(raw).layout()

    def test_malformed_resolved_layout_identities_and_boundaries(self):
        changes = [lambda rows: rows[3].pop('Offset'), lambda rows: rows[3].pop('TypeId'),
                   lambda rows: rows[3].update(UniqueName=''), lambda rows: rows[3].update(UniqueName=1),
                   lambda rows: rows[3].update(TypeId=True), lambda rows: rows[3].update(TypeId=65536),
                   lambda rows: rows[3].update(TypeId=8), lambda rows: rows[3].update(Offset=True),
                   lambda rows: rows[3].update(Offset=1617.0),
                   lambda rows: rows[3].update(Offset=len(self.ability_raw)),
                   lambda rows: rows[3].update(Offset=1618), lambda rows: rows[4].update(Offset=2202),
                   lambda rows: rows[3].update(Dependency=[]), lambda rows: rows[3].update(Dependency=None),
                   lambda rows: rows[3].update(UniqueName='Unresolved_Camera'),
                   lambda rows: rows.__setitem__(3, None)]
        for index, mutate in enumerate(changes):
            with self.subTest(index=index), self.assertRaises(ValueError):
                rows = copy.deepcopy(self.layouts)
                mutate(rows)
                camera.resolve_camera(self.ability_raw, rows)
        for rows in [None, [], tuple(self.layouts), self.layouts * 60]:
            with self.subTest(rowsType=type(rows)), self.assertRaises(ValueError):
                camera.resolve_camera(self.ability_raw, rows)

    def test_native_fields_and_seek_bind_to_complete_pe_functions(self):
        self.assertEqual((13, 5621), (len(self.profile['nativeMethods']), self.profile['nativeBytes']))
        self.assertEqual((13, 5621), camera.verify_native(CLIENT))
        self.assertEqual(list(range(8)), [r['value'] for r in self.profile['layoutEnumConstants']])
        for method in self.profile['nativeMethods']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual('PE Exception Directory', method['bounds'])
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual(int(method['endRva'], 16) - int(method['rva'], 16), len(raw))
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        base = camera.abilities.load_profile()
        method = next(m for m in base['nativeMethods'] if m['index'] == 118711)
        instructions = {(hex(i.address - 0x180000000), i.mnemonic, i.op_str)
            for i in decoder.disasm(bytes.fromhex(method['bytesHex']), 0x180000000 + int(method['rva'], 16))}
        self.assertIn(('0x1d9393f7', 'test', 'ebx, 0x400'), instructions)
        self.assertIn(('0x1d939412', 'add', 'rsi, 0x44'), instructions)
        self.assertIn(('0x1d939439', 'jmp', '0x19d1c4080'), instructions)
        method = next(m for m in self.profile['nativeMethods'] if m['index'] == 522370)
        instructions = {(hex(i.address - 0x180000000), i.mnemonic, i.op_str)
            for i in decoder.disasm(bytes.fromhex(method['bytesHex']), 0x180000000 + int(method['rva'], 16))}
        self.assertIn(('0xbef9a5a', 'mov', 'edx, dword ptr [rdi + 0x20]'), instructions)
        self.assertIn(('0xbef9a60', 'call', '0x19dd0f520'), instructions)
        self.assertIn(('0xbef9a91', 'call', '0x19dc34150'), instructions)

    def test_static_report_and_output_boundary(self):
        output = Path(self.work.name) / 'report.json'
        report = camera.analyze(SimpleNamespace(client_dir=CLIENT, output=output))
        self.assertEqual((3, 30452, 15), (report['recordCount'], report['recordBytes'], report['taskCount']))
        self.assertTrue(report['sharedCameraDefinitionResolved'])
        self.assertEqual(76, len(report['layouts']))
        self.assertTrue(report['callerFullyConsumed'])
        self.assertEqual(1, len(report['callerReferences']))
        for key in ['jsonInstalled', 'abilityExecutionVerified', 'allResonanceAbilitiesImplemented',
                    'clientGameplayVerified', 'clientExecutableStarted', 'clientModified']:
            self.assertIs(False, report[key])
        with self.assertRaises(ValueError):
            camera.analyze(SimpleNamespace(client_dir=CLIENT, output=CLIENT / 'camera-evidence.json'))

    def test_actual_trigger_binds_to_exactly_one_shared_definition(self):
        resolved = camera.resolve_camera(self.ability_raw, self.layouts)
        links = camera.bind_caller(self.caller, resolved)
        self.assertEqual([{'callingAbility': 'Avatar_RogueBattleevent120_Skill03_Phase02',
            'callback': 'OnStart', 'actionOrdinal': 0, 'taskId': 3573, 'targetAlias': 'Caster',
            'isSkillPerform': True, 'referencedAbility': 'BattleEventAbility_620101_Camera',
            'callerObjectOffset': 497, 'resolvedDefinitionOffset': 1617}], links)
        mutations = [lambda c: c['AbilityList'][2]['OnStart'][0].update(taskId=3572),
            lambda c: c['AbilityList'][2]['OnStart'][0]['AbilityName'].update(Value='Unknown'),
            lambda c: c['AbilityList'][2]['OnStart'][0]['AbilityName'].update(Custom=True, **{'$mask': '0x5'}),
            lambda c: c['AbilityList'][2]['OnStart'][0]['TargetType'].update(Alias='Target'),
            lambda c: c['AbilityList'][2]['OnStart'][0].update(IsSkillPerform=False),
            lambda c: c['AbilityList'].append(copy.deepcopy(c['AbilityList'][2]))]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index), self.assertRaises(ValueError):
                invalid = copy.deepcopy(self.caller)
                mutate(invalid)
                camera.bind_caller(invalid, resolved)


if __name__ == '__main__':
    unittest.main()
