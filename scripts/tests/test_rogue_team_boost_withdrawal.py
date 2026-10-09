"""核对附加战技点链表、增益条目和子实体列表的普通撤销。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('team_boost_withdrawal',ROOT/'scripts/read-rogue-team-boost-withdrawal.py')
team_boost_withdrawal=importlib.util.module_from_spec(spec);spec.loader.exec_module(team_boost_withdrawal)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in team_boost_withdrawal.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}
def runtime():return {i['rva']:(i['mnemonic'],i['operand']) for i in team_boost_withdrawal.load_profile()['runtimeMethods'][0]['instructions']}

class TeamBoostWithdrawalTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=team_boost_withdrawal.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-team-boost-withdrawal-python-evidence.json'))
 def test_complete_methods_fields_bound_outer_methods_generic_and_counter_match(self):
  self.assertEqual((3,741,3,2,3,1,219,4),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCounterLiteralValues']))
 def test_first_registration_match_requires_low_presence_bit_and_keeps_signed_id(self):
  r=rows(524903);self.assertEqual(('mov','rcx, qword ptr [rdi + 0x10]'),r['0xb665b84']);self.assertEqual(('mov','rdx, qword ptr [rcx + 0x18]'),r['0xb665b8d']);self.assertEqual(('test','byte ptr [rdx + 0x2c], 1'),r['0xb665ba0']);self.assertEqual(('cmp','dword ptr [rdx + 0x30], esi'),r['0xb665ba6']);self.assertEqual(('jmp','0x1976d5720'),r['0xb665bd6'])
 def test_search_reads_owner_only_after_nonnull_next_and_stops_at_owners_head(self):
  r=rows(524903);self.assertEqual(('mov','rax, qword ptr [rdx + 0x48]'),r['0xb665bab']);self.assertEqual(('je','0x18b665bc6'),r['0xb665bb2']);self.assertEqual(('mov','rdi, qword ptr [rdx + 0x50]'),r['0xb665bb4']);self.assertEqual(('cmp','rax, qword ptr [rdi + 0x18]'),r['0xb665bc0'])
 def test_linked_remove_validates_owner_then_neighbours_before_invalidation_and_counter(self):
  r=runtime();self.assertEqual(('cmp','qword ptr [rdx + 0x50], rcx'),r['0x176d572a']);self.assertEqual(('mov','qword ptr [rax + 0x40], rsi'),r['0x176d5746']);self.assertEqual(('test','rsi, rsi'),r['0x176d574e']);self.assertEqual(('mov','qword ptr [rsi + 0x48], rax'),r['0x176d5753']);self.assertEqual(('movups','xmmword ptr [rdx + 0x40], xmm0'),r['0x176d5772']);self.assertEqual(('mov','qword ptr [rdx + 0x50], 0'),r['0x176d5776']);self.assertEqual([-1,1,0,0],team_boost_withdrawal.load_profile()['counterLiteral']['values'])
 def test_boost_identity_filter_is_reverse_and_clears_entities_before_current_removal(self):
  r=rows(524895);self.assertEqual(('lea','rbx, [rsi - 1]'),r['0xb664257']);self.assertEqual(('cmp','qword ptr [rax + rcx + 0x20], r15'),r['0xb664280']);self.assertEqual(('mov','dword ptr [rax + 0x18], 0'),r['0xb6642b9']);self.assertEqual(('mov','rdi, qword ptr [r12 + 0x20]'),r['0xb6642d0']);self.assertEqual(('mov','dword ptr [rdi + 0x18], ecx'),r['0xb6642e8']);self.assertEqual(('call','0x19c075050'),r['0xb664301']);self.assertEqual(('inc','dword ptr [rdi + 0x1c]'),r['0xb664231'])
 def test_child_entity_clear_updates_version_and_zero_size_before_array_clear(self):
  r=rows(524874);self.assertEqual(('inc','dword ptr [rax + 0x1c]'),r['0xb1625ea']);self.assertEqual(('mov','r8d, dword ptr [rax + 0x18]'),r['0xb1625ed']);self.assertEqual(('mov','dword ptr [rax + 0x18], 0'),r['0xb1625f1']);self.assertEqual(('jle','0x18b16260d'),r['0xb1625fb']);self.assertEqual(('jmp','0x19c074b80'),r['0xb162608'])
  p=team_boost_withdrawal.load_profile();f=next(f for f in p['genericFields'] if f['ownerTypeIndex']==56894);self.assertEqual(('List`1','GameEntity'),(f['record']['genericName'],f['record']['args'][0]['name']))
 def test_three_recursive_container_arguments_and_actual_outer_binding_are_matched(self):
  p=team_boost_withdrawal.load_profile();self.assertEqual([('LinkedList`1','INKMMBKPNMJ'),('List`1','BFHDICJPGLD'),('List`1','GameEntity')],[(f['record']['genericName'],f['record']['args'][0]['name']) for f in p['genericFields']])
  self.assertEqual([510264,510265],[m['index'] for m in p['boundMethodIdentities']]);self.assertIs(True,self.report['actualOuterStackedEffectWithdrawalsIntegrated'])
 def test_finite_withdrawals_and_prefixes_are_complete_but_admission_world_and_acceptance_are_unclaimed(self):
  for k in ['firstPresentRegistrationLinkedNodeWithdrawalImplemented','circularHeadOwnerAndDetachedNextRulesImplemented','linkedNeighbourPrefixThenInvalidationAndCountersImplemented','capturedLinkedListAndCurrentNodeFieldsPreserved','reverseAllBoostIdentityMatchesImplemented','valueEntrySnapshotAndCurrentListRemovalImplemented','childEntityHeaderClearBeforeStorageImplemented','outerCountBeforeShiftZeroAndVersionImplemented','defaultZeroValueEntryAndNullModifierImplemented','successfulRemovalPrefixesAndRetriesPreserved','projectFiniteCompiledContainersExplicit']:self.assertIs(True,self.report[k],k)
  for k in ['runtimeLinkedRemovalRegisteredIdentityMatched','fullNativeTeamBoostPointRuntimeImplemented','nativeBoostPointAdmissionAndFactoriesInstalled','nativeTeamBoostNotificationBodyInstalled','generalNativeListAndArrayRuntimeImplemented','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
