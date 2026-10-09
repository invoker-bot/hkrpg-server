package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissionResourcesTest {
  @Test
  void sceneSnapshotUsesActualStoryValuesInsteadOfCompletingEveryFloorFlag() {
    var gson = new Gson();
    var floor =
        gson.fromJson(
            "{\"FloorID\":19996001,\"DimensionList\":[{\"ID\":0,\"SavedValues\":[{\"Name\":\"StoryGate\",\"DefaultValue\":0,\"MaxValue\":9}]}]}",
            emu.lunarcore.data.config.FloorInfo.class);
    var plane = gson.fromJson("{\"PlaneID\":19996}", emu.lunarcore.data.excel.MazePlaneExcel.class);
    var p =
        new Player() {
          final emu.lunarcore.game.player.lineup.PlayerLineup lineup =
              new emu.lunarcore.game.player.lineup.PlayerLineup(this, 0);

          public emu.lunarcore.game.player.lineup.PlayerLineup getCurrentLineup() {
            if (lineup.size() == 0) lineup.getAvatars().add(999999);
            return lineup;
          }

          public void save() {}
        };
    GameData.getFloorInfos().put("P19996_F19996001", floor);
    try {
      var scene = new emu.lunarcore.game.scene.Scene(p, plane, 19996001);
      assertEquals(0, scene.toProto().getFloorSavedValueMap().get(0).getValue());
      p.getGameplay().getStoryValues().put("floor:19996001:StoryGate", 1);
      p.getGameplay().getStoryValues().put("floor:19996002:OtherFloor", 5);
      var snapshot = scene.toProto();
      assertEquals(1, snapshot.getFloorSavedValueMap().get(0).getValue());
      assertEquals(1, snapshot.getFloorSavedValueMap().length());
    } finally {
      GameData.getFloorInfos().remove("P19996_F19996001");
    }
  }

  @Test
  void splitMissionFilesAreMergedWithoutGuessingMissingSubmissions(@TempDir Path root)
      throws Exception {
    var previous = new java.util.HashMap<>(GameData.getMissionInfos());
    var previousSubs = new java.util.HashMap<>(GameplayDefinitions.subs);
    try {
      Files.writeString(
          root.resolve("MissionInfo_19996001.json"),
          "{\"MainMissionID\":19996001,\"StartSubMissionList\":[199960011],\"FinishSubMissionList\":[199960011]}");
      Files.writeString(
          root.resolve("MissionInfo_19996001.partial.json"),
          "{\"SubMissionList\":[{\"ID\":199960011,\"MainMissionID\":19996001,\"TakeType\":\"Auto\",\"FinishType\":\"Talk\",\"ParamStr1\":\"test\"}]}");
      GameplayDefinitions.loadMissions(root);
      assertEquals(1, GameData.getMissionInfos().size());
      assertEquals("test", GameplayDefinitions.subs.get(199960011).getParamStr1());
      assertEquals(
          199960011, GameData.getMissionInfos().get(19996001).getFinishSubMissionList()[0]);
      Files.writeString(
          root.resolve("MissionInfo_19996001.partial.json"), "{\"MainMissionID\":19996002}");
      assertThrows(IllegalStateException.class, () -> GameplayDefinitions.loadMissions(root));
    } finally {
      GameData.getMissionInfos().clear();
      GameData.getMissionInfos().putAll(previous);
      GameplayDefinitions.subs.clear();
      GameplayDefinitions.subs.putAll(previousSubs);
    }
  }

  @Test
  void pinnedMissionScriptsAreLoadedAndCannotEscapeTheMissionResourceTree(@TempDir Path root)
      throws Exception {
    var missions = root.resolve("Config/Level/Mission/19996004");
    Files.createDirectories(missions);
    var info = missions.resolve("MissionInfo_19996004.json");
    String definition =
        "{\"MainMissionID\":19996004,\"SubMissionList\":[{\"ID\":199960041,\"MainMissionID\":19996004,\"MissionJsonPath\":\"Config/Level/Mission/19996004/Mission_199960041.json\"}]}";
    Files.writeString(info, definition);
    Files.writeString(
        missions.resolve("Mission_199960041.json"),
        "{\"Type\":\"Mission\",\"OnStartSequece\":[{\"Order\":1,\"TaskList\":[{\"$type\":\"RPG.GameCore.TriggerPerformance\",\"PerformanceID\":123}]}]}");
    var previous = new java.util.HashMap<>(GameData.getMissionInfos());
    var previousSubs = new java.util.HashMap<>(GameplayDefinitions.subs);
    try {
      GameplayDefinitions.loadMissions(root.resolve("Config/Level/Mission"));
      assertEquals(
          123,
          GameplayDefinitions.subs
              .get(199960041)
              .getTask()
              .getOnStartSequece()[0]
              .getTaskList()[0]
              .getPerformanceId());
      Files.writeString(
          info,
          definition.replace(
              "Config/Level/Mission/19996004/Mission_199960041.json", "../outside.json"));
      assertThrows(
          IllegalStateException.class,
          () -> GameplayDefinitions.loadMissions(root.resolve("Config/Level/Mission")));
    } finally {
      GameData.getMissionInfos().clear();
      GameData.getMissionInfos().putAll(previous);
      GameplayDefinitions.subs.clear();
      GameplayDefinitions.subs.putAll(previousSubs);
    }
  }

  @Test
  void missionGroupsRequireAcceptedStateAndUnloadOnFinish() {
    var p =
        new Player() {
          public void save() {}
        };
    var group =
        new Gson()
            .fromJson(
                "{\"Category\":\"Mission\",\"OwnerMainMissionID\":19996003,\"LoadCondition\":{\"Conditions\":[{\"ID\":19996003,\"Phase\":\"Accept\"}]},\"UnloadCondition\":{\"Conditions\":[{\"ID\":19996003,\"Phase\":\"Finish\"}]}}",
                GroupInfo.class);
    assertFalse(p.getMissionManager().shouldLoadGroup(group));
    var main = new QuestState(19996003);
    p.getGameplay().getMainMissions().put(19996003, main);
    assertTrue(p.getMissionManager().shouldLoadGroup(group));
    main.advance(1, 1, 1);
    assertFalse(p.getMissionManager().shouldLoadGroup(group));
  }
}
