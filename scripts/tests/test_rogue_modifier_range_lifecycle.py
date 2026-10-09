"""核对三个作用域监听、销毁退订顺序和有效范围退出。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_range_lifecycle',ROOT/'scripts/read-rogue-modifier-range-lifecycle.py')
modifier_range_lifecycle=importlib.util.module_from_spec(spec);spec.loader.exec_module(modifier_range_lifecycle)
def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in modifier_range_lifecycle.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierRangeLifecycleTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=modifier_range_lifecycle.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-range-lifecycle-python-evidence.json'))
 def test_methods_fields_runtime_components_and_listener_slots_match(self):
  self.assertEqual((4,3720,5,3,1,1,237,1,3),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedNativeRuntimeFunctions','matchedNativeRuntimeBytes','matchedGenericComponents','matchedListenerMethodSlots']))
 def test_modifier_owner_callbacks_pack_scope_two_and_five(self):
  m=rows(510246);o=rows(510247);self.assertEqual(('or','rdi, 2'),m['0xe8151c9']);self.assertEqual(('or','rdi, 5'),o['0xe815259']);self.assertEqual(('call','0x18e80ce20'),m['0xe8151db']);self.assertEqual(('call','0x18e80ce20'),o['0xe81526b'])
 def test_retirement_masks_and_three_remove_calls_follow_owner_caster_modifier_order(self):
  r=rows(510243);self.assertEqual(('test','al, 0x20'),r['0xe811dc8']);self.assertEqual(('test','al, 0x10'),r['0xe811e74']);self.assertEqual(('test','byte ptr [r14 + 0x2c8], 4'),r['0xe811f2c']);self.assertTrue(all(r[a]==('call','0x19c1a2450') for a in ['0xe811e4f','0xe811f0e','0xe811f83']));self.assertEqual(('mov','dword ptr [r14 + 0x2c8], 0'),r['0xe811fa1'])
 def test_owner_root_is_required_but_caster_root_can_be_absent(self):
  r=rows(510243);self.assertEqual(('je','0x18e812194'),r['0xe811e09']);self.assertEqual(('je','0x18e811f2c'),r['0xe811ecc']);self.assertEqual(('mov','qword ptr [r14 + 0x58], rax'),r['0xe811ea7']);self.assertEqual(('call','0x18b10c6a0'),r['0xe811e9f'])
 def test_active_exit_does_not_change_range_state_or_counter_and_clears_dictionary_afterwards(self):
  r=rows(510243);self.assertEqual(('cmp','byte ptr [rax + 0x33], 0'),r['0xe8120b4']);self.assertEqual(('mov','rdi, qword ptr [rax + 0x18]'),r['0xe8120ba']);self.assertEqual(('call','0x18b1ea7f0'),r['0xe812083']);self.assertEqual(('mov','qword ptr [r14 + 0x1c0], 0'),r['0xe8122e6']);section=[v for a,v in r.items() if 0xe812060<=int(a,16)<=0xe8120d8];self.assertFalse(any(v[0] in ['inc','dec','mov'] and ('+ 0x31]' in v[1] or '+ 0x33]' in v[1]) for v in section))
 def test_values_enumerator_checks_version_even_after_last_group(self):
  p=modifier_range_lifecycle.load_profile();f=p['nativeRuntimeFunctions'][0];r={i['rva']:(i['mnemonic'],i['operand']) for i in f['instructions']};self.assertEqual(('mov','eax, dword ptr [rcx + 0xc]'),r['0x1aa3b0c1']);self.assertEqual(('cmp','eax, dword ptr [rdx + 0x24]'),r['0x1aa3b0c4']);self.assertEqual(('jne','0x19aa3b16f'),r['0x1aa3b0c7']);self.assertIs(False,f['registeredMethodIdentityMatched']);self.assertEqual([510247,510248,510246],[s['method']['index'] for s in p['listenerMethodSlots']])
 def test_scoped_root_retirement_and_finite_versioned_exits_are_implemented(self):
  for k in ['modifierOwnerAndCasterScopesImplemented','orderedRootListenerRetirementImplemented','currentMaskAndCachedCasterRereadsImplemented','lastMatchingDuplicateListenerRemovalImplemented','activeRangeExitWithoutCounterMutationImplemented','capturedDictionaryVersionCheckImplemented','currentRangeListCountImplemented','dictionaryClearedAfterSuccessfulExitsImplemented','projectGroupVersionModelExplicit','actualRootNotificationRetirementIntegrationImplemented']:self.assertIs(True,self.report[k],k)
 def test_complete_destruction_native_world_factories_and_general_dictionary_are_not_claimed(self):
  for k in ['fullNativeModifierDestroyRuntimeImplemented','nativeListenerRegistrationFactoryInstalled','nativeNumericComponentFactoriesInstalled','generalNativeDictionaryMutationRuntimeImplemented','nativeOwnerAbilityEntityExtractionInstalled','nativeSequenceExecutorBodyInstalled','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
