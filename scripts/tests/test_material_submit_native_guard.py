"""固定来源与隔离控制屏障的离线守卫；不启动游戏服、不操作运营目录。"""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest

HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'src/main/resources/protocol/4.6').is_dir())
sys.path.insert(0,str(ROOT/'scripts'))
WRAPPER=HERE/'smoke-material-submit-activity.py'
if not WRAPPER.is_file():WRAPPER=ROOT/'scripts/smoke-material-submit-activity.py'
spec=importlib.util.spec_from_file_location('material_guard_target',WRAPPER)
target=importlib.util.module_from_spec(spec);spec.loader.exec_module(target)


class MaterialNativeGuard(unittest.TestCase):
    def setUp(self):
        self.proof=json.loads(target.PROOF.read_bytes())

    def test_full_original_fixture_and_exact_source_rewards(self):
        self.assertEqual(target.base.sha(target.PROOF),target.PROOF_SHA)
        self.assertIs(target.validate_proof(self.proof),self.proof)
        totals={}
        for rewards in target.original_rewards().values():
            for item,n in rewards.items():totals[item]=totals.get(item,0)+n
        self.assertEqual({k:totals[k] for k in [1,2,213]},{1:500,2:280000,213:21})
        self.assertEqual(set(target.original_rewards()),set(range(301,308)))

    def test_source_commit_drift_rejected(self):
        self.proof['sourceCommit']='0'*40
        with self.assertRaises(ValueError):target.validate_proof(self.proof)

    def test_client_acceptance_cannot_be_invented(self):
        self.proof['clientAccepted']=True
        with self.assertRaises(ValueError):target.validate_proof(self.proof)

    def test_missing_complete_table_rejected(self):
        del self.proof['files']['ExcelOutput/RewardData.json']
        with self.assertRaises(ValueError):target.validate_proof(self.proof)

    def test_changed_complete_table_sha_rejected(self):
        self.proof['files']['ExcelOutput/RewardData.json']['sha256']='0'*64
        with self.assertRaises(ValueError):target.validate_proof(self.proof)

    def test_changed_whole_table_count_rejected(self):
        self.proof['files']['ExcelOutput/MaterialSubmitter.json']['completeRowCount']=7
        with self.assertRaises(ValueError):target.validate_proof(self.proof)

    def test_shortened_original_byte_length_rejected(self):
        self.proof['files']['ExcelOutput/ActivityConfig.json']['bytes']-=1
        with self.assertRaises(ValueError):target.validate_proof(self.proof)

    def test_schema_overlay_never_allowed_for_actual_runtime(self):
        with self.assertRaisesRegex(ValueError,'schema-only'):
            target.runtime_capabilities(ROOT/'build/castorice-daily-chess-aether-runtime.jar',False,ROOT/'build/material-submit-activity-next-source/owned-green-v4/production')

    def test_old_immutable_runtime_rejected_before_native_start(self):
        with self.assertRaisesRegex(ValueError,'newly frozen'):
            target.runtime_capabilities(ROOT/'build/castorice-daily-chess-aether-runtime.jar',False,None)

    def test_normal_console_order_and_exact_nonce(self):
        bridge=target.ConsoleBridge('initial',1791763000)
        self.assertEqual(bridge.next_command('MATERIAL_SUBMIT_READY_UID=10001:before-empty'),'materialsubmitfixture 10001 before-empty 1791763000\n')
        self.assertIsNone(bridge.next_acknowledgement('HKRPG_MATERIAL_SUBMIT_ACK:10001:before-empty:1'))
        self.assertEqual(bridge.next_acknowledgement('HKRPG_MATERIAL_SUBMIT_ACK:10001:before-empty:1791763000'),'MATERIAL_SUBMIT_OBSERVED:before-empty\n')
        self.assertFalse(bridge.complete)

    def test_wrong_uid_rejected(self):
        with self.assertRaises(ValueError):target.ConsoleBridge('initial',100).next_command('MATERIAL_SUBMIT_READY_UID=10002:before-empty')

    def test_out_of_order_observation_rejected(self):
        with self.assertRaises(ValueError):target.ConsoleBridge('initial',100).next_command('MATERIAL_SUBMIT_READY_UID=10001:after-empty')

    def test_overlapping_console_operation_rejected(self):
        bridge=target.ConsoleBridge('initial',100);bridge.next_command('MATERIAL_SUBMIT_READY_UID=10001:before-empty')
        with self.assertRaises(ValueError):bridge.next_command('MATERIAL_SUBMIT_READY_UID=10001:after-empty')

    def test_duplicate_ready_does_not_repeat_fixture_command(self):
        bridge=target.ConsoleBridge('initial',100);message='MATERIAL_SUBMIT_READY_UID=10001:before-empty'
        self.assertIsNotNone(bridge.next_command(message));self.assertIsNone(bridge.next_command(message))

    def test_wrong_acknowledgement_operation_rejected(self):
        bridge=target.ConsoleBridge('initial',100);bridge.next_command('MATERIAL_SUBMIT_READY_UID=10001:before-empty')
        with self.assertRaises(ValueError):bridge.next_acknowledgement('HKRPG_MATERIAL_SUBMIT_ACK:10001:after-empty:100')

    def test_restart_preserves_all_initial_operation_receipts(self):
        all_operations=target.OPERATIONS['initial']+target.OPERATIONS['restart']
        self.assertEqual(len(all_operations),len(set(all_operations)))
        self.assertEqual(target.OPERATIONS['restart'][0],'windows')
        self.assertEqual(target.OPERATIONS['restart'][-1],'after-complete-query')


if __name__=='__main__':unittest.main()
