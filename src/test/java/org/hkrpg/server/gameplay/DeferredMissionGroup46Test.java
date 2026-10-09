package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original Herta group; mission completion histories and the isolated floor map are fixtures. */
class DeferredMissionGroup46Test {
  final DailyStageWin46Test source = new DailyStageWin46Test();
  final Gson gson = emu.lunarcore.data.ResourceLoader.getGson();
  final List<NpcExcel> oldNpcs = new ArrayList<>();
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  final MissionGroupConditions46Test nativeViews = new MissionGroupConditions46Test();
  @TempDir Path temp;
  GroupInfo group;
  FloorInfo floor;
  MazePlaneExcel plane;
  JsonObject rawGroup;

  DailyStageWin46Test.Actor actor() {
    return source.actor();
  }

  @BeforeEach
  void setup() throws Exception {
    source.temp = temp;
    source.setup();
    oldNpcs.addAll(GameData.getNpcExcelMap().values());
    oldFloors.putAll(GameData.getFloorInfos());
    try (var reader =
        new java.io.InputStreamReader(
            getClass().getResourceAsStream("/fixtures/deferred-mission-group-4.6.json"),
            java.nio.charset.StandardCharsets.UTF_8)) {
      var rows = JsonParser.parseReader(reader).getAsJsonArray();
      assertEquals(6, rows.size());
      for (var raw : rows) {
        var entry = raw.getAsJsonObject();
        var row = entry.getAsJsonObject("row");
        switch (entry.get("kind").getAsString()) {
          case "group" -> {
            rawGroup = row;
            group = gson.fromJson(row, GroupInfo.class);
            group.setId(2);
          }
          case "floor" -> floor = gson.fromJson(row, FloorInfo.class);
          case "plane" -> plane = gson.fromJson(row, MazePlaneExcel.class);
          case "npc" -> GameData.getNpcExcelMap().add(gson.fromJson(row, NpcExcel.class));
          case "main" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          default -> fail("Unexpected source kind");
        }
      }
    }
    assertTrue(
        rawGroup.getAsJsonObject("UnloadCondition").get("DelayToLevelReload").getAsBoolean());
    actor().floor = 80112001;
    actor().getCurrentLineup().getAvatars().add(0);
    floor.getGroups().put(2, group);
    GameData.getFloorInfos().put("P80112_F80112001", floor);
  }

  @AfterEach
  void cleanup() {
    source.cleanup();
    GameData.getNpcExcelMap().values().clear();
    oldNpcs.forEach(GameData.getNpcExcelMap()::add);
    GameData.getFloorInfos().clear();
    GameData.getFloorInfos().putAll(oldFloors);
  }

  Scene scene() throws Exception {
    var value = new Scene(actor(), plane, 80112001);
    DailyMissionAcceptance46Test.set(actor(), "scene", value);
    return value;
  }

  void finished(int id) {
    actor().getGameplay().getMainMissions().put(id, DailyMissionAcceptance46Test.closed(id));
  }

  List<Integer> npcs(Scene scene) {
    return scene.getEntities(EntityNpc.class).stream().map(EntityNpc::getNpcId).toList();
  }

  @Test
  void originalNpcWaitsForTheAuthoredLoadCompletionProof() throws Exception {
    assertTrue(npcs(scene()).isEmpty());
    finished(1000400);
    assertEquals(List.of(1013), npcs(scene()));
  }

  @Test
  void aCompletedUnloadConditionSuppressesTheNpcWhenBuildingANewScene() throws Exception {
    finished(1000400);
    finished(1000401);
    assertTrue(npcs(scene()).isEmpty());
  }

  @Test
  void finishingTheUnloadMissionKeepsTheNpcInTheAlreadyLoadedScene() throws Exception {
    finished(1000400);
    var current = scene();
    assertEquals(List.of(1013), npcs(current));
    finished(1000401);
    current.refreshMissionGroups();
    assertEquals(List.of(1013), npcs(current));
    assertTrue(current.getLoadedGroups().contains(2));
  }

  @Test
  void deferredNpcDisappearsOnlyWhenTheFloorIsRebuilt() throws Exception {
    finished(1000400);
    var current = scene();
    finished(1000401);
    current.refreshMissionGroups();
    assertEquals(List.of(1013), npcs(current));
    var next = scene();
    assertTrue(npcs(next).isEmpty());
    assertFalse(next.getLoadedGroups().contains(2));
  }

  @Test
  void frozenSceneFieldsRetainTheDeferredNpcThenOmitItOnTheNextEntry() throws Exception {
    finished(1000400);
    var current = scene();
    nativeViews.assertNativeNpcs(current, List.of(1013));
    finished(1000401);
    current.refreshMissionGroups();
    nativeViews.assertNativeNpcs(current, List.of(1013));
    nativeViews.assertNativeNpcs(scene(), List.of());
  }

  @ParameterizedTest
  @ValueSource(strings = {"id", "zero", "overGoal", "time", "doingWithTime", "phase", "cancelled"})
  void corruptedOrCancelledLoadProofDoesNotSpawnTheOriginalNpc(String field) throws Exception {
    finished(1000400);
    var state = actor().getGameplay().getMainMissions().get(1000400);
    switch (field) {
      case "id" -> DailyMissionAcceptance46Test.set(state, "questId", 1000401);
      case "zero" -> DailyMissionAcceptance46Test.set(state, "progress", 0);
      case "overGoal" -> DailyMissionAcceptance46Test.set(state, "progress", 2);
      case "time" -> DailyMissionAcceptance46Test.set(state, "finishTime", 0L);
      case "doingWithTime" ->
          DailyMissionAcceptance46Test.set(state, "phase", QuestState.Phase.DOING);
      case "phase" -> DailyMissionAcceptance46Test.set(state, "phase", null);
      case "cancelled" -> actor().getGameplay().getCancelledMissions().add(1000400);
      default -> fail(field);
    }
    assertDoesNotThrow(() -> assertTrue(npcs(scene()).isEmpty()));
  }

  @Test
  void theCurrentSceneCanLoadTheNpcOnceItsOriginalPrerequisiteFinishes() throws Exception {
    var current = scene();
    assertTrue(npcs(current).isEmpty());
    finished(1000400);
    current.refreshMissionGroups();
    assertEquals(List.of(1013), npcs(current));
  }

  @Test
  void repeatedRefreshesKeepTheSameDeferredActorWithoutRespawningIt() throws Exception {
    finished(1000400);
    var current = scene();
    var original = current.getEntities(EntityNpc.class).getFirst();
    int id = original.getEntityId();
    finished(1000401);
    for (int i = 0; i < 12; i++) {
      current.refreshMissionGroups();
      assertEquals(1, current.getEntities(EntityNpc.class).size());
      assertSame(original, current.getEntities(EntityNpc.class).getFirst());
      assertEquals(id, original.getEntityId());
    }
  }

  @Test
  void aMissionOwnerFinishingStillOverridesADelayedUnload() throws Exception {
    finished(1000400);
    actor().getGameplay().getMainMissions().put(1000401, new QuestState(1000401));
    DailyMissionAcceptance46Test.set(group, "Category", GroupInfo.GroupCategory.Mission);
    DailyMissionAcceptance46Test.set(group, "OwnerMainMissionID", 1000401);
    var current = scene();
    assertEquals(List.of(1013), npcs(current));
    finished(1000401);
    current.refreshMissionGroups();
    assertTrue(npcs(current).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknownPhase", "empty", "unknownOperation"})
  void anUnknownDeferredUnloadCannotAuthorizeAFreshSceneActor(String kind) throws Exception {
    finished(1000400);
    String json =
        switch (kind) {
          case "unknownPhase" ->
              "{\"DelayToLevelReload\":true,\"Conditions\":[{\"ID\":1000401,\"Phase\":\"unknown\"}]}";
          case "empty" -> "{\"DelayToLevelReload\":true,\"Conditions\":[]}";
          case "unknownOperation" ->
              "{\"DelayToLevelReload\":true,\"Operation\":\"unknown\",\"Conditions\":[{\"ID\":1000401,\"Phase\":\"Finish\"}]}";
          default -> throw new IllegalArgumentException();
        };
    DailyMissionAcceptance46Test.set(
        group, "UnloadCondition", gson.fromJson(json, LoadConditionInfo.class));
    assertDoesNotThrow(() -> assertTrue(npcs(scene()).isEmpty()));
  }

  @Test
  void twoActualPlayerReloadsRebuildTheFloorWithoutTheDeferredNpc() throws Exception {
    finished(1000400);
    var current = scene();
    finished(1000401);
    current.refreshMissionGroups();
    assertEquals(List.of(1013), npcs(current));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "deferred_group");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, QuestState.class);
      for (int i = 0; i < 2; i++) {
        var base = new emu.lunarcore.game.player.Player();
        DailyMissionAcceptance46Test.set(base, "uid", 123456802);
        DailyMissionAcceptance46Test.set(base, "gameplay", actor().getGameplay());
        store.save(base);
        var restored =
            store
                .find(emu.lunarcore.game.player.Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", 123456802))
                .first();
        assertNotNull(restored);
        var value = new DailyStageWin46Test.Actor();
        assertTrue(value.setNickname("Restored deferred fixture"));
        value.floor = 80112001;
        value.getCurrentLineup().getAvatars().add(0);
        DailyMissionAcceptance46Test.set(value, "uid", 123456802);
        DailyMissionAcceptance46Test.set(value, "gameplay", restored.getGameplay());
        source.assigned.repeated.fixture.actor = value;
        source.clock(DailyMissionRepeat46Test.FIRST_DAY);
        assertTrue(actor().getMissionManager().finished(1000400));
        assertTrue(actor().getMissionManager().finished(1000401));
        var rebuilt = scene();
        assertTrue(npcs(rebuilt).isEmpty());
        nativeViews.assertNativeNpcs(rebuilt, List.of());
      }
    } finally {
      server.shutdownNow();
    }
  }
}
