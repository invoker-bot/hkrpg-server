"""1510原Input探针的完整物理原件、排他证据与预检边界；不启动服务器。"""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('maze_manual_smoke', ROOT / 'scripts/smoke-maze-manual-ability.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class MazeManualSmokeGuardTest(unittest.TestCase):
    def test_preparation_copies_all_three_whole_originals_and_both_extra_quote_tables(self):
        directory = ROOT / 'build/battle-cast-draft' / ('manual-native-guard-' + str(time.time_ns()))
        directory.mkdir(parents=True, exist_ok=False)
        jar = directory / 'inert-not-executed.jar'
        jar.write_bytes(b'Only a mocked toolchain uses this inert fixture')
        original_run = smoke.subprocess.run
        original_popen = smoke.subprocess.Popen
        server_attempts = []

        def non_server_process(command, *args, **kwargs):
            if Path(command[0]).name.lower() == 'java.exe' and '-jar' in command:
                server_attempts.append(command)
                raise AssertionError('The preparation phase must not start a server')
            return original_popen(command, *args, **kwargs)

        def mocked_toolchain(command, *args, **kwargs):
            if Path(command[0]).name.lower() == 'javac.exe':
                return smoke.subprocess.CompletedProcess(command, 0, stdout=b'', stderr=b'')
            if Path(command[0]).name.lower() == 'java.exe':
                self.assertEqual('--verify-descriptors', command[-1])
                return smoke.subprocess.CompletedProcess(command, 0,
                    stdout=b'{"passed":true,"requestSchemas":{},"unitTestStub":true}\n', stderr=b'')
            return original_run(command, *args, **kwargs)

        with mock.patch.object(smoke.subprocess, 'run', side_effect=mocked_toolchain), \
                mock.patch.object(smoke.subprocess, 'Popen', side_effect=non_server_process):
            prepared = smoke.prepare(jar, directory)
        self.assertEqual([], server_attempts)
        fixture = prepared['fixture'].resolve()
        self.assertEqual(5, len(prepared['physicalOriginals']))
        for relative in smoke.SOURCE_FILES:
            self.assertTrue((fixture / relative).resolve().is_relative_to(fixture))
            self.assertFalse(os.path.samefile(fixture / relative, ROOT / 'resources/4.6' / relative))
            self.assertEqual((ROOT / 'resources/4.6' / relative).read_bytes(), (fixture / relative).read_bytes())
        marker = json.loads((fixture / 'resource-manifest.json').read_text('utf-8'))
        self.assertFalse(marker['useIDOrPaymentPrewritten'])
        self.assertTrue(marker['completeOriginal1510Ability'])
        self.assertTrue(marker['originalAstaUnmodified'])
        asta = 'Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Asta_00_Ability.json'
        self.assertEqual((ROOT / 'resources/4.6' / asta).read_bytes(), (fixture / asta).read_bytes())
        group = fixture / 'Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G91.json'
        content = json.loads(group.read_text('utf-8'))
        self.assertEqual(list(range(300001, 300009)), [row['ID'] for row in content['PropList']])
        self.assertEqual({200109, 200110}, {row['ID'] for row in content['MonsterList'] if row['NPCMonsterID'] == 1003010})

    def test_source_hard_link_cannot_pass_as_an_owned_physical_original(self):
        source = ROOT / 'resources/4.6'
        relative = smoke.source_proof.ABILITY
        with tempfile.TemporaryDirectory(prefix='manual-native-hardlink-', dir=ROOT / 'build/battle-cast-draft') as temporary:
            fixture = Path(temporary)
            target = fixture / relative
            target.parent.mkdir(parents=True)
            os.link(source / relative, target)
            with self.assertRaisesRegex(ValueError, 'complete physical owned copy'):
                smoke.verify_original_closure(source, fixture, {relative: {'originalSHA256': smoke.sha(source / relative)}})

    def test_changed_complete_attack_cost_is_rejected_before_any_server(self):
        source = ROOT / 'resources/4.6'
        with tempfile.TemporaryDirectory(prefix='manual-native-changed-cost-') as temporary:
            fixture = Path(temporary)
            records = {}
            for relative in smoke.source_proof.PINS:
                target = fixture / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((source / relative).read_bytes())
                records[relative] = {'originalSHA256': smoke.sha(source / relative)}
            ability = fixture / smoke.source_proof.ABILITY
            changed = json.loads(ability.read_text('utf-8'))
            changed['AbilityList'][2]['OnStart'][11]['OnProjectileHit'][1]['CostMP'] = 1
            ability.write_text(json.dumps(changed), 'utf-8')
            with mock.patch.object(smoke.subprocess, 'Popen') as process:
                with self.assertRaises(ValueError):
                    smoke.verify_original_closure(source, fixture, records)
            process.assert_not_called()

    def test_existing_report_and_first_lease_are_preserved(self):
        with tempfile.TemporaryDirectory(prefix='manual-native-evidence-') as temporary:
            directory = Path(temporary)
            jar, output = directory / 'inert.jar', directory / 'old.json'
            jar.write_bytes(b'inert')
            output.write_bytes(b'old failure or success')
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(jar, output)
            self.assertEqual(b'old failure or success', output.read_bytes())
            output = directory / 'new.json'
            lease = smoke.reserve_output(jar, output)
            previous = lease.read_bytes()
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(jar, output)
            self.assertEqual(previous, lease.read_bytes())

    def test_http_and_mongo_cannot_share_a_reused_tcp_port(self):
        with mock.patch.object(smoke.common, 'port', side_effect=[32001, 32001, 32002, 32003]):
            self.assertEqual((32001, 32002, 32003), smoke.transport_ports())

    def test_native_report_missing_its_real_final_balance_query_is_rejected(self):
        observed = [{'request': 'SceneCastSkillCsReq'}, {'request': 'GetCurLineupDataCsReq'}]
        with self.assertRaisesRegex(ValueError, 'replies and request count differ'):
            smoke.verify_snapshot_counts({'requests': 1, 'observedReplies': observed})

    def test_native_report_counts_each_actual_reply_including_its_final_query(self):
        observed = [{'request': 'SceneCastSkillCsReq'}, {'request': 'GetCurLineupDataCsReq'}]
        smoke.verify_snapshot_counts({'requests': 2, 'observedReplies': observed})

    def test_preflight_never_manufactures_a_native_success(self):
        with tempfile.TemporaryDirectory(prefix='manual-native-preflight-') as temporary:
            directory = Path(temporary)
            jar, output, config = directory / 'inert.jar', directory / 'report.json', directory / 'config.json'
            jar.write_bytes(b'inert')
            config.write_bytes(b'owned config')
            proof = directory / 'proof.json'
            proof.write_bytes(b'owned proof')
            prepared = dict(source=directory, fixture=directory, hashes={}, config=config,
                configBytes=config.read_bytes(), descriptor={'passed': True, 'unitTestStub': True},
                physicalOriginals={}, probeSourceSHA256={}, proofFile=proof)
            with mock.patch.object(smoke, 'prepare', return_value=prepared), \
                    mock.patch.object(smoke, 'verify_original_closure'), \
                    mock.patch.object(smoke.subprocess, 'Popen') as process:
                smoke.run(jar, output, preflight_only=True)
            process.assert_not_called()
            report = json.loads(output.read_text('utf-8'))
            self.assertTrue(report['preflightPassed'])
            self.assertFalse(report['serverStarted'])
            self.assertFalse(report['nativeExecuted'])
            self.assertNotIn('passed', report)
            self.assertNotIn('boundedOriginal1510InputNetworkVerified', report)

    def test_preparation_failure_does_not_count_a_server_or_native_start(self):
        with tempfile.TemporaryDirectory(prefix='manual-native-first-failure-') as temporary:
            directory = Path(temporary)
            jar, output = directory / 'inert.jar', directory / 'report.json'
            jar.write_bytes(b'inert')
            with mock.patch.object(smoke, 'prepare', side_effect=ValueError('Actual first source mismatch')), \
                    mock.patch.object(smoke.tempfile, 'mkdtemp', return_value=str(directory)), \
                    mock.patch.object(smoke.subprocess, 'Popen') as process:
                with self.assertRaisesRegex(ValueError, 'Actual first source mismatch'):
                    smoke.run(jar, output)
            process.assert_not_called()
            report = json.loads((directory / 'failed-run.json').read_text('utf-8'))
            self.assertFalse(report['serverStarted'])
            self.assertFalse(report['nativeExecuted'])
            self.assertEqual(0, report['normalStops'])
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
