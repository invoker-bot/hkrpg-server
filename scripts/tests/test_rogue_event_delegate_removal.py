"""核对普通事件委托撤回、子段方向与克隆字段的本机来源。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('event_delegate_removal',ROOT/'scripts/read-rogue-event-delegate-removal.py');event_delegate_removal=importlib.util.module_from_spec(spec);spec.loader.exec_module(event_delegate_removal)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in event_delegate_removal.load_profile()['nativeMethods'] for i in m['instructions']}
class EventDelegateRemovalTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=event_delegate_removal.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-event-delegate-removal-python-evidence.json'))
 def test_complete_remove_search_auxiliary_caller_fields_and_identities_match(self):
  self.assertEqual((4,2023,3,4135,2,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities']))
 def test_public_null_branches_precede_source_then_value_runtime_types_and_virtual_dispatch(self):
  r=rows();self.assertEqual(('test','rcx, rcx'),r['0x1c1a2457']);self.assertEqual(('test','rdx, rdx'),r['0x1c1a2462']);self.assertEqual(('call','0x183d3e590'),r['0x1c1a246e']);self.assertEqual(('call','0x183d3e590'),r['0x1c1a247d']);self.assertEqual(('cmp','rbx, rax'),r['0x1c1a2482']);self.assertEqual(('jmp','rax'),r['0x1c1a24ae'])
 def test_equal_length_comparison_invokes_source_but_shorter_sequence_invokes_value(self):
  r=rows();self.assertEqual(('mov','rsi, qword ptr [r14 + rbx*8 + 0x20]'),r['0x1c2521c9']);self.assertEqual(('mov','rbp, qword ptr [r12 + rbx*8 + 0x20]'),r['0x1c2521d7']);self.assertEqual(('mov','rsi, qword ptr [r12 + r13*8 + 0x20]'),r['0x1c2522ae']);self.assertEqual(('mov','rbp, qword ptr [rdi + r13*8]'),r['0x1c2522bc']);self.assertEqual(('dec','r15'),r['0x1c25235c'])
 def test_single_remove_two_item_reduction_and_length_one_exception_are_distinct(self):
  r=rows();self.assertEqual(('cmp','ecx, 1'),r['0x1c2524ef']);self.assertEqual(('jle','0x19c2526f5'),r['0x1c2524f2']);self.assertEqual(('cmp','ecx, 2'),r['0x1c2524fa']);self.assertEqual(('sete','cl'),r['0x1c252507']);self.assertEqual(('mov','r14, qword ptr [rax + rcx*8 + 0x20]'),r['0x1c25250a'])
 def test_sequence_clone_copies_target_method_and_uses_new_self_method_code_target(self):
  r=rows();self.assertEqual(('movups','xmm0, xmmword ptr [r15 + 0x20]'),r['0x1c252537']);self.assertEqual(('mov','qword ptr [rax + 0x40], rax'),r['0x1c252540']);self.assertEqual(('mov','rax, qword ptr [r15 + 0x38]'),r['0x1c252544']);self.assertEqual(('mov','qword ptr [r14 + 0x18], rax'),r['0x1c252548']);self.assertEqual(('mov','qword ptr [r14 + 0x38], rax'),r['0x1c25254c']);self.assertEqual(('mov','qword ptr [r14 + 0x68], rax'),r['0x1c25257c'])
 def test_sequence_copy_rereads_value_then_source_after_prefix(self):
  r=rows();self.assertEqual(('call','0x19c077020'),r['0x1c25258a']);self.assertEqual(('mov','rax, qword ptr [rbx + 0x68]'),r['0x1c25258f']);self.assertEqual(('mov','rcx, qword ptr [r15 + 0x68]'),r['0x1c25259c']);self.assertEqual(('mov','r8, qword ptr [r14 + 0x68]'),r['0x1c2525a9']);self.assertEqual(('call','0x19c075050'),r['0x1c252660'])
 def test_original_destroy_caller_and_two_complete_equalities_are_frozen_but_not_misreported(self):
  p=event_delegate_removal.load_profile();self.assertEqual([4486,4565,510243],[m['index'] for m in p['auxiliaryMethods']]);caller=p['auxiliaryMethods'][2];self.assertEqual('0xe811c10',caller['rva']);self.assertEqual(4,sum(i['mnemonic']=='call' and i['operand']=='0x19c1a2450' for i in caller['instructions']))
 def test_actual_joint_projection_keeps_factory_array_search_world_and_client_limits(self):
  for k,v in event_delegate_removal.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualBaseWithdrawalAndDelegateEqualityJointlyTested']);self.assertFalse(self.report['genericSingleArrayLastIndexOfBodyInstalled']);self.assertFalse(self.report['modifierDestroyEventCallerBodyImplemented']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
