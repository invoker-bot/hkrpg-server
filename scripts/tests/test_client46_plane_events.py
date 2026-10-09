import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('plane_events', ROOT / 'scripts/inspect-client-plane-events.py')
plane = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plane)


def uvar(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def sint(value):
    return uvar(value * 2 if value >= 0 else -value * 2 - 1)


class PlaneEventReaderTests(unittest.TestCase):
    def test_signed_header_and_native_field_order_preserve_absent_fields(self):
        raw = sint(1) + uvar(0x89) + uvar(99999001) + sint(0) + sint(0)
        self.assertEqual(plane.decode_table(raw), [{'EventID': 99999001, 'DropList': [], 'DisplayItemList': []}])

    def test_world_level_stage_rewards_and_bool_are_independent_fields(self):
        raw = sint(1) + uvar(0x77) + uvar(20134221) + uvar(6) + uvar(201342216) + uvar(70) + b'\x01' + uvar(90)
        self.assertEqual(plane.decode_table(raw), [{'EventID': 20134221, 'WorldLevel': 6,
            'StageID': 201342216, 'Reward': 70, 'IsUseMonsterDrop': True, 'AvatarExpReward': 90}])

    def test_drop_list_reads_each_uint32_and_preserves_authored_null(self):
        raw = sint(1) + uvar(9) + uvar(1) + sint(2) + uvar(127) + uvar(0xffffffff)
        self.assertEqual(plane.decode_table(raw)[0]['DropList'], [127, 0xffffffff])
        raw = sint(1) + uvar(9) + uvar(1) + sint(-1)
        self.assertIsNone(plane.decode_table(raw)[0]['DropList'])

    def test_duplicate_effective_event_world_identity_is_rejected(self):
        # An omitted world level and an explicitly authored zero name the same row.
        with self.assertRaises(ValueError):
            plane.decode_table(sint(2) + uvar(1) + uvar(1) + uvar(3) + uvar(1) + uvar(0))

    def test_row_count_unknown_mask_and_missing_identity_are_rejected(self):
        for raw in [sint(-1), sint(plane.MAX_ROWS + 1), sint(1) + uvar(256),
                    sint(1) + uvar(0), sint(1) + uvar(1) + uvar(0)]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                plane.decode_table(raw)

    def test_unsupported_display_lists_and_invalid_primitive_values_stop(self):
        for raw in [sint(1) + uvar(129) + uvar(1) + sint(1),
                    sint(1) + uvar(129) + uvar(1) + sint(-1),
                    sint(1) + uvar(33) + uvar(1) + b'\x02',
                    sint(1) + uvar(1) + uvar(1 << 32)]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                plane.decode_table(raw)

    def test_truncation_noncanonical_integer_and_trailing_bytes_stop(self):
        for raw in [b'', sint(1) + b'\x80', sint(1) + uvar(1) + b'\x81\x00', b'\x00\x00']:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                plane.decode_table(raw)

    def test_event_queries_refuse_invalid_or_ambiguous_ids(self):
        plane.validate_events([1, 0xffffffff])
        for events in [[], [1, 1], [0], [-1], [1 << 32], [True], [1.0], list(range(1, 258))]:
            with self.subTest(events=events), self.assertRaises(ValueError):
                plane.validate_events(events)

    def test_report_cannot_be_written_inside_client(self):
        with tempfile.TemporaryDirectory() as directory:
            client = Path(directory)
            with self.assertRaises(ValueError):
                plane.inspect(SimpleNamespace(client_dir=client, events=[1], output=client / 'report.json'))


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '固定本机客户端不可用')
class PlaneEventNativeTests(unittest.TestCase):
    def test_all_original_rows_and_missing_event_are_repeatable_without_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            args = SimpleNamespace(client_dir=CLIENT, events=[20134220, 20134221, 20134222], output=path)
            report = plane.inspect(args)
            self.assertEqual(report['rowCount'], 79030)
            self.assertTrue(report['tableFullyConsumed'])
            self.assertEqual(report['tableSemanticSHA256'], plane.TABLE_SHA256)
            self.assertEqual(report['missingEventIDs'], [20134220])
            self.assertEqual([r['WorldLevel'] if 'WorldLevel' in r else 0 for r in report['events'][:7]], list(range(7)))
            self.assertEqual({r['EventID'] for r in report['events']}, {20134221, 20134222})
            self.assertEqual(len(report['events']), 14)
            self.assertTrue(all(not report[key] for key in ['clientExecutableStarted', 'clientModified',
                'clientAccepted', 'jsonInstalled', 'scriptsExecuted', 'completeGameplay']))
            before = path.stat().st_mtime_ns
            self.assertEqual(report, plane.inspect(args))
            self.assertEqual(path.stat().st_mtime_ns, before)
            path.write_bytes(b'operator report')
            with self.assertRaises(FileExistsError):
                plane.inspect(args)
            self.assertEqual(path.read_bytes(), b'operator report')


if __name__ == '__main__':
    unittest.main()
