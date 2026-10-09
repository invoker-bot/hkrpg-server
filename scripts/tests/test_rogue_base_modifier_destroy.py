"""逐项核对基类销毁与两个委托监听撤回，不声称原生委托工厂。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('base_modifier_destroy',ROOT/'scripts/read-rogue-base-modifier-destroy.py');base_modifier_destroy=importlib.util.module_from_spec(spec);spec.loader.exec_module(base_modifier_destroy)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in base_modifier_destroy.load_profile()['nativeMethods'] for i in m['instructions']}
class BaseModifierDestroyTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=base_modifier_destroy.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-base-modifier-destroy-python-evidence.json'))
 def test_complete_base_auxiliary_fields_callbacks_parameters_and_identity_match(self):
  self.assertEqual((1,421,3,712,6,4,2,1,5,6),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedCallbackMethodSlots','matchedCallbackTypeSlots','matchedRipBindings','matchedNativeParameterRecords']))
 def test_reason_write_precedes_virtual_owner_lookup_and_entity_world_manager_capture(self):
  r=rows();self.assertEqual(('mov','dword ptr [rsi + 0x78], edi'),r['0xe5ae93c']);self.assertEqual(('call','rdi'),r['0xe5ae95b']);self.assertEqual(('mov','rbx, qword ptr [rax + 0x10]'),r['0xe5ae966']);self.assertEqual(('mov','rax, qword ptr [rbx + 0x88]'),r['0xe5ae973']);self.assertEqual(('mov','rdi, qword ptr [rax + 0x58]'),r['0xe5ae983'])
 def test_entity_id_is_captured_before_each_callback_allocation(self):
  r=rows();self.assertEqual(('mov','ebp, dword ptr [rbx + 0xc0]'),r['0xe5ae987']);self.assertEqual(('call','0x183d3a130'),r['0xe5ae994']);self.assertEqual(('mov','ebx, dword ptr [rbx + 0xc0]'),r['0xe5ae9df']);self.assertEqual(('call','0x183d3a130'),r['0xe5ae9ec']);p=base_modifier_destroy.load_profile();uid=next(r for r in p['parameterRecords'] if r['methodIndex']==523574 and r['parameter']['ordinal']==1);self.assertEqual(9,uid['record']['kind'])
 def test_first_delegate_writes_preserve_native_field_order_and_shared_target(self):
  r=rows();self.assertEqual(('mov','qword ptr [rax + 0x10], rdx'),r['0xe5ae9a4']);self.assertEqual(('mov','qword ptr [rax + 0x28], rcx'),r['0xe5ae9a8']);self.assertEqual(('mov','qword ptr [rax + 0x20], rsi'),r['0xe5ae9ac']);self.assertEqual(('mov','qword ptr [rax + 0x18], rdx'),r['0xe5ae9b0']);self.assertEqual(('mov','qword ptr [rax + 0x40], rsi'),r['0xe5ae9b4']);self.assertEqual(('mov','qword ptr [rax + 0x38], r14'),r['0xe5ae9bf'])
 def test_event207_and208_names_constants_and_unregister_identity_are_matched(self):
  p=base_modifier_destroy.load_profile();enum=p['matchedTypes'][5];self.assertEqual([207,208],[f['constant']['value'] for f in enum['selectedFields']]);unregister=next(m for m in p['boundMethodIdentities'] if m['index']==523574);self.assertEqual('UnRegisterEventListener',unregister['name']);r=rows();self.assertEqual(('mov','edx, 0xcf'),r['0xe5ae9cf']);self.assertEqual(('call','0x18e66d300'),r['0xe5ae9da']);self.assertEqual(('mov','edx, 0xd0'),r['0xe5aea17']);self.assertEqual(('jmp','0x18e66d300'),r['0xe5aea2c'])
 def test_two_callback_slots_use_destroy_and_fade_out_method_identities(self):
  p=base_modifier_destroy.load_profile();self.assertEqual([(508636,'_OnOtherModifierDestroy'),(508637,'_OnOtherModifierAttachEffectFadeOutFinish')],[(row['method']['index'],row['method']['name']) for row in p['callbackMethodSlots']]);self.assertEqual((56692,'OnEventCallBack'),(p['callbackTypeSlots'][0]['typeIndex'],p['callbackTypeSlots'][0]['typeName']))
 def test_callback_inherited_fields_and_world_event_manager_are_independently_matched(self):
  p=base_modifier_destroy.load_profile();self.assertEqual([(16,'method_ptr'),(24,'invoke_impl'),(32,'m_target'),(40,'method'),(56,'extra_arg'),(64,'method_code')],[(f['instanceOffset'],f['name']) for f in p['matchedTypes'][4]['selectedInstanceFields']]);f=p['matchedTypes'][3]['selectedInstanceFields'][0];self.assertEqual(('_EventManager',88,56690),(f['name'],f['instanceOffset'],f['nativeType']['typeIndex']));self.assertEqual('0x3d10',p['ripBindings'][2]['target'])
 def test_joint_ordinary_base_execution_keeps_native_factory_event_manager_and_world_boundaries(self):
  for k,v in base_modifier_destroy.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualOrdinaryDestroyAndBaseWithdrawalJointlyTested']);self.assertFalse(self.report['eventManagerWithdrawalBodyImplemented']);self.assertFalse(self.report['nativeDelegateFactoryInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
