"""核对淡出调度、原始回调顺序、跟随组件、插值与音效通知。"""
import importlib.util,unittest
from pathlib import Path
from types import SimpleNamespace
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('effect_timer',ROOT/'scripts/read-rogue-effect-timer.py');effect_timer=importlib.util.module_from_spec(spec);spec.loader.exec_module(effect_timer)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in effect_timer.load_profile()['nativeMethods'] for i in m['instructions']}
class EffectTimerTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=effect_timer.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-effect-timer-python-evidence.json'))
 def test_complete_body_fields_bindings_and_generic_follow_record_match(self):
  self.assertEqual((1,931,0,0,5,2009,6,7,0,0,1,34,1,0,0),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs','matchedMethodInfoSlots','matchedRawFloatLiterals']))
 def test_low_byte_flags_precede_owner_and_disabled_branch_directly_invokes_incoming(self):
  r=rows()
  for address,value in [('0xc4afc0e',('mov','byte ptr [rsi + 0x49], bl')),('0xc4afc11',('mov','byte ptr [rsi + 0x48], 0')),('0xc4afc15',('mov','rcx, qword ptr [rsi + 0x10]')),('0xc4afc53',('cmp','byte ptr [rcx + 0x20], 0')),('0xc4afc5d',('cmp','qword ptr [rsi + 0x18], 0')),('0xc4afd83',('test','rdi, rdi')),('0xc4afd8c',('mov','rax, qword ptr [rdi + 0x18]')),('0xc4afda5',('jmp','rax'))]:self.assertEqual(value,r[address])
 def test_previous_callback_precedes_incoming_publication_check_and_tail_fallback(self):
  r=rows()
  for address,value in [('0xc4afc68',('mov','rax, qword ptr [rsi + 0x30]')),('0xc4afc79',('call','qword ptr [rax + 0x18]')),('0xc4afc7c',('mov','qword ptr [rsi + 0x30], rdi')),('0xc4afc83',('call','0x18c4aff90')),('0xc4afc8a',('je','0x18c4afda8')),('0xc4afdb8',('jmp','0x18c4b0170'))]:self.assertEqual(value,r[address])
 def test_stop_follow_component_identity_and_field_are_independently_matched(self):
  p=effect_timer.load_profile();s=p['poolMethodSpecs'][0];self.assertEqual({'methodDefinition':31409,'classInst':78383,'methodInst':-1},s['methodSpec']);a=s['instRecords'][0]['arguments'][0];self.assertEqual((18,70066,'MonoEffectPluginFollow'),tuple(a[k] for k in ['kind','payload','name']));f=next(f for t in p['matchedTypes'] if t['typeIndex']==70066 for f in t['selectedInstanceFields'] if f['instanceOffset']==158);self.assertEqual('StopFollow',f['name']);self.assertEqual(('mov','byte ptr [rax + 0x9e], 1'),rows()['0xc4afd29'])
 def test_current_owner_raw_lerp_one_zero_and_other_branches(self):
  r=rows()
  for address,value in [('0xc4afd30',('mov','rcx, qword ptr [rsi + 0x10]')),('0xc4afd6e',('mov','eax, dword ptr [rcx + 0x6c]')),('0xc4afd71',('cmp','eax, 1')),('0xc4afd79',('movaps','xmm1, xmm6')),('0xc4afd7c',('call','0x18c4b0250')),('0xc4afdbd',('test','eax, eax')),('0xc4afdbf',('jne','0x18c4afdcf')),('0xc4afdc4',('movaps','xmm1, xmm6')),('0xc4afdc7',('mov','r8d, ebp')),('0xc4afdca',('call','0x18c4b0410'))]:self.assertEqual(value,r[address])
 def test_audio_optional_string_and_effect_capture_precede_game_object_and_audio(self):
  r=rows()
  for address,value in [('0xc4afe0e',('mov','rdi, qword ptr [rcx + 0x88]')),('0xc4afe1e',('cmp','dword ptr [rdi + 0x10], 0')),('0xc4afe50',('mov','rbp, qword ptr [rcx + 0x18]')),('0xc4afe59',('mov','rbx, qword ptr [rbp + 0x100]')),('0xc4afe93',('mov','rcx, rbx')),('0xc4afe96',('mov','rdx, rdi')),('0xc4afe99',('mov','r8, rax')),('0xc4afe9c',('call','0x18d79a780')),('0xc4afea1',('mov','rcx, qword ptr [rsi + 0x10]'))]:self.assertEqual(value,r[address])
 def test_final_owner_effect_and_behavior_are_required_before_on_fade_out(self):
  r=rows();self.assertEqual(('mov','rax, qword ptr [rcx + 0x18]'),r['0xc4afedc']);self.assertEqual(('test','rax, rax'),r['0xc4afee0']);self.assertEqual(('mov','rcx, qword ptr [rax + 0x100]'),r['0xc4afee5']);self.assertEqual(('test','rcx, rcx'),r['0xc4afeec']);self.assertEqual(('jmp','0x18d790840'),r['0xc4afefe'])
 def test_actual_waiting_hide_joint_check_keeps_interpolation_audio_delegate_and_world_limits(self):
  p=effect_timer.load_profile()
  for k,v in p.items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualWaitingSkillHideAndSchedulingAndDelayedHideJointlyTested']);self.assertFalse(self.report['nativeFadeInterpolationBodiesImplemented']);self.assertFalse(self.report['nativeEffectAudioBodyImplemented']);self.assertFalse(self.report['nativeCallbackDelegateAbiInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
