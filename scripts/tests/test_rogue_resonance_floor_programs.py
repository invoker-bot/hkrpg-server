"""原始 floor 程序的有限标准数值回归；数值上下文全部为显式夹具。"""
from decimal import Decimal, ROUND_HALF_UP, localcontext
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
spec = importlib.util.spec_from_file_location('resonance_floor_programs', ROOT / 'scripts/read-rogue-resonance-floor-programs.py')
numeric = importlib.util.module_from_spec(spec)
spec.loader.exec_module(numeric)
ONE = 1 << 33


class ResonanceFloorNumericTest(unittest.TestCase):
    def test_original_programs_addition_order_negative_floor_and_missing_input(self):
        for index, row in enumerate(numeric.load_profile()['expressions']):
            code = bytes.fromhex(row['rawPostfixBytes'])
            constants, keys = row['fixedSignedConstants'], row['variableKeys']
            values = {-978103959: 200 * ONE, -1803181471: 80 * ONE, 1555818884: 3 * ONE}
            self.assertEqual((3 if index == 0 else 66) * ONE, numeric.evaluate(code, constants, keys, values))
            values[-978103959] = -200 * ONE
            self.assertEqual((-2 if index == 0 else -67) * ONE, numeric.evaluate(code, constants, keys, values))
            with self.assertRaisesRegex(ValueError, '未解析'):
                numeric.evaluate(code, constants, keys, {})
            for length in range(len(code)):
                with self.subTest(index=index, length=length), self.assertRaises(ValueError):
                    numeric.compile_program(code[:length], constants, keys)
            with self.assertRaises(ValueError):
                numeric.compile_program(code + b'\x00', constants, keys)

    def test_division_uses_native_magnitude_rounding_including_ties_and_signs(self):
        for a, b, expected in [(2 * ONE, 3 * ONE, 5726623062), (-2 * ONE, 3 * ONE, -5726623062),
                (3 * ONE, 5 * ONE, 5153960756), (-3 * ONE, 5 * ONE, -5153960756),
                (2, 2 * ONE, 2), (-2, 2 * ONE, -2), (2, -2 * ONE, -2), (-2, -2 * ONE, 2)]:
            self.assertEqual(expected, numeric.evaluate(bytes.fromhex('000000010511'), [a, b], [], {}))
        rand = random.Random(46068481)
        with localcontext() as context:
            context.prec = 128
            for _ in range(2048):
                a, b = rand.randrange(-2**42, 2**42) & ~1, rand.randrange(1, 2**40) * 2
                if rand.randrange(2):
                    b = -b
                legacy = (Decimal(a) / Decimal(b) * Decimal(2**32)).to_integral_value(rounding=ROUND_HALF_UP)
                expected = int(legacy) * 2
                self.assertEqual(expected, numeric.evaluate(bytes.fromhex('000000010511'), [a, b], [], {}))

    def test_native_division_and_only_standard_addition_slices_have_exact_anchors(self):
        profile = numeric.load_profile()
        self.assertEqual((1, 654, 63), (len(profile['nativeMethods']), profile['nativeBytes'], profile['standardAdditionFragmentBytes']))
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        instructions = {}
        for method in profile['nativeMethods']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual(len(raw), int(method['endRva'], 16) - int(method['rva'], 16))
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual('PE Exception Directory', method['bounds'])
            instructions.update({i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + int(method['rva'], 16))})
        for fragment in profile['standardAdditionFragments']:
            raw = bytes.fromhex(fragment['bytesHex'])
            self.assertEqual(fragment['length'], len(raw))
            self.assertEqual(fragment['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertFalse(fragment['completeNativeMethod'])
            instructions.update({i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + int(fragment['rva'], 16))})
        for address, expected in [(0x1dcc86e4, ('test', 'al, 1')), (0x1dcc86e8, ('lea', 'rax, [rdx + rcx]')),
            (0x1dcc86fe, ('js', '0x19dcc8750')), (0x1dcc8703, ('sar', 'rcx, 0x21')),
            (0x1dcc8711, ('cmp', 'rdx, 0x40000000')), (0x1dcc8718, ('jb', '0x19dcc8802')),
            (0x1dcc8802, ('ret', '')), (0x1dcc9507, ('test', 'al, 1')),
            (0x1dcc951c, ('sar', 'r11, 1')), (0x1dcc9522, ('sar', 'r14, 1')),
            (0x1dcc953f, ('mov', 'ebx, 0x21')), (0x1dcc96ad, ('inc', 'rsi')),
            (0x1dcc96b0, ('shr', 'rsi, 1')), (0x1dcc96bc, ('cmovns', 'rax, rsi')),
            (0x1dcc96d9, ('add', 'rax, rax')), (0x1dcc96ed, ('cmp', 'rdx, 0x40000000'))]:
            self.assertEqual(expected, (instructions[address].mnemonic, instructions[address].op_str))

    def test_unmatched_numeric_operations_inputs_and_extended_results_stop(self):
        code = bytes.fromhex('000000010511')
        for constants in [[ONE, 0], [1, ONE], [ONE, 1], [-2**63, ONE], [2**63 - 2, 2]]:
            with self.subTest(constants=constants), self.assertRaises(ValueError):
                numeric.evaluate(code, constants, [], {})
        for context in [None, {1: None}, {True: 0}, {2**31: 0}, {1: 1}, {i: 0 for i in range(256)}]:
            with self.subTest(context=str(context)[:40]), self.assertRaises(ValueError):
                numeric.evaluate(bytes.fromhex('010011'), [], [1], context)
        for code in ['000000000311', '000000000411', '00000e11']:
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, '数值操作码'):
                numeric.compile_program(bytes.fromhex(code), [0], [])
        with self.assertRaises(ValueError):
            numeric.evaluate(bytes.fromhex('000000010211'), [0x3fffffff * ONE, ONE], [], {})
        with self.assertRaises(ValueError):
            numeric.evaluate(bytes.fromhex('010011'), [], [1776456860], {1776456860: 0})

    def test_output_boundary_is_checked_before_reading_native_data(self):
        for output in [CLIENT / 'result.json', ROOT / 'resources/result.json', ROOT / 'config.json']:
            with self.subTest(output=str(output)), self.assertRaises(ValueError):
                numeric.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定客户端，跳过原始 floor 程序')
class ResonanceFloorNumericOriginalTest(unittest.TestCase):
    def test_two_original_programs_and_native_identities_preserve_actual_context_gap(self):
        with tempfile.TemporaryDirectory(prefix='floor-original-test-', dir=ROOT / 'build') as temporary:
            report = numeric.analyze(SimpleNamespace(client_dir=CLIENT, output=Path(temporary) / 'report.json'))
        self.assertEqual((592, 2, 1, 654, 63), (report['sourceExpressionCount'], report['originalCallProgramCount'],
            report['matchedSupplementalNativeMethods'], report['matchedSupplementalNativeBytes'], report['standardAdditionFragmentBytes']))
        self.assertTrue(report['standaloneNumericEvaluatorImplemented'])
        self.assertTrue(report['standardArithmeticOnly'])
        for key in ['standardAdditionCompleteNativeMethod', 'variableContextResolved', 'originalExpressionsEvaluated',
                    'abilityExecutionVerified', 'jsonInstalled', 'clientExecutableStarted', 'clientModified',
                    'clientGameplayVerified', 'allResonanceAbilitiesImplemented']:
            self.assertFalse(report[key])


if __name__ == '__main__':
    unittest.main()
