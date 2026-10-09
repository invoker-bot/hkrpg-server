package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import java.util.*;
import org.junit.jupiter.api.*;

class StoryGroupIgnoreVerseTest {
  static final int MAIN = 19997061, SUB = 199970611, PLANE = 1999706, FLOOR = 199970601;
  final GameplayProgressTest fixture = new GameplayProgressTest();

  @AfterEach
  void cleanup() {
    fixture.reset();
    GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    GameData.getMissionInfos().remove(MAIN);
    GameData.getFloorInfos().remove("P" + PLANE + "_F" + FLOOR);
  }

  void resources(String ignores) {
    var floor = fixture.gson.fromJson("{\"FloorID\":" + FLOOR + "}", FloorInfo.class);
    var group =
        fixture.gson.fromJson(
            "{\"GroupPropertyMap\":{\"1\":{\"Name\":\"PuzzleState\",\"DefaultValue\":0}}}",
            GroupInfo.class);
    group.setId(202);
    floor.getGroups().put(202, group);
    GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR, floor);
    fixture.mission(
        MAIN,
        "Manual",
        Integer.toString(SUB),
        "[{\"ID\":"
            + SUB
            + ",\"MainMissionID\":"
            + MAIN
            + ",\"TakeType\":\"Auto\","
            + "\"LevelPlaneID\":"
            + PLANE
            + ",\"LevelFloorID\":"
            + FLOOR
            + ",\"MazePlaneID\":"
            + PLANE
            + ",\"MazeFloorID\":"
            + FLOOR
            + ",\"FinishType\":\"GroupPropertyValueIgnoreVerse\",\"ParamType\":\"Equal\",\"ParamInt1\":202,\"ParamInt2\":3,"
            + "\"ParamStr1\":\"PuzzleState\",\"IgnoreVerseParamList\":"
            + ignores
            + "}]");
  }

  GameplayProgressTest.ScenePlayer player() {
    var p = new GameplayProgressTest.ScenePlayer();
    p.scenePlane = PLANE;
    p.sceneFloor = FLOOR;
    p.getMissionManager().accept(MAIN);
    return p;
  }

  String key() {
    return "group:" + FLOOR + ":202:PuzzleState";
  }

  @Test
  void savedSharedPuzzleStateCompletesAnAcceptedExplicitlyIgnoredTarget() {
    resources("[\"StoryLine\",\"Content\"]");
    var p = player();
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    p.getGameplay().getStoryValues().put(key(), 3);
    p.getMissionManager().refresh();
    assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void ignoreParameterOrderDoesNotChangeTheSharedPropertyMeaning() {
    resources("[\"Content\",\"StoryLine\"]");
    var p = player();
    p.getGameplay().getStoryValues().put(key(), 3);
    p.getMissionManager().refresh();
    assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void merelyEnteringOrQueryingDoesNotFabricateACompletedPuzzleValue() {
    resources("[\"StoryLine\",\"Content\"]");
    var p = player();
    for (int i = 0; i < 3; i++) p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    assertFalse(p.getGameplay().getStoryValues().containsKey(key()));
  }

  @Test
  void unknownPartialOrDuplicateIgnoreRequirementsDoNotBorrowSharedState() {
    for (String ignores :
        List.of(
            "[]",
            "[\"StoryLine\"]",
            "[\"Content\"]",
            "[\"StoryLine\",\"Unknown\"]",
            "[\"StoryLine\",\"StoryLine\"]",
            "[\"StoryLine\",\"Content\",\"Content\"]",
            "[null,\"Content\"]",
            "null")) {
      resources(ignores);
      var p = player();
      p.getGameplay().getStoryValues().put(key(), 3);
      p.getMissionManager().refresh();
      assertEquals(0, p.getMissionManager().sub(SUB).getProgress(), ignores);
    }
  }

  @Test
  void savedValueWithoutItsExactResourceGroupAndPropertyCannotComplete() {
    for (int situation = 0; situation < 4; situation++) {
      resources("[\"StoryLine\",\"Content\"]");
      var p = player();
      p.getGameplay().getStoryValues().put(key(), 3);
      var floor = GameData.getFloorInfo(PLANE, FLOOR);
      switch (situation) {
        case 0 -> floor.getGroups().remove(202);
        case 1 -> floor.getGroups().put(202, fixture.gson.fromJson("{}", GroupInfo.class));
        case 2 -> floor.getGroups().get(202).getGroupPropertyMap().clear();
        case 3 ->
            floor
                .getGroups()
                .get(202)
                .getGroupPropertyMap()
                .put(
                    2,
                    fixture.gson.fromJson(
                        "{\"Name\":\"PuzzleState\"}", GroupInfo.GroupProperty.class));
      }
      p.getMissionManager().refresh();
      assertEquals(0, p.getMissionManager().sub(SUB).getProgress(), "situation=" + situation);
    }
  }

  @Test
  void wrongPlaneFloorAndTransitionPendingKeepTheObjectivePending() {
    resources("[\"StoryLine\",\"Content\"]");
    var p = player();
    p.getGameplay().getStoryValues().put(key(), 3);
    p.scenePlane = PLANE + 1;
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    p.scenePlane = PLANE;
    p.sceneFloor = FLOOR + 1;
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    p.sceneFloor = FLOOR;
    var place =
        new StoryLineState.Place(
            PLANE,
            FLOOR,
            0,
            0,
            new emu.lunarcore.util.Position(),
            new emu.lunarcore.util.Position());
    p.getGameplay().getStoryLines().begin(1999706, place, place);
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    p.getGameplay().getStoryLines().arrived();
    p.getMissionManager().refresh();
    assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void aDistinctMazeTargetIsNotSilentlyReplacedByTheLevelFloor() {
    resources("[\"StoryLine\",\"Content\"]");
    GameplayDefinitions.subs.get(SUB).setMazeFloorID(FLOOR + 1);
    var p = player();
    p.getGameplay().getStoryValues().put(key(), 3);
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    GameplayDefinitions.subs.get(SUB).setMazeFloorID(FLOOR);
    p.getMissionManager().refresh();
    assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void additionalStoryRestrictionsAndUnknownComparisonsStayPending() {
    for (String extra :
        List.of(
            "\"ParamType\":\"GreaterEqual\"",
            "\"Progress\":2",
            "\"ParamInt3\":1",
            "\"ParamIntList\":[1]",
            "\"StoryLineIDList\":[1]",
            "\"ParamItemList\":[{\"ItemID\":1,\"ItemNum\":1}]",
            "\"ParamStr1\":null")) {
      resources("[\"StoryLine\",\"Content\"]");
      var data = fixture.gson.toJsonTree(GameplayDefinitions.subs.get(SUB)).getAsJsonObject();
      fixture
          .gson
          .fromJson("{" + extra + "}", com.google.gson.JsonObject.class)
          .entrySet()
          .forEach(e -> data.add(e.getKey(), e.getValue()));
      GameplayDefinitions.subs.put(
          SUB, fixture.gson.fromJson(data, MissionInfo.SubMissionInfo.class));
      var p = player();
      p.getGameplay().getStoryValues().put(key(), 3);
      p.getMissionManager().refresh();
      assertEquals(0, p.getMissionManager().sub(SUB).getProgress(), extra);
    }
  }

  @Test
  void concurrentRefreshDoesNotRepeatThePuzzleCompletionAction() throws Exception {
    resources("[\"StoryLine\",\"Content\"]");
    GameplayDefinitions.subs
        .get(SUB)
        .setFinishActionList(
            fixture.gson.fromJson(
                "[{\"FinishActionType\":\"AddCustomValue\",\"FinishActionParaString\":[\"PuzzleDone\",\"1\"]}]",
                MissionInfo.FinishAction[].class));
    var p = player();
    p.getGameplay().getStoryValues().put(key(), 3);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
      for (int i = 0; i < 6; i++)
        tasks.add(
            () -> {
              p.getMissionManager().refresh();
              return null;
            });
      for (var result : executor.invokeAll(tasks))
        result.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
    assertEquals(1, p.getGameplay().getStoryValues().get("mission:" + MAIN + ":PuzzleDone"));
  }

  @Test
  void actualMorphiaReloadKeepsTheRecordedPuzzleValueAndFinishedActions() throws Exception {
    resources("[\"StoryLine\",\"Content\"]");
    var p = player();
    p.getGameplay().getStoryValues().put(key(), 3);
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "shared-story-puzzle");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      var state = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      uid.setAccessible(true);
      state.setAccessible(true);
      uid.setInt(document, 123456861);
      for (int i = 0; i < 2; i++) {
        state.set(document, p.getGameplay());
        store.save(document);
        state.set(p, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
        p.getMissionManager().refresh();
        assertEquals(3, p.getGameplay().getStoryValues().get(key()));
        assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void anActualScenePropertyWriteCompletesTheTargetWithoutChangingSharedResourceDefaults() {
    resources("[\"StoryLine\",\"Content\"]");
    var p =
        new MissionFarmingBattleTest.Farmer() {
          emu.lunarcore.game.scene.Scene current;

          public int getPlaneId() {
            return PLANE;
          }

          public int getFloorId() {
            return FLOOR;
          }

          public emu.lunarcore.game.scene.Scene getScene() {
            return current;
          }
        };
    var plane =
        fixture.gson.fromJson(
            "{\"PlaneID\":" + PLANE + "}", emu.lunarcore.data.excel.MazePlaneExcel.class);
    p.current = new emu.lunarcore.game.scene.Scene(p, plane, FLOOR);
    p.current.getLoadedGroups().add(202);
    p.current.getGroups().put(202, new emu.lunarcore.game.scene.SceneGroup(202));
    p.getMissionManager().accept(MAIN);
    assertTrue(p.current.setStoryGroupProperty(202, "PuzzleState", 3));
    p.getMissionManager().refresh();
    assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
    assertEquals(3, p.getGameplay().getStoryValues().get(key()));
    assertEquals(
        0,
        GameData.getFloorInfo(PLANE, FLOOR)
            .getGroups()
            .get(202)
            .getGroupPropertyMap()
            .get(1)
            .getDefaultValue());
  }
}
