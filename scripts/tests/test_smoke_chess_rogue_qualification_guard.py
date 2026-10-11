"""有限Native隔离/来源/顺序守卫；无服务启动或外部写入。"""
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE=Path(__file__).resolve().parent
TARGET=HERE/'smoke-chess-rogue-qualification.py'
if not TARGET.is_file(): TARGET=HERE.parent/'smoke-chess-rogue-qualification.py'
spec=importlib.util.spec_from_file_location('qualification_guard_target',TARGET)
native=importlib.util.module_from_spec(spec); spec.loader.exec_module(native)


class QualificationNativeGuardTest(unittest.TestCase):
    def test_prepared_owned_source_paths_obey_the_existing_portable_path_guard(self):
        def complete(command,**kwargs):
            if kwargs.get('text'):
                return SimpleNamespace(returncode=0,stdout='{"passed":true,"nativeExecuted":false}',stderr='')
            return SimpleNamespace(returncode=0,stdout=b'',stderr=b'')
        with tempfile.TemporaryDirectory(prefix='qualification-portable-guard-') as temporary:
            directory=Path(temporary); (directory/'resources-fixture').mkdir(); (directory/'probe-classes').mkdir()
            (directory/'resources-fixture/resource-manifest.json').write_text(json.dumps(dict(testFixture=True)), 'utf-8')
            with patch.object(native,'qualification_integrity_source',return_value={}), \
                    patch.object(native.initial,'initial_source_proof',return_value={}), \
                    patch.object(native.initial,'qualification_source_proof',return_value={}), \
                    patch.object(native,'runtime_capabilities',return_value=False), \
                    patch.object(native.base,'prepare',return_value=({},Path('java.exe'),[],'fixture-classpath',(1,2,3))), \
                    patch.object(native.subprocess,'run',side_effect=complete):
                prepared,*_=native.prepare(Path('not-executed.jar'),directory,True)
            self.assertTrue(all('\\' not in name for name in prepared['qualificationOwnedFiles']),
                'Declared owned source paths must work with the existing base.inside portable-path guard')

    def test_real_old258b_is_preflight_only_and_cannot_start_a_game_server(self):
        jar=native.ROOT/'build/initial-query-cyrene-daily-origin-clock-runtime.jar'
        self.assertFalse(native.runtime_capabilities(jar,True))
        with patch.object(native.subprocess,'Popen') as start:
            with self.assertRaisesRegex(ValueError,'newly frozen repaired jar'):
                native.runtime_capabilities(jar,False)
            start.assert_not_called()

    def test_fixed_source_keeps_the_four_whole_rows_and_complete_consumer(self):
        proof=native.qualification_integrity_source()
        self.assertEqual(4,len(proof['completeOriginalRows']))
        self.assertEqual(137,proof['wholeNativeUnlockedConsumer']['byteCount'])
        self.assertFalse(proof['clientGameplayVerified'])

    def test_mutated_whole_source_row_is_not_accepted_as_a_partial_proof(self):
        with tempfile.TemporaryDirectory(prefix='qualification-source-guard-') as temporary:
            bad=Path(temporary)/'proof.json'; bad.write_bytes(native.PROOF.read_bytes()+b' ')
            with patch.object(native,'PROOF',bad):
                with self.assertRaisesRegex(ValueError,'source drifted'):
                    native.qualification_integrity_source()

    def test_wrong_uid_cannot_seed_or_observe_the_operator_account(self):
        bridge=native.QualificationConsoleBridge(41)
        with self.assertRaisesRegex(ValueError,'UID'):
            bridge.next_command('CHESS_QUALIFICATION_READY_UID=10002:seed-null')
        self.assertFalse(bridge.complete)

    def test_a_before_barrier_cannot_skip_the_explicit_corrupt_fixture_setup(self):
        bridge=native.QualificationConsoleBridge(41)
        with self.assertRaisesRegex(ValueError,'Out-of-order'):
            bridge.next_command('CHESS_QUALIFICATION_READY_UID=10001:before-null')

    def test_a_source_write_cannot_overlap_an_unacknowledged_fixture_operation(self):
        bridge=native.QualificationConsoleBridge(41)
        self.assertEqual('chessqualfixture 10001 seed-null 41\n',bridge.next_command('CHESS_QUALIFICATION_READY_UID=10001:seed-null'))
        with self.assertRaisesRegex(ValueError,'overlapping'):
            bridge.next_command('CHESS_QUALIFICATION_READY_UID=10001:before-null')

    def test_an_old_console_nonce_does_not_release_the_current_probe(self):
        bridge=native.QualificationConsoleBridge(41)
        bridge.next_command('CHESS_QUALIFICATION_READY_UID=10001:seed-null')
        self.assertIsNone(bridge.next_acknowledgement('HKRPG_CHESS_QUALIFICATION_ACK:10001:seed-null:40'))
        self.assertEqual('seed-null',bridge.pending)
        self.assertFalse(bridge.complete)

    def test_all_three_source_branches_and_first_start_need_every_real_console_ack(self):
        bridge=native.QualificationConsoleBridge(41)
        for operation in native.OPERATIONS:
            self.assertEqual(f'chessqualfixture 10001 {operation} 41\n',
                bridge.next_command(f'CHESS_QUALIFICATION_READY_UID=10001:{operation}'))
            self.assertEqual(f'CHESS_QUALIFICATION_OBSERVED:{operation}\n',
                bridge.next_acknowledgement(f'HKRPG_CHESS_QUALIFICATION_ACK:10001:{operation}:41'))
            self.assertIsNone(bridge.next_command(f'CHESS_QUALIFICATION_READY_UID=10001:{operation}'))
        self.assertTrue(bridge.complete)
        self.assertEqual('after-start',bridge.acknowledged[-1])


if __name__=='__main__': unittest.main()
