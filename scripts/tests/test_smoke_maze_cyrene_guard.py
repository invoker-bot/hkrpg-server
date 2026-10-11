"""Cyrene独立Native工具的证据/闭包保护，不启动游戏服务器。"""
import importlib.util
import json
import os
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / 'scripts/smoke_resource_fixture.py').is_file())
spec = importlib.util.spec_from_file_location('cyrene_smoke', ROOT / 'scripts/smoke-maze-cyrene-ability.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
ABILITY = 'Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Cyrene_00_Ability.json'
ORIGINALS = set(smoke.common.QUOTE_TABLES) | {ABILITY, 'ExcelOutput/AvatarMazeBuff.json', 'ExcelOutput/AdventurePlayer.json'}


class CyreneNativeGuardTest(unittest.TestCase):
    def test_original_duration_survives_transport_wait_with_read_only_query_slots(self):
        java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
        jar = ROOT / 'build/initial-query-cyrene-daily-origin-clock-runtime.jar'
        with tempfile.TemporaryDirectory(prefix='cyrene-wait-guard-', dir=ROOT / 'build') as temporary:
            classes = Path(temporary)
            compiled = subprocess.run([str(java.with_name('javac.exe')), '--release', '21', '-encoding', 'UTF-8',
                '-cp', str(jar), '-d', str(classes), str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeMazeCyreneAbilityProbe.java')], capture_output=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr.decode('utf-8'))
            tested = subprocess.run([str(java), '-cp', str(classes) + os.pathsep + str(jar),
                'org.hkrpg.server.protocol.NativeMazeCyreneAbilityProbe', '--verify-expiry-wait'], capture_output=True)
            self.assertEqual(0, tested.returncode, tested.stderr.decode('utf-8'))
            observed = json.loads(tested.stdout.decode('utf-8').splitlines()[-1])
            self.assertTrue(observed['passed'])
            self.assertFalse(observed['nativeExecuted'])
            self.assertEqual(31000, observed['waitMilliseconds'])
            self.assertGreaterEqual(observed['readOnlyHeartbeats'], 2)
            self.assertLess(observed['maximumRequestIdleMilliseconds'], observed['declaredKcpIdleTimeoutMilliseconds'])

    def mocked_prepare(self, directory):
        jar = directory / 'not-executed.jar'
        jar.write_bytes(b'guard tests do not execute this inert artifact')
        old_run = smoke.subprocess.run

        def tool(command, *args, **kwargs):
            exe = Path(command[0]).name.lower()
            if exe == 'javac.exe':
                return smoke.subprocess.CompletedProcess(command, 0, stdout=b'', stderr=b'')
            if exe == 'java.exe':
                self.assertEqual('--verify-descriptors', command[-1])
                return smoke.subprocess.CompletedProcess(command, 0, stdout=b'{"passed":true,"unitTestStub":true}\n', stderr=b'')
            return old_run(command, *args, **kwargs)

        with mock.patch.object(smoke.subprocess, 'run', side_effect=tool):
            return smoke.prepare(jar, directory)

    def test_all_seven_whole_originals_are_physical_copies(self):
        with tempfile.TemporaryDirectory(prefix='cyrene-closure-', dir=ROOT / 'build') as temporary:
            ready = self.mocked_prepare(Path(temporary))
            self.assertEqual(ORIGINALS, set(ready['physicalOriginals']))
            for name in ORIGINALS:
                target = ready['fixture'] / name
                self.assertTrue(target.resolve().is_relative_to(ready['fixture'].resolve()))
                self.assertFalse(os.path.samefile(target, ROOT / 'resources/4.6' / name))
                self.assertEqual((ROOT / 'resources/4.6' / name).read_bytes(), target.read_bytes())
            marker = json.loads((ready['fixture'] / 'resource-manifest.json').read_text('utf-8'))
            self.assertTrue(marker['completeOriginal1415Ability'])
            self.assertFalse(marker['useIDOrPaymentPrewritten'])
            self.assertFalse(marker['basePaymentPhaseKnown'])

    def test_rewriting_both_source_and_fixture_cannot_replace_fixed_cyrene_pin(self):
        names = ORIGINALS | set(smoke.source_proof.PINS)
        with tempfile.TemporaryDirectory(prefix='cyrene-pins-', dir=ROOT / 'build') as temporary:
            directory = Path(temporary)
            source, fixture = directory / 'source', directory / 'fixture'
            for name in names:
                for root in (source, fixture):
                    file = root / name
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_bytes((ROOT / 'resources/4.6' / name).read_bytes())
            for root in (source, fixture):
                file = root / ABILITY
                changed = json.loads(file.read_text('utf-8'))
                changed['AbilityList'][2]['OnStart'][11]['LifeTime']['FixedValue']['Value'] = 60
                file.write_text(json.dumps(changed), 'utf-8')
            records = {name: {'originalSHA256': smoke.sha(source / name)} for name in names}
            with self.assertRaisesRegex(ValueError, 'Cyrene|1415|source pin'):
                smoke.verify_original_closure(source, fixture, records)

    def test_native_report_cannot_claim_base_without_inventing_1408_prepayment(self):
        wrong = dict(requests=1, observedReplies=[{'request': 'SceneCastSkillCostMpCsReq'}])
        with self.assertRaisesRegex(ValueError, '1408|prepayment'):
            smoke.verify_snapshot_counts(wrong)

    def test_existing_native_report_and_exclusive_lease_are_preserved(self):
        with tempfile.TemporaryDirectory(prefix='cyrene-evidence-', dir=ROOT / 'build') as temporary:
            root = Path(temporary)
            jar, output = root / 'inert.jar', root / 'existing.json'
            jar.write_bytes(b'not executed')
            output.write_bytes(b'old evidence')
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(jar, output)
            self.assertEqual(b'old evidence', output.read_bytes())
            new = root / 'new.json'
            lease = smoke.reserve_output(jar, new)
            original = lease.read_bytes()
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(jar, new)
            self.assertEqual(original, lease.read_bytes())

    def test_tcp_port_reuse_cannot_merge_http_and_mongo(self):
        with mock.patch.object(smoke.common, 'port', side_effect=[32001, 32001, 32002, 32003]):
            self.assertEqual((32001, 32002, 32003), smoke.transport_ports())

    def test_actual_final_balance_reply_is_part_of_the_request_count(self):
        replies = [{'request': 'SceneCastSkillCsReq'}, {'request': 'GetCurLineupDataCsReq'}]
        with self.assertRaisesRegex(ValueError, 'replies and request count differ'):
            smoke.verify_snapshot_counts({'requests': 1, 'observedReplies': replies})
        smoke.verify_snapshot_counts({'requests': 2, 'observedReplies': replies})

    def test_hardlink_to_original_is_not_an_owned_source_copy(self):
        with tempfile.TemporaryDirectory(prefix='cyrene-hardlink-', dir=ROOT / 'build') as temporary:
            fixture = Path(temporary)
            target = fixture / ABILITY
            target.parent.mkdir(parents=True)
            os.link(ROOT / 'resources/4.6' / ABILITY, target)
            with self.assertRaisesRegex(ValueError, 'complete physical owned copy'):
                smoke.verify_original_closure(ROOT / 'resources/4.6', fixture,
                    {ABILITY: {'originalSHA256': smoke.sha(target)}})

    def test_preflight_does_not_manufacture_a_native_pass(self):
        with tempfile.TemporaryDirectory(prefix='cyrene-preflight-', dir=ROOT / 'build') as temporary:
            root = Path(temporary)
            jar, output, config, proof = [root / name for name in ('inert.jar', 'report.json', 'config.json', 'proof.json')]
            for file in (jar, config, proof):
                file.write_bytes(b'not executed')
            ready = dict(source=root, fixture=root, hashes={}, config=config, configBytes=config.read_bytes(),
                descriptor={'passed': True, 'unitTestStub': True}, physicalOriginals={}, probeSourceSHA256={}, proofFile=proof)
            with mock.patch.object(smoke, 'prepare', return_value=ready), \
                    mock.patch.object(smoke, 'verify_original_closure'), \
                    mock.patch.object(smoke.subprocess, 'Popen') as process:
                smoke.run(jar, output, preflight_only=True)
            process.assert_not_called()
            report = json.loads(output.read_text('utf-8'))
            self.assertTrue(report['preflightPassed'])
            self.assertFalse(report['serverStarted'])
            self.assertFalse(report['nativeExecuted'])
            self.assertNotIn('passed', report)
            self.assertNotIn('boundedOriginal1415FreeEndNetworkVerified', report)

    def test_preparation_failure_preserves_first_exception_and_counts_no_stop(self):
        with tempfile.TemporaryDirectory(prefix='cyrene-first-failure-', dir=ROOT / 'build') as temporary:
            root = Path(temporary)
            jar, output = root / 'inert.jar', root / 'report.json'
            jar.write_bytes(b'not executed')
            with mock.patch.object(smoke, 'prepare', side_effect=ValueError('Original source mismatch')), \
                    mock.patch.object(smoke.tempfile, 'mkdtemp', return_value=str(root)), \
                    mock.patch.object(smoke.subprocess, 'Popen') as process:
                with self.assertRaisesRegex(ValueError, 'Original source mismatch'):
                    smoke.run(jar, output)
            process.assert_not_called()
            failed = json.loads((root / 'failed-run.json').read_text('utf-8'))
            self.assertFalse(failed['serverStarted'])
            self.assertFalse(failed['nativeExecuted'])
            self.assertEqual(0, failed['normalStops'])
            self.assertIn('Original source mismatch', failed['error'])
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
