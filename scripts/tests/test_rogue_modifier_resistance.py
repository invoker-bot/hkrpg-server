"""行为抗性最大值、独立补数乘积和字段来源的有限边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_resistance',ROOT/'scripts/read-rogue-modifier-resistance.py')
resistance=importlib.util.module_from_spec(spec);spec.loader.exec_module(resistance)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in resistance.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierResistanceTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.report=resistance.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-resistance-python-evidence.json'))

 def test_complete_methods_and_declared_fields_match_installed_client(self):
  self.assertEqual((5,8963,6),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes']))

 def test_actual_dicts_share_behavior_enum_to_property_value_type(self):
  p=resistance.load_profile()
  self.assertEqual(4,self.report['matchedMappingGenericArguments'])
  self.assertEqual([('_StatusChanceResistanceDict',224),('_StatusProbabilityDict',416)],
   [(r['field']['name'],r['field']['instanceOffset']) for r in p['mappingGenerics']])
  for r in p['mappingGenerics']:
   self.assertEqual((1535,[(17,10407),(18,56339)]),(r['genericDefinition'],[(a['kind'],a['definition']) for a in r['args']]))
  value=next(t for t in p['matchedTypes'] if t['typeIndex']==56339)['selectedFields'][0]
  self.assertEqual(('EJNMGLJJEOO',120,9939),(value['name'],value['instanceOffset'],value['nativeType']['typeIndex']))

 def test_behavior_lookup_reads_actual_mapping_and_current_value_and_chooses_maximum(self):
  r=rows(510399)
  self.assertEqual(('mov','rdi, qword ptr [r15 + 0xe0]'),r['0xe7b0c42'])
  self.assertEqual(('mov','r13, qword ptr [rax + 0x78]'),r['0xe7b0caa'])
  self.assertEqual(('cmp','r13, r14'),r['0xe7b0dbd'])
  self.assertEqual(('jg','0x18e7b0c20'),r['0xe7b0de0'])
  self.assertEqual(('mov','r13, r14'),r['0xe7b0de6'])
  self.assertEqual(('mov','rax, qword ptr [rax + 0x78]'),r['0xe7b0eda'])
  self.assertEqual(('jg','0x18e7b0e50'),r['0xe7b0f92'])

 def test_anti_flag_membership_skips_only_resistance_below_one(self):
  r=rows(510399)
  self.assertEqual(('call','0x1980571d0'),r['0xe7b0cba'])
  self.assertEqual(('je','0x18e7b0cf0'),r['0xe7b0cc1'])
  self.assertEqual(('mov','rcx, qword ptr [rip - 0x546d00a]'),r['0xe7b0cc3'])
  self.assertEqual(('cmp','r13, rcx'),r['0xe7b0cd8'])
  self.assertEqual(('jl','0x18e7b0c23'),r['0xe7b0cdb'])
  self.assertEqual(0x9343cc0,0xe7b0cca-0x546d00a)

 def test_status_factor_reads_property_then_two_subtractions_and_one_product(self):
  r=rows(510398)
  self.assertEqual(('mov','edx, 0x87'),r['0xe7b0b37'])
  self.assertEqual(('call','0x18e766260'),r['0xe7b0b3c'])
  self.assertEqual(('call','0x19dccb2c0'),r['0xe7b0b47'])
  self.assertEqual(('call','0x18e7b0bc0'),r['0xe7b0b5f'])
  self.assertEqual(('call','0x19dccb2c0'),r['0xe7b0b6a'])
  self.assertEqual(('jmp','0x19dccb160'),r['0xe7b0b80'])

 def test_entry_extra_hit_reads_property_then_behavior_probability_and_addition(self):
  r=rows(509996)
  self.assertEqual(('mov','edx, 0x84'),r['0x16546ad2'])
  self.assertEqual(('call','0x18e766260'),r['0x16546ad7'])
  self.assertEqual(('call','0x18e7b1300'),r['0x16546ae5'])
  self.assertEqual(('call','0x19dcc86e0'),r['0x16546af7'])
  self.assertEqual(('mov','qword ptr [rbp + 0x1b0], rax'),r['0x16546afc'])
  p=resistance.load_profile();t=next(t for t in p['matchedTypes'] if t['name']=='AbilityProperty')
  self.assertEqual([('StatusProbability',132),('StatusResistance',135)],[(f['name'],f['constant']['value']) for f in t['selectedFields']])

 def test_behavior_probability_reads_current_mapping_maximum_and_rechecks_count(self):
  r=rows(510403)
  self.assertEqual(('mov','rdi, qword ptr [r14 + 0x1a0]'),r['0xe7b1371'])
  self.assertEqual(('mov','rax, qword ptr [rax + 0x78]'),r['0xe7b13d9'])
  self.assertEqual(('cmp','rax, r12'),r['0xe7b13ee'])
  self.assertEqual(('jg','0x18e7b1350'),r['0xe7b1492'])
  self.assertEqual(('mov','rax, r12'),r['0xe7b1498'])
  self.assertEqual(('movsxd','rcx, eax'),r['0xe7b135a'])

 def test_initial_zero_and_complement_one_are_actual_constructor_sources(self):
  p=resistance.load_profile()
  self.assertEqual((1<<33,'0x9343cc0'),(p['oneInitializer']['raw'],p['oneInitializer']['slotRva']))
  self.assertEqual((0,'0x9343d30',16),(p['zeroInitializer']['missingRaw'],p['zeroInitializer']['missingWordDestinationRva'],self.report['zeroInitializerBytes']))
  self.assertEqual(6,self.report['sourceModifierCommands'])
  fragment=p['standardSubtractionFragment']
  self.assertEqual((61,False),(self.report['matchedStandardSubtractionFragmentBytes'],fragment['completeNativeMethod']))
  self.assertEqual((68470,'op_Subtraction'),tuple(p['subtractionDefinition'][k] for k in ['index','name']))
  r={i['rva']:(i['mnemonic'],i['operand']) for i in fragment['instructions']}
  self.assertEqual(('sub','rax, rdx'),r['0x1dccb2cb'])
  self.assertEqual(('cmp','rdx, 0x40000000'),r['0x1dccb2f0'])

 def test_report_keeps_projected_values_separate_from_live_properties_rolls_and_instances(self):
  r=self.report
  for k in ['finiteBehaviorResistanceMaximumImplemented','finiteStatusFactorImplemented','finiteExtraHitChanceImplemented',
   'antiFlagsPreserveCompleteImmunity','orderedPropertyAndMappingReadsPreserved','standardArithmeticOnly']:self.assertTrue(r[k],k)
  for k in ['actualStatusDictionaryValueRuntimeImplemented','actualPropertyProviderImplemented','componentDictionariesInstalled',
   'actualChanceRollImplemented','modifierInstanceApplied','fullOriginalAddModifierRuntimeImplemented',
   'completeResonanceRuntimeImplemented','clientGameplayVerified','standardSubtractionCompleteNativeMethod',
   'jsonInstalled','clientExecutableStarted','clientModified']:self.assertFalse(r[k],k)

 def test_output_rejects_client_and_nonbuild_targets_before_verification(self):
  for output in [CLIENT/'resistance.json',ROOT/'resistance.json']:
   with self.assertRaises(ValueError):resistance.analyze(SimpleNamespace(client_dir=CLIENT,output=output))

if __name__=='__main__':unittest.main()
