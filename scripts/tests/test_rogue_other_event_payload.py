"""核对双载荷构造/范围初始化、实体ID字段与207/208查询完整来源。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('other_event_payload',ROOT/'scripts/read-rogue-other-event-payload.py');other_event_payload=importlib.util.module_from_spec(spec);spec.loader.exec_module(other_event_payload)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in other_event_payload.load_profile()['nativeMethods']+other_event_payload.load_profile()['runtimeMethods'] for i in m['instructions']}
class OtherEventPayloadTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=other_event_payload.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-other-event-payload-python-evidence.json'))
 def test_full_bodies_auxiliary_fields_templates_and_specs_match(self):
  self.assertEqual((8,818,1,179,4,1220,3,2,1,1,0,6,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs']))
 def test_both_constructors_allocate_initialize_then_publish_only_effect_list(self):
  r=rows();self.assertEqual(('call','0x183d3a130'),r['0xb6d31c0']);self.assertEqual(('call','0x1980b2f70'),r['0xb6d31d2']);self.assertEqual(('mov','qword ptr [rsi + 0x20], rdi'),r['0xb6d31d7']);self.assertEqual(('call','0x183d3a130'),r['0x159cb380']);self.assertEqual(('call','0x1980b2f70'),r['0x159cb392']);self.assertEqual(('mov','qword ptr [rsi + 0x20], rdi'),r['0x159cb397'])
 def test_range_source_write_then_one_version_reset_clear_and_current_list(self):
  r=rows();self.assertEqual(('mov','qword ptr [rsi + 0x28], rdi'),r['0xb6d304f']);self.assertEqual(('inc','dword ptr [rdi + 0x1c]'),r['0xb6d3060']);self.assertEqual(('mov','dword ptr [rdi + 0x18], 0'),r['0xb6d3067']);self.assertEqual(('call','0x19c074b80'),r['0xb6d3079']);self.assertEqual(('mov','rdi, qword ptr [rsi + 0x20]'),r['0xb6d307e']);self.assertEqual(('inc','dword ptr [rdi + 0x1c]'),r['0x159cb120'])
 def test_range_passes_current_index_original_source_and_writes_modifier_last(self):
  r=rows();self.assertEqual(('mov','ebp, dword ptr [rdi + 0x18]'),r['0xb6d308e']);self.assertEqual(('mov','edx, ebp'),r['0xb6d30a4']);self.assertEqual(('mov','r8, rbx'),r['0xb6d30a6']);self.assertEqual(('call','0x1980b58c0'),r['0xb6d30a9']);self.assertEqual(('mov','qword ptr [rsi + 0x18], r14'),r['0xb6d30ae']);self.assertEqual(('call','0x1980b58c0'),r['0x159cb169'])
 def test_source_query_requires_entity_and_reads_uint32_runtime_id_at192(self):
  r=rows();self.assertEqual(('mov','rax, qword ptr [rsi + 0x28]'),r['0xb6d3121']);self.assertEqual(('test','rax, rax'),r['0xb6d3125']);self.assertEqual(('mov','eax, dword ptr [rax + 0xc0]'),r['0xb6d312a']);p=other_event_payload.load_profile();f=p['matchedTypes'][2]['selectedInstanceFields'][0];self.assertEqual((192,9,'<RuntimeID>k__BackingField'),(f['instanceOffset'],f['nativeType']['kind'],f['name']))
 def test_ordinary_event_type_queries_are207208_without_receiver_reads(self):
  r=rows();self.assertEqual(('mov','eax, 0xcf'),r['0xb6d316e']);self.assertEqual(('mov','eax, 0xd0'),r['0x159cb32e']);self.assertTrue(self.report['constant207208QueriesDoNotReadNullableReceiver'])
 def test_ctor_and_insert_range_specs_preserve_template_roles_and_effect_argument(self):
  p=other_event_payload.load_profile();self.assertEqual({12691,12710},{s['methodSpec']['methodDefinition'] for s in p['poolMethodSpecs']})
  for s in p['poolMethodSpecs']:
   self.assertEqual((-1,39196),(s['methodSpec']['classInst'],s['methodSpec']['methodInst']));arg=s['instRecords'][0]['arguments'][0];self.assertEqual((54431,'ModifierEffectData'),(arg['payload'],arg['name']))
 def test_actual_payload_query_dictionary_callback_joint_check_keeps_real_world_limits(self):
  for k,v in other_event_payload.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertEqual(('mov','qword ptr [rsi + 0x10], rax'),rows()['0x180b2fc7']);self.assertTrue(self.report['ordinaryDefaultEffectListConstructorControlImplemented']);self.assertTrue(self.report['actualPayloadUsesActualDefaultListConstructorJointlyTested']);self.assertTrue(self.report['actualFireDictionaryAndOtherCallbackUseActualPayloadQueriesJointlyTested']);self.assertFalse(self.report['nativeClosedListRangeInsertBodyImplemented']);self.assertFalse(self.report['nativeSharedEmptyArrayOrClassInitializationInstalled']);self.assertFalse(self.report['constructorAllocationClassTypeSlotIndependentlyMatched']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
