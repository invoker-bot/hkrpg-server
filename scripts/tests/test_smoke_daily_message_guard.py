"""原生 Phone 隔离脚本的输出排他、端口与失败清理；这些检查不启动服务器。"""
import importlib.util
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('daily_message_smoke', ROOT / 'scripts/smoke-daily-message.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class DailyMessageSmokeGuardTest(unittest.TestCase):
    def test_http_and_mongo_never_use_the_same_tcp_port(self):
        with patch.object(smoke, 'port', side_effect=[1234, 1234, 5678, 9012]):
            self.assertEqual((1234, 5678, 9012), smoke.transport_ports())

    def test_paid_schedule_keeps_partial_phone_across_two_normal_database_restarts(self):
        self.assertEqual([[(1, 'open')], [(1, 'finish'), (2, 'open')], [(2, 'finish'), (2, 'claimed')]],
                         smoke.schedule('settled'))

    def test_unpaid_boundary_request_has_a_dedicated_process_before_any_new_run_query(self):
        self.assertEqual([[(1, 'open')], [(2, 'boundary-reject')], [(2, 'open'), (2, 'finish')], [(2, 'claimed')]],
                         smoke.schedule('expired-unpaid'))

    def fixture(self, root):
        jar = root / 'build/runtime.jar'
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b'not executed fixture')
        java = root / '.tools/jdk-21/bin/java.exe'
        java.parent.mkdir(parents=True)
        java.touch()
        source = root / 'src/test/resources/fixtures/daily-message-3000602-4.6.json'
        source.parent.mkdir(parents=True)
        source.write_text(smoke.json.dumps([{'sourceCommit': smoke.COMMIT}] * 124), 'utf-8')
        return jar, root / 'build/report.json'

    def test_existing_report_is_preserved_without_starting_a_process_or_replacing_lease(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            jar, output = self.fixture(root)
            output.write_text('retained evidence', 'utf-8')
            with patch.object(smoke, 'ROOT', root), patch.object(smoke, 'one') as run, patch.object(smoke.sys, 'argv',
                    ['smoke', '--jar', str(jar), '--output', str(output)]):
                with self.assertRaisesRegex(AssertionError, 'unused report'):
                    smoke.main()
            self.assertEqual('retained evidence', output.read_text('utf-8'))
            self.assertFalse(output.with_name(output.name + '.lease.json').exists())
            run.assert_not_called()

    def test_existing_lease_is_preserved_and_blocks_an_overlapping_run(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            jar, output = self.fixture(root)
            lease = output.with_name(output.name + '.lease.json')
            lease.write_text('active or failed run reservation', 'utf-8')
            with patch.object(smoke, 'ROOT', root), patch.object(smoke, 'one') as run, patch.object(smoke.sys, 'argv',
                    ['smoke', '--jar', str(jar), '--output', str(output)]):
                with self.assertRaises(FileExistsError):
                    smoke.main()
            self.assertEqual('active or failed run reservation', lease.read_text('utf-8'))
            self.assertFalse(output.exists())
            run.assert_not_called()

    def test_bad_normal_stop_keeps_the_original_failure_and_records_cleanup_details(self):
        original = RuntimeError('original Phone assertion')
        child = Mock()
        child.poll.return_value = None
        child.wait.side_effect = [TimeoutError('stop timed out'), None]
        child.stdin.closed = False
        self.assertFalse(smoke.cleanup(child, original, normal_stop=True))
        self.assertEqual('original Phone assertion', str(original))
        self.assertIn('stop timed out', original.__notes__[0])
        child.terminate.assert_called_once()
        child.stdin.close.assert_called_once()

    def test_broken_stdin_close_cannot_replace_the_original_phone_failure(self):
        original = RuntimeError('first observed failure')
        child = Mock()
        child.poll.return_value = 1
        child.stdin.closed = False
        child.stdin.close.side_effect = OSError('stdin cleanup failure')
        self.assertFalse(smoke.cleanup(child, original))
        self.assertEqual('first observed failure', str(original))
        self.assertTrue(any('stdin cleanup failure' in note for note in original.__notes__))

    def test_a_cleanup_failure_without_an_original_failure_is_still_a_failure(self):
        child = Mock()
        child.poll.return_value = 1
        child.stdin.closed = False
        child.stdin.close.side_effect = OSError('stdin cleanup failure')
        with self.assertRaisesRegex(OSError, 'stdin cleanup failure'):
            smoke.cleanup(child, None)


if __name__ == '__main__':
    unittest.main()
