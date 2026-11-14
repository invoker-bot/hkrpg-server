"""Read-only wait capture regressions using a bounded synthetic object graph."""
import importlib.util
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('client_wait_state', ROOT / 'scripts/client-wait-state.py')
wait = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wait)


class Memory:
    def __init__(self):
        self.data = {}
        self.reads = []
        self.base = 0x180000000
        self.static, self.ui, self.block = 0x100000, 0x110000, 0x120000
        self.dictionary, self.entries, self.entry = 0x130000, 0x140000, 0x150000
        self.put(self.base + 0x93434a8, 'Q', self.static)
        self.put(self.static + 0x1398, 'Q', self.ui)
        self.put(self.ui + 0xd0, 'Q', self.block)
        for offset, value in ((0x124, 0), (0x12c, 1), (0x12f, 0)):
            self.put(self.ui + offset, 'B', value)
        self.put(self.block + 0x3c, 'BB', 1, 0)
        self.put(self.block + 0x10, 'Q', self.dictionary)
        self.put(self.block + 0x20, 'Q', 0)
        self.put(self.dictionary + 0x18, 'Q', self.entries)
        self.put(self.dictionary + 0x20, 'iiii', 2, 7, 1, 1)
        self.put(self.entries + 0x18, 'Q', 3)
        self.put(self.entries + 0x20, 'i', 123)
        self.put(self.entries + 0x30, 'Q', self.entry)
        self.put(self.entries + 0x38, 'i', -1)  # Deleted entry; do not dereference.
        self.put(self.entry + 0x38, 'i', 2)
        self.put(self.entry + 0x3e, 'H', 6175)
        self.put(self.entry + 0x40, 'f', 30)

    def put(self, address, fmt, *values):
        self.data.update({address + i: value for i, value in enumerate(struct.pack('<' + fmt, *values))})

    def image_base(self): return self.base

    def read(self, address, size):
        self.reads.append((address, size))
        try:
            return bytes(self.data[address + i] for i in range(size))
        except KeyError:
            raise AssertionError('Unexpected read outside the known UI fields') from None


class WaitSnapshotTest(unittest.TestCase):
    def test_pending_response_survives_without_credentials_or_deleted_entries(self):
        memory = Memory()
        result = wait.snapshot(memory)
        self.assertEqual([{'responseId': 6175, 'timeoutSeconds': 30}], result['waits'])
        self.assertEqual({'0x10': 1}, result['dictionaryCounts'])
        self.assertFalse(result['loading'])
        self.assertTrue(result['storyMode'])
        self.assertNotIn(str(memory.entry), json.dumps(result))
        self.assertNotIn((memory.entries + 0x48, 8), memory.reads)

    def test_no_ui_yet_is_not_reported_as_an_empty_wait(self):
        memory = Memory()
        memory.put(memory.static + 0x1398, 'Q', 0)
        self.assertEqual({'uiReady': False}, wait.snapshot(memory))

    def test_counts_outside_bound_are_rejected_before_array_read(self):
        for count, free in ((513, 0), (2, 3), (-1, 0)):
            with self.subTest(count=count, free=free):
                memory = Memory()
                memory.put(memory.dictionary + 0x20, 'i', count)
                memory.put(memory.dictionary + 0x2c, 'i', free)
                with self.assertRaises(wait.NotReady): wait.snapshot(memory)
                self.assertNotIn((memory.dictionary + 0x18, 8), memory.reads)

    def test_short_or_invalid_pointers_are_not_followed(self):
        memory = Memory()
        memory.put(memory.dictionary + 0x18, 'Q', 1)
        with self.assertRaises(wait.NotReady): wait.snapshot(memory)
        self.assertNotIn((0x19, 8), memory.reads)
        with patch.object(memory, 'read', return_value=b''):
            with self.assertRaises(wait.NotReady): wait.snapshot(memory)

    def test_small_array_and_nonfinite_wait_limit_are_rejected(self):
        memory = Memory()
        memory.put(memory.entries + 0x18, 'Q', 1)
        with self.assertRaises(wait.NotReady): wait.snapshot(memory)
        for value in (float('nan'), float('inf'), -1):
            memory = Memory()
            memory.put(memory.entry + 0x40, 'f', value)
            with self.subTest(value=value), self.assertRaises(wait.NotReady):
                wait.snapshot(memory)

    def test_dictionary_replaced_mid_sample_is_not_reported_as_valid(self):
        memory = Memory()
        original = memory.read
        def changing(address, size):
            result = original(address, size)
            if address == memory.entry + 0x40:
                memory.put(memory.dictionary + 0x24, 'i', 8)
            return result
        with patch.object(memory, 'read', side_effect=changing):
            with self.assertRaises(wait.NotReady): wait.snapshot(memory)

    def test_empty_dictionary_is_recorded_after_wait_is_removed(self):
        memory = Memory()
        memory.put(memory.dictionary + 0x20, 'i', 0)
        memory.put(memory.dictionary + 0x2c, 'i', 0)
        result = wait.snapshot(memory)
        self.assertEqual([], result['waits'])
        self.assertEqual(0, result['dictionaryCounts']['0x10'])

    def test_fixed_ui_lock_records_ids_and_target_without_exposing_labels(self):
        memory = Memory()
        fixed_dict, fixed_entries, fixed_value = 0x160000, 0x170000, 0x180000
        manager, module, target_dict, target_entries, target = 0x190000, 0x1a0000, 0x1b0000, 0x1c0000, 0x1d0000
        label, focus, focus_name = 0x1e0000, 0x1f0000, 0x200000
        memory.put(memory.block + 0x20, 'Q', fixed_dict)
        memory.put(fixed_dict + 0x18, 'Q', fixed_entries)
        memory.put(fixed_dict + 0x20, 'iiii', 2, 3, 1, 1)
        memory.put(fixed_entries + 0x18, 'Q', 2)
        memory.put(fixed_entries + 0x20, 'i', 13)
        memory.put(fixed_entries + 0x28, 'i', 42)
        memory.put(fixed_entries + 0x30, 'Q', fixed_value)
        memory.put(fixed_entries + 0x38, 'i', -1)
        memory.put(fixed_value + 0x10, 'Q', 0)
        memory.put(fixed_value + 0x18, 'Q', label)
        memory.put(fixed_value + 0x28, 'f', 3.4028234663852886e38)
        memory.put(fixed_value + 0x2c, 'i', 1)
        memory.put(fixed_value + 0x34, 'f', 3.4028234663852886e38)
        memory.put(fixed_value + 0x38, 'B', 1)
        raw_label = 'diagnostic-source'.encode('utf-16le')
        memory.put(label + 0x10, 'i', len(raw_label) // 2)
        memory.data.update({label + 0x14 + i: value for i, value in enumerate(raw_label)})
        memory.put(memory.static + 0x1468, 'Q', manager)
        memory.put(manager + 0x500, 'Q', module)
        memory.put(module + 0x20, 'Q', target_dict)
        memory.put(target_dict + 0x18, 'Q', target_entries)
        memory.put(target_dict + 0x20, 'iiii', 1, 6, 0, 0)
        memory.put(target_entries + 0x18, 'Q', 1)
        memory.put(target_entries + 0x20, 'i', 25)
        memory.put(target_entries + 0x28, 'i', 12)
        memory.put(target_entries + 0x30, 'Q', target)
        memory.put(target + 0x20, 'i', 17)
        memory.put(target + 0x24, 'B', 1)
        memory.put(target + 0x28, 'i', 42)
        memory.put(target + 0x2c, 'B', 1)
        memory.put(memory.ui + 0xb0, 'Q', focus)
        memory.put(focus + 0x30, 'Q', focus_name)
        raw_focus = 'TalkDialog'.encode('utf-16le')
        memory.put(focus_name + 0x10, 'i', len(raw_focus) // 2)
        memory.data.update({focus_name + 0x14 + i: value for i, value in enumerate(raw_focus)})
        result = wait.snapshot(memory)
        self.assertIn('fixedBlocks', result)
        self.assertEqual([{'id': 42, 'kind': 1, 'durationSeconds': 3.4028234663852886e38,
                           'delaySeconds': 3.4028234663852886e38, 'silent': True,
                           'callbackPresent': False,
                           'originSHA256': hashlib.sha256(raw_label).hexdigest()}], result['fixedBlocks'])
        self.assertEqual({'isLocked': True, 'blockId': 42, 'idPresent': True, 'matchesFixedBlock': True},
                         result['uiLockTarget'])
        self.assertEqual('TalkDialog', result['focusedController'])
        self.assertNotIn('diagnostic-source', json.dumps(result))
        self.assertNotIn(str(fixed_value), json.dumps(result))
        self.assertNotIn((fixed_entries + 0x48, 8), memory.reads)

        memory.put(target + 0x28, 'i', 43)
        self.assertFalse(wait.snapshot(memory)['uiLockTarget']['matchesFixedBlock'])
        memory.put(target + 0x28, 'i', 42)

        memory.put(fixed_value + 0x2c, 'i', 2)
        with self.assertRaises(wait.NotReady): wait.snapshot(memory)
        memory.put(fixed_value + 0x2c, 'i', 1)

        original_read = memory.read
        def changed_version(address, size):
            value = original_read(address, size)
            if address == fixed_value + 0x38: memory.put(fixed_dict + 0x24, 'i', 4)
            return value
        with patch.object(memory, 'read', side_effect=changed_version):
            with self.assertRaises(wait.NotReady): wait.snapshot(memory)


class Signal:
    def __init__(self): self.seconds = 0; self.stopped = False
    def is_set(self): return self.stopped
    def wait(self, seconds): self.seconds += seconds


class Process:
    def __init__(self, signal): self.signal = signal; self.closed = False
    def alive(self): return self.signal.seconds < 2
    def exit_code(self): return 0
    def __enter__(self): return self
    def __exit__(self, *unused): self.closed = True


class CaptureLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.target = self.root / 'waits.jsonl'
        self.signal = Signal()
        self.process = Process(self.signal)
        self.factory = Mock(return_value=self.process)
        self.memory = Memory()

    def run_capture(self, **kw):
        options = dict(verify=lambda root: True, memory_factory=lambda *a: self.memory,
                       clock=lambda: self.signal.seconds)
        options.update(kw)
        wait.capture(123, self.root, 456, self.target, self.factory, self.signal, **options)
        return [json.loads(row) for row in self.target.read_text().splitlines()]

    def test_wait_samples_flush_before_exit_and_bind_exact_process_identity(self):
        rows = self.run_capture()
        self.factory.assert_called_once_with(123, self.root, 456, memory_read=True)
        self.assertEqual(['starting', 'sample', 'sample', 'client-exited'], [r['status'] for r in rows])
        self.assertEqual([0, 1], [r['elapsedSeconds'] for r in rows if r['status'] == 'sample'])
        self.assertTrue(self.process.closed)

    def test_unsupported_client_stops_without_opening_process(self):
        rows = self.run_capture(verify=lambda root: False)
        self.assertEqual('unsupported-client', rows[-1]['status'])
        self.factory.assert_not_called()
        self.assertEqual([], self.memory.reads)

    def test_access_denial_is_sanitized_and_does_not_escape_worker(self):
        self.factory.side_effect = OSError('password=private, arbitrary memory')
        rows = self.run_capture()
        self.assertEqual('unavailable', rows[-1]['status'])
        self.assertNotIn('private', self.target.read_text())

    def test_object_race_retries_then_records_wait(self):
        original = wait.snapshot
        count = 0
        def snapshot(memory):
            nonlocal count
            count += 1
            if count == 1: raise wait.NotReady()
            return original(memory)
        with patch.object(wait, 'snapshot', side_effect=snapshot): rows = self.run_capture()
        self.assertEqual(['starting', 'objects-not-ready', 'sample', 'client-exited'], [r['status'] for r in rows])

    def test_duration_limit_and_reader_stop_close_without_stopping_game(self):
        rows = self.run_capture(max_seconds=1)
        self.assertEqual('capture-expired', rows[-1]['status'])
        self.assertTrue(self.process.alive())
        self.assertTrue(self.process.closed)
        self.target = self.root / 'stopped.jsonl'
        self.signal.stopped = True
        self.assertEqual('reader-stopped', self.run_capture()[-1]['status'])

    def test_hash_mismatch_is_rejected(self):
        with patch.object(wait, 'PINNED_FILES', {'GameAssembly.dll': '0' * 64}):
            (self.root / 'GameAssembly.dll').write_bytes(b'a different build')
            self.assertFalse(wait.verify_client(self.root))


if __name__ == '__main__': unittest.main()
