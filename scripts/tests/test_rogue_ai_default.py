"""核对默认AI静态构造、配置两级查询及共享对象发布顺序。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('ai_default',ROOT/'scripts/read-rogue-ai-default.py');ai_default=importlib.util.module_from_spec(spec);spec.loader.exec_module(ai_default)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in ai_default.load_profile()['nativeMethods'] for i in m['instructions']}
class AiDefaultTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=ai_default.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-ai-default-python-evidence.json'))
 def test_complete_native_fields_leaf_layout_generic_and_slots_match(self):
  self.assertEqual((2,414,1,1,5,1,2,2,2,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedLeafFragments','matchedLeafFragmentBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedStaticLayoutRecords','matchedGenericFields','matchedStringLiteralSlots','matchedTypeInfoSlots']))
 def test_empty_constructor_fragment_remains_separate_from_full_methods(self):
  p=ai_default.load_profile();leaf=p['leafFragments'][0];self.assertEqual((513265,'0x158da7f0','c3'),(leaf['index'],leaf['rva'],leaf['bytesHex']));self.assertNotEqual('PE Exception Directory',leaf['bounds']);self.assertEqual([107651,513266],sorted(m['index'] for m in p['nativeMethods']))
 def test_priority_lookup_then_normal_layout_and_flags_then_publish(self):
  r=rows();self.assertEqual(('call','0x19d9135f0'),r['0x158da82e']);self.assertEqual(('mov','dword ptr [rsi + 0x1c], eax'),r['0x158da838']);self.assertEqual(('mov','edx, 1'),r['0x158da842']);self.assertEqual(('mov','dword ptr [rax + 0x20], 1'),r['0x158da857']);self.assertEqual(('mov','qword ptr [rsi + 0x10], rax'),r['0x158da85e']);self.assertEqual(('mov','word ptr [rsi + 0x18], 0x101'),r['0x158da862']);self.assertEqual(('mov','qword ptr [rax + 0x42d70], rsi'),r['0x158da86f'])
 def test_normal_is_independently_matched_enum_one_and_behavior_field_is_enum_array(self):
  p=ai_default.load_profile();behavior=p['matchedTypes'][0];enum=p['matchedTypes'][1];normal=next(f for f in enum['fields'] if f['name']=='Normal');self.assertEqual((24622,'SkillLayoutKey',1),(enum['typeIndex'],enum['name'],normal['constant']['value']));array=next(f for f in behavior['fields'] if f.get('instanceOffset')==16);self.assertEqual((29,24622),(array['nativeType']['kind'],array['nativeType']['elementType']['typeIndex']));self.assertEqual([24,25,28],[f['instanceOffset'] for f in behavior['fields'] if f.get('instanceOffset',0)>16])
 def test_two_literal_keys_and_two_typed_dictionary_layers_are_distinct(self):
  p=ai_default.load_profile();self.assertEqual(['CrazyAIBehaviorPriority','PROG_Default'],[s['value'] for s in p['stringLiteralSlots']]);self.assertEqual([17126,17125],[f['ownerTypeIndex'] for f in p['genericFields']]);outer,inner=[f['record'] for f in p['genericFields']];self.assertEqual((1535,18,17125),(outer['genericDefinition'],outer['args'][1]['kind'],outer['args'][1]['payload']));self.assertEqual((1535,14,8),(inner['genericDefinition'],inner['args'][0]['kind'],inner['args'][1]['kind']))
 def test_shared_config_and_default_layout_keep_raw_static_reference_tags(self):
  p=ai_default.load_profile();self.assertEqual([(55640,'0x4042d70'),(56506,'0x40016c0')],[(r['typeIndex'],r['rawOffset']) for r in p['staticLayouts']]);field=p['matchedTypes'][4]['selectedFields'][0];self.assertEqual(('PriorityConfigList',True,17126),(field['name'],field['isStatic'],field['nativeType']['typeIndex']))
 def test_missing_lookup_entries_raise_instead_of_returning_zero(self):
  r=rows();self.assertEqual(('test','eax, eax'),r['0x1d913634']);self.assertEqual(('js','0x19d9136ec'),r['0x1d913636']);self.assertEqual(('test','eax, eax'),r['0x1d913689']);self.assertEqual(('js','0x19d9136f4'),r['0x1d91368b']);self.assertEqual(('call','0x19c139b60'),r['0x1d9136ef']);self.assertEqual(('call','0x19c139b60'),r['0x1d9136f7']);self.assertTrue(self.report['missingPriorityNeverReplacedWithInventedZero'])
 def test_actual_selection_and_withdrawal_do_not_claim_world_or_official_config_value(self):
  for k,v in ai_default.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualAiSelectionComparatorAndWithdrawalJointlyTested']);self.assertFalse(self.report['officialPriorityResourceValueKnown']);self.assertFalse(self.report['globalNativeCatalogFactoryInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
