package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original goals, roster and source maps; prerequisite histories and the party are fixtures. */
class ActivityKillQuests46Test {
  @Test void lockedActivityDoesNotScanItsBattleSourcesDuringLoginRefresh() throws Exception {
    assertTrue(ActivityAccess.unlocked(player, 30013));
    player.getGameplay().getMainMissions().clear();
    assertFalse(ActivityAccess.unlocked(player, 30013));
    int saves = player.saves;
    var reads = org.hkrpg.server.SourceReadAssertions.reads(root, () -> {
      assertFalse(ActivityKillQuests.refresh(player));
      assertTrue(ActivityKillQuests.states(player).isEmpty());
    });
    assertTrue(reads.isEmpty(), "Locked activity scanned resources: " + reads);
    assertEquals(saves, player.saves);
  }

  @TempDir Path root;
  final Gson gson = ResourceLoader.getGson();
  final JsonObject source =
      JsonParser.parseReader(
              new java.io.InputStreamReader(
                  Objects.requireNonNull(
                      getClass().getResourceAsStream("/fixtures/activity-kill-quests-4.6.json")),
                  StandardCharsets.UTF_8))
          .getAsJsonObject();
  final Map<ResourceMap, List<ResourceBase>> previous = new IdentityHashMap<>();
  final Map<String, FloorInfo> previousFloors = new HashMap<>();
  final List<GameplayDefinitions.Window> oldWindows = new ArrayList<>();
  String previousRoot;
  Actor player;
  final MissionFarmingBattleTest.Service service = new MissionFarmingBattleTest.Service();

  static class Actor extends MissionFarmingBattleTest.Farmer {
    Scene scene;
    boolean failSave;
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
      if (failSave) throw new IllegalStateException("injected save failure");
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
      case "MonsterConfig" -> new Table(GameData.getMonsterExcelMap(), MonsterExcel.class);
      case "StageConfig" -> new Table(GameData.getStageExcelMap(), StageExcel.class);
      case "MazePlane" -> new Table(GameData.getMazePlaneExcelMap(), MazePlaneExcel.class);
      case "MapEntrance" -> new Table(GameData.getMapEntranceExcelMap(), MapEntranceExcel.class);
      case "NPCMonsterData" -> new Table(GameData.getNpcMonsterExcelMap(), NpcMonsterExcel.class);
      case "PlaneEvent" -> new Table(GameData.getPlaneEventExcelMap(), PlaneEventExcel.class);
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
        var v = gson.fromJson(r, t.type());
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
    for (int id : new int[] {1000510, 1040105, 1040106}) {
      var q = new QuestState(id);
      q.advance(1, 1, 1);
      player.getGameplay().getMainMissions().put(id, q);
    }
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

  int template(int id) {
    return id == 6029841
        ? 4012010
        : id == 6029842 ? 4012020 : id == 6029843 ? 4012030 : id == 6029844 ? 4012040 : 4013010;
  }

  int stage(int id) {
    return id == 6029841
        ? 204110050
        : id == 6029842
            ? 204112310
            : id == 6029843 ? 204110010 : id == 6029844 ? 204121010 : 204112310;
  }

  Battle start(int stage) {
    player.getQuestManager().refreshUnlocks();
    return Objects.requireNonNull(service.startBattle(player, stage, 0));
  }

  List<MissionBattleKills.Monster> deaths(Battle b, int target) {
    var rows = new ArrayList<MissionBattleKills.Monster>();
    int entity = 5001;
    for (var w : b.getWaves())
      for (int m : w.getMonsters())
        if (m == target)
          rows.add(new MissionBattleKills.Monster(entity++, m, w.getStage().getId(), 1));
    return rows;
  }

  Battle finish(Battle b, List<MissionBattleKills.Monster> rows) {
    return service.finishBattle(
        player,
        b.getId(),
        b.getStage().getId(),
        BattleEndStatus.BATTLE_END_WIN,
        BattleStatistics.newInstance(),
        rows);
  }

  @ParameterizedTest
  @ValueSource(ints = {6029841, 6029842, 6029843, 6029844, 6029845})
  void originalNonperiodicGoalIsAcceptedAtZero(int id) {
    var q = state(id);
    assertNotNull(q, "Original kill activity goal missing");
    assertEquals(QuestState.Phase.DOING, q.getPhase());
    assertEquals(0, q.getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {6029841, 6029842, 6029843, 6029844, 6029845})
  void actualOriginalRosterDeathsAdvanceOnlyTheOriginalTemplate(int id) {
    var b = start(stage(id));
    var rows = deaths(b, template(id));
    assertFalse(rows.isEmpty());
    assertNotNull(finish(b, rows));
    int goal = id == 6029845 ? 1 : 3;
    assertEquals(Math.min(goal, rows.size()), progress(id));
    for (int other : new int[] {6029841, 6029842, 6029843, 6029844, 6029845})
      if (other != id) assertEquals(0, progress(other));
    assertNull(finish(b, rows));
    assertEquals(Math.min(goal, rows.size()), progress(id));
  }

  @ParameterizedTest
  @ValueSource(ints = {6029841, 6029842, 6029843, 6029844, 6029845})
  void emptyVictoryCannotManufactureAnyDeaths(int id) {
    var b = start(stage(id));
    assertNotNull(finish(b, List.of()));
    assertEquals(0, progress(id));
  }

  @ParameterizedTest
  @ValueSource(ints = {6029841, 6029842, 6029843, 6029844, 6029845})
  void countSaveFailureLeavesTheBattleRetryable(int id) {
    var b = start(stage(id));
    var rows = deaths(b, template(id));
    player.failSave = true;
    assertNull(finish(b, rows));
    player.failSave = false;
    assertFalse(b.isSettling());
    assertSame(b, player.getBattle());
    assertEquals(0, progress(id));
    assertNotNull(finish(b, rows));
    assertEquals(Math.min(id == 6029845 ? 1 : 3, rows.size()), progress(id));
  }

  @ParameterizedTest
  @ValueSource(ints = {6029841, 6029842, 6029843, 6029844, 6029845})
  void sourceBoundCountSurvivesSavedReload(int id) throws Exception {
    var b = start(stage(id));
    var rows = deaths(b, template(id));
    assertNotNull(finish(b, rows));
    var f = Player.class.getDeclaredField("gameplay");
    f.setAccessible(true);
    f.set(player, gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    assertEquals(Math.min(id == 6029845 ? 1 : 3, rows.size()), progress(id));
  }

  @Test
  void originalExplicitMonsterVariantUsesItsTemplate() {
    int stage = source.get("variantExampleStageID").getAsInt();
    var b = start(stage);
    assertNotNull(finish(b, List.of(new MissionBattleKills.Monster(5001, 401201004, stage, 1))));
    assertEquals(1, progress(6029841));
  }

  @Test
  void duplicateEntityInvalidatesTheWholeReportedBatch() {
    var b = start(204110050);
    var row = new MissionBattleKills.Monster(5001, 4012010, 204110050, 1);
    assertNotNull(finish(b, List.of(row, row)));
    assertEquals(0, progress(6029841));
  }

  @Test
  void rosterOverrunInvalidatesTheWholeReportedBatch() {
    var b = start(204110050);
    var rows = new ArrayList<MissionBattleKills.Monster>();
    for (int i = 0; i < 5; i++)
      rows.add(new MissionBattleKills.Monster(5001 + i, 4012010, 204110050, 1));
    assertNotNull(finish(b, rows));
    assertEquals(0, progress(6029841));
  }

  @Test
  void unrelatedStageAndMonsterDoNotCount() {
    var b = start(204110050);
    assertNotNull(
        finish(
            b,
            List.of(
                new MissionBattleKills.Monster(5001, 4012010, 204110010, 1),
                new MissionBattleKills.Monster(5002, 4012020, 204110050, 1))));
    assertEquals(0, progress(6029841));
  }

  @Test
  void absentDeathClassificationCannotCount() {
    var b = start(204110050);
    assertNotNull(
        finish(
            b,
            List.of(
                new MissionBattleKills.Monster(5001, 4012010, 204110050, 0),
                new MissionBattleKills.Monster(5002, 4012010, 204110050, 3))));
    assertEquals(0, progress(6029841));
  }

  @Test
  void replacingTheOperatorWindowAfterEntryCannotBorrowTheNewWindow() {
    var b = start(204110050);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(30013, 3001301, 2, Long.MAX_VALUE));
    assertNotNull(finish(b, deaths(b, 4012010)));
    assertEquals(0, progress(6029841));
  }

  @Test
  void originalMainPrerequisiteMustRemainValid() {
    var b = start(204110050);
    player.getGameplay().getCancelledMissions().add(1040105);
    assertNotNull(finish(b, deaths(b, 4012010)));
    assertEquals(0, progress(6029841));
  }

  @Test
  void changedOriginalStageFileCannotCountAfterEntry() throws Exception {
    var b = start(204110050);
    var rows =
        source.getAsJsonObject("records").getAsJsonArray("ExcelOutput/StageConfig.json").deepCopy();
    rows.get(0).getAsJsonObject().addProperty("StageName", "changed operator row");
    write("ExcelOutput/StageConfig.json", rows);
    assertNotNull(finish(b, deaths(b, 4012010)));
    assertEquals(0, progress(6029841));
  }

  @Test
  void changedCombatRosterCannotCountAfterEntry() {
    var b = start(204110050);
    b.getWaves().getFirst().getMonsters().removeLast();
    assertNotNull(finish(b, deaths(b, 4012010)));
    assertEquals(0, progress(6029841));
  }

  @Test
  void replacedAcceptanceCannotBorrowAnEarlierBattle() {
    var b = start(204110050);
    player.getGameplay().getActivityKillQuests().accept(6029841);
    assertNotNull(finish(b, deaths(b, 4012010)));
    assertEquals(0, progress(6029841));
  }

  @Test
  void replacedSceneCannotBorrowAnEarlierBattle() {
    var b = start(204110050);
    scene();
    assertNotNull(finish(b, deaths(b, 4012010)));
    assertEquals(0, progress(6029841));
  }

  @Test
  void wrongBattleIdentityCannotSettleOrCount() {
    var b = start(204110050);
    assertNull(
        service.finishBattle(
            player,
            b.getId() + 1,
            b.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            deaths(b, 4012010)));
    assertSame(b, player.getBattle());
    assertFalse(b.isSettling());
    assertEquals(0, progress(6029841));
    assertNotNull(finish(b, deaths(b, 4012010)));
    assertEquals(3, progress(6029841));
  }

  @Test
  void invalidSavedReceiptCannotBeHiddenBehindProgress() {
    var b = start(204110050);
    assertNotNull(finish(b, deaths(b, 4012010)));
    assertTrue(QuestCompletionRecords.completed(player, 6029841));
    var raw = gson.toJsonTree(player.getGameplay().getActivityKillQuests()).getAsJsonObject();
    raw.getAsJsonObject("receipts")
        .getAsJsonArray("6029841")
        .get(0)
        .getAsJsonObject()
        .addProperty("monster", 4012020);
    player.getGameplay().activityKillQuests(gson.fromJson(raw, ActivityKillState.class));
    assertFalse(QuestCompletionRecords.completed(player, 6029841));
    assertFalse(QuestCompletionRecords.closed(player, 6029841));
    assertNull(state(6029841));
  }

  @Test
  void unsupportedSavedVersionCannotBeReacceptedAsAnEmptyDomain() {
    state(6029841);
    var raw = gson.toJsonTree(player.getGameplay().getActivityKillQuests()).getAsJsonObject();
    raw.addProperty("version", 2);
    player.getGameplay().activityKillQuests(gson.fromJson(raw, ActivityKillState.class));
    assertNull(state(6029841));
    var b = start(204110050);
    assertNull(b.getActivityKillQuests());
    assertNotNull(finish(b, deaths(b, 4012010)));
    assertNull(state(6029841));
  }

  @Test
  void finishedAndClosedHistoryRemainDistinctAfterWindowExpiry() {
    var b = start(204110050);
    assertNotNull(finish(b, deaths(b, 4012010)));
    assertTrue(QuestCompletionRecords.completed(player, 6029841));
    assertFalse(QuestCompletionRecords.closed(player, 6029841));
    var q = state(6029841);
    assertTrue(q.reserveReward());
    q.closeReward();
    GameplayDefinitions.windows.clear();
    assertNull(state(6029841));
    assertTrue(QuestCompletionRecords.completed(player, 6029841));
    assertTrue(QuestCompletionRecords.closed(player, 6029841));
  }
}
