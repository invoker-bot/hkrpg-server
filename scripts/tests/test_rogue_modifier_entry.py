"""有限效果入口的完整PE、泛型组件身份、配置省略及真实字节边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_entry',ROOT/'scripts/read-rogue-modifier-entry.py')
entry=importlib.util.module_from_spec(spec);spec.loader.exec_module(entry)


def rows(index):
 return {r['rva']:(r['mnemonic'],r['operand']) for m in entry.load_profile()['nativeMethods'] if m['index']==index for r in m['instructions']}


class ModifierEntryTest(unittest.TestCase):
 def test_all_five_complete_methods_fields_and_generic_component_match_current_client(self):
  self.assertEqual((5,10316),entry.verify_native(CLIENT))

 def test_source_charm_is_the_matched_component_bool_not_an_invented_status(self):
  p=entry.load_profile();c=p['sourceCharmComponent'];s=p['genericMethodSlots'][0]
  self.assertEqual((54850,'TurnBasedAbilityComponent',692,'bIsInCharmAction'),
   (c['typeIndex'],c['name'],c['field']['instanceOffset'],c['field']['name']))
  self.assertEqual((6,502972,54850),(s['usageKind'],s['methodSpec']['methodDefinition'],s['instRecords'][0]['argumentTypeIndex']))
  self.assertEqual(p['genericMethodSlots'],entry.components.read_generic_slots(CLIENT,p))

 def test_native_constructor_preserves_minus_one_sentinels_and_explicit_boolean_writes(self):
  r=rows(127703);d=entry.load_profile()['constructorDefaults']
  self.assertEqual(('mov','byte ptr [rcx + 0x20], 1'),r['0x1d35c329'])
  self.assertEqual(('movabs','rdi, 0xfffffffe00000000'),r['0x1d35c33d'])
  self.assertEqual((-8589934592,-8589934592),(d['ChanceRaw'],d['MaxTargetCountRaw']))
  self.assertEqual(('mov','word ptr [rsi + 0x84], 0x101'),r['0x1d35c371'])
  self.assertEqual(('mov','dword ptr [rsi + 0x8c], 0xbf800000'),r['0x1d35c37a'])
  self.assertEqual(('mov','byte ptr [rsi + 0x90], 1'),r['0x1d35c384'])
  self.assertEqual(('mov','byte ptr [rsi + 0xad], 1'),r['0x1d35c38b'])
  self.assertEqual(('mov','byte ptr [rsi + 0x10], 1'),r['0x1d35c392'])

 def test_charm_check_and_terminal_skip_precede_alive_target_selection(self):
  r=rows(509996)
  self.assertEqual(('call','0x18e777190'),r['0x16546539'])
  self.assertEqual(('cmp','byte ptr [rax + 0x2b4], 0'),r['0x16546577'])
  self.assertEqual(('mov','dword ptr [r15 + 0x10], 0x9999'),r['0x16546580'])
  self.assertEqual(('mov','esi, 1'),r['0x16546614'])
  self.assertEqual(('cmove','esi, ecx'),r['0x16546619'])
  self.assertEqual(('call','0x18e7796f0'),r['0x16546665'])
  enum=entry.load_profile()['aliveMaskType']['fields']
  self.assertTrue(next(f['constant']['unsupportedValueEncoding'] for f in enum if f['name']=='Mask_AliveOnly'))
  self.assertEqual(1,entry.load_profile()['sourceAliveMask'])

 def test_empty_targets_release_and_complete_without_final_owner_notification(self):
  r=rows(509996)
  self.assertEqual(('cmp','dword ptr [r12 + 0x18], 0'),r['0x16546673'])
  self.assertEqual(('je','0x196547b39'),r['0x16546679'])
  self.assertEqual(('call','0x18b10ce60'),r['0x16547b43'])
  self.assertEqual(('mov','dword ptr [r15 + 0x10], 0x9999'),r['0x16547b48'])

 def test_literal_dynamic_string_branch_reads_value_without_resolving_a_variable(self):
  r=rows(509813)
  self.assertEqual(('cmp','byte ptr [rsi + 0x10], 0'),r['0xe778659'])
  self.assertEqual(('je','0x18e7786b9'),r['0xe77865d'])
  self.assertEqual(('mov','r15, qword ptr [rsi + 0x20]'),r['0xe7786b9'])
  self.assertEqual(('jmp','0x18e778a2d'),r['0xe7786bd'])

 def test_nonempty_entry_completes_after_separate_context_owner_notification(self):
  r=rows(509996)
  self.assertEqual(('call','0x18b10c6a0'),r['0x16547eec'])
  self.assertEqual(('call','0x195c62fa0'),r['0x16547f0a'])
  self.assertEqual(('call','0x18b10ce60'),r['0x16547f19'])
  self.assertEqual(('mov','dword ptr [rdi + 0x10], 0x9999'),r['0x16547f1e'])

 def test_every_fresh_original_modifier_is_caster_literal_and_omits_runtime_options(self):
  source=entry.source_commands(CLIENT)
  self.assertEqual([41001,41002,41003,41004,41005,41008],[s['eventId'] for s in source])
  for s in source:
   t=s['command'];self.assertEqual(('0xa','Caster','0x4'),(t['$mask'],t['TargetType']['Alias'],t['ModifierName']['$mask']))
   for key in ['AliveOnly','Chance','MaxTargetCount','SuccessTaskList','FailTaskList','TaskEnabled']:self.assertNotIn(key,t)

 def test_report_distinguishes_entry_from_actual_effect_runtime_and_client_acceptance(self):
  r=entry.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-entry-python-evidence.json'))
  self.assertTrue(r['passed']);self.assertEqual((5,10316,6,54850,692,1),tuple(r[k] for k in [
   'matchedNativeMethods','matchedNativeBytes','sourceModifierCommands','sourceCharmTypeIndex','sourceCharmFieldOffset','genericGetComponentSlots']))
  for k in ['finiteModifierEntryImplemented','constructorDefaultWritesMatched','sourceCharmComponentTypeArgumentMatched',
   'charmSkipBeforeTargetSelectionMatched','defaultAliveMaskMatched','entryCompletionAfterBoundWorkMatched','literalDynamicStringBranchMatched']:self.assertTrue(r[k],k)
  for k in ['modifierDefinitionResolutionImplemented','eligibilityRulesImplemented','casterInheritanceImplemented',
   'stackingAndCallbacksImplemented','targetNotificationImplemented','fullOriginalAddModifierRuntimeImplemented',
   'liveBattleContextAttached','onlineOnAddExecuted','clientGameplayVerified','completeResonanceRuntimeImplemented',
   'jsonInstalled','clientExecutableStarted','clientModified']:self.assertFalse(r[k],k)

 def test_output_cannot_write_into_client_or_outside_build(self):
  for output in [CLIENT/'modifier.json',ROOT/'modifier.json']:
   with self.assertRaises(ValueError):entry.analyze(SimpleNamespace(client_dir=CLIENT,output=output))


if __name__=='__main__':unittest.main()
