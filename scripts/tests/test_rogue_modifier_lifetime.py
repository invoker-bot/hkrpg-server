"""寿命原件分支、锁定来源和有限状态绑定边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_lifetime',ROOT/'scripts/read-rogue-modifier-lifetime.py')
lifetime=importlib.util.module_from_spec(spec);spec.loader.exec_module(lifetime)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in lifetime.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierLifetimeTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=lifetime.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-lifetime-python-evidence.json'))
 def test_complete_methods_and_all_eleven_field_types_match_original(self):
  self.assertEqual((8,4502,11),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes']))
 def test_initial_lifetime_sentinel_is_checked_before_status_lookup(self):
  r=rows(510244)
  self.assertEqual(('cmp','dword ptr [rsi + 0x2dc], -1'),r['0xe80dd5a'])
  self.assertEqual(('je','0x18e80de6d'),r['0xe80dd61'])
  self.assertEqual(('dec','edi'),r['0xe80de19'])
  self.assertEqual(('mov','dword ptr [rsi + 0x2a8], edi'),r['0xe80de28'])
 def test_ordinary_step_keeps_immediate_override_of_same_turn(self):
  r=rows(510133)
  self.assertEqual(('cmp','eax, 6'),r['0xe7d0127'])
  self.assertEqual(('cmp','byte ptr [rsi + 0x2e5], 0'),r['0xe7d0133'])
  self.assertEqual(('jne','0x18e7d0144'),r['0xe7d013a'])
  self.assertEqual(('cmp','dword ptr [rsi + 0x2a4], edi'),r['0xe7d013c'])
 def test_round_change_dispatches_only_round_end_and_does_not_assign_remaining_rounds(self):
  r=rows(510138)
  self.assertEqual(('cmp','dword ptr [rax + 0x4c], 6'),r['0xe7d0c2d'])
  self.assertEqual(('jmp','0x18e80dd40'),r['0xe7d0c3b'])
  t=next(t for t in lifetime.load_profile()['matchedTypes'] if t['typeIndex']==18225)
  self.assertEqual([('Unknow',0),('ModifierPhase1End',1),('ModifierPhase2End',2),('CasterModifierPhase1End',3),('ActionPhaseEnd',4),('TurnEnd',5),('RoundEnd',6)],[(f['name'],f['constant']['value']) for f in t['fields'] if 'constant' in f])
 def test_state_lookup_uses_current_override_key_then_original_name(self):
  r=rows(510244)
  self.assertEqual(('mov','rdi, qword ptr [rsi + 0x208]'),r['0xe80dd74'])
  self.assertEqual(('mov','rdi, qword ptr [rsi + 0x30]'),r['0xe80de78'])
  self.assertEqual(('call','0x19daf5100'),r['0xe80dda5'])
 def test_lock_step_argument_is_entity_type_and_instance_lock_tag_controls_preservation(self):
  r=rows(510244)
  self.assertEqual(('mov','edi, dword ptr [rax + 0x100]'),r['0xe80dde5'])
  self.assertEqual(('call','0x18e7d1910'),r['0xe80de01'])
  self.assertEqual(('cmp','dword ptr [rsi + 0x288], 0'),r['0xe80de0a'])
  t=next(t for t in lifetime.load_profile()['matchedTypes'] if t['typeIndex']==53551)
  self.assertEqual(('_EntityType',256,10436),(t['selectedFields'][0]['name'],t['selectedFields'][0]['instanceOffset'],t['selectedFields'][0]['nativeType']['typeIndex']))
  r=rows(510599)
  self.assertEqual(('cmp','dword ptr [rdi + 0x264], 0'),r['0xe7d1934'])
  self.assertEqual(('mov','rdx, qword ptr [rdi + 0x198]'),r['0xe7d193d'])
  self.assertEqual(('cmp','dword ptr [rdi + 0x288], 0'),r['0xe7d1948'])
  self.assertEqual(('mov','rdx, qword ptr [rdi + 0xe8]'),r['0xe7d1951'])
  r=rows(510600)
  self.assertEqual(('cmp','eax, esi'),r['0xe7d1a52'])
  self.assertEqual(('je','0x18e7d1c58'),r['0xe7d1a54'])
 def test_value_change_precedes_hud_then_life_end_destroy(self):
  r=rows(510244)
  self.assertEqual(('call','0x18e807e60'),r['0xe80de36'])
  self.assertEqual(('mov','ecx, 0x36f'),r['0xe80de54'])
  self.assertEqual(('mov','edx, 2'),r['0xe80deae'])
  self.assertEqual(('call','0x18e7c5af0'),r['0xe80deb3'])
 def test_post_expiry_world_chain_is_task_world_battle_mode_and_limbo_container(self):
  r=rows(510244)
  self.assertEqual(('mov','rax, qword ptr [rax + 0x38]'),r['0xe80dec5'])
  self.assertEqual(('mov','rax, qword ptr [rax + 0x98]'),r['0xe80dece'])
  self.assertEqual(('mov','rsi, qword ptr [rax + 0x18]'),r['0xe80dede'])
  self.assertEqual(('mov','rax, qword ptr [rsi + 0x78]'),r['0xe80def8'])
  self.assertEqual(('jmp','0x18e7ebce0'),r['0xe80df1c'])
 def test_finite_step_does_not_claim_live_events_virtual_binding_destroy_or_world_installation(self):
  for k in ['finiteOrdinaryLifetimeStepImplemented','finiteRoundChangeStepImplemented','initialLifetimeSentinelPreserved',
   'immediateStepBypassesSameTurn','finiteLockCountAndEntityTypesImplemented','valueChangeBeforeHudAndDestroy',
   'postExpiryLimboBindingOrderPreserved','signed32LifetimeWrapImplemented']:self.assertTrue(self.report[k],k)
  for k in ['actualContextEntityVirtualBindingImplemented','nativeSourceContainersInstalled','lockCountersAttachedToWorld',
   'nativeHudEventDispatcherImplemented','nativeValueBindingArrayImplemented','fullNativeDestroyImplemented',
   'fullNativeLimboDisposalImplemented','runtimeHotfixOverridesImplemented','modifierInstanceApplied',
   'fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified',
   'jsonInstalled','clientExecutableStarted','clientModified']:self.assertFalse(self.report[k],k)
 def test_readonly_report_rejects_client_and_nonbuild_target(self):
  for output in [CLIENT/'modifier-lifetime.json',ROOT/'modifier-lifetime.json']:
   with self.assertRaises(ValueError):lifetime.analyze(SimpleNamespace(client_dir=CLIENT,output=output))

if __name__=='__main__':unittest.main()
