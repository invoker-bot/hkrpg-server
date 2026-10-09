"""核对销毁状态、共享清理、预展示实体、HUD闭包Getter和子项顺序。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_destroy',ROOT/'scripts/read-rogue-modifier-destroy.py');modifier_destroy=importlib.util.module_from_spec(spec);spec.loader.exec_module(modifier_destroy)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in modifier_destroy.load_profile()['nativeMethods'] for i in m['instructions']}
class ModifierDestroyTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=modifier_destroy.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-destroy-python-evidence.json'))
 def test_complete_destroy_auxiliary_fields_generics_parameters_and_identities_match(self):
  self.assertEqual((1,964,2,3751,7,12,1,1,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedPoolMethodSpecs','matchedNativeParameterRecords']))
 def test_signed_state_guard_precedes_capture_owner_and_publish_reason_state(self):
  r=rows();self.assertEqual(('cmp','ebp, 1'),r['0xe7c5b15']);self.assertEqual(('jg','0x18e7c5cdb'),r['0xe7c5b18']);self.assertEqual(('mov','rsi, qword ptr [rcx + 0x10]'),r['0xe7c5b2e']);self.assertEqual(('mov','dword ptr [rdi + 0x78], r14d'),r['0xe7c5b49']);self.assertEqual(('mov','dword ptr [rdi + 0x90], 2'),r['0xe7c5b4d'])
 def test_alive_event_increments_and_only_normally_decrements_shared_counter_before_actual_cleanup(self):
  r=rows();self.assertEqual(('inc','dword ptr [rdi + 0x98]'),r['0xe7c5bc5']);self.assertEqual(('call','0x18e811c10'),r['0xe7c5bce']);self.assertEqual(('dec','dword ptr [rdi + 0x98]'),r['0xe7c5bd3']);self.assertEqual(('call','0x18e806be0'),r['0xe7c5bdc'])
 def test_raw_byte_behavior_difference_and_current_owner_mute_write_match(self):
  r=rows();self.assertEqual(('mov','byte ptr [r15 + 0x2b7], al'),r['0xe7c5bb9']);self.assertEqual(('xor','bl, al'),r['0xe7c5c06']);self.assertEqual(('cmp','bl, 1'),r['0xe7c5c08']);self.assertEqual(('mov','dx, 0x31e'),r['0xe7c5c33'])
 def test_notify_uses_character_hud_class_instantiation_and_shared_modifier_payload(self):
  p=modifier_destroy.load_profile();row=p['poolMethodSpecs'][0];self.assertEqual(('0x9558820',502972,26172,-1),(row['slot'],row['methodSpec']['methodDefinition'],row['methodSpec']['classInst'],row['methodSpec']['methodInst']));arg=row['instRecords'][0]['arguments'][0];self.assertEqual((57180,'CharacterHudComponent'),(arg['payload'],arg['name']));r=rows();self.assertEqual(('mov','ecx, 0x36e'),r['0xe7c5c8c']);self.assertEqual(('mov','rdx, rdi'),r['0xe7c5c91']);self.assertEqual(('mov','r8b, 1'),r['0xe7c5c94'])
 def test_preshow_entity_field_and_group_enum_are_independently_matched(self):
  p=modifier_destroy.load_profile();f=next(f for f in p['matchedTypes'][0]['selectedInstanceFields'] if f['instanceOffset']==232);self.assertEqual(('<CreatePreshowEntity>k__BackingField',53551),(f['name'],f['nativeType']['typeIndex']));self.assertEqual(('Group',27),(p['matchedTypes'][4]['selectedFields'][0]['name'],p['matchedTypes'][4]['selectedFields'][0]['constant']['value']));r=rows();self.assertEqual(('cmp','dword ptr [rbx + 0x100], 0x1b'),r['0xe7c5cc5']);self.assertEqual(('call','0x18e6ba380'),r['0xe7c5cd4']);self.assertEqual(('call','0x18e6ba510'),r['0xe7c5cf1'])
 def test_children_use_current_parent_reason_and_clear_headers_before_storage(self):
  r=rows();self.assertEqual(('mov','edx, dword ptr [rdi + 0x78]'),r['0xe7c5d59']);self.assertEqual(('call','0x18e7c5af0'),r['0xe7c5d5c']);self.assertEqual(('mov','rax, qword ptr [rdi + 0x170]'),r['0xe7c5d64']);self.assertEqual(('inc','dword ptr [rax + 0x1c]'),r['0xe7c5d75']);self.assertEqual(('mov','dword ptr [rax + 0x18], 0'),r['0xe7c5d78']);self.assertEqual(('jmp','0x18e5ae920'),r['0xe7c5da1']);g=modifier_destroy.load_profile()['genericFields'][0];self.assertEqual(54843,g['record']['args'][0]['payload'])
 def test_event_base_getter_world_and_gameplay_boundaries_remain_explicit(self):
  for k,v in modifier_destroy.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualEffectCleanupBodyCalled']);self.assertTrue(self.report['actualPointRefreshConsumptionDestructionJointlyTested']);self.assertFalse(self.report['fullOnModifierDestroyEventBodyImplemented']);self.assertFalse(self.report['fullBaseDestroyBodyImplemented']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
