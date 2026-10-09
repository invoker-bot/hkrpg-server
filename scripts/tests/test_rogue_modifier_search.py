"""核对名称/状态/来源筛选、来源实体组件和当前实例的明确边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_search',ROOT/'scripts/read-rogue-modifier-search.py')
search=importlib.util.module_from_spec(spec);spec.loader.exec_module(search)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in search.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierSearchTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=search.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-search-python-evidence.json'))
 def test_complete_methods_fields_and_generic_source_component_match(self):
  self.assertEqual((3,1394,5,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedGenericComponentSlots']))
 def test_unsigned_state_gate_and_origin_any_before_caster(self):
  r=rows(524471)
  self.assertEqual(('cmp','dword ptr [rsi + 0x90], 1'),r['0xe4a7d94'])
  self.assertEqual(('ja','0x18e4a7e51'),r['0xe4a7d9b'])
  self.assertEqual(('cmp','r12d, 0x64'),r['0xe4a7da1'])
  self.assertEqual(('lea','eax, [r14 + 1]'),r['0xe4a7db6'])
  self.assertEqual(('jb','0x18e4a7e53'),r['0xe4a7dbd'])
 def test_source_context_is_reread_and_destroyed_uses_own_id(self):
  r=rows(524471)
  self.assertEqual(('mov','rcx, qword ptr [rsi + 0x10]'),r['0xe4a7dc3'])
  self.assertEqual(('mov','rcx, qword ptr [rsi + 0x10]'),r['0xe4a7de9'])
  self.assertEqual(('cmp','dword ptr [rdi + 0xe0], 8'),r['0xe4a7e24'])
  self.assertEqual(('mov','eax, dword ptr [rdi + 0xc0]'),r['0xe4a7e6d'])
 def test_requested_ability_is_read_then_combined_without_caster_short_circuit(self):
  r=rows(524471)
  self.assertEqual(('mov','rsi, qword ptr [rsi + 0x10]'),r['0xe4a7e8d'])
  self.assertEqual(('cmp','rax, r15'),r['0xe4a7eb0'])
  self.assertEqual(('and','bl, al'),r['0xe4a7eb6'])
 def test_find_reloads_list_and_checks_current_before_second_read(self):
  r=rows(524462)
  self.assertEqual(('mov','rax, qword ptr [rsi + 0x28]'),r['0xe4a7c0d'])
  self.assertEqual(('call','0x18e4a7fb0'),r['0xe4a7c6f'])
  self.assertEqual(('test','rax, rax'),r['0xe4a7c74'])
  self.assertEqual(('jmp','0x18e4a7fb0'),r['0xe4a7c8c'])
 def test_source_entity_id_is_uint32_at_decimal_36_not_list_at_decimal_24(self):
  p=search.load_profile();slot=p['genericMethodSlots'][0]
  self.assertEqual((502972,56967),(slot['methodSpec']['methodDefinition'],slot['instRecords'][0]['argumentTypeIndex']))
  t=next(t for t in p['matchedTypes'] if t['typeIndex']==56967);f=t['selectedInstanceFields'][0]
  self.assertEqual(('<SourceEntityID>k__BackingField',36,9),(f['name'],f['instanceOffset'],f['nativeType']['kind']))
  self.assertEqual(('mov','eax, dword ptr [rax + 0x24]'),rows(524471)['0xe4a7e41'])
 def test_finite_filters_list_reads_and_explicit_fallback_are_implemented(self):
  for key in ['finiteModifierMatchOrderImplemented','casterWildcardBeforeAbilityPreserved','sourceContextRereadsPreserved','currentListRereadsPreserved','explicitCurrentModifierDoubleReadImplemented','sourceEntityComponentArgumentResolved']:
   self.assertIs(True,self.report[key],key)
 def test_live_component_lookup_global_collection_and_reserved_value_still_unclaimed(self):
  for key in ['nativeSourceComponentLookupInstalled','reservedCurrentModifierNameResolved','nativeExecutionStackPeekInstalled','fullNativeCurrentModifierLookupImplemented','globalEntityCollectionInstalled','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:
   self.assertIs(False,self.report[key],key)

if __name__=='__main__':unittest.main()
