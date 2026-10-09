"""核对异步效果完成普通体、卸载字段与完整双参数委托类型。"""
import importlib.util,unittest
from pathlib import Path
from types import SimpleNamespace
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('async_effect_finish',ROOT/'scripts/read-rogue-async-effect-finish.py');async_effect_finish=importlib.util.module_from_spec(spec);spec.loader.exec_module(async_effect_finish)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in async_effect_finish.load_profile()['nativeMethods'] for i in m['instructions']}
class AsyncEffectFinishTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=async_effect_finish.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-async-effect-finish-python-evidence.json'))
 def test_full_body_fields_callbacks_and_auxiliary_match(self):
  self.assertEqual((1,263,0,0,9,3818,10,3,3,1,1,34,2,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs','matchedMethodInfoSlots']))
 def test_optional_instance_pointer_params_and_signed_alive_state_precede_self_read(self):
  r=rows();self.assertEqual(('test','rbx, rbx'),r['0xe805629']);self.assertEqual(('cmp','qword ptr [rbx + 0x10], 0'),r['0xe80562e']);self.assertEqual(('mov','rax, qword ptr [rbx + 0x48]'),r['0xe80563e']);self.assertEqual(('cmp','dword ptr [rax + 0x128], 5'),r['0xe805647']);self.assertEqual(('jle','0x18e8056a1'),r['0xe80564e'])
 def test_effect_unload_or_null_active_list_uses_set_finish_true_false_false(self):
  r=rows();self.assertEqual(('cmp','byte ptr [rdi + 0x27c], 0'),r['0xe8056a1']);self.assertEqual(('cmp','qword ptr [rdi + 0x50], 0'),r['0xe8056c5']);self.assertEqual(('mov','rcx, qword ptr [rbx + 0x100]'),r['0xe8056aa']);self.assertEqual(('mov','dl, 1'),r['0xe8056b6']);self.assertEqual(('xor','r8d, r8d'),r['0xe8056b8']);self.assertEqual(('xor','r9d, r9d'),r['0xe8056bb']);self.assertEqual(('call','0x18d78f360'),r['0xe8056be'])
 def test_active_instance_id_padding_pointer_written_before_hide_and_skill(self):
  r=rows();self.assertEqual(('mov','eax, dword ptr [rbx + 0x20]'),r['0xe8056d1']);self.assertEqual(('mov','dword ptr [rsi + 0x20], eax'),r['0xe8056d4']);self.assertEqual(('mov','dword ptr [rsi + 0x24], 0'),r['0xe8056d7']);self.assertEqual(('mov','qword ptr [rsi + 0x28], rbx'),r['0xe8056de']);self.assertEqual(('call','0x18e804320'),r['0xe8056e8']);self.assertEqual(('jmp','0x18e805330'),r['0xe8056fd'])
 def test_disposal_and_lifetime_fields_are_independently_matched(self):
  types={t['typeIndex']:t for t in async_effect_finish.load_profile()['matchedTypes']}
  for index,offset,name in [(54843,636,'_EffectUnload'),(69911,72,'TriggerParams'),(69918,296,'<AliveState>k__BackingField')]:
   f=next(f for f in types[index]['selectedInstanceFields'] if f['instanceOffset']==offset);self.assertEqual(name,f['name'])
 def test_complete_callback_is_action_two_with_mono_effect_and_callback_value(self):
  p=async_effect_finish.load_profile();record=next(g['record'] for g in p['genericFields'] if g['name']=='CompleteCallback');self.assertEqual(('Action`2',170),(record['genericName'],record['genericDefinition']));self.assertEqual([(18,69911,'MonoEffect'),(17,69914,'TriggerEffectCallbackParams')],[(a['kind'],a['payload'],a['name']) for a in record['args']]);self.assertTrue(self.report['ignoredSecondNativeCompletionParameterPreserved'])
 def test_bound_set_finish_and_two_hide_identities_are_preserved(self):
  p=async_effect_finish.load_profile();self.assertEqual({639670:'SetFinish',510096:'_SetupEffectHide',510101:'_TryHideEffectInSkillUse'},{m['index']:m['name'] for m in p['boundMethodIdentities']});self.assertEqual(510352,p['methodInfoSlots'][0]['method']['index'])
 def test_actual_closure_joint_check_keeps_unity_world_and_hidden_bodies_limits(self):
  p=async_effect_finish.load_profile()
  for k,v in p.items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualMutexCompletionClosureAndAsyncFinishJointlyTested']);self.assertFalse(self.report['nativeMonoEffectSetFinishBodyImplemented']);self.assertFalse(self.report['effectHideAndSkillHideBodiesImplemented']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
