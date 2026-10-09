"""核对弱点添加、空槽搜索、双数组扩容和实际掩码刷新入口。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('character_weakness_stack',ROOT/'scripts/read-rogue-character-weakness-stack.py')
character_weakness_stack=importlib.util.module_from_spec(spec);spec.loader.exec_module(character_weakness_stack)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in character_weakness_stack.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class CharacterWeaknessStackTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=character_weakness_stack.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-character-weakness-stack-python-evidence.json'))
 def test_complete_methods_fields_refresh_bindings_generic_views_and_three_switches_match(self):
  self.assertEqual((12,3507,4,5,1,12),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedOperationSwitchTargets']))
 def test_three_four_way_dispatch_tables_independently_match(self):
  p=character_weakness_stack.load_profile();self.assertEqual([534701,534708,534709],[t['methodIndex'] for t in p['operationSwitchTables']]);self.assertEqual(['0xe6110c6','0xe6110ec','0xe6110d9','0xe6110ff'],p['operationSwitchTables'][0]['targets']);self.assertEqual(['0xe61256b','0xe612587','0xe612579','0xe612595'],p['operationSwitchTables'][1]['targets']);self.assertEqual(['0xe61263f','0xe61264b','0xe612645','0xe612651'],p['operationSwitchTables'][2]['targets'])
 def test_search_captures_occupancy_and_first_false_or_minus_one(self):
  r=rows(534709);self.assertEqual(('mov','rcx, qword ptr [rsi]'),r['0xe612655']);self.assertEqual(('mov','rdx, qword ptr [rcx + 0x18]'),r['0xe61265d']);self.assertEqual(('mov','eax, 0xffffffff'),r['0xe612661']);self.assertEqual(('cmp','byte ptr [rcx + rdx + 0x20], 0'),r['0xe612670']);self.assertEqual(('mov','eax, edx'),r['0xe612681'])
 def test_full_search_reserves_once_and_searches_again_without_minimum_capacity(self):
  r=rows(534710);self.assertEqual(('call','0x18e612610'),r['0xe612719']);self.assertEqual(('cmp','eax, -1'),r['0xe61271e']);self.assertEqual(('call','0x18e612540'),r['0xe61272f']);self.assertEqual(('jmp','0x18e612610'),r['0xe61273f']);self.assertEqual(('add','ebx, ebx'),rows(534704)['0xe612263'])
 def test_two_copies_finish_before_publishing_grown_arrays(self):
  r=rows(534704);self.assertEqual(('call','0x19c077020'),r['0xe612299']);self.assertEqual(('call','0x19c077020'),r['0xe6122ae']);self.assertEqual(('mov','qword ptr [rsi + 0x18], rdi'),r['0xe6122b3']);self.assertEqual(('mov','qword ptr [rsi + 0x28], rbx'),r['0xe6122b7'])
 def test_slot_occupancy_precedes_mask_then_refresh_and_returned_index(self):
  r=rows(534711);self.assertEqual(('mov','byte ptr [rcx + rax + 0x20], 1'),r['0xe6112b8']);self.assertEqual(('mov','dword ptr [rcx + rax*4 + 0x20], edi'),r['0xe6112cb']);self.assertEqual(('call','0x18e6106e0'),r['0xe6112d2']);self.assertEqual(('call','0x18e610850'),r['0xe6112da']);self.assertEqual(('mov','eax, ebx'),r['0xe6112df'])
 def test_finite_search_growth_add_and_actual_unstack_receipt_are_implemented(self):
  for k in ['fourOperationAddReserveSearchDispatchMatched','capturedFirstAvailableSlotSearchImplemented','singleReserveAndSecondSearchImplemented','stackLengthDoubledForBothArraysImplemented','bothCopiesBeforePublishingArraysImplemented','occupancyBeforeMaskWriteImplemented','actualMaskAndActiveRefreshIntegrated','returnedIndexReusableByUnstackAndStackedReceipt','failurePrefixesAndZeroCapacityPreserved','projectFiniteGrowthBoundsExplicit']:self.assertIs(True,self.report[k],k)
 def test_complete_weakness_initial_factories_catalog_world_and_acceptance_are_unclaimed(self):
  for k in ['fullNativeCharacterWeaknessRuntimeImplemented','nativeInitialWeaknessArrayFactoriesInstalled','nativeGlobalDamageCatalogInstalled','nativeWeaknessEventFactoryInstalled','nativeEventDispatchBodyInstalled','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
