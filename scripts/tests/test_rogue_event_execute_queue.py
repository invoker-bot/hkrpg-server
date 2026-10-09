"""核对FIFO环形队列、增长控制、版本更新及泛型来源边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('event_execute_queue',ROOT/'scripts/read-rogue-event-execute-queue.py');event_execute_queue=importlib.util.module_from_spec(spec);spec.loader.exec_module(event_execute_queue)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in event_execute_queue.load_profile()['runtimeMethods'] for i in m['instructions']}
class EventExecuteQueueTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=event_execute_queue.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-event-execute-queue-python-evidence.json'))
 def test_complete_helpers_auxiliary_fields_templates_and_specs_match(self):
  self.assertEqual((0,0,3,588,3,1330,1,3,1,1,0,2,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs']))
 def test_dequeue_zero_only_guard_precedes_storage_head_and_clear(self):
  r=rows();self.assertEqual(('mov','r8d, dword ptr [rcx + 0x24]'),r['0x184e9ed5']);self.assertEqual(('test','r8d, r8d'),r['0x184e9ed9']);self.assertEqual(('je','0x1984e9f34'),r['0x184e9edc']);self.assertEqual(('movsxd','rsi, dword ptr [rcx + 0x2c]'),r['0x184e9ee7']);self.assertEqual(('mov','qword ptr [rdx + rsi*8 + 0x20], 0'),r['0x184e9ef5'])
 def test_dequeue_current_storage_head_wrap_and_captured_count_decrement(self):
  r=rows();self.assertEqual(('mov','r9, qword ptr [rcx + 0x10]'),r['0x184e9efe']);self.assertEqual(('cmovne','edx, esi'),r['0x184e9f0f']);self.assertEqual(('mov','dword ptr [rcx + 0x2c], edx'),r['0x184e9f12']);self.assertEqual(('dec','r8d'),r['0x184e9f15']);self.assertEqual(('inc','dword ptr [rcx + 0x28]'),r['0x184e9f1c'])
 def test_enqueue_growth_only_when_count_equals_capacity_with_signed_maximum(self):
  r=rows();self.assertEqual(('cmp','ebx, edx'),r['0x184e9cfc']);self.assertEqual(('jne','0x1984e9d31'),r['0x184e9cfe']);self.assertEqual(('lea','eax, [rbx + rbx]'),r['0x184e9d00']);self.assertEqual(('add','ebx, 4'),r['0x184e9d03']);self.assertEqual(('cmovle','ebx, eax'),r['0x184e9d08']);self.assertEqual(('call','0x1984ea220'),r['0x184e9d20'])
 def test_enqueue_store_then_tail_wrap_then_simultaneous_current_count_version_increment(self):
  r=rows();self.assertEqual(('movsxd','rcx, dword ptr [rsi + 0x20]'),r['0x184e9d31']);self.assertEqual(('mov','qword ptr [rax + rcx*8 + 0x20], rdi'),r['0x184e9d39']);self.assertEqual(('mov','rax, qword ptr [rsi + 0x10]'),r['0x184e9d3e']);self.assertEqual(('mov','dword ptr [rsi + 0x20], edx'),r['0x184e9d51']);self.assertEqual(('psubd','xmm0, xmm1'),r['0x184e9d5d']);self.assertEqual(('movq','qword ptr [rsi + 0x24], xmm0'),r['0x184e9d61'])
 def test_capacity_allocation_then_wrapped_copy_rereads_current_storage_head_tail(self):
  r=rows();self.assertEqual(('call','0x183d1ad70'),r['0x184ea252']);self.assertEqual(('mov','eax, dword ptr [rsi + 0x24]'),r['0x184ea25a']);self.assertEqual(('call','0x19c075050'),r['0x184ea291']);self.assertEqual(('mov','rcx, qword ptr [rsi + 0x10]'),r['0x184ea296']);self.assertEqual(('mov','eax, dword ptr [rsi + 0x20]'),r['0x184ea29f']);self.assertEqual(('sub','r9d, dword ptr [rsi + 0x2c]'),r['0x184ea2a6']);self.assertEqual(('call','0x19c075050'),r['0x184ea2b3'])
 def test_capacity_publishes_array_head_current_count_tail_then_version(self):
  r=rows();self.assertEqual(('mov','qword ptr [rsi + 0x10], rbx'),r['0x184ea2b8']);self.assertEqual(('mov','dword ptr [rsi + 0x2c], 0'),r['0x184ea2bc']);self.assertEqual(('mov','eax, dword ptr [rsi + 0x24]'),r['0x184ea2c3']);self.assertEqual(('cmovne','ecx, eax'),r['0x184ea2ca']);self.assertEqual(('inc','dword ptr [rsi + 0x28]'),r['0x184ea2d0'])
 def test_closed_queue_nested_arguments_and_actual_fire_keep_world_and_layout_limits(self):
  p=event_execute_queue.load_profile();q=p['genericFields'][0]['record'];self.assertEqual((3016,5111,56692),(q['genericDefinition'],q['args'][0]['genericDefinition'],q['args'][0]['args'][0]['payload']));self.assertEqual({22294,22298},{s['methodSpec']['methodDefinition'] for s in p['poolMethodSpecs']})
  for k,v in p.items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualFireAcquireAndReleaseUseActualQueueJointlyTested']);self.assertFalse(self.report['queueTemplateOffsetsUsedAsClosedPhysicalLayout']);self.assertFalse(self.report['nativeTypedArrayFactoryInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
