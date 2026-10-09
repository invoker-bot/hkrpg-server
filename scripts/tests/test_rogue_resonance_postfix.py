"""原生虚拟机与原始共鸣表达式结构的有限回归；没有运行 DLL。"""
import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_postfix', ROOT / 'scripts/read-rogue-resonance-postfix.py')
postfix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(postfix)


class ResonancePostfixFormatTest(unittest.TestCase):
    def test_complete_functions_and_actual_byte_opcode_constants(self):
        profile = postfix.load_profile()
        self.assertEqual((10, 8294), (len(profile['nativeMethods']), profile['nativeBytes']))
        for method in profile['nativeMethods']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual('PE Exception Directory', method['bounds'])
            self.assertEqual(len(raw), int(method['endRva'], 16) - int(method['rva'], 16))
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
        self.assertEqual(10362, profile['opCodeType']['typeIndex'])
        values = profile['opCodeConstants']
        self.assertEqual(list(range(18)), [v['value'] for v in values])
        self.assertEqual({5}, {v['nativeKind'] for v in values})
        self.assertEqual(['Constant', 'GetGlobal', 'Add', 'Subtract', 'Multiply', 'Divide',
            'Modulo', 'Equal', 'NotEqual', 'Less', 'Greater', 'LessOrEqual', 'GreaterOrEqual',
            'TernaryCondition', 'Negative', 'Not', 'Call', 'Return'], [v['name'] for v in values])
        self.assertEqual([0, 1, 2, 3, 4, 5, 14, 16, 17], profile['acceptedOpCodes'])
        self.assertEqual([1], profile['acceptedCallArgumentCounts'])

    def test_all_switch_targets_match_original_complete_vm_function(self):
        profile = postfix.load_profile()
        method = next(m for m in profile['nativeMethods'] if m['index'] == 70469)
        raw = bytes.fromhex(method['bytesHex'])
        start, table = int(method['rva'], 16), int(profile['vmSwitchTableRva'], 16)
        actual = [table + value for value in struct.unpack_from('<18i', raw, table - start)]
        self.assertEqual([int(value, 16) for value in profile['vmSwitchTargets']], actual)
        self.assertTrue(all(start <= value < table for value in actual))
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        instructions = {i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + start)}
        self.assertEqual(('lea', 'rbp, [rip + 0x1375]'),
            (instructions[0x1d23e524].mnemonic, instructions[0x1d23e524].op_str))
        self.assertEqual(('jmp', 'rdx'), (instructions[0x1d23e533].mnemonic, instructions[0x1d23e533].op_str))
        # Noncommutative arithmetic passes second-popped value as rcx and first as rdx.
        self.assertEqual('rcx, rax', instructions[0x1d23e78e].op_str)
        self.assertEqual('rdx, rbx', instructions[0x1d23e791].op_str)
        self.assertEqual(('call', '0x19dccb2c0'),
            (instructions[0x1d23e794].mnemonic, instructions[0x1d23e794].op_str))
        self.assertEqual(('call', '0x19d23fbc0'),
            (instructions[0x1d23ee5a].mnemonic, instructions[0x1d23ee5a].op_str))

    def test_noncommutative_tree_keeps_raw_constants_and_signed_global_keys(self):
        plan = postfix.decode(bytes.fromhex('010001010300000511'), [-9223372036854775808], [-1, 2147483647])
        tree = plan['tree']
        self.assertEqual('Divide', tree['op'])
        self.assertEqual('Subtract', tree['left']['op'])
        self.assertEqual(-1, tree['left']['left']['variableKey'])
        self.assertEqual(2147483647, tree['left']['right']['variableKey'])
        self.assertEqual(-9223372036854775808, tree['right']['fixedSignedRaw'])
        self.assertEqual((6, 2, True, False), (plan['instructionCount'], plan['maxStackDepth'],
            plan['fullyConsumed'], plan['expressionEvaluated']))
        negative = postfix.decode(bytes.fromhex('00000e11'), [9223372036854775807], [])
        self.assertEqual({'op': 'Negative', 'value': {'op': 'Constant', 'tableIndex': 0,
            'fixedSignedRaw': 9223372036854775807}}, negative['tree'])

    def test_call_preserves_one_argument_and_does_not_resolve_callee(self):
        tree = postfix.decode(bytes.fromhex('01000101010205100111'), [], [-100, 200, -300])['tree']
        self.assertEqual(('Call', 1, -100), (tree['op'], tree['argumentCount'], tree['callee']['variableKey']))
        self.assertEqual(('Divide', 200, -300), (tree['arguments'][0]['op'],
            tree['arguments'][0]['left']['variableKey'], tree['arguments'][0]['right']['variableKey']))
        for program in ['00000000100111', '01000101100011', '01000101100211']:
            with self.subTest(program=program), self.assertRaises(ValueError):
                postfix.decode(bytes.fromhex(program), [0], [0, 1])

    def test_scalar_types_sizes_and_structure_bounds_are_rejected(self):
        for program, constants, variables in [(b'', [], []), (b'\x11' * 256, [], []),
            ([0, 0, 17], [0], []), (b'\x00\x00\x11', [True], []),
            (b'\x00\x00\x11', [1.0], []), (b'\x00\x00\x11', [1 << 63], []),
            (b'\x01\x00\x11', [], [-(1 << 31) - 1]),
            (b'\x01\x00\x11', [], [0] * 256),
            (b'\x01\x00' * 65 + b'\x11', [], [0]),
            (b'\x00\x00' + b'\x0e' * 32 + b'\x11', [0], [])]:
            with self.subTest(size=len(program), constants=constants[:2], variables=variables[:2]), self.assertRaises(ValueError):
                postfix.decode(program, constants, variables)

    def test_output_boundary_is_checked_before_source_read(self):
        for output in [CLIENT / 'result.json', ROOT / 'resources/Config/test.json', ROOT / 'config.json']:
            with self.subTest(output=str(output)), self.assertRaises(ValueError):
                postfix.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定客户端，跳过原始表达式读取')
class ResonancePostfixOriginalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory(prefix='postfix-original-test-', dir=ROOT / 'build') as temporary:
            cls.report = postfix.analyze(SimpleNamespace(client_dir=CLIENT, output=Path(temporary) / 'report.json'))

    def test_all_original_expression_trees_keep_real_unresolved_inputs(self):
        report = self.report
        self.assertEqual((9, 71510, 346, 32, 10, 8294, 18), (report['recordCount'], report['recordBytes'],
            report['expressionCount'], report['programCount'], report['matchedNativeMethods'],
            report['matchedNativeBytes'], report['opCodeConstantsMatched']))
        for row in report['expressions']:
            self.assertTrue(row['path'].startswith('Config/ConfigAbility/BattleEvent/Avatar_RogueBattleevent'))
            self.assertTrue(row['plan']['fullyConsumed'])
            self.assertFalse(row['plan']['expressionEvaluated'])
        self.assertTrue(report['expressionTopologyVerified'])
        for key in ['expressionEvaluationVerified', 'variableContextResolved', 'callImplementationsResolved',
                    'jsonInstalled', 'clientExecutableStarted', 'clientModified',
                    'allResonanceAbilitiesImplemented', 'clientGameplayVerified']:
            self.assertFalse(report[key])

    def test_every_unique_original_program_prefix_and_trailing_byte_stops(self):
        representatives = {row['rawPostfixBytes']: row for row in self.report['expressions']}
        self.assertEqual(32, len(representatives))
        for code, row in representatives.items():
            raw = bytes.fromhex(code)
            for end in range(len(raw)):
                with self.subTest(code=code, end=end), self.assertRaises(ValueError):
                    postfix.decode(raw[:end], row['fixedSignedConstants'], row['variableKeys'])
            with self.subTest(code=code, extra=True), self.assertRaises(ValueError):
                postfix.decode(raw + b'\x00', row['fixedSignedConstants'], row['variableKeys'])


if __name__ == '__main__':
    unittest.main()
