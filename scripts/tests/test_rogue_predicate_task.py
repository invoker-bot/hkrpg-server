"""共享条件主体、startup泛型父记录和实际函数指针的静态核对。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('predicate_task',ROOT/'scripts/read-rogue-predicate-task.py')
task=importlib.util.module_from_spec(spec);spec.loader.exec_module(task)

def rows(definition):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in task.load_profile()['sharedBodies']+task.load_profile()['sharedLeaves'] if m['definition']==definition for i in m['instructions']}

class PredicateTaskTest(unittest.TestCase):
 def test_three_shared_bodies_five_complete_providers_and_matched_generic_records(self):
  self.assertEqual((3,283,5,29159,2,14),task.verify_native(CLIENT))

 def test_startup_metadata_is_separately_pinned_without_global_header_padding(self):
  p=task.load_profile();self.assertEqual((4091208,'0x52d60',430264),tuple(p[k] for k in ['startupMetadataLength','genericClassTableOffsetInStartup','genericClassCount']))
  self.assertEqual('78d090a64d8375574db2e36721e38994265c23ca8ac398ea97061523f2166034',p['nativeFiles']['startup-metadata.dat'])
  task.verify_files(CLIENT)

 def test_concrete_comparison_and_team_inherit_the_same_generic_base_with_distinct_configuration_arguments(self):
  p=task.load_profile();self.assertEqual((2,14),task.verify_generic_records(CLIENT))
  self.assertEqual([(54578,54789,39947),(54727,54789,40093)],[(r['index'],r['genericDefinition'],r['instIndex']) for r in p['parentGenericRecords']])
  self.assertEqual('PredicateTaskBase`1',p['genericBaseMetadata']['name'])
  actual=[r for r in p['functionRecords'] if r['definition']==509898 and r['argumentKind']==18]
  self.assertEqual({23014,22553},{r['argumentType'] for r in actual})
  self.assertEqual({'0x19eca9a0'},{r['bodyPointerRva'] for r in actual})

 def test_body_pointer_uses_registration_offset_30_not_the_invoker_array(self):
  p=task.load_profile();self.assertEqual(('0x43f9fa0','0x45c4190',383748),tuple(p['bodyPointerRegistration'][k] for k in ['codeRegistrationRva','arrayRva','count']))
  provider=next(m for m in p['runtimeProviders'] if m['rva']=='0x3d34470')
  r={i['rva']:(i['mnemonic'],i['operand']) for i in provider['instructions']}
  self.assertEqual(('mov','rcx, qword ptr [rcx + 0x30]'),r['0x3d34903'])
  self.assertEqual(('mov','r12, qword ptr [rcx + rax*8]'),r['0x3d34907'])
  shared=next(m for m in p['functionRecords'] if m['definition']==509898 and m['argumentKind']==28)
  self.assertEqual((17425,'0x19eca9a0'),(shared['bodyPointerIndex'],shared['bodyPointerRva']))

 def test_evaluate_virtual_slot_ten_matches_both_overrides_and_the_shared_begin_call(self):
  p=task.load_profile();self.assertEqual({509896,509070,509384},{r['definition'] for r in p['evaluationVirtualSlots']})
  self.assertTrue(all(r['decodedSlot']==10 for r in p['evaluationVirtualSlots']))
  self.assertEqual(('mov','rdi, qword ptr [rcx + 0x110]'),rows(509898)['0x19eca9ac'])
  self.assertEqual(('call','rdi'),rows(509898)['0x19eca9ce'])

 def test_inverse_read_follows_evaluate_then_exclusive_or_and_only_then_state_write(self):
  r=rows(509898)
  self.assertEqual(('mov','rcx, qword ptr [rsi + 0x18]'),r['0x19eca9d0'])
  self.assertEqual(('cmp','byte ptr [rcx + 0x18], 0'),r['0x19eca9d9'])
  self.assertEqual(('xor','al, cl'),r['0x19eca9e0'])
  self.assertEqual(('mov','eax, 0x9999'),r['0x19eca9e2']);self.assertEqual(('mov','ecx, 0xaaaa'),r['0x19eca9e7'])
  self.assertEqual(('mov','dword ptr [rsi + 0x10], ecx'),r['0x19eca9ef'])

 def test_tick_throws_verified_not_implemented_exception_and_carries_its_generic_method_info(self):
  p=task.load_profile();r=rows(509900)
  self.assertEqual((304,'NotImplementedException'),tuple(p['tickExceptionClassSlots'][0][k] for k in ['typeIndex','typeName']))
  self.assertEqual((3113,'.ctor','0x1c252890'),tuple(p['tickExceptionConstructor'][k] for k in ['index','name','rva']))
  self.assertEqual(('call','0x19c252890'),r['0x19ecaa90']);self.assertEqual(('call','0x183cc8040'),r['0x19ecaa9f'])
  info=p['tickThrowMethodInfo'];self.assertEqual((6,509900,-1,0,18254),tuple(info[k] for k in ['usageKind','definition','argumentAt4','argumentAt8','payload']))

 def test_leaf_dispose_is_ret_and_reset_only_writes_ready(self):
  p=task.load_profile();self.assertEqual((2,9),(len(p['sharedLeaves']),p['sharedLeafBytes']))
  self.assertEqual({'0x19eca990':('ret','')},rows(509897))
  self.assertEqual({'0x19ecaa60':('mov','dword ptr [rcx + 0x10], 0x7777'),'0x19ecaa67':('ret','')},rows(509899))
  self.assertEqual(109,p['registrationFragment']['length'])

 def test_constructor_binds_ready_context_and_configuration_without_inventing_generic_field_offsets(self):
  p=task.load_profile();r=rows(509895)
  self.assertEqual(('mov','dword ptr [rcx + 0x10], 0x7777'),r['0x19eca969'])
  self.assertEqual(('mov','qword ptr [rcx + 0x20], rdx'),r['0x19eca970'])
  self.assertEqual(('mov','qword ptr [rcx + 0x18], r8'),r['0x19eca974'])
  self.assertEqual(['_TaskContext','_ConfigRef'],[f['name'] for f in p['genericBaseMetadata']['fields']])
  self.assertTrue(all('instanceOffset' not in f for f in p['genericBaseMetadata']['fields']))
  abstract=next(r for r in p['functionRecords'] if r['definition']==509896)
  self.assertEqual(28,abstract['argumentKind']);self.assertEqual('-0x180000000',abstract['bodyPointerRva'])

 def test_fresh_source_callbacks_omit_inverse_in_both_original_predicates(self):
  p=task.load_profile();self.assertEqual(p['sourceConditions'],task.branches.source_conditions(CLIENT))
  self.assertEqual([57,173],[r['event'] for r in p['sourceConditions']])
  for r in p['sourceConditions']:self.assertNotIn('Inverse',r['task']['Predicate'])

 def test_report_keeps_shared_bodies_providers_fragments_and_online_verification_separate(self):
  r=task.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-predicate-task-python-evidence.json'))
  self.assertTrue(r['passed']);self.assertEqual((3,283,5,29159,2,9,109,2,14,2),tuple(r[k] for k in ['matchedCompleteSharedBodies',
   'matchedCompleteSharedBodyBytes','matchedCompleteRuntimeProviders','matchedCompleteRuntimeProviderBytes','matchedSharedLeaves',
   'matchedSharedLeafBytes','matchedRegistrationFragmentBytes','sourceGenericParents','matchedFunctionRecords','sourceConditions']))
  for k in ['finiteGenericPredicateTaskLifecycleImplemented','predicateInversionImplemented','inverseReadAfterEvaluationImplemented',
   'nativeUnsupportedTickPreserved','stateWriteAfterSuccessfulEvaluationAndInverseRead','sourceCallbacksUseConcretePredicateTaskCompiler',
   'sourceGenericParentsAndConfigurationArgumentsResolved','genericBodyPointerNotInvokerResolved']:self.assertTrue(r[k],k)
  for k in ['genericPredicateTypesAllImplemented','genericPredicateLiveWorldAttached','predicateSkipImplemented','nativeEntitySelectionImplemented',
   'nativeVisualEffectsImplemented','callbacksAttachedToLiveEntities','fullOriginalAddModifierRuntimeImplemented','clientGameplayVerified',
   'completeResonanceRuntimeImplemented','jsonInstalled','clientExecutableStarted','clientModified']:self.assertFalse(r[k],k)

 def test_output_paths_cannot_overwrite_the_client_or_operator(self):
  for output in [CLIENT/'predicate-task.json',ROOT/'predicate-task.json']:
   with self.assertRaises(ValueError):task.analyze(SimpleNamespace(client_dir=CLIENT,output=output))

if __name__=='__main__':unittest.main()
