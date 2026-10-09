"""逐项核对同一登记编号消费、原始参数、共享泛型成员和耗尽原因。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('additional_bp_cost',ROOT/'scripts/read-rogue-additional-bp-cost.py');additional_bp_cost=importlib.util.module_from_spec(spec);spec.loader.exec_module(additional_bp_cost)
def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in additional_bp_cost.load_profile()['nativeMethods'] for i in m['instructions']}
class AdditionalBpCostTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=additional_bp_cost.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-additional-bp-cost-python-evidence.json'))
 def test_complete_cost_destroy_source_fields_generic_and_parameter_records_match(self):
  self.assertEqual((1,312,1,964,3,1,1,2),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFieldArguments','matchedNativeParameterRecords']))
 def test_null_and_zero_initial_count_return_before_any_destroy_request(self):
  r=rows();self.assertEqual(('mov','rcx, qword ptr [rsi + 0x1f8]'),r['0xe80e50b']);self.assertEqual(('je','0x18e80e5c1'),r['0xe80e515']);self.assertEqual(('call','0x19a6a9af0'),r['0xe80e51d']);self.assertEqual(('je','0x18e80e5c1'),r['0xe80e524'])
 def test_second_count_is_read_before_positive_reverse_scan(self):
  r=rows();self.assertEqual(('mov','rcx, qword ptr [rsi + 0x1f8]'),r['0xe80e52a']);self.assertEqual(('call','0x19a6a9af0'),r['0xe80e53c']);self.assertEqual(('jle','0x18e80e594'),r['0xe80e543']);self.assertEqual(('dec','ebx'),r['0xe80e547'])
 def test_current_record_id_controls_current_list_removal_without_team_read(self):
  r=rows();self.assertEqual(('mov','rdx, qword ptr [rsi + 0x1f8]'),r['0xe80e55c']);self.assertEqual(('call','0x18e816f20'),r['0xe80e571']);self.assertEqual(('cmp','dword ptr [rsp + 0x30], edi'),r['0xe80e576']);self.assertEqual(('mov','rcx, qword ptr [rsi + 0x1f8]'),r['0xe80e57c']);self.assertEqual(('call','0x19a6ad850'),r['0xe80e58d'])
 def test_exhaustion_uses_current_null_or_zero_list_and_reason_four(self):
  r=rows();self.assertEqual(('mov','rcx, qword ptr [rsi + 0x1f8]'),r['0xe80e59d']);self.assertEqual(('je','0x18e80e5b4'),r['0xe80e5a7']);self.assertEqual(('jne','0x18e80e5c1'),r['0xe80e5b2']);self.assertEqual(('mov','edx, 4'),r['0xe80e5b7']);self.assertEqual(('call','0x18e7c5af0'),r['0xe80e5bc']);p=additional_bp_cost.load_profile();f=next(f for f in p['matchedTypes'][2]['fields'] if f['name']=='Exhausted');self.assertEqual(4,f['constant']['value'])
 def test_source_list_is_same_existing_stack_event_entry_type(self):
  p=additional_bp_cost.load_profile();f=p['genericFields'][0];self.assertEqual(504,f['field']['instanceOffset']);self.assertEqual((5636,55831),(f['record']['genericDefinition'],f['record']['args'][0]['payload']));entry=p['matchedTypes'][1];self.assertEqual([(16,'MCLLALGABAB'),(24,'GMDBHJKEAEI')],[(f['instanceOffset'],f['name']) for f in entry['fields']])
 def test_cost_int32_and_destroy_enum_parameter_types_match_raw_method_rows(self):
  p=additional_bp_cost.load_profile();self.assertEqual([(510148,8,285),(510206,17,18261)],[(r['methodIndex'],r['record']['kind'],r['record']['payload']) for r in p['parameterRecords']]);self.assertEqual('Destroy',p['boundMethodIdentities'][0]['name'])
 def test_runtime_world_destroy_hotfix_and_complete_gameplay_boundaries_remain_explicit(self):
  for k,v in additional_bp_cost.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualSpecialWithdrawalPointRefreshCostJointlyTested']);self.assertFalse(self.report['fullDestroyBodyImplemented']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
