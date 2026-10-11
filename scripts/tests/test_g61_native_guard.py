"""G61 原生脚本的报告排他、独占端口、无代理与首失败保留；不启动游戏 JVM。"""
import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('g61_native_smoke', ROOT / 'scripts/smoke-g61-first-talk.py')
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


class G61NativeGuardTest(unittest.TestCase):
    def test_previous_successful_or_failed_report_is_preserved_before_preparation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar, output = root / 'inert.jar', root / 'result.json'
            jar.write_bytes(b'owned non-executable artifact')
            output.write_bytes(b'earlier actual evidence')
            with mock.patch.object(smoke.subprocess, 'Popen') as process:
                with self.assertRaises(FileExistsError):
                    smoke.reserve_output(jar, output)
            process.assert_not_called()
            self.assertEqual(b'earlier actual evidence', output.read_bytes())
            self.assertFalse(output.with_name(output.name + '.lease.json').exists())

    def test_same_report_path_cannot_be_reserved_by_a_second_run(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar, output = root / 'inert.jar', root / 'result.json'
            jar.write_bytes(b'owned non-executable artifact')
            lease = smoke.reserve_output(jar, output)
            before = lease.read_bytes()
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(jar, output)
            self.assertEqual(before, lease.read_bytes())
            self.assertFalse(output.exists(), 'Reservation must not create a passed report')

    def test_previous_failure_sidecar_cannot_be_reused_as_a_new_output_name(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar, output = root / 'inert.jar', root / 'result.json'
            jar.write_bytes(b'owned non-executable artifact')
            failure = output.with_name(output.name + '.failed.json')
            failure.write_bytes(b'actual earlier failure')
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(jar, output)
            self.assertEqual(b'actual earlier failure', failure.read_bytes())
            self.assertFalse(output.with_name(output.name + '.lease.json').exists())

    def test_http_and_mongo_cannot_receive_the_same_tcp_port_after_release(self):
        with mock.patch.object(smoke._support, 'port', side_effect=[33001, 33001, 33002, 33003]):
            self.assertEqual((33001, 33002, 33003), smoke.transport_ports())
        with mock.patch.object(smoke._support, 'port', return_value=33001):
            with self.assertRaisesRegex(RuntimeError, 'distinct'):
                smoke.transport_ports()

    def test_cleanup_failure_preserves_the_original_probe_exception(self):
        process = OwnedProcess(1)
        primary = RuntimeError('Actual G61 first meeting failed')
        try:
            try:
                raise primary
            finally:
                smoke.cleanup(process, primary, normal_stop=True)
        except RuntimeError as error:
            self.assertIs(primary, error)
            self.assertIn('Owned process cleanup failed', error.__notes__[0])
        else:
            self.fail('The original native failure was replaced')
        self.assertTrue(process.stdin.closed)

    def test_a_process_without_confirmed_readiness_is_not_counted_as_a_normal_stop(self):
        expected = 1000 if os.name == 'nt' else 232
        self.assertFalse(smoke.cleanup(OwnedProcess(expected), None, normal_stop=False))
        self.assertTrue(smoke.cleanup(OwnedProcess(expected), None, normal_stop=True))

    def test_all_loopback_requests_bypass_environment_proxy(self):
        def handler(origin):
            class Handler(BaseHTTPRequestHandler):
                def do_GET(self):
                    data = json.dumps(dict(origin=origin, method=self.command)).encode('utf-8')
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
            self.assertEqual(('owned-target', 'GET'), (health['origin'], health['method']))
            self.assertEqual(('owned-target', 'POST'), (login['origin'], login['method']))
        finally:
            for server in (target, proxy):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(timeout=2)


if __name__ == '__main__':
    unittest.main()
