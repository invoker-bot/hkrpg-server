"""核对施加者缓存、容器订阅迁移、范围键和所属身份列表。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_caster_change',ROOT/'scripts/read-rogue-modifier-caster-change.py')
caster_change=importlib.util.module_from_spec(spec);spec.loader.exec_module(caster_change)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in caster_change.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierCasterChangeTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=caster_change.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-caster-change-python-evidence.json'))
 def test_methods_fields_components_and_callback_slot_match(self):
  self.assertEqual((3,1188,4,3,2,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericComponents','matchedListenerMethodSlots']))
 def test_cache_is_cleared_before_getter_and_scope_mask_checked_after_identity(self):
  r=rows(510191);self.assertEqual(('mov','qword ptr [rsi + 0x58], 0'),r['0xe80b26b']);self.assertEqual(('call','0x18b10c6a0'),r['0xe80b28d']);self.assertEqual(('cmp','rdi, rax'),r['0xe80b296']);self.assertEqual(('test','byte ptr [rsi + 0x2c8], 0x10'),r['0xe80b29f'])
 def test_remove_then_current_cache_reread_and_combine_order_match(self):
  r=rows(510191);self.assertEqual(('call','0x19c1a2450'),r['0xe80b312']);self.assertEqual(('mov','rcx, qword ptr [rsi + 0x58]'),r['0xe80b33d']);self.assertEqual(('call','0x18b10c6a0'),r['0xe80b353']);self.assertEqual(('call','0x19c1a2210'),r['0xe80b3c2'])
 def test_caster_callback_packs_key_in_high_word_and_scope_four(self):
  r=rows(510248);self.assertEqual(('shl','rdi, 0x20'),r['0xe8152e5']);self.assertEqual(('or','rdi, 4'),r['0xe8152e9']);self.assertEqual(('call','0x18e80ce20'),r['0xe8152fb'])
 def test_owner_registry_scans_reverse_reference_identity_then_appends(self):
  r=rows(526131);self.assertEqual(('lea','ecx, [rax - 1]'),r['0x15b9cc0d']);self.assertEqual(('cmp','qword ptr [rsi + rdx*8 + 0x18], r14'),r['0x15b9cc41']);self.assertEqual(('inc','dword ptr [rdi + 0x1c]'),r['0x15b9cc54']);self.assertEqual(('mov','qword ptr [rcx + rax*8 + 0x20], r14'),r['0x15b9cc6b'])
 def test_components_and_non_generic_caster_callback_identity_are_exact(self):
  p=caster_change.load_profile();self.assertEqual([57562,56967],[f['instRecords'][0]['argumentTypeIndex'] for f in p['genericMethodSlots']]);self.assertTrue(all(f['methodSpec']['methodDefinition']==502972 for f in p['genericMethodSlots']));self.assertEqual((510248,'_OnDynamicValueChange_Caster'),(p['listenerMethodSlot']['method']['index'],p['listenerMethodSlot']['method']['name']));self.assertEqual('0x9648a88',p['listenerMethodSlot']['slot'])
 def test_cache_real_root_migration_identity_and_shared_state_are_implemented(self):
  for k in ['finiteCasterCacheRefreshImplemented','rootNumericListenerMigrationImplemented','casterCallbackScopeFourForwardingImplemented','ownerModifierIdentityRegistrationImplemented','currentCasterContextAndAbilityRereadsImplemented','sharedReplacementAndSequenceIntegrationImplemented']:self.assertIs(True,self.report[k],k)
 def test_dynamic_range_world_factories_and_destruction_unsubscribe_are_not_claimed(self):
  for k in ['fullNativeCasterChangeRuntimeImplemented','generalDynamicRangeHandlerImplemented','nativeGameEntityComponentFactoriesInstalled','nativeAbilityOwnerRegistryExtractionInstalled','taskContextAbilityVirtualIdentityMatched','modifierDestructionListenerRemovalInstalled','projectedObserverMembershipImplemented','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
