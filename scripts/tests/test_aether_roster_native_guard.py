"""独占工具守卫测试；不启游戏服/客户端，不改原资源。"""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import tempfile
from unittest.mock import patch

HERE=Path(__file__).resolve().parent
ROOT=next(path for path in HERE.parents if (path/'src/main/resources/protocol/4.6').is_dir())
WRAPPER=HERE/'smoke-aether-divide-roster.py'
if not WRAPPER.is_file():WRAPPER=ROOT/'scripts/smoke-aether-divide-roster.py'
spec=importlib.util.spec_from_file_location('aether_roster_owned_guard',WRAPPER)
lane=importlib.util.module_from_spec(spec);spec.loader.exec_module(lane)


class AetherNativeGuardTest(unittest.TestCase):
    def proof(self):return json.loads(lane.PROOF.read_bytes())

    def test_complete_original_fixed_fixture_is_accepted(self):
        value=self.proof();self.assertIs(lane.validate_proof(value),value)

    def test_partial_table_is_rejected(self):
        value=self.proof();value['files']['ExcelOutput/AetherDivideSpirit.json']['completeRows'].pop()
        with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_changed_whole_native_bytes_are_rejected(self):
        value=self.proof();value['wholeNativeFunctions'][0]['rawBase64']='AA=='
        with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_fragment_ownership_and_ui_claims_are_rejected(self):
        for key in ['fragmentIsSpiritOwnership','acquisitionImplemented','officialShortArrayReplacementSemanticsVerified']:
            with self.subTest(key=key):
                value=self.proof();value['scope'][key]=True
                with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_unknown_or_nonpositive_console_nonce_is_rejected(self):
        for mode,nonce in [('unknown',1),('initial',0),('restart',-1)]:
            with self.assertRaises(ValueError):lane.ConsoleBridge(mode,nonce)

    def test_only_owned_uid_can_receive_fixture_command(self):
        with self.assertRaises(ValueError):lane.ConsoleBridge('initial',9).next_command('AETHER_ROSTER_READY_UID=20001:before-empty')

    def test_future_operation_is_rejected(self):
        with self.assertRaises(ValueError):lane.ConsoleBridge('initial',9).next_command('AETHER_ROSTER_READY_UID=10001:seed')

    def test_pending_console_barrier_cannot_be_skipped(self):
        bridge=lane.ConsoleBridge('initial',9);self.assertIn('before-empty',bridge.next_command('AETHER_ROSTER_READY_UID=10001:before-empty'))
        with self.assertRaises(ValueError):bridge.next_command('AETHER_ROSTER_READY_UID=10001:after-empty')

    def test_foreign_nonce_is_not_authorization(self):
        bridge=lane.ConsoleBridge('initial',9);bridge.next_command('AETHER_ROSTER_READY_UID=10001:before-empty')
        self.assertIsNone(bridge.next_acknowledgement('HKRPG_AETHER_ROSTER_ACK:10001:before-empty:8'));self.assertFalse(bridge.complete)

    def test_exact_restart_sequence_completes_without_seed(self):
        bridge=lane.ConsoleBridge('restart',19);ready='';ack=''
        for operation in lane.OPERATIONS['restart']:
            ready+='\nAETHER_ROSTER_READY_UID=10001:'+operation
            self.assertEqual(f'aetherrosterfixture 10001 {operation} 19\n',bridge.next_command(ready))
            ack+='\nHKRPG_AETHER_ROSTER_ACK:10001:'+operation+':19'
            self.assertEqual('AETHER_ROSTER_OBSERVED:'+operation+'\n',bridge.next_acknowledgement(ack))
        self.assertTrue(bridge.complete);self.assertIsNone(bridge.next_command(ready))

    def test_production_overlay_never_starts_actual_native(self):
        with self.assertRaisesRegex(ValueError,'schema-only'):
            lane.runtime_capabilities(lane.ROOT/'build/initial-query-cyrene-daily-origin-clock-runtime.jar',False,
                lane.ROOT/'build/aether-divide-roster-draft/delivery-green-v1/owned-main')

    def test_immutable_old_jar_without_explicit_preflight_overlay_is_rejected(self):
        jar=lane.ROOT/'build/initial-query-cyrene-daily-origin-clock-runtime.jar'
        with self.assertRaises(ValueError):lane.runtime_capabilities(jar,False,None)
        with self.assertRaises(ValueError):lane.runtime_capabilities(jar,True,None)

    def test_owned_bindings_are_portable_and_do_not_escape(self):
        with self.assertRaises(ValueError):lane.base.inside(HERE,'..\\unsafe')
        with self.assertRaises(ValueError):lane.base.inside(HERE,'../unsafe')
        self.assertEqual(HERE/'safe.json',lane.base.inside(HERE,'safe.json'))


    def test_adjacent_build_source_is_preferred(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);local=root/'NativeAetherDivideRosterProbe.java';local.write_text('local fixture','utf-8')
            with patch.object(lane,'HERE',root),patch.object(lane,'ROOT',root/'project'):
                self.assertEqual(local,lane.source_path(local.name,'scripts/fixtures/elsewhere.java'))

    def test_formal_java_fallback_is_selected_when_adjacent_source_absent(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with patch.object(lane,'HERE',root/'scripts'),patch.object(lane,'ROOT',root):
                expected=root/'scripts/fixtures/java/org/hkrpg/server/gameplay/AetherDivideRosterInspector.java'
                self.assertEqual(expected,lane.source_path('AetherDivideRosterInspector.java',expected.relative_to(root)))


if __name__=='__main__':unittest.main(verbosity=2)
