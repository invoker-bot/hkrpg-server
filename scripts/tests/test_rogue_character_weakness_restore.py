"""核对普通弱点重置、复制辅助体、参数类型与特殊列表更新。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('character_weakness_restore',ROOT/'scripts/read-rogue-character-weakness-restore.py')
character_weakness_restore=importlib.util.module_from_spec(spec);spec.loader.exec_module(character_weakness_restore)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in character_weakness_restore.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}
def runtime(rva):return {i['rva']:(i['mnemonic'],i['operand']) for m in character_weakness_restore.load_profile()['runtimeMethods'] if m['rva']==rva for i in m['instructions']}

class CharacterWeaknessRestoreTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=character_weakness_restore.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-character-weakness-restore-python-evidence.json'))
 def test_complete_bodies_fields_parameters_generic_and_switch_match(self):
  self.assertEqual((9,3291,8,5,2,4,2,757,5),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedOperationSwitchTargets','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedNativeParameterRecords']))
 def test_four_reset_targets_and_occupied_mask_removal_match(self):
  self.assertEqual(['0xe611cdf','0xe611d03','0xe611d14','0xe611d25'],character_weakness_restore.load_profile()['operationSwitchTable']['targets'])
  for index,start in [(534719,0xe611d80),(534720,0xe611eb0),(534721,0xe611fe0),(534722,0xe612110)]:
   r=rows(index);self.assertEqual(('not','r15d'),r[hex(start+47)]);self.assertEqual(('and','dword ptr [rbx + rbp*4 + 0x20], r15d'),r[hex(start+64)])
   self.assertFalse(any(a=='mov' and b.startswith('byte ptr [') and b.endswith(', 0') for a,b in r.values()))
 def test_copy_preserves_native_mask_order_and_two_separate_boolean_bits(self):
  r=rows(534728);self.assertEqual(('mov','rax, qword ptr [rdi + 0x80]'),r['0xe612b83']);self.assertEqual(('mov','qword ptr [rsi + 0x80], rax'),r['0xe612b8a']);self.assertEqual(('test','bl, 1'),r['0xe612bfb']);self.assertEqual(('test','ebx, 0x100'),r['0xe612c38'])
  options=next(t for t in character_weakness_restore.load_profile()['matchedTypes'] if t['typeIndex']==57730)
  self.assertEqual([16,17],[f['instanceOffset'] for f in options['fields']]);self.assertTrue(all(f['nativeType']['kind']==2 for f in options['fields']))
 def test_copy_helpers_reuse_and_zero_tail_or_clone_before_publication(self):
  a=runtime('0x16f2f500');b=runtime('0x16f2f3a0')
  self.assertEqual(('cmp','ecx, edx'),a['0x16f2f532']);self.assertEqual(('mov','ebx, 0'),a['0x16f2f595']);self.assertEqual(('mov','qword ptr [r15], r12'),a['0x16f2f61a']);self.assertEqual(('call','0x183d1a680'),a['0x16f2f549'])
  self.assertEqual(('xor','edi, edi'),b['0x16f2f410']);self.assertEqual(('mov','byte ptr [rax + rsi + 0x20], dil'),b['0x16f2f412']);self.assertEqual(('mov','qword ptr [r14], rbx'),b['0x16f2f4d9'])
 def test_default_array_type_null_reduction_and_actual_refresh_are_matched(self):
  p=character_weakness_restore.load_profile();array=next(r for r in p['parameterRecords'] if r['methodIndex']==534690)['record'];helper=next(r for r in p['parameterRecords'] if r['methodIndex']==534737)['record']
  self.assertEqual((29,25493),(array['kind'],array['payload']));self.assertEqual(('IList`1','AttackDamageType'),(helper['genericName'],helper['args'][0]['name']))
  r=rows(534737);self.assertEqual(('test','rsi, rsi'),r['0xb5724c8']);self.assertEqual(('je','0x18b5727cf'),r['0xb5724cb']);self.assertEqual(('or','dword ptr [rsp + 0x3c], ebp'),r['0xb5726e6'])
  r=rows(534690);self.assertEqual(('call','0x18b5724a0'),r['0xe610688']);self.assertEqual(('mov','dword ptr [rsi + 0x84], eax'),r['0xe61068d']);self.assertEqual(('call','0x18e6106e0'),r['0xe610696']);self.assertEqual(('jmp','0x18e610850'),r['0xe6106a4'])
 def test_copy_final_dirty_active_clear_view_clear_then_all_mask_clear_match(self):
  r=rows(534728);self.assertEqual(('mov','byte ptr [rsi + 0x74], 1'),r['0xe612c78']);self.assertEqual(('mov','dword ptr [rsi + 0x6c], 0'),r['0xe612c7c']);self.assertEqual(('mov','dword ptr [rax + 0x18], 0'),r['0xe612c8f']);self.assertEqual(('mov','dword ptr [rsi + 0x68], 0'),r['0xe612c96']);self.assertEqual(('jmp','0x18e610850'),r['0xe612ca7'])
 def test_special_list_generic_event_fields_and_current_rereads_match(self):
  p=character_weakness_restore.load_profile();f=next(f for f in p['genericFields'] if f['field']['instanceOffset']==96)
  self.assertEqual(('List`1','MonsterWeaknessSpecialType'),(f['record']['genericName'],f['record']['args'][0]['name']))
  event=next(t for t in p['matchedTypes'] if t['typeIndex']==55554);self.assertEqual([16,24,28],[f['instanceOffset'] for f in event['fields']]);r=rows(534730)
  self.assertEqual(('cmp','byte ptr [rsi + 0x18], 0'),r['0xe612d3f']);self.assertEqual(('mov','rdi, qword ptr [rdi + 0x60]'),r['0xe612d65']);self.assertEqual(('mov','esi, dword ptr [rsi + 0x1c]'),r['0xe612d72']);self.assertEqual(('mov','rcx, qword ptr [rdi + 0x60]'),r['0xe612dbd']);self.assertEqual(('jmp','0x1980594a0'),r['0xe612dda'])
 def test_finite_paths_are_implemented_and_factory_world_acceptance_remain_unclaimed(self):
  for k in ['ordinaryFourCategoryResetImplemented','occupiedMaskRemovalWithoutFreeingSlotsImplemented','actualRefreshAfterResetAndDefaultIntegrated','nativeDefaultDamageArrayReductionImplemented','copyMaskOrderAndSeparateBooleanOptionsImplemented','targetArrayReuseTailZeroAndIndependentCloneImplemented','copyFinalDirtyAndDoubleViewClearImplemented','copyFailurePrefixesPreserved','specialListUniquenessAndFirstRemovalImplemented','specialCurrentListAndEventRereadsImplemented','actualWeaknessStateAndStackIntegrationImplemented','projectFiniteCollectionsExplicit']:self.assertIs(True,self.report[k],k)
  for k in ['runtimeCopyHelperRegisteredIdentityMatched','fullDefaultDamageCollectionHelperImplemented','fullNativeCharacterWeaknessRuntimeImplemented','nativeInitialWeaknessArrayFactoriesInstalled','nativeGlobalDamageCatalogInstalled','nativeWeaknessEventFactoryInstalled','nativeEventDispatchBodyInstalled','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
