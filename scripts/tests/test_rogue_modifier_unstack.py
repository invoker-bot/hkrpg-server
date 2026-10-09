"""核对普通UnStack调用顺序、共享状态和嵌入战技点脏值清空边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('modifier_unstack',ROOT/'scripts/read-rogue-modifier-unstack.py');modifier_unstack=importlib.util.module_from_spec(spec);spec.loader.exec_module(modifier_unstack)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in modifier_unstack.load_profile()['nativeMethods'] for i in m['instructions']}
class ModifierUnstackTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=modifier_unstack.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-modifier-unstack-python-evidence.json'))
 def test_full_entry_auxiliary_sources_fields_and_callee_identities_match(self):
  self.assertEqual((1,252,2,3155,3,12),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities']))
 def test_all_ordinary_calls_and_final_tail_match_native_order(self):
  p=modifier_unstack.load_profile();calls=[i['operand'] for i in p['nativeMethods'][0]['instructions'] if i['mnemonic'] in ['call','jmp'] and int(i['rva'],16)<0xe814755]
  self.assertEqual([hex(0x180000000+r) for r in [0xe8153a0,0xe810190,0xe815eb0,0xe815f90,0xb664a50,0xe816100,0xe816340,0xe816600,0xe80f9f0,0xe8110d0,0xe810f80,0xe810a80]],calls)
 def test_character_detach_is_immediate_and_unselectable_is_final_jump(self):
  r=rows();self.assertEqual(('mov','dl, 1'),r['0xe814738']);self.assertEqual(('call','0x18e8110d0'),r['0xe81473a']);self.assertEqual(('jmp','0x18e810a80'),r['0xe814750'])
 def test_null_team_skips_clear_and_null_module_reaches_clear(self):
  r=rows();self.assertEqual(('mov','rax, qword ptr [rsi + 0x118]'),r['0xe8146e3']);self.assertEqual(('je','0x18e814715'),r['0xe8146ed']);self.assertEqual(('mov','rcx, qword ptr [rax + 0x20]'),r['0xe8146f6']);self.assertEqual(('je','0x18e814707'),r['0xe8146fd'])
 def test_module_removal_receives_current_modifier_then_zeroes_full_24_bytes(self):
  r=rows();self.assertEqual(('mov','rdx, rsi'),r['0xe8146ff']);self.assertEqual(('call','0x18b664a50'),r['0xe814702']);self.assertEqual(('xorps','xmm0, xmm0'),r['0xe814707']);self.assertEqual(('movups','xmmword ptr [rdi], xmm0'),r['0xe81470a']);self.assertEqual(('mov','qword ptr [rdi + 0x10], 0'),r['0xe81470d'])
 def test_embedded_dirty_has_team_modifier_and_int_and_module_owner_is_matched(self):
  p=modifier_unstack.load_profile();f=p['matchedTypes'][0]['selectedFields'][0];self.assertEqual(('_StackBpDirty',280,17,56895),(f['name'],f['instanceOffset'],f['nativeType']['kind'],f['nativeType']['typeIndex']));value=p['matchedTypes'][1];self.assertEqual([(16,18),(24,18),(32,8)],[(f['instanceOffset'],f['nativeType']['kind']) for f in value['fields']]);team=p['matchedTypes'][2]['selectedFields'][0];self.assertEqual((32,56900),(team['instanceOffset'],team['nativeType']['typeIndex']))
 def test_auxiliary_complete_sources_are_distinct_from_implemented_binding_bodies(self):
  p=modifier_unstack.load_profile();self.assertEqual([(510268,2832),(524898,323)],[(m['index'],len(bytes.fromhex(m['bytesHex']))) for m in p['auxiliaryMethods']]);self.assertFalse(self.report['completeStackedPropertiesBodyImplemented']);self.assertFalse(self.report['completeSpecialBpModuleRemovalBodyImplemented'])
 def test_joint_cleanup_and_existing_withdrawals_do_not_claim_complete_world_or_client(self):
  for k,v in modifier_unstack.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualCleanupEntryAndBindingPayloadClearJointlyTested']);self.assertTrue(self.report['existingStackedRegistrationAndOwnedWithdrawalBodiesCalled']);self.assertFalse(self.report['fullOriginalUnStackRuntimeImplemented']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
