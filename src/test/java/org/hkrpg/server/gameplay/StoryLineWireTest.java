package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp;
import emu.lunarcore.server.packet.CmdId;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class StoryLineWireTest {
  @TempDir Path root;
  final StoryLineLifecycleTest fixture = new StoryLineLifecycleTest();
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
  StoryLineLifecycleTest.Director player;
  emu.lunarcore.data.excel.AvatarExcel secondOld;
  boolean secondConfigured;

  int skillCasts;

  int preparePendingStoryTechnique() throws Exception {
    var excel = emu.lunarcore.data.GameData.getAvatarExcelMap().get(StoryLineLifecycleTest.ACTOR);
    excel.setMazeSkill(
        new emu.lunarcore.game.battle.skills.MazeSkill(excel, 1) {
          @Override
          public void onCast(
              emu.lunarcore.game.avatar.GameAvatar caster,
              emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo motion) {
            skillCasts++;
          }
        });
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    player
        .getGameplay()
        .getStoryLines()
        .begin(
            StoryLineLifecycleTest.STORY,
            new StoryLineState.Place(player),
            new StoryLineState.Place(player));
    return player.getScene().getAvatarEntityIds().iterator().nextInt();
  }

  static class AttackBoundary extends RuntimeException {}

  void assertTrialTechniqueExemption(boolean nativePath, int points) throws Exception {
    var old = emu.lunarcore.data.GameData.getAvatarExcelMap().get(1308);
    try {
      var excel =
          fixture.gson.fromJson("{\"AvatarID\":1308}", emu.lunarcore.data.excel.AvatarExcel.class);
      excel.setMazeSkill(
          new emu.lunarcore.game.battle.skills.MazeSkill(excel, 1) {
            @Override
            public void onCast(
                emu.lunarcore.game.avatar.GameAvatar caster,
                emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo motion) {
              skillCasts++;
            }
          });
      emu.lunarcore.data.GameData.getAvatarExcelMap().add(excel);
      emu.lunarcore.data.GameData.getSpecialAvatarExcelMap()
          .add(
              fixture.gson.fromJson(
                  "{\"SpecialAvatarID\":1199931,\"AvatarID\":1308,\"Level\":80,\"Promotion\":6}",
                  emu.lunarcore.data.excel.SpecialAvatarExcel.class));
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
      player.getCurrentLineup().setMp(points);
      int caster = player.getScene().getAvatarEntityIds().iterator().nextInt();
      var session =
          new Session(player) {
            @Override
            public emu.lunarcore.server.game.GameServer getServer() {
              throw new AttackBoundary();
            }

            @Override
            public void send(emu.lunarcore.server.packet.BasePacket packet) {}
          };
      if (nativePath) {
        var req = DynamicMessage.newBuilder(bridge.clientSchema().message("SceneCastSkillCsReq"));
        req.setField(req.getDescriptorForType().findFieldByName("cast_entity_id"), caster);
        req.setField(req.getDescriptorForType().findFieldByName("attacked_by_entity_id"), caster);
        req.setField(req.getDescriptorForType().findFieldByName("skill_index"), 1);
        try {
          assertTrue(
              VersionedPacketHandler.handle(
                  session, bridge.clientCommand("SceneCastSkillCsReq"), req.build().toByteArray()));
        } catch (AttackBoundary stoppedAfterEffects) {
        }
      } else {
        var req =
            emu.lunarcore.proto.SceneCastSkillCsReqOuterClass.SceneCastSkillCsReq.newInstance()
                .setCasterId(caster)
                .setSkillIndex(1)
                .setAttackedGroupId(caster);
        new emu.lunarcore.server.packet.recv.HandlerSceneCastSkillCsReq()
            .handle(session, req.toByteArray());
      }
      assertEquals(points, player.getCurrentLineup().getMp());
      assertEquals(1, skillCasts);
    } finally {
      emu.lunarcore.data.GameData.getAvatarExcelMap().keySet().remove(1308);
      if (old != null) emu.lunarcore.data.GameData.getAvatarExcelMap().add(old);
    }
  }

  @Test
  void nativeTrialTechniqueUsesTheActorsInheritedPointExemption() throws Exception {
    assertTrialTechniqueExemption(true, 3);
  }

  @Test
  void legacyTrialTechniqueUsesTheActorsInheritedPointExemption() throws Exception {
    assertTrialTechniqueExemption(false, 3);
  }

  @Test
  void nativeTrialTechniqueExemptionAlsoAllowsTheInheritedZeroPointCast() throws Exception {
    assertTrialTechniqueExemption(true, 0);
  }

  @Test
  void pendingStoryTransitionCannotSpendTechniquePointsOrCastEffectsOnTheNativePath()
      throws Exception {
    int caster = preparePendingStoryTechnique();
    var session =
        new Session(player) {
          @Override
          public emu.lunarcore.server.game.GameServer getServer() {
            // Stop at the attack-service boundary; this test observes the preceding technique
            // effects.
            throw new AttackBoundary();
          }

          @Override
          public void send(emu.lunarcore.server.packet.BasePacket packet) {}
        };
    var req = DynamicMessage.newBuilder(bridge.clientSchema().message("SceneCastSkillCsReq"));
    req.setField(req.getDescriptorForType().findFieldByName("cast_entity_id"), caster);
    req.setField(req.getDescriptorForType().findFieldByName("attacked_by_entity_id"), caster);
    req.setField(req.getDescriptorForType().findFieldByName("skill_index"), 1);
    try {
      assertTrue(
          VersionedPacketHandler.handle(
              session, bridge.clientCommand("SceneCastSkillCsReq"), req.build().toByteArray()));
    } catch (AttackBoundary stoppedAfterEffects) {
      // No exception from fixtures or gameplay is swallowed.
    }
    assertEquals(5, player.getCurrentLineup().getMp());
    assertEquals(0, skillCasts);
    assertNotNull(session.response);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(),
        number(session.response, "retcode"));
  }

  @Test
  void pendingStoryTransitionCannotSpendTechniquePointsOrCastEffectsOnTheLegacyPath()
      throws Exception {
    int caster = preparePendingStoryTechnique();
    var packets = new ArrayList<emu.lunarcore.server.packet.BasePacket>();
    var session =
        new Session(player) {
          @Override
          public void send(emu.lunarcore.server.packet.BasePacket packet) {
            packets.add(packet);
          }
        };
    var req =
        emu.lunarcore.proto.SceneCastSkillCsReqOuterClass.SceneCastSkillCsReq.newInstance()
            .setCasterId(caster)
            .setSkillIndex(1)
            .setAttackedGroupId(caster);
    new emu.lunarcore.server.packet.recv.HandlerSceneCastSkillCsReq()
        .handle(session, req.toByteArray());
    assertEquals(5, player.getCurrentLineup().getMp());
    assertEquals(0, skillCasts);
    assertEquals(1, packets.size());
    var rsp =
        emu.lunarcore.proto.SceneCastSkillScRspOuterClass.SceneCastSkillScRsp.parseFrom(
            packets.getFirst().getData().toByteArray());
    assertEquals(emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(), rsp.getRetcode());
  }

  void configureSecondTrial(boolean initiallyJoined) throws Exception {
    int second = StoryLineLifecycleTest.SPECIAL + 1, base = StoryLineLifecycleTest.ACTOR + 2;
    secondOld = emu.lunarcore.data.GameData.getAvatarExcelMap().get(base);
    secondConfigured = true;
    emu.lunarcore.data.GameData.getAvatarExcelMap()
        .add(
            fixture.gson.fromJson(
                "{\"AvatarID\":" + base + "}", emu.lunarcore.data.excel.AvatarExcel.class));
    emu.lunarcore.data.GameData.getSpecialAvatarExcelMap()
        .add(
            fixture.gson.fromJson(
                "{\"SpecialAvatarID\":"
                    + second
                    + ",\"AvatarID\":"
                    + base
                    + ",\"Level\":50,\"Promotion\":3}",
                emu.lunarcore.data.excel.SpecialAvatarExcel.class));
    java.nio.file.Files.writeString(
        root.resolve("ExcelOutput/StroyLineTrialAvatarData.json"),
        "[{\"StoryLineID\":199931,\"TrialAvatarList\":[1199931,1199932],"
            + "\"InitTrialAvatarList\":[1199931"
            + (initiallyJoined ? ",1199932" : "")
            + "],\"CaptainAvatarID\":1199931,\"SkipJoinLineup\":false}]");
    StoryLineDefinitions.load(root);
  }

  DynamicMessage nativeParty() throws Exception {
    var packet =
        new emu.lunarcore.server.packet.send.PacketSyncLineupNotify(player.getCurrentLineup());
    var encoded =
        emu.lunarcore.server.packet.PacketCodec.encode(
            packet.getCmdId(), new byte[0], packet.getData().toByteArray());
    var notify =
        bridge
            .clientSchema()
            .parse("SyncLineupNotify", frame(bridge.outboundFrames(encoded, player)).body());
    return (DynamicMessage)
        notify.getField(notify.getDescriptorForType().findFieldByName("lineup"));
  }

  @Test
  void removingTheSecretPointBonusActorClampsSavedPointsToTheNewPartyLimit() throws Exception {
    configureSecondTrial(true);
    var old = emu.lunarcore.data.GameData.getAvatarExcelMap().get(1408);
    try {
      emu.lunarcore.data.GameData.getAvatarExcelMap()
          .add(
              fixture.gson.fromJson(
                  "{\"AvatarID\":1408}", emu.lunarcore.data.excel.AvatarExcel.class));
      emu.lunarcore.data.GameData.getSpecialAvatarExcelMap()
          .add(
              fixture.gson.fromJson(
                  "{\"SpecialAvatarID\":1199932,\"AvatarID\":1408,\"Level\":80,\"Promotion\":6}",
                  emu.lunarcore.data.excel.SpecialAvatarExcel.class));
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
      assertEquals(8, player.getCurrentLineup().getMaxMp());
      player.getCurrentLineup().setMp(8);
      assertEquals(
          0, replace(new NativeSession(player), replacement(StoryLineLifecycleTest.STORY)));
      assertEquals(5, player.getCurrentLineup().getMp());
      var restored = fixture.player(true);
      var state = Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(
          restored,
          fixture.gson.fromJson(fixture.gson.toJson(player.getGameplay()), GameplayState.class));
      assertTrue(restored.getStoryLineManager().restoreOnLogin());
      assertEquals(5, restored.getCurrentLineup().getMp());
    } finally {
      emu.lunarcore.data.GameData.getAvatarExcelMap().keySet().remove(1408);
      if (old != null) emu.lunarcore.data.GameData.getAvatarExcelMap().add(old);
    }
  }

  @Test
  void legacyStorySaveWithoutAvailabilityProvesOnlyItsSavedRoster() throws Exception {
    configureSecondTrial(true);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    assertEquals(0, replace(new NativeSession(player), replacement(StoryLineLifecycleTest.STORY)));
    var json = fixture.gson.toJsonTree(player.getGameplay()).getAsJsonObject();
    json.getAsJsonObject("storyLines")
        .getAsJsonObject("runs")
        .getAsJsonObject(Integer.toString(StoryLineLifecycleTest.STORY))
        .remove("availableTrials");
    var restored = fixture.player(true);
    var state = Player.class.getDeclaredField("gameplay");
    state.setAccessible(true);
    state.set(restored, fixture.gson.fromJson(json, GameplayState.class));
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    assertEquals(
        Set.of(StoryLineLifecycleTest.SPECIAL),
        restored.getStoryLineManager().current().getAvailableTrials());
    assertFalse(
        restored
            .getStoryLineManager()
            .lineup()
            .tryReplace(List.of(StoryLineLifecycleTest.SPECIAL + 1)));
    var savedAgain = fixture.gson.toJsonTree(restored.getGameplay()).getAsJsonObject();
    assertTrue(
        savedAgain
            .getAsJsonObject("storyLines")
            .getAsJsonObject("runs")
            .getAsJsonObject(Integer.toString(StoryLineLifecycleTest.STORY))
            .has("availableTrials"));
  }

  @Test
  void declaredButUnjoinedStoryActorCannotBeSelectedOrAdvertisedAsAvailable() throws Exception {
    configureSecondTrial(false);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    var req = replacement(StoryLineLifecycleTest.STORY);
    var slots = req.getDescriptorForType().findFieldByName("lineup_slot_list");
    var row = ((DynamicMessage) req.getRepeatedField(slots, 0)).toBuilder();
    row.setField(
        row.getDescriptorForType().findFieldByName("id"), StoryLineLifecycleTest.SPECIAL + 1);
    req.setRepeatedField(slots, 0, row.build());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID.getVal(),
        replace(new NativeSession(player), req));
    assertEquals(List.of(StoryLineLifecycleTest.SPECIAL), player.getCurrentLineup().getAvatars());
    var party = nativeParty();
    assertNull(player.getAvatarById(StoryLineLifecycleTest.SPECIAL + 1));
    assertEquals(
        List.of(StoryLineLifecycleTest.SPECIAL),
        party.getField(
            party.getDescriptorForType().findFieldByName("available_trial_avatar_i_ds")));
    assertEquals(
        List.of(StoryLineLifecycleTest.ACTOR),
        player.getLineupManager().getLineups()[0].getAvatars());
  }

  @Test
  void benchedStoryActorRemainsAvailableInNativeStatusAndAfterPersistence() throws Exception {
    configureSecondTrial(true);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    assertEquals(0, replace(new NativeSession(player), replacement(StoryLineLifecycleTest.STORY)));
    var status = StoryLine46.info(bridge.clientSchema(), player, true);
    assertEquals(
        List.of(StoryLineLifecycleTest.SPECIAL, StoryLineLifecycleTest.SPECIAL + 1),
        status.getField(status.getDescriptorForType().findFieldByNumber(11)));
    var restored = fixture.player(true);
    var saved =
        fixture.gson.fromJson(fixture.gson.toJson(player.getGameplay()), GameplayState.class);
    var state = Player.class.getDeclaredField("gameplay");
    state.setAccessible(true);
    state.set(restored, saved);
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    var req = replacement(StoryLineLifecycleTest.STORY);
    var slots = req.getDescriptorForType().findFieldByName("lineup_slot_list");
    var row = ((DynamicMessage) req.getRepeatedField(slots, 0)).toBuilder();
    row.setField(
        row.getDescriptorForType().findFieldByName("id"), StoryLineLifecycleTest.SPECIAL + 1);
    req.setRepeatedField(slots, 0, row.build());
    assertEquals(0, replace(new NativeSession(restored), req));
    assertEquals(
        List.of(StoryLineLifecycleTest.SPECIAL + 1), restored.getCurrentLineup().getAvatars());
  }

  static int number(DynamicMessage message, String field) {
    return (Integer) message.getField(message.getDescriptorForType().findFieldByName(field));
  }

  static emu.lunarcore.server.packet.PacketCodec.Frame frame(byte[] bytes) {
    var buffer = io.netty.buffer.Unpooled.wrappedBuffer(bytes);
    try {
      return emu.lunarcore.server.packet.PacketCodec.decodeNext(buffer);
    } finally {
      buffer.release();
    }
  }

  static class Session extends emu.lunarcore.server.game.GameSession {
    final Player actor;
    String responseName;
    DynamicMessage response;

    Session(Player actor) {
      super(null);
      this.actor = actor;
      setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return actor;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    public void sendClientPacket(String name, byte[] bytes) {
      responseName = name;
      try {
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (java.io.IOException invalid) {
        throw new IllegalStateException(invalid);
      }
    }
  }

  static class NativeSession extends Session {
    int command;
    byte[] payload;

    NativeSession(Player player) {
      super(player);
    }

    public void sendClientPacket(int command, byte[] payload) {
      this.command = command;
      this.payload = payload;
    }
  }

  DynamicMessage.Builder replacement(int story) {
    var req = DynamicMessage.newBuilder(bridge.clientSchema().message("ReplaceLineupCsReq"));
    req.setField(req.getDescriptorForType().findFieldByName("game_story_line_id"), story);
    var slots = req.getDescriptorForType().findFieldByName("lineup_slot_list");
    var row = DynamicMessage.newBuilder(slots.getMessageType());
    row.setField(row.getDescriptorForType().findFieldByName("id"), StoryLineLifecycleTest.SPECIAL);
    var avatarType = row.getDescriptorForType().findFieldByName("avatar_type");
    row.setField(
        avatarType, avatarType.getEnumType().findValueByName("AvatarType_AvatarTrialType"));
    req.addRepeatedField(slots, row.build());
    return req;
  }

  int replace(NativeSession session, DynamicMessage.Builder request) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 704, request.build().toByteArray()));
    assertEquals(719, session.command);
    var wire = UnknownFieldSet.parseFrom(session.payload);
    assertFalse(wire.hasField(6));
    return wire.hasField(9) ? wire.getField(9).getVarintList().getFirst().intValue() : 0;
  }

  void slotType(DynamicMessage.Builder request, String name) {
    var slots = request.getDescriptorForType().findFieldByName("lineup_slot_list");
    var row = ((DynamicMessage) request.getRepeatedField(slots, 0)).toBuilder();
    var type = row.getDescriptorForType().findFieldByName("avatar_type");
    row.setField(type, type.getEnumType().findValueByName(name));
    request.setRepeatedField(slots, 0, row.build());
  }

  @Test
  void aStoryPartyAcceptsTheTrialTypeItActuallyAdvertisesOnTheWire() throws Exception {
    configureSecondTrial(true);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    var party = nativeParty();
    var advertised =
        (DynamicMessage)
            ((List<?>) party.getField(party.getDescriptorForType().findFieldByName("avatar_list")))
                .getFirst();
    var type =
        (Descriptors.EnumValueDescriptor)
            advertised.getField(advertised.getDescriptorForType().findFieldByName("avatar_type"));
    assertEquals("AvatarType_AvatarTrialType", type.getName());
    var request = replacement(StoryLineLifecycleTest.STORY);
    slotType(request, type.getName());
    assertEquals(0, replace(new NativeSession(player), request));
    assertEquals(List.of(StoryLineLifecycleTest.SPECIAL), player.getCurrentLineup().getAvatars());
  }

  @Test
  void aLimitedAvatarTypeCannotBeUsedToEditAFrozenStoryTrialParty() throws Exception {
    configureSecondTrial(true);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    var request = replacement(StoryLineLifecycleTest.STORY);
    slotType(request, "AvatarType_AvatarLimitType");
    int saves = player.saves;
    assertNotEquals(0, replace(new NativeSession(player), request));
    assertEquals(saves, player.saves);
    assertEquals(
        List.of(StoryLineLifecycleTest.SPECIAL, StoryLineLifecycleTest.SPECIAL + 1),
        player.getCurrentLineup().getAvatars());
  }

  @Test
  void nativeStoryReplacementOrdersTwoDistinctTrialActorsAndHonoursTheRequestedLeader()
      throws Exception {
    int second = StoryLineLifecycleTest.SPECIAL + 1, base = StoryLineLifecycleTest.ACTOR + 2;
    var old = emu.lunarcore.data.GameData.getAvatarExcelMap().get(base);
    try {
      emu.lunarcore.data.GameData.getAvatarExcelMap()
          .add(
              fixture.gson.fromJson(
                  "{\"AvatarID\":" + base + "}", emu.lunarcore.data.excel.AvatarExcel.class));
      emu.lunarcore.data.GameData.getSpecialAvatarExcelMap()
          .add(
              fixture.gson.fromJson(
                  "{\"SpecialAvatarID\":"
                      + second
                      + ",\"AvatarID\":"
                      + base
                      + ",\"Level\":50,\"Promotion\":3}",
                  emu.lunarcore.data.excel.SpecialAvatarExcel.class));
      java.nio.file.Files.writeString(
          root.resolve("ExcelOutput/StroyLineTrialAvatarData.json"),
          "[{\"StoryLineID\":199931,\"TrialAvatarList\":[1199931,1199932],\"InitTrialAvatarList\":[1199931,1199932],\"CaptainAvatarID\":1199931,\"SkipJoinLineup\":false}]");
      StoryLineDefinitions.load(root);
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
      var team = player.getCurrentLineup();
      player.getAvatarById(second).setCurrentHp(team, 2100);
      player.getAvatarById(StoryLineLifecycleTest.SPECIAL).setCurrentSp(team, 3200);
      var req = replacement(StoryLineLifecycleTest.STORY);
      var field = req.getDescriptorForType().findFieldByName("lineup_slot_list");
      var first = ((DynamicMessage) req.getRepeatedField(field, 0)).toBuilder();
      first.setField(first.getDescriptorForType().findFieldByName("slot"), 1);
      req.setRepeatedField(field, 0, first.build());
      var another = first.clone();
      another.setField(another.getDescriptorForType().findFieldByName("id"), second);
      another.setField(another.getDescriptorForType().findFieldByName("slot"), 0);
      req.addRepeatedField(field, another.build());
      req.setField(req.getDescriptorForType().findFieldByName("leader_slot"), 1);
      assertEquals(0, replace(new NativeSession(player), req));
      assertEquals(List.of(second, StoryLineLifecycleTest.SPECIAL), team.getAvatars());
      assertEquals(1, team.getLeader());
      assertEquals(2100, player.getAvatarById(second).getCurrentHp(team));
      assertEquals(3200, player.getAvatarById(StoryLineLifecycleTest.SPECIAL).getCurrentSp(team));
      assertEquals(
          Set.of(second, StoryLineLifecycleTest.SPECIAL), player.getScene().getAvatars().keySet());
    } finally {
      emu.lunarcore.data.GameData.getAvatarExcelMap().keySet().remove(base);
      if (old != null) emu.lunarcore.data.GameData.getAvatarExcelMap().add(old);
    }
  }

  @Test
  void nativeStoryReplacementKeepsVitalsAndTheFormalRoster() throws Exception {
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    var lineup = player.getCurrentLineup();
    player.getAvatarById(StoryLineLifecycleTest.SPECIAL).setCurrentHp(lineup, 1234);
    lineup.setMp(2);
    assertEquals(0, replace(new NativeSession(player), replacement(StoryLineLifecycleTest.STORY)));
    assertEquals(1234, player.getAvatarById(StoryLineLifecycleTest.SPECIAL).getCurrentHp(lineup));
    assertEquals(2, lineup.getMp());
    assertEquals(
        List.of(StoryLineLifecycleTest.ACTOR),
        player.getLineupManager().getLineups()[0].getAvatars());
  }

  @Test
  void ordinaryHeliobusRequestsDoNotBecomeStoryEditsWithoutAnActiveStory() throws Exception {
    var req = replacement(0);
    var extra = req.getDescriptorForType().findFieldByName("extra_lineup_type");
    req.setField(extra, extra.getEnumType().findValueByNumber(13));
    assertFalse(StoryParty46.handle(new NativeSession(player), 704, req.build().toByteArray()));
    assertEquals(List.of(StoryLineLifecycleTest.ACTOR), player.getCurrentLineup().getAvatars());
  }

  @Test
  void staleAndUnstartedStoryReplacementCannotReachTheFormalTeam() throws Exception {
    var session = new NativeSession(player);
    assertNotEquals(0, replace(session, replacement(StoryLineLifecycleTest.STORY)));
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    assertNotEquals(0, replace(session, replacement(StoryLineLifecycleTest.STORY + 1)));
    assertEquals(List.of(StoryLineLifecycleTest.SPECIAL), player.getCurrentLineup().getAvatars());
    assertEquals(
        List.of(StoryLineLifecycleTest.ACTOR),
        player.getLineupManager().getLineups()[0].getAvatars());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void invalidStorySlotTypeLeaderAndOpaqueFlagsRejectBeforeAnyPartyMutation() throws Exception {
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    for (String fault : List.of("duplicate", "gap", "formal", "leader", "flag", "plane")) {
      var req = replacement(StoryLineLifecycleTest.STORY);
      var slots = req.getDescriptorForType().findFieldByName("lineup_slot_list");
      var row = ((DynamicMessage) req.getRepeatedField(slots, 0)).toBuilder();
      switch (fault) {
        case "duplicate" -> req.addRepeatedField(slots, row.build());
        case "gap" -> {
          row.setField(row.getDescriptorForType().findFieldByName("slot"), 1);
          req.setRepeatedField(slots, 0, row.build());
        }
        case "formal" -> {
          var field = row.getDescriptorForType().findFieldByName("avatar_type");
          row.setField(field, field.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
          req.setRepeatedField(slots, 0, row.build());
        }
        case "leader" -> req.setField(req.getDescriptorForType().findFieldByName("leader_slot"), 1);
        case "flag" ->
            req.setField(req.getDescriptorForType().findFieldByName("MMMHKFEODJK"), true);
        case "plane" ->
            req.setField(req.getDescriptorForType().findFieldByName("plane_id"), 199939);
      }
      int saves = player.saves;
      assertNotEquals(0, replace(new NativeSession(player), req), fault);
      assertEquals(saves, player.saves, fault);
      assertEquals(List.of(StoryLineLifecycleTest.SPECIAL), player.getCurrentLineup().getAvatars());
    }
  }

  @Test
  void pendingStoryTransitionAndPreAuthenticationCannotReplaceTheParty() throws Exception {
    var session = new NativeSession(player);
    session.setState(emu.lunarcore.server.packet.SessionState.WAITING_FOR_TOKEN);
    assertTrue(
        VersionedPacketHandler.handle(
            session, 704, replacement(StoryLineLifecycleTest.STORY).build().toByteArray()));
    assertNull(session.payload);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    player
        .getGameplay()
        .getStoryLines()
        .begin(
            StoryLineLifecycleTest.STORY,
            new StoryLineState.Place(player),
            new StoryLineState.Place(player));
    session.setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(),
        replace(session, replacement(StoryLineLifecycleTest.STORY)));
  }

  @BeforeEach
  void prepare() throws Exception {
    fixture.root = root;
    fixture.prepare();
    player = fixture.player(true);
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
    if (secondConfigured) {
      emu.lunarcore.data.GameData.getAvatarExcelMap()
          .keySet()
          .remove(StoryLineLifecycleTest.ACTOR + 2);
      if (secondOld != null) emu.lunarcore.data.GameData.getAvatarExcelMap().add(secondOld);
    }
  }

  @Test
  void nativeStoryQueriesReportOnlyActualStartedRecordsAndCurrentTrialActors() throws Exception {
    var schema = bridge.clientSchema();
    assertEquals(
        List.of(),
        StoryLine46.info(schema, player, false)
            .getField(
                schema
                    .message("GetStoryLineInfoScRsp")
                    .findFieldByName("unfinished_story_line_id_list")));
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    var info = StoryLine46.info(schema, player, false);
    assertEquals(StoryLineLifecycleTest.STORY, number(info, "cur_story_line_id"));
    assertEquals(
        List.of(StoryLineLifecycleTest.SPECIAL),
        info.getField(info.getDescriptorForType().findFieldByName("trial_avatar_id_list")));
    assertEquals(
        List.of(StoryLineLifecycleTest.STORY),
        info.getField(
            info.getDescriptorForType().findFieldByName("unfinished_story_line_id_list")));
    var wire = CodedInputStream.newInstance(info.toByteArray());
    boolean current = false;
    while (!wire.isAtEnd()) {
      int tag = wire.readTag();
      if (tag == (8 << 3)) {
        assertEquals(StoryLineLifecycleTest.STORY, wire.readUInt32());
        current = true;
      } else wire.skipField(tag);
    }
    assertTrue(current);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        player.getStoryLineManager().change(0, 0, 0, 0, false));
    var paused = StoryLine46.info(schema, player, false);
    assertEquals(0, number(paused, "cur_story_line_id"));
    assertEquals(
        List.of(),
        paused.getField(paused.getDescriptorForType().findFieldByName("trial_avatar_id_list")));
    assertEquals(
        List.of(StoryLineLifecycleTest.STORY),
        paused.getField(
            paused.getDescriptorForType().findFieldByName("unfinished_story_line_id_list")));
  }

  @Test
  void actualNativeStoryPartyUsesStoryIdentityInsteadOfTheHeliobusExtraTeam() throws Exception {
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    var packet =
        new emu.lunarcore.server.packet.send.PacketSyncLineupNotify(player.getCurrentLineup());
    var encoded =
        emu.lunarcore.server.packet.PacketCodec.encode(
            packet.getCmdId(), new byte[0], packet.getData().toByteArray());
    var nativeFrame = frame(bridge.outboundFrames(encoded, player));
    var notify = bridge.clientSchema().parse("SyncLineupNotify", nativeFrame.body());
    var party =
        (DynamicMessage) notify.getField(notify.getDescriptorForType().findFieldByName("lineup"));
    assertEquals(StoryLineLifecycleTest.STORY, number(party, "game_story_line_id"));
    assertEquals(
        0,
        ((Descriptors.EnumValueDescriptor)
                party.getField(party.getDescriptorForType().findFieldByName("extra_lineup_type")))
            .getNumber());
    assertEquals(
        List.of(StoryLineLifecycleTest.SPECIAL),
        party.getField(party.getDescriptorForType().findFieldByName("story_line_avatar_id_list")));
    assertEquals(
        List.of(StoryLineLifecycleTest.SPECIAL),
        party.getField(
            party.getDescriptorForType().findFieldByName("available_trial_avatar_i_ds")));
    var avatars =
        (List<?>) party.getField(party.getDescriptorForType().findFieldByName("avatar_list"));
    assertEquals(1, avatars.size());
    assertEquals(StoryLineLifecycleTest.SPECIAL, number((DynamicMessage) avatars.getFirst(), "id"));
    var wire = CodedInputStream.newInstance(party.toByteArray());
    boolean identity = false;
    while (!wire.isAtEnd()) {
      int tag = wire.readTag();
      if (tag == (9 << 3)) {
        assertEquals(StoryLineLifecycleTest.STORY, wire.readUInt32());
        identity = true;
      } else wire.skipField(tag);
    }
    assertTrue(identity);
    assertArrayEquals(encoded, ProtocolBridge.forVersion("4.2.0").outboundFrames(encoded, player));
  }

  @Test
  void activeBranchDoesNotRewriteUnrelatedFormalOrStaleTrialParty() throws Exception {
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    var formal = player.getLineupManager().getLineups()[0];
    var packet = new emu.lunarcore.server.packet.send.PacketSyncLineupNotify(formal);
    var encoded =
        emu.lunarcore.server.packet.PacketCodec.encode(
            packet.getCmdId(), new byte[0], packet.getData().toByteArray());
    var nativeFrame = frame(bridge.outboundFrames(encoded, player));
    var notify = bridge.clientSchema().parse("SyncLineupNotify", nativeFrame.body());
    var party =
        (DynamicMessage) notify.getField(notify.getDescriptorForType().findFieldByName("lineup"));
    assertEquals(0, number(party, "game_story_line_id"));
    assertEquals(
        List.of(),
        party.getField(
            party.getDescriptorForType().findFieldByName("available_trial_avatar_i_ds")));
    var stale =
        emu.lunarcore.proto.SyncLineupNotifyOuterClass.SyncLineupNotify.newInstance()
            .setLineup(player.getCurrentLineup().toProto());
    stale.getMutableLineup().getMutableAvatarList().get(0).setId(12345678);
    encoded =
        emu.lunarcore.server.packet.PacketCodec.encode(
            CmdId.SyncLineupNotify, new byte[0], stale.toByteArray());
    nativeFrame = frame(bridge.outboundFrames(encoded, player));
    notify = bridge.clientSchema().parse("SyncLineupNotify", nativeFrame.body());
    party =
        (DynamicMessage) notify.getField(notify.getDescriptorForType().findFieldByName("lineup"));
    assertEquals(0, number(party, "game_story_line_id"));
  }

  @Test
  void actualBranchSceneCarriesNativeStoryField2045AlongsideDimension1225() throws Exception {
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    var original =
        bridge
            .outbound(
                CmdId.GetCurSceneInfoScRsp,
                GetCurSceneInfoScRsp.newInstance()
                    .setScene(player.getScene().toProto())
                    .toByteArray())
            .body();
    var bytes = Scene46.enrich(bridge.clientSchema(), "GetCurSceneInfoScRsp", original, player);
    var wrapper = bridge.clientSchema().parse("GetCurSceneInfoScRsp", bytes);
    var scene =
        (DynamicMessage) wrapper.getField(wrapper.getDescriptorForType().findFieldByName("scene"));
    assertEquals(4, number(scene, "dimension_id"));
    assertEquals(StoryLineLifecycleTest.STORY, number(scene, "game_story_line_id"));
    var wire = CodedInputStream.newInstance(scene.toByteArray());
    boolean story = false;
    while (!wire.isAtEnd()) {
      int tag = wire.readTag();
      if (tag == (2045 << 3)) {
        assertEquals(StoryLineLifecycleTest.STORY, wire.readUInt32());
        story = true;
      } else wire.skipField(tag);
    }
    assertTrue(story);
  }

  @Test
  void cnNotifyUsesField3ForUnfinishedBranchesAndField11ForActualTrialsDespiteTheSourceNames() {
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    var info = StoryLine46.info(bridge.clientSchema(), player, true);
    assertEquals(
        List.of(0, StoryLineLifecycleTest.STORY),
        info.getField(info.getDescriptorForType().findFieldByNumber(3)));
    assertEquals(
        List.of(StoryLineLifecycleTest.SPECIAL),
        info.getField(info.getDescriptorForType().findFieldByNumber(11)));
    assertEquals(
        StoryLineLifecycleTest.STORY,
        info.getField(info.getDescriptorForType().findFieldByNumber(9)));
  }

  @Test
  void native6234QueryIsRegisteredAuthenticatedAndRejectsAdditionalRequestFields()
      throws Exception {
    var session = new Session(player);
    assertEquals(6234, bridge.clientCommand("GetStoryLineInfoCsReq"));
    session.setState(emu.lunarcore.server.packet.SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session, 6234, new byte[0]));
    assertNull(session.response);
    session.setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    assertTrue(VersionedPacketHandler.handle(session, 6234, new byte[0]));
    assertEquals("GetStoryLineInfoScRsp", session.responseName);
    assertEquals(StoryLineLifecycleTest.STORY, number(session.response, "cur_story_line_id"));
    assertThrows(
        ProtocolException.class,
        () -> VersionedPacketHandler.handle(session, 6234, new byte[] {8, 1}));
  }

  @Test
  void embeddedBranchStateAndFrozenActorsSurviveActualMorphiaPlayerStorage() throws Exception {
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    var lineup = player.getCurrentLineup();
    var avatar = player.getAvatarById(StoryLineLifecycleTest.SPECIAL);
    avatar.setCurrentHp(lineup, 3210);
    avatar.setCurrentSp(lineup, 6543);
    lineup.setMp(2);
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "story-line-persistence");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var plain = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(plain, 123456779);
      var state = Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(plain, player.getGameplay());
      store.save(plain);
      var reloaded = store.find(Player.class).first();
      assertNotNull(reloaded);
      var run = reloaded.getGameplay().getStoryLines().getRuns().get(StoryLineLifecycleTest.STORY);
      assertEquals(List.of(StoryLineLifecycleTest.SPECIAL), run.getRoster());
      assertEquals(Set.of(StoryLineLifecycleTest.SPECIAL), run.getAvailableTrials());
      assertEquals(3210, run.getVitals().get(StoryLineLifecycleTest.SPECIAL).getHp());
      assertEquals(6543, run.getVitals().get(StoryLineLifecycleTest.SPECIAL).getSp());
      assertEquals(2, run.getMp());
      assertEquals(4, run.getLocation().getDimension());
      assertEquals(123, reloaded.getGameplay().getStoryLines().getHome().getPos().getX());
      assertTrue(run.getTrialConfigs().get(StoryLineLifecycleTest.SPECIAL).contains("1199931"));
      var restored = fixture.player(true);
      state.set(restored, reloaded.getGameplay());
      assertTrue(restored.getStoryLineManager().restoreOnLogin());
      assertEquals(40, restored.getAvatarById(StoryLineLifecycleTest.SPECIAL).getLevel());
      assertEquals(
          3210,
          restored
              .getAvatarById(StoryLineLifecycleTest.SPECIAL)
              .getCurrentHp(restored.getCurrentLineup()));
    } finally {
      server.shutdownNow();
    }
  }
}
