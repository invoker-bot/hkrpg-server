package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original world props and presentation scripts; prerequisite histories and location are fixtures.
 */
class WorldChestActivity46Test {
  @TempDir Path root;
  final Gson gson = ResourceLoader.getGson();
  final JsonObject source =
      JsonParser.parseReader(
              new java.io.InputStreamReader(
                  Objects.requireNonNull(
                      getClass().getResourceAsStream("/fixtures/world-chest-4.6.json")),
                  StandardCharsets.UTF_8))
          .getAsJsonObject();
  final Map<ResourceMap, List<ResourceBase>> previous = new IdentityHashMap<>();
  final Map<String, FloorInfo> previousFloors = new HashMap<>();
  final List<GameplayDefinitions.Window> oldWindows = new ArrayList<>();
  String previousRoot;
  Actor player;

  static class Actor extends MissionFarmingBattleTest.Farmer {
    Scene scene;
    boolean failSave;
    int failOnSave = -1;
    boolean failGrant;
    final Inventory chestInventory =
        new Inventory(this) {
          public GrantResult grantItems(ItemParamMap items, boolean hint) {
            grants++;
            return new GrantResult(failGrant ? List.of() : items.toItemList(), !failGrant);
          }
        };

    public Inventory getInventory() {
      return chestInventory;
    }

    GameplayState saved;
    int saves;
    int plane = 20411, floor = 20411001, entry = 2041101;

    public Scene getScene() {
      return scene;
    }

    public int getPlaneId() {
      return plane;
    }

    public int getFloorId() {
      return floor;
    }

    public int getEntryId() {
      return entry;
    }

    public int getWorldId() {
      return 501;
    }

    public void save() {
      if (failSave || saves + 1 == failOnSave)
        throw new IllegalStateException("injected save failure");
      saves++;
      saved = new Gson().fromJson(new Gson().toJson(getGameplay()), GameplayState.class);
    }
  }

  record Table(ResourceMap map, Class<? extends ResourceBase> type) {}

  @SuppressWarnings("unchecked")
  static Collection<ResourceBase> values(ResourceMap map) {
    return map instanceof ExcelMap excel ? excel.values() : ((MultiKeyExcelMap) map).values();
  }

  Table table(String name) {
    return switch (name) {
      case "QuestData" -> new Table(GameData.getQuestExcelMap(), QuestExcel.class);
      case "FinishWay" -> new Table(GameData.getFinishWayExcelMap(), FinishWayExcel.class);
      case "RewardData" -> new Table(GameData.getRewardExcelMap(), RewardExcel.class);
      case "ItemConfig" -> new Table(GameData.getItemExcelMap(), ItemExcel.class);
      case "ActivityPanel" ->
          new Table(GameData.getActivityPanelExcelMap(), ActivityPanelExcel.class);
      case "MainMission" -> new Table(GameData.getMainMissionExcelMap(), MainMissionExcel.class);
      case "MazeProp" -> new Table(GameData.getPropExcelMap(), PropExcel.class);
      case "StageConfig" -> new Table(GameData.getStageExcelMap(), StageExcel.class);
      case "MazePlane" -> new Table(GameData.getMazePlaneExcelMap(), MazePlaneExcel.class);
      case "MapEntrance" -> new Table(GameData.getMapEntranceExcelMap(), MapEntranceExcel.class);
      case "NPCMonsterData" -> new Table(GameData.getNpcMonsterExcelMap(), NpcMonsterExcel.class);
      case "PlaneEvent" -> new Table(GameData.getPlaneEventExcelMap(), PlaneEventExcel.class);
      case "InteractConfig" -> new Table(GameData.getInteractExcelMap(), InteractExcel.class);
      default -> null;
    };
  }

  void write(String path, JsonElement value) throws Exception {
    Path f = root.resolve(path);
    Files.createDirectories(f.getParent());
    Files.writeString(f, gson.toJson(value));
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void load() throws Exception {
    previousRoot = LunarCore.getConfig().getResourceDir();
    var f = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    f.setAccessible(true);
    f.set(LunarCore.getConfig(), root.toString());
    oldWindows.addAll(GameplayDefinitions.windows);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(30013, 3001301, 1, Long.MAX_VALUE));
    for (var e : source.getAsJsonObject("records").entrySet()) {
      write(e.getKey(), e.getValue());
      var t = table(Path.of(e.getKey()).getFileName().toString().replace(".json", ""));
      if (t == null) continue;
      previous.put(t.map(), new ArrayList<>(values(t.map())));
      values(t.map()).clear();
      for (var r : e.getValue().getAsJsonArray()) {
        var decoded = r.deepCopy();
        if (t.type() == PropExcel.class
            && decoded.getAsJsonObject().has("PropName")
            && decoded.getAsJsonObject().get("PropName").isJsonObject())
          decoded
              .getAsJsonObject()
              .addProperty(
                  "PropName",
                  decoded.getAsJsonObject().getAsJsonObject("PropName").get("Hash").getAsLong());
        var v = gson.fromJson(decoded, t.type());
        v.onLoad();
        t.map().add(v);
      }
    }
    for (var e : source.getAsJsonObject("groups").entrySet()) write(e.getKey(), e.getValue());
    for (var e : source.getAsJsonObject("floors").entrySet()) {
      write(e.getKey(), e.getValue());
      String key = Path.of(e.getKey()).getFileName().toString().replace(".json", "");
      var fi = gson.fromJson(e.getValue(), FloorInfo.class);
      for (var g : source.getAsJsonObject("groups").entrySet())
        if (g.getKey().contains("Groups_" + key + "/")) {
          var gi = gson.fromJson(g.getValue(), GroupInfo.class);
          int id = Integer.parseInt(g.getKey().replaceAll(".*_G([0-9]+)\\.json", "$1"));
          gi.setId(id);
          fi.getGroups().put(id, gi);
        }
      fi.onLoad();
      previousFloors.put(key, GameData.getFloorInfos().get(key));
      GameData.getFloorInfos().put(key, fi);
    }
    player = new Actor();
    player.setLevel(21);
    for (int id : new int[] {1000510}) {
      var q = new QuestState(id);
      q.advance(1, 1, 1);
      q.reserveReward();
      q.closeReward();
      player.getGameplay().getMainMissions().put(id, q);
    }
    for (var e : source.getAsJsonObject("headers").entrySet()) write(e.getKey(), e.getValue());
    scene();
  }

  void scene() {
    player.scene =
        new Scene(player, GameData.getMazePlaneExcelMap().get(player.plane), player.floor);
    player.scene.setEntryId(player.entry);
  }

  @AfterEach
  @SuppressWarnings("unchecked")
  void restore() throws Exception {
    previous.forEach(
        (m, rows) -> {
          values(m).clear();
          rows.forEach(m::add);
        });
    previousFloors.forEach(
        (k, v) -> {
          if (v == null) GameData.getFloorInfos().remove(k);
          else GameData.getFloorInfos().put(k, v);
        });
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(oldWindows);
    var f = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    f.setAccessible(true);
    f.set(LunarCore.getConfig(), previousRoot);
  }

  QuestState state(int id) {
    return player.getQuestManager().states().stream()
        .filter(q -> q.getQuestId() == id)
        .findFirst()
        .orElse(null);
  }

  int progress(int id) {
    var q = state(id);
    return q == null ? 0 : q.getProgress();
  }

  EntityProp chest(int id) {
    var row = source.getAsJsonObject("locations").getAsJsonObject(String.valueOf(id));
    var matches =
        player.scene.getEntitiesByGroup(EntityProp.class, row.get("group").getAsInt()).stream()
            .filter(p -> p.getInstId() == row.get("instance").getAsInt())
            .toList();
    assertEquals(1, matches.size(), "Original treasure actor not loaded");
    return matches.getFirst();
  }

  @ParameterizedTest
  @ValueSource(ints = {6029850, 6029851, 6029852})
  void originalNonperiodicWorldChestGoalsAreAcceptedAtZero(int id) {
    var q = state(id);
    assertNotNull(q, "Original world-chest activity missing");
    assertEquals(0, q.getProgress());
    assertEquals(QuestState.Phase.DOING, q.getPhase());
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        20411601, 20411602, 20411603, 20411604, 20411605, 20411606, 20411607, 20411608, 20411609,
        20411610, 20411611, 20411612, 20411613, 20411615, 20411616, 20411617, 20411618, 20411619,
        20411620, 20411621, 20411622, 20411623, 20411624, 20411626
      })
  void originalChestOpensAndCreditsEachAcceptedWorldGoalOnlyOnce(int id) {
    player.getQuestManager().refreshUnlocks();
    var prop = chest(id);
    assertEquals(emu.lunarcore.game.enums.PropState.ChestClosed, prop.getState());
    assertEquals(
        Retcode.SUCC, SceneInteractions.validateNative(player, 112131, prop.getEntityId()));
    assertSame(prop, player.interactWithProp(112131, prop.getEntityId()));
    assertEquals(emu.lunarcore.game.enums.PropState.ChestUsed, prop.getState());
    for (int goal : new int[] {6029850, 6029851, 6029852}) assertEquals(1, progress(goal));
    assertNull(player.interactWithProp(112131, prop.getEntityId()));
    for (int goal : new int[] {6029850, 6029851, 6029852}) assertEquals(1, progress(goal));
  }

  @Test
  void sourceLockedStateCannotBeReportedAsAnOpenChest() {
    var prop = chest(20411601);
    prop.setState(emu.lunarcore.game.enums.PropState.ChestLocked, false);
    assertNotEquals(
        Retcode.SUCC, SceneInteractions.validateNative(player, 112131, prop.getEntityId()));
    assertNull(player.interactWithProp(112131, prop.getEntityId()));
    assertEquals(0, progress(6029850));
  }

  @Test
  void interruptedInventoryDeliveryDoesNotPublishAndBlocksOtherChestOpenings() {
    player.getQuestManager().refreshUnlocks();
    player.failGrant = true;
    var first = chest(20411601);
    assertEquals(
        Retcode.PLAYER_DATA_ERROR, WorldChests.open(player, 112131, first.getEntityId()).retcode());
    assertEquals(emu.lunarcore.game.enums.PropState.ChestClosed, first.getState());
    assertEquals(0, progress(6029850));
    player.failGrant = false;
    var second = chest(20411602);
    assertEquals(
        Retcode.SYSTEM_BUSY, WorldChests.open(player, 112131, second.getEntityId()).retcode());
    assertEquals(1, player.grants);
  }

  @Test
  void failedReservationChangesNeitherInventoryNorWorldAndTheRequestCanRetry() {
    assertNotNull(state(6029850));
    var original = player.getGameplay().getWorldChests();
    var prop = chest(20411601);
    player.failSave = true;
    assertEquals(
        Retcode.SYSTEM_BUSY, WorldChests.open(player, 112131, prop.getEntityId()).retcode());
    assertSame(original, player.getGameplay().getWorldChests());
    assertTrue(original.getRecords().isEmpty());
    assertTrue(player.getGameplay().getRewards().isEmpty());
    assertEquals(0, player.grants);
    assertEquals(emu.lunarcore.game.enums.PropState.ChestClosed, prop.getState());
    player.failSave = false;
    assertEquals(Retcode.SUCC, WorldChests.open(player, 112131, prop.getEntityId()).retcode());
    assertEquals(1, player.grants);
    assertEquals(1, progress(6029850));
  }

  @Test
  void failedFinalSaveLeavesPendingDeliveryWithoutPublishingOrRepeatingTheGrant() {
    assertNotNull(state(6029850));
    var prop = chest(20411601);
    player.failOnSave = player.saves + 2;
    assertEquals(
        Retcode.SYSTEM_BUSY, WorldChests.open(player, 112131, prop.getEntityId()).retcode());
    assertEquals(1, player.grants);
    assertEquals(0, player.getGameplay().getWorldChests().getQuests().get(6029850).getProgress());
    assertEquals(emu.lunarcore.game.enums.PropState.ChestClosed, prop.getState());
    assertEquals(
        Retcode.SYSTEM_BUSY, WorldChests.open(player, 112131, prop.getEntityId()).retcode());
    assertEquals(1, player.grants);
  }

  @Test
  void originalCountsStopAtFiveTenAndFifteenWhileOtherChestsStillOpen() {
    player.getQuestManager().refreshUnlocks();
    for (var key : source.getAsJsonObject("locations").keySet()) {
      var prop = chest(Integer.parseInt(key));
      assertEquals(Retcode.SUCC, WorldChests.open(player, 112131, prop.getEntityId()).retcode());
    }
    assertEquals(24, player.grants);
    for (int id : new int[] {6029850, 6029851, 6029852}) {
      assertEquals(WorldChestState.goal(id), progress(id));
      assertTrue(QuestCompletionRecords.completed(player, id));
    }
  }

  @Test
  void serializationAndSceneReconstructionKeepTheChestUsed() {
    player.getQuestManager().refreshUnlocks();
    var prop = chest(20411601);
    assertEquals(Retcode.SUCC, WorldChests.open(player, 112131, prop.getEntityId()).retcode());
    player
        .getGameplay()
        .worldChests(
            gson.fromJson(
                gson.toJson(player.getGameplay().getWorldChests()), WorldChestState.class));
    scene();
    assertEquals(emu.lunarcore.game.enums.PropState.ChestUsed, chest(20411601).getState());
    assertNull(player.interactWithProp(112131, chest(20411601).getEntityId()));
    assertEquals(1, progress(6029850));
    assertEquals(1, player.grants);
  }

  @Test
  void earlierOpeningsAreNeverAddedToLaterActivityAcceptance() {
    var first = chest(20411601);
    assertEquals(Retcode.SUCC, WorldChests.open(player, 112131, first.getEntityId()).retcode());
    assertEquals(0, progress(6029850));
    assertTrue(
        player
            .getGameplay()
            .getWorldChests()
            .getRecords()
            .get(20411601)
            .getAcceptances()
            .isEmpty());
    var second = chest(20411602);
    assertEquals(Retcode.SUCC, WorldChests.open(player, 112131, second.getEntityId()).retcode());
    assertEquals(1, progress(6029850));
  }

  @Test
  void changedRawChestIdentityCannotBeHiddenByTheTypedPropModel() throws Exception {
    String path =
        source.getAsJsonObject("locations").getAsJsonObject("20411601").get("path").getAsString();
    var raw = JsonParser.parseString(Files.readString(root.resolve(path))).getAsJsonObject();
    raw.getAsJsonArray("PropList").get(0).getAsJsonObject().addProperty("ChestID", 20411602);
    write(path, raw);
    var prop = chest(20411601);
    assertNotEquals(Retcode.SUCC, WorldChests.open(player, 112131, prop.getEntityId()).retcode());
    assertEquals(0, player.grants);
  }

  @Test
  void unrelatedLegacyCompletionCannotClaimTheOwnedActivityReward() {
    var legacy = new QuestState(6029850);
    legacy.advance(5, 5, 1);
    player.getQuestStates().put(6029850, legacy);
    assertEquals(0, progress(6029850));
    assertFalse(QuestCompletionRecords.completed(player, 6029850));
    assertNotEquals(Retcode.SUCC, player.getQuestManager().claim(6029850).retcode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "chest", "loot", "phase", "acceptance", "source"})
  void damagedOpeningRecordsCannotCountRestoreOrRegrant(String field) {
    player.getQuestManager().refreshUnlocks();
    var prop = chest(20411601);
    assertEquals(Retcode.SUCC, WorldChests.open(player, 112131, prop.getEntityId()).retcode());
    var raw = gson.toJsonTree(player.getGameplay().getWorldChests()).getAsJsonObject();
    var record = raw.getAsJsonObject("records").getAsJsonObject("20411601");
    switch (field) {
      case "version" -> raw.addProperty("version", 2);
      case "chest" -> record.addProperty("chest", 20411602);
      case "loot" -> record.getAsJsonObject("loot").addProperty("1", 999);
      case "phase" -> record.addProperty("phase", "PREPARED");
      case "acceptance" ->
          record
              .getAsJsonObject("acceptances")
              .addProperty("6029850", UUID.randomUUID().toString());
      case "source" -> record.addProperty("groupSource", "unverified");
    }
    player.getGameplay().worldChests(gson.fromJson(raw, WorldChestState.class));
    assertFalse(QuestCompletionRecords.completed(player, 6029850));
    scene();
    assertEquals(emu.lunarcore.game.enums.PropState.ChestClosed, chest(20411601).getState());
    assertNotEquals(
        Retcode.SUCC, WorldChests.open(player, 112131, chest(20411601).getEntityId()).retcode());
    assertEquals(1, player.grants);
  }
}
