"""核对两个回调过滤、活动状态、差异标志与未还原的虚处理边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('base_other_modifier_events',ROOT/'scripts/read-rogue-base-other-modifier-events.py');base_other_modifier_events=importlib.util.module_from_spec(spec);spec.loader.exec_module(base_other_modifier_events)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in base_other_modifier_events.load_profile()['nativeMethods'] for i in m['instructions']}
class BaseOtherModifierEventsTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=base_other_modifier_events.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-base-other-modifier-events-python-evidence.json'))
 def test_complete_callbacks_auxiliary_types_slots_generics_and_identity_match(self):
  self.assertEqual((2,530,0,0,3,1226,4,1,2,1,2,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings']))
 def test_null_payload_and_type_hierarchy_cast_precede_modifier_state(self):
  r=rows();self.assertEqual(('test','rdi, rdi'),r['0xe5ada16']);self.assertEqual(('cmp','rcx, rax'),r['0xe5ada25']);self.assertEqual(('cmp','qword ptr [rcx + rdx*8 - 8], rax'),r['0xe5ada3d']);self.assertEqual(('test','rdi, rdi'),r['0xe5adb26']);self.assertEqual(('cmp','qword ptr [rcx + rdx*8 - 8], rax'),r['0xe5adb4d'])
 def test_self_identity_at_payload24_precedes_exact_alive_state_at_receiver144(self):
  r=rows();self.assertEqual(('cmp','qword ptr [rdi + 0x18], rsi'),r['0xe5ada44']);self.assertEqual(('cmp','dword ptr [rsi + 0x90], 1'),r['0xe5ada4a']);self.assertEqual(('cmp','qword ptr [rdi + 0x18], rsi'),r['0xe5adb54']);self.assertEqual(('cmp','dword ptr [rsi + 0x90], 1'),r['0xe5adb5a'])
 def test_effect_list_field32_is_read_only_after_alive_check(self):
  r=rows();self.assertEqual(('mov','rdi, qword ptr [rdi + 0x20]'),r['0xe5ada53']);self.assertEqual(('mov','rdi, qword ptr [rdi + 0x20]'),r['0xe5adb63']);p=base_other_modifier_events.load_profile();self.assertEqual([24,32,40],[f['instanceOffset'] for f in p['matchedTypes'][1]['fields']])
 def test_two_callbacks_forward_same_effects_with_false_and_true_at_virtual_slot21(self):
  r=rows();self.assertEqual(('mov','rax, qword ptr [rcx + 0x168]'),r['0xe5ada5a']);self.assertEqual(('mov','rdx, rdi'),r['0xe5ada6d']);self.assertEqual(('xor','r8d, r8d'),r['0xe5ada70']);self.assertEqual(('mov','r8b, 1'),r['0xe5adb80']);self.assertEqual(('jmp','rax'),r['0xe5ada7d']);self.assertEqual(('jmp','rax'),r['0xe5adb8d'])
 def test_payload_types_are_distinct_and_generic_effect_list_is_matched_independently(self):
  p=base_other_modifier_events.load_profile();self.assertEqual([56598,56599],[row['typeIndex'] for row in p['payloadTypeSlots']]);self.assertEqual(['LIMAAJAJPKJ','GEHAPKHIMNE'],[row['typeName'] for row in p['payloadTypeSlots']]);self.assertEqual(2,len(p['genericFields']))
  for row in p['genericFields']:
   record=row['record'];self.assertEqual((1570,'List`1',54431,'ModifierEffectData'),(record['genericDefinition'],record['genericName'],record['args'][0]['payload'],record['args'][0]['name']))
 def test_mutex_abstract_signature_is_recorded_without_claiming_virtual_slot_identity(self):
  p=base_other_modifier_events.load_profile();m=p['boundMethodIdentities'][0];self.assertEqual((508633,'_TryAttachMutexEffects','-0x180000000'),(m['index'],m['name'],m['rva']));self.assertFalse(self.report['virtualSlot21ToMutexDeclarationMatched']);self.assertFalse(self.report['virtualEffectProcessingBodyImplemented'])
 def test_actual_base_factory_and_fresh_delegate_joint_checks_keep_dispatcher_and_client_limits(self):
  for k,v in base_other_modifier_events.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualBaseCallbackFactoryAndFreshDelegateComparisonJointlyTested']);self.assertFalse(self.report['nativePayloadFactoryOrGlobalEventDispatcherInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
