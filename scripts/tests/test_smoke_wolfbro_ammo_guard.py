"""哈努新原生验收的输出保护、原章节/坐标来源与首失败保留；不启动游戏JVM。"""
import copy
import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import shutil
from types import SimpleNamespace
import tempfile
import threading
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('wolf_ammo_smoke', ROOT / 'scripts/smoke-wolfbro-ammo.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class OwnedProcess:
    def __init__(self, status):
        self.returncode = None
        self.status = status
        self.stdin = io.BytesIO()

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        self.returncode = self.status

    def terminate(self):
        self.returncode = self.status


class WolfBroAmmoSmokeGuardTest(unittest.TestCase):
    def test_existing_report_is_preserved_before_any_process_start(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar, report = root / 'owned.jar', root / 'report.json'
            jar.write_bytes(b'owned inert artifact')
            report.write_bytes(b'preserved previous evidence')
            with mock.patch.object(smoke.subprocess, 'Popen') as process:
                with self.assertRaises(FileExistsError):
                    smoke.reserve_output(jar, report)
            process.assert_not_called()
            self.assertEqual(b'preserved previous evidence', report.read_bytes())

    def test_lease_refuses_a_second_run_before_output_exists(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar, report = root / 'owned.jar', root / 'report.json'
            jar.write_bytes(b'owned inert artifact')
            lease = smoke.reserve_output(jar, report)
            before = lease.read_bytes()
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(jar, report)
            self.assertEqual(before, lease.read_bytes())
            self.assertFalse(report.exists(), 'A lease cannot manufacture passed native evidence')

    def test_windows_reused_tcp_port_is_not_used_by_both_http_and_mongo(self):
        with mock.patch.object(smoke, 'port', side_effect=[31001, 31001, 31002, 31003]):
            self.assertEqual((31001, 31002, 31003), smoke.transport_ports())

    def test_exhausted_distinct_tcp_selection_is_a_preparation_failure(self):
        with mock.patch.object(smoke, 'port', return_value=31001):
            with self.assertRaisesRegex(RuntimeError, 'distinct'):
                smoke.transport_ports()

    def test_probe_cleanup_error_keeps_the_actual_first_request_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            process = OwnedProcess(1)
            with mock.patch.object(process, 'terminate', side_effect=RuntimeError('owned terminate failed')):
                try:
                    try:
                        raise RuntimeError('actual 6514 request failed')
                    finally:
                        smoke.stop_owned_probe(process, Path(temporary))
                except RuntimeError as error:
                    self.assertEqual('actual 6514 request failed', str(error))
                    self.assertIn('Owned probe cleanup also failed', error.__notes__[0])
                else:
                    self.fail('Actual first request failure was lost')
            self.assertTrue(process.stdin.closed)
            self.assertIn('owned terminate failed', (Path(temporary) / 'cleanup-failures.log').read_text('utf-8'))

    def test_server_stop_error_keeps_the_first_probe_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            process = OwnedProcess(1)
            try:
                try:
                    raise RuntimeError('native wrong drop position')
                finally:
                    smoke.stop_owned_server(process, Path(temporary), ready=True)
            except RuntimeError as error:
                self.assertEqual('native wrong drop position', str(error))
                self.assertIn('Owned server stop also failed', error.__notes__[0])
            else:
                self.fail('Native first failure was lost')
            self.assertIn('Unexpected owned server stop exit', (Path(temporary) / 'cleanup-failures.log').read_text('utf-8'))

    def test_unready_server_is_never_counted_as_a_normal_stop(self):
        with tempfile.TemporaryDirectory() as temporary:
            expected = 1000 if os.name == 'nt' else 232
            self.assertEqual(0, smoke.stop_owned_server(OwnedProcess(expected), Path(temporary), False))
            self.assertEqual(1, smoke.stop_owned_server(OwnedProcess(expected), Path(temporary), True))

    def test_authored_positions_use_real_float32_truncation_not_rounding(self):
        self.assertEqual(-6670, smoke.scene_coordinate(-6.67))
        self.assertEqual(53812, smoke.scene_coordinate(53.81236))
        self.assertEqual(-144590, smoke.scene_coordinate(-144.59))
        self.assertEqual(1234, smoke.scene_coordinate(1.2349))
        self.assertEqual(-1234, smoke.scene_coordinate(-1.2349))

    def test_original_complete_level_and_full_partial_chapter_pass_without_changes(self):
        source = ROOT / 'resources/4.6'
        proof = smoke.source_json.loads((ROOT / 'src/test/resources/fixtures/wolfbro-ammo-4.6.json').read_text('utf-8'))
        prepared = smoke.fixture_inputs(source, proof)
        self.assertEqual((6, 30), (prepared['sourceFilesCount'], prepared['sourceRowsCount']))
        self.assertIn(smoke.CHAPTER + '/MissionInfo_1031501.json', prepared['copiedChapterFiles'])
        self.assertIn(smoke.CHAPTER + '/MissionInfo_1031501.partial.json', prepared['copiedChapterFiles'])
        self.assertEqual({'x': -20016, 'y': 53840, 'z': -146616}, prepared['gunPosition'])
        self.assertEqual({'x': -6670, 'y': 53812, 'z': -144590}, prepared['checkpointMotion']['pos'])
        self.assertEqual([1031501], prepared['prerequisiteRows']['MapEntrance']['FinishMainMissionList'])
        self.assertEqual([103150120], prepared['prerequisiteRows']['MapEntrance']['FinishSubMissionList'])

    def test_complete_original_number_tokens_are_admitted_by_the_runtime_gson_identity(self):
        proof = smoke.source_json.loads((ROOT / 'src/test/resources/fixtures/wolfbro-ammo-4.6.json').read_text('utf-8'))
        group = next(row for row in proof['sourceFiles'] if row['path'].endswith('_G21.json'))
        group['canonicalSHA256'] = '3d0649fd63d95e5a2681b9927b512a2c7099d571891f653a5b58701b38c53cac'
        try:
            prepared = smoke.fixture_inputs(ROOT / 'resources/4.6', proof)
        except ValueError as error:
            self.fail('The fixed complete G21 original must retain its number tokens: ' + str(error))
        self.assertEqual(6, prepared['sourceFilesCount'])

    def test_native_preparation_keeps_all_runtime_checked_originals_inside_its_owned_root(self):
        source = ROOT / 'resources/4.6'
        proof = smoke.source_json.loads((ROOT / 'src/test/resources/fixtures/wolfbro-ammo-4.6.json').read_text('utf-8'))
        prepared = smoke.fixture_inputs(source, proof)
        required = sorted({row['path'] for row in proof['sourceFiles']}
                          | {row['sourcePath'] for row in proof['sourceRows']})

        class PreparationComplete(RuntimeError):
            pass

        with tempfile.TemporaryDirectory() as temporary:
            owned = Path(temporary)
            resources = owned / 'resources/4.6'
            for name in set(required) | set(prepared['copiedChapterFiles']):
                target = resources / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source / name, target)
            java = owned / '.tools/jdk-21/bin/java.exe'
            java.parent.mkdir(parents=True)
            java.write_bytes(b'Never executed in preparation regression')
            artifact = owned / 'build/frozen.jar'
            artifact.parent.mkdir()
            artifact.write_bytes(b'Inert preparation artifact')
            source_proof = owned / 'src/test/resources/fixtures/wolfbro-ammo-4.6.json'
            source_proof.parent.mkdir(parents=True)
            shutil.copy2(ROOT / 'src/test/resources/fixtures/wolfbro-ammo-4.6.json', source_proof)
            inspected = []

            def inspect_prepared_tree(command, **_):
                if command[0] == 'powershell':
                    # Unrelated resource junctions need not be created to inspect owned originals.
                    return SimpleNamespace(returncode=0, stdout=b'', stderr=b'')
                self.assertEqual(java.with_name('javac.exe'), Path(command[0]))
                fixture = next((owned / 'build/smoke').glob('wolfbro-ammo-native-*')) / 'resources-fixture'
                for name in required:
                    target = fixture / name
                    self.assertTrue(target.is_file(), 'Required original was only an external junction: ' + name)
                    self.assertTrue(target.resolve().is_relative_to(fixture.resolve()), name)
                    self.assertEqual((resources / name).read_bytes(), target.read_bytes(), name)
                    inspected.append(name)
                raise PreparationComplete('Physical original closure checked before any JVM')

            with mock.patch.object(smoke, 'ROOT', owned), \
                    mock.patch.object(smoke, 'fixture_inputs', return_value=prepared), \
                    mock.patch.object(smoke.subprocess, 'run', side_effect=inspect_prepared_tree), \
                    mock.patch.object(smoke.subprocess, 'Popen') as process:
                with self.assertRaises(PreparationComplete):
                    smoke.run(artifact, owned / 'build/preparation-report.json')
            process.assert_not_called()
            self.assertEqual(required, inspected)
            self.assertFalse((owned / 'build/preparation-report.json').exists())

    def test_changed_complete_source_gun_row_is_not_an_accepted_fixture(self):
        proof = smoke.source_json.loads((ROOT / 'src/test/resources/fixtures/wolfbro-ammo-4.6.json').read_text('utf-8'))
        changed = copy.deepcopy(proof)
        gun = next(row for row in changed['sourceRows'] if row['table'] == 'MazeProp' and row['key'] == 103068)
        gun['data']['JsonPath'] = 'invented-gun-config'
        with self.assertRaisesRegex(ValueError, 'complete table row changed'):
            smoke.fixture_inputs(ROOT / 'resources/4.6', changed)

    def test_loopback_http_ignores_environment_proxy(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                data = json.dumps({'owned': True}).encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *_):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
        thread.start()
        try:
            with mock.patch.dict(os.environ, {'http_proxy': 'http://127.0.0.1:1'}, clear=True), \
                    mock.patch.object(smoke.urllib.request, 'proxy_bypass', return_value=False):
                self.assertEqual({'owned': True}, smoke.local_request(server.server_port, '/health/ready'))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == '__main__':
    unittest.main()
