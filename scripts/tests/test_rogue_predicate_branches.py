"""两个原始瞄准条件与普通分支的完整原生来源和边界验证。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('predicate_branches',ROOT/'scripts/read-rogue-predicate-branches.py')
branches=importlib.util.module_from_spec(spec);spec.loader.exec_module(branches)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in branches.load_profile()['nativeMethods']+branches.load_profile()['nativeLeaves'] if m['index']==index for i in m['instructions']}

class PredicateBranchesTest(unittest.TestCase):
 def test_thirteen_complete_methods_and_declared_configuration_fields_match(self):
  self.assertEqual((13,4253),branches.verify_native(CLIENT))

 def test_constructors_preserve_enabled_and_compare_default_mask_fifteen(self):
  p=branches.load_profile();self.assertEqual((3,21),(len(p['nativeLeaves']),sum(m['bytes'] for m in p['nativeLeaves'])))
  self.assertEqual(('mov','word ptr [rcx + 0x30], 0xf'),rows(127457)['0x1d4afda0'])
  self.assertEqual(('mov','byte ptr [rcx + 0x10], 1'),rows(127457)['0x1d4afda6'])
  self.assertEqual(('mov','byte ptr [rcx + 0x10], 1'),rows(125969)['0x1d5123d0'])
  self.assertEqual(15,p['compareDefaultAliveMask']);self.assertEqual(65535,p['teamSelectionAliveMask'])

 def test_compare_is_reference_identity_and_stops_when_the_first_selection_is_null(self):
  r=rows(509070)
  self.assertEqual(('call','0x18e4b4180'),r['0xdfb2b8e'])
  self.assertEqual('je',r['0xdfb2b96'][0])
  self.assertEqual(('call','0x18e4b4180'),r['0xdfb2bc9'])
  self.assertEqual(('cmp','rdi, rax'),r['0xdfb2bce']);self.assertEqual('sete',r['0xdfb2bd1'][0])

 def test_team_selects_with_all_mask_then_reads_the_verified_team_field(self):
  r=rows(509384);p=branches.load_profile()
  self.assertEqual(('mov','r8w, 0xffff'),r['0xf25166a'])
  self.assertEqual(('call','0x18e4b4180'),r['0xf25166f'])
  self.assertEqual(('mov','eax, dword ptr [rax + 0xc8]'),r['0xf251682'])
  self.assertEqual(('cmp','eax, dword ptr [rcx + 0x28]'),r['0xf251688'])
  self.assertEqual(('_Team',200,53551),(p['teamField']['name'],p['teamField']['instanceOffset'],p['entityType']['typeIndex']))

 def test_single_selector_returns_first_list_entity_and_returns_the_pool_on_normal_path(self):
  r=rows(508242)
  self.assertEqual(('call','0x18e7796f0'),r['0xe4b41ba'])
  self.assertEqual(('cmp','dword ptr [rsi + 0x18], 0'),r['0xe4b41c4'])
  self.assertEqual(('mov','rdi, qword ptr [rax + 0x20]'),r['0xe4b41d9'])
  self.assertEqual(('call','0x18b10d0d0'),r['0xe4b41eb']);self.assertEqual(('mov','rax, rdi'),r['0xe4b41f0'])

 def test_branch_picks_only_exact_success_and_maps_all_nonwaiting_branch_states_to_success(self):
  b=rows(512185);sync=rows(512188);tick=rows(512187);reset=rows(512186)
  self.assertEqual(('cmp','eax, 0x9999'),b['0xba02f7e'])
  self.assertEqual(('mov','rdi, qword ptr [rsi + 0x20]'),b['0xba02fdf'])
  self.assertEqual(('cmp','eax, 0x8888'),sync['0xba02ed7'])
  self.assertEqual(('mov','ecx, 0x9999'),sync['0xba02edc']);self.assertEqual(('cmove','ecx, eax'),sync['0xba02ee1'])
  self.assertEqual(('cmp','dword ptr [rdi + 0x10], 0x8888'),tick['0xba03165'])
  self.assertEqual(('call','0x18b3917e0'),reset['0xba030c7'])
  self.assertEqual(('mov','qword ptr [rsi + 0x40], 0'),reset['0xba030d3'])

 def test_dispose_clears_success_then_failure_then_predicate_only_after_returns(self):
  r=rows(512182)
  self.assertEqual(('mov','rdi, qword ptr [rsi + 0x30]'),r['0xba02c07'])
  self.assertEqual(('mov','qword ptr [rsi + 0x30], 0'),r['0xba02c3b'])
  self.assertEqual(('mov','rdi, qword ptr [rsi + 0x20]'),r['0xba02c43'])
  self.assertEqual(('mov','qword ptr [rsi + 0x20], 0'),r['0xba02c77'])
  self.assertEqual(('mov','rdi, qword ptr [rsi + 0x28]'),r['0xba02c7f'])
  self.assertEqual(('mov','qword ptr [rsi + 0x28], 0'),r['0xba02caf'])
  self.assertTrue(any(mnemonic=='call' and operand=='0x19dd6b050' for mnemonic,operand in rows(513995).values()))

 def test_fresh_original_callback_branches_preserve_absence_empty_and_default_predicates(self):
  source=branches.source_conditions(CLIENT);self.assertEqual(branches.load_profile()['sourceConditions'],source)
  first,second=[s['task'] for s in source];self.assertNotIn('FailedTaskList',first)
  self.assertEqual([],second['SuccessTaskList'])
  self.assertEqual((526,273),(first['Predicate']['$variantId'],second['Predicate']['$variantId']))
  for task in [first,second]:
   self.assertNotIn('Inverse',task['Predicate']);self.assertNotIn('Enabled',task['Predicate'])
  self.assertEqual([57,173],[s['event'] for s in source])

 def test_report_opens_two_evaluations_and_ordinary_branches_without_claiming_online_entities(self):
  r=branches.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-predicate-branches-python-evidence.json'))
  self.assertTrue(r['passed']);self.assertEqual((13,4253,3,21,2,15,65535),tuple(r[k] for k in ['matchedNativeMethods',
   'matchedNativeBytes','matchedConstructorLeaves','matchedConstructorLeafBytes','sourceConditions','compareDefaultAliveMask','teamSelectionAliveMask']))
  for k in ['finiteSourcePredicateEvaluationImplemented','nativeSingleTargetIdentityComparisonImplemented',
   'sourceAliveMasksPreserved','ordinaryPredicateBranchLifecycleImplemented','selectedBranchFailureEndsParentSuccessfully',
   'normalReturnOnlyPredicateResetImplemented','recursiveSourceBranchCompilerImplemented','explicitGenericPredicateTaskFactoryRequired']:
   self.assertTrue(r[k],k)
  for k in ['genericPredicateBaseRuntimeImplemented','predicateInverseImplemented','predicateSkipImplemented',
   'nativeEntitySelectionImplemented','liveBattleContextAttached','nativeVisualEffectsImplemented','callbacksAttachedToLiveEntities',
   'fullOriginalAddModifierRuntimeImplemented','clientGameplayVerified','completeResonanceRuntimeImplemented','jsonInstalled','clientExecutableStarted','clientModified']:
   self.assertFalse(r[k],k)

 def test_evidence_cannot_overwrite_operator_or_client_files(self):
  for path in [CLIENT/'predicates.json',ROOT/'predicates.json']:
   with self.assertRaises(ValueError):branches.analyze(SimpleNamespace(client_dir=CLIENT,output=path))

if __name__=='__main__':unittest.main()
