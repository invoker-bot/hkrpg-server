package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Explicit isolated story declarations; these casts do not execute an official game client. */
class TrialMazeSkillLockTest {
  @TempDir Path root;
  final StoryLineWireTest fixture = new StoryLineWireTest();
  int casts;

  static class AttackBoundary extends RuntimeException {}

  static class Capture extends StoryLineWireTest.Session {
    final List<BasePacket> packets = new ArrayList<>();
    boolean attackAttempted;

    Capture(Player owner) {
      super(owner);
    }

    @Override
    public void send(BasePacket packet) {
      packets.add(packet);
    }

    @Override
    public emu.lunarcore.server.game.GameServer getServer() {
      attackAttempted = true;
      throw new AttackBoundary();
    }
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.prepare();
    var excel = GameData.getAvatarExcelMap().get(StoryLineLifecycleTest.ACTOR);
    excel.setMazeSkill(
        new emu.lunarcore.game.battle.skills.MazeSkill(excel, 1) {
          @Override
          public void onCast(
              emu.lunarcore.game.avatar.GameAvatar actor,
              emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo motion) {
            casts++;
          }
        });
    excel.setMazeAttack(new emu.lunarcore.game.battle.skills.MazeSkill(excel, 0));
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.restore();
  }

  void locked(boolean value) {
    var row =
        fixture
            .fixture
            .gson
            .toJsonTree(GameData.getSpecialAvatarExcelMap().get(StoryLineLifecycleTest.SPECIAL, 0))
            .getAsJsonObject();
    row.addProperty("LockMazeSkill", value);
    GameData.getSpecialAvatarExcelMap()
        .add(fixture.fixture.gson.fromJson(row, SpecialAvatarExcel.class));
  }

  Player started(boolean value) {
    locked(value);
    assertEquals(Retcode.SUCC, fixture.fixture.enter(fixture.player));
    return fixture.player;
  }

  Capture cast(Player player, boolean nativePath, int index) throws Exception {
    var session = new Capture(player);
    int caster = player.getScene().getAvatarEntityIds().iterator().nextInt();
    try {
      if (nativePath) {
        var req =
            DynamicMessage.newBuilder(fixture.bridge.clientSchema().message("SceneCastSkillCsReq"));
        req.setField(req.getDescriptorForType().findFieldByName("cast_entity_id"), caster);
        req.setField(req.getDescriptorForType().findFieldByName("attacked_by_entity_id"), caster);
        req.setField(req.getDescriptorForType().findFieldByName("skill_index"), index);
        assertTrue(
            VersionedPacketHandler.handle(
                session,
                fixture.bridge.clientCommand("SceneCastSkillCsReq"),
                req.build().toByteArray()));
      } else {
        var req =
            emu.lunarcore.proto.SceneCastSkillCsReqOuterClass.SceneCastSkillCsReq.newInstance()
                .setCasterId(caster)
                .setAttackedGroupId(caster)
                .setSkillIndex(index);
        new emu.lunarcore.server.packet.recv.HandlerSceneCastSkillCsReq()
            .handle(session, req.toByteArray());
      }
    } catch (AttackBoundary stoppedBeforeBattle) {
      // Only successful native dispatch reaches this test's explicit attack-service boundary.
      assertTrue(nativePath);
    }
    return session;
  }

  void rejected(Capture session, boolean nativePath) throws Exception {
    assertFalse(session.attackAttempted);
    assertEquals(0, casts);
    assertFalse(
        session.packets.stream()
            .anyMatch(p -> p.getCmdId() == CmdId.SceneCastSkillMpUpdateScNotify));
    if (nativePath) {
      assertEquals("SceneCastSkillScRsp", session.responseName);
      assertEquals(
          Retcode.REQ_PARA_INVALID.getVal(), StoryLineWireTest.number(session.response, "retcode"));
    } else {
      assertEquals(1, session.packets.size());
      assertEquals(CmdId.SceneCastSkillScRsp, session.packets.getFirst().getCmdId());
      assertEquals(
          Retcode.REQ_PARA_INVALID.getVal(),
          emu.lunarcore.proto.SceneCastSkillScRspOuterClass.SceneCastSkillScRsp.parseFrom(
                  session.packets.getFirst().getData().toByteArray())
              .getRetcode());
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void declaredTechniqueLockIsCheckedBeforePointsEffectsAndAttack(boolean nativePath, boolean lock)
      throws Exception {
    var player = started(lock);
    var session = cast(player, nativePath, 1);
    if (lock) {
      rejected(session, nativePath);
      assertEquals(5, player.getCurrentLineup().getMp());
    } else {
      assertEquals(1, casts);
      assertEquals(4, player.getCurrentLineup().getMp());
      assertEquals(nativePath, session.attackAttempted);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void techniqueLockDoesNotBlockOrdinaryMazeAttack(boolean nativePath) throws Exception {
    var player = started(true);
    var session = cast(player, nativePath, 0);
    assertEquals(0, casts);
    assertEquals(5, player.getCurrentLineup().getMp());
    assertEquals(nativePath, session.attackAttempted);
    if (!nativePath)
      assertEquals(
          0,
          emu.lunarcore.proto.SceneCastSkillScRspOuterClass.SceneCastSkillScRsp.parseFrom(
                  session.packets.getLast().getData().toByteArray())
              .getRetcode());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void restoredStoryActorKeepsItsFrozenTechniqueLockAfterTheResourceChanges(boolean nativePath)
      throws Exception {
    var original = started(true);
    var saved =
        fixture.fixture.gson.fromJson(
            fixture.fixture.gson.toJson(original.getGameplay()), GameplayState.class);
    locked(false);
    var restored = fixture.fixture.player(true);
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(restored, saved);
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    rejected(cast(restored, nativePath, 1), nativePath);
    assertEquals(5, restored.getCurrentLineup().getMp());
  }
}
