"""核对字符串注入遍历顺序、版本与字典来源边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_string_injection',ROOT/'scripts/read-rogue-modifier-string-injection.py')
string_injection=importlib.util.module_from_spec(spec);spec.loader.exec_module(string_injection)

def rows(runtime=False):
 p=string_injection.load_profile()
 methods=p['nativeRuntimeFunctions'] if runtime else p['nativeMethods']
 return {i['rva']:(i['mnemonic'],i['operand']) for m in methods for i in m['instructions']}

class ModifierStringInjectionTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=string_injection.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-string-injection-python-evidence.json'))
 def test_complete_method_runtime_function_and_generic_arguments_are_separate(self):
  self.assertEqual((1,319,3,1,1739,1,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedNativeRuntimeFunctions','matchedNativeRuntimeBytes','matchedBoundMethodIdentities','matchedGenericDictionaryArguments']))
 def test_null_and_empty_list_skip_before_dictionary_access(self):
  r=rows();self.assertEqual(('test','r15, r15'),r['0xe5ae430']);self.assertEqual(('je','0x18e5ae526'),r['0xe5ae433']);self.assertEqual(('jle','0x18e5ae526'),r['0xe5ae43e']);self.assertEqual(('ret',''),r['0xe5ae536'])
 def test_version_is_captured_and_checked_before_current_count(self):
  r=rows();self.assertEqual(('mov','r12d, dword ptr [r15 + 0x1c]'),r['0xe5ae444']);self.assertEqual(('cmp','r12d, dword ptr [r15 + 0x1c]'),r['0xe5ae450']);self.assertEqual(('jne','0x18e5ae50e'),r['0xe5ae454']);self.assertEqual(('cmp','edi, dword ptr [r15 + 0x18]'),r['0xe5ae45a']);self.assertEqual(('call','0x19c139730'),r['0xe5ae513'])
 def test_dictionary_is_reread_and_parameter_slots_remain_inline(self):
  r=rows();self.assertEqual(('mov','rbx, qword ptr [r14 + 0x38]'),r['0xe5ae472']);self.assertEqual(('shl','rcx, 4'),r['0xe5ae47d']);self.assertEqual(('mov','r13, qword ptr [rax + rcx + 0x28]'),r['0xe5ae489']);self.assertEqual(('mov','esi, dword ptr [rdx]'),r['0xe5ae48e']);self.assertEqual(('inc','edi'),r['0xe5ae4aa']);self.assertEqual(('mov','r9b, 1'),r['0xe5ae4b9'])
 def test_overwrite_mode_one_is_verified_without_claiming_helper_identity(self):
  r=rows(True);self.assertEqual(('cmp','r12b, 1'),r['0x1adcd660']);self.assertEqual(('je','0x19adcd699'),r['0x1adcd664']);self.assertEqual(('mov','qword ptr [rcx + rax*8 + 0x10], r13'),r['0x1adcd699']);self.assertIs(False,string_injection.load_profile()['nativeRuntimeFunctions'][0]['registeredMethodIdentityMatched'])
 def test_generic_dictionary_key_is_string_hash_and_value_is_builtin_string(self):
  p=string_injection.load_profile();f=p['genericMethodSlots'][0];self.assertEqual((12484,-1,33814),tuple(f['methodSpec'][k] for k in ['methodDefinition','argumentIndexAt4','argumentIndexAt8']));arguments=f['instRecords'][0]['arguments'];self.assertEqual((17,10875),(arguments[0]['kind'],arguments[0]['payload']));self.assertEqual(14,arguments[1]['kind']);self.assertEqual('set_Item',p['boundMethodIdentities'][0]['name'])
 def test_version_dictionary_overwrite_null_values_and_shared_state_are_implemented(self):
  for k in ['versionedStringListImplemented','nullAndEmptyStringListSkipDictionary','currentDictionaryRereadImplemented','capturedVersionBeforeCountImplemented','duplicateKeyOverwriteImplemented','nullStringValuesPreserved','sameReplacementStateImplemented']:self.assertIs(True,self.report[k],k)
 def test_native_factories_comparers_and_live_world_are_not_claimed(self):
  for k in ['nativeDictionaryInternalsInstalled','customNativeStringComparersInstalled','nativeStringContainerFactoriesInstalled','actualWorldBindingInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertIs(False,self.report[k],k)

if __name__=='__main__':unittest.main()
