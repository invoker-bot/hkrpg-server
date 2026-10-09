"""核对标准根容器注入、数值编码比较和原件边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_injection',ROOT/'scripts/read-rogue-modifier-injection.py')
injection=importlib.util.module_from_spec(spec);spec.loader.exec_module(injection)
def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in injection.load_profile()['nativeMethods']+injection.load_profile()['nativeFragments'] if m['index']==index for i in m.get('instructions',[])}
class ModifierInjectionTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=injection.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-injection-python-evidence.json'))
 def test_complete_methods_fields_and_separate_fragments_match(self):
  self.assertEqual((3,2464,3,3,464),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedNativeMethodFragments','matchedNativeFragmentBytes']))
 def test_float_loop_rereads_container_and_count_and_null_list_throws(self):
  r=rows(508648);self.assertEqual(('mov','rcx, qword ptr [r15 + 0x68]'),r['0xe5ae32c']);self.assertEqual(('movsxd','rax, dword ptr [r13 + 0x18]'),r['0xe5ae37f']);self.assertEqual(('call','0x183cc8050'),r['0xe5ae3e1']);self.assertEqual(('call','0x19cd7e070'),r['0xe5ae377'])
 def test_existing_bounds_are_copied_without_second_clamp(self):
  r=rows(74759);self.assertEqual(('call','0x19dcd1a00'),r['0x1cd7e093']);self.assertEqual(('mov','qword ptr [rbx + 0x38], rax'),r['0x1cd7e367']);self.assertEqual(('mov','qword ptr [rbx + 0x40], rax'),r['0x1cd7e3f7']);self.assertFalse(any(m=='call' and o=='0x19dcd1a00' for a,(m,o) in r.items() if int(a,16)>0x1cd7e098))
 def test_initializer_observer_passes_absent_previous_optional_and_input_raw(self):
  r=rows(74759);self.assertEqual(('mov','r9, qword ptr [rsi + 8]'),r['0x1cd7e4f3']);self.assertEqual(('xorps','xmm0, xmm0'),r['0x1cd7e4ff']);self.assertEqual(('movaps','xmmword ptr [rsp + 0x30], xmm0'),r['0x1cd7e502'])
 def test_mixed_compare_uses_seven_bit_arithmetic_shift_and_residual(self):
  r=rows(68439);self.assertEqual(('sar','rax, 7'),r['0x1dccc611']);self.assertEqual(('test','cl, 0x7f'),r['0x1dccc62a']);self.assertEqual(('neg','eax'),r['0x1dccc666']);self.assertTrue(all(f['completePEFunction'] is False for f in injection.load_profile()['nativeFragments']))
 def test_native_fractional_places_exclude_reserved_mode_bit(self):
  t=next(t for t in injection.load_profile()['matchedTypes'] if t['typeIndex']==9939);values={f['name']:f.get('constant',{}).get('value') for f in t['selectedFields']};self.assertEqual(32,values['STANDARD_FRACTIONAL_PLACES']);self.assertEqual(7,values['EXTENDED_SLIDE']);self.assertEqual(26,values['EXTENDED_TOTAL_SHIFT']);self.assertEqual(16,next(f for f in t['selectedFields'] if f['name']=='m_rawValue')['instanceOffset'])
 def test_standard_root_injection_and_order_are_implemented(self):
  for key in ['sourceStandardRootInjectionsImplemented','retainedOldFixedBoundsPreserved','noSecondClampAgainstOldBounds','initializerObserverPreviousAbsent','currentContainerAndListCountReread','rawTaggedComparisonImplemented','rawTaggedClampImplemented','ordinaryWritePreviousPresencePreserved']:self.assertIs(True,self.report[key],key)
 def test_general_initializer_extended_cells_and_live_world_remain_unclaimed(self):
  for key in ['generalNativeCellInitializerImplemented','extendedVariableCellsImplemented','injectionIntoProjectedCellsImplemented','nativeAdsorptionMetadataInstalled','nativeNumericContainerFactoriesInstalled','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[key],key)
if __name__=='__main__':unittest.main()
