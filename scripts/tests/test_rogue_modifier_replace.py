"""核对替换顺序、序列期间延迟能力、列表/可空参数与明确绑定边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_replace',ROOT/'scripts/read-rogue-modifier-replace.py')
replace=importlib.util.module_from_spec(spec);spec.loader.exec_module(replace)
def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in replace.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}
class ModifierReplaceTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=replace.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-replace-python-evidence.json'))
 def test_complete_methods_fields_and_four_generic_arguments_match(self):
  self.assertEqual((4,1543,5,4),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedGenericArguments']))
 def test_executing_sequence_count_is_integer_not_a_mode_enum(self):
  f=next(f for t in replace.load_profile()['matchedTypes'] if t['typeIndex']==54843 for f in t['selectedInstanceFields'] if f['name']=='_ExecutingSequenceCount')
  self.assertEqual((152,8),(f['instanceOffset'],f['nativeType']['kind']))
  self.assertEqual(('cmp','dword ptr [rsi + 0x98], 0'),rows(508647)['0xe5aeaf1'])
 def test_strings_and_fixed_number_injection_lists_are_not_nullable_integers(self):
  p=replace.load_profile();f=p['genericFields']
  self.assertEqual([('List`1',17,54435),('List`1',17,54434)],[(x['record']['genericName'],x['record']['args'][0]['kind'],x['record']['args'][0]['payload']) for x in f[:2]])
  types={t['typeIndex']:t for t in p['matchedTypes']}
  self.assertEqual([8,14],[f['nativeType']['kind'] for f in types[54435]['fields']])
  self.assertEqual([10875,9939],[f['nativeType']['typeIndex'] for f in types[54434]['fields']])
 def test_maximum_and_forced_layer_fields_are_nullable_int32(self):
  f=replace.load_profile()['genericFields'][2:]
  self.assertEqual([108,148],[x['field']['instanceOffset'] for x in f])
  self.assertEqual([('Nullable`1',[8])]*2,[(x['record']['genericName'],[a['kind'] for a in x['record']['args']]) for x in f])
  self.assertEqual(('cmp','byte ptr [r15 + 0x70], 0'),rows(510107)['0xe807d43'])
 def test_override_acquisition_precedes_parameter_presence_and_max_callback_is_unconditional(self):
  r=rows(510107)
  self.assertEqual(('call','0x195c612d0'),r['0xe807d32'])
  self.assertEqual(('test','r15, r15'),r['0xe807d37'])
  self.assertEqual(('mov','dword ptr [rbx + 0x290], eax'),r['0xe807d6c'])
  self.assertEqual(('mov','edx, 2'),r['0xe807d75'])
  self.assertEqual(('call','0x18e807e60'),r['0xe807d7a'])
 def test_layer_add_write_and_normal_release_precede_force_copy_and_life_flag(self):
  r=rows(510107)
  self.assertEqual(('call','0x195c60d80'),r['0xe807d9c'])
  self.assertEqual(('mov','dword ptr [rbx + 0x250], eax'),r['0xe807da1'])
  self.assertEqual(('call','0x18b10ce60'),r['0xe807db6'])
  self.assertEqual(('mov','rax, qword ptr [r15 + 0x94]'),r['0xe807dbb'])
  self.assertEqual(('mov','qword ptr [rbx + 0x2b4], rax'),r['0xe807dc2'])
 def test_explicit_immediate_parameter_short_circuits_flag_seven(self):
  r=rows(510107)
  self.assertEqual(('cmp','byte ptr [r15 + 0x6b], 0'),r['0xe807dcb'])
  self.assertEqual(('jne','0x18e807de2'),r['0xe807dd0'])
  self.assertEqual(('mov','edx, 7'),r['0xe807dd5'])
  self.assertEqual(('mov','byte ptr [rbx + 0x2e5], al'),r['0xe807de2'])
 def test_base_defers_ability_during_execution_and_injects_floats_before_strings(self):
  r=rows(508647)
  self.assertEqual(('mov','qword ptr [rsi + 0x48], r14'),r['0xe5aeafa'])
  self.assertEqual(('mov','rdx, qword ptr [rdi + 0x18]'),r['0xe5aeb07'])
  self.assertEqual(('mov','qword ptr [rsi + 0x20], rdx'),r['0xe5aeb10'])
  self.assertEqual(('call','0x18e5ae2c0'),r['0xe5aeb17'])
  self.assertEqual(('mov','rdx, qword ptr [rdi + 0x10]'),r['0xe5aeb1c'])
  self.assertEqual(('mov','qword ptr [rsi + 0x18], rdx'),r['0xe5aeb25'])
  self.assertEqual(('call','0x18e5ae400'),r['0xe5aeb2c'])
 def test_lookup_native_lists_virtual_dispatch_and_full_runtime_remain_closed(self):
  for key in ['nativeNumericOverrideLookupInstalled','nativeInjectionListsInstalled','nativeContextVirtualSlotsInstalled','nativeReplaceVirtualSlotsInstalled','fullNativeOnReplaceImplemented','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[key],key)
 def test_finite_replace_order_and_shared_maximum_notification_are_open(self):
  for key in ['finiteOnReplaceOrderImplemented','numericRecalculationAndReleaseOrderImplemented','deferredAbilityReplacementImplemented','baseInjectionOrderImplemented','forceLayerAndImmediateCopied','sharedMaximumLayerNotificationImplemented']:self.assertIs(True,self.report[key],key)
if __name__=='__main__':unittest.main()
