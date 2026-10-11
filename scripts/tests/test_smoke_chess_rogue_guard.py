"""Chess 新工具的来源、输出排他、清理首失败及纯预检边界；不启动游戏服务。"""
import base64
import copy
import importlib.util
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
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('chess_rogue_smoke', ROOT / 'scripts/smoke-chess-rogue-merchant.py')
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


class ChessRogueSmokeGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.proof = json.loads(smoke.PROOF.read_text('utf-8'))
        cls.policy = json.loads(smoke.POLICY.read_text('utf-8'))

    def test_existing_evidence_is_rejected_before_starting_a_process(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar, report = Path(temporary) / 'inert.jar', Path(temporary) / 'report.json'
            jar.write_bytes(b'owned inert fixture artifact')
            report.write_bytes(b'prior actual failed evidence')
            with mock.patch.object(smoke.subprocess, 'Popen') as process:
                with self.assertRaises(FileExistsError):
                    smoke.reserve_output(jar, report)
            process.assert_not_called()
            self.assertEqual(b'prior actual failed evidence', report.read_bytes())

    def test_exclusive_lease_blocks_a_second_run_even_before_report_exists(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar, report = Path(temporary) / 'inert.jar', Path(temporary) / 'report.json'
            jar.write_bytes(b'inert jar')
            lease = smoke.reserve_output(jar, report)
            before = lease.read_bytes()
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(jar, report)
            self.assertEqual(before, lease.read_bytes())
            self.assertFalse(report.exists())

    def test_reused_windows_tcp_number_cannot_be_both_http_and_mongo(self):
        with mock.patch.object(smoke, 'port', side_effect=[31001, 31001, 31002, 31003]):
            self.assertEqual((31001, 31002, 31003), smoke.transport_ports())
        with mock.patch.object(smoke, 'port', return_value=31001):
            with self.assertRaisesRegex(RuntimeError, 'distinct'):
                smoke.transport_ports()

    def test_failed_server_cleanup_preserves_the_first_actual_wire_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            process = OwnedProcess(1)
            try:
                try:
                    raise RuntimeError('actual fourth battle settlement failed')
                finally:
                    smoke.stop_owned_server(process, Path(temporary), True)
            except RuntimeError as error:
                self.assertEqual('actual fourth battle settlement failed', str(error))
                self.assertIn('Owned Chess server stop also failed', error.__notes__[0])
            else:
                self.fail('Actual first request failure was lost')

    def test_unready_start_never_counts_as_a_normal_stop(self):
        with tempfile.TemporaryDirectory() as temporary:
            normal = 1000 if os.name == 'nt' else 232
            self.assertEqual(0, smoke.stop_owned_server(OwnedProcess(normal), Path(temporary), False))
            self.assertEqual(1, smoke.stop_owned_server(OwnedProcess(normal), Path(temporary), True))

    def test_probe_cleanup_failure_preserves_actual_stock_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            process = OwnedProcess(1)
            with mock.patch.object(process, 'terminate', side_effect=RuntimeError('owned terminate failed')):
                try:
                    try:
                        raise RuntimeError('actual shop ownership bit changed')
                    finally:
                        smoke.stop_owned_probe(process, Path(temporary))
                except RuntimeError as error:
                    self.assertEqual('actual shop ownership bit changed', str(error))
                    self.assertIn('Owned Chess probe cleanup also failed', error.__notes__[0])
                else:
                    self.fail('Actual first request failure was lost')
            self.assertTrue(process.stdin.closed)

    def test_production_flag_false_and_only_isolated_flag_true_are_required(self):
        enabled = smoke.enabled_policy(self.policy, self.proof)
        self.assertTrue(enabled['enabled'])
        self.assertFalse(self.policy['enabled'])
        enabled_default = dict(self.policy, enabled=True)
        with self.assertRaisesRegex(ValueError, 'disabled by default'):
            smoke.enabled_policy(enabled_default, self.proof)
        changed = copy.deepcopy(self.proof)
        changed['route']['initialCoins'] = 400
        with self.assertRaisesRegex(ValueError, 'Only enabled'):
            smoke.enabled_policy(self.policy, changed)

    def test_all_original_88_blobs_23_tables_and_82_groups_are_bound(self):
        prepared = smoke.fixture_inputs(ROOT / 'resources/4.6', self.proof, self.policy)
        self.assertEqual((88, 23, 82), (prepared['sourceFilesCount'], prepared['sourceTableCount'], prepared['completeGroupFiles']))
        self.assertEqual([8011020, 8011010], prepared['actors']['normal']['waves'][0])
        self.assertEqual([1004022], prepared['actors']['boss']['waves'][0])
        self.assertEqual(8013104, prepared['qualification']['MainMissionID'])
        self.assertFalse(prepared['localActSource']['taskTagIsWireCommand'])

    def test_encounter_actor_comes_from_original_monster_group_not_room_anchor(self):
        prepared = smoke.fixture_inputs(ROOT / 'resources/4.6', self.proof, self.policy)
        for key, block in [('normal', 'MonsterNormal'), ('boss', 'MonsterBoss')]:
            room = next(row for row in self.policy['rooms'] if row['block'] == block)
            actual = prepared['actors'][key]
            path = f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80201_F80201001/LevelGroup_P80201_F80201001_G{actual["group"]}.json'
            record = next(row for row in self.proof['files'] if row['path'] == path)
            source = json.loads(base64.b64decode(record['rawBase64']))
            matches = [row for row in source.get('MonsterList', []) if row['ID'] == actual['instance']]
            self.assertEqual(1, len(matches), f'{key}: selected actor is not an actual monster placement in the original group')
            self.assertNotEqual(room['anchorGroup'], actual['group'], 'Anchor prop group cannot substitute for the actual encounter actor')

    def test_real_start_is_observed_before_first_battle_and_not_replaced_by_history_seed(self):
        bridge = smoke.FixtureConsoleBridge(719)
        self.assertEqual('chessroguefixture 10001 unlock 719\n', bridge.next_command('CHESS_FIXTURE_READY_UID=10001'))
        self.assertEqual('PREREQUISITES_APPLIED\n', bridge.next_acknowledgement('HKRPG_CHESS_FIXTURE_ACK:10001:unlock:719'))
        # The actual third run credited original reward18013104 at Start, before combat.
        # The native child must wait for a read-only observation of that moment.
        self.assertEqual('chessroguefixture 10001 observe-start 0 719\n',
            bridge.next_command('CHESS_OBSERVE_READY_UID=10001:start:0'))
        self.assertEqual('CHESS_OBSERVATION_RECORDED:start:0\n',
            bridge.next_acknowledgement('HKRPG_CHESS_FIXTURE_ACK:10001:observe-start:0:719'))
        self.assertFalse(bridge.complete)

    def test_each_real_battle_id_gets_its_own_read_only_delta_before_next_encounter(self):
        bridge = smoke.FixtureConsoleBridge(720)
        bridge.next_command('CHESS_FIXTURE_READY_UID=10001')
        bridge.next_acknowledgement('HKRPG_CHESS_FIXTURE_ACK:10001:unlock:720')
        bridge.next_command('CHESS_OBSERVE_READY_UID=10001:start:0')
        bridge.next_acknowledgement('HKRPG_CHESS_FIXTURE_ACK:10001:observe-start:0:720')
        for cell in range(201, 205):
            battle = 873618642 + cell
            self.assertEqual(f'chessroguefixture 10001 observe-win{cell} {battle} 720\n',
                bridge.next_command(f'CHESS_OBSERVE_READY_UID=10001:win{cell}:{battle}'))
            self.assertEqual(f'CHESS_OBSERVATION_RECORDED:win{cell}:{battle}\n',
                bridge.next_acknowledgement(f'HKRPG_CHESS_FIXTURE_ACK:10001:observe-win{cell}:{battle}:720'))
            self.assertIsNone(bridge.next_command(f'CHESS_OBSERVE_READY_UID=10001:win{cell}:{battle}'))
            self.assertEqual(cell == 204, bridge.complete)

    def test_truncated_original_group_manifest_is_rejected(self):
        changed = copy.deepcopy(self.proof)
        changed['files'] = changed['files'][:-1]
        with self.assertRaisesRegex(ValueError, '88-blob/23-table'):
            smoke.fixture_inputs(ROOT / 'resources/4.6', changed, self.policy)

    def test_corrupted_complete_original_blob_is_rejected(self):
        changed = copy.deepcopy(self.proof)
        changed['files'][0]['rawBase64'] = 'e30K'
        with self.assertRaisesRegex(ValueError, 'blob SHA mismatch'):
            smoke.fixture_inputs(ROOT / 'resources/4.6', changed, self.policy)

    def test_preflight_mode_cannot_start_game_servers_or_native_clients(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar, report = root / 'inert.jar', root / 'report.json'
            jar.write_bytes(b'inert jar')
            prepared = dict(artifactSHA256=smoke.sha(jar), sourceFilesCount=88)
            with mock.patch.object(smoke, 'ROOT', root), \
                    mock.patch.object(smoke, 'prepare', return_value=(prepared, None, [], '', (1, 2, 3))), \
                    mock.patch.object(smoke, 'unchanged'), mock.patch.object(smoke.subprocess, 'Popen') as process:
                smoke.run(jar, report, preflight_only=True)
            process.assert_not_called()
            result = json.loads(report.read_text('utf-8'))
            self.assertTrue(result['passed'])
            self.assertFalse(result['nativeExecuted'])
            self.assertNotIn('normalStops', result)

    def test_loopback_http_ignores_system_proxy(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                data = b'{"owned":true}'
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
