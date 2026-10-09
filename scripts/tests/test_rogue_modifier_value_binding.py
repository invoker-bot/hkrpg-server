"""核对动态数值绑定槽位、整数编码和标准变量联合边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_value_binding',ROOT/'scripts/read-rogue-modifier-value-binding.py')
binding=importlib.util.module_from_spec(spec);spec.loader.exec_module(binding)
def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in binding.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}
class ModifierValueBindingTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=binding.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-value-binding-python-evidence.json'))
 def test_all_complete_methods_and_three_field_types_match(self):
  self.assertEqual((3,1959,3),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes']))
 def test_binding_array_is_string_hash_values_and_mask_is_unsigned(self):
  t=next(t for t in binding.load_profile()['matchedTypes'] if t['typeIndex']==54843)
  f={f['name']:f for f in t['selectedInstanceFields']}
  self.assertEqual((432,29,10875),(f['_BindModifierValueArr']['instanceOffset'],f['_BindModifierValueArr']['nativeType']['kind'],f['_BindModifierValueArr']['nativeType']['elementType']['typeIndex']))
  self.assertEqual((644,9),(f['_BindModifierValueMask']['instanceOffset'],f['_BindModifierValueMask']['nativeType']['kind']))
  self.assertEqual(10850,f['<DynamicValues>k__BackingField']['nativeType']['typeIndex'])
 def test_array_mask_and_unsigned_type_range_gate_before_any_write(self):
  r=rows(510253)
  self.assertEqual(('mov','rax, qword ptr [rsi + 0x1b0]'),r['0xe807e79'])
  self.assertEqual(('bt','ecx, edi'),r['0xe807e8b'])
  self.assertEqual(('cmp','edi, 3'),r['0xe807e90'])
  self.assertEqual(('ja','0x18e807ef1'),r['0xe807e93'])
 def test_each_slot_has_length_guard_and_uses_its_own_hash(self):
  r=rows(510253)
  for address,operation,operand in [('0xe807ea7','cmp','dword ptr [rax + 0x18], 0'),('0xe807ef9','test','dword ptr [rax + 0x18], 0xfffffffe'),('0xe807f46','cmp','dword ptr [rax + 0x18], 2'),('0xe807fb1','test','dword ptr [rax + 0x18], 0xfffffffc')]:
   self.assertEqual((operation,operand),r[address])
  for address,operand in [('0xe807ee9','edx, dword ptr [rax + 0x20]'),('0xe807f3e','edx, dword ptr [rax + 0x24]'),('0xe807f54','ebx, dword ptr [rax + 0x28]'),('0xe807ff2','edx, dword ptr [rax + 0x2c]')]:self.assertEqual(('mov',operand),r[address])
 def test_current_layer_count_life_use_signed_integer_reads(self):
  r=rows(510253)
  for address,operand in [('0xe807ebe','r8, dword ptr [rsi + 0x274]'),('0xe807f13','r8, dword ptr [rsi + 0x84]'),('0xe807fc7','r8, dword ptr [rsi + 0x2a8]')]:self.assertEqual(('movsxd',operand),r[address])
 def test_max_layer_adds_int32_then_uses_minimum_one_in_getter_and_notifier(self):
  r=rows(510253);g=rows(510283)
  self.assertEqual(('add','ecx, dword ptr [rsi + 0x290]'),r['0xe807f6a'])
  self.assertEqual(('cmp','ecx, 2'),r['0xe807f70'])
  self.assertEqual(('cmovge','eax, ecx'),r['0xe807f78'])
  self.assertEqual(('add','ecx, dword ptr [rsi + 0x290]'),g['0xe814177'])
  self.assertEqual(('cmovge','eax, ecx'),g['0xe814185'])
 def test_all_four_values_choose_33_or_26_bit_encoding_at_absolute_boundary(self):
  r=rows(510253)
  for start,end in [(0xe807ebe,0xe807ee9),(0xe807f13,0xe807f3e),(0xe807f84,0xe807faa),(0xe807fc7,0xe807ff2)]:
   instructions=[v for a,v in r.items() if start<=int(a,16)<end]
   self.assertTrue(any(m=='cmp' and o.endswith('0x40000000') for m,o in instructions))
   self.assertTrue(any(m=='shl' and o.endswith('0x1a') for m,o in instructions))
   self.assertTrue(any(m=='shl' and o.endswith('0x21') for m,o in instructions))
   self.assertTrue(any(m=='or' and o.endswith(', 1') for m,o in instructions))
 def test_native_missing_key_returns_before_any_value_getter_clamp_or_set(self):
  r=rows(74764)
  self.assertEqual(('call','0x19adccce0'),r['0x1cd7ea47'])
  self.assertEqual(('test','eax, eax'),r['0x1cd7ea4c'])
  self.assertEqual(('js','0x19cd7ed9f'),r['0x1cd7ea4e'])
  self.assertEqual(('call','rbp'),r['0x1cd7eb05'])
  self.assertEqual(('call','0x19dcd1a00'),r['0x1cd7ec30'])
  self.assertEqual(('call','rbp'),r['0x1cd7ecc9'])
 def test_extended_cell_arithmetic_native_arrays_and_world_are_still_closed(self):
  for key in ['mixedVariableCellsImplemented','nativeValueArraysInstalled','nativeDynamicContainerInstalled','nativeCountVirtualBindingInstalled','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[key],key)
 def test_finite_bindings_and_standard_variables_are_explicitly_open(self):
  for key in ['finiteModifierValueBindingsImplemented','currentValuesReadAtNotification','signed32MaxLayerWrapImplemented','standardAndExtendedIntegerEncodingImplemented','standardVariableJointBindingImplemented','absentStandardVariableKeySkipped']:self.assertIs(True,self.report[key],key)
if __name__=='__main__':unittest.main()
