"""核对互斥重试、效果字段与异步完成闭包完整来源。"""
import importlib.util,unittest
from pathlib import Path
from types import SimpleNamespace
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('mutex_attach',ROOT/'scripts/read-rogue-mutex-attach.py');mutex_attach=importlib.util.module_from_spec(spec);spec.loader.exec_module(mutex_attach)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in mutex_attach.load_profile()['nativeMethods'] for i in m['instructions']}
class MutexAttachTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=mutex_attach.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-mutex-attach-python-evidence.json'))
 def test_full_native_fields_generic_lists_closure_slot_and_bound_identities_match(self):
  self.assertEqual((2,1699,0,0,7,2119,10,7,2,1,1,32,2,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs','matchedMethodInfoSlots']))
 def test_initial_optional_lists_reverse_bounds_and_nonzero_reuse_skip(self):
  r=rows();self.assertEqual(('mov','rax, qword ptr [r14 + 0x70]'),r['0xe804cdd']);self.assertEqual(('cmp','dword ptr [r15 + 0x18], 0'),r['0xe804cf2']);self.assertEqual(('lea','rcx, [rdx - 1]'),r['0xe804d3d']);self.assertEqual(('mov','r12d, dword ptr [r15 + 0x18]'),r['0xe804d6a']);self.assertEqual(('cmp','dword ptr [rax + 0x48], 0'),r['0xe804fb6']);self.assertEqual(('jne','0x18e804f50'),r['0xe804fba'])
 def test_path_null_length_identity_and_bound_equality_order(self):
  r=rows();self.assertEqual(('mov','rcx, qword ptr [rax + 0x38]'),r['0xe804db0']);self.assertEqual(('mov','rdx, qword ptr [r13 + 0x20]'),r['0xe804db4']);self.assertEqual(('cmp','rcx, rdx'),r['0xe804db8']);self.assertEqual(('cmp','eax, dword ptr [rdx + 0x10]'),r['0xe804dd2']);self.assertEqual(('call','0x19c0cced0'),r['0xe804ddb'])
 def test_fade_global_entity_and_unknown_mutex_kind_branches_preserve_parameters(self):
  r=rows();self.assertEqual(('cmp','byte ptr [rsp + 0x37], 0'),r['0xe804de8']);self.assertEqual(('mov','rbx, qword ptr [rax + 0xb0]'),r['0xe804e2d']);self.assertEqual(('mov','eax, dword ptr [r13 + 0x44]'),r['0xe804e34']);self.assertEqual(('movss','xmm6, dword ptr [r13 + 0x48]'),r['0xe804e49']);self.assertEqual(('call','0x18e6b2d00'),r['0xe804d99']);self.assertEqual(('mov','rsi, qword ptr [rax + 0x10]'),r['0xe804e98']);self.assertEqual(('call','0x18e6b3120'),r['0xe804ec6'])
 def test_active_version_append_then_current_effect_reuse_increment(self):
  r=rows();self.assertEqual(('inc','dword ptr [rbx + 0x1c]'),r['0xe804fd9']);self.assertEqual(('mov','dword ptr [rbx + 0x18], ecx'),r['0xe804ff5']);self.assertEqual(('call','0x1980b74a0'),r['0xe805019']);self.assertEqual(('mov','rax, qword ptr [rbp + 0x10]'),r['0xe80501e']);self.assertEqual(('inc','dword ptr [rax + 0x48]'),r['0xe80502b'])
 def test_valid_instance_revive_replay_hide_and_fresh_completion_callback(self):
  r=rows();self.assertEqual(('cmp','edx, dword ptr [rcx + 0x20]'),r['0xe805059']);self.assertEqual(('call','0x18d791380'),r['0xe805093']);self.assertEqual(('mov','rax, qword ptr [rbp + 0x10]'),r['0xe805098']);self.assertEqual(('call','0x18d795700'),r['0xe8050c4']);self.assertEqual(('call','0x18e804320'),r['0xe8050d0']);self.assertEqual(('call','0x18e805330'),r['0xe8050df']);self.assertEqual(('mov','qword ptr [rsi + 0x100], rax'),r['0xe8051f7'])
 def test_remove_tail_rereads_current_count_and_closure_calls_async_completion(self):
  r=rows();self.assertEqual(('mov','dword ptr [rsi + 0x18], eax'),r['0xe80521e']);self.assertEqual(('call','0x19c075050'),r['0xe805235']);self.assertEqual(('movsxd','rcx, dword ptr [rsi + 0x18]'),r['0xe805247']);self.assertEqual(('mov','qword ptr [rax + rcx*8 + 0x20], 0'),r['0xe804d13']);self.assertEqual(('inc','dword ptr [rsi + 0x1c]'),r['0xe804d1c']);self.assertEqual(('mov','rcx, qword ptr [rcx + 0x18]'),r['0xe856207']);self.assertEqual(('mov','rdx, qword ptr [rax + 0x10]'),r['0xe856213']);self.assertEqual(('jmp','0x18e805610'),r['0xe85621b'])
 def test_actual_other_event_joint_check_preserves_world_and_virtual_identity_limits(self):
  p=mutex_attach.load_profile();self.assertEqual(54847,p['payloadTypeSlots'][0]['typeIndex']);self.assertEqual(510352,p['methodInfoSlots'][0]['method']['index']);self.assertEqual('_OnAsyncLoadEffectFinish',next(m['name'] for m in p['boundMethodIdentities'] if m['index']==510095));self.assertEqual({54431,54432},{g['record']['args'][0]['payload'] for g in p['genericFields']})
  for k,v in p.items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualOtherFadeRetryAddResizeAndCapacityJointlyTested']);self.assertFalse(self.report['virtualEffectSlot21ToOverrideIdentityIndependentlyMatched']);self.assertFalse(self.report['nativeEffectInstanceOrUnityCachedPointerInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
