"""Native smoke must preserve earlier evidence before resource preparation or server launch."""
import importlib.util
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('cavalry_smoke_guard_subject', ROOT / 'scripts/smoke-cavalry-beast.py')
SUBJECT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUBJECT)


class CavalrySmokeGuardTest(unittest.TestCase):
    def test_bootstrap_uid_marker_does_not_replace_confirmed_readiness_before_stop(self):
        class InspectionReached(Exception):
            pass

        class ProbeProcess(StoppableProcess):
            def __init__(self):
                super().__init__(0)
                self.polls = 0

            def poll(self):
                self.polls += 1
                if self.polls >= 2:
                    self.returncode = 0
                return self.returncode

        with tempfile.TemporaryDirectory(prefix='cavalry-bootstrap-', dir=ROOT / 'build') as temporary:
            owned = Path(temporary)
            artifact, report, runtime, _ = prepare_owned_source(owned)
            server = StoppableProcess(1000 if os.name == 'nt' else 232)

            def run(args, **kwargs):
                if 'org.hkrpg.server.gameplay.CavalryFixtureInspector' in args:
                    raise InspectionReached
                return SUBJECT.subprocess.CompletedProcess(args, 0, b'', b'')

            def start(args, **kwargs):
                if '-jar' in args:
                    return server
                snapshot = dict(passed=True, uid=10001, requests=0, checks=[], mode='bootstrap')
                kwargs['stdout'].write(('READY_UID=10001\n' + json.dumps(snapshot) + '\n').encode())
                kwargs['stdout'].flush()
                return ProbeProcess()

            responses = [dict(ready=True), dict(retcode=0, data=dict(account=dict(uid='fixture-account', token='fixture-token'))),
                         dict(retcode=0, data=dict(combo_token='fixture-combo'))]
            caught = None
            with mock.patch.object(SUBJECT, 'ROOT', owned), \
                    mock.patch.object(sys, 'argv', ['smoke-cavalry-beast.py', '--jar', str(artifact), '--output', str(report)]), \
                    mock.patch.object(SUBJECT.tempfile, 'mkdtemp', return_value=str(runtime)), \
                    mock.patch.object(SUBJECT.subprocess, 'run', side_effect=run), \
                    mock.patch.object(SUBJECT.subprocess, 'Popen', side_effect=start), \
                    mock.patch.object(SUBJECT, 'local_request', side_effect=responses), \
                    mock.patch.object(SUBJECT.time, 'sleep'):
                try:
                    SUBJECT.main()
                except Exception as failure:
                    caught = failure
            self.assertIsInstance(caught, InspectionReached, 'Successful bootstrap must reach the offline prerequisite inspector')
            self.assertTrue(server.stdin.closed)
            self.assertFalse((runtime / 'cleanup-failures.log').exists())

    def test_http_and_mongo_cannot_share_a_reused_tcp_port(self):
        with mock.patch.object(SUBJECT, 'port', side_effect=[32001, 32001, 32002, 32003]):
            http, mongo, game = SUBJECT.isolated_ports()
        self.assertNotEqual(http, mongo, 'Windows can return the same just-released TCP port twice')
        self.assertGreater(game, 0)

    def test_cleanup_failure_preserves_the_original_startup_exception(self):
        process = StoppableProcess(1)
        with tempfile.TemporaryDirectory(prefix='cavalry-cleanup-', dir=ROOT / 'build') as temporary:
            caught = None
            try:
                try:
                    raise RuntimeError('Original fixture startup failure')
                finally:
                    SUBJECT.stop_owned_process(process, Path(temporary), ready=False)
            except Exception as failure:
                caught = failure
            self.assertIsInstance(caught, RuntimeError, 'A cleanup failure must not replace the original failure')
            self.assertEqual('Original fixture startup failure', str(caught))
            self.assertIn('Unexpected server stop exit', (Path(temporary) / 'cleanup-failures.log').read_text('utf-8'))
            self.assertTrue(process.stdin.closed)

    def test_a_process_without_confirmed_readiness_is_not_a_normal_stop(self):
        expected = 1000 if os.name == 'nt' else 232
        with tempfile.TemporaryDirectory(prefix='cavalry-stop-', dir=ROOT / 'build') as temporary:
            self.assertEqual(0, SUBJECT.stop_owned_process(StoppableProcess(expected), Path(temporary), ready=False))
            self.assertEqual(1, SUBJECT.stop_owned_process(StoppableProcess(expected), Path(temporary), ready=True))

    def test_loopback_readiness_and_login_ignore_an_environment_http_proxy(self):
        def handler(origin):
            class Handler(BaseHTTPRequestHandler):
                def do_GET(self):
                    data = json.dumps({'origin': origin, 'route': self.path, 'method': self.command}).encode()
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
            # Force proxy eligibility even when the host normally excludes loopback.
            # Both endpoints are owned real sockets; no external network is used.
            with mock.patch.dict(os.environ, {'http_proxy': f'http://127.0.0.1:{proxy.server_port}'}, clear=True), \
                    mock.patch.object(SUBJECT.urllib.request, 'proxy_bypass', return_value=False), \
                    mock.patch.object(SUBJECT.urllib.request, '_opener', None):
                health = SUBJECT.local_request(target.server_port, '/health/ready')
                login = SUBJECT.local_request(target.server_port, '/fixture-login', {'account': 'owned-fixture'})
            self.assertEqual('owned-target', health['origin'], 'Readiness must reach the owned server directly')
            self.assertEqual('owned-target', login['origin'], 'Fixture credentials must reach the owned server directly')
            self.assertEqual('/health/ready', health['route'])
            self.assertEqual(('POST', '/fixture-login'), (login['method'], login['route']))
        finally:
            for server in (target, proxy):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(timeout=2)

    def test_original_mission_files_are_materialized_before_probe_compilation(self):
        class PreparationReached(Exception):
            pass

        with tempfile.TemporaryDirectory(prefix='cavalry-prepare-', dir=ROOT / 'build') as temporary:
            owned = Path(temporary)
            artifact, report, runtime, originals = prepare_owned_source(owned)
            caught = None
            with mock.patch.object(SUBJECT, 'ROOT', owned), \
                    mock.patch.object(sys, 'argv', ['smoke-cavalry-beast.py', '--jar', str(artifact), '--output', str(report)]), \
                    mock.patch.object(SUBJECT.tempfile, 'mkdtemp', return_value=str(runtime)), \
                    mock.patch.object(SUBJECT.subprocess, 'run', side_effect=PreparationReached) as compile_probe:
                try:
                    SUBJECT.main()
                except Exception as failure:
                    caught = failure
            self.assertIsInstance(caught, PreparationReached, 'All pinned mission directories must exist before compilation')
            compile_probe.assert_called_once()
            for relative, data in originals.items():
                self.assertEqual(data, (runtime / 'resources-fixture' / relative).read_bytes(), relative)
                self.assertEqual(data, (owned / 'resources/4.6' / relative).read_bytes(), 'Original resources changed: ' + relative)

    def test_existing_report_is_preserved_before_artifact_or_resource_preparation(self):
        with tempfile.TemporaryDirectory(prefix='cavalry-guard-', dir=ROOT / 'build') as temporary:
            directory = Path(temporary)
            artifact = directory / 'fixture.jar'
            artifact.write_bytes(b'owned placeholder; no JVM may be started')
            report = directory / 'evidence.json'
            original = b'{"passed":true,"priorEvidence":true}\n'
            report.write_bytes(original)
            with mock.patch.object(sys, 'argv', ['smoke-cavalry-beast.py', '--jar', str(artifact), '--output', str(report)]), \
                    mock.patch.object(SUBJECT, 'sha', side_effect=RuntimeError('Artifact preparation happened before evidence rejection')) as prepare:
                with self.assertRaises(Exception) as rejection:
                    SUBJECT.main()
                self.assertIsInstance(rejection.exception, FileExistsError)
                prepare.assert_not_called()
            self.assertEqual(original, report.read_bytes())
            self.assertFalse(report.with_name(report.name + '.lease.json').exists())

    def test_existing_exclusive_lease_rejects_a_second_run_before_resource_preparation(self):
        with tempfile.TemporaryDirectory(prefix='cavalry-guard-', dir=ROOT / 'build') as temporary:
            directory = Path(temporary)
            artifact = directory / 'fixture.jar'
            artifact.write_bytes(b'owned placeholder; no JVM may be started')
            report = directory / 'evidence.json'
            lease = report.with_name(report.name + '.lease.json')
            original = json.dumps({'output': str(report), 'pid': 12345, 'priorEvidence': True}).encode()
            lease.write_bytes(original)

            def artifact_only(path):
                if path == artifact:
                    return 'a' * 64
                raise RuntimeError('Resources were read before the exclusive lease was acquired')

            with mock.patch.object(sys, 'argv', ['smoke-cavalry-beast.py', '--jar', str(artifact), '--output', str(report)]), \
                    mock.patch.object(SUBJECT, 'sha', side_effect=artifact_only):
                with self.assertRaises(Exception) as rejection:
                    SUBJECT.main()
                self.assertIsInstance(rejection.exception, FileExistsError)
            self.assertEqual(original, lease.read_bytes())
            self.assertFalse(report.exists())


def prepare_owned_source(owned):
    # Original group/header objects are used only to exercise filesystem preparation.
    # These small script fixtures do not stand in for NativeKCP gameplay evidence.
    fixed = json.loads((ROOT / 'src/test/resources/fixtures/cavalry-beast-4.6.json').read_text('utf-8'))
    binding = next(row for row in fixed['bindings'] if row['plane'] == 10401 and row['group'] == 176)
    files = dict(fixed['headers'])
    files[binding['path']] = fixed['groups'][binding['path']]
    files['ExcelOutput/MapEntrance.json'] = fixed['records']['ExcelOutput/MapEntrance.json']
    source = owned / 'resources/4.6'
    originals = {}
    for relative, value in files.items():
        target = source / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        originals[relative] = json.dumps(value, ensure_ascii=False).encode('utf-8')
        target.write_bytes(originals[relative])
    (source / 'resource-manifest.json').write_bytes(b'{}')
    proof = dict(sourceCommit=fixed['sourceCommit'], bindings=[binding],
                 groups={binding['path']: fixed['groups'][binding['path']]},
                 records={'ExcelOutput/MapEntrance.json': fixed['records']['ExcelOutput/MapEntrance.json']},
                 fileSHA256={path: hashlib.sha256(data).hexdigest() for path, data in originals.items()})
    proof_path = owned / 'src/test/resources/fixtures/cavalry-beast-4.6.json'
    proof_path.parent.mkdir(parents=True)
    proof_path.write_text(json.dumps(proof), 'utf-8')
    java = owned / '.tools/jdk-21-fixture/bin/java.exe'
    java.parent.mkdir(parents=True)
    java.write_bytes(b'Placeholder; the test never executes this file')
    artifact = owned / 'build/fixture.jar'
    artifact.parent.mkdir()
    artifact.write_bytes(b'Placeholder; the test never starts a JVM')
    report = owned / 'build/evidence.json'
    runtime = owned / 'build/smoke/cavalry-preparation'
    runtime.mkdir(parents=True)
    return artifact, report, runtime, originals


class StoppableProcess:
    def __init__(self, stop_code):
        self.stdin = io.BytesIO()
        self.returncode = None
        self.stop_code = stop_code

    def poll(self):
        return self.returncode

    def wait(self, timeout):
        self.returncode = self.stop_code
        return self.returncode


if __name__ == '__main__':
    unittest.main()
