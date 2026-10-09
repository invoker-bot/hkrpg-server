"""核对激活初始化、实例状态、二次对象查询及角色特效来源。"""
import importlib.util,unittest
from pathlib import Path
from types import SimpleNamespace
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('effect_active',ROOT/'scripts/read-rogue-effect-active.py');effect_active=importlib.util.module_from_spec(spec);spec.loader.exec_module(effect_active)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in effect_active.load_profile()['nativeMethods'] for i in m['instructions']}
class EffectActiveTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=effect_active.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-effect-active-python-evidence.json'))
 def test_complete_body_fields_bindings_and_generic_component_record_match(self):
  self.assertEqual((1,454,0,0,4,1555,4,4,0,0,1,25,1,0,0),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs','matchedMethodInfoSlots','matchedRawFloatLiterals']))
 def test_initialization_precedes_current_owner_read(self):
  r=rows();self.assertEqual(('cmp','byte ptr [rsi + 0x7c], 0'),r['0xd79a429']);self.assertEqual(('call','0x18d78dc60'),r['0xd79a432']);self.assertEqual(('mov','rcx, qword ptr [rsi + 0x10]'),r['0xd79a437'])
 def test_owner_class_slot_and_active_boolean_are_independently_matched(self):
  p=effect_active.load_profile();self.assertEqual([{'slot':'0x93c4a80','pairIndex':720946,'typeIndex':69911,'typeName':'MonoEffect'}],p['payloadTypeSlots']);t=next(t for t in p['matchedTypes'] if t['typeIndex']==69911);f=next(f for f in t['selectedInstanceFields'] if f['instanceOffset']==57);self.assertEqual(('<IsActive>k__BackingField',2),(f['name'],f['nativeType']['kind']))
 def test_active_write_precedes_first_object_lookup_and_validity_gate(self):
  r=rows();self.assertEqual(('mov','byte ptr [rcx + 0x39], bl'),r['0xd79a495']);self.assertEqual(('call','qword ptr [rip - 0x445e666]'),r['0xd79a498']);self.assertEqual(('test','rax, rax'),r['0xd79a49e']);self.assertEqual(('cmp','qword ptr [rax + 0x10], 0'),r['0xd79a4a7'])
 def test_visibility_is_followed_by_current_owner_and_nonnull_second_object(self):
  r=rows();self.assertEqual(('call','0x18d795010'),r['0xd79a4b7']);self.assertEqual(('mov','rcx, qword ptr [rsi + 0x10]'),r['0xd79a4bc']);self.assertEqual(('call','qword ptr [rip - 0x445e6c9]'),r['0xd79a4fb']);self.assertEqual(('test','rax, rax'),r['0xd79a501']);self.assertEqual(('je','0x18d79a59f'),r['0xd79a504'])
 def test_generic_component_is_game_object_get_component_chara_plugin(self):
  p=effect_active.load_profile();m=next(m for m in p['boundMethodIdentities'] if m['index']==31472);self.assertEqual(('GameObject','GetComponent'),(m['typeName'],m['name']));s=p['poolMethodSpecs'][0];self.assertEqual({'methodDefinition':31472,'classInst':51529,'methodInst':-1},s['methodSpec']);arg=s['instRecords'][0]['arguments'][0];self.assertEqual((18,69938,'MonoEffectPluginCharaEffectBase'),tuple(arg[k] for k in ['kind','payload','name']))
 def test_chara_plugin_validity_and_enable_with_immediate_true(self):
  r=rows();self.assertEqual(('test','rcx, rcx'),r['0xd79a552']);self.assertEqual(('cmp','qword ptr [rcx + 0x10], 0'),r['0xd79a557']);self.assertEqual(('mov','edx, ebx'),r['0xd79a55e']);self.assertEqual(('mov','r8d, 1'),r['0xd79a560']);self.assertEqual(('jmp','0x18d79a630'),r['0xd79a56d'])
 def test_joint_projection_preserves_initialization_visibility_unity_and_world_limits(self):
  p=effect_active.load_profile()
  for k,v in p.items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualModifierAndSkillHideAndActivationJointlyTested']);self.assertFalse(self.report['nativeEffectInitializationBodyImplemented']);self.assertFalse(self.report['nativeEffectSetVisibleBodyImplemented']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
