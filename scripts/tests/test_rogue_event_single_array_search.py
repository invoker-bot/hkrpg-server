"""逐项核对数组查询边界、零次转发与 Delegate 泛型原件。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('event_single_array_search',ROOT/'scripts/read-rogue-event-single-array-search.py');event_single_array_search=importlib.util.module_from_spec(spec);spec.loader.exec_module(event_single_array_search)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in event_single_array_search.load_profile()['runtimeMethods'] for i in m['instructions']}
class EventSingleArraySearchTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=event_single_array_search.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-event-single-array-search-python-evidence.json'))
 def test_complete_helpers_caller_getter_and_method_spec_match(self):
  self.assertEqual((0,0,2,435,4,2023,1,389,0,1,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedCompleteAuxiliaryRuntimeHelpers','matchedCompleteAuxiliaryRuntimeHelperBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedPoolMethodSpecs']))
 def test_array_null_and_start_validation_precede_count_and_dispatch(self):
  r=rows();self.assertEqual(('test','rcx, rcx'),r['0x16bf88a9']);self.assertEqual(('test','edi, edi'),r['0x16bf88c0']);self.assertEqual(('cmp','eax, edi'),r['0x16bf88c4']);self.assertEqual(('test','esi, esi'),r['0x16bf88c8']);self.assertEqual(('sub','eax, esi'),r['0x16bf88ce']);self.assertEqual(('cmp','eax, -2'),r['0x16bf88d0'])
 def test_empty_array_two_start_values_and_zero_count_return_minus_one(self):
  r=rows();self.assertEqual(('inc','edi'),r['0x16bf8902']);self.assertEqual(('cmp','edi, 2'),r['0x16bf8904']);self.assertEqual(('jae','0x196bf8941'),r['0x16bf8907']);self.assertEqual(('test','esi, esi'),r['0x16bf8909']);self.assertEqual(('mov','eax, 0xffffffff'),r['0x16bf890d'])
 def test_nonempty_zero_count_has_no_early_return_before_search_dispatch(self):
  r=rows();self.assertEqual(('mov','r9d, esi'),r['0x16bf88f8']);self.assertEqual(('call','0x196c2f350'),r['0x16bf88fb']);self.assertEqual(('mov','rsi, rcx'),r['0x16c2f365']);self.assertEqual(('mov','r15, rdx'),r['0x16c2f362']);self.assertEqual(('mov','r14d, r8d'),r['0x16c2f35f'])
 def test_default_comparer_lookup_precedes_virtual_method_and_argument_forwarding(self):
  r=rows();self.assertEqual(('call','0x19b576620'),r['0x16c2f38d']);self.assertEqual(('mov','rcx, qword ptr [rax]'),r['0x16c2f395']);self.assertEqual(('mov','rdi, qword ptr [rcx + 0x118]'),r['0x16c2f398']);self.assertEqual(('mov','dword ptr [rsp + 0x20], ebx'),r['0x16c2f3bc']);self.assertEqual(('call','rdi'),r['0x16c2f3cc'])
 def test_array_last_index_method_spec_argument_is_delegate_and_not_a_command_or_class_slot(self):
  p=event_single_array_search.load_profile();m=p['boundMethodIdentities'][0];self.assertEqual((1027,136,'Array','LastIndexOf'),(m['index'],m['typeIndex'],m['typeName'],m['name']));row=p['poolMethodSpecs'][0];self.assertEqual(('0x9498300',6,1027,121,-1),(row['slot'],row['usageKind'],row['methodSpec']['methodDefinition'],row['methodSpec']['classInst'],row['methodSpec']['methodInst']));arg=row['instRecords'][0]['arguments'][0];self.assertEqual((18,388,'Delegate'),(arg['kind'],arg['payload'],arg['name']))
 def test_getter_is_complete_source_only_and_helpers_keep_no_registered_identity_claim(self):
  p=event_single_array_search.load_profile();self.assertEqual('0x1b576620',p['auxiliaryRuntimeMethods'][0]['rva']);self.assertEqual(389,len(bytes.fromhex(p['auxiliaryRuntimeMethods'][0]['bytesHex'])));self.assertFalse(self.report['helperRegisteredMethodIdentityMatched']);self.assertFalse(self.report['nativeDefaultComparerGetterBodyImplemented']);self.assertFalse(self.report['comparerLastIndexOfVirtualBodyImplemented'])
 def test_joint_removal_and_actual_equality_adapter_keep_world_and_client_boundaries(self):
  for k,v in event_single_array_search.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualMulticastRemovalAndDelegateComparisonJointlyTested']);self.assertFalse(self.report['fullClrGenericArrayRuntimeInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
