"""核对叠加事件层数、参数帧、执行版本和未接通的处理体。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_stack_event',ROOT/'scripts/read-rogue-modifier-stack-event.py')
stack_event=importlib.util.module_from_spec(spec);spec.loader.exec_module(stack_event)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in stack_event.load_profile()['nativeMethods'] for i in m['instructions']}

class ModifierStackEventTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=stack_event.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-stack-event-python-evidence.json'))
 def test_native_method_fields_bindings_and_stack_arguments_match(self):
  self.assertEqual((1,1578,5,10,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericStackArguments']))
 def test_parameter_push_precedes_capture_of_previous_layer(self):
  r=rows();self.assertEqual(('call','0x198692760'),r['0xe80d5c7']);self.assertEqual(('movsxd','r14, dword ptr [rsi + 0x274]'),r['0xe80d5cc']);self.assertEqual(('mov','qword ptr [rdi + 0x10], rax'),r['0xe80d5a9']);self.assertEqual(('call','0x1986926d0'),r['0xe80d8dd'])
 def test_addition_wraps_int32_but_difference_uses_widened_layer(self):
  r=rows();self.assertEqual(('add','ebx, r14d'),r['0xe80d5eb']);self.assertEqual(('movsxd','rax, dword ptr [rsi + 0x274]'),r['0xe80d664']);self.assertEqual(('sub','rax, r14'),r['0xe80d66b']);self.assertEqual(('cmp','rcx, 0x40000000'),r['0xe80d687']);self.assertEqual(('mov','qword ptr [rdi + 0x18], rax'),r['0xe80d692'])
 def test_maximum_wrap_floor_and_upper_only_clamp_match(self):
  r=rows();self.assertEqual(('add','eax, dword ptr [rsi + 0x290]'),r['0xe80d629']);self.assertEqual(('mov','ebx, 1'),r['0xe80d632']);self.assertEqual(('cmovge','ebx, eax'),r['0xe80d637']);self.assertEqual(('cmovl','ebx, ebp'),r['0xe80d63c'])
 def test_version_reset_reads_current_version_after_event(self):
  r=rows();self.assertEqual(('inc','eax'),r['0xe80d6f5']);self.assertEqual(('mov','dword ptr [rsi + 0x2a0], eax'),r['0xe80d6fd']);self.assertEqual(('mov','edx, 0x35'),r['0xe80d71f']);self.assertEqual(('cmp','dword ptr [rsi + 0x2a0], 1'),r['0xe80d72f']);self.assertEqual(('mov','dword ptr [rsi + 0x29c], 0'),r['0xe80d738']);self.assertEqual(('mov','dword ptr [rsi + 0x2a0], edi'),r['0xe80d744'])
 def test_silence_and_shelved_count_control_distinct_event_paths(self):
  r=rows();self.assertEqual(('cmp','byte ptr [rsi + 0x81], 0'),r['0xe80d74a']);self.assertEqual(('jne','0x18e80d8c6'),r['0xe80d751']);self.assertEqual(('cmp','dword ptr [rsi + 0x278], 0'),r['0xe80d8e2']);self.assertEqual(('jle','0x18e80d8ff'),r['0xe80d8e9']);self.assertEqual(('jmp','0x18e80e960'),r['0xe80d8fa'])
 def test_generic_stack_and_on_stack_event_identity_are_exact(self):
  p=stack_event.load_profile();self.assertEqual([22682,22681],[f['methodSpec']['methodDefinition'] for f in p['genericMethodSlots']]);self.assertTrue(all(f['methodSpec']['argumentIndexAt4']==-1 for f in p['genericMethodSlots']));self.assertTrue(all(f['instRecords'][0]['arguments'][0]['payload']==55840 for f in p['genericMethodSlots']));t=next(t for t in p['matchedTypes'] if t['typeIndex']==18238);self.assertEqual(53,next(f for f in t['selectedFields'] if f['name']=='OnStack')['constant']['value']);self.assertTrue(all(self.report[k] for k in ['finiteStackEventOrderImplemented','sharedReplacementLayerStateImplemented','signedLayerAddAndClampOrderImplemented','widenedLayerDifferenceImplemented','stackParameterFrameOrderImplemented','nestedEventVersionOrderImplemented','currentConfigAndSilenceRereadsImplemented','exceptionPrefixStatePreserved']))
 def test_real_property_bp_decorators_dispatchers_and_world_are_not_claimed(self):
  for k in ['fullNativeOnStackRuntimeImplemented','nativePropertyUnstackBodiesInstalled','nativeAdditionalBpBodiesInstalled','nativeBoostPointDecoratorsInstalled','nativeBehaviorEventDispatcherInstalled','nativeOwnerStackEventBodiesInstalled','nativeParameterStackFactoryInstalled','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
