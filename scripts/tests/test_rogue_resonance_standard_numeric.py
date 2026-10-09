"""全部原始程序及标准运算的有限回归，没有实际战斗变量上下文。"""
from decimal import Decimal, ROUND_FLOOR, localcontext
import hashlib
import importlib.util
from pathlib import Path
import random
import tempfile
from types import SimpleNamespace
import unittest
import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_standard_numeric', ROOT / 'scripts/read-rogue-resonance-standard-numeric.py')
standard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(standard)
ONE = 1 << 33


class ResonanceStandardNumericTest(unittest.TestCase):
    def test_all_original_programs_compile_and_old_floor_scope_remains_closed(self):
        profile = standard.load_profile()
        self.assertEqual(592, len(profile['expressions']))
        self.assertEqual([0, 1, 2, 3, 4, 5, 14, 16, 17], profile['acceptedNumericOpCodes'])
        for row in profile['expressions']:
            plan = standard.compile_program(bytes.fromhex(row['rawPostfixBytes']), row['fixedSignedConstants'], row['variableKeys'])
            self.assertTrue(plan['fullyConsumed'])
            self.assertFalse(plan['expressionEvaluated'])
        for code in ['000000000311', '000000000411', '00000e11']:
            with self.assertRaises(ValueError):
                standard.numeric.compile_program(bytes.fromhex(code), [0], [])

    def test_subtract_and_negative_preserve_order_and_raw_signed_values(self):
        for code, values, expected in [('000000010311', [7 * ONE, 2 * ONE], 5 * ONE),
            ('000000010311', [2 * ONE, 7 * ONE], -5 * ONE), ('00000e11', [ONE // 2], -ONE // 2),
            ('00000e11', [-ONE // 2], ONE // 2), ('01000e11', [], -ONE)]:
            self.assertEqual(expected, standard.evaluate(bytes.fromhex(code), values, [-1], {-1: ONE}))

    def test_multiply_matches_independent_decimal_floor_and_negative_quantum(self):
        for a, b, expected in [(2, 2, 0), (-2, 2, -2), (ONE, ONE, ONE),
                (3 * ONE // 2, ONE // 2, 3 * ONE // 4), (-3 * ONE // 2, ONE // 2, -3 * ONE // 4)]:
            self.assertEqual(expected, standard.evaluate(bytes.fromhex('000000010411'), [a, b], [], {}))
        rand = random.Random(46068474)
        with localcontext() as context:
            context.prec = 128
            for _ in range(4096):
                a, b = rand.randrange(-2**42, 2**42) & ~1, rand.randrange(-2**42, 2**42) & ~1
                expected = int((Decimal(a) * Decimal(b) / Decimal(2**34)).to_integral_value(rounding=ROUND_FLOOR)) * 2
                self.assertEqual(expected, standard.evaluate(bytes.fromhex('000000010411'), [a, b], [], {}))

    def test_all_distinct_original_prefixes_and_trailing_bytes_stop(self):
        rows = {r['rawPostfixBytes']: r for r in standard.load_profile()['expressions']}
        self.assertGreaterEqual(len(rows), 32)
        for code, row in rows.items():
            raw = bytes.fromhex(code)
            for end in range(len(raw)):
                with self.subTest(code=code, end=end), self.assertRaises(ValueError):
                    standard.compile_program(raw[:end], row['fixedSignedConstants'], row['variableKeys'])
            with self.assertRaises(ValueError):
                standard.compile_program(raw + b'\x00', row['fixedSignedConstants'], row['variableKeys'])

    def test_unmatched_context_modes_results_and_operations_remain_rejected(self):
        for code, values in [('000000010311', [-2**63, 2]), ('000000010411', [0x3fffffff * ONE, 2 * ONE]),
                ('00000e11', [-2**63]), ('00000e11', [1]), ('000000010411', [1, ONE])]:
            with self.subTest(code=code), self.assertRaises(ValueError):
                standard.evaluate(bytes.fromhex(code), values, [], {})
        for code in ['000000000611', '000000000711', '00000f11']:
            with self.subTest(code=code), self.assertRaises(ValueError):
                standard.compile_program(bytes.fromhex(code), [0], [])
        with self.assertRaises(ValueError):
            standard.evaluate(bytes.fromhex('01000e11'), [], [-1], {})
        with self.assertRaises(ValueError):
            standard.evaluate(bytes.fromhex('000011'), [0], [], {True: 0})

    def test_complete_multiply_branch_slices_and_vm_call_sites_match_original(self):
        profile = standard.load_profile()
        self.assertEqual((1, 346, 5, 91), (len(profile['nativeMethods']), profile['nativeBytes'],
            len(profile['standardBranchFragments']), profile['standardBranchFragmentBytes']))
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        instructions = {}
        for method in profile['nativeMethods']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual('PE Exception Directory', method['bounds'])
            self.assertEqual(len(raw), int(method['endRva'], 16) - int(method['rva'], 16))
            instructions.update({i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + int(method['rva'], 16))})
        for fragment in profile['standardBranchFragments']:
            raw = bytes.fromhex(fragment['bytesHex'])
            self.assertEqual(fragment['length'], len(raw))
            self.assertEqual(fragment['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertFalse(fragment['completeNativeMethod'])
            instructions.update({i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + int(fragment['rva'], 16))})
        vm = next(m for m in standard.numeric.functions.postfix.load_profile()['nativeMethods'] if m['index'] == 70469)
        instructions.update({i.address - 0x180000000: i for i in decoder.disasm(bytes.fromhex(vm['bytesHex']), 0x180000000 + int(vm['rva'], 16))})
        for address, expected in [(0x1dccb2c4, ('test', 'al, 1')), (0x1dccb2cb, ('sub', 'rax, rdx')),
            (0x1dccb2dd, ('js', '0x19dccb32f')), (0x1dccb2f7, ('jb', '0x19dccb3e1')),
            (0x1dccb3e1, ('ret', '')), (0x1dccb3fa, ('test', 'cl, 1')),
            (0x1dccb3ff, ('cmp', 'rcx, rax')), (0x1dccb41d, ('neg', 'rcx')), (0x1dccb488, ('ret', '')),
            (0x1dccb1a3, ('sar', 'rdi, 1')), (0x1dccb1a9, ('sar', 'r15, 1')),
            (0x1dccb1b9, ('imul', 'rbx, rax')), (0x1dccb1c1, ('shr', 'rbx, 0x20')),
            (0x1dccb256, ('add', 'rax, rax')), (0x1d23e794, ('call', '0x19dccb2c0')),
            (0x1d23e833, ('call', '0x19dccb160')), (0x1d23ed98, ('neg', 'rax'))]:
            self.assertEqual(expected, (instructions[address].mnemonic, instructions[address].op_str))

    def test_read_only_output_boundary(self):
        for output in [CLIENT / 'result.json', ROOT / 'resources/result.json', ROOT / 'config.json']:
            with self.subTest(output=str(output)), self.assertRaises(ValueError):
                standard.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定客户端，跳过原始标准数值程序')
class ResonanceStandardNumericOriginalTest(unittest.TestCase):
    def test_whole_original_collection_and_native_identities_without_actual_context(self):
        with tempfile.TemporaryDirectory(prefix='standard-original-test-', dir=ROOT / 'build') as temporary:
            report = standard.analyze(SimpleNamespace(client_dir=CLIENT, output=Path(temporary) / 'report.json'))
        self.assertEqual((592, 592, 1, 346, 91), (report['originalExpressionCount'], report['originalProgramsCompiled'],
            report['matchedSupplementalNativeMethods'], report['matchedSupplementalNativeBytes'], report['standardBranchFragmentBytes']))
        self.assertTrue(report['standaloneStandardNumericEvaluatorImplemented'])
        self.assertTrue(report['standardArithmeticOnly'])
        for key in ['standardBranchesCompleteNativeMethods', 'originalVariableContextResolved', 'originalExpressionsEvaluated',
                    'abilityExecutionVerified', 'jsonInstalled', 'clientExecutableStarted', 'clientModified',
                    'clientGameplayVerified', 'allResonanceAbilitiesImplemented']:
            self.assertFalse(report[key])


if __name__ == '__main__':
    unittest.main()
