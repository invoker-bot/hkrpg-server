"""核对事件载荷构造、清空顺序、静态None与数值零值。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('event_payload',ROOT/'scripts/read-rogue-event-payload.py')
event_payload=importlib.util.module_from_spec(spec);spec.loader.exec_module(event_payload)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in event_payload.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class EventPayloadTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=event_payload.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-event-payload-python-evidence.json'))
 def test_complete_methods_provider_fields_generics_and_default_literals_match(self):
  self.assertEqual((2,723,1,1513,4,2,1,1,16),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedFieldTypes','matchedGenericFieldArguments','matchedNoneStaticLayoutRecords','matchedNoneTypeUsageSlots','matchedZeroInitializerLiteralBytes']))
 def test_constructor_stores_entity_list_then_none_then_two_three_slot_arrays(self):
  r=rows(520498);self.assertEqual(('mov','qword ptr [rsi + 0x20], rdi'),r['0xb82f397']);self.assertEqual(('mov','rax, qword ptr [rax + 0xd4b0]'),r['0xb82f3b2']);self.assertEqual(('mov','qword ptr [rsi + 0x98], rax'),r['0xb82f3b9']);self.assertEqual(('mov','edx, 3'),r['0xb82f3c7']);self.assertEqual(('mov','qword ptr [rsi + 0x60], rax'),r['0xb82f3d1']);self.assertEqual(('mov','edx, 3'),r['0xb82f3dc']);self.assertEqual(('mov','qword ptr [rsi + 0x58], rax'),r['0xb82f3e6'])
 def test_early_reference_clear_precedes_entity_version_zero_count_and_storage_clear(self):
  r=rows(520525);self.assertEqual(('mov','qword ptr [rsi + 0x40], 0'),r['0xb82f137']);self.assertEqual(('mov','qword ptr [rsi + 0x10], 0'),r['0xb82f13f']);self.assertEqual(('mov','qword ptr [rsi + 0x50], 0'),r['0xb82f147']);self.assertEqual(('movups','xmmword ptr [rsi + 0x88], xmm0'),r['0xb82f152']);self.assertEqual(('inc','dword ptr [rax + 0x1c]'),r['0xb82f166']);self.assertEqual(('mov','dword ptr [rax + 0x18], 0'),r['0xb82f16d']);self.assertEqual(('jle','0x18b82f184'),r['0xb82f177']);self.assertEqual(('call','0x19c074b80'),r['0xb82f17f'])
 def test_numeric_and_string_arrays_reread_current_fields_and_clear_three_slots_only(self):
  r=rows(520525)
  for address in ['0xb82f1e1','0xb82f203','0xb82f228']:self.assertEqual(('mov','rax, qword ptr [rsi + 0x60]'),r[address])
  for address in ['0xb82f24a','0xb82f269','0xb82f28b']:self.assertEqual(('mov','rax, qword ptr [rsi + 0x58]'),r[address])
  for address,operand in [('0xb82f261','qword ptr [rax + 0x20], 0'),('0xb82f283','qword ptr [rax + 0x28], 0'),('0xb82f29e','qword ptr [rax + 0x30], 0')]:self.assertEqual(('mov',operand),r[address])
 def test_context_clears_all_buckets_then_count_free_vector_then_entry_prefix_then_version(self):
  r=rows(520525);self.assertEqual(('mov','edi, dword ptr [rbx + 0x20]'),r['0xb82f2bc']);self.assertEqual(('call','0x19c074b80'),r['0xb82f2d6']);self.assertEqual(('mov','dword ptr [rbx + 0x20], 0'),r['0xb82f2db']);self.assertEqual(('mov','eax, 0xffffffff'),r['0xb82f2e2']);self.assertEqual(('mov','qword ptr [rbx + 0x28], rax'),r['0xb82f2e7']);self.assertEqual(('mov','r8d, edi'),r['0xb82f2f1']);self.assertEqual(('call','0x19c074b80'),r['0xb82f2f4']);self.assertEqual(('inc','dword ptr [rbx + 0x24]'),r['0xb82f2f9'])
 def test_nonpositive_dictionary_count_skips_reset_and_tail_clear_waits_for_context_success(self):
  r=rows(520525);self.assertEqual(('jle','0x18b82f2f9'),r['0xb82f2c1']);self.assertEqual(('je','0x18b82f2fc'),r['0xb82f2ad']);self.assertEqual(('mov','qword ptr [rsi + 0x18], 0'),r['0xb82f2fc']);self.assertEqual(('mov','qword ptr [rsi + 0x70], 0'),r['0xb82f304']);self.assertEqual(('mov','qword ptr [rsi + 0x30], 0'),r['0xb82f30c'])
 def test_generic_context_values_are_object_references_and_none_layout_keeps_raw_static_tag(self):
  p=event_payload.load_profile();self.assertEqual([('List`1',[(18,'GameEntity')]),('Dictionary`2',[(17,'StringHash'),(18,'FFKBNBIMMHE')])],[(f['record']['genericName'],[(a['kind'],a['name']) for a in f['record']['args']]) for f in p['genericFields']]);fact=p['noneStaticLayout']['fields'][0];self.assertEqual(('None',6,'0x400d4b0'),(fact['field']['name'],fact['ordinal'],fact['rawOffset']));self.assertEqual(('JsonEnumStatic',10870),(p['noneTypeSlot']['typeName'],p['noneTypeSlot']['typeIndex']));self.assertEqual(0,p['zeroInitializer']['raw'])
 def test_finite_payload_factory_and_clear_available_without_pool_world_or_client_claims(self):
  p=event_payload.load_profile()
  for key,value in p.items():
   if isinstance(value,bool):self.assertIs(value,self.report[key],key)
  self.assertIs(True,self.report['sameTypedPayloadThroughActualDepartedTailIntegrated']);self.assertIs(False,self.report['completeNativeEventPoolAcquireReleaseInstalled']);self.assertIs(False,self.report['clientGameplayVerified']);self.assertIs(False,self.report['clientModified'])

if __name__=='__main__':unittest.main()
