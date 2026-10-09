"""核对叠加模式普通分支、设置器与尚未安装的原生边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_stack_mode',ROOT/'scripts/read-rogue-modifier-stack-mode.py')
stack=importlib.util.module_from_spec(spec);spec.loader.exec_module(stack)

def rows(index):
 return {i['rva']:(i['mnemonic'],i['operand']) for m in stack.load_profile()['nativeMethods'] if m['index']==index for i in m['instructions']}

class ModifierStackModeTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=stack.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-stack-mode-python-evidence.json'))
 def test_all_complete_methods_fields_modes_and_jump_targets_match_original(self):
  self.assertEqual((6,3148,4,14,11),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedStackModes','matchedJumpTargets']))
 def test_enum_is_mode_not_origin_category_and_includes_fourteen_values(self):
  enum=next(t for t in stack.load_profile()['matchedTypes'] if t['typeIndex']==15648)
  self.assertEqual(['Unknow','Unique','Refresh','Prolong','Multiple','Replace','Merge','ReplaceByCaster','ReplaceByCasterOrUnStack','EntityUnique','ReplaceButKeepLifeTime','RetainGlobalLatest','ReplaceByCasterAbility','RetainGlobalLatestUnique'],[f['name'] for f in enum['fields'] if 'constant' in f])
 def test_refresh_and_prolong_share_lifetime_reset_without_count_or_replace(self):
  r=rows(510420)
  self.assertEqual(('mov','dword ptr [rsi + 0x2a8], ebp'),r['0xe7b388e'])
  self.assertEqual(('mov','dword ptr [rsi + 0x2dc], eax'),r['0xe7b38e5'])
  self.assertEqual(('jmp','0x18e7b3980'),r['0xe7b38f8'])
  self.assertFalse(any(m=='call' and o in ['0x18e5ae580','0x18e807ce0'] for a,(m,o) in r.items() if 0xe7b3879<=int(a,16)<0xe7b38fd))
 def test_prolong_checks_initial_sentinel_then_adds_current_with_no_saturation(self):
  r=rows(510420)
  self.assertEqual(('cmp','ebp, -1'),r['0xe7b389b'])
  self.assertEqual(('cmp','dword ptr [rsi + 0x2dc], -1'),r['0xe7b38a0'])
  self.assertEqual(('add','ebp, dword ptr [rsi + 0x2a8]'),r['0xe7b38a9'])
 def test_merge_uses_max_then_replacement_without_count_setter(self):
  r=rows(510420)
  self.assertEqual(('cmp','dword ptr [rsi + 0x2dc], -1'),r['0xe7b3919'])
  self.assertEqual(('cmovg','ebp, ecx'),r['0xe7b392a'])
  self.assertEqual(('call','0x18e807ce0'),r['0xe7b396f'])
  self.assertFalse(any(m=='call' and o=='0x18e5ae580' for a,(m,o) in r.items() if 0xe7b390f<=int(a,16)<0xe7b3980))
 def test_all_replace_modes_share_life_count_replacement_order(self):
  r=rows(510420);t=stack.load_profile()['modeJumpTable']
  self.assertEqual(['0xe7b3815']*4,[t['targets'][i-2] for i in [5,7,8,12]])
  self.assertEqual(('call','0x18e807e60'),r['0xe7b383b'])
  self.assertEqual(('mov','dword ptr [rsi + 0x2dc], eax'),r['0xe7b3846'])
  self.assertEqual(('call','0x18e5ae580'),r['0xe7b3851'])
  self.assertEqual(('call','0x18e807ce0'),r['0xe7b385f'])
 def test_keep_life_mode_has_count_then_replacement_and_no_life_write(self):
  r=rows(510420)
  self.assertEqual(('call','0x18e5ae580'),r['0xe7b398e'])
  self.assertEqual(('call','0x18e807ce0'),r['0xe7b399c'])
  self.assertFalse(any(m=='mov' and o.startswith('dword ptr [rsi + 0x2a8]') for a,(m,o) in r.items() if 0xe7b3986<=int(a,16)<0xe7b39ad))
 def test_three_setters_compare_before_write_and_notify_types_zero_one_three(self):
  for index,address,operand in [(510282,'0xe8078a4','dword ptr [rsi + 0x274], edi'),(510286,'0xe807914','dword ptr [rsi + 0x2a8], edi'),(508676,'0xe5ae594','dword ptr [rsi + 0x84], edi')]:
   self.assertEqual(('cmp',operand),rows(index)[address])
  self.assertEqual(('xor','edx, edx'),rows(510282)['0xe8078bc'])
  self.assertEqual(('mov','edx, 3'),rows(510286)['0xe80792c'])
  self.assertEqual(('mov','edx, 1'),rows(508676)['0xe5ae5bf'])
 def test_full_redd_prelude_postprocess_replacement_and_world_remain_unclaimed(self):
  for key in ['fullOrdinaryReddPreludeImplemented','fullOrdinaryReddPostprocessImplemented','actualOnReplaceBodyImplemented','nativeValueBindingArrayImplemented','nativeCountVirtualBindingInstalled','stackAdmissionByCasterImplemented','modifierInstanceApplied','runtimeHotfixOverridesImplemented','fullOriginalAddModifierRuntimeImplemented','completeResonanceRuntimeImplemented','clientGameplayVerified','jsonInstalled','clientExecutableStarted','clientModified']:
   self.assertIs(False,self.report[key],key)
 def test_finite_branches_share_lifetime_but_do_not_claim_complete_gameplay(self):
  for key in ['finiteStackingModeBranchImplemented','separateLifeAndCountPreserved','initialPermanentSentinelPreserved','signed32ProlongWrapImplemented','changedValueNotificationsImplemented','sharedLifetimeStateImplemented']:
   self.assertIs(True,self.report[key],key)
  self.assertNotIn('completeGameplayVerified',self.report)

if __name__=='__main__':unittest.main()
