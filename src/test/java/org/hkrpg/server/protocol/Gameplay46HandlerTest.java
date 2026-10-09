package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import org.junit.jupiter.api.Test;

class Gameplay46HandlerTest {
  @Test
  void storyGroupNotificationCarriesRealOldAndNewValuesInPinned46Fields() {
    var session = new Session();
    var player =
        new Player() {
          public boolean isOnline() {
            return true;
          }

          public GameSession getSession() {
            return session;
          }
        };
    Story46.syncGroup(player, 19995001, 9, "Door", -1, 2);
    assertEquals("SceneGroupRefreshScNotify", session.responseName);
    assertEquals(19995001, InitialData46Test.field(session.response, "floor_id"));
    var group = InitialData46Test.first(session.response, "group_refresh_list");
    assertEquals(9, InitialData46Test.field(group, "group_id"));
    var property = InitialData46Test.first(group, "CDMKBCHLPAB");
    assertEquals("Door", InitialData46Test.field(property, "property_name"));
    assertEquals(-1, InitialData46Test.field(property, "old_value"));
    assertEquals(2, InitialData46Test.field(property, "new_value"));
    Story46.syncState(player, 19995001, 9, 3);
    assertEquals("SceneGroupRefreshScNotify", session.responseName);
    assertEquals(19995001, InitialData46Test.field(session.response, "floor_id"));
    group = InitialData46Test.first(session.response, "group_refresh_list");
    assertEquals(9, InitialData46Test.field(group, "group_id"));
    assertEquals(3, InitialData46Test.field(group, "state"));
  }

  static class Session extends GameSession {
    // A real session retains its bridge; rebuilding the reverse command maps for every request
    // obscures full-suite runtime without exercising different wire behavior.
    private final ProtocolBridge fixtureProtocol46 = ProtocolBridge.forVersion("4.6.0");
    final Player player =
        new Player() {
          public void save() {}
        };
    DynamicMessage response;
    String responseName;

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return fixtureProtocol46;
    }

    public void send(BasePacket p) {}

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        response = getProtocol().clientSchema().parse(name, bytes);
        responseName = name;
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    void request(String name) throws Exception {
      assertTrue(
          VersionedPacketHandler.handle(this, getProtocol().clientCommand(name), new byte[0]));
    }
  }

  @Test
  void missionSnapshotReportsStoredStateAndNeverCompletesUnknownIds() throws Exception {
    var s = new Session();
    var main = new QuestState(19997001);
    s.player.getGameplay().getMainMissions().put(19997001, main);
    var sub = new QuestState(199970011);
    sub.advance(2, 3, 1);
    s.player.getGameplay().getSubMissions().put(199970011, sub);
    s.request("GetMissionDataCsReq");
    assertEquals("GetMissionDataScRsp", s.responseName);
    assertEquals(
        0,
        s.response.getRepeatedFieldCount(
            s.response.getDescriptorForType().findFieldByName("finished_main_mission_id_list")));
    var row = InitialData46Test.first(s.response, "mission_list");
    assertEquals(2, InitialData46Test.field(row, "progress"));
    assertEquals("MissionStatus_MissionDoing", InitialData46Test.field(row, "status").toString());
  }

  @Test
  void unsupportedUniverseStartCannotCreateRunOrReturnSuccess() throws Exception {
    var s = new Session();
    s.request("StartRogueCsReq");
    assertEquals(2913, InitialData46Test.field(s.response, "retcode"));
    assertEquals(
        org.hkrpg.server.gameplay.GameplayState.RogueRun.Phase.IDLE,
        s.player.getGameplay().getRogue().getPhase());
  }

  @Test
  void emptyActivityScheduleUsesNative46Type() throws Exception {
    var s = new Session();
    s.request("GetActivityScheduleConfigCsReq");
    assertEquals("GetActivityScheduleConfigScRsp", s.responseName);
    assertEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(java.util.List.of(), InitialData46Test.field(s.response, "schedule_data"));
  }

  @Test
  void unauthenticatedTalkCannotAdvanceMission() throws Exception {
    var s = new Session();
    s.setState(SessionState.WAITING_FOR_TOKEN);
    s.request("FinishTalkMissionCsReq");
    assertNull(s.response);
    assertTrue(s.player.getGameplay().getSubMissions().isEmpty());
  }

  @Test
  void invalidSceneSkillDoesNotSpendPointsOrStartBattle() throws Exception {
    var s = new Session();
    var type = s.getProtocol().clientSchema().message("SceneCastSkillCsReq");
    var req =
        DynamicMessage.newBuilder(type)
            .setField(type.findFieldByName("skill_index"), 2)
            .setField(type.findFieldByName("cast_entity_id"), 123)
            .build();
    assertTrue(
        VersionedPacketHandler.handle(
            s, s.getProtocol().clientCommand("SceneCastSkillCsReq"), req.toByteArray()));
    assertEquals(5, InitialData46Test.field(s.response, "retcode"));
    assertEquals(123, InitialData46Test.field(s.response, "cast_entity_id"));
    assertNull(s.player.getBattle());
  }

  @Test
  void musicFinishRequiresAStartedLevelAndCannotInventCompletionFromAQuery() throws Exception {
    var s = new Session();
    s.request("MusicRhythmDataCsReq");
    assertEquals("MusicRhythmDataScRsp", s.responseName);
    assertEquals(java.util.List.of(), InitialData46Test.field(s.response, "level_data_list"));
    s.request("MusicRhythmFinishLevelCsReq");
    assertEquals(Retcode.MUSIC_LOCKED.getVal(), InitialData46Test.field(s.response, "retcode"));
  }

  @Test
  void storyChoiceMustBelongToTheActiveTalkAndDeclaredResourceValues() throws Exception {
    int mainId = 19996900, subId = 199969001;
    var gson = new com.google.gson.Gson();
    var main =
        gson.fromJson(
            "{\"MainMissionID\":19996900,\"TakeParam\":[{\"Type\":\"Manual\"}],\"BeginParam\":[{\"Type\":\"Auto\"}]}",
            emu.lunarcore.data.excel.MainMissionExcel.class);
    var info =
        gson.fromJson(
            "{\"MainMissionID\":19996900,\"FinishSubMissionList\":[199969001],\"MissionCustomValueList\":[{\"Name\":\"PlayerChoice\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2]}],\"SubMissionList\":[{\"ID\":199969001,\"MainMissionID\":19996900,\"TakeType\":\"Auto\",\"FinishType\":\"Talk\",\"ParamStr1\":\"choice-talk\",\"CustomValueList\":[\"PlayerChoice\"]}]}",
            emu.lunarcore.data.config.MissionInfo.class);
    emu.lunarcore.data.GameData.getMainMissionExcelMap().add(main);
    emu.lunarcore.data.GameData.getMissionInfos().put(mainId, info);
    org.hkrpg.server.gameplay.GameplayDefinitions.subs.put(subId, info.getSubMissionList()[0]);
    try {
      var s = new Session();
      s.player.getMissionManager().accept(mainId);
      var type = s.getProtocol().clientSchema().message("FinishTalkMissionCsReq");
      var custom = Gameplay46.builder(s, "MissionCustomValue");
      Gameplay46.set(custom, "index", 0);
      Gameplay46.set(custom, "custom_value", 9);
      var req = DynamicMessage.newBuilder(type);
      Gameplay46.set(req, "sub_mission_id", subId);
      Gameplay46.set(req, "talk_str", "choice-talk");
      Gameplay46.add(req, "custom_value_list", custom.build());
      VersionedPacketHandler.handle(
          s, s.getProtocol().clientCommand("FinishTalkMissionCsReq"), req.build().toByteArray());
      assertEquals(
          Retcode.MISSION_CUSTOM_VALUE_NOT_VALID.getVal(),
          InitialData46Test.field(s.response, "retcode"));
      assertFalse(s.player.getMissionManager().finished(mainId));
      Gameplay46.set(custom, "custom_value", 2);
      req.clearField(type.findFieldByName("custom_value_list"));
      Gameplay46.add(req, "custom_value_list", custom.build());
      VersionedPacketHandler.handle(
          s, s.getProtocol().clientCommand("FinishTalkMissionCsReq"), req.build().toByteArray());
      assertEquals(0, InitialData46Test.field(s.response, "retcode"));
      assertEquals(
          2, s.player.getGameplay().getStoryValues().get("mission:" + mainId + ":PlayerChoice"));
    } finally {
      org.hkrpg.server.gameplay.GameplayDefinitions.subs.remove(subId);
    }
  }

  @Test
  void activeLoginActivityNativeScheduleAndRewardUseStoredDays() throws Exception {
    var defs = org.hkrpg.server.gameplay.GameplayDefinitions.class;
    var id = 19997009;
    var now = java.time.Instant.parse("2026-10-01T00:00:00Z");
    var s = new Session();
    var panel =
        new com.google.gson.Gson()
            .fromJson("{\"PanelID\":19997009}", emu.lunarcore.data.excel.ActivityPanelExcel.class);
    emu.lunarcore.data.GameData.getActivityPanelExcelMap().add(panel);
    var window =
        new org.hkrpg.server.gameplay.GameplayDefinitions.Window(
            id, id, now.minusSeconds(1).getEpochSecond(), now.plusSeconds(86400).getEpochSecond());
    org.hkrpg.server.gameplay.GameplayDefinitions.activities.put(
        id,
        new org.hkrpg.server.gameplay.GameplayDefinitions.LoginActivity(
            id, java.util.List.of(0), id));
    org.hkrpg.server.gameplay.GameplayDefinitions.windows.add(window);
    try {
      s.player
          .getLoginActivityManager()
          .setClock(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
      s.request("GetLoginActivityCsReq");
      assertEquals(0, s.player.getLoginActivityManager().days(id));
      s.player.getLoginActivityManager().recordLogin();
      s.request("GetActivityScheduleConfigCsReq");
      var row = InitialData46Test.first(s.response, "schedule_data");
      assertEquals(id, InitialData46Test.field(row, "panel_id"));
      assertEquals(
          now.minusSeconds(1).getEpochSecond(), InitialData46Test.field(row, "begin_time"));
      var type = s.getProtocol().clientSchema().message("TakeLoginActivityRewardCsReq");
      var req =
          DynamicMessage.newBuilder(type)
              .setField(type.findFieldByName("id"), id)
              .setField(type.findFieldByName("take_days"), 1)
              .build();
      VersionedPacketHandler.handle(
          s, s.getProtocol().clientCommand("TakeLoginActivityRewardCsReq"), req.toByteArray());
      assertEquals(0, InitialData46Test.field(s.response, "retcode"));
      VersionedPacketHandler.handle(
          s, s.getProtocol().clientCommand("TakeLoginActivityRewardCsReq"), req.toByteArray());
      assertEquals(3301, InitialData46Test.field(s.response, "retcode"));
    } finally {
      org.hkrpg.server.gameplay.GameplayDefinitions.activities.remove(id);
      org.hkrpg.server.gameplay.GameplayDefinitions.windows.remove(window);
    }
  }
}
