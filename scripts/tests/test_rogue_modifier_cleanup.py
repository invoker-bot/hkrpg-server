"""核对回合收尾、待释放标志与销毁清理的原件和未完成边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_cleanup',ROOT/'scripts/read-rogue-modifier-cleanup.py')
cleanup=importlib.util.module_from_spec(spec);spec.loader.exec_module(cleanup)
def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in cleanup.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}
class ModifierCleanupTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=cleanup.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-cleanup-python-evidence.json'))
 def test_complete_methods_fields_and_bound_identities_match(self):
  self.assertEqual((5,2139,3,5),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities']))
 def test_turn_end_calls_base_then_compares_execution_with_ticking(self):
  r=rows(510269);self.assertEqual(('call','0x18e5af580'),r['0xe80af34']);self.assertEqual(('cmp','eax, dword ptr [rsi + 0x7c]'),r['0xe80af48']);self.assertEqual(('jg','0x18e80afc0'),r['0xe80af4b']);self.assertEqual(('cmp','dword ptr [rsi + 0x90], 2'),r['0xe80af4d'])
 def test_removal_calls_unstack_then_cleanup_before_pending_dispose(self):
  r=rows(510269);self.assertEqual(('call','0x18e8146a0'),r['0xe80af59']);self.assertEqual(('call','0x18e806be0'),r['0xe80af61']);self.assertEqual(('mov','byte ptr [rsi + 0x80], 0'),r['0xe80af6f']);self.assertEqual(('jmp','0x18e808080'),r['0xe80af7e'])
 def test_dispose_prelude_defers_only_on_signed_execution_greater_than_ticking(self):
  r=rows(510109);self.assertEqual(('cmp','eax, dword ptr [rsi + 0x7c]'),r['0xe8080a2']);self.assertEqual(('jle','0x18e8080fa'),r['0xe8080a5']);self.assertEqual(('mov','byte ptr [rsi + 0x80], 1'),r['0xe8080a7']);self.assertEqual(('call','0x18e5ae650'),r['0xe8080fd'])
 def test_cleanup_detaches_before_reference_clear_and_override_reset(self):
  r=rows(510106);self.assertEqual(('call','0x18e5ad180'),r['0xe806bfa']);self.assertEqual(('mov','qword ptr [rsi + 0x50], 0'),r['0xe806bff']);self.assertEqual(('mov','qword ptr [rsi + 0x70], 0'),r['0xe806c07']);self.assertEqual(('call','0x18e7cb4a0'),r['0xe806c30']);self.assertEqual(('mov','dword ptr [rsi + 0x2ac], 0xffffffff'),r['0xe806c35'])
 def test_config_reread_and_priority_string_length_differ_from_behavior_presence(self):
  r=rows(510106);self.assertEqual(('cmp','dword ptr [rax + 0x10], 0'),r['0xe806c64']);self.assertEqual(('mov','rax, qword ptr [rsi + 0x100]'),r['0xe806c7f']);self.assertEqual(('cmp','qword ptr [rax + 0x68], 0'),r['0xe806c8b'])
  t=next(t for t in cleanup.load_profile()['matchedTypes'] if t['typeIndex']==18229);self.assertEqual({'BehaviorFlagList':(104,29),'EffectPriority':(128,14)},{f['name']:(f['instanceOffset'],f['nativeType']['kind']) for f in t['selectedFields']})
 def test_finite_cleanup_and_shared_state_implemented(self):
  for key in ['finiteTurnSequenceEndOrderImplemented','pendingDisposePreludeImplemented','finiteDestructionCleanupOrderImplemented','sharedSequenceAndCandidateStateImplemented','cleanupConfigRereadsPreserved','effectReferencesClearedOnlyAfterDetach']:self.assertIs(True,self.report[key],key)
 def test_effect_world_unstack_disposal_and_gameplay_remain_unclaimed(self):
  for key in ['nativeUnstackBodyImplemented','nativeDetachEffectsBodyImplemented','nativeOwnerComponentHandlersInstalled','fullNativeDisposeBodyImplemented','nativeContextVirtualSlotsInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[key],key)
if __name__=='__main__':unittest.main()
