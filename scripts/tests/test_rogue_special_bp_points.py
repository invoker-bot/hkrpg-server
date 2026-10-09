"""核对点数尾部撤回、特殊转普通与活动消费回调的完整来源。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('special_bp_points',ROOT/'scripts/read-rogue-special-bp-points.py');special_bp_points=importlib.util.module_from_spec(spec);spec.loader.exec_module(special_bp_points)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in special_bp_points.load_profile()['nativeMethods']+special_bp_points.load_profile()['runtimeMethods'] for i in m['instructions']}
class SpecialBpPointsTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=special_bp_points.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-special-bp-points-python-evidence.json'))
 def test_complete_sources_helper_auxiliary_fields_cost_identity_and_literal_match(self):
  self.assertEqual((2,710,1,219,1,312,4,1,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedCompleteRuntimeHelpers','matchedCompleteRuntimeHelperBytes','matchedCompleteAuxiliaryMethods','matchedCompleteAuxiliaryBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedCounterLiterals']))
 def test_withdrawal_starts_at_tail_then_scans_backward_by_flag_low_bit(self):
  r=rows();self.assertEqual(('mov','rax, qword ptr [rcx + 0x18]'),r['0xb665ca1']);self.assertEqual(('mov','rdx, qword ptr [rax + 0x40]'),r['0xb665caa']);self.assertEqual(('test','bl, bl'),r['0xb665cb3']);self.assertEqual(('test','byte ptr [rdx + 0x2c], 1'),r['0xb665cd0']);self.assertEqual(('mov','rax, qword ptr [rdx + 0x40]'),r['0xb665cd6']);self.assertEqual(('jmp','0x1976d5720'),r['0xb665cc0'])
 def test_node_unlink_writes_next_previous_before_validating_previous_then_clears_links(self):
  r=rows();self.assertEqual(('mov','qword ptr [rax + 0x40], rsi'),r['0x176d5746']);self.assertEqual(('test','rsi, rsi'),r['0x176d574e']);self.assertEqual(('mov','qword ptr [rsi + 0x48], rax'),r['0x176d5753']);self.assertEqual(('movups','xmmword ptr [rdx + 0x40], xmm0'),r['0x176d5772']);self.assertEqual(('mov','qword ptr [rdx + 0x50], 0'),r['0x176d5776']);self.assertEqual('ffffffff010000000000000000000000',special_bp_points.load_profile()['counterLiteral']['bytesHex'])
 def test_refresh_uses_special_count_twice_and_current_reserved_before_forward_scan(self):
  r=rows();self.assertEqual(('call','0x18b666630'),r['0xb666739']);self.assertEqual(('call','0x18b666630'),r['0xb666766']);self.assertEqual(('call','0x18b664670'),r['0xb66678f']);self.assertEqual(('sub','edi, eax'),r['0xb666794']);self.assertEqual(('sub','edi, ebx'),r['0xb666796']);self.assertEqual(('mov','rbx, qword ptr [rax + 0x18]'),r['0xb6667ab'])
 def test_conversion_zeroes_flag_and_modifier_before_active_check_and_cost_callback(self):
  r=rows();self.assertEqual(('mov','byte ptr [rbx + 0x2c], 0'),r['0xb666805']);self.assertEqual(('mov','qword ptr [rbx + 0x38], 0'),r['0xb666819']);self.assertEqual(('cmp','dword ptr [rcx + 0x90], 1'),r['0xb666826']);self.assertEqual(('mov','edx, dword ptr [rbx + 0x30]'),r['0xb66682f']);self.assertEqual(('call','0x18e80e4f0'),r['0xb666832']);self.assertEqual(('inc','esi'),r['0xb666837'])
 def test_alive_enum_and_inherited_modifier_state_field_are_independently_matched(self):
  p=special_bp_points.load_profile();f=p['matchedTypes'][2]['selectedInstanceFields'][0];self.assertEqual(('<State>k__BackingField',144,'BaseModifierInstance',54437),(f['name'],f['instanceOffset'],f['declaringType'],f['nativeType']['typeIndex']));e=p['matchedTypes'][3];alive=next(f for f in e['fields'] if f['name']=='Alive');self.assertEqual((54437,'ModifierState',1),(e['typeIndex'],e['name'],alive['constant']['value']))
 def test_forward_next_owner_head_is_checked_after_conversion_before_loop_limit(self):
  r=rows();self.assertEqual(('mov','rax, qword ptr [rbx + 0x48]'),r['0xb666839']);self.assertEqual(('mov','rcx, qword ptr [rbx + 0x50]'),r['0xb666842']);self.assertEqual(('cmp','rax, qword ptr [rcx + 0x18]'),r['0xb66684b']);self.assertEqual(('cmp','esi, edi'),r['0xb666854']);p=special_bp_points.load_profile();self.assertEqual('OnCostAdditionalTeamBp',p['boundMethodIdentities'][0]['name']);self.assertFalse(self.report['fullAdditionalTeamBpCostBodyImplemented'])
 def test_joint_real_point_bodies_preserve_limits_of_world_dispatch_and_generic_runtime(self):
  for k,v in special_bp_points.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualSpecialWithdrawalChangeAndPointBodiesJointlyTested']);self.assertFalse(self.report['genericNodeHelperRegisteredMethodIdentityMatched']);self.assertFalse(self.report['closedGenericContainerRuntimeLayoutsInstalled']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
