"""带种子基础随机、原件常量与普通投影的证据边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('logic_random',ROOT/'scripts/read-rogue-logic-random.py')
random=importlib.util.module_from_spec(spec);spec.loader.exec_module(random)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in random.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class LogicRandomTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=random.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-logic-random-python-evidence.json'))
 def test_complete_methods_fields_and_constants_match_client(self):
  self.assertEqual((7,1877,2,5,56),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedConstants','matchedConstantBytes']))
 def test_nine_fixed_seeds_and_1152_samples_match_independent_host_vectors(self):
  self.assertEqual((9,1152),random.verify_vectors());self.assertEqual(1152,self.report['comparedSamples'])
 def test_seed_minimum_uses_the_maximum_magnitude_special_case(self):
  r=rows(3204)
  self.assertEqual(('cmp','esi, 0x80000000'),r['0x1c265816'])
  self.assertEqual(('mov','ebx, 0x89a4ec87'),r['0x1c26581c'])
  self.assertEqual(('mov','edx, 0x38'),r['0x1c2657f9'])
  self.assertEqual(('movabs','rax, 0x1500000000'),r['0x1c265a67'])
 def test_four_mix_passes_and_final_index_pair_are_explicit(self):
  r=rows(3204)
  self.assertEqual(4,sum(m=='mov' and o=='edi, 0x1f' for m,o in r.values()))
  self.assertEqual(('mov','qword ptr [r14 + 0x18], rax'),r['0x1c265a71'])
 def test_integer_sample_is_bounded_by_maximum_exclusion(self):
  r=rows(3206)
  self.assertEqual(('cmp','eax, 0x7fffffff'),r['0x1c265bb0'])
  self.assertEqual(('mov','eax, 0x7ffffffe'),r['0x1c265bb5'])
  self.assertEqual(('mov','dword ptr [r9 + r8*4 + 0x20], eax'),r['0x1c265bbd'])
  constants=random.load_profile()['constants']
  self.assertEqual([55,55,0,0],constants[0]['int32']);self.assertEqual([1,1,0,0],constants[1]['int32'])
 def test_seed_wrapper_initializes_seed_count_and_concrete_seeded_random(self):
  r=rows(520484)
  self.assertEqual(('mov','dword ptr [rcx + 0x18], edx'),r['0xe5060dc'])
  self.assertEqual(('mov','dword ptr [rcx + 0x1c], 0'),r['0xe5060df'])
  self.assertEqual(('call','0x19c2657e0'),r['0xe5060fa'])
  self.assertEqual(('mov','qword ptr [rdi + 0x10], rbx'),r['0xe5060ff'])
 def test_counter_notification_precedes_local_and_global_assignments(self):
  r=rows(520486)
  self.assertEqual(('mov','ecx, ebx'),r['0xe5061d2']);self.assertEqual(('mov','edx, edi'),r['0xe5061d4'])
  self.assertEqual(('call','0x1958b04d0'),r['0xe5061d6'])
  self.assertEqual(('mov','dword ptr [rsi + 0x1c], edi'),r['0xe5061db'])
  self.assertEqual(('mov','dword ptr [rip - 0x51bbf74], edi'),r['0xe5061de'])
 def test_inverse_is_multiplied_and_ordinary_conversion_truncates_clears_low_bit(self):
  r=rows(3205);self.assertEqual(('mulsd','xmm0, qword ptr [rip - 0x16cf94d6]'),r['0x1c265b1e'])
  r=rows(520486);self.assertEqual(('cvttsd2si','rax, xmm6'),r['0xe506212'])
  self.assertEqual(('and','rax, 0xfffffffffffffffe'),r['0xe506217'])
  self.assertEqual(8589934592.0,random.load_profile()['constants'][3]['double'])
 def test_base_seeded_projection_is_distinct_from_events_world_and_client_acceptance(self):
  for k in ['seededBaseRandomImplemented','ordinaryLogicSampleImplemented','normalCounterOrderImplemented',
   'signed32CounterWrapImplemented','seededIntegerWrapImplemented','sampleConversionTruncatesAndClearsLowBit']:self.assertTrue(self.report[k],k)
  for k in ['nativeCounterEventPoolImplemented','actualGlobalCounterStorageInstalled','unseededRandomImplemented',
   'randomSubclassOverridesImplemented','runtimeHotfixOverridesImplemented','allRandomOperationsImplemented','worldLogicRandomInstalled',
   'modifierInstanceApplied','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented',
   'clientGameplayVerified','hostComparisonIsClientExecution','jsonInstalled','clientExecutableStarted','clientModified']:self.assertFalse(self.report[k],k)
 def test_report_rejects_client_and_nonbuild_output(self):
  for output in [CLIENT/'logic-random.json',ROOT/'logic-random.json']:
   with self.assertRaises(ValueError):random.analyze(SimpleNamespace(client_dir=CLIENT,output=output))

if __name__=='__main__':unittest.main()
