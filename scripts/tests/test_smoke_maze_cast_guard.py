"""施放 KCP 脚本的证据排他、独占端口、无代理及首失败清理边界；不启动 JVM。"""
import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('maze_cast_smoke', ROOT / 'scripts/smoke-maze-cast-sequence.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class OwnedProcess:
    def __init__(self, exit_code):
        self.stdin = io.BytesIO()
        self.returncode = None
        self.exit_code = exit_code

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        self.returncode = self.exit_code

    def terminate(self):
        self.returncode = self.exit_code


class MazeCastSmokeGuardTest(unittest.TestCase):
    def test_actual_run_materializes_complete_quote_tables_inside_its_resource_root_before_any_server(self):
        class PreparationStopped(Exception):
            pass

        expected_tables = ('MazeSkill.json', 'AvatarConfig.json', 'MultiplePathAvatarConfig.json', 'AvatarSkillConfig.json')
        evidence = ROOT / 'build/battle-cast-draft' / ('maze-quote-guard-' + str(time.time_ns()))
        self.assertTrue(evidence.resolve().is_relative_to((ROOT / 'build').resolve()))
        evidence.mkdir(parents=True, exist_ok=False)
        jar = evidence / 'inert.jar'
        jar.write_bytes(b'owned inert artifact never passed to a JVM')
        original_run = smoke.subprocess.run
        original_popen = smoke.subprocess.Popen

        def no_javac_process(command, *args, **kwargs):
            if Path(command[0]).name.lower() == 'javac.exe':
                return smoke.subprocess.CompletedProcess(command, 0, stdout=b'', stderr=b'')
            return original_run(command, *args, **kwargs)

        def inspect_before_server(command, *args, **kwargs):
            if Path(command[0]).name.lower() != 'java.exe':
                return original_popen(command, *args, **kwargs)
            fixture = (Path(kwargs['cwd']) / 'resources-fixture').resolve()
            self.assertTrue(fixture.is_relative_to((ROOT / 'build/smoke').resolve()))
            for name in expected_tables:
                target = fixture / 'ExcelOutput' / name
                self.assertTrue(target.is_file(), 'Original quote table was omitted: ' + name)
                self.assertTrue(target.resolve().is_relative_to(fixture), 'Quote source escaped the owned closed resource root: ' + name)
                self.assertEqual((ROOT / 'resources/4.6/ExcelOutput' / name).read_bytes(), target.read_bytes(),
                                 'The complete original quote table bytes changed: ' + name)
            raise PreparationStopped('closed source preparation verified without any JVM or server')

        with mock.patch.object(smoke.subprocess, 'run', side_effect=no_javac_process), \
                mock.patch.object(smoke.subprocess, 'Popen', side_effect=inspect_before_server):
            with self.assertRaisesRegex(PreparationStopped, 'closed source preparation verified'):
                smoke.run(jar, evidence / 'not-a-native-report.json')
        self.assertFalse((evidence / 'not-a-native-report.json').exists())

    def test_existing_report_is_preserved_before_any_jvm_or_resource_preparation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar, output = root / 'fixture.jar', root / 'report.json'
            jar.write_bytes(b'owned inert artifact')
            output.write_bytes(b'previous failed or successful evidence')
            with mock.patch.object(smoke.subprocess, 'Popen') as process:
                with self.assertRaises(FileExistsError):
                    smoke.reserve_output(jar, output)
            process.assert_not_called()
            self.assertEqual(b'previous failed or successful evidence', output.read_bytes())
            self.assertFalse(output.with_name(output.name + '.lease.json').exists())

    def test_one_output_can_only_be_reserved_once_even_before_a_report_exists(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar, output = root / 'fixture.jar', root / 'report.json'
            jar.write_bytes(b'owned inert artifact')
            lease = smoke.reserve_output(jar, output)
            first = lease.read_bytes()
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(jar, output)
            self.assertEqual(first, lease.read_bytes())
            self.assertEqual(str(output), json.loads(first)['output'])
            self.assertFalse(output.exists(), 'Reservation must not manufacture a passed report')

    def test_windows_tcp_port_reuse_cannot_assign_http_and_mongo_the_same_port(self):
        with mock.patch.object(smoke, 'port', side_effect=[32001, 32001, 32002, 32003]):
            self.assertEqual((32001, 32002, 32003), smoke.transport_ports())

    def test_repeated_tcp_reuse_is_a_preparation_failure(self):
        with mock.patch.object(smoke, 'port', return_value=32001):
            with self.assertRaisesRegex(RuntimeError, 'distinct'):
                smoke.transport_ports()

    def test_cleanup_failure_does_not_replace_the_first_probe_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            process = OwnedProcess(1)
            try:
                try:
                    raise RuntimeError('Actual request failed before cleanup')
                finally:
                    smoke.stop_owned_process(process, Path(temporary), ready=True)
            except RuntimeError as error:
                self.assertEqual('Actual request failed before cleanup', str(error))
                self.assertIn('Owned stop also failed', error.__notes__[0])
            else:
                self.fail('The actual request failure was lost')
            self.assertTrue(process.stdin.closed)
            self.assertIn('Unexpected owned server stop exit', (Path(temporary) / 'cleanup-failures.log').read_text('utf-8'))

    def test_a_server_without_confirmed_readiness_does_not_count_as_a_normal_stop(self):
        with tempfile.TemporaryDirectory() as temporary:
            expected = 1000 if os.name == 'nt' else 232
            self.assertEqual(0, smoke.stop_owned_process(OwnedProcess(expected), Path(temporary), ready=False))
            self.assertEqual(1, smoke.stop_owned_process(OwnedProcess(expected), Path(temporary), ready=True))

    def test_loopback_requests_ignore_environment_proxies(self):
        def handler(origin):
            class Handler(BaseHTTPRequestHandler):
                def do_GET(self):
                    data = json.dumps(dict(origin=origin, route=self.path, method=self.command)).encode()
                    self.send_response(200)
                    self.send_header('Content-Length', str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)

                def do_POST(self):
                    self.rfile.read(int(self.headers['Content-Length']))
                    self.do_GET()

                def log_message(self, *_):
                    pass
            return Handler

        target = ThreadingHTTPServer(('127.0.0.1', 0), handler('owned-target'))
        proxy = ThreadingHTTPServer(('127.0.0.1', 0), handler('environment-proxy'))
        threads = [threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
                   for server in (target, proxy)]
        for thread in threads:
            thread.start()
        try:
            with mock.patch.dict(os.environ, {'http_proxy': f'http://127.0.0.1:{proxy.server_port}'}, clear=True), \
                    mock.patch.object(smoke.urllib.request, 'proxy_bypass', return_value=False):
                health = smoke.local_request(target.server_port, '/health/ready')
                login = smoke.local_request(target.server_port, '/fixture-login', {'account': 'owned-only'})
            self.assertEqual('owned-target', health['origin'])
            self.assertEqual(('owned-target', 'POST'), (login['origin'], login['method']))
        finally:
            for server in (target, proxy):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(timeout=2)


if __name__ == '__main__':
    unittest.main()
