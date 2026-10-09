"""核对默认键哈希、桶链、原始索引与比较器闭包类型来源。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('event_dictionary_lookup',ROOT/'scripts/read-rogue-event-dictionary-lookup.py');event_dictionary_lookup=importlib.util.module_from_spec(spec);spec.loader.exec_module(event_dictionary_lookup)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in event_dictionary_lookup.load_profile()['nativeMethods']+event_dictionary_lookup.load_profile()['runtimeMethods'] for i in m['instructions']}
class EventDictionaryLookupTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=event_dictionary_lookup.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-event-dictionary-lookup-python-evidence.json'))
 def test_complete_key_and_lookup_sources_template_fields_and_comparer_usage_match(self):
  self.assertEqual((2,168,1,1086,0,0,3,2,1,1,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedGenericTypeUsages']))
 def test_key_hash_is_unmasked_uid_times_twenty_three_plus_event_and_seed(self):
  r=rows();self.assertEqual(('lea','edx, [rax + rax*2]'),r['0xb68eed6']);self.assertEqual(('shl','edx, 3'),r['0xb68eed9']);self.assertEqual(('sub','edx, eax'),r['0xb68eedc']);self.assertEqual(('lea','eax, [rdx + rcx]'),r['0xb68eede']);self.assertEqual(('add','eax, 0x2321'),r['0xb68eee1']);self.assertEqual(('cmp','dword ptr [rdi], esi'),r['0xb68ee75']);self.assertEqual(('cmp','dword ptr [rdi + 4], esi'),r['0xb68ee7d'])
 def test_lookup_captures_bucket_entries_comparer_and_masks_default_hash_before_modulo(self):
  r=rows();self.assertEqual(('mov','rbx, qword ptr [rcx + 0x10]'),r['0x1ac7315a']);self.assertEqual(('mov','rdi, qword ptr [rcx + 0x18]'),r['0x1ac73163']);self.assertEqual(('mov','rax, qword ptr [rcx + 0x30]'),r['0x1ac73167']);self.assertEqual(('and','r12d, 0x7fffffff'),r['0x1ac73210']);self.assertEqual(('idiv','ecx'),r['0x1ac7321f']);self.assertEqual(('dec','ebx'),r['0x1ac73238'])
 def test_default_hash_and_raw_key_compare_precede_unsigned_next_sentinel_return(self):
  r=rows();self.assertEqual(('cmp','dword ptr [rbp + rsi*8], r12d'),r['0x1ac73267']);self.assertEqual(('cmp','rcx, r15'),r['0x1ac73281']);self.assertEqual(('mov','ebx, dword ptr [rbp + rsi*8 + 4]'),r['0x1ac7329c']);self.assertEqual(('jb','0x19ac73260'),r['0x1ac732a2']);self.assertEqual(('mov','eax, ebx'),r['0x1ac734d1'])
 def test_default_minus_one_and_custom_zero_collision_counters_are_distinct(self):
  r=rows();self.assertEqual(('mov','edi, 0xffffffff'),r['0x1ac7324d']);self.assertEqual(('inc','edi'),r['0x1ac73292']);self.assertEqual(('cmp','edi, eax'),r['0x1ac73294']);self.assertEqual(('xor','r14d, r14d'),r['0x1ac73384']);self.assertEqual(('cmp','r14d, eax'),r['0x1ac7345c']);self.assertEqual(('inc','r14d'),r['0x1ac7346a'])
 def test_custom_hash_and_equals_use_captured_comparer_then_current_link(self):
  r=rows();self.assertEqual(('call','rsi'),r['0x1ac73340']);self.assertEqual(('and','r13d, 0x7fffffff'),r['0x1ac73345']);self.assertEqual(('call','r15'),r['0x1ac73440']);self.assertEqual(('mov','ebx, dword ptr [rdi + r12*8 + 4]'),r['0x1ac73465']);p=event_dictionary_lookup.load_profile();self.assertEqual(['Equals','GetHashCode'],[m['name'] for m in p['boundMethodIdentities']])
 def test_comparer_type_usage_is_generic_not_class_and_template_offsets_are_not_installed(self):
  p=event_dictionary_lookup.load_profile();row=p['genericTypeUsages'][0];self.assertEqual(('0x9447ee8',21,1564,56689),(row['slot'],row['record']['kind'],row['record']['genericDefinition'],row['record']['args'][0]['payload']));self.assertFalse(self.report['genericTemplateOffsetZeroTreatedAsPhysicalInstanceOffset']);self.assertFalse(self.report['closedGenericDictionaryRuntimeLayoutsInstalled'])
 def test_actual_lookup_withdrawal_delegate_tests_keep_generic_identity_world_and_gameplay_limits(self):
  for k,v in event_dictionary_lookup.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualManagerLookupWithdrawalAndDelegateComparisonJointlyTested']);self.assertFalse(self.report['genericFindEntryRegisteredMethodIdentityMatched']);self.assertFalse(self.report['customComparerInterfaceMethodBodiesInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
