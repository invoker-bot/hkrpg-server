"""三种施加者模式、内置中转配置及原始重复读取路径的静态核对。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_caster',ROOT/'scripts/read-rogue-modifier-caster.py')
caster=importlib.util.module_from_spec(spec);spec.loader.exec_module(caster)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in caster.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierCasterTest(unittest.TestCase):
 def test_six_complete_methods_provider_and_exact_field_layouts_match_fixed_client(self):
  self.assertEqual((6,8490,1,461),caster.verify_native(CLIENT))

 def test_three_modes_are_real_enum_constants_and_unknown_or_null_target_paths_return_null(self):
  p=caster.load_profile();t=next(t for t in p['matchedTypes'] if t['typeIndex']==24679)
  self.assertEqual([('Inherit',0),('CasterSelf',1),('TargetSelf',2)],[(f['name'],f['constant']['value']) for f in t['fields'] if f['isStatic']])
  r=rows(510000)
  self.assertEqual(('mov','eax, dword ptr [rax + 0x80]'),r['0x165480c7'])
  self.assertEqual(('cmp','eax, 2'),r['0x165480cd']);self.assertEqual(('cmp','eax, 1'),r['0x165480d2'])
  self.assertEqual(('jne','0x196548155'),r['0x165480dd'])
  self.assertEqual(('test','rdi, rdi'),r['0x16548144']);self.assertEqual(('xor','eax, eax'),r['0x16548155'])

 def test_inherit_uses_owner_ability_virtual_slot_and_casts_it_to_the_actual_turn_based_class(self):
  p=caster.load_profile();r=rows(510000)
  self.assertEqual(('mov','rbx, qword ptr [rcx + 0x120]'),r['0x165480ef'])
  self.assertEqual(('call','rbx'),r['0x16548108'])
  self.assertEqual(('je','0x196548194'),r['0x1654810d'])
  self.assertEqual(('call','0x183cc82a0'),r['0x1654813f'])
  self.assertEqual((55823,'FABMDCOFHCG'),tuple(p['abilityClassSlots'][0][k] for k in ['typeIndex','typeName']))
  self.assertEqual({509791:10,509799:12},{v['index']:v['decodedSlot'] for v in p['virtualMethodSlots']})

 def test_caster_self_uses_context_owner_entity_and_target_self_uses_the_original_target(self):
  r=rows(510000)
  self.assertEqual(('mov','rsi, qword ptr [rsi + 0x20]'),r['0x1654815f'])
  self.assertEqual(('mov','rdi, qword ptr [rcx + 0x110]'),r['0x1654816f'])
  self.assertEqual(('call','rdi'),r['0x16548188'])
  self.assertEqual(('mov','rcx, rdi'),r['0x16548150'])
  self.assertEqual(('call','0x18b10cc50'),r['0x165481b9'])
  self.assertEqual(('jmp','0x195c61a70'),r['0x165481db'])

 def test_fallback_provider_rereads_owner_ability_before_following_ability_component_to_entity(self):
  p=caster.load_profile();m=p['runtimeProviders'][0]
  self.assertEqual(('0xb10c6a0','0xb10c86d'),(m['rva'],m['endRva']))
  r={i['rva']:(i['mnemonic'],i['operand']) for i in m['instructions']}
  self.assertEqual(('call','rdi'),r['0xb10c6d2']);self.assertEqual(('call','rdi'),r['0xb10c6f5'])
  self.assertEqual(('mov','rax, qword ptr [rax + 0x68]'),r['0xb10c700'])
  self.assertEqual(('mov','rax, qword ptr [rax + 0x10]'),r['0xb10c70d'])
  self.assertEqual(('mov','rax, qword ptr [rcx + 0x110]'),r['0xb10c718'])
  self.assertEqual(('jmp','rax'),r['0xb10c733'])
  self.assertEqual(('call','0x18b10c6a0'),rows(510000)['0x165481a1'])

 def test_builtin_initializer_only_assigns_the_original_name_and_caches_that_configuration(self):
  p=caster.load_profile();r=rows(508748)
  self.assertEqual({'Name':'INTERNAL_ModifierCasterTransit'},p['builtinTransitConfig'])
  self.assertEqual((9642,'INTERNAL_ModifierCasterTransit'),tuple(p['transitNameSlots'][0][k] for k in ['literalIndex','value']))
  self.assertEqual((18197,'TurnBasedAbilityConfig'),tuple(p['transitConfigClassSlots'][0][k] for k in ['typeIndex','typeName']))
  self.assertEqual(('mov','qword ptr [rax + 0x10], rcx'),r['0x15c6353d'])
  self.assertEqual(('mov','qword ptr [rcx + 0x11bd8], rax'),r['0x15c63548'])

 def test_transit_lookup_uses_that_cached_config_name_and_creates_only_when_missing(self):
  r=rows(508770)
  self.assertEqual(('mov','rax, qword ptr [rax + 0x11bd8]'),r['0x15c61a9c'])
  self.assertEqual(('mov','rdx, qword ptr [rax + 0x10]'),r['0x15c61ab5'])
  self.assertEqual(('call','0x18e7b1e90'),r['0x15c61abc'])
  self.assertEqual(('je','0x195c61acc'),r['0x15c61ac4'])
  self.assertEqual(('xor','r8d, r8d'),r['0x15c61aed']);self.assertEqual(('xor','r9d, r9d'),r['0x15c61af0'])
  self.assertEqual(('jmp','0x18e7b1be0'),r['0x15c61af8'])
  self.assertEqual(('call','0x183cc82a0'),rows(510413)['0xe7b1eeb'])
  self.assertEqual(('call','0x1956589f0'),rows(510411)['0xe7b1c2f'])

 def test_original_six_tasks_omit_inherit_caster_and_the_entry_checks_selected_instance_before_application(self):
  p=caster.load_profile();self.assertEqual(p['sourceModifierCommands'],caster.selection.entry.source_commands(CLIENT))
  self.assertEqual(6,len(p['sourceModifierCommands']))
  for source in p['sourceModifierCommands']:self.assertNotIn('InheritCaster',source['command'])
  r=rows(509996)
  self.assertEqual(('call','0x1965480a0'),r['0x16546a64'])
  self.assertEqual(('test','rax, rax'),r['0x16546a69'])
  self.assertEqual(('je','0x196547b78'),r['0x16546a6c'])

 def test_report_distinguishes_selection_from_actual_component_instance_and_visual_runtime(self):
  r=caster.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-caster-python-evidence.json'))
  self.assertTrue(r['passed']);self.assertEqual((6,8490,1,461,6,2,6),tuple(r[k] for k in ['matchedCompleteNativeMethods',
   'matchedCompleteNativeBytes','matchedCompleteRuntimeProviders','matchedCompleteRuntimeProviderBytes','matchedFieldTypes',
   'matchedVirtualGetterSlots','sourceModifierCommands']))
  self.assertEqual({'Name':'INTERNAL_ModifierCasterTransit'},r['transitConfig'])
  for k in ['finiteCasterSelectionImplemented','nativeInheritanceModesPreserved','repeatedOwnerAbilityReadsPreserved',
   'defaultTransitNameResolvedFromNativeInitializer','defaultTransitFindBeforeCreateImplemented','sourceEntryCasterAdapterImplemented',
   'abilityComponentAndInstanceCreationRequireExplicitBindings']:self.assertTrue(r[k],k)
  for k in ['nativeEntitySelectionImplemented','nativeAbilityComponentFactoryImplemented','nativeAbilityInstanceCreationImplemented',
   'modifierInstancesImplemented','modifierStackAndLifeImplemented','callbacksAttachedToLiveEntities','nativeVisualEffectsImplemented',
   'fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled',
   'clientExecutableStarted','clientModified']:self.assertFalse(r[k],k)

 def test_output_locations_cannot_overwrite_operator_data_or_client(self):
  for output in [CLIENT/'modifier-caster.json',ROOT/'modifier-caster.json']:
   with self.assertRaises(ValueError):caster.analyze(SimpleNamespace(client_dir=CLIENT,output=output))

if __name__=='__main__':unittest.main()
