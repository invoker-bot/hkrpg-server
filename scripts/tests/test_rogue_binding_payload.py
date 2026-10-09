"""核对绑定载荷的空构造入口、完整Clear和共享Empty字段来源。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('binding_payload',ROOT/'scripts/read-rogue-binding-payload.py');binding_payload=importlib.util.module_from_spec(spec);spec.loader.exec_module(binding_payload)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in binding_payload.load_profile()['nativeMethods'] for i in m['instructions']}
class BindingPayloadTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=binding_payload.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-binding-payload-python-evidence.json'))
 def test_complete_clear_provider_leaf_fields_and_empty_layout_match(self):
  self.assertEqual((1,109,1,1513,1,1,3,2,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedLeafFragments','matchedLeafFragmentBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedEmptyStaticLayoutRecords']))
 def test_registered_constructor_fragment_is_ret_without_writes(self):
  p=binding_payload.load_profile();leaf=p['leafFragments'][0];self.assertEqual((534760,'0xb8a64d0','c3'),(leaf['index'],leaf['rva'],leaf['bytesHex']));self.assertEqual([{'rva':'0xb8a64d0','mnemonic':'ret','operand':''}],leaf['instructions']);self.assertNotEqual('PE Exception Directory',leaf['bounds'])
 def test_clear_reads_shared_string_empty_before_zeroing_any_member(self):
  r=rows();self.assertEqual(('mov','rax, qword ptr [rip - 0x2562fd0]'),r['0xb8a6471']);self.assertEqual(('mov','rax, qword ptr [rax + 0x110]'),r['0xb8a6478']);self.assertEqual(('mov','qword ptr [rsi + 0x10], rax'),r['0xb8a647f']);self.assertEqual(('xorps','xmm0, xmm0'),r['0xb8a6483'])
 def test_seven_zero_vector_stores_cover_four_reference_triples_context_and_flags(self):
  r=rows()
  for address,offset in [('0xb8a6486','0x18'),('0xb8a648a','0x28'),('0xb8a648e','0x38'),('0xb8a6492','0x48'),('0xb8a6496','0x58'),('0xb8a649a','0x68'),('0xb8a649e','0x78')]:self.assertEqual(('movups','xmmword ptr [rsi + '+offset+'], xmm0'),r[address])
 def test_member_is_value_type_with_three_dynamic_float_references(self):
  p=binding_payload.load_profile();payload=p['matchedTypes'][0];member=p['matchedTypes'][1];self.assertEqual([24,48,72,96],[f['instanceOffset'] for f in payload['fields'] if f['nativeType']['kind']==17]);self.assertEqual((57754,'ADBMFDNMBIJ'),(member['typeIndex'],member['name']));self.assertEqual([(16,'DynamicFloat',18),(24,'DynamicFloat',18),(32,'DynamicFloat',18)],[(f['instanceOffset'],f['nativeType']['name'],f['nativeType']['kind']) for f in member['fields']]);self.assertFalse(p['memberReferenceTriplesHaveNativeObjectIdentity'])
 def test_payload_context_and_array_are_zeroed_without_pointee_callbacks(self):
  p=binding_payload.load_profile();f=p['matchedTypes'][0]['fields'];self.assertEqual([(120,'TaskContext'),(128,None)],[(x['instanceOffset'],x['nativeType'].get('name')) for x in f if x['instanceOffset']>=120]);self.assertEqual(29,f[-1]['nativeType']['kind']);self.assertFalse(any(i['mnemonic']=='call' for i in p['nativeMethods'][0]['instructions'] if int(i['rva'],16)<0xb8a64a8));self.assertTrue(p['existingDynamicFloatObjectsRemainUntouched'])
 def test_empty_declaration_and_raw_static_reference_tag_are_preserved(self):
  p=binding_payload.load_profile();self.assertEqual((330,'String'),(p['matchedTypes'][2]['typeIndex'],p['matchedTypes'][2]['name']));f=p['matchedTypes'][2]['selectedFields'][0];self.assertEqual(('Empty',True,14),(f['name'],f['isStatic'],f['nativeType']['kind']));self.assertEqual('0x4000110',p['emptyStaticLayout']['fields'][0]['rawOffset'])
 def test_joint_actual_pool_payload_and_withdrawal_do_not_claim_global_world_or_catalog_factory(self):
  for k,v in binding_payload.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualBindingWithdrawalPoolAndPayloadClearJointlyTested']);self.assertFalse(self.report['nativeEmptyStringCatalogFactoryInstalled']);self.assertFalse(self.report['completeBindingValueExpressionEvaluationInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
