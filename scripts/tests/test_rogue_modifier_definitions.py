"""原始效果定义、零叠层增量和回调查找的静态来源验证。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_defs',ROOT/'scripts/read-rogue-modifier-definitions.py')
defs=importlib.util.module_from_spec(spec);spec.loader.exec_module(defs)


def rows(index):return {r['rva']:(r['mnemonic'],r['operand']) for m in defs.load_profile()['nativeMethods'] if m['index']==index for r in m['instructions']}


class ModifierDefinitionsTest(unittest.TestCase):
 def test_seven_complete_native_methods_and_all_declared_inherited_fields_match(self):
  self.assertEqual((7,1755),defs.verify_native(CLIENT))

 def test_constructor_writes_minus_one_count_lifetime_unique_and_phase2_end(self):
  r=rows(110219);p=defs.load_profile()
  self.assertEqual(('movabs','rax, 0x63ffffffff'),r['0x1dc35058'])
  self.assertEqual(('mov','qword ptr [rcx + 0x38], rax'),r['0x1dc35062'])
  self.assertEqual(('movabs','rax, 0x200000001'),r['0x1dc35088'])
  self.assertEqual(('mov','byte ptr [rsi + 0x64], 1'),r['0x1dc35096'])
  self.assertEqual(('movabs','rax, 0x1ffffffff'),r['0x1dc3509a'])
  self.assertEqual({'Count':-1,'Stacking':1,'LifeTime':-1,'MaxLayer':99,'LayerAddWhenStackRaw':0,
   'WorkingTurn':1,'LifeStepMoment':2,'ApplyBehaviorFlagBindEffects':True},p['constructorDefaults'])
  enums={t['name']:{f['name']:f['constant']['value'] for f in t['fields'] if 'value' in f.get('constant',{})}
   for t in p['definitionTypes']}
  self.assertEqual((1,1,2),(enums['ModifierStacking']['Unique'],enums['ModifierWorking']['OwnerTurn'],enums['ModifierLifeStepMoment']['ModifierPhase2End']))

 def test_layer_increment_uses_the_same_static_slot_initialized_to_raw_zero(self):
  ctor=rows(110219);static=rows(74677);p=defs.load_profile()
  self.assertEqual(('mov','rax, qword ptr [rip - 0x148f1bd5]'),ctor['0x1dc35076'])
  self.assertEqual(('mov','rax, qword ptr [rax + 0x21608]'),ctor['0x1dc3507d'])
  self.assertEqual(('mov','qword ptr [rax + 0x10], 0'),static['0x1d5fae40'])
  self.assertEqual(('mov','rcx, qword ptr [rip - 0x142b79a7]'),static['0x1d5fae48'])
  self.assertEqual(('mov','qword ptr [rcx + 0x21608], rax'),static['0x1d5fae4f'])
  self.assertEqual(int(p['layerDefaultStaticGlobalSlotRva'],16),0x1dc35076+7-0x148f1bd5)
  self.assertEqual(int(p['layerDefaultStaticGlobalSlotRva'],16),0x1d5fae48+7-0x142b79a7)

 def test_local_nonnull_definition_returns_before_global_lookup_and_error(self):
  r=rows(508768)
  self.assertEqual(('mov','rax, qword ptr [rdi + 0xb8]'),r['0x15c5eb1f'])
  self.assertEqual(('mov','rbx, qword ptr [rax + 0x58]'),r['0x15c5eb2f'])
  self.assertEqual(('jne','0x195c5ebf8'),r['0x15c5eb97'])
  self.assertEqual(('call','0x18e4ae7e0'),r['0x15c5ebac'])
  self.assertEqual(('jne','0x195c5ebf8'),r['0x15c5ebb4'])
  self.assertEqual(('call','0x19dd6b050'),r['0x15c5ebf1'])
  ability=next(t for t in defs.load_profile()['definitionTypes'] if t['typeIndex']==55823)
  self.assertEqual(18197,next(f['nativeType']['typeIndex'] for f in ability['fields'] if f.get('instanceOffset')==184))

 def test_callback_info_and_program_stop_at_first_matching_event(self):
  info=rows(110227);program=rows(110228);has=rows(110229)
  self.assertEqual(('je','0x19dc362a9'),info['0x1dc3629d'])
  self.assertEqual(('je','0x19dc36304'),program['0x1dc362ee'])
  self.assertEqual(('mov','rax, qword ptr [r10 + 0x18]'),program['0x1dc36304'])
  self.assertEqual(('je','0x19dc3635e'),has['0x1dc36350'])

 def test_fresh_full_records_preserve_all_nine_local_lists_and_four_global_definitions(self):
  p=defs.load_profile();local,global_defs=defs.source_definitions(CLIENT)
  self.assertEqual((p['sourceLocalDefinitions'],p['sourceGlobalDefinitions']),(local,global_defs))
  self.assertEqual(8594,p['breedingSourceRecord']['length'])

 def test_five_local_marker_definitions_have_no_explicit_callbacks_or_other_fields(self):
  p=defs.load_profile();entries=[e for s in p['sourceLocalDefinitions'] if s['Modifiers'] is not None for e in s['Modifiers']['entries']]
  self.assertEqual(5,len(entries))
  for _,config in entries:self.assertEqual({'$type','$mask','$offset'},set(config));self.assertEqual('0x0',config['$mask'])

 def test_original_breeding_events_keep_source_aliases_and_absent_versus_empty_branches(self):
  entries=dict(defs.load_profile()['sourceGlobalDefinitions']['entries']);prefix='MAvatar_RogueBattleevent127_AimTarget'
  callbacks=[c for d in entries.values() for c in d['_CallbackList']]
  self.assertEqual([57,172,95,172,173],[c['Event'] for c in callbacks])
  events=next(t for t in defs.load_profile()['definitionTypes'] if t['name']=='TurnBasedModifierEvent')
  enum={f['constant']['value']:f['name'] for f in events['fields'] if 'value' in f.get('constant',{})}
  self.assertEqual(['OnListenCharacterCreate','OnAimAtTargetsRefresh_CL','OnBeforeSkillUse','OnListenAimAtTargetsRefresh_CL'],[enum[k] for k in [57,172,95,173]])
  first=entries[prefix]['_CallbackList'][0]['CallbackConfig'][0]
  self.assertEqual((526,'ParamEntity',1),(first['Predicate']['$variantId'],first['Predicate']['TargetType']['Alias'],first['Predicate']['Team']))
  self.assertNotIn('FailedTaskList',first)
  sub=entries[prefix+'_Sub']['_CallbackList'][0]['CallbackConfig'][0]
  self.assertEqual([],sub['SuccessTaskList']);self.assertEqual(273,sub['Predicate']['$variantId'])
  self.assertEqual('ModifierOwnerEntity',sub['FailedTaskList'][0]['TargetType']['Alias'])

 def test_report_opens_only_finite_catalogue_lookup_and_bound_callback_compilation(self):
  r=defs.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-definitions-python-evidence.json'))
  self.assertTrue(r['passed']);self.assertEqual((7,1755,9,5,4,5,7,8594),tuple(r[k] for k in [
   'matchedNativeMethods','matchedNativeBytes','sourceClassicEvents','sourceLocalDefinitions','sourceGlobalDefinitions',
   'sourceGlobalCallbacks','sourceCallbackTasksIncludingBranches','sourceGlobalAbilityBytes']))
  for k in ['finiteOriginalDefinitionCatalogImplemented','localBeforeBoundGlobalLookupImplemented',
   'missingDiagnosticAfterBothLookupsImplemented','sourceCallbackProgramsPreserved','firstMatchingCallbackOnly',
   'constructorDefaultWritesMatched','layerDefaultZeroMatched','absentVersusEmptyCallbackProgramsPreserved',
   'explicitCallbackTaskFactoryImplemented']:self.assertTrue(r[k],k)
  for k in ['globalLazyCacheImplemented','referenceModifierResolutionImplemented','nativePredicateEvaluationImplemented',
   'nativeVisualEffectsImplemented','modifierEligibilityImplemented','casterInheritanceImplemented','modifierInstancesCreated',
   'stackingAndLifetimeImplemented','callbacksAttachedToLiveEntities','fullOriginalAddModifierRuntimeImplemented',
   'liveBattleContextAttached','clientGameplayVerified','completeResonanceRuntimeImplemented','jsonInstalled',
   'clientExecutableStarted','clientModified']:self.assertFalse(r[k],k)

 def test_report_cannot_overwrite_client_or_operator_files(self):
  for output in [CLIENT/'definitions.json',ROOT/'definitions.json']:
   with self.assertRaises(ValueError):defs.analyze(SimpleNamespace(client_dir=CLIENT,output=output))


if __name__=='__main__':unittest.main()
