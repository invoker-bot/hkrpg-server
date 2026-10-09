"""多参数注册、标准分支和原生参数顺序的来源回归。"""
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

import capstone

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('resonance_multi',ROOT/'scripts/read-rogue-resonance-multi.py')
multi=importlib.util.module_from_spec(spec)
spec.loader.exec_module(multi)


class ResonanceMultiTest(unittest.TestCase):
    def instructions(self):
        decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
        result={}
        profile=multi.load_profile()
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
        for address,expected in rows:
            self.assertEqual(expected,(ins[address].mnemonic,ins[address].op_str),hex(address))

    def target(self,rva): return hex(0x180000000+rva)

    def test_five_complete_methods_and_clamp_spans_match_installed_client(self):
        self.assertEqual((5,6235),multi.verify_native(CLIENT))
        self.assertEqual([74383,74384,74389,70476,70469],[m['index'] for m in multi.load_profile()['nativeMethods']])

    def test_callback_registration_names_keys_and_methods_match(self):
        rows=multi.load_profile()['registrations']
        self.assertEqual([('min',339799074,74383),('max',696029444,74384),('clamp',-1355705909,74389)],
            [(r['name'],r['hashKey'],r['callbackMethod']['index']) for r in rows])
        registered={r['name']:r for r in multi.functions.load_profile()['registrations']}
        self.assertEqual(rows,[registered[name] for name in ['min','max','clamp']])
        for r in rows: self.assertEqual(r['hashKey'],multi.functions.native_name_key(r['name']))

    def test_minimum_starts_at_first_and_compares_all_later_standard_arguments(self):
        self.check([(0x1ce0af6f,('mov','edx, dword ptr [rcx + 0x18]')),
            (0x1ce0af7b,('mov','rax, qword ptr [rcx + 0x20]')),(0x1ce0af7f,('cmp','edx, 2')),
            (0x1ce0af82,('jl',self.target(0x1ce0b042))),
            (0x1ce0afaf,('mov','r10, qword ptr [rcx + r11*8 + 0x28]')),
            (0x1ce0afbe,('cmp','r10, rax')),(0x1ce0afc1,('jl',self.target(0x1ce0afa0)))])

    def test_maximum_uses_signed_comparison_and_singleton_return(self):
        self.check([(0x1ce0b070,('mov','edx, dword ptr [rcx + 0x18]')),
            (0x1ce0b07c,('mov','rax, qword ptr [rcx + 0x20]')),(0x1ce0b080,('cmp','edx, 2')),
            (0x1ce0b083,('jl',self.target(0x1ce0b157))),
            (0x1ce0b0af,('mov','r10, qword ptr [rcx + r11*8 + 0x28]')),
            (0x1ce0b0c0,('cmp','r10, rax')),(0x1ce0b0c3,('setg','bl')),
            (0x1ce0b0c6,('jl',self.target(0x1ce0b0a3))),
            (0x1ce0b149,('jg',self.target(0x1ce0b0a0)))])

    def test_clamp_reads_first_three_arguments_and_tail_calls_matched_helper(self):
        self.check([(0x1ce0b485,('cmp','eax, 2')),(0x1ce0b488,('jbe',self.target(0x1ce0b4a2))),
            (0x1ce0b48a,('mov','rax, qword ptr [rcx + 0x20]')),
            (0x1ce0b48e,('mov','rdx, qword ptr [rcx + 0x28]')),
            (0x1ce0b492,('mov','r8, qword ptr [rcx + 0x30]')),
            (0x1ce0b49d,('jmp',self.target(0x1dcd1a00)))])
        self.assertTrue(multi.load_profile()['nativeClampIgnoresExtraArguments'])
        self.assertEqual(3,multi.load_profile()['clampFiniteArguments'])

    def test_clamp_keeps_lower_first_strict_comparison_without_sorting(self):
        self.check([(0x1dcd1a00,('mov','rax, rdx')),(0x1dcd1a0c,('cmp','rcx, rax')),
            (0x1dcd1a0f,('jge',self.target(0x1dcd1a52))),
            (0x1dcd1af4,('setg','dl'))])
        p=multi.load_profile()
        self.assertEqual(52,p['standardBranchFragmentBytes'])
        self.assertEqual([(68532,'Clamp')],[(h['index'],h['name']) for h in p['helperIdentities']])
        self.assertTrue(all('endRva' not in f for f in p['standardBranchFragments']))

    def test_vm_call_opcode_reads_unsigned_count_and_forwards_it_to_callback_helper(self):
        self.check([(0x1d23ee3d,('movzx','ebx, byte ptr [rcx + rax + 1]')),
            (0x1d23ee47,('mov','edx, ebx')),(0x1d23ee57,('mov','r8d, ebx')),
            (0x1d23ee5a,('call',self.target(0x1d23fbc0)))])
        self.assertEqual('0x1d23ee27',multi.load_profile()['vmCallTargetRva'])

    def test_native_caller_restores_source_order_after_popping_stack(self):
        self.check([(0x1d23fbdd,('mov','rdx, rbx')),
            (0x1d23fc12,('call',self.target(0x1868fc80))),
            (0x1d23fc20,('dec','rbx')),(0x1d23fc25,('mov','qword ptr [rdi + rcx*8 + 0x20], rax')),
            (0x1d23fc9e,('mov','rdx, rdi')),(0x1d23fca1,('call','qword ptr [rbp + 0x18]')),
            (0x1d23fcb6,('call',self.target(0x1868fc80)))])

    def test_report_opens_only_explicit_standard_math_and_keeps_gameplay_closed(self):
        report=multi.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-resonance-multi-test-evidence.json'))
        for key in ['passed','multiStandaloneArithmeticImplemented','multiPostfixCallerImplemented','originalArgumentOrderPreserved']:
            self.assertIs(True,report[key],key)
        self.assertEqual((5,6235,52,3),tuple(report[k] for k in ['matchedNativeMethods','matchedNativeBytes',
            'standardInstructionFragmentBytes','newRegistrationsMatched']))
        self.assertEqual(['floor','round','ceil','abs','clamp01','min','max','clamp'],report['openedFunctionNames'])
        self.assertEqual([1,255],report['minMaxArguments'])
        for key in ['helperFragmentsCompleteNativeMethods','nativeClampExtraArgumentsSupported','newExtendedInputsSupported',
            'actualParameterValuesResolved','nativeNumericProviderImplemented','dynamicCellProvidersImplemented',
            'liveCasterPropertiesRead','liveBattleContextAttached','originalTaskBranchesExecutedInBattle',
            'abilityExecutionVerified','jsonInstalled','clientExecutableStarted','clientModified',
            'clientGameplayVerified','allResonanceAbilitiesImplemented']:
            self.assertIs(False,report[key],key)

    def test_invalid_output_paths_are_rejected_before_native_reads(self):
        for output in [ROOT/'multi-evidence.json',CLIENT/'multi-evidence.json']:
            with self.subTest(output=output),self.assertRaises(ValueError):
                multi.analyze(SimpleNamespace(client_dir=CLIENT,output=output))


if __name__=='__main__': unittest.main()
