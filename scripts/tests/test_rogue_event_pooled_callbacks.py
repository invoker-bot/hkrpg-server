"""核对池化列表构造分支与内处理器转发的五份完整原件。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('event_pooled_callbacks',ROOT/'scripts/read-rogue-event-pooled-callbacks.py');event_pooled_callbacks=importlib.util.module_from_spec(spec);spec.loader.exec_module(event_pooled_callbacks)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in event_pooled_callbacks.load_profile()['runtimeMethods'] for i in m['instructions']}
class EventPooledCallbacksTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=event_pooled_callbacks.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-event-pooled-callbacks-python-evidence.json'))
 def test_full_helper_auxiliary_generic_and_spec_evidence_matches(self):
  self.assertEqual((0,0,5,1955,6,1918,1,5,2,2,0,5,5),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs']))
 def test_constructor_receiver_then_static_branch_and_two_allocators(self):
  r=rows();self.assertEqual(('test','rcx, rcx'),r['0x1a98d697']);self.assertEqual(('cmp','byte ptr [rax], 0'),r['0x1a98d6fc']);self.assertEqual(('call','0x183d3a130'),r['0x1a98d725']);self.assertEqual(('call','0x183d3a130'),r['0x1a98d785'])
 def test_constructor_initializer_precedes_processor_publication(self):
  r=rows();self.assertEqual(('call','rdi'),r['0x1a98d7a8']);self.assertEqual(('mov','qword ptr [rsi + 0x10], rbx'),r['0x1a98d7aa']);p=event_pooled_callbacks.load_profile();t=next(t for t in p['genericDefinitionDeclarations'] if t['typeIndex']==5111);self.assertEqual(['_processor','IsClass'],[f['name'] for f in t['fields']]);self.assertFalse(self.report['constructorStaticIsClassFlagSlotIndependentlyMatched'])
 def test_range_captures_processor_and_forwards_original_source_argument(self):
  r=rows();self.assertEqual(('mov','rdi, qword ptr [rcx + 0x10]'),r['0x1a98fd59']);self.assertEqual(('mov','r14, rdx'),r['0x1a98fd66']);self.assertEqual(('mov','rdx, r14'),r['0x1a98fe17']);self.assertEqual(('call','rbx'),r['0x1a98fe1a'])
 def test_count_captures_processor_before_interface_virtual_query(self):
  r=rows();self.assertEqual(('mov','rbx, qword ptr [rcx + 0x10]'),r['0x1a98f0b7']);self.assertEqual(('mov','r9d, 1'),r['0x1a98f12a']);self.assertEqual(('mov','rcx, rbx'),r['0x1a98f173']);self.assertEqual(('call','rdi'),r['0x1a98f176'])
 def test_item_and_release_forward_raw_index_and_boolean_to_captured_processor(self):
  r=rows();self.assertEqual(('mov','edx, ebp'),r['0xb2f2dc7']);self.assertEqual(('call','rbx'),r['0xb2f2dc9']);self.assertEqual(('mov','rsi, qword ptr [rcx + 0x10]'),r['0x1a994868']);self.assertEqual(('mov','r9d, 0x39'),r['0x1a9948da']);self.assertEqual(('mov','edx, ebp'),r['0x1a994927']);self.assertEqual(('call','rdi'),r['0x1a994929'])
 def test_processor_generic_template_var_is_not_closed_callback_argument(self):
  p=event_pooled_callbacks.load_profile();record=p['genericFields'][1]['record'];self.assertEqual((5095,'IPooledList`1',19,1578),(record['genericDefinition'],record['genericName'],record['args'][0]['kind'],record['args'][0]['payload']));self.assertEqual({35258,35286,35295,35301,35360},{s['methodSpec']['methodDefinition'] for s in p['poolMethodSpecs']})
 def test_joint_actual_control_preserves_pending_processor_and_world_installation(self):
  for k,v in event_pooled_callbacks.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualFirePooledWrapperAndQueueJointlyTested']);self.assertFalse(self.report['innerPooledListProcessorBodiesImplemented']);self.assertFalse(self.report['nativeIPooledListVirtualSlotsInstalled']);self.assertFalse(self.report['releaseTrueFlagMeaningIndependentlyMatched']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
