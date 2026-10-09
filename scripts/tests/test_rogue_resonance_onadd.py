"""九条原始 OnAdd、TaskEnabled 叶指令与六个完整方法的静态证据。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('resonance_onadd',ROOT/'scripts/read-rogue-resonance-onadd.py')
onadd=importlib.util.module_from_spec(spec);spec.loader.exec_module(onadd)


class ResonanceOnAddTest(unittest.TestCase):
 def test_six_complete_native_methods_and_metadata_match(self):
  self.assertEqual((6,95918),onadd.verify_native(CLIENT))

 def test_task_enabled_default_is_separate_five_byte_leaf_not_a_pe_method(self):
  leaf=onadd.load_profile()['taskEnabledLeaf']
  self.assertEqual((133907,23738,'c6411001c3',5),tuple(leaf[k] for k in ['index','typeIndex','bytesHex','bytes']))
  self.assertIn('no PE exception entry',leaf['bounds'])
  self.assertNotIn(133907,[m['index'] for m in onadd.load_profile()['nativeMethods']])

 def test_native_disabled_command_branch_precedes_factory_call(self):
  m=next(m for m in onadd.load_profile()['nativeMethods'] if m['index']==513993)
  rows={r['rva']:r for r in m['instructions']}
  self.assertEqual(('cmp','byte ptr [rsi + 0x10], 0'),tuple(rows['0xb225d9c'][k] for k in ['mnemonic','operand']))
  self.assertEqual(('je','0x18b225c30'),tuple(rows['0xb225da0'][k] for k in ['mnemonic','operand']))
  self.assertEqual(('call','0x19451d850'),tuple(rows['0xb225dbc'][k] for k in ['mnemonic','operand']))

 def test_modifier_config_factory_and_runtime_slots_are_independently_matched(self):
  p=onadd.load_profile()
  self.assertEqual([('0x9392050',23067),('0x9393c78',54816)],[(s['slot'],s['typeIndex']) for s in p['modifierTypeSlots']])
  slot=p['factoryMethodSlot']
  self.assertEqual((516303,'FJLBFOHOFAA','EGPHLHMHHCN'),tuple(slot['method'][k] for k in ['index','name','typeName']))

 def test_all_twenty_four_source_commands_keep_source_order_and_absent_enabled_fields(self):
  source=onadd.load_profile()['sourceCommands']
  self.assertEqual(list(range(41001,41010)),[s['eventId'] for s in source])
  self.assertEqual(24,sum(len(s['commands']) for s in source))
  for s in source:
   self.assertEqual([2847,1122,50] if s['eventId'] in [41001,41002,41003,41004,41005,41008] else [2847,1122],
    [t['taskId'] for t in s['commands']])
   self.assertTrue(all('TaskEnabled' not in t for t in s['commands']))

 def test_all_source_creation_teams_parameters_and_absent_callbacks_are_preserved(self):
  for s in onadd.load_profile()['sourceCommands']:
   t=s['commands'][1]
   self.assertEqual((3,1,s['eventId']<<33,'0x72'),(t['Team'],t['TotalDamageTeam'],t['EventID']['fixedSignedRaw'],t['$mask']))
   self.assertNotIn('BeforeCreateTaskList',t);self.assertNotIn('AfterCreateTaskList',t)

 def test_six_modifiers_use_literal_name_and_caster_alias_without_other_fields(self):
  modifiers=[(s['eventId'],t) for s in onadd.load_profile()['sourceCommands'] for t in s['commands'] if t['taskId']==50]
  self.assertEqual(6,len(modifiers))
  for event,t in modifiers:
   self.assertEqual(('0xa','Caster',12,'0x4'),(t['$mask'],t['TargetType']['Alias'],t['TargetType']['$variantId'],t['ModifierName']['$mask']))
   self.assertNotIn('SuccessTaskList',t);self.assertNotIn('FailTaskList',t);self.assertNotIn('AliveOnly',t)
  self.assertEqual('MAvatar_RogueBattleevent127_AimTarget',modifiers[-1][1]['ModifierName']['Value'])

 def test_fresh_client_abilities_match_every_frozen_command_field(self):
  self.assertEqual(onadd.load_profile()['sourceCommands'],onadd.source_commands(CLIENT))

 def test_report_keeps_general_factory_modifier_effects_and_online_ui_unverified(self):
  r=onadd.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-resonance-onadd-python-evidence.json'))
  self.assertTrue(r['passed']);self.assertEqual((9,24,6,5),tuple(r[k] for k in ['sourceEvents','sourceCommands','sourceModifierCommands','matchedTaskEnabledLeafBytes']))
  for key in ['finiteClassicOnAddCompilerImplemented','explicitTaskEnabledFilterImplemented','sourceCommandOrderPreserved',
      'missingModifierImplementationRejected','nativeModifierFactoryIdentityMatched','sourceEnabledFieldsAbsent']:
   self.assertTrue(r[key],key)
  for key in ['generalOriginalTaskFactoryImplemented','linkedTaskInterfacesImplemented','completeModifierRuntimeImplemented',
      'allOriginalModifierFieldsInterpreted','liveBattleContextAttached','onlineOnAddExecuted','nativeSequenceExecuted',
      'clientGameplayVerified','completeResonanceRuntimeImplemented','jsonInstalled','clientExecutableStarted','clientModified']:
   self.assertFalse(r[key],key)

 def test_output_guard_rejects_client_and_nonbuild_paths(self):
  for output in [CLIENT/'onadd.json',ROOT/'onadd.json']:
   with self.assertRaises(ValueError):onadd.analyze(SimpleNamespace(client_dir=CLIENT,output=output))


if __name__=='__main__':unittest.main()
