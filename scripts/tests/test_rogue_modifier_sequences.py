"""核对等待序列、共享执行计数与延迟更新的来源和边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_sequences',ROOT/'scripts/read-rogue-modifier-sequences.py')
sequences=importlib.util.module_from_spec(spec);spec.loader.exec_module(sequences)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in sequences.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierSequencesTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=sequences.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-sequences-python-evidence.json'))
 def test_five_complete_methods_and_three_field_types_match(self):
  self.assertEqual((5,3229,3),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes']))
 def test_start_increments_both_counts_before_zero_delta_step(self):
  r=rows(508652)
  self.assertEqual(('inc','dword ptr [rdi + 0x98]'),r['0xe5af2aa'])
  self.assertEqual(('inc','dword ptr [rdi + 0x7c]'),r['0xe5af2b0'])
  self.assertEqual(('xorps','xmm1, xmm1'),r['0xe5af2bc'])
  self.assertEqual(('call','0x18b390fc0'),r['0xe5af2c2'])
  self.assertEqual(('jmp','0x18e5af350'),r['0xe5af2ee'])
 def test_start_normalizes_nonterminal_result_then_ticks_with_zero_delta(self):
  r=rows(508652)
  self.assertEqual(('cmp','eax, 0x9999'),r['0xe5af2ca'])
  self.assertEqual(('cmp','eax, 0xaaaa'),r['0xe5af2d1'])
  self.assertEqual(('mov','dword ptr [rsi + 0x10], 0x8888'),r['0xe5af2d8'])
  self.assertEqual(('xorps','xmm2, xmm2'),r['0xe5af2df'])
 def test_tick_resets_then_sets_ready_then_decrements_only_ticking_count(self):
  r=rows(508653)
  self.assertEqual(('call','0x18b3917e0'),r['0xe5af40c'])
  self.assertEqual(('mov','dword ptr [rdi + 0x10], 0x7777'),r['0xe5af411'])
  self.assertEqual(('dec','dword ptr [rsi + 0x7c]'),r['0xe5af418'])
  self.assertFalse(any(m=='dec' and o=='dword ptr [rsi + 0x98]' for m,o in r.values()))
 def test_flush_decrements_before_signed_previous_count_and_pending_checks(self):
  r=rows(508654)
  self.assertEqual(('lea','ecx, [rax - 1]'),r['0xe5af59b'])
  self.assertEqual(('mov','dword ptr [rsi + 0x98], ecx'),r['0xe5af59e'])
  self.assertEqual(('cmp','eax, 1'),r['0xe5af5a4'])
  self.assertEqual(('jg','0x18e5af5fc'),r['0xe5af5a7'])
  self.assertEqual(('mov','rdi, qword ptr [rsi + 0x48]'),r['0xe5af5a9'])
  self.assertEqual(('mov','qword ptr [rsi + 0x48], 0'),r['0xe5af5f4'])
 def test_fields_separate_ticking_count_executing_count_and_ability(self):
  t=next(t for t in sequences.load_profile()['matchedTypes'] if t['typeIndex']==54430)
  values={f['name']:(f['instanceOffset'],f['nativeType']['kind']) for f in t['selectedFields']}
  self.assertEqual((124,8),values['_TickingSequenceCount']);self.assertEqual((152,8),values['_ExecutingSequenceCount'])
  self.assertEqual((72,18),values['_DelayReplaceAbility'])
  enum=next(t for t in sequences.load_profile()['matchedTypes'] if t['typeIndex']==54773)
  self.assertEqual([0x7777,0x8888,0x9999,0xaaaa],[f['constant']['value'] for f in enum['fields'] if 'constant' in f])
 def test_shared_java_sequence_and_order_are_implemented(self):
  for key in ['finiteTickedSequenceOrderImplemented','sharedReplacementCounterImplemented','deferredAbilityFlushOrderImplemented','signed32CounterWrapPreserved','actualJavaChildSequenceJointBindingImplemented']:
   self.assertIs(True,self.report[key],key)
 def test_virtual_callback_identities_match_both_base_and_turn_based_declarations(self):
  self.assertEqual(6,self.report['matchedModifierVirtualSlots'])
  self.assertIs(True,self.report['modifierVirtualCallbackIdentitiesResolved'])
  self.assertEqual([(54430,'OnStack',28),(54430,'OnModifierCasterChanged',30),(54430,'_SequenceEndAndFlush',31),(54843,'OnModifierCasterChanged',30),(54843,'OnStack',28),(54843,'_SequenceEndAndFlush',31)],[(f['typeIndex'],f['name'],f['decodedSlot']) for f in sequences.load_profile()['modifierVirtualSlots']])
 def test_real_virtual_slots_task_factory_world_and_gameplay_remain_unclaimed(self):
  for key in ['nativeSequenceEndVirtualSlotInstalled','nativeContextVirtualSlotsInstalled','nativeTaskConfigFactoryInstalled','modifierInstanceApplied','fullNativeSequenceRuntimeImplemented','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:
   self.assertIs(False,self.report[key],key)

if __name__=='__main__':unittest.main()
