"""核对范围进入、退出、变化回调的创建顺序和明确任务工厂边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_range_setup',ROOT/'scripts/read-rogue-modifier-range-setup.py')
modifier_range_setup=importlib.util.module_from_spec(spec);spec.loader.exec_module(modifier_range_setup)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in modifier_range_setup.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierRangeSetupTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=modifier_range_setup.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-range-setup-python-evidence.json'))
 def test_complete_method_fields_factory_identity_and_construction_types_match(self):
  self.assertEqual((1,727,5,1,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedConstructionTypeSlots']))
 def test_empty_array_returns_before_target_access_but_null_array_throws(self):
  r=rows(510132);self.assertEqual(('je','0x18e80ce0c'),r['0xe80cb6b']);self.assertEqual(('jle','0x18e80cdb0'),r['0xe80cb77']);self.assertEqual(('test','r12, r12'),r['0xe80cd32'])
 def test_three_arrays_read_from_current_definition_in_enter_exit_change_order(self):
  r=rows(510132)
  self.assertEqual(('mov','rsi, qword ptr [r14 + 0x28]'),r['0xe80cbe9']);self.assertEqual(('mov','rsi, qword ptr [r14 + 0x30]'),r['0xe80cc43']);self.assertEqual(('mov','rsi, qword ptr [r14 + 0x38]'),r['0xe80cc9d'])
 def test_context_and_ready_state_are_reloaded_for_each_nonnull_array(self):
  r=rows(510132)
  for a in ['0xe80cbfb','0xe80cc55','0xe80ccab']:self.assertEqual(('mov','r13, qword ptr [rax + 0x10]'),r[a])
  for a in ['0xe80cc0e','0xe80cc68','0xe80ccbe']:self.assertEqual(('mov','dword ptr [rax + 0x10], 0x7777'),r[a])
  for a in ['0xe80cc36','0xe80cc90','0xe80cce6']:self.assertEqual(('call','0x18b225b30'),r[a])
 def test_created_sequences_are_bound_to_children_then_range_fields(self):
  r=rows(510132)
  for a in ['0xe80cc3b','0xe80cc95','0xe80cceb']:self.assertEqual(('mov','qword ptr [rdi + 0x20], rax'),r[a])
  self.assertEqual(('mov','qword ptr [rbx + 0x20], rdi'),r['0xe80cc3f']);self.assertEqual(('mov','qword ptr [rbx + 0x18], rdi'),r['0xe80cc99']);self.assertEqual(('mov','qword ptr [rbx + 0x28], rdi'),r['0xe80cd24'])
 def test_config_identity_and_three_zero_depths_precede_target_append(self):
  r=rows(510132);self.assertEqual(('mov','qword ptr [rax + 0x10], r14'),r['0xe80cbd8']);self.assertEqual(('mov','byte ptr [rax + 0x33], 0'),r['0xe80cbdc']);self.assertEqual(('mov','word ptr [rbx + 0x30], 0'),r['0xe80cd28']);self.assertEqual(('mov','byte ptr [rbx + 0x32], 0'),r['0xe80cd2e']);self.assertEqual(('mov','qword ptr [rax + rdx*8 + 0x20], rbx'),r['0xe80cb98'])
 def test_finite_explicit_factories_and_existing_scheduler_integrations_are_implemented(self):
  for k in ['callbackArrayAbsenceAndEmptyDistinguished','enterExitChangeConstructionOrderImplemented','contextCapturedPerSequenceImplemented','liveDefinitionAndArrayElementsImplemented','appendAfterSuccessfulConstructionImplemented','explicitChildFactoriesRequired','existingTaskSequenceAndModifierCountersIntegrated','actualRangeChangeAndRetirementIntegrated','projectConstructionAndSizeGuardsExplicit']:self.assertIs(True,self.report[k],k)
 def test_original_configs_factory_world_and_client_acceptance_remain_unclaimed(self):
  for k in ['nativeTaskConfigFactoryBodyInstalled','originalTaskConfigsDispatched','nativeSequenceExecutorBodyInstalled','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
