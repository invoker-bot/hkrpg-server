package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.battle.*;
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
class ActivityFirstTalkQuests46Test {
  @TempDir Path root;
  final Gson gson = ResourceLoader.getGson();
  final JsonObject source =
      JsonParser.parseReader(
              new java.io.InputStreamReader(
                  Objects.requireNonNull(
                      getClass().getResourceAsStream("/fixtures/activity-first-talk-4.6.json")),
                  StandardCharsets.UTF_8))
          .getAsJsonObject();
  final Map<ResourceMap, List<ResourceBase>> previous = new IdentityHashMap<>();
  final Map<String, FloorInfo> previousFloors = new HashMap<>();
  final List<GameplayDefinitions.Window> oldWindows = new ArrayList<>();
  String previousRoot;
  Actor player;
  final Map<Integer, FirstTalkDefinitions.Performance> oldPerformances = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();

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
      case "MazeProp" -> new Table(GameData.getPropExcelMap(), PropExcel.class);
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
        var decoded = r.deepCopy();
        if (t.type() == PropExcel.class && decoded.getAsJsonObject().get("PropName").isJsonObject())
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
    for (int id : new int[] {1000510, 1040101}) {
      var q = new QuestState(id);
      q.advance(1, 1, 1);
      q.reserveReward();
      q.closeReward();
      player.getGameplay().getMainMissions().put(id, q);
    }
    oldPerformances.putAll(FirstTalkDefinitions.performances);
    for (var e : source.getAsJsonObject("scripts").entrySet()) write(e.getKey(), e.getValue());
    for (var e : source.getAsJsonObject("headers").entrySet()) write(e.getKey(), e.getValue());
    FirstTalkDefinitions.load(root);
    oldSubs.putAll(GameplayDefinitions.subs);
    var prerequisite =
        source
            .getAsJsonObject("headers")
            .getAsJsonObject("Config/Level/Mission/1040101/MissionInfo_1040101.partial.json")
            .getAsJsonArray("SubMissionList");
    for (var raw : prerequisite) {
      var row = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(row.getId(), row);
    }
    var q = new QuestState(104010102);
    q.advance(1, 1, 1);
    q.reserveReward();
    q.closeReward();
    player.getGameplay().getSubMissions().put(104010102, q);
    locate(304002001);
  }

  void scene() {
    player.scene =
        new Scene(player, GameData.getMazePlaneExcelMap().get(player.plane), player.floor);
    player.scene.setEntryId(player.entry);
  }

  @AfterEach
  @SuppressWarnings("unchecked")
  void restore() throws Exception {
    FirstTalkDefinitions.performances.clear();
    FirstTalkDefinitions.performances.putAll(oldPerformances);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
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

  void locate(int performance) {
    var row =
        source
            .getAsJsonObject("locations")
            .getAsJsonArray(String.valueOf(performance))
            .get(0)
            .getAsJsonObject();
    player.plane = row.get("plane").getAsInt();
    player.floor = row.get("floor").getAsInt();
    player.entry =
        source
            .getAsJsonObject("records")
            .getAsJsonArray("ExcelOutput/MapEntrance.json")
            .asList()
            .stream()
            .map(JsonElement::getAsJsonObject)
            .filter(
                r ->
                    r.get("PlaneID").getAsInt() == player.plane
                        && r.get("FloorID").getAsInt() == player.floor)
            .findFirst()
            .orElseThrow()
            .get("ID")
            .getAsInt();
    scene();
    assertTrue(
        player.scene.getEntities().values().stream()
            .anyMatch(
                e ->
                    e instanceof EntityProp p
                        && String.valueOf(performance)
                            .equals(p.getPropInfo().getSharedValueByKey("PerformenceID"))),
        "Original performance prop was not loaded");
  }

  @Test
  void originalNonperiodicFirstTalkGoalIsAcceptedAtZero() {
    var q = state(6029859);
    assertNotNull(q, "Original first-talk activity missing");
    assertEquals(0, q.getProgress());
    assertEquals(QuestState.Phase.DOING, q.getPhase());
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        304002001, 304002002, 304002003, 304002004, 304002005, 304002006, 304002007, 304002008,
        304002009, 304002010, 304002011, 304002012, 304002013, 304002014, 304002015, 304002016,
        304002017, 304002018, 304002019, 304002020, 304002021, 304002022, 304002023, 304002024,
        304002025
      })
  void actualOriginalWorldPropCompletionAdvancesTheOriginalGoal(int performance) {
    locate(performance);
    player.getQuestManager().refreshUnlocks();
    assertFalse(player.getMissionManager().hasSeenFirstTalk(performance));
    assertEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(performance));
    assertTrue(player.getMissionManager().hasSeenFirstTalk(performance));
    assertEquals(1, progress(6029859));
    assertTrue(QuestCompletionRecords.completed(player, 6029859));
    assertFalse(QuestCompletionRecords.closed(player, 6029859));
    assertEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(performance));
    assertEquals(1, progress(6029859));
  }

  @Test
  void anotherMapCannotCompleteAnUnloadedOriginalActor() {
    locate(304002016);
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
    assertFalse(player.getMissionManager().hasSeenFirstTalk(304002001));
    assertEquals(0, progress(6029859));
  }

  @Test
  void absentOriginalBindingCannotBeInferredFromACameraAnchor() {
    locate(304002016);
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002026));
    assertFalse(player.getMissionManager().hasSeenFirstTalk(304002026));
    assertEquals(0, progress(6029859));
  }

  @Test
  void failedSaveRestoresBothDomainsAndTheSameReportCanRetry() {
    player.getQuestManager().refreshUnlocks();
    var original = player.getGameplay().getWorldFirstTalks();
    int saves = player.saves;
    player.failSave = true;
    assertEquals(
        Retcode.SYSTEM_BUSY, player.getMissionManager().finishFirstTalkPerformance(304002001));
    assertSame(original, player.getGameplay().getWorldFirstTalks());
    assertFalse(player.getMissionManager().hasSeenFirstTalk(304002001));
    assertEquals(0, original.getActivity().getProgress());
    assertEquals(saves, player.saves);
    player.failSave = false;
    assertEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
    assertEquals(1, progress(6029859));
    assertTrue(player.saved.getWorldFirstTalks().valid(WorldFirstTalkRules.load()));
  }

  @Test
  void serializationRestoresWorldObservationAndClosedActivityHistoryAfterExpiry() {
    player.getQuestManager().refreshUnlocks();
    assertEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
    var q = player.getGameplay().getWorldFirstTalks().getActivity();
    assertTrue(q.reserveReward());
    q.closeReward();
    var restored =
        gson.fromJson(
            gson.toJson(player.getGameplay().getWorldFirstTalks()), WorldFirstTalkState.class);
    player.getGameplay().worldFirstTalks(restored);
    GameplayDefinitions.windows.clear();
    assertTrue(restored.valid(WorldFirstTalkRules.load()));
    assertTrue(player.getMissionManager().hasSeenFirstTalk(304002001));
    assertNull(state(6029859));
    assertTrue(QuestCompletionRecords.closed(player, 6029859));
    assertNotEquals(Retcode.SUCC, player.getQuestManager().claim(6029859).retcode());
  }

  @Test
  void removedLiveActorCannotBeReconstructedFromItsSourceDefinition() {
    var actor = WorldFirstTalkRules.load().actor(player, 304002001);
    assertNotNull(actor);
    player.scene.removeEntity(actor);
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
    assertTrue(player.getGameplay().getWorldFirstTalks().getRecords().isEmpty());
  }

  @Test
  void stalePlayerEntryCannotReportTheCurrentSceneActor() {
    player.entry++;
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
    assertFalse(player.getMissionManager().hasSeenFirstTalk(304002001));
  }

  @Test
  void modifiedOriginalScriptDisablesBothCompletionAndActivityAcceptance() throws Exception {
    String path = WorldFirstTalkRules.load().script(304002001);
    var original = JsonParser.parseString(Files.readString(root.resolve(path))).getAsJsonObject();
    original.addProperty("unverifiedEffect", true);
    write(path, original);
    assertNull(state(6029859));
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
    assertTrue(player.getGameplay().getWorldFirstTalks().getRecords().isEmpty());
  }

  @Test
  void loadedUnsupportedScriptDefinitionCannotUseThePinnedFileAsASubstitute() {
    var original = FirstTalkDefinitions.performances.get(304002001);
    FirstTalkDefinitions.performances.put(
        304002001,
        new FirstTalkDefinitions.Performance(
            304002001, original.path(), Set.of("UnsupportedEffect"), true));
    assertNull(state(6029859));
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
  }

  @Test
  void observationBeforeAcceptanceNeverRetroactivelyCompletesTheActivity() {
    assertEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
    assertEquals(0, progress(6029859));
    assertEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
    assertEquals(0, progress(6029859));
    locate(304002002);
    assertEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002002));
    assertEquals(1, progress(6029859));
    assertEquals(
        304002002, player.getGameplay().getWorldFirstTalks().getActivityReceipt().getPerformance());
  }

  @Test
  void missingSourcePrerequisiteAllowsWorldHistoryButNeverActivityCredit() {
    player.getGameplay().getSubMissions().remove(104010102);
    assertNull(state(6029859));
    assertEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
    assertTrue(player.getMissionManager().hasSeenFirstTalk(304002001));
    assertFalse(QuestCompletionRecords.completed(player, 6029859));
  }

  @Test
  void overlappingActivityWindowsDoNotGrantAnAmbiguousReceipt() {
    player.getQuestManager().refreshUnlocks();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(30013, 3001301, 2, Long.MAX_VALUE));
    assertEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
    assertTrue(player.getMissionManager().hasSeenFirstTalk(304002001));
    assertEquals(0, player.getGameplay().getWorldFirstTalks().getActivity().getProgress());
    assertNull(player.getGameplay().getWorldFirstTalks().getActivityReceipt());
  }

  @Test
  void unrelatedLegacyQuestStateCannotForgeTheOwnedCompletion() {
    var legacy = new QuestState(6029859);
    legacy.advance(1, 1, 1);
    player.getQuestStates().put(6029859, legacy);
    assertEquals(0, progress(6029859));
    assertFalse(QuestCompletionRecords.completed(player, 6029859));
    assertNotEquals(Retcode.SUCC, player.getQuestManager().claim(6029859).retcode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "version",
        "records",
        "performance",
        "plane",
        "floor",
        "entry",
        "group",
        "instance",
        "prop",
        "runtime",
        "entity",
        "event",
        "propSource",
        "groupSource",
        "scriptSource",
        "progress",
        "receiptEvent",
        "receiptBegin",
        "receiptAcceptance"
      })
  void damagedSavedObservationsCannotExposeCompletionOrRewards(String field) {
    player.getQuestManager().refreshUnlocks();
    assertEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
    var raw = gson.toJsonTree(player.getGameplay().getWorldFirstTalks()).getAsJsonObject();
    var observation = raw.getAsJsonObject("records").getAsJsonObject("304002001");
    var receipt = raw.getAsJsonObject("activityReceipt");
    switch (field) {
      case "version" -> raw.addProperty("version", 2);
      case "records" -> raw.add("records", JsonNull.INSTANCE);
      case "progress" -> raw.getAsJsonObject("activity").addProperty("progress", 0);
      case "receiptEvent" -> receipt.addProperty("event", UUID.randomUUID().toString());
      case "receiptBegin" -> receipt.addProperty("begin", receipt.get("at").getAsLong() + 1);
      case "receiptAcceptance" -> receipt.addProperty("acceptance", UUID.randomUUID().toString());
      case "event", "propSource", "groupSource", "scriptSource" ->
          observation.addProperty(field, "unverified");
      default -> observation.addProperty(field, 0);
    }
    player.getGameplay().worldFirstTalks(gson.fromJson(raw, WorldFirstTalkState.class));
    assertFalse(player.getMissionManager().hasSeenFirstTalk(304002001));
    assertFalse(QuestCompletionRecords.completed(player, 6029859));
    assertNotEquals(Retcode.SUCC, player.getQuestManager().claim(6029859).retcode());
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishFirstTalkPerformance(304002001));
  }
}
