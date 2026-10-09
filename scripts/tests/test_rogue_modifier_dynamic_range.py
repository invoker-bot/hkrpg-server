"""核对范围事件、临时帧、重入计数和共享变化参数。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_dynamic_range',ROOT/'scripts/read-rogue-modifier-dynamic-range.py')
modifier_dynamic_range=importlib.util.module_from_spec(spec);spec.loader.exec_module(modifier_dynamic_range)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in modifier_dynamic_range.load_profile()['nativeMethods'] if m['index']==index for i in m.get('instructions',[])}

class ModifierDynamicRangeTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=modifier_dynamic_range.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-dynamic-range-python-evidence.json'))
 def test_complete_methods_fields_generic_arguments_and_enum_bytes_match(self):
  self.assertEqual((4,4052,8,2,2,4),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedCallbackEnumBytes']))
 def test_found_range_pushes_modifier_key_type_three_before_delta(self):
  r=rows(510245);self.assertEqual(('mov','qword ptr [rax + rdx + 0x20], rsi'),r['0xe80cefc']);self.assertEqual(('mov','dword ptr [rax + rdx + 0x28], edi'),r['0xe80cf01']);self.assertEqual(('mov','byte ptr [rax + rdx + 0x2c], 3'),r['0xe80cf05']);self.assertEqual(('call','0x19dccb2c0'),r['0xe80cf80']);self.assertEqual(('call','0x18e8147a0'),r['0xe80d076'])
 def test_absent_previous_zero_is_second_half_of_actual_initializer(self):
  p=modifier_dynamic_range.load_profile();zero=p['zeroDeltaInitializer'];self.assertEqual(('0x9343d30','0x9343d28',8,0),(zero['slotRva'],zero['writeSlotRva'],zero['sourceOffset'],zero['raw']));self.assertEqual(bytes(8),bytes.fromhex(zero['sourceBytesHex'])[8:]);self.assertEqual(('mov','rdi, qword ptr [rip - 0x54c92f9]'),rows(510245)['0xe80d022'])
 def test_range_state_commits_before_enter_and_signed_byte_counter_restores(self):
  r=rows(510251);self.assertEqual(('mov','byte ptr [r13 + 0x33], al'),r['0xe814b60']);self.assertEqual(('movsx','edx, cl'),r['0xe814829']);self.assertEqual(('mov','byte ptr [r13 + 0x30], al'),r['0xe814b93']);self.assertEqual(('call','0x18b1ea7f0'),r['0xe814bae']);self.assertEqual(('dec','byte ptr [r13 + 0x30]'),r['0xe814bb3'])
 def test_shared_callback_parameters_have_actual_and_supplied_difference(self):
  r=rows(510251);self.assertEqual(('call','0x19dccb2c0'),r['0xe814da8']);self.assertEqual(('mov','qword ptr [rax + 0x20], rbp'),r['0xe814de0']);self.assertEqual(('mov','qword ptr [rax + 0x28], rcx'),r['0xe814e10']);self.assertEqual(('mov','dword ptr [rsi + 0xa0], ebp'),r['0xe814e42']);self.assertEqual(('mov','qword ptr [rsp + 0x78], rsi'),r['0xe814e48'])
 def test_dictionary_scope_tuple_entry_and_callback_frame_types_are_exact(self):
  p=modifier_dynamic_range.load_profile();dictionary=p['genericFields'][0]['record'];self.assertEqual(('Dictionary`2','ValueTuple`2','List`1'),(dictionary['genericName'],dictionary['args'][0]['genericName'],dictionary['args'][1]['genericName']));self.assertEqual([10875,24757],[a['payload'] for a in dictionary['args'][0]['args']]);self.assertEqual(55841,dictionary['args'][1]['args'][0]['payload']);self.assertEqual(54671,p['genericFields'][1]['record']['args'][0]['payload']);self.assertEqual([0,1,2,3],[f['value'] for f in p['callbackEnumBytes']])
 def test_finite_dispatch_event_order_parameters_and_root_binding_are_implemented(self):
  for k in ['finiteDynamicRangeDispatchImplemented','hashScopeDictionarySelectionImplemented','rangeEnterExitAndChangeOrderImplemented','rangeConfigAndContextRereadsImplemented','signedByteReentryLimitImplemented','sharedCallbackParametersImplemented','currentFrameAndPoolCleanupImplemented','casterRootNotificationIntegrationImplemented']:self.assertIs(True,self.report[k],k)
 def test_native_factories_executor_mixed_arithmetic_and_online_world_stay_unverified(self):
  for k in ['fullNativeDynamicRangeRuntimeImplemented','nativeRangeEntryFactoryInstalled','nativeTaskContextFactoryInstalled','nativeSequenceExecutorBodyInstalled','nativeCallbackParameterPoolFactoryInstalled','nativeGlobalRecursionConfigExtractionInstalled','extendedRangeArithmeticImplemented','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
