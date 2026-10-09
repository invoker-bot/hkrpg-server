package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import org.junit.jupiter.api.Test;

class StoryGroupTest {
  @Test
  void stateActionRestoresAnEmptyGroupAndRejectsMissingGroupBeforeEarlierActions()
      throws Exception {
    var gson = new Gson();
    var floor = gson.fromJson("{\"FloorID\":19995201}", FloorInfo.class);
    var group = gson.fromJson("{\"GroupName\":\"StoryState\"}", GroupInfo.class);
    group.setId(8);
    floor.getGroups().put(8, group);
    var plane = gson.fromJson("{\"PlaneID\":199952}", MazePlaneExcel.class);
    var player =
        new Player() {
          final PlayerLineup lineup = new PlayerLineup(this, 0);
          Scene current;

          public PlayerLineup getCurrentLineup() {
            if (lineup.size() == 0) lineup.getAvatars().add(999999);
            return lineup;
          }

          public Scene getScene() {
            return current == null ? (current = new Scene(this, plane, 19995201)) : current;
          }

          public void save() {}
        };
    GameData.getFloorInfos().put("P199952_F19995201", floor);
    try {
      var objective =
          gson.fromJson(
              "{\"ID\":19995209,\"MainMissionID\":199952,\"TakeType\":\"Auto\",\"FinishType\":\"GroupState\",\"ParamType\":\"Equal\",\"ParamInt1\":8,\"ParamInt2\":3,\"LevelFloorID\":19995201}",
              MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(objective.getId(), objective);
      player
          .getGameplay()
          .getMainMissions()
          .put(199952, new emu.lunarcore.game.quest.QuestState(199952));
      player.getMissionManager().refresh();
      assertEquals(0, player.getMissionManager().sub(objective.getId()).getProgress());
      var invalid =
          gson.fromJson(
              "{\"ID\":199952010,\"MainMissionID\":19995201,\"FinishActionList\":["
                  + "{\"FinishActionType\":\"SetCustomValue\",\"FinishActionParaString\":[\"Before\",\"1\"]},"
                  + "{\"FinishActionType\":\"SetGroupState\",\"FinishActionPara\":[999,3]}]}",
              MissionInfo.SubMissionInfo.class);
      assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, new StoryActions(player).execute(invalid));
      assertTrue(player.getGameplay().getStoryValues().isEmpty());
      assertFalse(player.getGameplay().getStoryActions().containsKey(invalid.getId()));
      var action =
          gson.fromJson(
              "{\"ID\":199952011,\"FinishActionList\":["
                  + "{\"FinishActionType\":\"SetGroupState\",\"FinishActionPara\":[8,3]}]}",
              MissionInfo.SubMissionInfo.class);
      assertEquals(Retcode.SUCC, new StoryActions(player).execute(action));
      assertEquals(3, player.getScene().getGroups().get(8).getState());
      player.getMissionManager().refresh();
      assertEquals(1, player.getMissionManager().sub(objective.getId()).getProgress());
      var reloaded = new Scene(player, plane, 19995201);
      assertEquals(3, reloaded.getGroups().get(8).getState());
      assertTrue(reloaded.getGroups().get(8).getProperties().isEmpty());
      var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
      var wire =
          bridge
              .clientSchema()
              .parse(
                  "SceneInfo",
                  bridge.translate("SceneInfo", reloaded.toProto().toByteArray(), true));
      var groups = wire.getDescriptorForType().findFieldByName("entity_group_list");
      var row =
          ((java.util.List<?>) wire.getField(groups))
              .stream()
                  .map(com.google.protobuf.DynamicMessage.class::cast)
                  .filter(
                      r ->
                          r.getField(r.getDescriptorForType().findFieldByName("group_id"))
                              .equals(8))
                  .findFirst()
                  .orElseThrow();
      assertEquals(3, row.getField(row.getDescriptorForType().findFieldByName("state")));
      assertEquals(Retcode.SUCC, new StoryActions(player).execute(action));
      assertEquals(1, player.getGameplay().getStoryActions().get(action.getId()).getNext());
      player.getGameplay().getStoryValues().clear();
      var other = new Scene(player, plane, 19995201);
      assertFalse(other.getGroups().containsKey(8));
    } finally {
      GameData.getFloorInfos().remove("P199952_F19995201");
      GameplayDefinitions.subs.remove(19995209);
    }
  }

  @Test
  void configuredGroupActionSurvivesSceneReloadAndPreservesEmptyGroups() {
    var gson = new Gson();
    var floor = gson.fromJson("{\"FloorID\":19995001}", FloorInfo.class);
    var group =
        gson.fromJson(
            "{\"GroupName\":\"StoryMechanism\",\"GroupPropertyMap\":{\"1\":{\"ID\":1,\"Name\":\"Door\",\"DefaultValue\":0,\"MaxValue\":2}}}",
            GroupInfo.class);
    group.setId(9);
    floor.getGroups().put(9, group);
    var plane = gson.fromJson("{\"PlaneID\":19995}", MazePlaneExcel.class);
    var p =
        new Player() {
          final PlayerLineup lineup = new PlayerLineup(this, 0);
          Scene current;

          public PlayerLineup getCurrentLineup() {
            if (lineup.size() == 0) lineup.getAvatars().add(999999);
            return lineup;
          }

          public Scene getScene() {
            return current == null ? (current = new Scene(this, plane, 19995001)) : current;
          }

          public void save() {}
        };
    GameData.getFloorInfos().put("P19995_F19995001", floor);
    try {
      var sub =
          gson.fromJson(
              "{\"ID\":199950011,\"FinishActionList\":[{\"FinishActionType\":\"SetGroupProperty\",\"FinishActionParaString\":[\"9\",\"Door\",\"1\"]}]}",
              MissionInfo.SubMissionInfo.class);
      assertEquals(Retcode.SUCC, new StoryActions(p).execute(sub));
      assertEquals(1, p.getScene().getGroups().get(9).getProperties().getInt("Door"));
      var reloaded = new Scene(p, plane, 19995001);
      assertEquals(1, reloaded.getGroups().get(9).getProperties().getInt("Door"));
      var snapshot = reloaded.toProto();
      var wireGroup =
          java.util.stream.StreamSupport.stream(snapshot.getEntityGroupList().spliterator(), false)
              .filter(g -> g.getGroupId() == 9)
              .findFirst()
              .orElseThrow();
      assertEquals(1, wireGroup.getGroupPropertyMap().get(0).getValue());
      var other = new Scene(p, plane, 19995002);
      assertFalse(other.getGroups().containsKey(9));
      var invalid =
          gson.fromJson(
              "{\"ID\":199950012,\"FinishActionList\":[{\"FinishActionType\":\"SetGroupProperty\",\"FinishActionParaString\":[\"9\",\"Unknown\",\"1\"]}]}",
              MissionInfo.SubMissionInfo.class);
      assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, new StoryActions(p).execute(invalid));
      assertFalse(p.getGameplay().getStoryActions().containsKey(199950012));
      assertEquals(0, group.getGroupPropertyMap().get(1).getDefaultValue());
      var objective =
          gson.fromJson(
              "{\"ID\":199950013,\"MainMissionID\":19995001,\"TakeType\":\"Auto\",\"FinishType\":\"GroupPropertyValue\",\"LevelFloorID\":19995001,\"ParamInt1\":9,\"ParamInt2\":1,\"ParamStr1\":\"Door\"}",
              MissionInfo.SubMissionInfo.class);
      var unknownProperty =
          gson.fromJson(
              "{\"ID\":199950014,\"MainMissionID\":19995001,\"TakeType\":\"Auto\",\"FinishType\":\"GroupPropertyValue\",\"LevelFloorID\":19995001,\"ParamInt1\":9,\"ParamInt2\":0,\"ParamStr1\":\"Unknown\"}",
              MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(199950013, objective);
      GameplayDefinitions.subs.put(199950014, unknownProperty);
      p.getGameplay()
          .getMainMissions()
          .put(19995001, new emu.lunarcore.game.quest.QuestState(19995001));
      p.getMissionManager().refresh();
      assertEquals(
          emu.lunarcore.game.quest.QuestState.Phase.FINISHED,
          p.getMissionManager().sub(199950013).getPhase());
      assertEquals(
          emu.lunarcore.game.quest.QuestState.Phase.DOING,
          p.getMissionManager().sub(199950014).getPhase());
    } finally {
      GameData.getFloorInfos().remove("P19995_F19995001");
      GameplayDefinitions.subs.remove(199950013);
      GameplayDefinitions.subs.remove(199950014);
    }
  }
}
