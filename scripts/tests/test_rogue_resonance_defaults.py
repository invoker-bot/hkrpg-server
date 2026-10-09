"""未序列化范围的原生初始化、标准数值边界与原始固定声明回归。"""
from copy import deepcopy
from fractions import Fraction
import hashlib
import importlib.util
from pathlib import Path
import random
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_defaults', ROOT / 'scripts/read-rogue-resonance-defaults.py')
defaults = importlib.util.module_from_spec(spec)
spec.loader.exec_module(defaults)


class ResonanceDefaultsTest(unittest.TestCase):
    def fixture_record(self):
        profile = defaults.load_profile()
        return {**profile['sourceRecord'], 'decoded': {'AbilityList': [{}, {}, {
            'Name': profile['sourceAbilityName'], 'DynamicValues': deepcopy(profile['sourceDynamicValues'])}]}}

    def instructions(self):
        profile = defaults.load_profile()
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        decoder.detail = True
        instructions = {}
        for method in profile['nativeMethods']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual('PE Exception Directory', method['bounds'])
            self.assertEqual(len(raw), int(method['endRva'], 16) - int(method['rva'], 16))
            instructions.update({i.address - 0x180000000: i
                for i in decoder.disasm(raw, 0x180000000 + int(method['rva'], 16))})
        return instructions

    def test_complete_read_create_and_static_initialize_methods_match_fixed_client(self):
        self.assertEqual((3, 4968), defaults.verify_native(CLIENT))
        self.assertEqual([74469, 74759, 68417], [m['index'] for m in defaults.load_profile()['nativeMethods']])

    def test_absent_range_branch_keeps_native_defaults_before_fixed_cell_creation(self):
        ins = self.instructions()
        for address, expected in [
                (0x1d1fca58, ('test', 'bl, bl')),
                (0x1d1fca5a, ('je', '0x19d1fcab6')),
                (0x1d1fc775, ('mov', 'qword ptr [rsp + 0xf8], r12')),
                (0x1d1fc77d, ('mov', 'qword ptr [rsp + 0x100], r14')),
                (0x1d1fc7a6, ('call', '0x19cd7e070')),
                (0x1cd7e093, ('call', '0x19dcd1a00')),
                (0x1cd7e0b8, ('mov', 'qword ptr [rbx + 0x30], rax')),
                (0x1cd7e0c0, ('mov', 'qword ptr [rbx + 0x38], rax')),
                (0x1cd7e0c8, ('mov', 'qword ptr [rbx + 0x40], rax'))]:
            self.assertEqual(expected, (ins[address].mnemonic, ins[address].op_str), hex(address))

    def test_bound_initializers_and_reader_rip_targets_agree(self):
        profile = defaults.load_profile()
        ins = self.instructions()
        pairs = [(0x1d1fca10, 0x9343cb8), (0x1d1fca17, 0x9343ca0),
                 (0x1dcd3a84, 0x556db60), (0x1dcd3a8b, 0x9343cb8), (0x1dcd3a7d, 0x9343ca0)]
        for address, target in pairs:
            instruction = ins[address]
            operand = next(o for o in instruction.operands if o.type == capstone.x86.X86_OP_MEM)
            self.assertEqual(capstone.x86.X86_REG_RIP, operand.mem.base)
            self.assertEqual(target, instruction.address + instruction.size + operand.mem.disp - 0x180000000)
        value = ins[0x1dcd3a73]
        self.assertEqual(('movabs', profile['maximumInitializer']['valueInstructionOperand']), (value.mnemonic, value.op_str))
        self.assertEqual(profile['maximumInitializer']['raw'], value.operands[1].imm)
        self.assertEqual(profile['minimumInitializer']['raw'], int.from_bytes(
            bytes.fromhex(profile['minimumInitializer']['sourceBytesHex'])[:8], 'little', signed=True))

    def test_every_standard_word_is_inside_extended_defaults_using_exact_rationals(self):
        profile = defaults.load_profile()
        lower = Fraction(profile['minimumInitializer']['raw'] & ~1, 1 << 26)
        upper = Fraction(profile['maximumInitializer']['raw'] & ~1, 1 << 26)
        self.assertEqual((-99999999999, 99999999999), (lower, upper))
        rng = random.Random(46074469)
        words = [-(1 << 63), (1 << 63) - 2, 0, 1 << 33, -(1 << 33)]
        words += [(rng.randrange(-(1 << 63), 1 << 63) & ~1) for _ in range(4096)]
        for word in words:
            value = Fraction(word, 1 << 33)
            self.assertLess(lower, value)
            self.assertLess(value, upper)

    def test_original_four_fixed_cells_have_zero_read_type_and_no_serialized_range(self):
        self.assertEqual({-978103959: 0, 1555818884: 0, -38167523: 0, -1803181471: 0},
                         defaults.verify_source_declarations(self.fixture_record()))

    def test_changed_identity_read_mode_value_bounds_and_path_are_rejected(self):
        for kind in ['identity', 'name', 'path', 'key', 'value', 'read', 'range', 'duplicate']:
            record = self.fixture_record()
            ability = record['decoded']['AbilityList'][2]
            rows = ability['DynamicValues']['floats']
            if kind == 'identity': record['sha256'] = '0' * 64
            elif kind == 'name': ability['Name'] += '_changed'
            elif kind == 'path': record['decoded']['AbilityList'].pop()
            elif kind == 'key': rows[0]['key'] += 1
            elif kind == 'value': rows[0]['fixedSignedRaw'] = 1
            elif kind == 'read': rows[0]['rawReadType'] = 1
            elif kind == 'range': rows[0]['minimum'] = 0
            else: rows.append(deepcopy(rows[0]))
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                defaults.verify_source_declarations(record)

    def test_live_source_evidence_keeps_unimplemented_context_and_effect_flags_closed(self):
        output = ROOT / 'build/rogue-resonance-defaults-test-evidence.json'
        report = defaults.analyze(SimpleNamespace(client_dir=CLIENT, output=output))
        self.assertEqual((True, 3, 4968), tuple(report[k] for k in ['passed', 'matchedNativeMethods', 'matchedNativeBytes']))
        self.assertTrue(report['defaultSourceStandardCellsImplemented'])
        self.assertEqual(4, len(report['sourceFixedDeclarations']))
        for key in ['mixedClampImplemented', 'extendedCellsImplemented', 'contextLookupSelectionImplemented',
                    'actualParameterValuesResolved', 'liveBattleContextAttached', 'abilityExecutionVerified',
                    'jsonInstalled', 'clientExecutableStarted', 'clientModified', 'clientGameplayVerified',
                    'allResonanceAbilitiesImplemented', 'serializedCustomRangesAcceptedBySourceFactory']:
            self.assertIs(False, report[key], key)

    def test_output_outside_build_or_inside_client_is_rejected_before_reading(self):
        for output in [ROOT / 'defaults-evidence.json', CLIENT / 'defaults-evidence.json']:
            with self.subTest(output=output), self.assertRaises(ValueError):
                defaults.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


if __name__ == '__main__':
    unittest.main()
