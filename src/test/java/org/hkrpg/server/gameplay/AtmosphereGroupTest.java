package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.*;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.util.*;
import org.junit.jupiter.api.*;

class AtmosphereGroupTest {
  static final int PLANE = 199978, FLOOR = 19997801, SUB = 424030103, MAIN = 4240301;
  final Gson gson = new Gson();
  Scene scene;

  @Test
  void distinctAuthoredNpcInstancesKeepTheirIdentityEvenWithTheSameNpcDefinition() {
    var floor = gson.fromJson("{\"FloorID\":19997801}", FloorInfo.class);
    var plane = gson.fromJson("{\"PlaneID\":199978,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    var first =
        gson.fromJson(
            "{\"NPCList\":[{\"ID\":400001,\"NPCID\":199978,\"Pos\":{},\"Rot\":{}}]}",
            GroupInfo.class);
    var second = gson.fromJson(gson.toJson(first), GroupInfo.class);
    first.setId(131);
    second.setId(232);
    floor.getGroups().put(232, second);
    floor.getGroups().put(131, first);
    var oldFloor = GameData.getFloorInfos().put("P199978_F19997801", floor);
    var oldNpc = GameData.getNpcExcelMap().get(199978);
    GameData.getNpcExcelMap().add(gson.fromJson("{\"ID\":199978}", NpcExcel.class));
    try {
      scene = new Scene(player, plane, FLOOR);
      var actors = scene.getEntities(EntityNpc.class);
      assertEquals(2, actors.size());
      assertEquals(
          Set.of(131, 232),
          actors.stream().map(EntityNpc::getGroupId).collect(java.util.stream.Collectors.toSet()));
      assertEquals(2, actors.stream().map(EntityNpc::getEntityId).distinct().count());
      assertNull(scene.getEntityLoader().loadNpc(scene, first, first.getNPCList().getFirst()));
    } finally {
      if (oldFloor == null) GameData.getFloorInfos().remove("P199978_F19997801");
      else GameData.getFloorInfos().put("P199978_F19997801", oldFloor);
      if (oldNpc == null) GameData.getNpcExcelMap().keySet().remove(199978);
      else GameData.getNpcExcelMap().add(oldNpc);
    }
  }

  final Player player =
      new Player() {
        final emu.lunarcore.game.player.lineup.PlayerLineup lineup =
            new emu.lunarcore.game.player.lineup.PlayerLineup(this, 0);

        public emu.lunarcore.game.player.lineup.PlayerLineup getCurrentLineup() {
          return lineup;
        }

        public void save() {}

        public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {}

        public Scene getScene() {
          return scene;
        }

        public int getFloorId() {
          return FLOOR;
        }

        public int getPlaneId() {
          return PLANE;
        }
      };

  GroupInfo group(String conditions) {
    return gson.fromJson(
        "{\"Category\":\"Atmosphere\",\"AtmosphereCondition\":" + conditions + "}",
        GroupInfo.class);
  }

  @Test
  void aFloorValueNpcUsesTheNewSceneDefaultAndThenItsOwnPersistedFloorValue() {
    var floor =
        gson.fromJson(
            "{\"FloorID\":19997801,\"DimensionList\":[{\"ID\":0,\"GroupIndexList\":[0],\"SavedValues\":[{\"ID\":1,\"Name\":\"ClockNpc\",\"DefaultValue\":2}]}],\"GroupInstanceList\":[{\"ID\":162}]}",
            FloorInfo.class);
    var plane = gson.fromJson("{\"PlaneID\":199978,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    var row =
        group(
            "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupAtmosphereSavedValueCondition\",\"SavedValueName\":\"ClockNpc\",\"Value\":2}]}");
    row.setId(162);
    floor.getGroups().put(162, row);
    var oldFloor = GameData.getFloorInfos().put("P199978_F19997801", floor);
    try {
      assertNull(scene);
      scene = new Scene(player, plane, FLOOR);
      assertTrue(scene.getLoadedGroups().contains(162));
      player.getGameplay().getStoryValues().put("floor:" + (FLOOR + 1) + ":ClockNpc", 2);
      player.getGameplay().getStoryValues().put("floor:" + FLOOR + ":ClockNpc", 0);
      player.getMissionManager().refresh();
      assertFalse(scene.getLoadedGroups().contains(162));
      scene = new Scene(player, plane, FLOOR);
      assertFalse(scene.getLoadedGroups().contains(162));
      player.getGameplay().getStoryValues().put("floor:" + FLOOR + ":ClockNpc", 2);
      player.getMissionManager().refresh();
      assertTrue(scene.getLoadedGroups().contains(162));
    } finally {
      if (oldFloor == null) GameData.getFloorInfos().remove("P199978_F19997801");
      else GameData.getFloorInfos().put("P199978_F19997801", oldFloor);
    }
  }

  @Test
  void floorValueComparisonsRespectSignedLimitsAndDoNotInventUnknownZeroValues() {
    var floor = gson.fromJson("{\"FloorID\":19997801}", FloorInfo.class);
    var oldFloor = GameData.getFloorInfos().put("P199978_F19997801", floor);
    try {
      scene =
          new Scene(
              player,
              gson.fromJson("{\"PlaneID\":199978,\"PlaneType\":\"Town\"}", MazePlaneExcel.class),
              FLOOR);
      String prefix =
          "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupAtmosphereSavedValueCondition\",\"SavedValueName\":\"ClockNpc\"";
      assertFalse(player.getMissionManager().shouldLoadGroup(group(prefix + "}]}")));
      player.getGameplay().getStoryValues().put("floor:" + FLOOR + ":ClockNpc", Integer.MIN_VALUE);
      assertTrue(
          player
              .getMissionManager()
              .shouldLoadGroup(group(prefix + ",\"Operation\":\"Less\",\"Value\":32767}]}")));
      assertFalse(
          player
              .getMissionManager()
              .shouldLoadGroup(
                  group(prefix + ",\"Operation\":\"GreaterEqual\",\"Value\":32767}]}")));
      player.getGameplay().getStoryValues().put("floor:" + FLOOR + ":ClockNpc", Integer.MAX_VALUE);
      assertTrue(
          player
              .getMissionManager()
              .shouldLoadGroup(group(prefix + ",\"Operation\":\"Greater\",\"Value\":-32768}]}")));
      assertTrue(
          player
              .getMissionManager()
              .shouldLoadGroup(
                  group(prefix + ",\"Operation\":\"GreaterEqual\",\"Value\":32767}]}")));
      assertFalse(
          player
              .getMissionManager()
              .shouldLoadGroup(group(prefix + ",\"Operation\":\"LessEqual\",\"Value\":32767}]}")));
      assertTrue(
          player
              .getMissionManager()
              .shouldLoadGroup(group(prefix + ",\"Operation\":\"NotEqual\",\"Value\":-32768}]}")));
      player.getGameplay().getStoryValues().put("floor:" + FLOOR + ":ClockNpc", 32767);
      assertTrue(player.getMissionManager().shouldLoadGroup(group(prefix + ",\"Value\":32767}]}")));
      for (String suffix :
          List.of(
              ",\"Value\":2147483648}]}",
              ",\"Value\":0.5}]}",
              ",\"Value\":\"2147483647\"}]}",
              ",\"Operation\":{}}]}",
              ",\"Operation\":\"Unknown\"}]}",
              ",\"Value\":2147483647,\"Unknown\":1}]}"))
        assertFalse(player.getMissionManager().shouldLoadGroup(group(prefix + suffix)));
    } finally {
      if (oldFloor == null) GameData.getFloorInfos().remove("P199978_F19997801");
      else GameData.getFloorInfos().put("P199978_F19997801", oldFloor);
    }
  }

  @Test
  void floorComparisonTargetCannotTruncateOrAcceptAnInt32ValueOutsideTheNativeShort() {
    var floor = gson.fromJson("{\"FloorID\":19997801}", FloorInfo.class);
    var oldFloor = GameData.getFloorInfos().put("P199978_F19997801", floor);
    try {
      scene =
          new Scene(
              player,
              gson.fromJson("{\"PlaneID\":199978,\"PlaneType\":\"Town\"}", MazePlaneExcel.class),
              FLOOR);
      for (int expected : List.of(-32769, 32768, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
        player.getGameplay().getStoryValues().put("floor:" + FLOOR + ":ClockNpc", expected);
        var row =
            group(
                "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupAtmosphereSavedValueCondition\",\"SavedValueName\":\"ClockNpc\",\"Value\":"
                    + expected
                    + "}]}");
        assertFalse(player.getMissionManager().shouldLoadGroup(row), String.valueOf(expected));
      }
    } finally {
      if (oldFloor == null) GameData.getFloorInfos().remove("P199978_F19997801");
      else GameData.getFloorInfos().put("P199978_F19997801", oldFloor);
    }
  }

  @Test
  void omittedCompositeOperationUsesTheVerifiedNativeAndDefault() {
    var row =
        group(
            "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupSubMissionCompleteCondition\",\"SubMissionID\":424030103},{\"$type\":\"RPG.GameCore.LevelGroupSubMissionCompleteCondition\",\"SubMissionID\":424030104}]}");
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
    finishedSub(SUB);
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
    finishedSub(SUB + 1);
    assertTrue(player.getMissionManager().shouldLoadGroup(row));
    var unknown =
        group(
            "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupSubMissionCompleteCondition\",\"SubMissionID\":424030103},{\"$type\":\"Unknown\"}]}");
    assertFalse(player.getMissionManager().shouldLoadGroup(unknown));
  }

  @Test
  void anUnknownFloorValueCannotSatisfyAnOrAlongsideACompletedMission() {
    var floor = gson.fromJson("{\"FloorID\":19997801}", FloorInfo.class);
    var oldFloor = GameData.getFloorInfos().put("P199978_F19997801", floor);
    try {
      scene =
          new Scene(
              player,
              gson.fromJson("{\"PlaneID\":199978,\"PlaneType\":\"Town\"}", MazePlaneExcel.class),
              FLOOR);
      finishedSub(SUB);
      var row =
          group(
              "{\"Operation\":\"Or\",\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupSubMissionCompleteCondition\",\"SubMissionID\":424030103},{\"$type\":\"RPG.GameCore.LevelGroupAtmosphereSavedValueCondition\",\"SavedValueName\":\"Unknown\"}]}");
      assertFalse(player.getMissionManager().shouldLoadGroup(row));
    } finally {
      if (oldFloor == null) GameData.getFloorInfos().remove("P199978_F19997801");
      else GameData.getFloorInfos().put("P199978_F19997801", oldFloor);
    }
  }

  @Test
  void floorDefaultsBelongToTheCurrentDimensionAndCannotRepairCorruptStoredValues() {
    var floor =
        gson.fromJson(
            "{\"FloorID\":19997801,\"DimensionList\":[{\"ID\":0,\"SavedValues\":[{\"Name\":\"ClockNpc\",\"DefaultValue\":2}]},{\"ID\":1,\"SavedValues\":[{\"Name\":\"ClockNpc\",\"DefaultValue\":0}]}]}",
            FloorInfo.class);
    var oldFloor = GameData.getFloorInfos().put("P199978_F19997801", floor);
    try {
      scene =
          new Scene(
              player,
              gson.fromJson("{\"PlaneID\":199978,\"PlaneType\":\"Town\"}", MazePlaneExcel.class),
              FLOOR);
      var row =
          group(
              "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupAtmosphereSavedValueCondition\",\"SavedValueName\":\"ClockNpc\",\"Value\":2}]}");
      assertTrue(player.getMissionManager().shouldLoadGroup(row));
      scene = new Scene(player, scene.getExcel(), FLOOR, 1);
      assertFalse(player.getMissionManager().shouldLoadGroup(row));
      scene = new Scene(player, scene.getExcel(), FLOOR, 0);
      player.getGameplay().getStoryValues().put("floor:" + FLOOR + ":ClockNpc", null);
      assertFalse(player.getMissionManager().shouldLoadGroup(row));
      assertTrue(player.getGameplay().getStoryValues().containsKey("floor:" + FLOOR + ":ClockNpc"));
      assertNull(player.getGameplay().getStoryValues().get("floor:" + FLOOR + ":ClockNpc"));
    } finally {
      if (oldFloor == null) GameData.getFloorInfos().remove("P199978_F19997801");
      else GameData.getFloorInfos().put("P199978_F19997801", oldFloor);
    }
  }

  void finishedSub(int id) {
    var state = new QuestState(id);
    state.advance(1, 1, 1);
    player.getGameplay().getSubMissions().put(id, state);
  }

  @Test
  void anAuthoredFormerMissionChoiceSelectsOneNpcBranchAndSurvivesMissionCompletion() {
    var definition =
        gson.fromJson(
            "{\"MainMissionID\":5000423,\"MissionCustomValueList\":[{\"Index\":1,\"Name\":\"PlayerChoice\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2,3]}]}",
            MissionInfo.class);
    var previous = GameData.getMissionInfos().put(5000423, definition);
    try {
      var state = new QuestState(5000423);
      player.getGameplay().getMainMissions().put(5000423, state);
      var one = choice(1);
      var two = choice(2);
      assertFalse(player.getMissionManager().shouldLoadGroup(one));
      player.getGameplay().getStoryValues().put("mission:5000424:PlayerChoice", 1);
      assertFalse(player.getMissionManager().shouldLoadGroup(one));
      player.getGameplay().getStoryValues().put("mission:5000423:PlayerChoice", 1);
      assertTrue(player.getMissionManager().shouldLoadGroup(one));
      assertFalse(player.getMissionManager().shouldLoadGroup(two));
      state.advance(1, 1, 1);
      assertTrue(player.getMissionManager().shouldLoadGroup(one));
      player.getGameplay().getStoryValues().put("mission:5000423:PlayerChoice", 2);
      assertFalse(player.getMissionManager().shouldLoadGroup(one));
      assertTrue(player.getMissionManager().shouldLoadGroup(two));
      player.getGameplay().getCancelledMissions().add(5000423);
      assertFalse(player.getMissionManager().shouldLoadGroup(two));
    } finally {
      if (previous == null) GameData.getMissionInfos().remove(5000423);
      else GameData.getMissionInfos().put(5000423, previous);
    }
  }

  GroupInfo choice(int expected) {
    return group(
        "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupMissionSavedValueCondition\",\"MainMissionID\":5000423,\"MissionSavedValue\":{\"Index\":1,\"Name\":\"PlayerChoice\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2,3]},\"Value\":"
            + expected
            + "}]}");
  }

  @Test
  void realTalkChoiceSwapsTheSceneActorAndItsSavedBranchRestoresAfterTheMissionEnds() {
    var definition =
        gson.fromJson(
            "{\"MainMissionID\":5000423,\"FinishSubMissionList\":[500042302],\"MissionCustomValueList\":[{\"Index\":1,\"Name\":\"PlayerChoice\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2,3]}]}",
            MissionInfo.class);
    var oldDefinition = GameData.getMissionInfos().put(5000423, definition);
    var floor = gson.fromJson("{\"FloorID\":19997801}", FloorInfo.class);
    for (int value : List.of(1, 2)) {
      var raw = gson.toJsonTree(choice(value)).getAsJsonObject();
      raw.add(
          "NPCList",
          gson.fromJson(
              "[{\"ID\":400001,\"NPCID\":199978,\"Pos\":{},\"Rot\":{}}]",
              com.google.gson.JsonArray.class));
      var row = gson.fromJson(raw, GroupInfo.class);
      row.setId(215 + value);
      floor.getGroups().put(row.getId(), row);
    }
    var oldFloor = GameData.getFloorInfos().put("P199978_F19997801", floor);
    var oldNpc = GameData.getNpcExcelMap().get(199978);
    GameData.getNpcExcelMap().add(gson.fromJson("{\"ID\":199978}", NpcExcel.class));
    var previousSubs = new HashMap<Integer, MissionInfo.SubMissionInfo>();
    try {
      player.getGameplay().getMainMissions().put(5000423, new QuestState(5000423));
      for (int id : List.of(500042301, 500042302)) {
        var sub =
            gson.fromJson(
                "{\"ID\":"
                    + id
                    + ",\"MainMissionID\":5000423,\"FinishType\":\"Talk\",\"ParamStr1\":\"Branch_"
                    + id
                    + "\",\"Progress\":1,\"CustomValueList\":[\"PlayerChoice\"]}",
                MissionInfo.SubMissionInfo.class);
        previousSubs.put(id, GameplayDefinitions.subs.put(id, sub));
        player.getGameplay().getSubMissions().put(id, new QuestState(id));
      }
      var plane =
          gson.fromJson("{\"PlaneID\":199978,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
      scene = new Scene(player, plane, FLOOR);
      assertTrue(scene.getEntities(EntityNpc.class).isEmpty());
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC,
          player.getMissionManager().finishTalk(500042301, "Branch_500042301", Map.of(1, 1)));
      assertEquals(
          List.of(216),
          scene.getEntities(EntityNpc.class).stream().map(EntityNpc::getGroupId).toList());
      int oldEntity = scene.getEntities(EntityNpc.class).getFirst().getEntityId();
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC,
          player.getMissionManager().finishTalk(500042302, "Branch_500042302", Map.of(1, 2)));
      assertEquals(
          List.of(217),
          scene.getEntities(EntityNpc.class).stream().map(EntityNpc::getGroupId).toList());
      assertNotEquals(oldEntity, scene.getEntities(EntityNpc.class).getFirst().getEntityId());
      assertTrue(player.getMissionManager().finished(5000423));
      var restored = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
      player.getGameplay().getStoryValues().clear();
      player.getGameplay().getStoryValues().putAll(restored.getStoryValues());
      player.getGameplay().getMainMissions().clear();
      player.getGameplay().getMainMissions().putAll(restored.getMainMissions());
      scene = new Scene(player, plane, FLOOR);
      assertEquals(
          List.of(217),
          scene.getEntities(EntityNpc.class).stream().map(EntityNpc::getGroupId).toList());
    } finally {
      if (oldDefinition == null) GameData.getMissionInfos().remove(5000423);
      else GameData.getMissionInfos().put(5000423, oldDefinition);
      if (oldFloor == null) GameData.getFloorInfos().remove("P199978_F19997801");
      else GameData.getFloorInfos().put("P199978_F19997801", oldFloor);
      if (oldNpc == null) GameData.getNpcExcelMap().keySet().remove(199978);
      else GameData.getNpcExcelMap().add(oldNpc);
      for (var entry : previousSubs.entrySet()) {
        if (entry.getValue() == null) GameplayDefinitions.subs.remove(entry.getKey());
        else GameplayDefinitions.subs.put(entry.getKey(), entry.getValue());
      }
    }
  }

  @Test
  void missionChoiceNeedsTheMatchingUniqueDeclarationAndRealOwnerState() {
    var definition =
        gson.fromJson(
            "{\"MainMissionID\":5000423,\"MissionCustomValueList\":[{\"Index\":1,\"Name\":\"PlayerChoice\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2,3]}]}",
            MissionInfo.class);
    var previous = GameData.getMissionInfos().put(5000423, definition);
    try {
      var row = choice(1);
      player.getGameplay().getStoryValues().put("mission:5000423:PlayerChoice", 1);
      assertFalse(player.getMissionManager().shouldLoadGroup(row));
      player.getGameplay().getMainMissions().put(5000423, new QuestState(5000424));
      assertFalse(player.getMissionManager().shouldLoadGroup(row));
      player.getGameplay().getMainMissions().put(5000423, new QuestState(5000423));
      assertTrue(player.getMissionManager().shouldLoadGroup(row));
      for (String replacement :
          List.of(
              "{\"Index\":2,\"Name\":\"PlayerChoice\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2,3]}",
              "{\"Index\":1,\"Name\":\"Other\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2,3]}",
              "{\"Index\":1,\"Name\":\"PlayerChoice\",\"Type\":\"Current\",\"ValidValueParamList\":[1,2,3]}",
              "{\"Index\":1,\"Name\":\"PlayerChoice\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2]}",
              "{\"Index\":1,\"Name\":\"PlayerChoice\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2,3],\"isRange\":true}")) {
        GameData.getMissionInfos()
            .put(
                5000423,
                gson.fromJson(
                    "{\"MainMissionID\":5000423,\"MissionCustomValueList\":[" + replacement + "]}",
                    MissionInfo.class));
        assertFalse(player.getMissionManager().shouldLoadGroup(row), replacement);
      }
      GameData.getMissionInfos()
          .put(
              5000423,
              gson.fromJson(
                  "{\"MainMissionID\":5000423,\"MissionCustomValueList\":[{\"Index\":1,\"Name\":\"PlayerChoice\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2,3]},{\"Index\":1,\"Name\":\"Other\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2,3]}]}",
                  MissionInfo.class));
      assertFalse(player.getMissionManager().shouldLoadGroup(row));
    } finally {
      if (previous == null) GameData.getMissionInfos().remove(5000423);
      else GameData.getMissionInfos().put(5000423, previous);
    }
  }

  @Test
  void missionChoiceRejectsMalformedOrUnknownShapesAndCorruptStoredValues() {
    var definition =
        gson.fromJson(
            "{\"MainMissionID\":5000423,\"MissionCustomValueList\":[{\"Index\":1,\"Name\":\"PlayerChoice\",\"Type\":\"Former\",\"ValidValueParamList\":[1,2,3]}]}",
            MissionInfo.class);
    var previous = GameData.getMissionInfos().put(5000423, definition);
    try {
      player.getGameplay().getMainMissions().put(5000423, new QuestState(5000423));
      player.getGameplay().getStoryValues().put("mission:5000423:PlayerChoice", 1);
      assertTrue(player.getMissionManager().shouldLoadGroup(choice(1)));
      var raw = gson.toJsonTree(choice(1)).getAsJsonObject().getAsJsonObject("AtmosphereCondition");
      for (String change :
          List.of(
              "missingValue",
              "unknownRoot",
              "unknownNested",
              "stringIndex",
              "omittedType",
              "badList",
              "range")) {
        var set = raw.deepCopy();
        var clause = set.getAsJsonArray("Conditions").get(0).getAsJsonObject();
        var custom = clause.getAsJsonObject("MissionSavedValue");
        switch (change) {
          case "missingValue" -> clause.remove("Value");
          case "unknownRoot" -> clause.addProperty("Unknown", 1);
          case "unknownNested" -> custom.addProperty("Unknown", 1);
          case "stringIndex" -> custom.addProperty("Index", "1");
          case "omittedType" -> custom.remove("Type");
          case "badList" -> custom.add("ValidValueParamList", gson.toJsonTree(List.of(1, "2", 3)));
          case "range" -> custom.addProperty("isRange", true);
        }
        assertFalse(player.getMissionManager().shouldLoadGroup(group(gson.toJson(set))), change);
      }
      for (Integer value : Arrays.asList(0, 4, null)) {
        player.getGameplay().getStoryValues().put("mission:5000423:PlayerChoice", value);
        assertFalse(player.getMissionManager().shouldLoadGroup(choice(1)));
      }
    } finally {
      if (previous == null) GameData.getMissionInfos().remove(5000423);
      else GameData.getMissionInfos().put(5000423, previous);
    }
  }

  @Test
  void theAuthoredReplacementNpcRequiresTheCompletedSubMission() {
    var row =
        group(
            "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupSubMissionCompleteCondition\",\"SubMissionID\":424030103}]}");
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
    player.getGameplay().getSubMissions().put(SUB, new QuestState(SUB));
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
    finishedSub(SUB);
    assertTrue(player.getMissionManager().shouldLoadGroup(row));
    player.getGameplay().getCancelledSubs().add(SUB);
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
    player.getGameplay().getCancelledSubs().remove(SUB);
    var wrong = new QuestState(SUB + 1);
    wrong.advance(1, 1, 1);
    player.getGameplay().getSubMissions().put(SUB, wrong);
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
  }

  @Test
  void aMainMissionGateDoesNotTreatAcceptanceOrCancellationAsCompletion() {
    var row =
        group(
            "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupAtmosphereMainMissionCompleteCondition\",\"MainMissionID\":4240301}]}");
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
    var state = new QuestState(MAIN);
    player.getGameplay().getMainMissions().put(MAIN, state);
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
    state.advance(1, 1, 1);
    assertTrue(player.getMissionManager().shouldLoadGroup(row));
    player.getGameplay().getCancelledMissions().add(MAIN);
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
  }

  @Test
  void explicitAndAndOrConditionsUseRealMissionsAndRejectUnknownBranches() {
    String first =
        "{\"$type\":\"RPG.GameCore.LevelGroupSubMissionCompleteCondition\",\"SubMissionID\":424030103}";
    String second =
        "{\"$type\":\"RPG.GameCore.LevelGroupSubMissionCompleteCondition\",\"SubMissionID\":424030104}";
    var and = group("{\"Operation\":\"And\",\"Conditions\":[" + first + "," + second + "]}");
    var or = group("{\"Operation\":\"Or\",\"Conditions\":[" + first + "," + second + "]}");
    assertFalse(player.getMissionManager().shouldLoadGroup(and));
    assertFalse(player.getMissionManager().shouldLoadGroup(or));
    finishedSub(SUB);
    assertFalse(player.getMissionManager().shouldLoadGroup(and));
    assertTrue(player.getMissionManager().shouldLoadGroup(or));
    finishedSub(SUB + 1);
    assertTrue(player.getMissionManager().shouldLoadGroup(and));
    var unknown =
        group("{\"Operation\":\"Or\",\"Conditions\":[" + first + ",{\"$type\":\"Unknown\"}]}");
    assertFalse(player.getMissionManager().shouldLoadGroup(unknown));
  }

  @Test
  void unknownAndMalformedClausesCannotLoadAnNpcWhileEmptyDefinitionsStayUnconditional() {
    assertTrue(player.getMissionManager().shouldLoadGroup(group("{}")));
    assertTrue(player.getMissionManager().shouldLoadGroup(group("{\"Conditions\":[]}")));
    for (String raw :
        List.of(
            "{\"Conditions\":[{}]}",
            "{\"Conditions\":[{\"$type\":\"Unknown\",\"SubMissionID\":424030103}]}",
            "{\"Conditions\":[null]}",
            "{\"Conditions\":{},\"Operation\":\"And\"}",
            "{\"Conditions\":[],\"Operation\":\"Unknown\"}",
            "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupSubMissionCompleteCondition\",\"SubMissionID\":0}]}",
            "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupSubMissionCompleteCondition\",\"SubMissionID\":424030103,\"Unknown\":1}]}"))
      assertFalse(player.getMissionManager().shouldLoadGroup(group(raw)), raw);
  }

  @Test
  void completedSubWithACancelledParentCannotSpawnItsReplacement() {
    var row =
        group(
            "{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupSubMissionCompleteCondition\",\"SubMissionID\":424030103}]}");
    var config =
        gson.fromJson(
            "{\"ID\":424030103,\"MainMissionID\":4240301}", MissionInfo.SubMissionInfo.class);
    var previous = GameplayDefinitions.subs.put(SUB, config);
    try {
      finishedSub(SUB);
      assertTrue(player.getMissionManager().shouldLoadGroup(row));
      player.getGameplay().getCancelledMissions().add(MAIN);
      assertFalse(player.getMissionManager().shouldLoadGroup(row));
    } finally {
      if (previous == null) GameplayDefinitions.subs.remove(SUB);
      else GameplayDefinitions.subs.put(SUB, previous);
    }
  }

  @Test
  void aMissionOwnedNpcIsVisibleOnlyWhileItsOwningMissionIsActive() {
    var row =
        gson.fromJson("{\"Category\":\"Mission\",\"OwnerMainMissionID\":4240301}", GroupInfo.class);
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
    var state = new QuestState(MAIN);
    player.getGameplay().getMainMissions().put(MAIN, state);
    assertTrue(player.getMissionManager().shouldLoadGroup(row));
    player.getGameplay().getCancelledMissions().add(MAIN);
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
    player.getGameplay().getCancelledMissions().remove(MAIN);
    state.advance(1, 1, 1);
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
    state.reserveReward();
    state.closeReward();
    assertFalse(player.getMissionManager().shouldLoadGroup(row));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void theActualSceneSwapsMissionAndAtmosphereNpcsOnCompletionAndRestoresTheSameSet(
      boolean sameNpc) {
    var floor = gson.fromJson("{\"FloorID\":19997801}", FloorInfo.class);
    var plane = gson.fromJson("{\"PlaneID\":199978,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    var active =
        gson.fromJson(
            "{\"Category\":\"Mission\",\"OwnerMainMissionID\":4240301,\"NPCList\":[{\"ID\":400001,\"NPCID\":199978,\"Pos\":{},\"Rot\":{}}]}",
            GroupInfo.class);
    var replacement =
        gson.fromJson(
            "{\"Category\":\"Atmosphere\",\"AtmosphereCondition\":{\"Conditions\":[{\"$type\":\"RPG.GameCore.LevelGroupSubMissionCompleteCondition\",\"SubMissionID\":424030103}]},\"NPCList\":[{\"ID\":400001,\"NPCID\":199979,\"Pos\":{},\"Rot\":{}}]}",
            GroupInfo.class);
    active.setId(131);
    replacement.setId(162);
    if (sameNpc) {
      var npc = gson.toJsonTree(replacement.getNPCList().getFirst()).getAsJsonObject();
      npc.addProperty("NPCID", 199978);
      replacement.getNPCList().set(0, gson.fromJson(npc, NpcInfo.class));
    }
    floor.getGroups().put(162, replacement);
    floor.getGroups().put(131, active);
    var oldFloor = GameData.getFloorInfos().put("P199978_F19997801", floor);
    var oldNpc = GameData.getNpcExcelMap().get(199978);
    var oldReplacement = GameData.getNpcExcelMap().get(199979);
    GameData.getNpcExcelMap().add(gson.fromJson("{\"ID\":199978}", NpcExcel.class));
    GameData.getNpcExcelMap().add(gson.fromJson("{\"ID\":199979}", NpcExcel.class));
    try {
      player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
      scene = new Scene(player, plane, FLOOR);
      assertEquals(
          List.of(199978),
          scene.getEntities(EntityNpc.class).stream().map(EntityNpc::getNpcId).toList());
      assertFalse(scene.getLoadedGroups().contains(162));
      finishedSub(SUB);
      player.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 1);
      player.getMissionManager().refresh();
      assertEquals(
          List.of(sameNpc ? 199978 : 199979),
          scene.getEntities(EntityNpc.class).stream().map(EntityNpc::getNpcId).toList());
      assertFalse(scene.getLoadedGroups().contains(131));
      assertTrue(scene.getLoadedGroups().contains(162));
      scene = new Scene(player, plane, FLOOR);
      assertEquals(
          List.of(sameNpc ? 199978 : 199979),
          scene.getEntities(EntityNpc.class).stream().map(EntityNpc::getNpcId).toList());
      player.getGameplay().getCancelledSubs().add(SUB);
      player.getMissionManager().refresh();
      assertTrue(scene.getEntities(EntityNpc.class).isEmpty());
      assertFalse(scene.getLoadedGroups().contains(162));
    } finally {
      if (oldFloor == null) GameData.getFloorInfos().remove("P199978_F19997801");
      else GameData.getFloorInfos().put("P199978_F19997801", oldFloor);
      if (oldNpc == null) GameData.getNpcExcelMap().keySet().remove(199978);
      else GameData.getNpcExcelMap().add(oldNpc);
      if (oldReplacement == null) GameData.getNpcExcelMap().keySet().remove(199979);
      else GameData.getNpcExcelMap().add(oldReplacement);
    }
  }
}
