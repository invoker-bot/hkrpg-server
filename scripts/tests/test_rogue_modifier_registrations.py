"""核对名称、绑定、AI、分组撤回的普通顺序与字段来源。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_registrations',ROOT/'scripts/read-rogue-modifier-registrations.py');modifier_registrations=importlib.util.module_from_spec(spec);spec.loader.exec_module(modifier_registrations)
def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in modifier_registrations.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}
class ModifierRegistrationsTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=modifier_registrations.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-registrations-python-evidence.json'))
 def test_complete_methods_helpers_templates_fields_and_specs_match(self):
  self.assertEqual((6,2204,3,834,1,2790,11,6,7,4,2,6),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedGenericDefinitionDeclarations','matchedGenericParentRecords','matchedPoolMethodSpecs']))
 def test_name_enumeration_checks_captured_version_before_move_and_current_owner_each_key(self):
  r=rows(510275);self.assertEqual(('mov','r12, qword ptr [rsi + 0xa8]'),r['0xe81612b']);self.assertEqual(('cmp','r14d, dword ptr [r12 + 0x38]'),r['0xe81616a']);self.assertEqual(('cmp','dword ptr [rdi], 0'),r['0xe8161d9']);self.assertEqual(('mov','rax, qword ptr [rax + 0xd0]'),r['0xe81621d']);self.assertEqual(('mov','rcx, qword ptr [rax + 0xd0]'),r['0xe816229']);self.assertEqual(('call','0x19b0f5980'),r['0xe816245']);self.assertEqual(('mov','rsi, qword ptr [rax + 0xa8]'),r['0xe8162a2'])
 def test_binding_found_value_recycles_before_remove_and_absent_map_keeps_cached_version(self):
  r=rows(510276);self.assertEqual(('mov','eax, r15d'),r['0xe8163aa']);self.assertEqual(('mov','eax, r15d'),r['0xe816474']);self.assertEqual(('je','0x18e8163ad'),r['0xe81647a']);self.assertEqual(('call','0x19b0f4110'),r['0xe8164a6']);self.assertEqual(('js','0x18e8164d5'),r['0xe8164ad']);self.assertEqual(('call','0x18b10ce60'),r['0xe8164d0']);self.assertEqual(('call','0x19b0f5980'),r['0xe8164e2']);self.assertEqual(('mov','eax, dword ptr [r14 + 0x38]'),r['0xe8164e7']);self.assertEqual(('mov','rsi, qword ptr [rax + 0x1b8]'),r['0xe816559'])
 def test_set_clear_stores_slots_then_bucket_prefix_then_count_last_free_then_version(self):
  r=rows(510275);self.assertEqual(('mov','r8d, dword ptr [rsi + 0x28]'),r['0xe8162ae']);self.assertEqual(('call','0x19c074b80'),r['0xe8162bd']);self.assertEqual(('mov','r8d, dword ptr [rsi + 0x20]'),r['0xe8162c6']);self.assertEqual(('call','0x19c074b80'),r['0xe8162cc']);self.assertEqual(('mov','qword ptr [rsi + 0x24], 0'),r['0xe8162d1']);self.assertEqual(('mov','dword ptr [rsi + 0x2c], 0xffffffff'),r['0xe8162d9']);self.assertEqual(('inc','dword ptr [rsi + 0x38]'),r['0xe8162e0']);self.assertEqual(('jle','0x18e8162e0'),r['0xe8162b5'])
 def test_ai_removes_current_cache_then_selects_before_clearing_cache(self):
  r=rows(510267);self.assertEqual(('mov','rbx, qword ptr [rsi + 0xc8]'),r['0xe816657']);self.assertEqual(('call','0x1980b67d0'),r['0xe81667a']);self.assertEqual(('call','0x18bc31160'),r['0xe816682']);self.assertEqual(('mov','qword ptr [rdi + 0x58], rax'),r['0xe816687']);self.assertEqual(('mov','qword ptr [rsi + 0xc8], 0'),r['0xe81668b']);r=rows(534804);self.assertEqual(('call','0x196f21e20'),r['0xbc3118f']);self.assertEqual(('mov','rcx, qword ptr [rsi + 0x70]'),r['0xbc31194']);self.assertEqual(('mov','rax, qword ptr [rax + 0x20]'),r['0xbc311b2']);r=rows(513267);self.assertEqual(('cmp','eax, dword ptr [rsi + 0x1c]'),r['0x158da79f']);self.assertEqual(('setg','cl'),r['0x158da7a2'])
 def test_upper_ids_are_uint_and_retained_after_mapping_removal(self):
  r=rows(510161);self.assertEqual(('call','0x19a99a780'),r['0xe80fa27']);self.assertEqual(('mov','rdi, qword ptr [rdi + 0x218]'),r['0xe80fa9b']);self.assertEqual(('mov','edx, dword ptr [rcx + 0x24]'),r['0xe80fae4']);self.assertEqual(('call','0x19a69bad0'),r['0xe80fb15']);self.assertEqual(('call','0x19b213e10'),r['0xe80fb40']);self.assertEqual(('jmp','0x18e80fb10'),r['0xe80fb45']);p=modifier_registrations.load_profile();g=next(f['record'] for f in p['genericFields'] if f['field']['name']=='_StackUpperGroupList');self.assertEqual(('PoolablePooledList`1',9),(g['genericName'],g['args'][0]['kind']));self.assertFalse(any(i['mnemonic']=='call' and i['operand']=='0x19c074b80' for i in p['nativeMethods'][0]['instructions']))
 def test_names_and_bind_keys_are_strings_and_generic_parent_records_remain_templates(self):
  p=modifier_registrations.load_profile();self.assertEqual([14,14],[p['genericFields'][i]['record']['args'][0]['kind'] for i in [0,1]]);self.assertEqual([(5636,'PooledList`1',19),(5637,'PooledSet`1',19)],[(r['typeIndex'],r['parent']['genericName'],r['parent']['args'][0]['kind']) for r in p['genericParentRecords']]);spec=next(r for r in p['poolMethodSpecs'] if r['methodSpec']['methodDefinition']==41452);self.assertEqual((18,57756),(spec['instRecords'][0]['arguments'][0]['kind'],spec['instRecords'][0]['arguments'][0]['payload']));self.assertFalse(p['genericTemplateOffsetsAreConcreteInstanceLayouts'])
 def test_finite_binding_pool_joint_tests_do_not_claim_native_clear_sort_world_or_client(self):
  for k,v in modifier_registrations.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['bindingRecycleThroughActualReferencePoolFixtureTested']);self.assertFalse(self.report['nativeBindingPayloadConstructorAndClearInstalled']);self.assertFalse(self.report['nativeAiSortAndEqualPriorityOrderingInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
