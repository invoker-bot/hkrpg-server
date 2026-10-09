"""核对组件初始数组、目录静态数据、查询和释放的来源边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('character_weakness_lifecycle',ROOT/'scripts/read-rogue-character-weakness-lifecycle.py')
character_weakness_lifecycle=importlib.util.module_from_spec(spec);spec.loader.exec_module(character_weakness_lifecycle)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in character_weakness_lifecycle.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class CharacterWeaknessLifecycleTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=character_weakness_lifecycle.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-character-weakness-lifecycle-python-evidence.json'))
 def test_complete_bodies_helpers_leaves_fields_and_catalog_match(self):
  self.assertEqual((13,1418,9,7,2,3,2150,2,16,8,7,8),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedNativeLeafFragments','matchedNativeLeafBytes','matchedInitialCapacityReads','matchedCatalogValues','matchedMetadataUsageSwitchTargets']))
 def test_leaf_capacity_four_and_eight_constructor_reads_match_independently(self):
  p=character_weakness_lifecycle.load_profile();self.assertEqual(4,p['initialCapacity']);self.assertEqual('0x9357060',p['initialCapacityStaticSlot']);self.assertEqual(8,len(p['initialCapacityReads']));self.assertTrue(all(r['slot']=='0x9357060' for r in p['initialCapacityReads']))
  leaf=p['nativeLeafFragments'][0];self.assertEqual(534688,leaf['index']);self.assertEqual(['mov','ret'],[i['mnemonic'] for i in leaf['instructions']]);self.assertEqual('dword ptr [rip - 0x52bbe3a], 4',leaf['instructions'][0]['operand'])
 def test_constructor_preserves_list_owner_notification_and_array_install_order(self):
  r=rows(534687);self.assertEqual(('mov','qword ptr [rsi + 0x60], rbx'),r['0xe6104bb']);self.assertEqual(('mov','byte ptr [rsi + 0x75], 1'),r['0xe6104bf']);self.assertEqual(('mov','qword ptr [rsi + 0x48], rdi'),r['0xe6104c3'])
  writes=[b for a,b in r.values() if a=='mov' and b.startswith('qword ptr [rsi + ') and b.endswith(', rax')]
  self.assertEqual(['qword ptr [rsi + 0x18], rax','qword ptr [rsi + 0x28], rax','qword ptr [rsi + 0x38], rax','qword ptr [rsi + 0x50], rax','qword ptr [rsi + 0x20], rax','qword ptr [rsi + 0x58], rax','qword ptr [rsi + 0x30], rax','qword ptr [rsi + 0x10], rax'],writes)
 def test_catalog_is_seven_native_data_values_with_field_usage_kind_four(self):
  p=character_weakness_lifecycle.load_profile();c=p['catalogStaticData'];self.assertEqual([1,2,4,8,16,32,64],c['values']);self.assertEqual('0x96f3468',c['slot']);self.assertEqual(392761,c['fieldIndex']);self.assertEqual('0x17c6995',c['defaultDataOffset']);self.assertEqual('0x3d3339c',p['metadataUsageSwitchTable']['targets'][4])
  r=rows(534732);self.assertEqual(('mov','edx, 7'),r['0xb572ac5']);self.assertEqual(('call','0x183cd8820'),r['0xb572ae6']);self.assertEqual(('mov','qword ptr [rax + 0x332c0], rsi'),r['0xb572af2'])
 def test_queries_read_masks_collection_and_signed_positive_or_nonzero_conditions(self):
  for index,rva,offset in [(534691,'0xe610d31','0x84'),(534692,'0xe610d81','0x80'),(534693,'0xe610dd1','0x78'),(534694,'0xe610e11','0x70'),(534695,'0xe610e51','0x6c'),(534697,'0xe610ed1','0x68')]:self.assertEqual(('mov',f'eax, dword ptr [rsi + {offset}]'),rows(index)[rva])
  self.assertEqual(('mov','rax, qword ptr [rsi + 0x40]'),rows(534696)['0xe610e91']);self.assertEqual(('setg','al'),rows(534698)['0xe610f32']);self.assertEqual(('setg','al'),rows(534699)['0xe610fd2']);self.assertEqual(('setne','al'),rows(534700)['0xe611055']);self.assertEqual(534731,character_weakness_lifecycle.load_profile()['nativeLeafFragments'][1]['index'])
 def test_disposal_clears_owner_views_and_only_eight_array_fields(self):
  r=rows(534689);self.assertEqual(('mov','qword ptr [rsi + 0x48], 0'),r['0xe610601']);self.assertEqual(('mov','dword ptr [rax + 0x18], 0'),r['0xe610615'])
  self.assertEqual([('movups','xmmword ptr [rsi + 0x30], xmm0'),('movups','xmmword ptr [rsi + 0x20], xmm0'),('movups','xmmword ptr [rsi + 0x10], xmm0'),('movups','xmmword ptr [rsi + 0x50], xmm0')],[(a,b) for a,b in r.values() if a=='movups'])
 def test_factory_queries_disposal_and_actual_component_joint_paths_are_implemented(self):
  for k in ['ordinaryWeaknessComponentFactoryImplemented','eightIndependentFourSlotArraysImplemented','verifiedOrderedDamageCatalogFactoryImplemented','currentCatalogAndExplicitNotificationsAdapterImplemented','ordinaryMaskAndCollectionQueriesImplemented','positiveIntersectionAndNonzeroSecretQueriesImplemented','ownerAndCapturedViewClearBeforeArrayDisposalImplemented','specialMasksAndFlagsRetainedOnDisposal','actualCreateAddResetCopyUnstackSpecialDisposeIntegrationImplemented','nativeInitialWeaknessArrayFactoriesInstalled']:self.assertIs(True,self.report[k],k)
 def test_leaf_pe_bounds_and_world_notification_hotfix_acceptance_remain_unclaimed(self):
  p=character_weakness_lifecycle.load_profile();self.assertTrue(all('no PE Exception entry' in r['bounds'] for r in p['nativeLeafFragments']))
  for k in ['nativeGlobalDamageCatalogInstalled','nativeWeaknessEventFactoryInstalled','nativeEventDispatchBodyInstalled','runtimeHelpersRegisteredIdentityMatched','fullNativeCharacterWeaknessRuntimeImplemented','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
