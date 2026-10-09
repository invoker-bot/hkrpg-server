"""三种一元标准数回调、注册键和初始化常量的来源回归。"""
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
spec=importlib.util.spec_from_file_location('resonance_unary',ROOT/'scripts/read-rogue-resonance-unary.py')
unary=importlib.util.module_from_spec(spec)
spec.loader.exec_module(unary)


class ResonanceUnaryTest(unittest.TestCase):
    def instructions(self):
        decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
        decoder.detail=True
        profile=unary.load_profile()
        result={}
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

    def rip(self,i):
        op=next(o for o in i.operands if o.type==capstone.x86.X86_OP_MEM)
        return i.address+i.size+op.mem.disp-0x180000000

    def test_four_complete_methods_and_standard_helper_spans_match_installed_client(self):
        self.assertEqual((4,846),unary.verify_native(CLIENT))
        profile=unary.load_profile()
        self.assertEqual([74385,74387,74388,68417],[m['index'] for m in profile['nativeMethods']])
        self.assertEqual(129,profile['standardBranchFragmentBytes'])

    def test_callback_names_keys_and_method_identities_match_native_registration(self):
        rows=unary.load_profile()['registrations']
        self.assertEqual([('ceil',-514975797,74385),('clamp01',368963114,74387),('abs',1099313850,74388)],
            [(r['name'],r['hashKey'],r['callbackMethod']['index']) for r in rows])
        registered={r['name']:r for r in unary.functions.load_profile()['registrations']}
        self.assertEqual([registered[r['name']] for r in rows],rows)
        for r in rows: self.assertEqual(r['hashKey'],unary.functions.native_name_key(r['name']))

    def test_standard_ceiling_returns_integral_raw_or_uses_floor_mask_then_add_one(self):
        self.check([(0x1ce0b18b,('test','al, 1')),(0x1ce0b192,('shr','rcx, 1')),
                    (0x1ce0b196,('test','ecx, ecx')),(0x1ce0b198,('je',hex(0x180000000+0x1ce0b1ee))),
                    (0x1ce0b19a,('movabs','rcx, 0xfffffffe00000000')),
                    (0x1ce0b1a4,('and','rax, rcx')),(0x1ce0b1b5,('jmp',hex(0x180000000+0x1dcc86e0))),
                    (0x1ce0b1f2,('ret',''))])

    def test_standard_absolute_value_uses_signed_negation_and_original_on_negative_result(self):
        self.check([(0x1ce0b3ce,('test','cl, 1')),(0x1ce0b3d3,('neg','rax')),
                    (0x1ce0b3d6,('cmovs','rax, rcx')),(0x1ce0b3de,('ret',''))])
        raw=-(1<<63)
        negated=(-raw+(1<<63))%(1<<64)-(1<<63)
        self.assertEqual(raw,raw if negated<0 else negated)

    def test_standard_clamp01_uses_lower_then_upper_signed_comparisons(self):
        self.check([(0x1ce0b397,('jmp',hex(0x180000000+0x1dcd1b10))),
                    (0x1dcd1b24,('cmp','r9, rax')),(0x1dcd1b27,('jle',hex(0x180000000+0x1dcd1b75))),
                    (0x1dcd1be6,('cmp','r9, rax')),(0x1dcd1be9,('setl','r8b')),
                    (0x1dcd1c32,('mov','rax, r9')),(0x1dcd1c35,('ret',''))])

    def test_zero_and_one_initializer_pairs_match_actual_data_and_all_consumer_rips(self):
        ins=self.instructions()
        pe=pefile.PE(str(CLIENT/'GameAssembly.dll'),fast_load=True)
        facts={f['name']:f for f in unary.load_profile()['initializerPairs']}
        for name,value in [('one',1<<33),('zero',0)]:
            fact=facts[name]
            self.assertEqual(value,unary.read_pair(pe,fact))
            self.assertEqual(int(fact['rva'],16),self.rip(ins[int(fact['loadInstructionRva'],16)]))
            self.assertEqual(int(fact['destinationRva'],16),self.rip(ins[int(fact['storeInstructionRva'],16)]))
        self.assertEqual(int(facts['one']['destinationRva'],16)+8,self.rip(ins[0x1ce0b1aa]))
        self.assertEqual(int(facts['one']['destinationRva'],16)+8,self.rip(ins[0x1dcd1b75]))
        self.assertEqual(int(facts['zero']['destinationRva'],16)+8,self.rip(ins[0x1dcd1b13]))

    def test_helper_identity_and_partial_spans_do_not_claim_complete_clamp_or_add(self):
        profile=unary.load_profile()
        self.assertEqual([(68467,'op_Addition'),(68533,'Clamp01')],[(h['index'],h['name']) for h in profile['helperIdentities']])
        self.assertTrue(all(h['typeName']=='FixPoint' for h in profile['helperIdentities']))
        self.assertEqual(6,len(profile['standardBranchFragments']))
        self.assertTrue(all('endRva' not in f for f in profile['standardBranchFragments']))

    def test_report_keeps_extended_modes_parameters_provider_and_actual_gameplay_closed(self):
        report=unary.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-resonance-unary-test-evidence.json'))
        for key in ['passed','unaryStandaloneArithmeticImplemented','unaryPostfixCallerImplemented','absMinimumRawPreserved']:
            self.assertIs(True,report[key],key)
        self.assertEqual((4,846,129,32,2,3),tuple(report[k] for k in ['matchedNativeMethods','matchedNativeBytes',
            'standardInstructionFragmentBytes','initializerBytes','initializerPairsMatched','newRegistrationsMatched']))
        self.assertEqual(['floor','round','ceil','abs','clamp01'],report['supportedUnaryNames'])
        for key in ['helperFragmentsCompleteNativeMethods','ceilExtendedResultSupported','newExtendedInputsSupported',
                    'actualParameterValuesResolved','nativeNumericProviderImplemented','dynamicCellProvidersImplemented',
                    'liveCasterPropertiesRead','liveBattleContextAttached','originalTaskBranchesExecutedInBattle',
                    'abilityExecutionVerified','jsonInstalled','clientExecutableStarted','clientModified',
                    'clientGameplayVerified','allResonanceAbilitiesImplemented']:
            self.assertIs(False,report[key],key)

    def test_changed_pair_bytes_word_offset_or_value_are_rejected(self):
        pe=pefile.PE(str(CLIENT/'GameAssembly.dll'),fast_load=True)
        for kind in ['bytes','offset','value']:
            fact=deepcopy(unary.load_profile()['initializerPairs'][0])
            if kind=='bytes': fact['bytesHex']='00'*16
            elif kind=='offset': fact['wordOffset']=0
            else: fact['constantRaw']=0
            with self.subTest(kind=kind),self.assertRaises(ValueError): unary.read_pair(pe,fact)
        with self.assertRaises(ValueError): unary.read_rva(pe,0x9343d30,8)

    def test_invalid_outputs_are_rejected_before_native_reads(self):
        for output in [ROOT/'unary-evidence.json',CLIENT/'unary-evidence.json']:
            with self.subTest(output=output),self.assertRaises(ValueError):
                unary.analyze(SimpleNamespace(client_dir=CLIENT,output=output))


if __name__=='__main__': unittest.main()
