"""核对有限实例监听、调用快照与数值容器回调来源边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('resonance_observer',ROOT/'scripts/read-rogue-resonance-observer.py')
observer=importlib.util.module_from_spec(spec);spec.loader.exec_module(observer)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in observer.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ResonanceObserverTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=observer.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-resonance-observer-python-evidence.json'))
 def test_complete_methods_fields_and_separate_invocation_function_match(self):
  self.assertEqual((5,4260,2,1,116),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedNativeRuntimeFunctions','matchedNativeRuntimeBytes']))
 def test_multicast_combine_allocates_new_array_and_copies_old_before_new(self):
  r=rows(4569);self.assertEqual(('add','edx, dword ptr [rax + 0x18]'),r['0x1c251e87']);self.assertEqual(('mov','qword ptr [rsi + 0x68], rax'),r['0x1c251e96']);self.assertEqual(('call','0x19c075050'),r['0x1c251eb6']);self.assertEqual(('mov','r9d, dword ptr [rax + 0x18]'),r['0x1c251edc'])
 def test_single_binding_removal_searches_from_last_index(self):
  r=rows(4571);self.assertEqual(('mov','r9d, dword ptr [rsi + 0x18]'),r['0x1c2524bb']);self.assertEqual(('lea','r8d, [r9 - 1]'),r['0x1c2524bf']);self.assertEqual(('call','0x196bf88a0'),r['0x1c2524ce']);self.assertEqual(('cmp','eax, -1'),r['0x1c2524d3'])
 def test_invocation_wrapper_captures_array_count_and_keeps_same_raw_input(self):
  f=observer.load_profile()['nativeRuntimeFunctions'][0];r={i['rva']:(i['mnemonic'],i['operand']) for i in f['instructions']};self.assertEqual(('mov','rsi, qword ptr [rcx + 0x68]'),r['0x2fd45e0']);self.assertEqual(('mov','rdi, qword ptr [rsi + 0x18]'),r['0x2fd45e4']);self.assertEqual(('movups','xmm0, xmmword ptr [r12]'),r['0x2fd460d']);self.assertEqual(('cmp','rdi, rbx'),r['0x2fd462e']);self.assertIs(False,f['registeredMethodIdentityMatched'])
 def test_native_observer_and_invocation_array_offsets_match(self):
  p=observer.load_profile();container=next(t for t in p['matchedTypes'] if t['typeIndex']==10850);self.assertEqual(16,container['selectedFields'][0]['instanceOffset']);multicast=next(t for t in p['matchedTypes'] if t['typeIndex']==410);self.assertEqual(104,next(f for f in multicast['selectedInstanceFields'] if f['name']=='delegates')['instanceOffset'])
 def test_source_initializer_passes_previous_optional_absent(self):
  r=rows(74759);self.assertEqual(('xorps','xmm0, xmm0'),r['0x1cd7e4ff']);self.assertEqual(('movaps','xmmword ptr [rsp + 0x30], xmm0'),r['0x1cd7e502'])
 def test_root_notifications_and_finite_membership_are_implemented(self):
  for k in ['finiteInstanceObserverMembershipImplemented','lastIdentityMatchRemovalImplemented','capturedInvocationSnapshotImplemented','rootNumericNotificationBridgeImplemented','previousPresenceAndRawValuesPreserved','legacyConstructorObserverOrderPreserved','callbackMutationAndNestedNotificationImplemented']:self.assertIs(True,self.report[k],k)
 def test_general_delegate_subsequence_static_projection_and_world_are_not_claimed(self):
  for k in ['fullGeneralNativeDelegateRuntimeImplemented','multicastSubsequenceRemovalImplemented','staticNativeDelegateVariantsImplemented','legacyConstructorObserverRemovalImplemented','projectedObserverMembershipImplemented','nativeDelegateFactoriesInstalled','nativeCasterCallbackBodyInstalled','actualWorldBindingInstalled','modifierInstanceApplied','extendedVariableCellsImplemented','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
