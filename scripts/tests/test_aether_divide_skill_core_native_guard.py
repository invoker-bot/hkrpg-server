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
WRAPPER=HERE/'smoke-aether-divide-skill-core.py'
if not WRAPPER.is_file():WRAPPER=ROOT/'scripts/smoke-aether-divide-skill-core.py'
spec=importlib.util.spec_from_file_location('aether_roster_owned_guard',WRAPPER)
lane=importlib.util.module_from_spec(spec);spec.loader.exec_module(lane)


class AetherNativeGuardTest(unittest.TestCase):
    def proof(self):return json.loads(lane.PROOF.read_bytes())

    def checkpoint(self):
        before=dict(livePlayer=dict(uid=10001,lastActiveTime=1791763201,gameplay=dict(aetherDivide=dict(owner=6001))),
            storedPlayerDocument=dict(_id=10001,lastActiveTime=1791763184,gameplay=dict(aetherDivide=dict(owner=6001))),
            actualMaterials={'250202':4},credits=0,ordinaryTeams=[8001])
        after=copy.deepcopy(before);after['storedPlayerDocument']['lastActiveTime']=before['livePlayer']['lastActiveTime']
        return before,after

    def test_known_pending_login_watermark_has_complete_separate_attribution(self):
        before,after=self.checkpoint()
        self.assertNotEqual(before,after)
        self.assertEqual({'lastActiveTime':{'before':1791763184,'after':1791763201}},lane.validate_login_checkpoint(before,after))

    def test_unknown_pending_player_field_cannot_be_ignored(self):
        before,after=self.checkpoint();after['storedPlayerDocument']['credits']=1
        with self.assertRaises(ValueError):lane.validate_login_checkpoint(before,after)

    def test_checkpoint_cannot_change_core_or_other_gameplay(self):
        before,after=self.checkpoint();after['storedPlayerDocument']['gameplay']['aetherDivide']['owner']=6005
        with self.assertRaises(ValueError):lane.validate_login_checkpoint(before,after)

    def test_checkpoint_cannot_change_actual_items_or_team(self):
        for key,value in [('actualMaterials',{'250202':3}),('ordinaryTeams',[8002])]:
            with self.subTest(key=key):
                before,after=self.checkpoint();after[key]=value
                with self.assertRaises(ValueError):lane.validate_login_checkpoint(before,after)

    def test_checkpoint_cannot_hide_live_timestamp_or_player_mutation(self):
        before,after=self.checkpoint();after['livePlayer']['lastActiveTime']+=1
        with self.assertRaises(ValueError):lane.validate_login_checkpoint(before,after)

    def test_complete_original_fixed_fixture_is_accepted(self):
        value=self.proof();self.assertIs(lane.validate_proof(value),value)

    def test_partial_table_is_rejected(self):
        value=self.proof();value['files']['ExcelOutput/AetherDivideSpirit.json']['completeRows'].pop()
        with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_changed_whole_native_bytes_are_rejected(self):
        value=self.proof();value['wholeNativeFunctions'][0]['rawBase64']='AA=='
        with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_complete_lua_cannot_be_replaced_by_partial_bytes(self):
        value=self.proof();value['wholeLua'][0]['rawBase64']='AA=='
        with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_lua_partial_consumer_flag_is_rejected(self):
        value=self.proof();value['wholeLua'][0]['fullyConsumed']=False
        with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_duplicate_native_function_does_not_count_as_full_source(self):
        value=self.proof();value['wholeNativeFunctions'][1]['index']=value['wholeNativeFunctions'][0]['index']
        with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_zero_based_or_uniform_promotion_policy_is_rejected(self):
        for key,item in [('indexBase',0),('uniformPromotionRule',True),('optionalMissingPassiveTypeDefault','Assist')]:
            with self.subTest(key=key):
                value=self.proof();value['sourceSlotPolicy'][key]=item
                with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_misnamed_or_repriced_command_mapping_is_rejected(self):
        for key,item in [('EquipSkillCoreScRsp',4824),('EquipAetherDividePassiveSkillCsReq',4846)]:
            with self.subTest(key=key):
                value=self.proof();value['commands'][key]=item
                with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_wrong_native_enum_default_is_rejected(self):
        value=self.proof();value['skillTypeEnum']['Defend']=4
        with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_fragment_ownership_and_ui_claims_are_rejected(self):
        for key in ['occupiedReplacementImplemented','crossSpiritTransferImplemented','clientGameplayVerified','inventorySpent']:
            with self.subTest(key=key):
                value=self.proof();value['scope'][key]=True
                with self.assertRaises(ValueError):lane.validate_proof(value)

    def test_unknown_or_nonpositive_console_nonce_is_rejected(self):
        for mode,nonce in [('unknown',1),('initial',0),('restart',-1)]:
            with self.assertRaises(ValueError):lane.ConsoleBridge(mode,nonce)

    def test_only_owned_uid_can_receive_fixture_command(self):
        with self.assertRaises(ValueError):lane.ConsoleBridge('initial',9).next_command('AETHER_CORE_READY_UID=20001:before-empty')

    def test_future_operation_is_rejected(self):
        with self.assertRaises(ValueError):lane.ConsoleBridge('initial',9).next_command('AETHER_CORE_READY_UID=10001:before-equip')

    def test_pending_console_barrier_cannot_be_skipped(self):
        bridge=lane.ConsoleBridge('initial',9);self.assertIn('before-empty',bridge.next_command('AETHER_CORE_READY_UID=10001:before-empty'))
        with self.assertRaises(ValueError):bridge.next_command('AETHER_CORE_READY_UID=10001:after-empty')

    def test_foreign_nonce_is_not_authorization(self):
        bridge=lane.ConsoleBridge('initial',9);bridge.next_command('AETHER_CORE_READY_UID=10001:before-empty')
        self.assertIsNone(bridge.next_acknowledgement('HKRPG_AETHER_CORE_ACK:10001:before-empty:8'));self.assertFalse(bridge.complete)

    def test_actual_pending_fixture_failure_is_not_a_success_ack(self):
        bridge=lane.ConsoleBridge('initial',9);bridge.next_command('AETHER_CORE_READY_UID=10001:before-empty')
        self.assertEqual('before-empty',bridge.next_failure('HKRPG_AETHER_CORE_FAILURE:10001:before-empty:9'))
        self.assertFalse(bridge.complete);self.assertEqual([],bridge.acknowledged)

    def test_foreign_failure_nonce_is_ignored(self):
        bridge=lane.ConsoleBridge('initial',9);bridge.next_command('AETHER_CORE_READY_UID=10001:before-empty')
        self.assertIsNone(bridge.next_failure('HKRPG_AETHER_CORE_FAILURE:10001:before-empty:8'))

    def test_unowned_or_wrong_order_failure_is_rejected(self):
        for marker in ['HKRPG_AETHER_CORE_FAILURE:20001:before-empty:9','HKRPG_AETHER_CORE_FAILURE:10001:after-empty:9']:
            with self.subTest(marker=marker):
                bridge=lane.ConsoleBridge('initial',9);bridge.next_command('AETHER_CORE_READY_UID=10001:before-empty')
                with self.assertRaises(ValueError):bridge.next_failure(marker)

    def test_exact_restart_sequence_completes_without_seed(self):
        bridge=lane.ConsoleBridge('restart',19);ready='';ack=''
        for operation in lane.OPERATIONS['restart']:
            ready+='\nAETHER_CORE_READY_UID=10001:'+operation
            self.assertEqual(f'aethercorefixture 10001 {operation} 19\n',bridge.next_command(ready))
            ack+='\nHKRPG_AETHER_CORE_ACK:10001:'+operation+':19'
            self.assertEqual('AETHER_CORE_OBSERVED:'+operation+'\n',bridge.next_acknowledgement(ack))
        self.assertTrue(bridge.complete);self.assertIsNone(bridge.next_command(ready))

    def test_production_overlay_never_starts_actual_native(self):
        with self.assertRaisesRegex(ValueError,'schema-only'):
            lane.runtime_capabilities(lane.ROOT/'build/castorice-daily-chess-aether-runtime.jar',False,
                lane.ROOT/'build/aether-divide-core-draft/delivery-green-v1/owned-main')

    def test_immutable_old_jar_without_explicit_preflight_overlay_is_rejected(self):
        jar=lane.ROOT/'build/castorice-daily-chess-aether-runtime.jar'
        with self.assertRaises(ValueError):lane.runtime_capabilities(jar,False,None)
        with self.assertRaises(ValueError):lane.runtime_capabilities(jar,True,None)

    def test_owned_bindings_are_portable_and_do_not_escape(self):
        with self.assertRaises(ValueError):lane.base.inside(HERE,'..\\unsafe')
        with self.assertRaises(ValueError):lane.base.inside(HERE,'../unsafe')
        self.assertEqual(HERE/'safe.json',lane.base.inside(HERE,'safe.json'))


    def test_adjacent_build_source_is_preferred(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);local=root/'NativeAetherDivideSkillCoreProbe.java';local.write_text('local fixture','utf-8')
            with patch.object(lane,'HERE',root),patch.object(lane,'ROOT',root/'project'):
                self.assertEqual(local,lane.source_path(local.name,'scripts/fixtures/elsewhere.java'))

    def test_formal_java_fallback_is_selected_when_adjacent_source_absent(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with patch.object(lane,'HERE',root/'scripts'),patch.object(lane,'ROOT',root):
                expected=root/'scripts/fixtures/java/org/hkrpg/server/gameplay/AetherDivideSkillCoreInspector.java'
                self.assertEqual(expected,lane.source_path('AetherDivideSkillCoreInspector.java',expected.relative_to(root)))


if __name__=='__main__':unittest.main(verbosity=2)
