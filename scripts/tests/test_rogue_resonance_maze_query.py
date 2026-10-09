"""查询任务2847的完整本机方法、九条源配置及真实原始定点参数核对。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('maze_query',ROOT/'scripts/read-rogue-resonance-maze-query.py')
query = importlib.util.module_from_spec(spec)
spec.loader.exec_module(query)


class MazeQueryTest(unittest.TestCase):
    def test_complete_factory_registry_executor_reader_and_exact_table_lookup_match_client(self):
        self.assertEqual((7,17371),query.verify_native(CLIENT))

    def test_original_config_and_runtime_fields_are_bound_to_the_factory(self):
        p = query.load_profile()
        self.assertEqual(('0x9378870',23834,'SetDynamicValueByMazeBuffConfig'),
            tuple(p['configTypeSlot'][k] for k in ['slot','typeIndex','typeName']))
        self.assertEqual(('0x93a24d8',55453,'LPMEAGPFNHE'),
            tuple(p['runtimeTypeSlot'][k] for k in ['slot','typeIndex','typeName']))
        fields = p['queryTypes'][0]['fields']
        self.assertEqual([('MazeBuffID',16),('Level',20),('Index',24)],
            [(f['name'],f['instanceOffset']) for f in fields])

    def test_complete_param_reader_initializes_level_one_before_present_field_override(self):
        method = next(m for m in query.load_profile()['nativeMethods'] if m['index']==110276)
        instructions = method['instructions']
        self.assertEqual('mov dword ptr [rax + 0x14], 1',
            next(i['mnemonic']+' '+i['operand'] for i in instructions if i['rva']=='0x1d8350cb'))
        self.assertEqual('mov dword ptr [rsi + 0x14], eax',
            next(i['mnemonic']+' '+i['operand'] for i in instructions if i['rva']=='0x1d835151'))
        self.assertEqual(316,len(bytes.fromhex(method['bytesHex'])))

    def test_query_loop_uses_one_based_index_and_separate_resolve_then_write_helpers(self):
        methods = {m['index']:m for m in query.load_profile()['nativeMethods']}
        resolve = methods[512715]['instructions']
        write = methods[512716]['instructions']
        self.assertTrue(any(i['mnemonic']=='lea' and i['operand']=='edx, [rcx - 1]' for i in resolve))
        self.assertTrue(any(i['mnemonic']=='call' and i['operand']=='0x19d832da0' for i in resolve))
        self.assertTrue(any(i['mnemonic']=='call' and i['operand']=='0x18e4c35d0' for i in write))
        self.assertTrue(any(i['mnemonic']=='jmp' and i['operand']=='0x19cd7ea20' for i in write))
        self.assertTrue(any(i['mnemonic']=='mov' and i['operand']=='dword ptr [rsi + 0x10], 0x9999'
            for i in methods[512714]['instructions']))

    def test_all_nine_fresh_original_query_configs_match_order_names_and_absent_level(self):
        rows = query.source_queries(CLIENT)
        self.assertEqual(query.load_profile()['classicQueries'],rows)
        self.assertEqual(list(range(41001,41010)),[r['eventId'] for r in rows])
        self.assertEqual([9,5,12,6,9,9,7,14,3],[len(r['queries']) for r in rows])
        self.assertEqual(74,sum(len(r['queries']) for r in rows))
        self.assertTrue(all(r['scope']==0 for r in rows))
        self.assertTrue(all(q['level']==1 and not q['levelPresent'] for r in rows for q in r['queries']))

    def test_all_queries_have_actual_original_parameter_rows_and_exact_raw_words(self):
        resolved = query.resolved_source_values(query.load_profile()['classicQueries'])
        self.assertEqual(74,sum(len(r['values']) for r in resolved))
        values = {v['name']:v['raw'] for r in resolved for v in r['values']}
        self.assertEqual(4294967300,values['ADF_612626_1'])
        self.assertNotEqual((1<<33)//2,values['ADF_612626_1'])
        self.assertEqual(171798691840,values['ADF_612627_1'])

    def test_erudition_keeps_only_three_queries_and_does_not_add_output_only_variables(self):
        rows = query.load_profile()['classicQueries']
        self.assertEqual(['ADF_612821_1','ADF_612822_1','ADF_612823_1'],
            [q['name'] for q in rows[-1]['queries']])
        self.assertEqual(14,len(rows[-2]['queries']))

    def test_complete_original_source_analysis_retains_live_battle_and_hotfix_boundaries(self):
        result = query.analyze(SimpleNamespace(client_dir=CLIENT,
            output=ROOT/'build/rogue-resonance-maze-query-python-evidence.json'))
        self.assertTrue(result['passed'])
        self.assertEqual((9,74),(result['sourceEventCount'],result['sourceQueryCount']))
        for name in ['explicitQueryExecutionImplemented','queryDefaultLevelMatched',
                'queryIndexConventionMatched','sourceFailureContinuesMatched','allOriginalQueriesResolved']:
            self.assertTrue(result[name],name)
        for name in ['nativeQueryTaskExecuted','queryLevelFallbackImplemented','heldBlessingsApplied',
                'liveBattleContextAttached','battleEventCreated','callbackExecutionImplemented','hotfixPatchDecoded',
                'clientGameplayVerified','abilityExecutionVerified','completeResonanceRuntimeImplemented',
                'jsonInstalled','clientExecutableStarted','clientModified']:
            self.assertFalse(result[name],name)

    def test_output_in_client_is_rejected_before_analysis(self):
        with self.assertRaises(ValueError):
            query.analyze(SimpleNamespace(client_dir=CLIENT,output=CLIENT/'maze-query.json'))

    def test_output_outside_project_build_is_rejected_before_analysis(self):
        with self.assertRaises(ValueError):
            query.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'maze-query.json'))


if __name__ == '__main__':
    unittest.main()
