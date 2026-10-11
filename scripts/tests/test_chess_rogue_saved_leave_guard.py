"""保存离开探针的原件、隔离、排他与纯预检门槛；不启动游戏服务。"""
import base64
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
spec=importlib.util.spec_from_file_location('saved_leave_smoke',ROOT/'scripts/smoke-chess-rogue-saved-leave.py')
smoke=importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SavedLeaveGuardTest(unittest.TestCase):
    def test_complete_source_proof_has_all_native_originals_and_whole_lua(self):
        proof=smoke.leave_source_proof()
        self.assertEqual(11,proof['wholeNativeFunctions'])
        self.assertEqual((13145,30),(proof['wholeLuaBytes'],proof['fullyConsumedLuaPrototypes']))
        self.assertFalse(proof['clientGameplayVerified'])

    def altered_proof(self,mutation):
        original=json.loads(smoke.PROOF.read_text('utf-8'))
        mutation(original)
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'complete.json'
            path.write_text(json.dumps(original),'utf-8')
            with mock.patch.object(smoke,'PROOF',path),mock.patch.object(smoke,'PROOF_SHA',smoke.base.sha(path)):
                with self.assertRaises(ValueError):
                    smoke.leave_source_proof()

    def test_corrupted_whole_native_function_is_rejected_even_with_matching_envelope_hash(self):
        self.altered_proof(lambda p:p['wholeNativeFunctions'][0].update(rawBase64=base64.b64encode(b'inert wrong source').decode()))

    def test_truncated_native_function_inventory_is_rejected(self):
        self.altered_proof(lambda p:p['wholeNativeFunctions'].pop())

    def test_wrong_leave_command_is_rejected_before_network(self):
        self.altered_proof(lambda p:p['commands'].update(ChessRogueLeaveCsReq=5464))

    def test_partial_lua_prototypes_are_not_claimed_as_complete_original(self):
        self.altered_proof(lambda p:p['wholeLua'].update(fullyConsumedPrototypeCount=29))

    def test_f2_missing_new_profile_and_lease_is_only_accepted_for_preflight(self):
        with tempfile.TemporaryDirectory() as temp:
            jar=Path(temp)/'inert.jar'
            with zipfile.ZipFile(jar,'w') as archive:
                archive.writestr('org/hkrpg/server/gameplay/ChessRogueState$ReturnLocation.class',b'inert no feature')
            self.assertEqual((False,False),smoke.runtime_capabilities(jar,True))
            with mock.patch.object(smoke.subprocess,'Popen') as start:
                with self.assertRaisesRegex(ValueError,'F2 is preflight only'):
                    smoke.runtime_capabilities(jar,False)
            start.assert_not_called()

    def test_new_profile_without_persisted_return_lease_is_not_runtime_ready(self):
        with tempfile.TemporaryDirectory() as temp:
            jar=Path(temp)/'inert.jar'
            with zipfile.ZipFile(jar,'w') as archive:
                archive.writestr('org/hkrpg/server/gameplay/ChessRogueProfile.class',b'inert marker only')
            with self.assertRaises(ValueError):
                smoke.runtime_capabilities(jar,False)

    def test_preflight_does_not_start_game_server_or_kcp_child(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            jar=root/'inert.jar';jar.write_bytes(b'inert')
            report=root/'report.json'
            prepared=dict(artifactSHA256=smoke.base.sha(jar))
            with mock.patch.object(smoke,'ROOT',root),mock.patch.object(smoke,'prepare',return_value=(prepared,None,[],'',(1,2,3))), \
                    mock.patch.object(smoke,'unchanged'),mock.patch.object(smoke.subprocess,'Popen') as process:
                smoke.run(jar,report,True)
            process.assert_not_called()
            result=json.loads(report.read_text('utf-8'))
            self.assertFalse(result['nativeExecuted'])
            self.assertFalse(result['gameServerStarted'])

    def test_old_evidence_and_lease_are_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            jar=Path(temp)/'inert.jar';jar.write_bytes(b'inert')
            report=Path(temp)/'report.json'
            lease=smoke.base.reserve_output(jar,report)
            before=lease.read_bytes()
            with self.assertRaises(FileExistsError):smoke.base.reserve_output(jar,report)
            self.assertEqual(before,lease.read_bytes())
            report.write_bytes(b'original failed evidence')
            with self.assertRaises(FileExistsError):smoke.base.reserve_output(jar,report)
            self.assertEqual(b'original failed evidence',report.read_bytes())

    def test_saved_record_phases_have_two_normal_h2_restarts_and_no_fake_finish(self):
        self.assertEqual(['saved-leave','paused-resume','resaved-leave'],smoke.PHASES)
        self.assertNotIn('quit',smoke.PHASES)
        self.assertNotIn('finished',smoke.PHASES)

    def test_failed_stop_preserves_actual_leave_failure(self):
        class Owned:
            returncode=None
            stdin=io.BytesIO()
            def poll(self):return self.returncode
            def wait(self,timeout=None):self.returncode=1
            def terminate(self):self.returncode=1
        with tempfile.TemporaryDirectory() as temp:
            try:
                try:raise RuntimeError('actual5462-stage14 missing')
                finally:smoke.base.stop_owned_server(Owned(),Path(temp),True)
            except RuntimeError as e:
                self.assertEqual('actual5462-stage14 missing',str(e))
                self.assertIn('stop also failed',e.__notes__[0])
            else:self.fail('Actual native first failure was lost')


if __name__=='__main__':
    unittest.main()
