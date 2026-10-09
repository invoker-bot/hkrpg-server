"""原始回合任务状态、八个完整序列方法和显式运行边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('task_sequence',ROOT/'scripts/read-rogue-task-sequence.py')
sequence=importlib.util.module_from_spec(spec)
spec.loader.exec_module(sequence)


class TaskSequenceTest(unittest.TestCase):
    def test_eight_complete_native_methods_match_client(self):
        self.assertEqual((8,5278),sequence.verify_native(CLIENT))

    def test_correct_task_state_definition_and_constants_are_frozen(self):
        state=sequence.load_profile()['stateType']
        self.assertEqual(54773,state['typeIndex'])
        self.assertEqual([0x7777,0x8888,0x9999,0xaaaa],[f['constant']['value'] for f in state['fields'] if 'constant' in f])

    def test_begin_passes_zero_delta_and_maps_nonterminal_result_to_executing(self):
        method=next(m for m in sequence.load_profile()['nativeMethods'] if m['index']==509751)
        self.assertTrue(any(r['mnemonic']=='xorps' and r['operand']=='xmm1, xmm1' for r in method['instructions']))
        self.assertTrue(any(r['mnemonic']=='call' and r['operand']=='0x18b390fc0' for r in method['instructions']))
        self.assertTrue(any(r['mnemonic']=='mov' and r['operand']=='dword ptr [rsi + 0x10], 0x8888' for r in method['instructions']))

    def test_step_checks_child_completion_after_tick_before_parent_failure_cleanup(self):
        method=next(m for m in sequence.load_profile()['nativeMethods'] if m['index']==509761)
        instructions={r['rva']:r for r in method['instructions']}
        self.assertEqual(('cmp','eax, 0x9999'),tuple(instructions['0xb3912f8'][k] for k in ['mnemonic','operand']))
        self.assertEqual(('jne','0x18b391698'),tuple(instructions['0xb3912fd'][k] for k in ['mnemonic','operand']))
        self.assertEqual(('call','0x18b392830'),tuple(instructions['0xb3916a9'][k] for k in ['mnemonic','operand']))

    def test_failure_notification_starts_after_the_current_child_and_requires_ready_state(self):
        method=next(m for m in sequence.load_profile()['nativeMethods'] if m['index']==509764)
        instructions=method['instructions']
        self.assertTrue(any(r['rva']=='0xb39289a' and r['mnemonic']=='inc' and r['operand']=='rdi' for r in instructions))
        self.assertTrue(any(r['mnemonic']=='cmp' and r['operand']=='eax, 0x7777' for r in instructions))
        slot=next(s for s in sequence.load_profile()['interfaceTypeSlots'] if s['slot']=='0x93c4e60')
        self.assertEqual(54780,slot['typeIndex'])

    def test_performance_immediate_flag_and_interface_identity_are_explicit(self):
        p=sequence.load_profile()
        self.assertEqual(('FinishImmediatelyInPerformanceFlag',154),
            tuple(p['performanceField']['field'][k] for k in ['name','instanceOffset']))
        self.assertEqual(54779,next(s['typeIndex'] for s in p['interfaceTypeSlots'] if s['slot']=='0x93b42b8'))

    def test_run_once_waiting_diagnostic_is_log_error_and_not_assumed_throw(self):
        helper=sequence.load_profile()['diagnosticHelper']
        self.assertEqual((47390,'LogError','SuperDebug'),tuple(helper[k] for k in ['index','name','typeName']))
        method=next(m for m in sequence.load_profile()['nativeMethods'] if m['index']==513995)
        self.assertTrue(any(r['mnemonic']=='call' and r['operand']=='0x19dd6b050' for r in method['instructions']))

    def test_analysis_keeps_original_factories_plugins_alias_cache_dispose_pointer_and_ui_unverified(self):
        result=sequence.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-task-sequence-python-evidence.json'))
        self.assertTrue(result['passed'])
        for key in ['explicitTaskSequenceImplemented','ordinaryNoPluginStepMatched','performanceImmediateInterfaceMatched',
                'disposeChildReleaseOrderMatched','waitingDiagnosticMethodMatched']:
            self.assertTrue(result[key],key)
        for key in ['nativeDisposedArrayReplacementMatched','originalTaskConfigsDispatched','globalPluginHooksImplemented',
                'globalAliasCacheImplemented','nativeSequenceExecuted','liveBattleContextAttached','abilityExecutionVerified',
                'clientGameplayVerified','completeResonanceRuntimeImplemented','jsonInstalled','clientExecutableStarted','clientModified']:
            self.assertFalse(result[key],key)

    def test_output_inside_client_is_rejected(self):
        with self.assertRaises(ValueError):
            sequence.analyze(SimpleNamespace(client_dir=CLIENT,output=CLIENT/'sequence.json'))

    def test_output_outside_build_is_rejected(self):
        with self.assertRaises(ValueError):
            sequence.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'sequence.json'))


if __name__=='__main__':
    unittest.main()
