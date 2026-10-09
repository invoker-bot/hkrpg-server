"""事件创建任务的完整本机生命周期、参数栈和原始空回调边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('event_lifecycle',ROOT/'scripts/read-rogue-event-lifecycle.py')
lifecycle=importlib.util.module_from_spec(spec)
spec.loader.exec_module(lifecycle)


class EventLifecycleTest(unittest.TestCase):
    def test_twelve_complete_methods_match_original_client(self):
        self.assertEqual((12,4499),lifecycle.verify_native(CLIENT))

    def test_configuration_runtime_register_and_before_delegate_field_identities(self):
        p=lifecycle.load_profile()
        types={t['name']:t for t in p['types']}
        fields={f['name']:f['instanceOffset'] for f in types['CreateBattleEvent']['fields']}
        self.assertEqual([24,64,72,80,81],[fields[n] for n in ['EventID','OnBeforeCreateCallback',
            'OnBattleEventCreate','IsDynamicPreload','AllowDuplicate']])
        self.assertEqual([40,48,56],[f['instanceOffset'] for f in types['NAPIKMAGDDI']['fields']])
        slot=p['beforeCallbackMethodSlot']
        self.assertEqual(('0x949f6e8',3,536336),(slot['slot'],slot['usageKind'],slot['method']['index']))
        self.assertEqual((72,56185),(p['worldRegister']['field']['instanceOffset'],
            p['worldRegister']['field']['nativeType']['typeIndex']))

    def test_done_state_is_written_before_identity_evaluation_and_preparation(self):
        methods={m['index']:m for m in lifecycle.load_profile()['nativeMethods']}
        ins=methods[536333]['instructions']
        done=next(i for i,r in enumerate(ins) if r['mnemonic']=='mov' and r['operand']=='dword ptr [rsi + 0x10], 0x9999')
        evaluate=next(i for i,r in enumerate(ins) if r['mnemonic']=='call' and r['operand']=='0x18e777c90')
        prepare=next(i for i,r in enumerate(ins) if r['mnemonic']=='call' and r['operand']=='0x18e6b4e90')
        self.assertLess(done,evaluate)
        self.assertLess(evaluate,prepare)
        self.assertTrue(any(r['mnemonic']=='sar' and r['operand']=='rdi, cl' for r in ins))

    def test_both_callback_helpers_set_entity_execute_then_pop_without_unwind_handler(self):
        p=lifecycle.load_profile()
        methods={m['index']:m for m in p['nativeMethods']}
        unwind={m['index']:m for m in p['unwindRecords']}
        for identity in [536335,536336]:
            ins=methods[identity]['instructions']
            entity=next(i for i,r in enumerate(ins) if r['mnemonic']=='mov' and r['operand']=='qword ptr [rbx + 0x40], rdi')
            execute=next(i for i,r in enumerate(ins) if r['mnemonic']=='call' and r['operand']=='0x18b1ea7f0')
            pop=next(i for i,r in enumerate(ins) if r['mnemonic']=='call' and r['operand']=='0x1986926d0')
            self.assertLess(entity,execute)
            self.assertLess(execute,pop)
            self.assertEqual(0,unwind[identity]['unwindFlags'])
            self.assertFalse(unwind[identity]['hasExceptionHandler'])
            self.assertFalse(unwind[identity]['hasTerminationHandler'])

    def test_disposal_clears_after_before_and_delegate_in_that_order(self):
        method=next(m for m in lifecycle.load_profile()['nativeMethods'] if m['index']==536337)
        cleared=[r['operand'] for r in method['instructions'] if r['mnemonic']=='mov'
            and r['operand'] in ['qword ptr [rsi + 0x30], 0','qword ptr [rsi + 0x38], 0','qword ptr [rsi + 0x28], 0']]
        self.assertEqual(['qword ptr [rsi + 0x30], 0','qword ptr [rsi + 0x38], 0','qword ptr [rsi + 0x28], 0'],cleared)

    def test_complete_parameter_register_push_pop_count_and_safe_current_are_included(self):
        methods={m['index']:m for m in lifecycle.load_profile()['nativeMethods']}
        self.assertEqual(386,sum(len(bytes.fromhex(methods[i]['bytesHex'])) for i in [520527,520530,520532,520533]))
        self.assertTrue(any(r['operand']=='0x198692760' for r in methods[520527]['instructions']))
        self.assertTrue(any(r['operand']=='0x1986926d0' for r in methods[520530]['instructions']))
        self.assertTrue(any(r['mnemonic']=='mov' and r['operand']=='eax, dword ptr [rax + 0x20]' for r in methods[520532]['instructions']))
        self.assertTrue(any(r['mnemonic']=='xor' and r['operand']=='eax, eax' for r in methods[520533]['instructions']))

    def test_all_nine_original_creation_configs_have_absent_callbacks(self):
        rows=lifecycle.source_callbacks(CLIENT)
        self.assertEqual(list(range(41001,41010)),[r['eventId'] for r in rows])
        self.assertTrue(all(r['before'] is None and r['after'] is None for r in rows))
        self.assertEqual(lifecycle.load_profile()['classicCallbacks'],rows)

    def test_original_analysis_keeps_live_entity_factory_task_execution_and_ui_unverified(self):
        result=lifecycle.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-event-lifecycle-python-evidence.json'))
        self.assertTrue(result['passed'])
        self.assertEqual((9,0,0),(result['sourceEventCount'],result['sourceBeforeCallbacks'],result['sourceAfterCallbacks']))
        for key in ['explicitCreateTaskLifecycleImplemented','explicitCallbackFrameStackImplemented','ordinaryCallbackExceptionCleanupMatched']:
            self.assertTrue(result[key],key)
        for key in ['callbackHelpersHaveExceptionHandler','callbackHelpersHaveTerminationHandler','liveEntityFactoryImplemented',
                'originalCallbackTasksExecuted','battleEventCreated','liveBattleContextAttached','abilityExecutionVerified',
                'clientGameplayVerified','completeResonanceRuntimeImplemented','jsonInstalled','clientExecutableStarted','clientModified']:
            self.assertFalse(result[key],key)

    def test_output_inside_client_is_rejected_before_analysis(self):
        with self.assertRaises(ValueError):
            lifecycle.analyze(SimpleNamespace(client_dir=CLIENT,output=CLIENT/'lifecycle.json'))

    def test_output_outside_build_is_rejected_before_analysis(self):
        with self.assertRaises(ValueError):
            lifecycle.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'lifecycle.json'))


if __name__=='__main__':
    unittest.main()
