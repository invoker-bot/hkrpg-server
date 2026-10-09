"""十八操作码与九种补充标准分支的来源回归。"""
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
import capstone

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('resonance_condition',ROOT/'scripts/read-rogue-resonance-condition.py')
condition=importlib.util.module_from_spec(spec)
spec.loader.exec_module(condition)

class ResonanceConditionTest(unittest.TestCase):
    def instructions(self):
        decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
        decoder.detail=True
        result={}
        p=condition.load_profile()
        for m in p['nativeMethods']+p['nativeFragments']:
            raw=bytes.fromhex(m['bytesHex'])
            self.assertEqual(m['sha256'],hashlib.sha256(raw).hexdigest())
            if 'endRva' in m:
                self.assertEqual('PE Exception Directory',m['bounds'])
                self.assertEqual(len(raw),int(m['endRva'],16)-int(m['rva'],16))
            else: self.assertFalse(m['completeNativeMethod'])
            result.update({i.address-0x180000000:i for i in decoder.disasm(raw,0x180000000+int(m['rva'],16))})
        return result
    def check(self,rows):
        ins=self.instructions()
        for addr,expected in rows: self.assertEqual(expected,(ins[addr].mnemonic,ins[addr].op_str),hex(addr))
    def target(self,rva): return hex(0x180000000+rva)
    def rip(self,i):
        o=next(o for o in i.operands if o.type==capstone.x86.X86_OP_MEM and o.mem.base==capstone.x86.X86_REG_RIP)
        return i.address+i.size+o.mem.disp-0x180000000

    def test_complete_vm_approximately_and_initializer_match_installed_client(self):
        self.assertEqual((3,5943),condition.verify_native(CLIENT))
        self.assertEqual([70469,68530,68417],[m['index'] for m in condition.load_profile()['nativeMethods']])
    def test_all_eighteen_byte_constants_and_relative_switch_targets_match(self):
        p=condition.load_profile()
        self.assertEqual(list(range(18)),[f['value'] for f in p['opCodeConstants']])
        self.assertEqual(['Constant','GetGlobal','Add','Subtract','Multiply','Divide','Modulo','Equal','NotEqual',
            'Less','Greater','LessOrEqual','GreaterOrEqual','TernaryCondition','Negative','Not','Call','Return'],
            [f['name'] for f in p['opCodeConstants']])
        self.assertEqual([6,7,8,9,10,11,12,13,15],p['newOpCodes'])
        self.assertEqual(list(range(18)),p['acceptedNumericOpCodes'])
        self.assertEqual(18,len(p['vmSwitchTargets']))
    def test_fragment_counts_remain_separate_from_complete_native_methods(self):
        p=condition.load_profile()
        self.assertEqual(620,p['fragmentBytes'])
        self.assertEqual([(68470,298),(68531,322)],[(f['methodIndex'],f['length']) for f in p['nativeFragments']])
        self.assertTrue(all('endRva' not in f and not f['completeNativeMethod'] for f in p['nativeFragments']))
    def test_modulo_vm_pops_right_then_left_and_uses_signed_raw_remainder(self):
        self.check([(0x1d23e92c,('call',self.target(0x1868fc80))),
            (0x1d23e93d,('mov','rbx, rax')),(0x1d23e942,('call',self.target(0x1868fc80))),
            (0x1d23f239,('cqo','')),(0x1d23f23b,('idiv','rbx')),(0x1d23f23e,('mov','rbx, rdx'))])
    def test_equal_and_not_equal_use_approximately_and_encoded_one(self):
        self.check([(0x1d23e9c0,('call',self.target(0x1dcd1760))),
            (0x1d23ea52,('call',self.target(0x1dcd1760))),
            (0x1d23ef55,('xor','bl, 1')),(0x1d23e4de,('movzx','ecx, bl')),
            (0x1d23e4e1,('shl','rcx, 0x21'))])
    def test_strict_comparisons_exclude_nearly_equal_before_signed_order(self):
        self.check([(0x1d23eaea,('call',self.target(0x1dcd1760))),
            (0x1d23eaf1,('jne',self.target(0x1d23eb30))),
            (0x1d23f41d,('cmp','rbp, rbx')),(0x1d23f420,('jge',self.target(0x1d23eb30))),
            (0x1d23ebc0,('call',self.target(0x1dcd1760))),
            (0x1d23ebc7,('jne',self.target(0x1d23f449)))])
    def test_inclusive_comparisons_accept_exact_order_or_nearly_equal(self):
        self.check([(0x1d23ec5a,('cmp','rax, rbx')),(0x1d23ec5d,('jge',self.target(0x1d23f0d8))),
            (0x1d23f0de,('call',self.target(0x1dcd1760))),
            (0x1d23eca4,('cmp','rax, rbx')),(0x1d23f179,('call',self.target(0x1dcd1760)))])
    def test_ternary_pops_both_already_computed_branches_before_testing_condition(self):
        self.check([(0x1d23ecc3,('call',self.target(0x1868fc80))),
            (0x1d23ecd4,('mov','rbp, rax')),(0x1d23ecd9,('call',self.target(0x1868fc80))),
            (0x1d23ecea,('mov','rbx, rax')),(0x1d23ecef,('call',self.target(0x1868fc80))),
            (0x1d23ecf7,('call',self.target(0x1dcd18b0))),
            (0x1d23ed0a,('cmovne','rbx, rbp'))])
        self.assertEqual(['condition','whenTrue','whenFalse'],condition.load_profile()['ternaryInputOrder'])
    def test_not_uses_almost_zero_boolean_without_exact_zero_replacement(self):
        self.check([(0x1d23edb9,('call',self.target(0x1dcd18b0))),
            (0x1d23edca,('mov','ebx, eax')),(0x1d23ee1c,('jb',self.target(0x1d23e4de))),
            (0x1dcd19de,('cmp','rdx, rcx')),(0x1dcd19ea,('cmovge','eax, r8d')),
            (0x1dcd19ee,('shr','eax, 0x1f'))])
    def test_almost_zero_has_safe_minimum_fallback_and_same_initialized_epsilon(self):
        self.check([(0x1dcd18b0,('movabs','rax, 0x8000000000000000')),
            (0x1dcd18bf,('cmp','rcx, rax')),(0x1dcd18c2,('je',self.target(0x1dcd18df))),
            (0x1dcd18c7,('neg','rdx')),(0x1dcd18ca,('cmovs','rdx, rcx')),
            (0x1dcd3a73,('movabs','rax, 0x5d21db9ffc000001')),
            (0x1dcd3bc0,('mov','qword ptr [rip - 0x1498feab], 0x218c'))])
        ins=self.instructions()
        self.assertEqual(0x9343ca0,self.rip(ins[0x1dcd18df]))
        self.assertEqual(0x9343ca0,self.rip(ins[0x1dcd3a7d]))
        self.assertEqual(0x9343d20,self.rip(ins[0x1dcd1961]))
        self.assertEqual(0x9343d20,self.rip(ins[0x1dcd3bc0]))
    def test_report_opens_only_finite_operations_and_keeps_source_usage_and_gameplay_honest(self):
        r=condition.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-resonance-condition-test-evidence.json'))
        for key in ['passed','conditionPostfixImplemented','ternaryBothBranchesEager','truthUsesStrictAlmostZero']: self.assertIs(True,r[key],key)
        self.assertEqual((3,5943,620,16,18,8588,1<<33,0),tuple(r[k] for k in ['matchedNativeMethods','matchedNativeBytes',
            'nativeFragmentBytes','initializerBytes','opcodeConstantsMatched','epsilonRaw','booleanTrueRaw','originalConditionProgramsObserved']))
        for key in ['helperFragmentsCompleteNativeMethods','newExtendedInputsSupported','actualParameterValuesResolved','nativeNumericProviderImplemented',
            'dynamicCellProvidersImplemented','liveCasterPropertiesRead','liveBattleContextAttached','originalTaskBranchesExecutedInBattle',
            'abilityExecutionVerified','jsonInstalled','clientExecutableStarted','clientModified','clientGameplayVerified','allResonanceAbilitiesImplemented']:
            self.assertIs(False,r[key],key)
    def test_invalid_output_paths_are_rejected_before_native_reads(self):
        for output in [ROOT/'condition-evidence.json',CLIENT/'condition-evidence.json']:
            with self.subTest(output=output),self.assertRaises(ValueError): condition.analyze(SimpleNamespace(client_dir=CLIENT,output=output))

if __name__=='__main__': unittest.main()
