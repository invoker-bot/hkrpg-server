"""固定4.6标准round数值与实际原生证据回归。"""
from decimal import Decimal, ROUND_HALF_EVEN
import hashlib
import importlib.util
from pathlib import Path
import random
import re
import tempfile
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_round', ROOT / 'scripts/read-rogue-resonance-round.py')
rounding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rounding)


class ResonanceRoundTest(unittest.TestCase):
    def test_native_standard_midpoints_and_nearest_quantum(self):
        one, half = 1 << 33, 1 << 32
        pairs = [(0, 0), (2, 0), (-2, 0), (half, 0), (-half, 0),
            (one + half, 2), (-one - half, -2), (2 * one + half, 2), (-2 * one - half, -2),
            (3 * one + half, 4), (-3 * one - half, -4),
            (half - 2, 0), (half + 2, 1), (-half - 2, -1), (-half + 2, 0), (one, 1), (-one, -1)]
        for raw, expected in pairs:
            with self.subTest(raw=raw):
                self.assertEqual(expected, rounding.round_to_int(raw))
                self.assertEqual(expected * one, rounding.round_raw(raw))

    def test_random_standard_inputs_use_independent_decimal_half_even_oracle(self):
        rand = random.Random(46074381)
        for _ in range(4096):
            raw = rand.randrange(-2**55, 2**55) & ~1
            expected = int((Decimal(raw) / Decimal(2**33)).quantize(Decimal(1), rounding=ROUND_HALF_EVEN))
            self.assertEqual(expected * 2**33, rounding.round_raw(raw), str(raw))

    def test_unmatched_extended_malformed_inputs_and_result_overflow_are_closed(self):
        for raw in [1, -1, True, False, None, '0', 0.0, 2**63, -2**63 - 1,
                    -2**63, 2**63 - 2, 2**30 * 2**33, -2**30 * 2**33,
                    (2**30 - 1) * 2**33 + 2**32]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                rounding.round_raw(raw)
        for raw in [(2**30 - 1) * 2**33, -(2**30 - 1) * 2**33]:
            self.assertEqual(raw, rounding.round_raw(raw))
        # The earlier reader remains scoped to its original floor calls.
        with self.assertRaises(ValueError):
            rounding.functions.decode(bytes.fromhex('01000000100111'), [0], [1554761690])

    def test_full_method_rounding_anchors_and_paired_one_initializer(self):
        profile = rounding.load_profile()
        self.assertEqual((3, 936), (len(profile['nativeMethods']), profile['nativeBytes']))
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        instructions = {}
        for method in profile['nativeMethods']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual('PE Exception Directory', method['bounds'])
            self.assertEqual(len(raw), int(method['endRva'], 16) - int(method['rva'], 16))
            instructions.update({i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + int(method['rva'], 16))})
        for address, expected in [(0x1ce0ace1, ('movabs', 'rax, 0xfffffffe00000000')),
                (0x1ce0acee, ('bt', 'r8, 0x20')), (0x1ce0acf3, ('jae', '0x19ce0adb9')),
                (0x1ce0acf9, ('movabs', 'rcx, 0x3fffffffe')),
                (0x1ce0ad06, ('movabs', 'rcx, 0x100000000')),
                (0x1ce0ad13, ('jne', '0x19ce0adbe')), (0x1ce0adcc, ('jmp', '0x19dcc86e0')),
                (0x1dcd1d29, ('movabs', 'rax, 0xfffffffe00000000')),
                (0x1dcd1d36, ('bt', 'rcx, 0x20')), (0x1dcd1d41, ('movabs', 'rdx, 0x3fffffffe')),
                (0x1dcd1d5b, ('jne', '0x19dcd1dfd')), (0x1dcd1e07, ('call', '0x19dcc86e0')),
                (0x1dcd1e1b, ('sar', 'rax, cl'))]:
            self.assertEqual(expected, (instructions[address].mnemonic, instructions[address].op_str))
        one = profile['oneInitializer']
        self.assertEqual(2**33, int.from_bytes(bytes.fromhex(one['sourceBytesHex']), 'little', signed=True))
        paired = one['pairedMinimumInitializer']
        self.assertEqual(int(paired['slotRva'], 16) + 8, int(one['slotRva'], 16))
        self.assertEqual(int(paired['sourceSlotRva'], 16) + 8, int(one['sourceSlotRva'], 16))
        for address in [0x1ce0adbe, 0x1dcd1dfd]:
            load = instructions[address]
            match = re.fullmatch(r'rdx, qword ptr \[rip - (0x[0-9a-f]+)\]', load.op_str)
            self.assertIsNotNone(match)
            self.assertEqual(int(one['slotRva'], 16), address + load.size - int(match[1], 16))

    def test_fixed_native_files_registration_and_bounded_functions_match_client(self):
        self.assertEqual((3, 936), rounding.verify_native(CLIENT))
        registration = rounding.load_profile()['registration']
        self.assertEqual(('round', 1554761690, 74381),
            (registration['name'], registration['hashKey'], registration['callbackMethod']['index']))

    def test_evidence_and_output_boundary_keep_gameplay_unverified(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'build') as temporary:
            report = rounding.analyze(SimpleNamespace(client_dir=CLIENT, output=Path(temporary) / 'round.json'))
            self.assertTrue(report['passed'])
            self.assertEqual((3, 936, 63), (report['matchedNativeMethods'], report['matchedNativeBytes'], report['standardAdditionInstructionBytes']))
            self.assertTrue(report['roundStandaloneCallbackImplemented'])
            for key in ['standardAdditionCompleteNativeMethod', 'roundActionBindingVerified',
                    'originalAbilityExpressionsRead', 'originalVariableContextResolved', 'abilityExecutionVerified',
                    'jsonInstalled', 'clientExecutableStarted', 'clientModified', 'clientGameplayVerified',
                    'allResonanceAbilitiesImplemented']:
                self.assertFalse(report[key], key)
        for output in [CLIENT / 'round.json', ROOT / 'resources/4.6/round.json', ROOT / 'config.json']:
            with self.subTest(output=output), self.assertRaises(ValueError):
                rounding.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


if __name__ == '__main__':
    unittest.main()
