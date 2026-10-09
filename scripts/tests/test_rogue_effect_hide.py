"""核对效果隐藏原件、组件泛型身份、延迟回调与原始常量。"""
import importlib.util,unittest
from pathlib import Path
from types import SimpleNamespace
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('effect_hide',ROOT/'scripts/read-rogue-effect-hide.py');effect_hide=importlib.util.module_from_spec(spec);spec.loader.exec_module(effect_hide)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in effect_hide.load_profile()['nativeMethods'] for i in m['instructions']}
class EffectHideTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=effect_hide.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-effect-hide-python-evidence.json'))
 def test_complete_bodies_fields_binding_slots_and_literal_match(self):
  self.assertEqual((3,1292,0,0,1,263,10,3,0,0,2,18,1,1,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs','matchedMethodInfoSlots','matchedRawFloatLiterals']))
 def test_setup_validity_signed_state_hide_flags_and_only_sole_reason_reactivation(self):
  r=rows()
  for address,value in [('0xe80435a',('mov','eax, dword ptr [rsi]')),('0xe80435c',('cmp','eax, dword ptr [rbx + 0x20]')),('0xe804373',('cmp','dword ptr [rax + 0x128], 5')),('0xe80437a',('jle','0x18e804417')),('0xe804420',('cmp','byte ptr [r14 + 0x283], 0')),('0xe80442a',('cmp','byte ptr [r14 + 0x2e1], 0')),('0xe80444a',('or','eax, 0x80')),('0xe804456',('and','ecx, 0xffffff7f')),('0xe80445f',('cmp','eax, 0x80'))]:self.assertEqual(value,r[address])
 def test_closure_allocation_precedes_flags_and_sixteen_byte_instance_snapshot(self):
  r=rows()
  for address,value in [('0xe805355',('call','0x183d3a130')),('0xe805366',('mov','qword ptr [rax + 0x10], rsi')),('0xe80536a',('cmp','byte ptr [rsi + 0x2e2], 0')),('0xe80537c',('cmp','byte ptr [rdi + 0x58], 0')),('0xe805389',('movups','xmm0, xmmword ptr [rdi + 0x20]')),('0xe80538d',('movups','xmmword ptr [rbx + 0x18], xmm0')),('0xe8053c7',('cmp','dword ptr [rax + 0x128], 4'))]:self.assertEqual(value,r[address])
 def test_component_generic_is_get_component_fade_plugin_and_timer_field_is_bound_type(self):
  p=effect_hide.load_profile();m=next(m for m in p['boundMethodIdentities'] if m['index']==31409);self.assertEqual(('Component','GetComponent',4320),(m['typeName'],m['name'],m['typeIndex']))
  spec=p['poolMethodSpecs'][0];self.assertEqual({'methodDefinition':31409,'classInst':78325,'methodInst':-1},spec['methodSpec']);self.assertEqual((18,70059,'MonoEffectPluginFade'),tuple(spec['instRecords'][0]['arguments'][0][k] for k in ['kind','payload','name']))
  field=next(f for t in p['matchedTypes'] if t['typeIndex']==70059 for f in t['selectedInstanceFields'] if f['instanceOffset']==184);self.assertEqual(('_Behavior',69875),(field['name'],field['nativeType']['typeIndex']))
 def test_lookup_precedes_wait_and_current_instance_is_reread(self):
  r=rows()
  for address,value in [('0xe8054b8',('call','qword ptr [rip - 0x54c967e]')),('0xe8054be',('mov','rax, qword ptr [rbx + 0x20]')),('0xe8054c2',('test','bpl, bpl')),('0xe8054e1',('cmp','dword ptr [rcx + 0x128], 3')),('0xe8054fb',('mov','rsi, qword ptr [rcx + 0xb8]')),('0xe805509',('call','0x183d3a130')),('0xe805538',('test','rsi, rsi'))]:self.assertEqual(value,r[address])
 def test_delegate_initialization_and_raw_schedule_arguments(self):
  p=effect_hide.load_profile();r=rows();self.assertEqual(510354,p['methodInfoSlots'][0]['method']['index']);self.assertEqual('000080bf',p['rawFloatLiterals'][0]['bytesHex']);self.assertEqual('0xbf800000',p['rawFloatLiterals'][0]['rawBits'])
  for address,value in [('0xe805519',('mov','qword ptr [rax + 0x10], rdx')),('0xe80551d',('mov','qword ptr [rax + 0x28], rcx')),('0xe805521',('mov','qword ptr [rax + 0x20], rbx')),('0xe805529',('mov','qword ptr [rax + 0x40], rbx')),('0xe805541',('mov','byte ptr [rsp + 0x20], 0')),('0xe805554',('xor','r9d, r9d')),('0xe805557',('call','0x18c4afbe0'))]:self.assertEqual(value,r[address])
 def test_callback_checks_active_list_then_current_behavior_without_instance_validity(self):
  r=rows()
  for address,value in [('0xe856245',('mov','rax, qword ptr [rcx + 0x10]')),('0xe85624e',('cmp','qword ptr [rax + 0x50], 0')),('0xe856255',('mov','rax, qword ptr [rcx + 0x20]')),('0xe85625e',('mov','rsi, qword ptr [rax + 0x100]')),('0xe856273',('or','byte ptr [rsi + 0x69], 2')),('0xe85627a',('xor','edx, edx')),('0xe856281',('jmp','0x18d79a410'))]:self.assertEqual(value,r[address])
 def test_joint_source_projection_keeps_actual_world_and_abi_limits_explicit(self):
  p=effect_hide.load_profile()
  for k,v in p.items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualAsyncCompletionAndTwoHideBodiesJointlyTested']);self.assertFalse(self.report['nativeUnityComponentLookupInstalled']);self.assertFalse(self.report['nativeFadeBehaviorScheduleBodyImplemented']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
