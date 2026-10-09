package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.*;
import org.junit.jupiter.api.Test;

class Battle46Test {
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  @Test
  void stageEntryUsesTheEventIdAndResultKeepsCurrentBattleIdentity() throws Exception {
    var type = bridge.clientSchema().message("SceneEnterStageCsReq");
    var wire =
        DynamicMessage.newBuilder(type).setField(type.findFieldByName("event_id"), 100101).build();
    var converted =
        bridge.inbound(bridge.clientCommand("SceneEnterStageCsReq"), wire.toByteArray());
    assertEquals(
        100101,
        SceneEnterStageCsReqOuterClass.SceneEnterStageCsReq.parseFrom(converted.body())
            .getStageId());
    var result = bridge.clientSchema().message("PVEBattleResultCsReq");
    var finish =
        DynamicMessage.newBuilder(result)
            .setField(result.findFieldByName("battle_id"), 73)
            .setField(result.findFieldByName("stage_id"), 100101)
            .build();
    var parsed =
        PVEBattleResultCsReqOuterClass.PVEBattleResultCsReq.parseFrom(
            bridge.inbound(134, finish.toByteArray()).body());
    assertEquals(73, parsed.getBattleId());
  }

  @Test
  void battleWaveAndBuffOwnershipArePreserved() throws Exception {
    var core =
        SceneBattleInfoOuterClass.SceneBattleInfo.newInstance().setBattleId(73).setStageId(100101);
    core.addMonsterWaveList(
        SceneMonsterWaveOuterClass.SceneMonsterWave.newInstance()
            .setStageId(100101)
            .setWaveId(2)
            .setWaveParam(
                SceneMonsterWaveParamOuterClass.SceneMonsterWaveParam.newInstance().setLevel(40)));
    core.addBuffList(BattleBuffOuterClass.BattleBuff.newInstance().setId(1000119).setOwnerId(-1));
    core.addBattleAvatarList(
        BattleAvatarOuterClass.BattleAvatar.newInstance()
            .setId(8005)
            .setAvatarEnhanceId(1)
            .setAvatarType(AvatarTypeOuterClass.AvatarType.AVATAR_FORMAL_TYPE));
    var result =
        bridge
            .clientSchema()
            .parse(
                "SceneBattleInfo", bridge.translate("SceneBattleInfo", core.toByteArray(), true));
    var wave = InitialData46Test.first(result, "monster_wave_list");
    assertEquals(2, InitialData46Test.field(wave, "battle_wave_id"));
    assertEquals(100101, InitialData46Test.field(wave, "battle_stage_id"));
    assertEquals(
        -1, InitialData46Test.field(InitialData46Test.first(result, "buff_list"), "owner_index"));
    assertEquals(
        1,
        InitialData46Test.field(
            InitialData46Test.first(result, "battle_avatar_list"), "enhanced_id"));
  }
}
