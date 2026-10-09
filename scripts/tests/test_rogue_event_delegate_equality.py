"""逐项核对委托与多播委托有限投影中的相等顺序。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('event_delegate_equality',ROOT/'scripts/read-rogue-event-delegate-equality.py');event_delegate_equality=importlib.util.module_from_spec(spec);spec.loader.exec_module(event_delegate_equality)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in event_delegate_equality.load_profile()['nativeMethods'] for i in m['instructions']}
class EventDelegateEqualityTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=event_delegate_equality.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-event-delegate-equality-python-evidence.json'))
 def test_complete_equality_getter_source_fields_and_method_identities_match(self):
  self.assertEqual((2,805,1,155,4,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities']))
 def test_target_identity_precedes_other_then_self_method_resolution(self):
  r=rows();self.assertEqual(('mov','rax, qword ptr [rdi + 0x20]'),r['0x1c1a1aef']);self.assertEqual(('cmp','rax, qword ptr [rsi + 0x20]'),r['0x1c1a1af3']);self.assertEqual(('call','rbx'),r['0x1c1a1b1e']);self.assertEqual(('mov','r14, rax'),r['0x1c1a1b20']);self.assertEqual(('call','r15'),r['0x1c1a1b3d']);self.assertEqual(('cmp','r14, rax'),r['0x1c1a1b40'])
 def test_data_target_type_identity_precedes_string_method_name_equality(self):
  r=rows();self.assertEqual(('mov','rcx, qword ptr [rdi + 0x58]'),r['0x1c1a1b45']);self.assertEqual(('mov','rax, qword ptr [rsi + 0x58]'),r['0x1c1a1b49']);self.assertEqual(('cmp','rdx, qword ptr [rax + 0x18]'),r['0x1c1a1b5b']);self.assertEqual(('mov','rcx, qword ptr [rcx + 0x10]'),r['0x1c1a1b61']);self.assertEqual(('jmp','0x19c0a4950'),r['0x1c1a1b74']);p=event_delegate_equality.load_profile();self.assertEqual('op_Equality',next(m for m in p['boundMethodIdentities'] if m['index']==3492)['name'])
 def test_single_data_side_only_checks_nonnull_target_type_without_curried_flag(self):
  r=rows();self.assertEqual(('cmp','qword ptr [rax + 0x18], 0'),r['0x1c1a1b7e']);self.assertEqual(('test','rdx, rdx'),r['0x1c1a1b85']);p=event_delegate_equality.load_profile();self.assertEqual([(16,'method_name'),(24,'target_type'),(32,'curried_first_arg')],[(f['instanceOffset'],f['name']) for f in p['matchedTypes'][2]['fields']])
 def test_multicast_first_runs_base_then_checks_both_null_or_initial_lengths(self):
  r=rows();self.assertEqual(('call','0x19c1a1ab0'),r['0x1c251a56']);self.assertEqual(('mov','rcx, qword ptr [r15 + 0x68]'),r['0x1c251a9d']);self.assertEqual(('mov','rdx, qword ptr [rsi + 0x68]'),r['0x1c251aa1']);self.assertEqual(('cmp','eax, dword ptr [rdx + 0x18]'),r['0x1c251ac3'])
 def test_multicast_current_right_slot_and_left_virtual_equality_precede_current_left_reread(self):
  r=rows();self.assertEqual(('mov','rax, qword ptr [rsi + 0x68]'),r['0x1c251af8']);self.assertEqual(('mov','rbx, qword ptr [rcx + rdi*8 + 0x20]'),r['0x1c251b11']);self.assertEqual(('mov','r14, qword ptr [rax + rdi*8 + 0x20]'),r['0x1c251b1f']);self.assertEqual(('call','r13'),r['0x1c251b4c']);self.assertEqual(('mov','rcx, qword ptr [r15 + 0x68]'),r['0x1c251b56'])
 def test_shared_callback_inherited_target_data_and_invocation_list_are_matched(self):
  p=event_delegate_equality.load_profile();fields=p['matchedTypes'][3]['selectedInstanceFields'];self.assertEqual([(32,'m_target','Delegate'),(88,'data','Delegate'),(104,'delegates','MulticastDelegate')],[(f['instanceOffset'],f['name'],f['declaringType']) for f in fields]);self.assertEqual('GetMethodImpl',next(m for m in p['boundMethodIdentities'] if m['index']==4488)['name'])
 def test_separately_allocated_callbacks_keep_real_world_type_hierarchy_getter_and_gameplay_limits(self):
  for k,v in event_delegate_equality.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualSeparatelyAllocatedBaseCallbacksJointlyCompared']);self.assertFalse(self.report['fullRuntimeMethodInfoGetterBodyImplemented']);self.assertFalse(self.report['allClrDelegateEqualityInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
