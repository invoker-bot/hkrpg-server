"""固定 4.6 原生函数注册与调用身份的回归。"""
import importlib.util
import hashlib
from pathlib import Path
import random
import re
import tempfile
from types import SimpleNamespace
import unittest
import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_functions', ROOT / 'scripts/read-rogue-resonance-functions.py')
functions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(functions)


class ResonanceFunctionsTest(unittest.TestCase):
    def test_floor_call_is_bound_to_actual_name_and_callback(self):
        tree = functions.decode(bytes.fromhex('01000101010205100111'), [], [1776456860, 200, -300])['tree']
        self.assertEqual({'name': 'floor', 'hashKey': 1776456860, 'methodIndex': 74380, 'methodRva': '0x1ce0ac00'}, tree.get('function'))
        self.assertEqual('Divide', tree['arguments'][0]['op'])
        self.assertEqual([200, -300], [tree['arguments'][0]['left']['variableKey'], tree['arguments'][0]['right']['variableKey']])

    def test_unknown_and_unopened_calls_are_rejected(self):
        for key in [0, -1, 1554761690, -1226284721]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                functions.decode(bytes.fromhex('01000000100111'), [0], [key])

    def test_all_native_function_names_have_exact_signed_hash_keys(self):
        rows = functions.load_profile()['registrations']
        self.assertEqual(['floor', 'round', 'int', 'min', 'max', 'ceil', 'mod', 'clamp01',
            'abs', 'clamp', 'leftshift', 'rightshift', 'and', 'or', 'xor'], [r['name'] for r in rows])
        self.assertEqual(15, len({r['hashKey'] for r in rows}))
        for row in rows:
            self.assertEqual(row['hashKey'], functions.native_name_key(row['name']))
            self.assertEqual(10806, row['callbackMethod']['typeIndex'])
        for name in ['', '\0', 'floor\0', '中文', '🙂', 'a' * 65, None, 123]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                functions.native_name_key(name)

    def test_all_registrations_match_complete_method_call_and_literal_method_slots(self):
        profile = functions.load_profile()
        self.assertEqual((6, 3164), (len(profile['nativeMethods']), profile['nativeBytes']))
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        instructions = {}
        for method in profile['nativeMethods']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual('PE Exception Directory', method['bounds'])
            self.assertEqual(len(raw), int(method['endRva'], 16) - int(method['rva'], 16))
            instructions.update({i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + int(method['rva'], 16))})
        for row in profile['registrations']:
            call = instructions[int(row['callRva'], 16)]
            self.assertEqual(('call', '0x19d23e320'), (call.mnemonic, call.op_str))
            for field, slot, register in [('literalLoadRva', 'literalSlotRva', 'rdx'),
                                          ('callbackMethodLoadRva', 'callbackMethodSlotRva', 'rcx')]:
                load = instructions[int(row[field], 16)]
                self.assertEqual('mov', load.mnemonic)
                match = re.fullmatch(register + r', qword ptr \[rip ([+-]) (0x[0-9a-f]+)\]', load.op_str)
                self.assertIsNotNone(match)
                address = load.address + load.size - 0x180000000 + (1 if match[1] == '+' else -1) * int(match[2], 16)
                self.assertEqual(int(row[slot], 16), address)
            callback_load = instructions[int(row['callbackMethodLoadRva'], 16)]
            self.assertEqual('rdx, qword ptr [rcx + 8]', instructions[int(row['callbackMethodLoadRva'], 16) + callback_load.size].op_str)
        for address, expected in [(0x1ce0ac20, ('movabs', 'rax, 0xfffffffe00000000')),
            (0x1ce0ac35, ('and', 'rcx, 0xfffffffffc000000')),
            (0x1ce0ac3c, ('movabs', 'rax, 0xa2de246003ffffff')),
            (0x1ce0ac52, ('movabs', 'rdx, 0x5d21db9ffc000001')),
            (0x1ce0ac84, ('sar', 'rcx, 0x1a')), (0x1ce0ac92, ('cmp', 'rdx, 0x3fffffff')),
            (0x1ce0ac9f, ('shl', 'rax, 7')),
            (0x1dcd3a73, ('movabs', 'rax, 0x5d21db9ffc000001'))]:
            self.assertEqual(expected, (instructions[address].mnemonic, instructions[address].op_str))
        constants = profile['floorConstants']
        for field, address, register, length in [('maximumInitializer', 0x1dcd3a7d, 'rax', 7),
            ('minimumInitializer', 0x1dcd3a8b, 'xmm0', 7)]:
            ins = instructions[address]
            match = re.fullmatch(r'(?:qword|xmmword) ptr \[rip ([+-]) (0x[0-9a-f]+)\], ' + register, ins.op_str)
            self.assertIsNotNone(match)
            target = address + length + (1 if match[1] == '+' else -1) * int(match[2], 16)
            self.assertEqual(constants[field]['slotRva'], hex(target))

    def test_floor_integer_oracle_covers_both_modes_saturation_and_normalization(self):
        rand = random.Random(46074380)
        raw_values = [-2**63, 2**63 - 1, -1, 0, 1, 2, -2,
            0xa2de246004000001 - 2**64, 0x5d21db9ffc000001,
            (-0x40000000 << 26) | 1, (-0x3fffffff << 26) | 1,
            (0x3fffffff << 26) | 1, (0x40000000 << 26) | 1]
        raw_values += [rand.randrange(-2**63, 2**63) for _ in range(4096)]
        for raw in raw_values:
            with self.subTest(raw=raw):
                if raw & 1:
                    integral = max(-99_999_999_999, min(99_999_999_999, (raw & ~1) // 2**26))
                    expected = integral * 2**33 if abs(integral) <= 0x3fffffff else (integral * 2**26) | 1
                else:
                    expected = (raw // 2**33) * 2**33
                actual = functions.floor_raw(raw)
                self.assertEqual(expected, actual)
                self.assertEqual(actual, functions.floor_raw(actual))
        for raw in [True, 1.0, '1', None, 2**63, -2**63 - 1]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                functions.floor_raw(raw)

    def test_old_topology_remains_independent_and_all_argument_shapes_stop(self):
        code = bytes.fromhex('01000000100111')
        old = functions.postfix.decode(code, [0], [0])
        self.assertNotIn('function', old['tree'])
        self.assertFalse(old['expressionEvaluated'])
        for code in ['01000000100011', '01000000100211', '00000000100111']:
            with self.subTest(code=code), self.assertRaises(ValueError):
                functions.decode(bytes.fromhex(code), [0], [1776456860])
        for prefix in range(9):
            raw = bytes.fromhex('01000101010205100111')
            with self.subTest(prefix=prefix), self.assertRaises(ValueError):
                functions.decode(raw[:prefix], [], [1776456860, 200, -300])

    def test_output_is_restricted_before_reading_client_files(self):
        for output in [CLIENT / 'result.json', ROOT / 'resources/result.json', ROOT / 'config.json']:
            with self.subTest(output=str(output)), self.assertRaises(ValueError):
                functions.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定客户端，跳过原始函数读取')
class ResonanceFunctionsOriginalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory(prefix='functions-original-test-', dir=ROOT / 'build') as temporary:
            cls.report = functions.analyze(SimpleNamespace(client_dir=CLIENT, output=Path(temporary) / 'report.json'))

    def test_all_592_expressions_and_two_real_floor_calls_keep_unresolved_context(self):
        report = self.report
        self.assertEqual((10, 93228, 592, 2, 6, 3164), (report['recordCount'], report['abilityRecordBytes'],
            report['expressionCount'], report['callCount'], report['matchedNativeMethods'], report['matchedNativeBytes']))
        self.assertEqual(15, len(report['registrations']))
        self.assertTrue(report['functionNamesResolved'])
        self.assertTrue(report['floorStandaloneArithmeticImplemented'])
        for call in report['calls']:
            self.assertEqual({'name': 'floor', 'hashKey': 1776456860, 'methodIndex': 74380, 'methodRva': '0x1ce0ac00'}, call['function'])
            self.assertEqual(1, call['argumentCount'])
            self.assertTrue(call['path'].startswith('Config/ConfigAbility/'))
        for key in ['floorNumericContextAttached', 'variableContextResolved', 'expressionEvaluationVerified',
                    'callImplementationsExecuted', 'jsonInstalled', 'clientExecutableStarted', 'clientModified',
                    'clientGameplayVerified', 'allResonanceAbilitiesImplemented']:
            self.assertFalse(report[key])
        self.assertTrue(all(row['plan']['fullyConsumed'] and not row['plan']['expressionEvaluated'] for row in report['expressions']))


if __name__ == '__main__':
    unittest.main()
