"""整数转换、余数和位运算原生分支的来源回归。"""
from copy import deepcopy
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
import capstone
import pefile

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('resonance_integer',ROOT/'scripts/read-rogue-resonance-integer.py')
integer=importlib.util.module_from_spec(spec)
spec.loader.exec_module(integer)

class ResonanceIntegerTest(unittest.TestCase):
    def instructions(self):
        decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
        decoder.detail=True
        result={}
        profile=integer.load_profile()
        for method in profile['nativeMethods']+profile['standardBranchFragments']:
            raw=bytes.fromhex(method['bytesHex'])
            self.assertEqual(method['sha256'],hashlib.sha256(raw).hexdigest())
            if 'endRva' in method:
                self.assertEqual('PE Exception Directory',method['bounds'])
                self.assertEqual(len(raw),int(method['endRva'],16)-int(method['rva'],16))
            else: self.assertFalse(method['completeNativeMethod'])
            result.update({i.address-0x180000000:i for i in decoder.disasm(raw,0x180000000+int(method['rva'],16))})
        return result
    def check(self,rows):
        ins=self.instructions()
        for address,expected in rows: self.assertEqual(expected,(ins[address].mnemonic,ins[address].op_str),hex(address))
    def target(self,rva): return hex(0x180000000+rva)
    def rip(self,i):
        op=next(o for o in i.operands if o.type==capstone.x86.X86_OP_MEM and o.mem.base==capstone.x86.X86_REG_RIP)
        return i.address+i.size+op.mem.disp-0x180000000

    def test_nine_complete_methods_and_partial_helpers_match_installed_client(self):
        self.assertEqual((9,4200),integer.verify_native(CLIENT))
        self.assertEqual([74382,74386,74390,74391,74392,74393,74394,68530,68417],
            [m['index'] for m in integer.load_profile()['nativeMethods']])
    def test_seven_callback_names_keys_methods_and_all_fifteen_names_match(self):
        p=integer.load_profile()
        self.assertEqual([('int',-1226284721,74382),('mod',1502598494,74386),('leftshift',-1825696445,74390),
            ('rightshift',965494012,74391),('and',-1226285001,74392),('or',381169821,74393),('xor',1502599105,74394)],
            [(r['name'],r['hashKey'],r['callbackMethod']['index']) for r in p['registrations']])
        for r in p['registrations']: self.assertEqual(r['hashKey'],integer.functions.native_name_key(r['name']))
        self.assertEqual([r['name'] for r in integer.functions.load_profile()['registrations']],p['openedFunctionNames'])
    def test_modulo_preserves_signed_idiv_and_unsigned_small_value_paths(self):
        self.check([(0x1ce0b28e,('test','cl, 1')),(0x1ce0b299,('shr','rcx, 0x20')),
            (0x1ce0b29f,('cqo','')),(0x1ce0b2a1,('idiv','r8')),(0x1ce0b2a4,('mov','rax, rdx')),
            (0x1ce0b2e6,('xor','edx, edx')),(0x1ce0b2e8,('div','r8d')),(0x1ce0b2eb,('mov','eax, edx'))])
    def test_shift_value_rounding_and_count_floor_precede_32_bit_operation(self):
        self.check([(0x1ce0b4f0,('bt','rcx, 0x20')),(0x1ce0b512,('cmp','rcx, rdx')),
            (0x1ce0b5c1,('call',self.target(0x1dcc86e0))),
            (0x1ce0b5da,('sar','rax, cl')),(0x1ce0b5dd,('mov','rsi, qword ptr [rbx + 0x28]')),
            (0x1ce0b5ea,('sar','rsi, cl')),(0x1ce0b5ef,('shl','eax, cl')),
            (0x1ce0b76a,('sar','rsi, cl')),(0x1ce0b76f,('sar','eax, cl'))])
    def test_bit_folds_round_first_and_later_arguments_before_all_three_operations(self):
        self.check([(0x1ce0b805,('test','rax, r9')),(0x1ce0b81b,('cmp','rax, r9')),
            (0x1ce0b8bd,('call',self.target(0x1dcc86e0))),
            (0x1ce0b8e0,('sar','rdi, cl')),(0x1ce0b920,('sar','rax, cl')),
            (0x1ce0b923,('and','edi, eax')),(0x1ce0b9f8,('call',self.target(0x1dcc86e0))),
            (0x1ce0bbe3,('or','edi, eax')),(0x1ce0bea3,('xor','edi, eax'))])
    def test_int_approximately_branch_precedes_float32_conversion_and_truncation(self):
        self.check([(0x1ce0aeee,('call',self.target(0x1dcd1760))),
            (0x1ce0aef5,('jne',self.target(0x1ce0af3f))),
            (0x1ce0af04,('cvtsi2ss','xmm0, rsi')),(0x1ce0af10,('mulss','xmm0, dword ptr [rcx + rax*4]')),
            (0x1ce0af15,('cvttss2si','eax, xmm0')),(0x1ce0af35,('cmp','rax, 0x40000000'))])
    def test_approximation_is_strict_less_than_inline_initialized_epsilon(self):
        self.check([(0x1dcd176f,('call',self.target(0x1dccb2c0))),
            (0x1dcd1780,('neg','rcx')),(0x1dcd1783,('cmovs','rcx, rax')),
            (0x1dcd188e,('cmp','rcx, rdx')),(0x1dcd189a,('cmovge','eax, esi')),
            (0x1dcd189d,('shr','eax, 0x1f')),
            (0x1dcd3bc0,('mov','qword ptr [rip - 0x1498feab], 0x218c'))])
        ins=self.instructions()
        self.assertEqual(0x9343d20,self.rip(ins[0x1dcd181a]))
        self.assertEqual(0x9343d20,self.rip(ins[0x1dcd3bc0]))
        self.assertEqual(8588,integer.load_profile()['approximation']['epsilonRaw'])
    def test_float_factor_table_is_actual_file_data_at_native_lea_target(self):
        p=integer.load_profile()
        pe=pefile.PE(str(CLIENT/'GameAssembly.dll'),fast_load=True)
        self.assertEqual((2**-26,2**-33),integer.read_factors(pe,p['floatFactors']))
        self.assertEqual(int(p['floatFactors']['rva'],16),self.rip(self.instructions()[0x1ce0af09]))
        for kind in ['bytes','index','exponent']:
            fact=deepcopy(p['floatFactors'])
            if kind=='bytes': fact['bytesHex']='00'*8
            elif kind=='index': fact['standardFactorIndex']=0
            else: fact['standardFactorExponent']=-26
            with self.subTest(kind=kind),self.assertRaises(ValueError): integer.read_factors(pe,fact)
    def test_standard_round_overflow_intermediate_decodes_exact_positive_integer(self):
        self.check([(0x1dcc86fe,('js',self.target(0x1dcc8750))),
            (0x1dcc8753,('sar','rax, 7')),(0x1dcc8761,('sar','rcx, 7')),
            (0x1dcc877d,('add','rcx, r8')),(0x1dcc87c2,('or','rax, 1')),
            (0x1dcc87e3,('sar','rcx, 0x1a')),(0x1dcc87f1,('cmp','rdx, 0x3fffffff')),
            (0x1dcc87f8,('ja',self.target(0x1dcc8802)))])
        encoded=((0x3fffffff<<33)>>7)+((1<<33)>>7)|1
        self.assertEqual(0x40000000,encoded>>26)
    def test_helper_fragments_and_finite_counts_do_not_claim_whole_helper_or_extra_arguments(self):
        p=integer.load_profile()
        self.assertEqual(303,p['standardBranchFragmentBytes'])
        self.assertEqual(5,len(p['standardBranchFragments']))
        self.assertTrue(all('endRva' not in f for f in p['standardBranchFragments']))
        self.assertEqual([(68467,'op_Addition'),(68470,'op_Subtraction')],[(h['index'],h['name']) for h in p['helperIdentities']])
        self.assertEqual(['int','mod','leftshift','rightshift'],p['nativeIgnoredExtraArguments'])
        self.assertEqual((1,2,[1,255]),(p['intFiniteArguments'],p['binaryFiniteArguments'],p['bitArguments']))
    def test_report_opens_finite_math_for_fifteen_keys_and_keeps_live_gameplay_closed(self):
        r=integer.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-resonance-integer-test-evidence.json'))
        for key in ['passed','integerStandaloneArithmeticImplemented','integerPostfixCallerImplemented','intEpsilonStrictLess',
            'intFloat32FallbackImplemented','intApproximateNegativeEndpointPreserved','positiveStandardRoundIntermediateDecoded','shift32BitMaskImplemented']:
            self.assertIs(True,r[key],key)
        self.assertEqual((9,4200,303,24,7,15,8588),tuple(r[k] for k in ['matchedNativeMethods','matchedNativeBytes',
            'standardInstructionFragmentBytes','initializerAndFactorBytes','newRegistrationsMatched','registeredStandardCallbacksImplemented','intEpsilonRaw']))
        for key in ['helperFragmentsCompleteNativeMethods','nativeIgnoredExtraArgumentsSupported','newExtendedInputsSupported','newExtendedResultsSupported',
            'actualParameterValuesResolved','nativeNumericProviderImplemented','dynamicCellProvidersImplemented','liveCasterPropertiesRead',
            'liveBattleContextAttached','originalTaskBranchesExecutedInBattle','abilityExecutionVerified','jsonInstalled','clientExecutableStarted',
            'clientModified','clientGameplayVerified','allResonanceAbilitiesImplemented']: self.assertIs(False,r[key],key)
    def test_invalid_output_paths_are_rejected_before_native_reads(self):
        for output in [ROOT/'integer-evidence.json',CLIENT/'integer-evidence.json']:
            with self.subTest(output=output),self.assertRaises(ValueError): integer.analyze(SimpleNamespace(client_dir=CLIENT,output=output))

if __name__=='__main__': unittest.main()
