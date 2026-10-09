"""核对特殊战技点总量、首项撤回、点数变化和整数通知来源。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('special_bp',ROOT/'scripts/read-rogue-special-bp-withdrawal.py');special_bp=importlib.util.module_from_spec(spec);spec.loader.exec_module(special_bp)

def rows():return {i['rva']:(i['mnemonic'],i['operand']) for m in special_bp.load_profile()['nativeMethods'] for i in m['instructions']}
class SpecialBpTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.report=special_bp.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-special-bp-withdrawal-python-evidence.json'))
 def test_complete_sources_fields_bound_callbacks_generics_and_primitive_match(self):
  self.assertEqual((4,1125,4,3,3,1),tuple(self.report[k] for k in ['matchedCompleteNativeMethods','matchedCompleteNativeBytes','matchedFieldTypes','matchedBoundMethodIdentities','matchedGenericFields','matchedPrimitiveTypeUsages']))
 def test_sum_uses_int_add_and_signed_optional_maximum_clamp(self):
  r=rows();self.assertEqual(('add','edi, dword ptr [rcx]'),r['0xb6646d5']);self.assertEqual(('mov','rax, qword ptr [rbx + 0x3c]'),r['0xb6646f4']);self.assertEqual(('mov','eax, dword ptr [rbx + 0x64]'),r['0xb664703']);self.assertEqual(('cmp','eax, edi'),r['0xb664706']);self.assertEqual(('cmovl','edi, eax'),r['0xb664708'])
 def test_first_identity_match_removes_and_shifts_before_version_and_new_sum(self):
  r=rows();self.assertEqual(('cmp','qword ptr [rdx], rbx'),r['0xb664ab5']);self.assertEqual(('mov','dword ptr [rdi + 0x18], eax'),r['0xb664ad3']);self.assertEqual(('call','0x19c075050'),r['0xb664ae9']);self.assertEqual(('movups','xmmword ptr [rcx + rax*8 + 0x20], xmm0'),r['0xb664b0c']);self.assertEqual(('inc','dword ptr [rdi + 0x1c]'),r['0xb664b1a']);self.assertEqual(('call','0x18b664670'),r['0xb664b20']);self.assertEqual(('jmp','0x18b664770'),r['0xb664b48'])
 def test_special_counter_masks_low_bit_and_uses_current_node_owner_head(self):
  r=rows();self.assertEqual(('movzx','eax, byte ptr [rcx + 0x2c]'),r['0xb666662']);self.assertEqual(('and','eax, 1'),r['0xb666666']);self.assertEqual(('mov','rdx, qword ptr [rcx + 0x48]'),r['0xb66666b']);self.assertEqual(('mov','rsi, qword ptr [rcx + 0x50]'),r['0xb666674']);self.assertEqual(('cmp','rdx, qword ptr [rsi + 0x18]'),r['0xb666680']);point=special_bp.load_profile()['matchedTypes'][2];field=next(f for f in point['fields'] if f.get('instanceOffset')==44);self.assertEqual(2,field['nativeType']['kind'])
 def test_change_formula_removes_normal_points_with_false_and_then_refreshes(self):
  r=rows();self.assertEqual(('cmp','esi, r14d'),r['0xb664790']);self.assertEqual(('jle','0x18b66481e'),r['0xb664793']);self.assertEqual(('sub','ebp, eax'),r['0xb6647be']);self.assertEqual(('cmovle','ecx, ebx'),r['0xb6647e7']);self.assertEqual(('cmovl','ebx, ebp'),r['0xb6647fa']);self.assertEqual(('xor','edx, edx'),r['0xb664813']);self.assertEqual(('call','0x18b665c80'),r['0xb664815']);self.assertEqual(('call','0x18b666710'),r['0xb664821'])
 def test_notification_boxes_int32_and_sends_raw_event_869_immediate(self):
  r=rows();self.assertEqual(('mov','dword ptr [rsp + 0x2c], esi'),r['0xb664826']);self.assertEqual(('call','0x183d2bcc0'),r['0xb664836']);self.assertEqual(('mov','ecx, 0x365'),r['0xb66483b']);self.assertEqual(('mov','r8b, 1'),r['0xb664843']);self.assertEqual(('jmp','0x18d9be560'),r['0xb664850']);u=special_bp.load_profile()['primitiveTypeUsages'][0];self.assertEqual(('0x93592a8',1,71,8),(u['slot'],u['usageKind'],u['payload'],u['record']['kind']));self.assertFalse(self.report['primitiveBoxingHasNativeObjectIdentity'])
 def test_three_field_generics_match_point_ring_dirty_list_and_nullable_int(self):
  p=special_bp.load_profile();g=p['genericFields'];self.assertEqual([16,40,60],[f['field']['instanceOffset'] for f in g]);self.assertEqual([3013,1570,413],[f['record']['genericDefinition'] for f in g]);self.assertEqual([56898,56895,8],[f['record']['args'][0]['payload'] if f['record']['args'][0]['kind']!=8 else 8 for f in g])
 def test_actual_unstack_joint_state_does_not_claim_remaining_components_or_full_world(self):
  for k,v in special_bp.load_profile().items():
   if isinstance(v,bool):self.assertIs(v,self.report[k],k)
  self.assertTrue(self.report['actualUnstackEntryDirtyValueAndRegistrationTailJointlyTested']);self.assertFalse(self.report['normalPointRemovalAndRefreshBodiesInstalled']);self.assertFalse(self.report['globalEventDispatcherInstalled']);self.assertFalse(self.report['fullOriginalUnStackRuntimeImplemented']);self.assertFalse(self.report['actualWorldBindingInstalled']);self.assertFalse(self.report['clientGameplayVerified'])
if __name__=='__main__':unittest.main()
