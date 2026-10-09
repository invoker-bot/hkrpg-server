"""核对离场事件尾部、池泛型载荷、两级分发与失败顺序。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('departed_transition',ROOT/'scripts/read-rogue-departed-transition.py')
departed_transition=importlib.util.module_from_spec(spec);spec.loader.exec_module(departed_transition)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for i in departed_transition.load_profile()['nativeMethods'][0]['instructions']}

class DepartedTransitionTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=departed_transition.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-departed-transition-python-evidence.json'))
 def test_complete_source_fields_bound_definitions_parameters_pool_specs_and_enum_constants_match(self):
  self.assertEqual((1,772,7,4,12,2,4),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedNativeParameterRecords','matchedPoolMethodSpecs','matchedEventEnumConstants']))
 def test_acquire_and_release_specs_use_same_closed_event_argument(self):
  p=departed_transition.load_profile();self.assertEqual([(41438,39005,-1),(41452,39005,-1)],[(r['methodSpec']['methodDefinition'],r['methodSpec']['classInst'],r['methodSpec']['methodInst']) for r in p['poolMethodSpecs']]);self.assertEqual([(18,56184,'LPNPMKNMMOK')]*2,[(r['instRecords'][0]['arguments'][0]['kind'],r['instRecords'][0]['arguments'][0]['payload'],r['instRecords'][0]['arguments'][0]['name']) for r in p['poolMethodSpecs']]);self.assertEqual([41438,41452,520711,520712],[m['index'] for m in p['boundMethodIdentities']])
 def test_acquisition_precedes_owner_capture_assignment_and_required_world(self):
  r=rows();self.assertEqual(('call','0x18b10cfd0'),r['0xe7d23b2']);self.assertEqual(('mov','rbx, qword ptr [rsi + 0x10]'),r['0xe7d23c3']);self.assertEqual(('mov','qword ptr [rdi + 0x40], rbx'),r['0xe7d23d4']);self.assertEqual(('test','rbx, rbx'),r['0xe7d23d8']);self.assertEqual(('mov','rax, qword ptr [rbx + 0x88]'),r['0xe7d23e1'])
 def test_optional_battle_mode_processor_each_jumps_to_release(self):
  r=rows()
  for address in ['0xe7d23fb','0xe7d2404','0xe7d240d']:self.assertEqual(('je','0x18e7d245f'),r[address])
  self.assertEqual(('mov','rax, qword ptr [rax + 0x98]'),r['0xe7d23f1']);self.assertEqual(('mov','rax, qword ptr [rax + 0x18]'),r['0xe7d23fd']);self.assertEqual(('mov','r15, qword ptr [rax + 0x20]'),r['0xe7d2406'])
 def test_entity_then_current_owner_global_then_release_order_and_null_filters(self):
  r=rows();self.assertEqual(('lea','r8d, [rbp + 0xe2]'),r['0xe7d2413']);self.assertEqual(('mov','word ptr [rsp + 0x28], 0'),r['0xe7d241a']);self.assertEqual(('mov','qword ptr [rsp + 0x20], 0'),r['0xe7d2421']);self.assertEqual(('call','0x18bc3baa0'),r['0xe7d2433']);self.assertEqual(('or','ebp, 0xe4'),r['0xe7d2438']);self.assertEqual(('mov','r9, qword ptr [rsi + 0x10]'),r['0xe7d243e']);self.assertEqual(('mov','qword ptr [rsp + 0x28], 0'),r['0xe7d2442']);self.assertEqual(('mov','word ptr [rsp + 0x20], 0'),r['0xe7d244b']);self.assertEqual(('call','0x18bc3b990'),r['0xe7d245a']);self.assertEqual(('jmp','0x18b10ce60'),r['0xe7d2475'])
 def test_original_event_enum_names_and_owner_field_are_matched(self):
  types={t['typeIndex']:t for t in departed_transition.load_profile()['matchedTypes']};self.assertEqual([('OnDepartedStart',226),('OnDepartedEnd',227),('OnListenDepartedStart',228),('OnListenDepartedEnd',229)],[(f['name'],f['constant']['value']) for f in types[18238]['selectedFields']]);field=types[56184]['selectedFields'][0];self.assertEqual(('BCBBPMNBGEI',64,53551),(field['name'],field['instanceOffset'],field['nativeType']['typeIndex']))
 def test_both_dispatchers_take_event_payload_and_entity_source_and_filter_struct(self):
  p=departed_transition.load_profile()
  for method,expected in [(520711,[18238,56184,53551,10506]),(520712,[53551,18238,56184,53551,10506])]:
   actual=[r['record']['payload'] for r in p['parameterRecords'] if r['methodIndex']==method and r['parameter']['ordinal']<len(expected)];self.assertEqual(expected,actual)
 def test_finite_tail_and_integration_available_without_claiming_factory_dispatch_or_client(self):
  p=departed_transition.load_profile()
  for key,value in p.items():
   if isinstance(value,bool):self.assertIs(value,self.report[key],key)
  self.assertIs(True,self.report['actualRegistrationPresenceTransitionIntegrated']);self.assertIs(False,self.report['nativeEntityAndGlobalDispatcherBodiesInstalled']);self.assertIs(False,self.report['clientGameplayVerified']);self.assertIs(False,self.report['clientModified'])

if __name__=='__main__':unittest.main()
