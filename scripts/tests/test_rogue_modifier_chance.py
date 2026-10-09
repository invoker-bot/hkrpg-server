"""概率计算的完整原生方法、泛型绑定与有限运行边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_chance',ROOT/'scripts/read-rogue-modifier-chance.py')
chance=importlib.util.module_from_spec(spec);spec.loader.exec_module(chance)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in chance.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierChanceTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.report=chance.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-chance-python-evidence.json'))

 def test_complete_methods_and_declared_types_match_installed_client(self):
  self.assertEqual((9,9875,7),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes']))

 def test_source_chance_is_evaluated_before_override_and_absolute_uses_presence_context(self):
  r=rows(510002)
  for address,row in {'0x16548f59':('mov','rdx, qword ptr [rax + 0x40]'),
   '0x16548f5d':('call','0x18e777c90'),'0x16548f6e':('mov','rcx, qword ptr [rbx + 0x80]'),
   '0x16548f7e':('mov','rdx, qword ptr [rbx + 0x30]'),'0x16548f87':('call','0x18e777c90'),
   '0x16548f8c':('jmp','0x1965490b8')}.items():self.assertEqual(row,r[address])

 def test_negative_guard_precedes_affine_reads_and_raw_aggregation_has_no_round(self):
  r=rows(510002)
  self.assertEqual(('cmp','rdi, rax'),r['0x16548fc1'])
  self.assertEqual(('jge','0x196549035'),r['0x16548fc4'])
  self.assertEqual(('mov','rcx, qword ptr [rbx + 0x80]'),r['0x1654903e'])
  self.assertEqual(('mov','rcx, qword ptr [rbx + 0x80]'),r['0x16549071'])
  self.assertEqual(('call','0x19dccb160'),r['0x165490a8'])
  self.assertEqual(('call','0x19dcc86e0'),r['0x165490b3'])
  self.assertEqual(('jmp','0x195c61070'),r['0x165490da'])
  self.assertFalse(any(m=='call' and o=='0x196630d10' for m,o in r.values()))

 def test_external_adjustments_sum_then_multiply_once_and_reread_count(self):
  r=rows(508761)
  self.assertEqual(('call','0x19dcc86e0'),r['0x15c611d1'])
  self.assertEqual(('call','0x19dcc86e0'),r['0x15c6114a'])
  self.assertEqual(('movsxd','rax, dword ptr [r12 + 0x18]'),r['0x15c61155'])
  self.assertEqual(('call','0x19dccb160'),r['0x15c6123c'])
  self.assertEqual(('jmp','0x19dcc86e0'),r['0x15c61254'])
  self.assertEqual(1,sum(m=='call' and o=='0x19dccb160' for m,o in r.values()))
  self.assertEqual(('mov','rcx, qword ptr [rdi + 0x78]'),r['0x15c611a8'])
  self.assertEqual(('mov','rcx, qword ptr [rdi + 0x78]'),r['0x15c611e6'])

 def test_named_match_precedes_flags_and_uses_actual_string_equals(self):
  r=rows(508763)
  self.assertEqual(('mov','rdx, qword ptr [rsi + 0x10]'),r['0x15c6153e'])
  self.assertEqual(('je','0x195c61685'),r['0x15c61551'])
  self.assertEqual(('jmp','0x19c0cced0'),r['0x15c61578'])
  self.assertEqual(('mov','rax, qword ptr [rsi + 0x80]'),r['0x15c6157d'])
  self.assertEqual(('call','0x19dc35e90'),r['0x15c615e8'])
  self.assertEqual(('mov','rcx, qword ptr [r14 + 0x1b0]'),r['0x15c615fa'])
  self.assertEqual(('jg','0x195c616dc'),r['0x15c616bf'])

 def test_collector_allocates_only_after_first_match_and_entry_returns_list_normally(self):
  r=rows(508762)
  self.assertEqual(('mov','rax, qword ptr [rdi + 0xa8]'),r['0x15c61310'])
  self.assertEqual(('call','0x195c61510'),r['0x15c613a3'])
  self.assertEqual(('jne','0x195c613d1'),r['0x15c613af'])
  self.assertEqual(('call','0x18b10cfd0'),r['0x15c613c0'])
  self.assertEqual(('mov','qword ptr [rax + rcx*8 + 0x20], rbx'),r['0x15c613f8'])
  r=rows(509996)
  self.assertEqual(('call','0x195c612d0'),r['0x16547069'])
  self.assertEqual(('call','0x196548f20'),r['0x1654707a'])
  self.assertEqual(('call','0x18b10ce60'),r['0x16547091'])

 def test_mapping_and_list_have_matched_real_generic_arguments(self):
  p=chance.load_profile();mapping,mute=p['mappingGenerics'];slot=p['acquireListSlot']
  self.assertEqual((3,1),(self.report['matchedMappingGenericArguments'],self.report['matchedAcquireListGenericArguments']))
  self.assertEqual((1535,[(14,330),(18,57756)]),(mapping['genericDefinition'],[(a['kind'],a['definition']) for a in mapping['args']]))
  self.assertEqual((3696,[(17,10407)]),(mute['genericDefinition'],[(a['kind'],a['definition']) for a in mute['args']]))
  self.assertEqual((41441,36670,-1),tuple(slot['methodSpec'][k] for k in ['methodDefinition','argumentIndexAt4','argumentIndexAt8']))
  self.assertEqual((5621,'AcquireList','-0x180000000'),tuple(p['acquireListDefinition'][k] for k in ['typeIndex','name','rva']))
  self.assertEqual((18,57756),tuple(slot['arguments'][0]['arguments'][0][k] for k in ['kind','payload']))

 def test_zero_comes_from_actual_paired_constructor_initialization(self):
  p=chance.load_profile();zero=p['zeroInitializer'];r=rows(68417)
  self.assertEqual((16,0),(self.report['zeroInitializerBytes'],zero['missingRaw']))
  self.assertEqual(('0x556db70','0x9343d28','0x9343d30',8),tuple(zero[k] for k in ['rva','pairDestinationRva','missingWordDestinationRva','missingWordOffset']))
  self.assertEqual(('movaps','xmm0, xmmword ptr [rip - 0x18765f3a]'),r['0x1dcd3aa3'])
  self.assertEqual(('movups','xmmword ptr [rip - 0x1498fd89], xmm0'),r['0x1dcd3aaa'])
  self.assertEqual((6,6),(self.report['sourceModifierCommands'],self.report['sourceChanceOmissions']))

 def test_proof_does_not_claim_random_roll_resistance_instances_or_client_acceptance(self):
  r=self.report
  for k in ['finiteChanceCalculationImplemented','sourceChanceEvaluatedFirst','negativeSentinelPreserved',
   'externalPercentagesSummedBeforeOneMultiplication','ordinalNameAndBehaviorMatchingImplemented',
   'normalReturnOnlyPoolReleaseImplemented','mutableContextAndListReadsPreserved','standardArithmeticOnly']:self.assertTrue(r[k],k)
  for k in ['actualDynamicCellFactoryImplemented','nativePoolImplemented','componentDictionariesInstalled',
   'actualChanceRollImplemented','actualResistanceImplemented','modifierInstanceApplied',
   'fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified',
   'jsonInstalled','clientExecutableStarted','clientModified']:self.assertFalse(r[k],k)

 def test_output_rejects_client_and_nonbuild_paths_before_reading_proof(self):
  for output in [CLIENT/'chance.json',ROOT/'chance.json']:
   with self.assertRaises(ValueError):chance.analyze(SimpleNamespace(client_dir=CLIENT,output=output))

if __name__=='__main__':unittest.main()
