"""核对叠层弱点、队伍点数、装饰回调的撤销与清理顺序。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_stacked_effects',ROOT/'scripts/read-rogue-modifier-stacked-effects.py')
modifier_stacked_effects=importlib.util.module_from_spec(spec);spec.loader.exec_module(modifier_stacked_effects)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in modifier_stacked_effects.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierStackedEffectsTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=modifier_stacked_effects.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-stacked-effects-python-evidence.json'))
 def test_complete_methods_fields_bound_removals_and_generic_records_match(self):
  self.assertEqual((3,668,7,3,3),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments']))
 def test_weakness_null_throws_but_other_initial_null_lists_return(self):
  w=rows(510263);b=rows(510264);d=rows(510265)
  self.assertEqual(('je','0x18e81021c'),w['0xe8101b1']);self.assertEqual(('call','0x183cc8050'),w['0xe81021c']);self.assertEqual(('je','0x18e815f41'),b['0xe815ed1']);self.assertEqual(('je','0x18e81602c'),d['0xe815fb0'])
 def test_current_list_fields_are_reread_after_counts_and_callbacks(self):
  for index,field,afterCount,afterCall in [(510263,'0x1e0','0xe8101c7','0xe810210'),(510264,'0x1f8','0xe815ef5','0xe815ee2'),(510265,'0xe0','0xe815fd5','0xe815fc2')]:
   r=rows(index);self.assertIn(field,r[afterCount][1]);self.assertIn(field,r[afterCall][1]);self.assertEqual('mov',r[afterCount][0]);self.assertEqual('mov',r[afterCall][0])
 def test_weakness_uses_component_entity_and_captured_operation_count(self):
  r=rows(510263);self.assertEqual(('mov','rcx, qword ptr [rax + 0x220]'),r['0xe8101ef']);self.assertEqual(('mov','rdx, qword ptr [rax + 0x10]'),r['0xe810205']);self.assertEqual(('mov','r8d, dword ptr [rsp + 0x28]'),r['0xe8101fb']);self.assertEqual(('mov','r9d, dword ptr [rsp + 0x2c]'),r['0xe810200']);self.assertEqual(('call','0x18e611970'),r['0xe810209'])
 def test_bp_and_boost_skip_null_team_but_require_boost_module(self):
  b=rows(510264);d=rows(510265);self.assertEqual(('je','0x18e815ee0'),b['0xe815f1b']);self.assertEqual(('je','0x18e815fc0'),d['0xe815ff2']);self.assertEqual(('mov','rcx, qword ptr [rax + 0x20]'),b['0xe815f1d']);self.assertEqual(('mov','rcx, qword ptr [rax + 0x20]'),d['0xe815ff4']);self.assertEqual(('call','0x18b665b70'),b['0xe815f2b']);self.assertEqual(('mov','rdx, rsi'),d['0xe815ffd']);self.assertEqual(('call','0x18b6641d0'),d['0xe816000'])
 def test_boost_clear_then_rereads_current_list_before_trim(self):
  r=rows(510265);self.assertEqual(('call','0x19a6a50f0'),r['0xe81600e']);self.assertEqual(('mov','rcx, qword ptr [rsi + 0xe0]'),r['0xe816013']);self.assertEqual(('je','0x18e816068'),r['0xe81601d']);self.assertEqual(('jmp','0x19a6a8b60'),r['0xe816027'])
 def test_current_lists_nullable_teams_clear_prefix_and_shared_stack_are_implemented(self):
  for k in ['weaknessListRequiredOtherListsOptionalImplemented','currentListCountAndElementRereadsImplemented','signedEntryBitsPreserved','nullableTeamComponentSkipped','requiredRemovalComponentsImplemented','successfulPrefixAndClearOrderImplemented','boostClearThenCurrentTrimImplemented','sharedStackEventAndModifierIdentityIntegrated','projectFiniteContainersExplicit']:self.assertIs(True,self.report[k],k)
 def test_native_pools_component_bodies_properties_world_and_acceptance_are_unclaimed(self):
  for k in ['nativePoolablePooledListFactoryInstalled','nativePooledListGetterIdentitiesMatched','nativeBoostPointModuleRemovalBodiesInstalled','nativeCharacterWeaknessRemovalBodyInstalled','fullNativeUnstackRuntimeImplemented','fullNativePropertyWithdrawalRuntimeImplemented','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
