import os
import shutil
import socket
import socketserver
import subprocess
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GIT = shutil.which('git')
BASH = (str(Path(GIT).resolve().parents[1] / 'bin/bash.exe') if os.name == 'nt' and GIT
        else shutil.which('bash'))


@unittest.skipUnless(BASH and Path(BASH).is_file(), 'Native bash unavailable')
class ComposeHealthcheckTests(unittest.TestCase):
    def response(self, status, body, extra_header=''):
        response = (f'HTTP/1.0 {status}\r\nContent-Type: application/json\r\n'
                    f'{extra_header}Connection: close\r\n\r\n{body}').encode()

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                request = b''
                while b'\r\n\r\n' not in request:
                    chunk = self.request.recv(4096)
                    if not chunk:
                        return
                    request += chunk
                self.request.sendall(response)
                self.request.shutdown(socket.SHUT_WR)

        with socketserver.TCPServer(('127.0.0.1', 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                env = dict(os.environ, HKRPG_HEALTH_PORT=str(server.server_address[1]))
                result = subprocess.run([BASH, str(ROOT / 'deploy/healthcheck.sh')], env=env,
                                        capture_output=True, text=True, timeout=8)
                return result.returncode
            finally:
                server.shutdown()
                thread.join(timeout=2)

    def test_healthy_requires_http_200_and_ready_true(self):
        self.assertEqual(0, self.response('200 OK', '{"mode":"BOTH", "ready" : true}'))

    def test_http_200_with_false_is_unhealthy(self):
        self.assertNotEqual(0, self.response('200 OK', '{"ready": false}'))

    def test_http_503_with_true_is_unhealthy(self):
        self.assertNotEqual(0, self.response('503 Unavailable', '{"ready":true}'))

    def test_header_cannot_forge_ready_body(self):
        self.assertNotEqual(0, self.response('200 OK', '{"ready":false}', 'X-Test: "ready":true,\r\n'))

    def test_true_prefix_is_not_a_boolean(self):
        self.assertNotEqual(0, self.response('200 OK', '{"ready":trueish}'))


if __name__ == '__main__':
    unittest.main()
