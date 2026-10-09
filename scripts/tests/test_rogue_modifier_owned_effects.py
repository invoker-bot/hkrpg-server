"""核对角色变化、离场及不可选中撤销的普通调用和对象链。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_owned_effects',ROOT/'scripts/read-rogue-modifier-owned-effects.py')
modifier_owned_effects=importlib.util.module_from_spec(spec);spec.loader.exec_module(modifier_owned_effects)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in modifier_owned_effects.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierOwnedEffectsTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=modifier_owned_effects.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-owned-effects-python-evidence.json'))
 def test_complete_bodies_fields_and_external_method_identities_match(self):
  self.assertEqual((3,494,8,4,6),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedNativeParameterRecords']))
 def test_character_reference_clear_precedes_owner_and_flagged_removal(self):
  r=rows(510185);self.assertEqual(('mov','rdi, qword ptr [rsi + 0x148]'),r['0xe8110e5']);self.assertEqual(('je','0x18e811138'),r['0xe8110ef']);self.assertEqual(('mov','qword ptr [rsi + 0x148], 0'),r['0xe8110f1']);self.assertEqual(('mov','rcx, qword ptr [rsi + 0xd0]'),r['0xe8110fc']);self.assertEqual(('mov','r8d, ebx'),r['0xe81110b']);self.assertEqual(('call','0x18e7d1d60'),r['0xe81110e'])
 def test_character_recycles_old_reference_then_notifies_990_null_immediate(self):
  r=rows(510185);self.assertEqual(('mov','rcx, rdi'),r['0xe81111a']);self.assertEqual(('call','0x18b10ce60'),r['0xe81111d']);self.assertEqual(('mov','ecx, 0x3de'),r['0xe811122']);self.assertEqual(('xor','edx, edx'),r['0xe811127']);self.assertEqual(('mov','r8b, 1'),r['0xe811129']);self.assertEqual(('jmp','0x18d9be560'),r['0xe811133'])
 def test_departed_null_skip_clear_false_and_recycle_tail_are_matched(self):
  r=rows(510183);self.assertEqual(('je','0x18e810fd5'),r['0xe810f9c']);self.assertEqual(('mov','qword ptr [rsi + 0x168], 0'),r['0xe810f9e']);self.assertEqual(('mov','rcx, qword ptr [rsi + 0xd0]'),r['0xe810fa9']);self.assertEqual(('xor','r8d, r8d'),r['0xe810fb8']);self.assertEqual(('call','0x18e7d22a0'),r['0xe810fbb']);self.assertEqual(('jmp','0x18b10ce60'),r['0xe810fd0'])
 def test_unselectable_clear_and_four_required_object_links_are_matched(self):
  r=rows(510177);self.assertEqual(('mov','qword ptr [rdi + 0x188], 0'),r['0xe810a9e']);self.assertEqual(('mov','rax, qword ptr [rdi + 0xd0]'),r['0xe810aa9'])
  for rva,operand in [('0xe810ab5','rax, qword ptr [rax + 0x10]'),('0xe810abe','rax, qword ptr [rax + 0x88]'),('0xe810aca','rax, qword ptr [rax + 0x98]'),('0xe810ad6','rcx, qword ptr [rax + 0x18]')]:self.assertEqual(('mov',operand),r[rva])
  self.assertEqual(('xor','r8d, r8d'),r['0xe810ae2']);self.assertEqual(('call','0x18e800780'),r['0xe810ae5']);self.assertEqual(('jmp','0x18b10ce60'),r['0xe810afa'])
 def test_chain_field_types_are_world_and_battle_instance(self):
  types={t['typeIndex']:t for t in modifier_owned_effects.load_profile()['matchedTypes']}
  expected=[(54850,16,'_OwnerRef',53551),(53551,136,'_OwnerWorldRef',57810),(57810,152,'<BattleInstanceRef>k__BackingField',56038),(56038,24,'_TurnBasedGameMode',53601)]
  for index,offset,name,target in expected:
   field=types[index]['selectedInstanceFields'][0];self.assertEqual((offset,name,target),(field['instanceOffset'],field['name'],field['nativeType']['typeIndex']))
 def test_three_owned_parameter_field_identities_and_bound_methods_are_exact(self):
  p=modifier_owned_effects.load_profile();t=p['matchedTypes'][0];self.assertEqual([(208,54850),(328,54837),(360,54855),(392,54857)],[(f['instanceOffset'],f['nativeType']['typeIndex']) for f in t['selectedFields']]);self.assertEqual([503914,510602,510608,542871],[m['index'] for m in p['boundMethodIdentities']])
  params=p['parameterRecords'];self.assertEqual([(503914,0,18,54857),(503914,1,18,54857),(510602,0,18,54837),(510602,1,2,205),(510608,0,18,54855),(510608,1,18,54855)],sorted((r['methodIndex'],r['parameter']['ordinal'],r['record']['kind'],r['record']['payload']) for r in params))
 def test_ordinary_paths_and_cleanup_integration_are_complete_with_factories_and_client_unclaimed(self):
  for k in ['capturedParameterClearBeforeOwnerLookupImplemented','characterCallerFlagAndPostRecycleNotificationImplemented','departedNullReplacementThenRecycleImplemented','unselectableNullReplacementImplemented','unselectableEntityWorldBattleModeChainImplemented','successfulPrefixesWithoutReferenceRollbackImplemented','newCallbackReferencesPreservedImplemented','absentParameterSkipsOwnerAndWorldImplemented','sharedCleanupOwnerStateExplicit','actualTerminalTaskSequenceCleanupIntegrated','ordinaryFiniteOwnedWithdrawalsImplemented']:self.assertIs(True,self.report[k],k)
  for k in ['nativeOwnedParameterFactoriesInstalled','nativeComponentRemovalBodiesInstalled','nativePoolRecycleBodyInstalled','nativeGlobalNotificationBodyInstalled','poolRecycleRegisteredMethodIdentityMatched','actualWorldBindingInstalled','fullOriginalUnStackRuntimeImplemented','fullOriginalAddModifierRuntimeImplemented','modifierInstanceApplied','runtimeHotfixOverridesImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
