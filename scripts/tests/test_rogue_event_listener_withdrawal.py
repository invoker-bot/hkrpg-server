"""核对实体事件键、字典条目与池化监听集合移除来源。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('event_listener_withdrawal',ROOT/'scripts/read-rogue-event-listener-withdrawal.py');event_listener_withdrawal=importlib.util.module_from_spec(spec);spec.loader.exec_module(event_listener_withdrawal)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in event_listener_withdrawal.load_profile()['nativeMethods']+event_listener_withdrawal.load_profile()['runtimeMethods'] for i in m['instructions']}
class EventListenerWithdrawalTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=event_listener_withdrawal.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-event-listener-withdrawal-python-evidence.json'))
 def test_complete_manager_pooled_helper_dictionary_source_fields_and_methodspec_match(self):
  self.assertEqual((1,182,1,360,1,1086,3,1,1,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedPoolMethodSpecs']))
 def test_missing_map_and_negative_lookup_skip_current_entries_and_removal(self):
  r=rows();self.assertEqual(('mov','rsi, qword ptr [rsi + 0x18]'),r['0xe66d31d']);self.assertEqual(('je','0x18e66d396'),r['0xe66d324']);self.assertEqual(('call','0x19ac73140'),r['0xe66d332']);self.assertEqual(('js','0x18e66d396'),r['0xe66d339'])
 def test_raw_key_packs_event_upper_and_unsigned_entity_lower_fields(self):
  r=rows();self.assertEqual(('shl','rdi, 0x20'),r['0xe66d326']);self.assertEqual(('mov','edx, ebx'),r['0xe66d32a']);self.assertEqual(('or','rdx, rdi'),r['0xe66d32c']);key=next(t for t in event_listener_withdrawal.load_profile()['matchedTypes'] if t['typeIndex']==56689);self.assertEqual([(16,9),(20,17)],[(f['instanceOffset'],f['nativeType']['kind']) for f in key['fields']])
 def test_captured_dictionary_entries_are_reread_after_lookup_with_twenty_four_byte_stride(self):
  r=rows();self.assertEqual(('mov','rcx, qword ptr [rsi + 0x18]'),r['0xe66d33b']);self.assertEqual(('cmp','eax, dword ptr [rcx + 0x18]'),r['0xe66d344']);self.assertEqual(('lea','rax, [rax + rax*2]'),r['0xe66d34b']);self.assertEqual(('mov','rcx, qword ptr [rcx + rax*8 + 0x30]'),r['0xe66d34f']);self.assertEqual(('jmp','0x19a9932b0'),r['0xe66d36c'])
 def test_map_generic_is_struct_key_and_pooled_on_event_callback_value(self):
  p=event_listener_withdrawal.load_profile();g=p['genericFields'][0];self.assertEqual((24,1535),(g['field']['instanceOffset'],g['record']['genericDefinition']));key,value=g['record']['args'];self.assertEqual((56689,5111,56692),(key['payload'],value['genericDefinition'],value['args'][0]['payload']))
 def test_pooled_remove_methodspec_uses_method_instantiation_with_on_event_callback(self):
  p=event_listener_withdrawal.load_profile();row=p['poolMethodSpecs'][0];self.assertEqual(('0x95b1cb0',35343,-1,41628),(row['slot'],row['methodSpec']['methodDefinition'],row['methodSpec']['classInst'],row['methodSpec']['methodInst']));self.assertEqual(56692,row['instRecords'][0]['arguments'][0]['payload']);self.assertEqual('Remove',p['boundMethodIdentities'][0]['name'])
 def test_inner_collection_is_captured_then_virtual_interface_remove_called(self):
  r=rows();self.assertEqual(('mov','rdi, qword ptr [rcx + 0x10]'),r['0x1a9932b9']);self.assertEqual(('mov','r14, rdx'),r['0x1a9932c6']);self.assertEqual(('mov','rcx, rdi'),r['0x1a993374']);self.assertEqual(('mov','rdx, r14'),r['0x1a993377']);self.assertEqual(('call','rbx'),r['0x1a99337a']);p=event_listener_withdrawal.load_profile();pooled=next(t for t in p['matchedTypes'] if t['typeIndex']==5111);self.assertEqual(0,next(f for f in pooled['fields'] if f['name']=='_processor')['instanceOffset']);self.assertFalse(self.report['genericTemplateOffsetZeroTreatedAsPhysicalInstanceOffset'])
 def test_real_base_manager_and_delegate_joint_tests_keep_closed_runtime_world_and_gameplay_limits(self):
  for k,v in event_listener_withdrawal.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualOrdinaryDestroyBaseManagerWithdrawalJointlyTested']);self.assertFalse(self.report['fullDictionaryIndexHelperBodyImplemented']);self.assertFalse(self.report['genericCollectionInterfaceRemoveBodyInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
