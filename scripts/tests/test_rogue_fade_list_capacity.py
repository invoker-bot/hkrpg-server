"""核对效果列表容量约束、当前复制与最终发布的完整来源。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('fade_list_capacity',ROOT/'scripts/read-rogue-fade-list-capacity.py');fade_list_capacity=importlib.util.module_from_spec(spec);spec.loader.exec_module(fade_list_capacity)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in fade_list_capacity.load_profile()['runtimeMethods'] for i in m['instructions']}
class FadeListCapacityTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=fade_list_capacity.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-fade-list-capacity-python-evidence.json'))
 def test_full_helper_auxiliary_types_generics_and_spec_match(self):
  self.assertEqual((0,0,1,369,4,1220,1,2,1,1,0,1,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedPayloadTypeSlots','matchedRipBindings','matchedPoolMethodSpecs']))
 def test_count_guard_precedes_storage_and_equal_capacity_return(self):
  r=rows();self.assertEqual(('cmp','dword ptr [rcx + 0x18], edx'),r['0x180b36d7']);self.assertEqual(('jg','0x1980b3831'),r['0x180b36da']);self.assertEqual(('mov','rax, qword ptr [rcx + 0x10]'),r['0x180b36e3']);self.assertEqual(('cmp','dword ptr [rax + 0x18], edx'),r['0x180b36f2']);self.assertEqual(('je','0x1980b37a0'),r['0x180b36f5'])
 def test_positive_capacity_allocates_then_rereads_current_count(self):
  r=rows();self.assertEqual(('test','ebx, ebx'),r['0x180b3705']);self.assertEqual(('jle','0x1980b374e'),r['0x180b3707']);self.assertEqual(('call','0x183d1ad70'),r['0x180b3728']);self.assertEqual(('mov','eax, dword ptr [rsi + 0x18]'),r['0x180b3730']);self.assertEqual(('test','eax, eax'),r['0x180b3733'])
 def test_copy_uses_current_storage_zero_indexes_and_current_count(self):
  r=rows();self.assertEqual(('mov','rcx, qword ptr [rsi + 0x10]'),r['0x180b3737']);self.assertEqual(('mov','dword ptr [rsp + 0x20], eax'),r['0x180b373b']);self.assertEqual(('xor','edx, edx'),r['0x180b373f']);self.assertEqual(('xor','r9d, r9d'),r['0x180b3744']);self.assertEqual(('call','0x19c075050'),r['0x180b3747'])
 def test_nonpositive_capacity_resolves_shared_empty_array_without_copy(self):
  r=rows();self.assertEqual(('mov','rbx, qword ptr [rax + 0x10]'),r['0x180b374e']);self.assertEqual(('mov','rax, qword ptr [rdi + 0x88]'),r['0x180b3792']);self.assertEqual(('mov','rdi, qword ptr [rax]'),r['0x180b3799'])
 def test_array_publication_is_last_without_version_or_count_writes(self):
  r=rows();self.assertEqual(('mov','qword ptr [rsi + 0x10], rdi'),r['0x180b379c']);self.assertFalse(any(m in ['inc','dec'] for m,o in r.values()));self.assertFalse(any(m=='mov' and o.startswith('dword ptr [rsi + 0x18],') for m,o in r.values()));self.assertFalse(any(m=='mov' and o.startswith('dword ptr [rsi + 0x1c],') for m,o in r.values()))
 def test_effect_list_argument_and_set_capacity_declaration_keep_registration_limit(self):
  p=fade_list_capacity.load_profile();record=p['genericFields'][0]['record'];self.assertEqual((1570,54431,'ModifierEffectData'),(record['genericDefinition'],record['args'][0]['payload'],record['args'][0]['name']));m=next(m for m in p['boundMethodIdentities'] if m['index']==12696);self.assertEqual(('set_Capacity','-0x180000000'),(m['name'],m['rva']));self.assertFalse(self.report['capacityDeclarationToSharedHelperRegistrationIdentityInstalled'])
 def test_actual_initializer_uses_actual_capacity_control_with_pending_world_and_array_runtime(self):
  for k,v in fade_list_capacity.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualSingleEffectInitializerUsesActualCapacitySetterJointlyTested']);self.assertFalse(self.report['nativeArrayCopyBodyImplemented']);self.assertFalse(self.report['nativeSharedEmptyArrayOrClassInitializationInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
