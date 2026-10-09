"""核对四类弱点栈移除、掩码聚合、活动状态优先级和通知收尾。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('character_weakness',ROOT/'scripts/read-rogue-character-weakness.py')
character_weakness=importlib.util.module_from_spec(spec);spec.loader.exec_module(character_weakness)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in character_weakness.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class CharacterWeaknessTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=character_weakness.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-character-weakness-python-evidence.json'))
 def test_complete_methods_fields_event_identity_generic_views_and_switch_match(self):
  self.assertEqual((10,3384,4,1,1,4),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedOperationSwitchTargets']))
 def test_operation_enum_and_four_jump_table_targets_are_independently_matched(self):
  p=character_weakness.load_profile();t=next(t for t in p['matchedTypes'] if t['typeIndex']==18219)
  self.assertEqual([('Override',0),('Protected',1),('Attach',2),('Secret',3)],[(f['name'],f['constant']['value']) for f in t['fields'] if 'constant' in f]);self.assertEqual(['0xe6119a6','0xe6119cc','0xe6119b9','0xe6119df'],p['operationSwitchTable']['targets']);self.assertEqual(('ja','0x18e611a24'),rows(534702)['0xe61198e'])
 def test_occupancy_is_cleared_before_stack_slot_then_category_and_active_refresh(self):
  for index,a,b,c,d in [(534715,'0xe611a95','0xe611aa8','0xe611ab3','0xe611ac1'),(534716,'0xe611bb5','0xe611bc8','0xe611bd3','0xe611be1'),(534717,'0xe611b25','0xe611b38','0xe611b43','0xe611b51'),(534718,'0xe611c45','0xe611c58','0xe611c63','0xe611c71')]:
   r=rows(index);self.assertEqual(('mov','byte ptr [rcx + rax + 0x20], 0'),r[a]);self.assertEqual(('mov','dword ptr [rcx + rax*4 + 0x20], 0'),r[b]);self.assertEqual('call',r[c][0]);self.assertEqual(('jmp','0x18e610850'),r[d])
 def test_override_empty_fallback_and_secret_intersection_are_matched(self):
  o=rows(534723);s=rows(534726);self.assertEqual(('test','cl, 1'),o['0xe6107d4']);self.assertEqual(('mov','eax, dword ptr [rsi + 0x84]'),o['0xe6107d9']);self.assertEqual(('mov','dword ptr [rsi + 0x80], eax'),o['0xe6107df']);self.assertEqual(('or','ebx, ebp'),s['0xe612aaa']);self.assertEqual(('and','ebx, dword ptr [rdi]'),s['0xe612ab5'])
 def test_active_mask_and_secret_protected_normal_view_priority_are_matched(self):
  r=rows(534727);self.assertEqual(('or','r15d, dword ptr [r12 + 0x80]'),r['0xe610881']);self.assertEqual(('not','eax'),r['0xe61088e']);self.assertEqual(('and','eax, r15d'),r['0xe610890']);self.assertEqual(('test','ebx, r15d'),r['0xe61093e']);self.assertEqual(('jle','0x18e6108d3'),r['0xe610941']);self.assertEqual(('mov','edi, dword ptr [r12 + 0x70]'),r['0xe610943']);self.assertEqual(('mov','edi, dword ptr [r12 + 0x7c]'),r['0xe610a70']);self.assertEqual(('mov','edi, dword ptr [r12 + 0x6c]'),r['0xe610b1c'])
 def test_notification_is_conditional_and_dirty_clear_follows_success(self):
  r=rows(534727);self.assertEqual(('cmp','byte ptr [r12 + 0x75], 0'),r['0xe610c50']);self.assertEqual(('mov','ecx, 0x453'),r['0xe610c76']);self.assertEqual(('mov','r8b, 1'),r['0xe610c7e']);self.assertEqual(('call','0x18d9be560'),r['0xe610c81']);self.assertEqual(('mov','byte ptr [r12 + 0x74], 0'),r['0xe610c86'])
 def test_finite_slots_masks_views_and_actual_stacked_withdrawal_are_implemented(self):
  for k in ['fourOperationDispatcherMatched','ordinaryStackSlotRemovalImplemented','occupancyBeforeStackWriteImplemented','fourOccupiedMaskRefreshesImplemented','occupiedZeroOverrideAndDefaultFallbackImplemented','secretMaskIntersectionImplemented','activeAndAllViewPriorityImplemented','capturedBaseAndCurrentFieldsImplemented','dirtyClearAfterSuccessfulNotificationImplemented','actualStackedEffectWithdrawalIntegrated','projectFiniteArraysAndCatalogBindingExplicit']:self.assertIs(True,self.report[k],k)
 def test_complete_weakness_factories_global_catalog_world_and_acceptance_are_unclaimed(self):
  for k in ['fullNativeCharacterWeaknessRuntimeImplemented','nativeWeaknessStackFactoriesInstalled','nativeGlobalDamageCatalogInstalled','nativeWeaknessEventFactoryInstalled','nativeEventDispatchBodyInstalled','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
