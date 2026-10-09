import importlib.util
import json
import struct
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('stage_tables', ROOT / 'scripts/inspect-client-stage-tables.py')
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


def uvar(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def sint(value):
    return uvar(value * 2 if value >= 0 else -value * 2 - 1)


def text(value):
    data = value.encode('utf-8')
    return uvar(len(data)) + data


def one(mask, body):
    return sint(1) + uvar(mask) + body


class StageTableReaderTests(unittest.TestCase):
    def test_identity_only_does_not_invent_default_fields(self):
        self.assertEqual([{'StageID': 177}], stage.decode_table(one(1, uvar(177))))
        self.assertEqual([], stage.decode_table(sint(0)))

    def test_native_root_and_child_field_order_preserve_all_authored_values(self):
        # Child bit order is deliberately asserted by names, independently of CHILD_FIELDS.
        sub = sint(1) + uvar(127) + b''.join(text('slot' + str(i)) for i in range(7))
        kv = sint(1) + uvar(3) + text('_Wave') + text('1')
        waves = sint(1) + uvar(31) + b''.join(uvar(i) for i in [100202006, 801301002, 100202006, 0, 0])
        body = (uvar(425001) + sint(34) + sint(0) + uvar(881347569645860828)
                + uvar(1) + uvar(95) + uvar(229) + text('Config/Level/StageCommonTemplate.json')
                + sint(1) + text('StageAbility_HalfSP') + uvar(99) + sub + kv + waves
                + sint(1) + text('LoseA') + sint(0) + b'\x01\x00\x01\x01'
                + struct.pack('<f', 1.1) + b'\x01' + sint(2) + uvar(1209) + uvar(0xffffffff))
        row = stage.decode_table(one((1 << 21) - 1, body))[0]
        self.assertEqual({'StageID': 425001, 'StageType': 'FateActivity',
            'StageName': {'Hash': 881347569645860828}, 'HardLevelGroup': 1, 'Level': 95,
            'EliteGroup': 229, 'LevelGraphPath': 'Config/Level/StageCommonTemplate.json',
            'StageAbilityConfig': ['StageAbility_HalfSP'], 'BattleScoringGroup': 99,
            'SubLevelGraphs': [{'BFLIFKBEOPJ': 'slot0', 'KACPIAGHKMN': 'slot1',
                'EOAHMJFOLHD': 'slot2', 'MEDEFCIKNLA': 'slot3', 'APNFMFEMPNI': 'slot4',
                'LGNKFIIEICM': 'slot5', 'BIEJJCIFBGA': 'slot6'}],
            'StageConfigData': [{'BFLIFKBEOPJ': '_Wave', 'MNDFOPKBHKP': '1'}],
            'MonsterList': [{'Monster0': 100202006, 'Monster1': 801301002,
                'Monster2': 100202006, 'Monster3': 0, 'Monster4': 0}],
            'LevelLoseCondition': ['LoseA'], 'LevelWinCondition': [],
            'ForbidAutoBattle': True, 'ForbidViewMode': False, 'Release': True,
            'ForbidExitBattle': True, 'MonsterWarningRatio': struct.unpack('<f', struct.pack('<f', 1.1))[0],
            'ResetBattleSpeed': True, 'TrialAvatarList': [1209, 0xffffffff]}, row)

    def test_text_id_keeps_unsigned_64_bit_hash_without_cache_field(self):
        row = stage.decode_table(one(5, uvar(177) + sint(-2) + uvar(0xffffffffffffffff)))[0]
        self.assertEqual({'Hash': 0xffffffffffffffff}, row['StageName'])

    def test_native_types_keep_explicit_values_and_reject_unverified_values(self):
        for value, name in [(8, 'VerseSimulation'), (34, 'FateActivity'), (35, 'LocalLegend'),
                            (36, 'ElationActivity'), (37, 'FateRin'),
                            (38, 'ExpeditionBattle'), (39, 'FinalityActivity')]:
            self.assertEqual(name, stage.decode_table(one(3, uvar(1) + sint(value)))[0]['StageType'])
        for value in [-1, 40, 0x7fffffff]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                stage.decode_table(one(3, uvar(1) + sint(value)))

    def test_all_array_families_preserve_explicit_null(self):
        for bit, name in [(7, 'StageAbilityConfig'), (9, 'SubLevelGraphs'), (10, 'StageConfigData'),
                          (11, 'MonsterList'), (12, 'LevelLoseCondition'),
                          (13, 'LevelWinCondition'), (20, 'TrialAvatarList')]:
            self.assertEqual({'StageID': 1, name: None},
                             stage.decode_table(one(1 | (1 << bit), uvar(1) + sint(-1)))[0])

    def test_large_table_bound_does_not_relax_level_graphs(self):
        raw = bytes(4 * 1024 * 1024 + 1)
        self.assertEqual(0, stage.Reader(raw).integer(32, True))
        with self.assertRaises(ValueError):
            stage.graphs.Reader(raw, {})
        with self.assertRaises(ValueError):
            stage.Reader(bytes(stage.MAX_RECORD + 1))

    def test_header_identity_and_duplicate_identity_are_strict(self):
        for raw in [b'', sint(-1), sint(stage.MAX_ROWS + 1), one(0, b''), one(1, uvar(0)),
                    sint(2) + uvar(1) + uvar(177) + uvar(1) + uvar(177)]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                stage.decode_table(raw)

    def test_root_and_child_unknown_masks_are_rejected(self):
        for raw in [one(1 << 21, b''), one(1 | (1 << 9), uvar(1) + sint(1) + uvar(128)),
                    one(1 | (1 << 10), uvar(1) + sint(1) + uvar(4)),
                    one(1 | (1 << 11), uvar(1) + sint(1) + uvar(32))]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                stage.decode_table(raw)

    def test_array_counts_integer_width_bool_and_float_are_bounded(self):
        for raw in [one(1, uvar(1 << 32)),
                    one(1 | (1 << 7), uvar(1) + sint(-2)),
                    one(1 | (1 << 11), uvar(1) + sint(stage.graphs.MAX_ARRAY + 1)),
                    one(1 | (1 << 14), uvar(1) + b'\x02'),
                    one(1 | (1 << 18), uvar(1) + struct.pack('<f', float('inf')))]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                stage.decode_table(raw)

    def test_string_utf8_and_length_are_bounded(self):
        for encoded in [uvar(1) + b'\xff', uvar(65537)]:
            with self.subTest(encoded=encoded), self.assertRaises(ValueError):
                stage.decode_table(one(1 | (1 << 6), uvar(1) + encoded))

    def test_truncation_noncanonical_integers_and_trailing_bytes_stop(self):
        for raw in [b'\x80', one(1, b'\x81\x00'), one(1, uvar(1)) + b'\x00',
                    one(1 | (1 << 18), uvar(1) + b'\x00\x00')]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                stage.decode_table(raw)

    def test_queries_require_unique_positive_uint32_ids(self):
        stage.validate_stages([1, 0xffffffff])
        for ids in [[], [1, 1], [0], [-1], [1 << 32], [True], [1.0], list(range(1, 258))]:
            with self.subTest(ids=ids), self.assertRaises(ValueError):
                stage.validate_stages(ids)

    def test_output_inside_client_is_refused_before_any_read(self):
        with tempfile.TemporaryDirectory() as directory:
            client = Path(directory)
            args = SimpleNamespace(client_dir=client, stages=[1], output=client / 'new.json')
            with self.assertRaises(ValueError):
                stage.inspect(args)
            self.assertFalse(args.output.exists())


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定本机客户端，跳过完整关卡表读取')
class StageTableClientTests(unittest.TestCase):
    def test_all_three_complete_tables_original_types_and_report_reuse(self):
        with tempfile.TemporaryDirectory() as directory:
            source = json.loads((ROOT / 'src/test/resources/fixtures/stage-types-4.6.json').read_text('utf-8'))
            ids = [100, 166, 177, 190, 201342210] + [r['row']['StageID'] for r in source] + [0xffffffff]
            args = SimpleNamespace(client_dir=CLIENT, stages=ids, output=Path(directory) / 'report.json')
            first = stage.inspect(args)
            self.assertEqual([29515, 829, 0], [t['rowCount'] for t in first['tables']])
            self.assertTrue(all(t['tableFullyConsumed'] for t in first['tables']))
            self.assertEqual([0xffffffff], first['missingStageIDs'])
            self.assertEqual(11, len(first['stages']))
            actual = {r['row']['StageID']: r['row'] for r in first['stages']}
            for r in source:
                expected = r['row']
                if 'MonsterWarningRatio' in expected:
                    expected['MonsterWarningRatio'] = struct.unpack('<f', struct.pack('<f', expected['MonsterWarningRatio']))[0]
                self.assertEqual(expected, actual[expected['StageID']])
            for key in ['clientExecutableStarted', 'clientModified', 'clientAccepted',
                        'jsonInstalled', 'scriptsExecuted', 'completeGameplay']:
                self.assertFalse(first[key])
            stamp = args.output.stat().st_mtime_ns
            self.assertEqual(first, stage.inspect(args))
            self.assertEqual(stamp, args.output.stat().st_mtime_ns)
            args.output.write_bytes(b'operator report')
            with self.assertRaises(FileExistsError):
                stage.inspect(args)
            self.assertEqual(b'operator report', args.output.read_bytes())


if __name__ == '__main__':
    unittest.main()
