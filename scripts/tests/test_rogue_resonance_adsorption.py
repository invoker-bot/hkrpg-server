"""标准吸附来源、Pow调用、分数提取和修正顺序回归。"""
import hashlib
import importlib.util
from pathlib import Path
import struct
from types import SimpleNamespace
import unittest
import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_adsorption',ROOT/'scripts/read-rogue-resonance-adsorption.py')
adsorption = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adsorption)


class ResonanceAdsorptionTest(unittest.TestCase):
    def instructions(self):
        c = capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
        result = {}
        for m in adsorption.load_profile()['nativeMethods']+adsorption.load_profile()['nativeFragments']:
            raw = bytes.fromhex(m['bytesHex'])
            self.assertEqual(m['sha256'],hashlib.sha256(raw).hexdigest())
            result.update({i.address-0x180000000:i for i in c.disasm(raw,0x180000000+int(m['rva'],16))})
        return result

    def check(self,rows):
        ins = self.instructions()
        for rva,expected in rows: self.assertEqual(expected,(ins[rva].mnemonic,ins[rva].op_str),hex(rva))

    def target(self,rva): return hex(0x180000000+rva)

    def test_complete_adsorption_and_multiply_divide_methods_match_installed_client(self):
        self.assertEqual((3,1706),adsorption.verify_native(CLIENT))
        self.assertEqual([68474,68481,74692],[m['index'] for m in adsorption.load_profile()['nativeMethods']])

    def test_math_pow_jump_and_double_base_ten_have_separate_fragment_boundaries(self):
        p = adsorption.load_profile()
        self.assertEqual(5,p['fragmentBytes'])
        self.assertFalse(p['nativeFragments'][0]['completeNativeMethod'])
        self.assertEqual((10.0,),struct.unpack('<d',bytes.fromhex(p['powerBaseConstant']['bytesHex'])))
        self.check([(0x1c24d130,('jmp',self.target(0x3ce0ed0))),
            (0x1d1103f5,('call',self.target(0x3ce0ed0)))])

    def test_digit_is_exponent_then_truncated_to_int32_and_encoded_as_fixpoint(self):
        self.check([(0x1d1103e8,('cvtsi2sd','xmm1, dword ptr [rdx + 0x18]')),
            (0x1d1103fa,('cvttsd2si','eax, xmm0')),(0x1d1103fe,('movsxd','rdi, eax')),
            (0x1d110412,('shl','rdx, 0x1a')),(0x1d110416,('or','rdx, 1')),
            (0x1d11041a,('shl','rdi, 0x21')),(0x1d11041e,('cmp','rax, 0x40000000')),
            (0x1d110424,('cmovae','rdi, rdx'))])

    def test_threshold_is_scaled_and_positive_gate_precedes_input_read(self):
        self.check([(0x1d11040b,('mov','rcx, qword ptr [rsi + 0x10]')),
            (0x1d11042b,('call',self.target(0x1dccb160))),
            (0x1d110445,('cmp','rbx, rax')),(0x1d1104d7,('jle',self.target(0x1d110688))),
            (0x1d1104dd,('mov','rax, qword ptr [r14]'))])

    def test_two_fraction_extractions_bracket_scale_multiplication(self):
        self.check([(0x1d1104ec,('movabs','rsi, 0x1ffffff00')),(0x1d1104f6,('or','rsi, 0xfe')),
            (0x1d110500,('and','rdx, rsi')),(0x1d110505,('cmovne','rdx, rcx')),
            (0x1d11050c,('call',self.target(0x1dccb160))),
            (0x1d11051d,('and','rsi, rax')),(0x1d110522,('cmovne','rsi, rcx'))])

    def test_half_comparison_uses_one_divided_by_two_and_upper_branch_includes_midpoint(self):
        self.check([(0x1d11052d,('movabs','rdx, 0x400000000')),
            (0x1d110537,('call',self.target(0x1dcc94f0))),
            (0x1d110540,('cmp','rsi, rax')),(0x1d110543,('jl',self.target(0x1d110584))),
            (0x1d11054f,('call',self.target(0x1dccb2c0)))])

    def test_both_distance_gates_are_strict_greater_so_equality_snaps(self):
        self.check([(0x1d11058b,('cmp','rsi, rbx')),(0x1d110597,('jg',self.target(0x1d110688))),
            (0x1d110563,('cmp','rax, rbx')),(0x1d11066a,('jg',self.target(0x1d110688)))])

    def test_division_precedes_subtraction_or_addition_and_final_word_assignment(self):
        self.check([(0x1d110619,('call',self.target(0x1dcc94f0))),
            (0x1d110624,('call',self.target(0x1dccb2c0))),
            (0x1d110675,('call',self.target(0x1dcc94f0))),
            (0x1d110680,('call',self.target(0x1dcc86e0))),
            (0x1d110685,('mov','qword ptr [r14], rax'))])

    def test_static_output_opens_only_finite_standard_adsorption(self):
        r = adsorption.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/adsorption-source-test.json'))
        self.assertEqual(9,r['maximumPositiveFractionalDigit'])
        self.assertEqual((32,8,5),(r['initializerBytes'],r['powerBaseConstantBytes'],r['nativeFragmentBytes']))
        for k in ['nonNullStandardAdsorptionImplemented','nullAdsorptionStillNoOp','getterDoesNotAdsorb']:
            self.assertTrue(r[k],k)
        for k in ['extendedInputsOrIntermediateResultsSupported','positiveDigitsAboveNineSupported',
            'propertyCellImplemented','aliasEvaluatorBindingImplemented','actualParameterValuesResolved',
            'liveCasterPropertiesRead','liveBattleContextAttached','originalTaskBranchesExecutedInBattle',
            'abilityExecutionVerified','clientGameplayVerified','allResonanceAbilitiesImplemented',
            'jsonInstalled','clientExecutableStarted','clientModified']:
            self.assertFalse(r[k],k)

    def test_client_output_is_rejected_before_source_inspection(self):
        with self.assertRaises(ValueError):
            adsorption.analyze(SimpleNamespace(client_dir=CLIENT,output=CLIENT/'adsorption-evidence.json'))


if __name__ == '__main__': unittest.main()
