"""核对离场与不可选中引用登记的完整普通方法及列表辅助函数。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('owned_parameter_lists',ROOT/'scripts/read-rogue-owned-parameter-lists.py')
owned_parameter_lists=importlib.util.module_from_spec(spec);spec.loader.exec_module(owned_parameter_lists)

def rows(index):return {i['rva']:(i['mnemonic'],i['operand']) for m in owned_parameter_lists.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}
def runtime(rva):return {i['rva']:(i['mnemonic'],i['operand']) for m in owned_parameter_lists.load_profile()['runtimeMethods'] if m['rva']==rva for i in m['instructions']}

class OwnedParameterListsTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=owned_parameter_lists.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-owned-parameter-lists-python-evidence.json'))
 def test_complete_bodies_helpers_fields_bindings_generics_and_parameters_match(self):
  self.assertEqual((3,1470,2,623,5,3,2,7),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedNativeParameterRecords']))
 def test_reference_remove_publishes_count_before_shift_clear_and_version(self):
  r=runtime('0x180b67d0');self.assertEqual(('mov','dword ptr [rsi + 0x18], eax'),r['0x180b688b']);self.assertEqual(('call','0x19c075050'),r['0x180b68a3']);self.assertEqual(('mov','qword ptr [rax + rcx*8 + 0x20], 0'),r['0x180b68be']);self.assertEqual(('inc','dword ptr [rsi + 0x1c]'),r['0x180b68c7'])
 def test_inline_add_version_precedes_storage_and_growth_uses_four_or_double(self):
  r=rows(510608);self.assertEqual(('inc','dword ptr [rbx + 0x1c]'),r['0xe7d2334']);self.assertEqual(('mov','rax, qword ptr [rbx + 0x10]'),r['0xe7d2337']);self.assertEqual(('mov','dword ptr [rbx + 0x18], ecx'),r['0xe7d2350']);self.assertEqual(('mov','qword ptr [rax + rdx*8 + 0x20], rdi'),r['0xe7d2353'])
  r=runtime('0x180b74a0');self.assertEqual(('lea','eax, [rcx + rcx]'),r['0x180b74e4']);self.assertEqual(('mov','ebx, 4'),r['0x180b74f7']);self.assertEqual(('call','0x1980b36d0'),r['0x180b751c']);self.assertEqual(('mov','dword ptr [rdi + 0x18], ebp'),r['0x180b7525'])
 def test_departed_identity_skip_then_remove_add_visibility_and_presence_transition(self):
  r=rows(510608);self.assertEqual(('cmp','rbx, rdi'),r['0xe7d22cf']);self.assertEqual(('je','0x18e7d2523'),r['0xe7d22d2']);self.assertEqual(('call','0x1980b67d0'),r['0xe7d2316']);self.assertEqual(('call','0x18e7d2610'),r['0xe7d237c']);self.assertEqual(('cmp','dword ptr [rax + 0x18], 0'),r['0xe7d239b']);self.assertEqual(('cmp','r14b, al'),r['0xe7d23a2'])
 def test_visibility_captures_list_prefix_and_checks_second_flag_before_optional_setter(self):
  r=rows(510611);self.assertEqual(('movsxd','rax, dword ptr [rcx + 0x18]'),r['0xe7d2634']);self.assertEqual(('mov','rcx, qword ptr [rcx + 0x10]'),r['0xe7d263d']);self.assertEqual(('cmp','byte ptr [rsi + 0x11], 0'),r['0xe7d2667']);self.assertEqual(('jne','0x18e7d267d'),r['0xe7d266b']);self.assertEqual(('not','dil'),r['0xe7d2697']);self.assertEqual(('mov','r8b, 1'),r['0xe7d26a3']);self.assertEqual(('mov','r9b, 0x1e'),r['0xe7d26a6']);self.assertEqual(('jmp','0x18e607320'),r['0xe7d26b0'])
 def test_unselectable_cross_entity_logs_and_same_entity_removes_then_adds_then_refreshes(self):
  r=rows(503914);self.assertEqual(('cmp','rdi, rsi'),r['0xe80079f']);self.assertEqual(('cmp','rax, rcx'),r['0xe8007ba']);self.assertEqual(('jmp','0x19dd6b190'),r['0xe800805']);self.assertEqual(('call','0x1980b67d0'),r['0xe800852']);self.assertEqual(('inc','dword ptr [rdi + 0x1c]'),r['0xe800873']);self.assertEqual(('jmp','0x18e7ae080'),r['0xe8008dd'])
 def test_compiled_container_arguments_and_bound_setter_error_and_refresh_are_exact(self):
  p=owned_parameter_lists.load_profile();self.assertEqual([('List`1','GOCCDKLAIKG'),('List`1','LJGNANFNLDF')],[(f['record']['genericName'],f['record']['args'][0]['name']) for f in p['genericFields']]);self.assertEqual([47392,510382,529186],[m['index'] for m in p['boundMethodIdentities']]);self.assertEqual([(0,2),(1,2),(2,17)],[(r['parameter']['ordinal'],r['record']['kind']) for r in p['parameterRecords'] if r['methodIndex']==529186]);self.assertEqual('CharacterVisibleReason',p['parameterRecords'][-1]['record']['name'])
 def test_finite_registration_core_is_available_while_world_factories_and_acceptance_stay_unclaimed(self):
  p=owned_parameter_lists.load_profile()
  for key,value in p.items():
   if isinstance(value,bool):self.assertIs(value,self.report[key],key)
  self.assertIs(True,self.report['actualModifierOwnedWithdrawalsIntegrated']);self.assertIs(False,self.report['fullNativeOwnedRegistrationRuntimeImplemented']);self.assertIs(False,self.report['clientGameplayVerified']);self.assertIs(False,self.report['clientModified'])

if __name__=='__main__':unittest.main()
