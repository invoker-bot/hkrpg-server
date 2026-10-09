package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SceneDimensionTest {
  final Gson gson = new Gson();
  static final int PLANE = 199949, FLOOR = 19994901, ENTRY = 19994901;
  static final String FLOOR_JSON =
      """
      {"FloorID":19994901,"DimensionList":[
         {"ID":0,"GroupIndexList":[0]},{"ID":4,"GroupIndexList":[1,0,1]}],
       "GroupInstanceList":[
         {"ID":8,"GroupPath":"Config/LevelOutput/RuntimeGroup/Main.json"},
         {"ID":9,"GroupPath":"Config/LevelOutput/RuntimeGroup/Story.json"}]}
      """;

  GroupInfo group(int id, String properties) {
    var group =
        gson.fromJson(
            "{\"GroupName\":\"Dimension test\",\"LoadSide\":\"Server\","
                + "\"AnchorList\":[],\"PropList\":[],"
                + properties
                + "}",
            GroupInfo.class);
    group.setId(id);
    return group;
  }

  @Test
  void theActualResourceLoaderRetainsGroupsReferencedByOtherStoryDimensions(@TempDir Path root)
      throws Exception {
    Files.createDirectories(root.resolve("Config/LevelOutput/RuntimeFloor"));
    Files.createDirectories(root.resolve("Config/LevelOutput/RuntimeGroup"));
    Files.writeString(
        root.resolve("Config/LevelOutput/RuntimeFloor/P199949_F19994901.json"), FLOOR_JSON);
    Files.writeString(
        root.resolve("Config/LevelOutput/RuntimeGroup/Main.json"),
        "{\"GroupName\":\"Main\",\"PropList\":[]}");
    Files.writeString(
        root.resolve("Config/LevelOutput/RuntimeGroup/Story.json"),
        "{\"GroupName\":\"Story\",\"PropList\":[]}");
    var configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    var oldConfig = configField.get(null);
    var oldEntrances = new ArrayList<>(GameData.getMapEntranceExcelMap().values());
    var oldFloors = new HashMap<>(GameData.getFloorInfos());
    try {
      var config = new Config();
      config.resourceDir = root.toString();
      configField.set(null, config);
      GameData.getMapEntranceExcelMap().keySet().clear();
      GameData.getFloorInfos().clear();
      GameData.getMapEntranceExcelMap()
          .add(
              gson.fromJson(
                  "{\"ID\":19994901,\"PlaneID\":199949,\"FloorID\":19994901}",
                  MapEntranceExcel.class));
      var load = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
      load.setAccessible(true);
      load.invoke(null);
      var loaded = GameData.getFloorInfo(PLANE, FLOOR);
      assertNotNull(loaded);
      assertEquals(Set.of(8, 9), loaded.getGroups().keySet());
      assertEquals(4, loaded.getDimensionList().get(1).getID());
    } finally {
      configField.set(null, oldConfig);
      GameData.getMapEntranceExcelMap().keySet().clear();
      oldEntrances.forEach(GameData.getMapEntranceExcelMap()::add);
      GameData.getFloorInfos().clear();
      GameData.getFloorInfos().putAll(oldFloors);
    }
  }

  @Test
  void aMainWorldSceneCannotLoadOrRefreshAMissionGroupFromAnotherDimension() {
    var floor = gson.fromJson(FLOOR_JSON, FloorInfo.class);
    floor.getGroups().put(8, group(8, "\"Category\":\"Normal\""));
    var story = group(9, "\"Category\":\"Mission\",\"OwnerMainMissionID\":199949");
    floor.getGroups().put(9, story);
    var plane = gson.fromJson("{\"PlaneID\":199949,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    var player =
        new Player() {
          final PlayerLineup lineup = new PlayerLineup(this, 0);

          public PlayerLineup getCurrentLineup() {
            return lineup;
          }

          public void save() {}
        };
    player.getGameplay().getMainMissions().put(PLANE, new QuestState(PLANE));
    GameData.getFloorInfos().put("P199949_F19994901", floor);
    try {
      var scene = new Scene(player, plane, FLOOR);
      assertTrue(scene.getLoadedGroups().contains(8));
      assertFalse(
          scene.getLoadedGroups().contains(9),
          "the main scene must not reveal a future story group");
      scene.loadGroup(story);
      scene.loadGroup(9);
      scene.refreshMissionGroups();
      assertFalse(scene.getLoadedGroups().contains(9));
      assertEquals(Set.of(8), scene.getLoadedGroups());
    } finally {
      GameData.getFloorInfos().remove("P199949_F19994901");
    }
  }

  @Test
  void anAlternateDimensionCannotOverwriteTheMainWorldAnchorCache() {
    var floor = gson.fromJson(FLOOR_JSON, FloorInfo.class);
    for (int id : List.of(8, 9)) {
      var group =
          gson.fromJson(
              "{\"GroupName\":\"Anchor dimension\",\"PropList\":["
                  + "{\"ID\":"
                  + (300000 + id)
                  + ",\"PropID\":1001,\"AnchorID\":1,\"AnchorGroupID\":"
                  + id
                  + ",\"MappingInfoID\":77,\"State\":\"CheckPointEnable\"}]}",
              GroupInfo.class);
      group.setId(id);
      floor.getGroups().put(id, group);
    }
    floor.onLoad();
    assertSame(
        floor.getGroups().get(8).getPropList().getFirst(), floor.getCachedTeleports().get(77));
    assertEquals(1, floor.getUnlockedCheckpoints().size());
    assertSame(
        floor.getGroups().get(9), floor.getGroups().get(9).getPropList().getFirst().getGroup());
  }

  @Test
  void invalidDimensionReferencesFailBeforePartialResourceLoading() {
    for (String dimensions :
        List.of(
            "[{\"ID\":0,\"GroupIndexList\":[-1]}]",
            "[{\"ID\":0,\"GroupIndexList\":[1]}]",
            "[{\"ID\":0,\"GroupIndexList\":[0]},{\"ID\":0,\"GroupIndexList\":[0]}]")) {
      var floor =
          gson.fromJson(
              "{\"FloorID\":19994901,\"DimensionList\":"
                  + dimensions
                  + ",\"GroupInstanceList\":[{\"ID\":8}]}",
              FloorInfo.class);
      assertThrows(IllegalStateException.class, floor::getReferencedGroupIndices);
      assertTrue(floor.getGroups().isEmpty());
    }
  }

  @Test
  void anExplicitStoryDimensionLoadsItsOwnGroupsInsteadOfTheMainWorld() {
    var floor = gson.fromJson(FLOOR_JSON.replace("[1,0,1]", "[1]"), FloorInfo.class);
    floor.getGroups().put(8, group(8, "\"Category\":\"Normal\""));
    floor.getGroups().put(9, group(9, "\"Category\":\"Normal\""));
    var plane = gson.fromJson("{\"PlaneID\":199949,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    var player =
        new Player() {
          final PlayerLineup lineup = new PlayerLineup(this, 0);

          public PlayerLineup getCurrentLineup() {
            return lineup;
          }

          public void save() {}
        };
    var old = GameData.getFloorInfos().put("P199949_F19994901", floor);
    try {
      var scene = new Scene(player, plane, FLOOR, 4);
      assertEquals(4, scene.getDimensionId());
      assertEquals(Set.of(9), scene.getLoadedGroups());
      scene.loadGroup(8);
      scene.refreshMissionGroups();
      assertEquals(Set.of(9), scene.getLoadedGroups());
      assertEquals(Set.of(8), new Scene(player, plane, FLOOR).getLoadedGroups());
    } finally {
      if (old == null) GameData.getFloorInfos().remove("P199949_F19994901");
      else GameData.getFloorInfos().put("P199949_F19994901", old);
    }
  }

  @Test
  void anUnknownDimensionCannotConstructAMainWorldSceneInItsPlace() {
    var floor = gson.fromJson(FLOOR_JSON, FloorInfo.class);
    var plane = gson.fromJson("{\"PlaneID\":199949,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    var old = GameData.getFloorInfos().put("P199949_F19994901", floor);
    try {
      assertThrows(IllegalArgumentException.class, () -> new Scene(new Player(), plane, FLOOR, 5));
      assertThrows(IllegalArgumentException.class, () -> new Scene(new Player(), plane, FLOOR, -1));
    } finally {
      if (old == null) GameData.getFloorInfos().remove("P199949_F19994901");
      else GameData.getFloorInfos().put("P199949_F19994901", old);
    }
  }

  @Test
  void selectedDimensionDefaultsAppearInTheSceneWithoutChangingTheSharedMainDefaults() {
    var floor =
        gson.fromJson(
            FLOOR_JSON
                .replace(
                    "\"ID\":0,\"GroupIndexList\":[0]",
                    "\"ID\":0,\"GroupIndexList\":[0],\"SavedValues\":[{\"Name\":\"Flag\",\"DefaultValue\":1},{\"Name\":\"MainOnly\",\"DefaultValue\":7}]")
                .replace(
                    "\"ID\":4,\"GroupIndexList\":[1,0,1]",
                    "\"ID\":4,\"GroupIndexList\":[1],\"SavedValues\":[{\"Name\":\"Flag\",\"DefaultValue\":2},{\"Name\":\"StoryOnly\",\"DefaultValue\":8}]"),
            FloorInfo.class);
    var plane = gson.fromJson("{\"PlaneID\":199949,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    var player =
        new Player() {
          final PlayerLineup lineup = new PlayerLineup(this, 0);

          {
            lineup.getAvatars().add(1);
          }

          public PlayerLineup getCurrentLineup() {
            return lineup;
          }

          public void save() {}
        };
    var old = GameData.getFloorInfos().put("P199949_F19994901", floor);
    try {
      var story = new Scene(player, plane, FLOOR, 4).toProto();
      var main = new Scene(player, plane, FLOOR).toProto();
      var storyValues = new HashMap<String, Integer>();
      var mainValues = new HashMap<String, Integer>();
      for (var value : story.getFloorSavedValueMap())
        storyValues.put(value.getKey(), value.getValue());
      for (var value : main.getFloorSavedValueMap())
        mainValues.put(value.getKey(), value.getValue());
      assertEquals(Map.of("Flag", 2, "StoryOnly", 8), storyValues);
      assertEquals(Map.of("Flag", 1, "MainOnly", 7), mainValues);
      assertEquals(1, floor.getExtraDatas().getFirst().getDefaultValue());
    } finally {
      if (old == null) GameData.getFloorInfos().remove("P199949_F19994901");
      else GameData.getFloorInfos().put("P199949_F19994901", old);
    }
  }

  @Test
  void floorsWithoutDimensionMetadataRetainTheirMainSceneButDoNotInventOtherDimensions() {
    var floor = gson.fromJson("{\"FloorID\":19994901}", FloorInfo.class);
    assertTrue(floor.isGroupInDimension(8, 0));
    assertFalse(floor.isGroupInDimension(8, 4));
    assertTrue(floor.getReferencedGroupIndices().isEmpty());
  }
}
