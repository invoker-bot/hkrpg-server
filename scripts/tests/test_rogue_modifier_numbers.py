"""效果整数参数的完整原生方法、上下文读取及有限实现边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_numbers',ROOT/'scripts/read-rogue-modifier-numbers.py')
numbers=importlib.util.module_from_spec(spec);spec.loader.exec_module(numbers)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in numbers.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierNumbersTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.report=numbers.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-numbers-python-evidence.json'))

 def test_complete_methods_layout_provider_and_exact_fields_match_fixed_client(self):
  r=self.report
  self.assertEqual((8,8953,1,1513,4),tuple(r[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes',
   'matchedCompleteLayoutProviders','matchedCompleteLayoutProviderBytes','matchedFieldTypes']))

 def test_source_six_commands_omit_count_layer_lifetime_and_layer_add(self):
  p=numbers.load_profile();self.assertEqual(6,len(p['sourceModifierCommands']))
  self.assertEqual(['Count','MaxLayer','LifeTime','LayerAddWhenStack'],p['originalNumericFields'])
  self.assertEqual(24,self.report['originalOmittedNumericFields'])
  for source in p['sourceModifierCommands']:
   for name in p['originalNumericFields']:self.assertNotIn(name,source['command'])

 def test_each_getter_uses_its_actual_source_cell_and_override_struct_offset(self):
  for index,prefix,source,override in [(510003,'0x16548c','0x60','0x10'),(510004,'0x16548d','0x50','0x48'),(510005,'0x16548e','0x68','0x60')]:
   r=rows(index)
   self.assertEqual(('cmp','qword ptr [rdi + 0x80], 0'),r[prefix+'3e'])
   self.assertEqual(('mov',f'r8, qword ptr [rax + {source}]'),r[prefix+'55'])
   self.assertEqual(('movups',f'xmm0, xmmword ptr [rdi + {override}]'),r[prefix+'62'])
   self.assertEqual(('call','0x196549410'),r[prefix+'76'])
   self.assertEqual(('test','rdx, rdx'),r[prefix+'8a'])
   self.assertEqual(('xor','eax, eax'),r[prefix+'c9'])
   self.assertEqual(('call','0x196630d10'),r[prefix+'b3'])
   self.assertEqual(('movabs','rax, 0x100000000'),r[prefix+'ba'])

 def test_inline_triplet_boxes_have_three_dynamic_cells_and_context_is_separate(self):
  p=numbers.load_profile();t=next(t for t in p['matchedTypes'] if t['typeIndex']==57754)
  self.assertEqual([('MMFJHJFCBMP',16),('KCNJNNDHEGN',24),('DDAMKPFFEPB',32)],[(f['name'],f['instanceOffset']) for f in t['fields']])
  self.assertTrue(all(f['nativeType']['typeIndex']==10836 for f in t['fields']))
  t=next(t for t in p['matchedTypes'] if t['typeIndex']==57755)
  self.assertEqual((128,54774),next((f['instanceOffset'],f['nativeType']['typeIndex']) for f in t['fields'] if f['name']=='BKIFMPHBIAK'))

 def test_absolute_override_rereads_context_and_uses_signed_shift_before_packing_presence(self):
  r=rows(510006)
  self.assertEqual(('mov','rdx, qword ptr [rdi]'),r['0x16549432'])
  self.assertEqual(('mov','rcx, qword ptr [rsi + 0x80]'),r['0x16549443'])
  self.assertEqual(('call','0x18e777c90'),r['0x16549453'])
  self.assertEqual(('mov','edx, 0x21'),r['0x1654945a'])
  self.assertEqual(('mov','ecx, 0x1a'),r['0x1654945f'])
  self.assertEqual(('sar','rax, cl'),r['0x16549467'])
  self.assertEqual(('jmp','0x19654955e'),r['0x1654946c'])
  self.assertEqual(('movabs','rax, 0x100000000'),r['0x1654955e'])

 def test_affine_reads_source_then_percent_and_add_before_multiply_add_round(self):
  r=rows(510006)
  self.assertEqual(('call','0x18e777c90'),r['0x165494b0'])
  self.assertEqual(('call','0x18e777c90'),r['0x165494e1'])
  self.assertEqual(('call','0x19dcc86e0'),r['0x165494ec'])
  self.assertEqual(('call','0x18e777c90'),r['0x16549516'])
  self.assertEqual(('call','0x19dccb160'),r['0x1654952d'])
  self.assertEqual(('call','0x19dcc86e0'),r['0x16549538'])
  self.assertEqual(('call','0x196630d10'),r['0x16549557'])
  self.assertEqual(('test','rbx, rbx'),r['0x165495e3'])
  self.assertEqual(('xor','eax, eax'),r['0x1654961a'])

 def test_entry_count_is_source_only_and_precedes_other_three_optional_values(self):
  r=rows(509996)
  self.assertEqual(('mov','rdx, qword ptr [rax + 0x58]'),r['0x16546e70'])
  self.assertEqual(('call','0x196630d10'),r['0x16546eac'])
  self.assertEqual(('mov','qword ptr [rsi + 0x20], rax'),r['0x16546ec0'])
  self.assertEqual(('call','0x196548c20'),r['0x16546f05'])
  self.assertEqual(('call','0x196548d20'),r['0x16546f15'])
  self.assertEqual(('call','0x196548e20'),r['0x16546f28'])
  self.assertEqual(('mov','qword ptr [r12 + 0x6c], rax'),r['0x16546f0a'])
  self.assertEqual(('mov','qword ptr [r12 + 0x8c], rax'),r['0x16546f1a'])
  self.assertEqual(('mov','qword ptr [r12 + 0x5c], rax'),r['0x16546f2d'])

 def test_one_and_zero_names_layout_delta_and_unbacked_pe_initial_storage_are_matched(self):
  p=numbers.load_profile();zero=p['zeroInitializer'];one=p['oneInitializer']
  self.assertEqual((1<<33,'0x9343cc0'),(one['raw'],one['slotRva']))
  self.assertEqual((0,'0x9343d30',0x70),(zero['raw'],zero['slotRva'],zero['staticOffsetDelta']))
  self.assertEqual([('One',0x02000810),('Zero',0x02000880)],[(f['name'],f['word']) for f in zero['fields']])
  r={i['rva']:(i['mnemonic'],i['operand']) for i in p['layoutProvider']['instructions']}
  self.assertEqual(('mov','ebx, dword ptr [rbx + rdx*4]'),r['0x3d22604'])
  self.assertEqual(('test','ebx, 0x1000000'),r['0x3d22607'])

 def test_report_keeps_cell_calculation_separate_from_live_parameters_stacking_and_client_acceptance(self):
  r=self.report;self.assertTrue(r['passed'])
  for k in ['modifierIntegerCellsImplemented','directOverrideUsesSignedShift','ordinaryRoundToEvenImplemented',
   'affineOverrideUsesDistinctContexts','repeatedOverrideContextReadsPreserved','missingIntegerCellsPreserved',
   'sourceConfigNumericAbsenceMatched','standardArithmeticOnly']:self.assertTrue(r[k],k)
  for k in ['actualDynamicCellFactoryImplemented','overrideTableAttachedToLiveAbility','actualParametersInstalled',
   'entryIntegerApplicationImplemented','modifierStackAndLifeImplemented','fullOriginalAddModifierRuntimeImplemented',
   'completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:self.assertFalse(r[k],k)

 def test_output_guards_reject_client_and_nonbuild_targets_before_verification(self):
  for output in [CLIENT/'modifier-numbers.json',ROOT/'modifier-numbers.json']:
   with self.assertRaises(ValueError):numbers.analyze(SimpleNamespace(client_dir=CLIENT,output=output))

if __name__=='__main__':unittest.main()
