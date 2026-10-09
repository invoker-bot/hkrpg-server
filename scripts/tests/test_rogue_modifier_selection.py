"""原始修改器名称、泛型覆盖表、活性与战斗事件限制的独立静态核对。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_selection',ROOT/'scripts/read-rogue-modifier-selection.py')
selection=importlib.util.module_from_spec(spec);spec.loader.exec_module(selection)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in selection.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierSelectionTest(unittest.TestCase):
 def test_eight_complete_methods_and_original_field_layouts_match_fixed_client(self):
  self.assertEqual((8,8710),selection.verify_native(CLIENT))

 def test_alive_or_limbo_is_a_null_safe_unsigned_four_state_range(self):
  r=rows(535071)
  self.assertEqual(('test','rsi, rsi'),r['0xe6a2dd1'])
  self.assertEqual(('mov','esi, dword ptr [rsi + 0xe0]'),r['0xe6a2dd6'])
  self.assertEqual(('dec','esi'),r['0xe6a2df5']);self.assertEqual(('cmp','esi, 4'),r['0xe6a2df7'])
  self.assertEqual(('setb','al'),r['0xe6a2dfa']);self.assertEqual(('xor','eax, eax'),r['0xe6a2e03'])

 def test_target_and_source_gates_preserve_type_component_team_and_behavior_flag_order(self):
  r=rows(535073)
  self.assertEqual(('call','0x18e6a2dc0'),r['0xe6a2fd5'])
  self.assertEqual(('je','0x18e6a307d'),r['0xe6a2fdc'])
  self.assertEqual(('cmp','dword ptr [rbx + 0x100], 0xc'),r['0xe6a2fee'])
  self.assertEqual(('call','0x18b10cc50'),r['0xe6a3005'])
  self.assertEqual(('cmp','byte ptr [rax + 0x17a], 0'),r['0xe6a300f'])
  self.assertEqual(('cmp','dword ptr [rdi + 0xc8], 2'),r['0xe6a301d'])
  self.assertEqual(('test','rsi, rsi'),r['0xe6a3026'])
  self.assertEqual(('mov','edx, 0x54'),r['0xe6a302e']);self.assertEqual(('jmp','0x19dc35e90'),r['0xe6a303b'])

 def test_behavior_flag_is_array_membership_rather_than_a_bit_mask(self):
  r=rows(110224)
  self.assertEqual(('mov','rbx, qword ptr [rcx + 0x68]'),r['0x1dc35e97'])
  self.assertEqual(('test','rbx, rbx'),r['0x1dc35e9b'])
  self.assertEqual(('cmp','dword ptr [rbx + rdx*4 + 0x20], esi'),r['0x1dc35ee0'])
  self.assertEqual(('cmp','dword ptr [rbx + rax*4 + 0x20], esi'),r['0x1dc35f30'])
  self.assertEqual(('xor','eax, eax'),r['0x1dc35f3e'])

 def test_component_method_spec_and_override_dictionary_arguments_match_real_types(self):
  p=selection.load_profile()
  self.assertEqual(2,selection.verify_override_mapping(CLIENT))
  slot=p['genericMethodSlots'][0]
  self.assertEqual(('0x9457350',502972,56888),(slot['slot'],slot['methodSpec']['methodDefinition'],slot['instRecords'][0]['argumentTypeIndex']))
  self.assertEqual([slot],selection.entry.components.read_generic_slots(CLIENT,p))
  r=p['overrideMappingGeneric']
  self.assertEqual((1535,'Dictionary`2'),(r['genericDefinition'],r['genericName']))
  self.assertEqual([(14,None),(18,'OKOCPOFNODF')],[(a['kind'],a['name']) for a in r['args']])
  self.assertEqual(('0x24cd28',36664),(r['startupRowOffset'],r['instIndex']))

 def test_existing_override_lookup_returns_null_for_missing_table_or_key(self):
  r=rows(510001)
  self.assertEqual(('mov','rdi, qword ptr [rax + 0xd0]'),r['0x16547fe7'])
  self.assertEqual(('call','0x19b0f4110'),r['0x16548010'])
  self.assertEqual(('js','0x196548034'),r['0x16548017'])
  self.assertEqual(('mov','rax, qword ptr [rcx + rax*8 + 0x30]'),r['0x1654802d'])
  self.assertEqual(('xor','eax, eax'),r['0x16548034'])

 def test_override_names_and_reference_selection_are_separate_from_caster_entities(self):
  r=rows(509996)
  self.assertEqual(('mov','rsi, qword ptr [r14 + 0x78]'),r['0x165468d0'])
  self.assertEqual(('cmove','rsi, rcx'),r['0x165468d7'])
  self.assertEqual(('cmp','byte ptr [r14 + 0x88], 0'),r['0x165468db'])
  self.assertEqual(('cmove','rdx, rsi'),r['0x165468e6'])
  self.assertEqual(('mov','rbx, qword ptr [rax + 0x30]'),r['0x16546905'])
  self.assertEqual(('cmove','rbx, rsi'),r['0x1654690c'])
  self.assertEqual(('call','0x195c5eb00'),r['0x1654692e'])

 def test_qualification_precedes_alias_and_alias_precedes_additional_restriction(self):
  r=rows(509996)
  self.assertEqual(('call','0x18e6a2fa0'),r['0x1654695b'])
  self.assertEqual(('je','0x196546857'),r['0x16546962'])
  self.assertEqual(('call','0x19c0cced0'),r['0x16546985'])
  self.assertEqual(('call','0x18e4af3e0'),r['0x165469aa'])
  self.assertEqual(('call','0x19c0cced0'),r['0x165469ee'])
  self.assertEqual(('xor','al, 1'),r['0x165469fa'])
  self.assertEqual(('call','0x195c62700'),r['0x16546a32'])
  self.assertEqual(('je','0x196546850'),r['0x16546a39'])
  self.assertEqual(('test','rbx, rbx'),rows(508783)['0x15c62727'])
  self.assertEqual(('je','0x195c6291c'),rows(508783)['0x15c6272a'])
  self.assertEqual(('call','0x19b0f4780'),rows(522103)['0xe4af444'])

 def test_source_tasks_use_implicit_reference_name_and_have_no_inferred_live_target_effects(self):
  p=selection.load_profile()
  self.assertEqual(p['sourceModifierCommands'],selection.entry.source_commands(CLIENT))
  self.assertEqual(6,len(p['sourceModifierCommands']))
  for record in p['sourceModifierCommands']:
   self.assertNotIn('ReferenceModifierName',record['command'])

 def test_report_exposes_only_finite_qualification_and_output_cannot_overwrite_operator_or_client(self):
  r=selection.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-selection-python-evidence.json'))
  self.assertTrue(r['passed'])
  self.assertEqual((8,8710,6,2,1,6,84,2,12),tuple(r[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes',
   'matchedFieldTypes','matchedOverrideGenericArguments','matchedBattleEventComponentSlots','sourceModifierCommands',
   'requiredBehaviorFlag','restrictedSourceTeam','restrictedTargetType']))
  for k in ['finiteTargetQualificationImplemented','aliveOrLimboShortCircuitImplemented','battleEventDarkTeamBehaviorFlagGateImplemented',
   'originalOverrideNameSelectionImplemented','ordinalNameComparisonImplemented','sourceEntryQualificationAdapterImplemented',
   'aliasRegistrationBeforeAdditionalRestrictionImplemented','additionalRestrictionRequiresExplicitBinding']:self.assertTrue(r[k],k)
  for k in ['nativeEntitySelectionImplemented','allTargetRestrictionsImplemented','casterInheritanceImplemented','modifierInstancesImplemented',
   'modifierStackAndLifeImplemented','callbacksAttachedToLiveEntities','nativeVisualEffectsImplemented','fullOriginalAddModifierRuntimeImplemented',
   'completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertFalse(r[k],k)
  for output in [CLIENT/'modifier-selection.json',ROOT/'modifier-selection.json']:
   with self.assertRaises(ValueError):selection.analyze(SimpleNamespace(client_dir=CLIENT,output=output))

if __name__=='__main__':unittest.main()
