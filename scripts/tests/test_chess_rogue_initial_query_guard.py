"""首次Query工具来源/保存屏障/四轮观察守卫；本文件不启动任何游戏服务。"""
import base64
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / 'src/main/resources/protocol/4.6').is_dir())
SMOKE = HERE / 'smoke-chess-rogue-initial-query.py'
if not SMOKE.is_file():
    SMOKE = ROOT / 'scripts/smoke-chess-rogue-initial-query.py'
spec = importlib.util.spec_from_file_location('initial_query_smoke', SMOKE)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class InitialQueryGuardTest(unittest.TestCase):
    def altered_proof(self, mutation):
        original = json.loads(smoke.PROOF.read_text('utf-8'))
        mutation(original)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'complete.json'
            path.write_text(json.dumps(original), 'utf-8')
            with mock.patch.object(smoke, 'PROOF', path), mock.patch.object(smoke, 'PROOF_SHA', smoke.base.sha(path)):
                with self.assertRaises(ValueError):
                    smoke.initial_source_proof()

    def test_complete_proof_has_all_native_originals_and_all_original_selected_rows(self):
        proof = smoke.initial_source_proof()
        self.assertEqual(15, proof['wholeNativeFunctions'])
        self.assertEqual(5, proof['completeOriginalQualificationAndNousRows'])
        self.assertFalse(proof['clientGameplayVerified'])

    def test_corrupted_whole_function_is_rejected_with_matching_envelope_hash(self):
        self.altered_proof(lambda p: p['wholeNativeFunctions'][0].update(rawBase64=base64.b64encode(b'wrong native bytes').decode()))

    def test_truncated_native_function_inventory_is_rejected(self):
        self.altered_proof(lambda p: p['wholeNativeFunctions'].pop())

    def test_wrong_query_command_is_rejected_before_network(self):
        self.altered_proof(lambda p: p['commands'].update(ChessRogueQueryCsReq=5437))

    def test_optional_run_cannot_be_replaced_by_fabricated_required_run(self):
        self.altered_proof(lambda p: p['optionalRun'].update(field=10))

    def test_missing_mandatory_profile_child_is_rejected(self):
        self.altered_proof(lambda p: p['mandatoryProfile']['requiredChildren'].pop())

    def test_partial_source_row_inventory_is_rejected(self):
        self.altered_proof(lambda p: p['completeSelectedSourceRows'].pop())

    def test_qualification_reward_is_complete_original_and_not_first_reward(self):
        proof = smoke.qualification_source_proof(smoke.ROOT / 'resources/4.6')
        self.assertEqual(dict(RewardID=18013104, ItemID_1=2, Count_1=5000), proof['completeOriginalRows'][1]['row'])
        self.assertFalse(proof['originalFirstReward109011Paid'])

    def test_changed_qualification_reward_amount_is_rejected(self):
        proof = json.loads(smoke.QUALIFICATION_PROOF.read_text('utf-8'))
        proof['completeOriginalRows'][1]['row']['Count_1'] = 1
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'qualification.json'
            path.write_text(json.dumps(proof), 'utf-8')
            with mock.patch.object(smoke, 'QUALIFICATION_PROOF', path):
                with self.assertRaises(ValueError):
                    smoke.qualification_source_proof(smoke.ROOT / 'resources/4.6')

    def test_changed_qualification_source_commit_is_rejected(self):
        proof = json.loads(smoke.QUALIFICATION_PROOF.read_text('utf-8'))
        proof['sourceCommit'] = 'unverified-source'
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'qualification.json'
            path.write_text(json.dumps(proof), 'utf-8')
            with mock.patch.object(smoke, 'QUALIFICATION_PROOF', path):
                with self.assertRaises(ValueError):
                    smoke.qualification_source_proof(smoke.ROOT / 'resources/4.6')

    def test_old_6525_profile_only_accepted_for_preflight(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / 'inert.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('org/hkrpg/server/gameplay/ChessRogueProfile.class', b'inert old profile')
            self.assertFalse(smoke.runtime_capabilities(jar, True))
            with mock.patch.object(smoke.subprocess, 'Popen') as process:
                with self.assertRaisesRegex(ValueError, '6525 is preflight only'):
                    smoke.runtime_capabilities(jar, False)
            process.assert_not_called()

    def test_preflight_never_starts_game_server_or_kcp(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            jar = root / 'inert.jar'; jar.write_bytes(b'inert')
            report = root / 'report.json'
            prepared = dict(artifactSHA256=smoke.base.sha(jar))
            with mock.patch.object(smoke, 'ROOT', root), mock.patch.object(smoke, 'prepare', return_value=(prepared, None, [], '', (1, 2, 3))), \
                    mock.patch.object(smoke, 'unchanged'), mock.patch.object(smoke.subprocess, 'Popen') as process:
                smoke.run(jar, report, True)
            process.assert_not_called()
            result = json.loads(report.read_text('utf-8'))
            self.assertFalse(result['nativeExecuted'])
            self.assertFalse(result['gameServerStarted'])

    def test_qualified_console_order_has_refresh_then_before_and_after(self):
        bridge = smoke.InitialQueryConsoleBridge('qualified', 17)
        seen = ''
        server = ''
        for operation in ['qualify', 'before-qualified', 'after-qualified']:
            seen += 'CHESS_INITIAL_QUERY_READY_UID=10001:' + operation + '\n'
            self.assertEqual(f'chessqueryfixture 10001 {operation} 17\n', bridge.next_command(seen))
            self.assertIsNone(bridge.next_command(seen))
            server += f'HKRPG_CHESS_INITIAL_QUERY_ACK:10001:{operation}:17\n'
            self.assertEqual(f'CHESS_INITIAL_QUERY_OBSERVED:{operation}\n', bridge.next_acknowledgement(server))
        self.assertTrue(bridge.complete)

    def test_unqualified_and_restart_do_not_seed_or_refresh_eligibility(self):
        for mode in ['unqualified', 'unqualified-restart', 'qualified-restart']:
            self.assertNotIn('qualify', smoke.InitialQueryConsoleBridge(mode, 17).operations)

    def test_wrong_uid_cannot_cross_console_barrier(self):
        bridge = smoke.InitialQueryConsoleBridge('unqualified', 17)
        with self.assertRaises(ValueError):
            bridge.next_command('CHESS_INITIAL_QUERY_READY_UID=10002:before-unqualified')

    def test_out_of_order_observation_is_rejected(self):
        bridge = smoke.InitialQueryConsoleBridge('qualified', 17)
        with self.assertRaises(ValueError):
            bridge.next_command('CHESS_INITIAL_QUERY_READY_UID=10001:after-qualified')

    def test_wrong_uid_and_out_of_order_ack_do_not_open_barrier(self):
        for ack in ['HKRPG_CHESS_INITIAL_QUERY_ACK:10002:before-unqualified:17',
                    'HKRPG_CHESS_INITIAL_QUERY_ACK:10001:after-unqualified:17']:
            bridge = smoke.InitialQueryConsoleBridge('unqualified', 17)
            bridge.next_command('CHESS_INITIAL_QUERY_READY_UID=10001:before-unqualified')
            with self.assertRaises(ValueError):
                bridge.next_acknowledgement(ack)
            self.assertFalse(bridge.complete)

    def test_wrong_nonce_is_not_an_acknowledgement(self):
        bridge = smoke.InitialQueryConsoleBridge('unqualified', 17)
        bridge.next_command('CHESS_INITIAL_QUERY_READY_UID=10001:before-unqualified')
        self.assertIsNone(bridge.next_acknowledgement('HKRPG_CHESS_INITIAL_QUERY_ACK:10001:before-unqualified:18'))
        self.assertFalse(bridge.complete)

    def test_previous_failed_evidence_and_lease_are_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / 'inert.jar'; jar.write_bytes(b'inert')
            report = Path(temp) / 'report.json'
            lease = smoke.base.reserve_output(jar, report)
            before = lease.read_bytes()
            with self.assertRaises(FileExistsError): smoke.base.reserve_output(jar, report)
            self.assertEqual(before, lease.read_bytes())
            report.write_bytes(b'original failed evidence')
            with self.assertRaises(FileExistsError): smoke.base.reserve_output(jar, report)
            self.assertEqual(b'original failed evidence', report.read_bytes())

    def test_four_query_phases_never_start_or_restore_a_chess_run(self):
        self.assertEqual(['unqualified', 'unqualified-restart', 'qualified', 'qualified-restart'], smoke.PHASES)
        self.assertNotIn('start', smoke.PHASES)
        self.assertNotIn('leave', smoke.PHASES)
        self.assertNotIn('quit', smoke.PHASES)

    def test_qualification_settlement_is_not_mislabeled_as_login_refresh(self):
        previous = dict(gameplay=dict(mainMissions={}, rewards={}))
        current = dict(gameplay=dict(mainMissions={'8013104': dict(phase='CLOSED')},
            rewards={'mission:8013104': dict(rewardId=18013104, granted=True)}))
        qualification = dict(before=previous, productionSettled=current)
        result = smoke.login_refresh_observation(previous, current, 'qualified', qualification)
        self.assertFalse(result['loginRefreshComparedToPreviousStop']['gameplayChanged'])
        self.assertEqual(previous['gameplay'], result['loginRefreshComparedToPreviousStop']['currentGameplay'])

    def test_actual_login_refresh_changes_are_still_reported_before_qualification(self):
        previous = dict(gameplay=dict(mainMissions={}, rewards={}))
        login = dict(gameplay=dict(mainMissions={'some-original-login-mission': dict(phase='DOING')}, rewards={}))
        current = dict(gameplay=dict(mainMissions={'some-original-login-mission': dict(phase='DOING'), '8013104': dict(phase='CLOSED')},
            rewards={'mission:8013104': dict(rewardId=18013104, granted=True)}))
        result = smoke.login_refresh_observation(previous, current, 'qualified', dict(before=login, productionSettled=current))
        self.assertTrue(result['loginRefreshComparedToPreviousStop']['gameplayChanged'])

    def test_failed_normal_stop_preserves_first_query_failure(self):
        class Owned:
            returncode = None
            stdin = io.BytesIO()
            def poll(self): return self.returncode
            def wait(self, timeout=None): self.returncode = 1
            def terminate(self): self.returncode = 1
        with tempfile.TemporaryDirectory() as temp:
            try:
                try: raise RuntimeError('actual5547 missing HIM')
                finally: smoke.base.stop_owned_server(Owned(), Path(temp), True)
            except RuntimeError as error:
                self.assertEqual('actual5547 missing HIM', str(error))
                self.assertIn('stop also failed', error.__notes__[0])
            else:
                self.fail('Actual native first failure was lost')


if __name__ == '__main__':
    unittest.main()
