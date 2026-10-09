"""核对效果范围操作完整体、接口槽与原生异常状态记录。"""
import importlib.util,struct,unittest
from pathlib import Path
from types import SimpleNamespace
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('effect_list_range',ROOT/'scripts/read-rogue-effect-list-range.py');effect_list_range=importlib.util.module_from_spec(spec);spec.loader.exec_module(effect_list_range)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in effect_list_range.load_profile()['runtimeMethods'] for i in m['instructions']}
class EffectListRangeTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=effect_list_range.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-effect-list-range-python-evidence.json'))
 def test_full_helpers_auxiliary_fields_interfaces_and_exception_records_match(self):
  self.assertEqual((0,0,5,5311,14,2586,3,5,1,1,3,56,2,14),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs','matchedNativeExceptionRecords']))
 def test_source_guard_unsigned_index_generic_count_and_non_generic_fallback(self):
  r=rows();self.assertEqual(('test','r8, r8'),r['0x180b58e7']);self.assertEqual(('cmp','dword ptr [rcx + 0x18], edx'),r['0x180b58f6']);self.assertEqual(('jb','0x1980b65eb'),r['0x180b58f9']);self.assertEqual(('call','rdi'),r['0x180b5a2f']);self.assertEqual(('mov','r9d, 1'),r['0x180b5bda']);self.assertEqual(('test','eax, eax'),r['0x180b5bf2'])
 def test_collection_current_capacity_shift_copy_and_self_copy_order(self):
  r=rows();self.assertEqual(('call','0x1980b36d0'),r['0x180b5b55']);self.assertEqual(('mov','eax, dword ptr [r12 + 0x18]'),r['0x180b5b5d']);self.assertEqual(('call','0x19c075050'),r['0x180b5b7e']);self.assertEqual(('call','0x19c075050'),r['0x180b5e4b']);self.assertEqual(('mov','rcx, qword ptr [r12 + 0x10]'),r['0x180b5e50']);self.assertEqual(('call','0x19c075050'),r['0x180b5e70']);self.assertEqual(('add','dword ptr [r12 + 0x18], r15d'),r['0x180b5e75'])
 def test_enumerable_middle_uses_single_insert_and_append_helper_has_own_version(self):
  r=rows();self.assertEqual(('call','0x1980b55d0'),r['0x180b609f']);self.assertEqual(('mov','r13d, esi'),r['0x180b60a4']);self.assertEqual(('call','0x1980b7580'),r['0x180b6254']);self.assertEqual(('inc','dword ptr [r12 + 0x1c]'),r['0x180b6259']);self.assertEqual(('inc','dword ptr [rcx + 0x1c]'),r['0x180b759d'])
 def test_single_insert_stores_before_current_count_version_increment(self):
  r=rows();self.assertEqual(('mov','qword ptr [rax + rcx*8 + 0x20], r14'),r['0x180b568f']);self.assertEqual(('psubd','xmm0, xmm1'),r['0x180b569d']);self.assertEqual(('movq','qword ptr [rsi + 0x18], xmm0'),r['0x180b56a1']);self.assertEqual(('mov','dword ptr [r13 + 0x18], eax'),r['0x180b7809']);self.assertEqual(('mov','qword ptr [rsi + rax*8 + 0x20], rbx'),r['0x180b78ce'])
 def test_native_catch_funclets_dispose_rethrow_and_nullable_fallthrough(self):
  r=rows();self.assertEqual(('mov','qword ptr [rbp - 0x38], rax'),r['0x180b6623']);self.assertEqual(('mov','dword ptr [rbp - 0x2c], 0'),r['0x180b6627']);self.assertEqual(('lea','rax, [rip - 0x4ae]'),r['0x180b662e']);self.assertEqual(('call','rsi'),r['0x180b6221']);self.assertEqual(('test','rdi, rdi'),r['0x180b6223']);self.assertEqual(('call','0x183cc8040'),r['0x180b644c']);self.assertEqual(('mov','qword ptr [rbp - 0x38], rax'),r['0x180b7b70']);self.assertEqual(('call','rsi'),r['0x180b7add']);self.assertEqual(('test','rcx, rcx'),r['0x180b7ae3'])
 def test_exception_info_proves_same_wrapper_and_two_catch_state_maps(self):
  p=effect_list_range.load_profile();facts=p['nativeExceptionRecords'];handlers=[x for x in facts if x['label']=='catch handler record'];self.assertEqual(2,len(handlers));self.assertEqual({0x180b6600,0x180b7b50},{struct.unpack('<5I',bytes.fromhex(x['bytesHex']))[3] for x in handlers});self.assertTrue(all(struct.unpack('<5I',bytes.fromhex(x['bytesHex']))[1]==0x913d000 for x in handlers));self.assertTrue(all(b'Il2CppExceptionWrapper' in bytes.fromhex(x['bytesHex']) for x in facts if x['label']=='native exception wrapper RTTI descriptor'))
 def test_actual_payload_joint_check_preserves_registration_and_world_limits(self):
  p=effect_list_range.load_profile()
  self.assertEqual({'ICollection','IEnumerator','IDisposable'},{x['typeName'] for x in p['payloadTypeSlots']})
  self.assertEqual({12691:'.ctor',12696:'set_Capacity',12710:'AddRange',12743:'Insert',12745:'InsertRange'},{m['index']:m['name'] for m in p['boundMethodIdentities']})
  for k,v in p.items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualPayloadRangeAndCapacitySetterJointlyTested']);self.assertTrue(self.report['nullableCaughtNativeWrapperFallthroughControlImplemented']);self.assertFalse(self.report['sharedHelperRegistrationOrRgctxIdentityInstalled']);self.assertFalse(self.report['nativeExceptionRuntimeOrPersonalityInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
