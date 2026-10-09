"""核对事件分发的真实接口、队列泛型、双查询与回调失败顺序。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('event_fire',ROOT/'scripts/read-rogue-event-fire.py');event_fire=importlib.util.module_from_spec(spec);spec.loader.exec_module(event_fire)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in event_fire.load_profile()['nativeMethods'] for i in m['instructions']}
class EventFireTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=event_fire.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-event-fire-python-evidence.json'))
 def test_complete_bodies_fields_templates_interfaces_specs_match(self):
  self.assertEqual((3,1330,0,0,3,998,3,9,2,2,1,15,7),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs']))
 def test_interface_source_then_type_queries_and_exact_packed_key(self):
  r=rows();self.assertEqual(('call','rsi'),r['0xe66cdcf']);self.assertEqual(('mov','ebx, eax'),r['0xe66cdd1']);self.assertEqual(('call','rsi'),r['0xe66ce64']);self.assertEqual(('shl','rax, 0x20'),r['0xe66ce73']);self.assertEqual(('or','rsi, rax'),r['0xe66ce79']);p=event_fire.load_profile();self.assertEqual((56693,'EKFMIDBJDIA'),(p['payloadTypeSlots'][0]['typeIndex'],p['payloadTypeSlots'][0]['typeName']))
 def test_presence_query_then_current_map_and_second_find(self):
  r=rows();self.assertEqual(('mov','rcx, qword ptr [r14 + 0x18]'),r['0xe66ce66']);self.assertEqual(('call','0x19ac73140'),r['0xe66ce7f']);self.assertEqual(('mov','rbx, qword ptr [r14 + 0x18]'),r['0xe66ce8c']);self.assertEqual(('call','0x19ac73140'),r['0xe66ce9f']);self.assertEqual(('mov','rcx, qword ptr [rbx + 0x18]'),r['0xe66ceac']);self.assertEqual(('mov','rsi, qword ptr [rcx + rax*8 + 0x30]'),r['0xe66cec8'])
 def test_execute_queue_is_fifo_with_nested_pooled_callback_list(self):
  p=event_fire.load_profile();q=p['genericFields'][0]['record'];self.assertEqual((3016,'Queue`1',5111,'PooledList`1',56692,'OnEventCallBack'),(q['genericDefinition'],q['genericName'],q['args'][0]['genericDefinition'],q['args'][0]['genericName'],q['args'][0]['args'][0]['payload'],q['args'][0]['args'][0]['name']));names={m['index']:m['name'] for m in p['boundMethodIdentities']};self.assertEqual('Dequeue',names[22298]);self.assertEqual('Enqueue',names[22294]);r=rows();self.assertEqual(('cmp','dword ptr [rcx + 0x24], 0'),r['0xe66cee7'])
 def test_copy_count_then_two_item_reads_and_raw_callback_invoke(self):
  r=rows();self.assertEqual(('call','0x19a98fd50'),r['0xe66cf16']);self.assertEqual(('call','0x19a98f0b0'),r['0xe66cf25']);self.assertEqual(('call','0x18b2f2d00'),r['0xe66cf61']);self.assertEqual(('call','0x18b2f2d00'),r['0xe66cf77']);self.assertEqual(('call','qword ptr [rax + 0x18]'),r['0xe66cf90']);self.assertEqual(('call','0x19a98f0b0'),r['0xe66cf4c'])
 def test_successful_execution_releases_true_before_current_queue_enqueue(self):
  r=rows();self.assertEqual(('mov','dl, 1'),r['0xe66cfac']);self.assertEqual(('call','0x19a994860'),r['0xe66cfae']);self.assertEqual(('mov','rcx, qword ptr [r14 + 0x10]'),r['0xe66cfb3']);self.assertEqual(('call','0x1984e9ce0'),r['0xe66cfca']);p=event_fire.load_profile();m=next(m for m in p['boundMethodIdentities'] if m['index']==35360);self.assertEqual('Release',m['name']);self.assertFalse(self.report['pooledListReleaseTrueFlagMeaningIndependentlyMatched'])
 def test_standalone_acquire_constructor_and_release_prefixes_match(self):
  r=rows();self.assertEqual(('jmp','0x1984e9ed0'),r['0xe66d3ec']);self.assertEqual(('call','0x19a98d690'),r['0xe66d40a']);self.assertEqual(('test','rsi, rsi'),r['0xe66d465']);self.assertEqual(('mov','dl, 1'),r['0xe66d474']);self.assertEqual(('call','0x19a994860'),r['0xe66d476']);self.assertEqual(('mov','rcx, qword ptr [rdi + 0x10]'),r['0xe66d47b']);self.assertEqual(('jmp','0x1984e9ce0'),r['0xe66d494'])
 def test_joint_dictionary_callback_and_recursive_copy_do_not_claim_real_world(self):
  for k,v in event_fire.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualDictionaryAndOtherFadeCallbackJointlyTested']);self.assertTrue(self.report['recursiveFireUsesSeparateExecutionListsJointlyTested']);self.assertFalse(self.report['closedQueuePooledListPrimitiveBodiesImplemented']);self.assertFalse(self.report['globalEventDispatcherInstalledInWorld']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
