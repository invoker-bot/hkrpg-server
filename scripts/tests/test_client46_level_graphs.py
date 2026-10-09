"""脚本读取边界；实际七份记录仅在指定客户端存在时核对。"""
import importlib.util
import json
import struct
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('level_graphs', ROOT / 'scripts/decode-client-level-graphs.py')
graphs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graphs)


def varint(value):
    raw = bytearray()
    while value >= 128:
        raw.append((value & 127) | 128)
        value >>= 7
    raw.append(value)
    return bytes(raw)


class Client46LevelGraphReaderTest(unittest.TestCase):
    def test_unsigned_64_overflow_rejected(self):
        with self.assertRaises(ValueError):
            graphs.Reader(bytes.fromhex('ffffffffffffffffff02')).uvar()

    def test_noncanonical_varint_rejected(self):
        with self.assertRaises(ValueError):
            graphs.Reader(bytes.fromhex('8000')).uvar()

    def test_signed_16_overflow_rejected(self):
        with self.assertRaises(ValueError):
            graphs.Reader(bytes.fromhex('808004')).read({'kind': 6})

    def test_unsigned_32_overflow_rejected(self):
        with self.assertRaises(ValueError):
            graphs.Reader(bytes.fromhex('8080808010')).read({'kind': 9})

    def test_exact_unsigned_64_maximum_and_signed_64_minimum(self):
        data = bytes.fromhex('ffffffffffffffffff01')
        self.assertEqual((1 << 64) - 1, graphs.Reader(data).read({'kind': 11}))
        self.assertEqual(-(1 << 63), graphs.Reader(data).read({'kind': 10}))

    def test_array_null_empty_and_oversized_counts(self):
        array = {'kind': 29, 'elementType': {'kind': 2}}
        self.assertIsNone(graphs.Reader(b'\x01').read(array))
        self.assertEqual([], graphs.Reader(b'\x00').read(array))
        for raw in (varint(8194), varint(3)):
            with self.assertRaises(ValueError):
                graphs.Reader(raw).read(array)

    def test_truncated_string_and_invalid_utf8_are_rejected(self):
        for raw in (b'\x02x', b'\x01\xff', varint(65537)):
            with self.assertRaises(ValueError):
                graphs.Reader(raw).read({'kind': 14})

    def test_invalid_boolean_and_nonfinite_float_are_rejected(self):
        with self.assertRaises(ValueError):
            graphs.Reader(b'\x02').read({'kind': 2})
        with self.assertRaises(ValueError):
            graphs.Reader(struct.pack('<f', float('inf'))).read({'kind': 12})

    def test_minimal_graph_preserves_absent_fields(self):
        graph = graphs.decode_graph(b'\x0b\x00\x00\x08')
        self.assertEqual([], graph['OnInitSequece'])
        self.assertEqual([], graph['OnStartSequece'])
        self.assertEqual(4, graph['Type'])
        self.assertNotIn('ValueSource', graph)

    def test_unknown_root_mask_and_trailing_bytes_are_rejected(self):
        for raw in (b'\x20', b'\x00\x00'):
            with self.assertRaises(ValueError):
                graphs.decode_graph(raw)

    def test_no_reader_factories_do_not_consume_the_next_task(self):
        raw = b'\x02\x02\x04\x04' + varint(2074) + varint(1681)
        tasks = graphs.decode_graph(raw)['OnStartSequece'][0]['TaskList']
        self.assertEqual([2074, 1681], [task['taskId'] for task in tasks])
        self.assertTrue(all(task['$noReaderPayload'] for task in tasks))
        self.assertTrue(all('TaskEnabled' not in task for task in tasks))

    def test_unknown_task_and_variant_are_rejected(self):
        for description, value in (({'kind': 18, 'name': 'TaskConfig'}, 4000),
                                   ({'kind': 18, 'name': 'TargetEvaluator'}, 115)):
            with self.assertRaises(ValueError):
                graphs.Reader(varint(value)).read(description)

    def test_unknown_struct_is_not_silently_treated_as_an_enum(self):
        with self.assertRaises(ValueError):
            graphs.Reader(b'\x00').read({'kind': 17, 'name': 'UnverifiedStruct', 'typeIndex': 999999})

    def test_shared_string_key_and_missing_value_remain_distinct(self):
        value = graphs.Reader(b'\x06\x01\x01K').read({'kind': 18, 'name': 'SharedValue'})
        self.assertEqual('SharedString', value['$type'])
        self.assertEqual('K', value['Key'])
        self.assertNotIn('Value', value)

    def test_dynamic_fixed_integer_is_preserved_without_scale_guess(self):
        value = graphs.Reader(b'\x00\x05').read({'kind': 18, 'name': 'DynamicFloat'})
        self.assertEqual(-3, value['fixedRawSigned'])
        self.assertNotIn('Value', value)
        with self.assertRaises(ValueError):
            graphs.Reader(b'\x01').read({'kind': 18, 'name': 'DynamicFloat'})

    def test_recursive_field_depth_is_bounded_before_stack_exhaustion(self):
        profile = {'classes': {'Nested': {'fields': [{'name': 'Child', 'type': {'kind': 18, 'name': 'Nested'}}]}}}
        with self.assertRaisesRegex(ValueError, '嵌套'):
            graphs.Reader(b'\x01' * 40 + b'\x00', profile).read({'kind': 18, 'name': 'Nested'})

    def test_total_field_count_is_bounded(self):
        with patch.object(graphs, 'MAX_NODES', 10), self.assertRaisesRegex(ValueError, '数量'):
            graphs.Reader(b'\x20' + b'\x00' * 16).read({'kind': 29, 'elementType': {'kind': 2}})

    def test_unverified_path_and_client_output_are_refused_before_client_reads(self):
        with tempfile.TemporaryDirectory() as directory:
            client = Path(directory)
            with self.assertRaises(ValueError):
                graphs.recover(SimpleNamespace(client_dir=client, paths=['Config/unknown.json'],
                                               output=client.parent / 'unknown.json'))
            with self.assertRaisesRegex(ValueError, '客户端目录'):
                graphs.recover(SimpleNamespace(client_dir=client, paths=[], output=client / 'out.json'))


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定的本机客户端，跳过七份实际关卡脚本')
class Client46OriginalLevelGraphsTest(unittest.TestCase):
    def test_all_seven_records_are_fully_consumed_and_identical_output_is_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(client_dir=CLIENT, paths=[], output=Path(directory) / 'graphs.json')
            first = graphs.recover(args)
            self.assertEqual(first, graphs.recover(args))
            self.assertEqual([401, 502, 503, 1513, 2359, 2165, 59],
                             [row['length'] for row in first['records']])
            self.assertEqual([12, 3, 3, 7, 9, 15, 1],
                             [len(row['graph']['OnStartSequece']) for row in first['records']])
            self.assertTrue(all(row['fullyConsumed'] for row in first['records']))
            for key in ('clientExecutableStarted', 'clientModified', 'clientAccepted',
                        'jsonInstalled', 'scriptsExecuted', 'completeGameplay'):
                self.assertFalse(first[key])
            profile = graphs.load_profile()
            for row in first['records']:
                self.assertEqual(profile['records'][row['sourcePath']]['sha256'], row['sha256'])
            self.assertEqual(first, json.loads(args.output.read_text(encoding='utf-8')))

    def test_existing_different_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'operator.json'
            target.write_text('operator-owned-data', encoding='utf-8')
            args = SimpleNamespace(client_dir=CLIENT, paths=[next(iter(graphs.load_profile()['records']))],
                                   output=target)
            with self.assertRaises(FileExistsError):
                graphs.recover(args)
            self.assertEqual('operator-owned-data', target.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
