"""两份完整战斗事件表、原始枚举和当前波次关卡准备的只读证据。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('event_tables', ROOT/'scripts/read-rogue-battle-event-tables.py')
tables = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tables)


class BattleEventTablesTest(unittest.TestCase):
    def test_eight_complete_methods_match_original_client(self):
        self.assertEqual((8, 5391), tables.verify_native(CLIENT))

    def test_all_original_table_records_are_fully_consumed(self):
        profile = tables.load_profile()
        totals = [0, 0, 0]
        for record in profile['records']:
            raw = bytes.fromhex(record['rawHex'])
            config = 'BattleEventConfig.' in record['record']['sourcePath']
            reader = tables.Reader(raw)
            rows = reader.table(tables.CONFIG if config else tables.DATA)
            self.assertEqual(len(raw), reader.pos)
            self.assertEqual(record['rows'], len(rows))
            totals[0] += len(rows)
            totals[1] += sum(len(r.get('OverrideProperty') or []) for r in rows)
            totals[2] += sum(len(r.get('ParamList') or []) for r in rows)
        self.assertEqual([956, 820, 488], totals)

    def test_nine_classic_config_rows_keep_raw_speed_properties_and_both_stage_flags(self):
        record = tables.load_profile()['records'][1]
        rows = tables.Reader(bytes.fromhex(record['rawHex'])).table(tables.CONFIG)
        selected = [r for r in rows if 41001 <= r['BattleEventID'] <= 41009]
        self.assertEqual(record['selected'], selected)
        for row in selected:
            self.assertEqual(3, row['Team']['raw'])
            self.assertEqual(3, row['EventSubType']['raw'])
            self.assertTrue(row['HardLevel'])
            self.assertTrue(row['EliteGroup'])
            self.assertEqual((60 if row['BattleEventID']==41003 else 100) << 33, row['Speed'])
            self.assertEqual(90 << 33, row['OverrideProperty'][0]['Value'])

    def test_nine_classic_data_rows_keep_skills_and_exact_character_paths(self):
        record = tables.load_profile()['records'][0]
        rows = tables.Reader(bytes.fromhex(record['rawHex'])).table(tables.DATA)
        selected = [r for r in rows if 41001 <= r['BattleEventID'] <= 41009]
        self.assertEqual(record['selected'], selected)
        for row in selected:
            self.assertEqual([row['BattleEventID']*100+1,row['BattleEventID']*100+2],row['SkillIDList'])
            self.assertTrue(row['IsSPReserved'])
            self.assertTrue(row['Config'].startswith('Config/ConfigCharacter/BattleEvent/'))

    def test_current_wave_stage_identity_is_a_three_field_chain(self):
        fields = tables.load_profile()['stageContext']
        self.assertEqual([0x98, 0x18, 0x2a0],[r['field']['instanceOffset'] for r in fields])
        self.assertEqual('<CurrentWaveStageID>k__BackingField', fields[-1]['field']['name'])
        self.assertEqual('TurnBasedGameMode', fields[-1]['name'])

    def test_property_enum_includes_227_constants_and_does_not_count_instance_value_field(self):
        types = {t['name']:t for t in tables.load_profile()['types']}
        fields = types['AbilityProperty']['fields']
        values = [f['constant']['value'] for f in fields if 'constant' in f]
        self.assertEqual(list(range(227)), sorted(values))
        self.assertEqual(228, len(fields))

    def test_unknown_masks_missing_identity_truncation_and_trailing_bytes_fail_closed(self):
        for raw in ['02', '0200', '02808004', '0201', '020100', '0000']:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                tables.Reader(bytes.fromhex(raw)).table(tables.CONFIG)
        self.assertEqual([],tables.Reader(b'\0').table(tables.CONFIG))

    def test_fresh_original_analysis_preserves_runtime_and_client_acceptance_boundaries(self):
        result = tables.analyze(SimpleNamespace(client_dir=CLIENT,
            output=ROOT/'build/rogue-battle-event-tables-python-evidence.json'))
        self.assertTrue(result['passed'])
        self.assertEqual((99243,956,18),(result['sourceTableBytes'],result['sourceRows'],result['classicRows']))
        for field in ['allOriginalEventTablesConsumed','standardBattleEventTableReaderImplemented',
                'explicitEventPreparationImplemented','currentWaveStageFieldMatched']:
            self.assertTrue(result[field],field)
        for field in ['battleEventCreated','liveBattleContextAttached','callbackExecutionImplemented',
                'abilityExecutionVerified','clientGameplayVerified','completeResonanceRuntimeImplemented',
                'jsonInstalled','clientExecutableStarted','clientModified']:
            self.assertFalse(result[field],field)

    def test_output_inside_client_is_rejected(self):
        with self.assertRaises(ValueError):
            tables.analyze(SimpleNamespace(client_dir=CLIENT,output=CLIENT/'events.json'))

    def test_output_outside_build_is_rejected(self):
        with self.assertRaises(ValueError):
            tables.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'events.json'))


if __name__ == '__main__':
    unittest.main()
