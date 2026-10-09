"""原始决定入口、一次抽样、召唤者限制与有限结果边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_chance_roll',ROOT/'scripts/read-rogue-modifier-chance-roll.py')
roll=importlib.util.module_from_spec(spec);spec.loader.exec_module(roll)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in roll.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierChanceRollTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.report=roll.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-chance-roll-python-evidence.json'))

 def test_complete_methods_types_and_generic_component_arguments_match_installed_client(self):
  self.assertEqual((11,13149,9,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedGenericComponentArguments']))

 def test_native_result_bytes_and_caller_resisted_expression_are_preserved(self):
  r=rows(508749)
  self.assertEqual(('mov','byte ptr [r15], bpl'),r['0x15c5d823'])
  self.assertEqual(('mov','byte ptr [r15 + 1], bl'),r['0x15c5d826'])
  self.assertEqual(('mov','qword ptr [r15 + 8], r12'),r['0x15c5d82a'])
  r=rows(509996)
  self.assertEqual(('cmp','byte ptr [rbp + 0x181], 0'),r['0x16547109'])
  self.assertEqual(('cmp','byte ptr [rbp + 0x180], 0'),r['0x16547112'])
  self.assertEqual(('sete','al'),r['0x16547119'])

 def test_always_success_precedes_actual_owner_and_normal_immunity(self):
  r=rows(508749)
  self.assertEqual(('call','0x19daf5100'),r['0x15c5d400'])
  self.assertEqual(('mov','edx, 0x45'),r['0x15c5d466'])
  self.assertEqual(('jmp','0x195c5d814'),r['0x15c5d4a2'])
  self.assertEqual(('call','0x18e4bdb80'),r['0x15c5d4f6'])
  t=next(t for t in roll.load_profile()['matchedTypes'] if t['name']=='ModifierBehaviorFlag')
  self.assertEqual([('Shield',10),('AlwaysSuccess',69),('OnlyAllowSummonerHealAndShield',70),('ForceShieldConvert',83)],
   [(f['name'],f['constant']['value']) for f in t['selectedFields']])

 def test_immunity_helpers_both_run_before_negative_bypass(self):
  r=rows(508749)
  self.assertEqual(('call','0x195c5dcd0'),r['0x15c5d668'])
  self.assertEqual(('call','0x195c5e050'),r['0x15c5d67b'])
  self.assertEqual(('cmp','r12, rsi'),r['0x15c5d6a9'])
  self.assertEqual(('jg','0x195c5d89a'),r['0x15c5d6ac'])
  self.assertEqual(('cmp','r13d, 2'),r['0x15c5d6b9'])
  self.assertEqual(('mov','edx, 0x88'),r['0x15c5d75d'])

 def test_same_draw_is_compared_inclusively_before_normal_release_and_resist_callback(self):
  r=rows(508749)
  self.assertEqual(1,sum(m=='call' and o=='0x18e506160' for m,o in r.values()))
  self.assertEqual(('cmp','rbx, r13'),r['0x15c5d9a2'])
  self.assertEqual(('cmp','rbx, r12'),r['0x15c5da78'])
  self.assertEqual(('setle','bpl'),r['0x15c5dae0'])
  self.assertEqual(('setle','bl'),r['0x15c5dae6'])
  self.assertEqual(('call','0x18b10ce60'),r['0x15c5dad9'])
  self.assertEqual(('call','0x18e7ca4a0'),r['0x15c5db12'])
  self.assertEqual(('xor','ebp, ebp'),r['0x15c5db19'])

 def test_shield_summoner_uses_actual_character_component_and_identity_field(self):
  r=rows(508749)
  self.assertEqual(('mov','edx, 0x46'),r['0x15c5d517'])
  self.assertEqual(('cmp','rbx, rdi'),r['0x15c5d52f'])
  self.assertEqual(('mov','rax, qword ptr [rax + 0xe0]'),r['0x15c5d54c'])
  self.assertEqual(('cmp','rax, rbx'),r['0x15c5d553'])
  t=next(t for t in roll.load_profile()['matchedTypes'] if t['typeIndex']==56916)['selectedFields'][0]
  self.assertEqual(('Summoner',224,53551),(t['name'],t['instanceOffset'],t['nativeType']['typeIndex']))
  slot=roll.load_profile()['genericMethodSlots'][1]
  self.assertEqual(('0x94553d0',56916),(slot['slot'],slot['instRecords'][0]['argumentTypeIndex']))

 def test_force_shield_conversion_event_precedes_any_random_sample(self):
  r=rows(508749)
  self.assertEqual(('mov','edx, 0x53'),r['0x15c5d5c1'])
  self.assertEqual(('mov','edx, 0xec'),r['0x15c5d61a'])
  self.assertEqual(('call','0x18e7548e0'),r['0x15c5d625'])
  self.assertEqual(('call','0x18b10ce60'),r['0x15c5d634'])
  self.assertEqual(('jmp','0x195c5d812'),r['0x15c5d639'])

 def test_world_logic_random_fields_and_counter_are_matched_without_claiming_algorithm(self):
  p=roll.load_profile();types={t['typeIndex']:t for t in p['matchedTypes']}
  f=types[53551]['selectedFields'][0]
  self.assertEqual(('_OwnerWorldRef',136,57810),(f['name'],f['instanceOffset'],f['nativeType']['typeIndex']))
  f=types[57810]['selectedFields'][0]
  self.assertEqual(('LogicRandom',32,56182),(f['name'],f['instanceOffset'],f['nativeType']['typeIndex']))
  f=types[56182]['selectedFields'][0]
  self.assertEqual(('OLEEIPHBJKB',16,317),(f['name'],f['instanceOffset'],f['nativeType']['typeIndex']))
  r=rows(520486)
  self.assertEqual(('mov','ebx, dword ptr [rsi + 0x1c]'),r['0xe5061ab'])
  self.assertEqual(('mov','dword ptr [rsi + 0x1c], edi'),r['0xe5061db'])

 def test_bound_decision_is_distinct_from_live_world_immunity_events_random_and_instances(self):
  r=self.report
  for k in ['finiteChanceDecisionImplemented','oneSampleComparedInclusively','negativeBypassAfterImmunity',
   'alwaysSuccessPrecedesOwnerAndImmunity','shieldAndSummonerPolicyImplemented',
   'normalReturnOnlyAntiListReleaseImplemented','orderedSourceAndContextReadsPreserved']:self.assertTrue(r[k],k)
  for k in ['actualOwnerHelperImplemented','actualImmunityTablesImplemented','nativeEventPoolsImplemented',
   'actualRandomAlgorithmImplemented','actualWorldPropertiesImplemented','modifierInstanceApplied',
   'fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified',
   'jsonInstalled','clientExecutableStarted','clientModified']:self.assertFalse(r[k],k)

 def test_output_guard_rejects_client_and_nonbuild_paths_before_reading(self):
  for output in [CLIENT/'chance-roll.json',ROOT/'chance-roll.json']:
   with self.assertRaises(ValueError):roll.analyze(SimpleNamespace(client_dir=CLIENT,output=output))

if __name__=='__main__':unittest.main()
