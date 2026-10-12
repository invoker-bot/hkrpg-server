"""1407新独立工具的来源、证据和运输保护；不会启动服务器。"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent
ROOT = next(parent for parent in HERE.parents if (parent / 'scripts/smoke_resource_fixture.py').is_file())
def source_path(relative):
    direct = ROOT / relative
    if direct.is_file(): return direct
    for parent in Path(__file__).resolve().parents:
        candidate = parent / relative
        if candidate.is_file(): return candidate
    return direct
spec = importlib.util.spec_from_file_location('castorice_smoke', source_path('scripts/smoke-maze-castorice-out.py'))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class CastoriceOutNativeGuardTest(unittest.TestCase):
    def mocked_prepare(self, directory):
        jar = directory / 'not-executed.jar'
        jar.write_bytes(b'guard tests do not execute this inert artifact')
        actual = smoke.subprocess.run

        def tool(command, *args, **kwargs):
            name = Path(command[0]).name.lower()
            if name == 'javac.exe':
                return subprocess.CompletedProcess(command, 0, stdout=b'', stderr=b'')
            if name == 'java.exe':
                self.assertEqual('--verify-descriptors', command[-1])
                return subprocess.CompletedProcess(command, 0, stdout=b'{"passed":true,"guardStubOnly":true}\n', stderr=b'')
            return actual(command, *args, **kwargs)

        with mock.patch.object(smoke.subprocess, 'run', side_effect=tool):
            return smoke.prepare(jar, directory)

    def test_missing_operator_localplayer_only_materializes_in_the_owned_fixture(self):
        source = ROOT / 'resources/4.6'
        self.assertFalse((source / smoke.PLAYER).exists())
        raw = smoke.original_blob(smoke.DECLARED['sourceFiles'][smoke.PLAYER])
        with tempfile.TemporaryDirectory(prefix='castorice-missing-source-', dir=ROOT / 'build') as directory:
            target = smoke.copy_original_blob(source, Path(directory), smoke.PLAYER, raw)
            self.assertEqual(raw, target.read_bytes())
            self.assertEqual(smoke.PINS[smoke.PLAYER], smoke.sha(target))
        self.assertFalse((source / smoke.PLAYER).exists())

    def test_all_thirteen_required_originals_are_full_physical_copies(self):
        with tempfile.TemporaryDirectory(prefix='castorice-closed-source-', dir=ROOT / 'build') as directory:
            ready = self.mocked_prepare(Path(directory))
            self.assertEqual(13, len(ready['physicalOriginals']))
            self.assertEqual(set(smoke.SOURCE_FILES), set(ready['physicalOriginals']))
            for name, record in ready['physicalOriginals'].items():
                target, operator = ready['fixture'] / name, ROOT / 'resources/4.6' / name
                self.assertTrue(target.resolve().is_relative_to(ready['fixture'].resolve()))
                self.assertEqual(record['originalSHA256'], smoke.sha(target))
                if operator.exists(): self.assertFalse(os.path.samefile(target, operator))
                if name in smoke.PINS:
                    self.assertEqual(smoke.original_blob(smoke.DECLARED['sourceFiles'][name]), target.read_bytes())
            marker = json.loads((ready['fixture'] / 'resource-manifest.json').read_text('utf-8'))
            self.assertTrue(marker['completeOriginal1407Ability'])
            self.assertTrue(marker['localPlayerCopiedOnlyIntoOwnedFixture'])
            self.assertFalse(marker['useIDOrPaymentPrewritten'])
            self.assertFalse(marker['basePaymentPhaseKnown'])

    def test_changing_both_source_and_copy_cannot_replace_the_fixed_original_pin(self):
        with tempfile.TemporaryDirectory(prefix='castorice-pin-guard-', dir=ROOT / 'build') as directory:
            source, fixture = Path(directory) / 'source', Path(directory) / 'fixture'
            records = {}
            for name, record in smoke.DECLARED['sourceFiles'].items():
                raw = smoke.original_blob(record)
                for root in (source, fixture):
                    path = root / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(raw)
                records[name] = dict(originalSHA256=smoke.sha(fixture / name), operatorPresent=True,
                                     operatorSHA256=smoke.sha(source / name))
            for root in (source, fixture):
                path = root / smoke.PLAYER
                path.write_bytes(path.read_bytes() + b' ')
            records[smoke.PLAYER].update(originalSHA256=smoke.sha(fixture / smoke.PLAYER),
                                         operatorSHA256=smoke.sha(source / smoke.PLAYER))
            with self.assertRaisesRegex(ValueError, 'source pin'):
                smoke.verify_original_closure(source, fixture, records)

    def test_base_phase_unknown_cannot_be_reported_as_1408_prepayment(self):
        with self.assertRaisesRegex(ValueError, '1408|prepayment'):
            smoke.verify_snapshot_counts(dict(requests=1, observedReplies=[{'request': 'SceneCastSkillCostMpCsReq'}]))

    def test_existing_output_and_exclusive_lease_are_preserved(self):
        with tempfile.TemporaryDirectory(prefix='castorice-evidence-guard-', dir=ROOT / 'build') as directory:
            root = Path(directory)
            jar, output = root / 'inert.jar', root / 'existing.json'
            jar.write_bytes(b'not executed'); output.write_bytes(b'old evidence')
            with self.assertRaises(FileExistsError): smoke.reserve_output(jar, output)
            self.assertEqual(b'old evidence', output.read_bytes())
            new = root / 'new.json'
            lease = smoke.reserve_output(jar, new)
            original = lease.read_bytes()
            with self.assertRaises(FileExistsError): smoke.reserve_output(jar, new)
            self.assertEqual(original, lease.read_bytes())

    def test_http_and_mongo_do_not_share_a_reused_tcp_port(self):
        with mock.patch.object(smoke.common, 'port', side_effect=[32001, 32001, 32002, 32003]):
            self.assertEqual((32001, 32002, 32003), smoke.transport_ports())

    def test_final_mp_and_read_only_heartbeats_are_counted_as_observed_requests(self):
        replies = [{'request': 'SceneCastSkillCsReq'}, {'request': 'GetCurLineupDataCsReq'}]
        with self.assertRaisesRegex(ValueError, 'replies and request count differ'):
            smoke.verify_snapshot_counts(dict(requests=1, observedReplies=replies))
        smoke.verify_snapshot_counts(dict(requests=2, observedReplies=replies))

    def test_hardlinked_operator_source_is_rejected_before_any_write(self):
        source = ROOT / 'resources/4.6'
        before = (source / smoke.ABILITY).read_bytes()
        with tempfile.TemporaryDirectory(prefix='castorice-hardlink-guard-', dir=ROOT / 'build') as directory:
            target = Path(directory) / smoke.ABILITY
            target.parent.mkdir(parents=True)
            os.link(source / smoke.ABILITY, target)
            with self.assertRaisesRegex(ValueError, 'complete physical owned copy'):
                smoke.copy_original_blob(source, Path(directory), smoke.ABILITY, b'cannot write this')
        self.assertEqual(before, (source / smoke.ABILITY).read_bytes())

    def test_preflight_does_not_manufacture_a_native_pass(self):
        with tempfile.TemporaryDirectory(prefix='castorice-preflight-guard-', dir=ROOT / 'build') as directory:
            root = Path(directory)
            jar, output, config, proof = [root / name for name in ('inert.jar', 'report.json', 'config.json', 'proof.json')]
            for file in (jar, config, proof): file.write_bytes(b'not executed')
            ready = dict(source=root, fixture=root, hashes={}, config=config, configBytes=config.read_bytes(),
                         descriptor={'passed': True, 'guardStubOnly': True}, physicalOriginals={}, probeSourceSHA256={}, proofFile=proof)
            with mock.patch.object(smoke, 'prepare', return_value=ready), \
                    mock.patch.object(smoke, 'verify_original_closure'), \
                    mock.patch.object(smoke.subprocess, 'Popen') as process:
                smoke.run(jar, output, preflight_only=True)
            process.assert_not_called()
            observed = json.loads(output.read_text('utf-8'))
            self.assertTrue(observed['preflightPassed'])
            self.assertFalse(observed['serverStarted'])
            self.assertFalse(observed['nativeExecuted'])
            self.assertNotIn('passed', observed)
            self.assertNotIn('boundedOriginal1407FreeOutNetworkVerified', observed)

    def test_prepare_failure_retains_the_first_error_and_counts_no_stop(self):
        with tempfile.TemporaryDirectory(prefix='castorice-error-guard-', dir=ROOT / 'build') as directory:
            root = Path(directory)
            jar, output = root / 'inert.jar', root / 'report.json'
            jar.write_bytes(b'not executed')
            with mock.patch.object(smoke, 'prepare', side_effect=ValueError('Original source mismatch')), \
                    mock.patch.object(smoke.tempfile, 'mkdtemp', return_value=str(root)), \
                    mock.patch.object(smoke.subprocess, 'Popen') as process:
                with self.assertRaisesRegex(ValueError, 'Original source mismatch'): smoke.run(jar, output)
            process.assert_not_called()
            failed = json.loads((root / 'failed-run.json').read_text('utf-8'))
            self.assertFalse(failed['serverStarted']); self.assertFalse(failed['nativeExecuted'])
            self.assertEqual(0, failed['normalStops']); self.assertFalse(output.exists())

    def test_source_gate_restores_exact_original_bytes_and_rejects_unknown_actions(self):
        with tempfile.TemporaryDirectory(prefix='castorice-source-gate-', dir=ROOT / 'build') as directory:
            fixture = Path(directory)
            raw = smoke.original_blob(smoke.DECLARED['sourceFiles'][smoke.PLAYER])
            path = smoke.copy_original_blob(ROOT / 'resources/4.6', fixture, smoke.PLAYER, raw)
            smoke.source_gate(fixture, 'hide')
            self.assertFalse(path.exists())
            smoke.source_gate(fixture, 'restore')
            self.assertEqual(raw, path.read_bytes())
            with self.assertRaisesRegex(ValueError, 'Unknown'): smoke.source_gate(fixture, 'unknown')
            self.assertEqual(raw, path.read_bytes())

    def test_source_cleanup_cannot_replace_the_first_probe_exception(self):
        with tempfile.TemporaryDirectory(prefix='castorice-source-error-', dir=ROOT / 'build') as directory:
            fixture = Path(directory) / 'resources-fixture'
            raw = smoke.original_blob(smoke.DECLARED['sourceFiles'][smoke.PLAYER])
            smoke.copy_original_blob(ROOT / 'resources/4.6', fixture, smoke.PLAYER, raw)
            smoke.source_gate(fixture, 'hide')
            first = ValueError('Primary actual probe failure')
            with mock.patch.object(smoke, 'source_gate', side_effect=RuntimeError('Secondary cleanup failure')):
                try:
                    raise first
                except ValueError:
                    smoke.restore_source_after_failure(fixture)
            self.assertTrue(first.__notes__)
            self.assertTrue((fixture.parent / 'source-cleanup-failures.log').exists())
            smoke.source_gate(fixture, 'restore')

    def test_original_twenty_second_expiry_wait_retains_read_only_transport_heartbeats(self):
        java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
        jar = ROOT / 'build/initial-query-cyrene-daily-origin-clock-runtime.jar'
        with tempfile.TemporaryDirectory(prefix='castorice-wait-guard-', dir=ROOT / 'build') as directory:
            classes = Path(directory)
            compiled = subprocess.run([str(java.with_name('javac.exe')), '--release', '21', '-encoding', 'UTF-8',
                '-cp', str(jar), '-d', str(classes), str(source_path('scripts/fixtures/java/org/hkrpg/server/protocol/NativeMazeCastoriceOutProbe.java'))], capture_output=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr.decode('utf-8'))
            tested = subprocess.run([str(java), '-cp', str(classes) + os.pathsep + str(jar),
                'org.hkrpg.server.protocol.NativeMazeCastoriceOutProbe', '--verify-expiry-wait'], capture_output=True)
            self.assertEqual(0, tested.returncode, tested.stderr.decode('utf-8'))
            observed = json.loads(tested.stdout.decode('utf-8').splitlines()[-1])
            self.assertEqual(21000, observed['waitMilliseconds']); self.assertFalse(observed['nativeExecuted'])
            self.assertLess(observed['maximumRequestIdleMilliseconds'], observed['declaredKcpIdleTimeoutMilliseconds'])


    def test_actual_out_cleanup_has_one_expected_native_removal_and_rejected_out_has_none(self):
        java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
        jar = ROOT / 'build/castorice-daily-chess-aether-runtime.jar'
        with tempfile.TemporaryDirectory(prefix='out-removal-contract-guard-', dir=ROOT / 'build') as directory:
            classes = Path(directory)
            source = source_path('scripts/fixtures/java/org/hkrpg/server/protocol/NativeMazeCastoriceOutProbe.java')
            compiled = subprocess.run([str(java.with_name('javac.exe')), '--release', '21', '-encoding', 'UTF-8',
                '-cp', str(jar), '-d', str(classes), str(source)], capture_output=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr.decode('utf-8'))
            tested = subprocess.run([str(java), '-cp', str(classes) + os.pathsep + str(jar),
                'org.hkrpg.server.protocol.NativeMazeCastoriceOutProbe', '--verify-out-contract'], capture_output=True)
            self.assertEqual(0, tested.returncode, tested.stderr.decode('utf-8'))
            actual = json.loads(tested.stdout.decode('utf-8').splitlines()[-1])
            self.assertFalse(actual['nativeExecuted']); self.assertFalse(actual['serverStarted'])


if __name__ == '__main__':
    unittest.main()
