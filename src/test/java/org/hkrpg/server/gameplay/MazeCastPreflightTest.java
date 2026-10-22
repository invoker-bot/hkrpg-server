package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.recv.HandlerSceneCastSkillCsReq;
import java.nio.file.Path;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Native 4.6 checks already exist; inherited handler must reject before consuming/executing too.
 */
class MazeCastPreflightTest {
  @TempDir Path root;
  final TrialMazeSkillLockTest fixture = new TrialMazeSkillLockTest();

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
  }

  TrialMazeSkillLockTest.Capture cast(Player player, boolean nativePath, int index, int caster)
      throws Exception {
    var capture = new TrialMazeSkillLockTest.Capture(player);
    if (nativePath) {
      var req =
          DynamicMessage.newBuilder(
              fixture.fixture.bridge.clientSchema().message("SceneCastSkillCsReq"));
      for (String name : java.util.List.of("cast_entity_id", "attacked_by_entity_id"))
        req.setField(req.getDescriptorForType().findFieldByName(name), caster);
      req.setField(req.getDescriptorForType().findFieldByName("skill_index"), index);
      try {
        assertTrue(
            VersionedPacketHandler.handle(
                capture,
                fixture.fixture.bridge.clientCommand("SceneCastSkillCsReq"),
                req.build().toByteArray()));
      } catch (TrialMazeSkillLockTest.AttackBoundary expected) {
        assertTrue(capture.attackAttempted);
      }
    } else {
      var req =
          emu.lunarcore.proto.SceneCastSkillCsReqOuterClass.SceneCastSkillCsReq.newInstance()
              .setCasterId(caster)
              .setAttackedGroupId(caster)
              .setSkillIndex(index);
      assertDoesNotThrow(
          () -> new HandlerSceneCastSkillCsReq().handle(capture, req.toByteArray()),
          "Invalid cast must return a controlled failure");
    }
    return capture;
  }

  void rejected(TrialMazeSkillLockTest.Capture capture, boolean nativePath, Retcode expected)
      throws Exception {
    assertFalse(capture.attackAttempted, "Rejected cast reached the attack service");
    assertEquals(0, fixture.casts, "Rejected cast executed an action");
    assertFalse(
        capture.packets.stream()
            .anyMatch(p -> p.getCmdId() == CmdId.SceneCastSkillMpUpdateScNotify),
        "Rejected cast notified a consumed point");
    if (nativePath)
      assertEquals(expected.getVal(), StoryLineWireTest.number(capture.response, "retcode"));
    else {
      assertEquals(1, capture.packets.size());
      var response =
          emu.lunarcore.proto.SceneCastSkillScRspOuterClass.SceneCastSkillScRsp.parseFrom(
              capture.packets.getFirst().getData().toByteArray());
      assertEquals(expected.getVal(), response.getRetcode());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "false,no-points",
    "true,no-points",
    "false,index-negative",
    "true,index-negative",
    "false,index-two",
    "true,index-two",
    "false,missing-skill",
    "true,missing-skill",
    "false,missing-scene",
    "true,missing-scene",
    "false,unknown-caster",
    "true,unknown-caster",
    "false,active-battle",
    "true,active-battle"
  })
  void invalidTechniqueCannotConsumePointsOrExecuteActions(boolean nativePath, String scenario)
      throws Exception {
    var player = fixture.started(false);
    int caster = player.getCurrentLeaderAvatar().getEntityId();
    int index = 1;
    switch (scenario) {
      case "no-points" -> player.getCurrentLineup().setMp(0);
      case "index-negative" -> index = -1;
      case "index-two" -> index = 2;
      case "missing-skill" -> player.getCurrentLeaderAvatar().getExcel().setMazeSkill(null);
      case "missing-scene" -> {
        var field = Player.class.getDeclaredField("scene");
        field.setAccessible(true);
        field.set(player, null);
      }
      case "unknown-caster" -> caster = Integer.MAX_VALUE;
      case "active-battle" ->
          player.setBattle(
              new emu.lunarcore.game.battle.Battle(
                  player,
                  player.getCurrentLineup(),
                  new emu.lunarcore.data.excel.StageExcel(),
                  false));
    }
    var capture = cast(player, nativePath, index, caster);
    rejected(
        capture,
        nativePath,
        scenario.equals("no-points") ? Retcode.MAZE_MP_NOT_ENOUGH
            : nativePath && scenario.equals("active-battle") ? Retcode.SYSTEM_BUSY
            : Retcode.REQ_PARA_INVALID);
    assertEquals(scenario.equals("no-points") ? 0 : 5, player.getCurrentLineup().getMp());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ordinaryAttackStillWorksAtZeroTechniquePoints(boolean nativePath) throws Exception {
    var player = fixture.started(false);
    player.getCurrentLineup().setMp(0);
    var capture = cast(player, nativePath, 0, player.getCurrentLeaderAvatar().getEntityId());
    assertEquals(0, fixture.casts);
    assertEquals(0, player.getCurrentLineup().getMp());
    assertEquals(nativePath, capture.attackAttempted);
    if (!nativePath)
      assertEquals(
          0,
          emu.lunarcore.proto.SceneCastSkillScRspOuterClass.SceneCastSkillScRsp.parseFrom(
                  capture.packets.getFirst().getData().toByteArray())
              .getRetcode());
  }
}
