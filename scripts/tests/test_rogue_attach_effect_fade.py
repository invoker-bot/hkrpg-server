"""核对淡出流程完整原件、字段、分发接口及两个独立泛型绑定。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('attach_effect_fade',ROOT/'scripts/read-rogue-attach-effect-fade.py');attach_effect_fade=importlib.util.module_from_spec(spec);spec.loader.exec_module(attach_effect_fade)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in attach_effect_fade.load_profile()['nativeMethods']+attach_effect_fade.load_profile()['runtimeMethods'] for i in m['instructions']}
class AttachEffectFadeTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=attach_effect_fade.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-attach-effect-fade-python-evidence.json'))
 def test_complete_native_helpers_auxiliary_fields_slots_generics_and_specs_match(self):
  self.assertEqual((3,998,1,222,2,530,5,4,1,1,1,3,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs']))
 def test_owner_slot22_and_manager_capture_precede_acquire_and_second_owner(self):
  r=rows();self.assertEqual(('mov','rsi, qword ptr [rcx + 0x170]'),r['0xe5ad85d']);self.assertEqual(('mov','rsi, qword ptr [rax + 0x58]'),r['0xe5ad810']);self.assertEqual(('call','0x18b10cfd0'),r['0xe5ad838']);self.assertEqual(('mov','qword ptr [rbx + 0x10], rsi'),r['0xe5ad856']);self.assertEqual(('mov','rdx, qword ptr [rax + 0x10]'),r['0xe5ad881'])
 def test_initializer_source_then_reset_first_version_then_clear_and_reread(self):
  r=rows();self.assertEqual(('mov','qword ptr [rsi + 0x28], rdi'),r['0x159cb1f2']);self.assertEqual(('inc','dword ptr [rdi + 0x1c]'),r['0x159cb203']);self.assertEqual(('mov','dword ptr [rdi + 0x18], 0'),r['0x159cb20a']);self.assertEqual(('call','0x19c074b80'),r['0x159cb21c']);self.assertEqual(('mov','rdi, qword ptr [rsi + 0x20]'),r['0x159cb221'])
 def test_initializer_second_version_single_effect_and_modifier_last_write(self):
  r=rows();self.assertEqual(('inc','dword ptr [rdi + 0x1c]'),r['0x159cb235']);self.assertEqual(('mov','qword ptr [rax + rdx*8 + 0x20], rbx'),r['0x159cb250']);self.assertEqual(('call','0x1980b74a0'),r['0x159cb26d']);self.assertEqual(('mov','qword ptr [rsi + 0x18], r14'),r['0x159cb272'])
 def test_post_cast_target_interface_and_failed_cast_null_before_dispatch(self):
  p=attach_effect_fade.load_profile();self.assertEqual((56693,'EKFMIDBJDIA'),(p['payloadTypeSlots'][0]['typeIndex'],p['payloadTypeSlots'][0]['typeName']));r=rows();self.assertEqual(('xor','edx, edx'),r['0xda3e69b']);self.assertEqual(('call','0x18e66cd20'),r['0xda3e6a0']);self.assertEqual(('test','bl, bl'),r['0xda3e6a5']);self.assertEqual(('call','0x19c3f92d0'),r['0xda3e6b0']);self.assertEqual(('call','0x19c3f8b10'),r['0xda3e6c0'])
 def test_resize_captures_index_uses_growth_minimum_and_publishes_count_before_failure(self):
  r=rows();self.assertEqual(('movsxd','rsi, dword ptr [rcx + 0x18]'),r['0x180b74b0']);self.assertEqual(('mov','ebx, 4'),r['0x180b74f7']);self.assertEqual(('cmovle','ebx, ebp'),r['0x180b7501']);self.assertEqual(('call','0x1980b36d0'),r['0x180b751c']);self.assertEqual(('mov','dword ptr [rdi + 0x18], ebp'),r['0x180b7525']);self.assertEqual(('test','rax, rax'),r['0x180b7528'])
 def test_acquire_and_add_specs_preserve_raw_roles_and_different_arguments(self):
  p=attach_effect_fade.load_profile();facts={s['methodSpec']['methodDefinition']:s for s in p['poolMethodSpecs']};self.assertEqual({12708,523572},set(facts))
  self.assertEqual((-1,39196,'method',54431,'ModifierEffectData'),(facts[12708]['methodSpec']['classInst'],facts[12708]['methodSpec']['methodInst'],facts[12708]['instRecords'][0]['role'],facts[12708]['instRecords'][0]['arguments'][0]['payload'],facts[12708]['instRecords'][0]['arguments'][0]['name']))
  self.assertEqual((39198,-1,'class',56599,'GEHAPKHIMNE'),(facts[523572]['methodSpec']['classInst'],facts[523572]['methodSpec']['methodInst'],facts[523572]['instRecords'][0]['role'],facts[523572]['instRecords'][0]['arguments'][0]['payload'],facts[523572]['instRecords'][0]['arguments'][0]['name']))
 def test_joint_callbacks_keep_finite_policy_and_native_world_dispatch_limits(self):
  for k,v in attach_effect_fade.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualOtherFadeCallbackJointlyTested']);self.assertTrue(self.report['finite4096ProjectionPolicyExplicit']);self.assertFalse(self.report['nativeRuntimeInterfaceCastInstalled']);self.assertFalse(self.report['nativeCapacitySetterBodyImplemented']);self.assertFalse(self.report['globalEventDispatcherBodyImplemented']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
