package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original commission graphs and real scene loads; closed tutorials and empty map groups are fixtures. */
class DailyMissionTravel46Test {
  static final Instant TODAY = DailyMissionAcceptance46Test.TODAY, NEXT = TODAY.plusSeconds(86400);
  static final List<Integer> MAINS = List.of(
      3020101, 3020102, 3020103, 3020104, 3020105, 3020106, 3020107, 3020108,
      3020201, 3020202, 3020203, 3020205, 3020206, 3012001, 3020204);
  final Gson gson = emu.lunarcore.data.ResourceLoader.getGson();
  final DailyMissionAcceptance46Test fixture = new DailyMissionAcceptance46Test();
  final List<MazePlaneExcel> oldPlanes = new ArrayList<>();
  final List<MapEntranceExcel> oldEntrances = new ArrayList<>();
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  final List<GameplayDefinitions.DailyPool> oldPool = new ArrayList<>();
  final Map<Integer, JsonObject> daily = new HashMap<>();
  @TempDir Path temp;
  Actor actor;
  int selected;
  Instant now;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    int saves;

    Actor() {
      resetPosition();
      setNickname("Original daily travel fixture");
      getCurrentLineup().getAvatars().add(0);
    }

    @Override public LineupManager getLineupManager() { return teams; }
    @Override public void save() { saves++; }
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.temp = temp;
    fixture.setup();
    oldPlanes.addAll(GameData.getMazePlaneExcelMap().values());
    oldEntrances.addAll(GameData.getMapEntranceExcelMap().values());
    oldFloors.putAll(GameData.getFloorInfos());
    oldPool.addAll(GameplayDefinitions.dailyPool);
    GameData.getMazePlaneExcelMap().values().clear();
    GameData.getMapEntranceExcelMap().values().clear();
    GameData.getFloorInfos().clear();
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.dailyPool.clear();
    fixture.dailyRows = new JsonArray();
    fixture.countRows = new JsonArray();
    var definitions = new HashMap<Integer, JsonObject>();
    var children = new HashMap<Integer, JsonArray>();
    var scripts = new HashMap<Integer, JsonObject>();
    try (var input = getClass().getResourceAsStream("/fixtures/daily-travel-4.6.json")) {
      var rows = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
      assertEquals(142, rows.size());
      for (var entry : rows) {
        var e = entry.getAsJsonObject();
        assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", e.get("sourceCommit").getAsString());
        var row = e.getAsJsonObject("row");
        switch (e.get("kind").getAsString()) {
          case "definition" -> definitions.put(row.get("MainMissionID").getAsInt(), row);
          case "sub" -> children.computeIfAbsent(row.get("MainMissionID").getAsInt(), ignored -> new JsonArray()).add(row);
          case "main" -> GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" -> GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
          case "daily" -> daily.put(row.get("ID").getAsInt(), row);
          case "count" -> fixture.countRows.add(row);
          case "pool" -> GameplayDefinitions.dailyPool.add(gson.fromJson(row, GameplayDefinitions.DailyPool.class));
          case "reward" -> { var value = gson.fromJson(row, RewardExcel.class); value.onLoad(); GameData.getRewardExcelMap().add(value); }
          case "item" -> GameData.getItemExcelMap().add(gson.fromJson(row, ItemExcel.class));
          case "plane" -> GameData.getMazePlaneExcelMap().add(gson.fromJson(row, MazePlaneExcel.class));
          case "entrance" -> GameData.getMapEntranceExcelMap().add(gson.fromJson(row, MapEntranceExcel.class));
          case "script" -> {
            var source = e.get("source").getAsString();
            int id = Integer.parseInt(source.substring(source.lastIndexOf("Mission_") + 8, source.length() - 5));
            scripts.put(id, row);
          }
          default -> fail("Unknown original fixture row");
        }
      }
    }
    assertEquals(15, definitions.size());
    for (var entry : definitions.entrySet()) {
      entry.getValue().add("SubMissionList", children.get(entry.getKey()));
      var graph = gson.fromJson(entry.getValue(), MissionInfo.class);
      GameData.getMissionInfos().put(entry.getKey(), graph);
      for (var sub : graph.getSubMissionList()) {
        GameplayDefinitions.subs.put(sub.getId(), sub);
        if (scripts.containsKey(sub.getId())) {
          var task = gson.fromJson(scripts.get(sub.getId()), MissionTaskInfo.class);
          task.retainSourceDefinition(scripts.get(sub.getId()));
          sub.setTask(task);
        }
      }
    }
    // Only entity population is isolated: original plane/floor/entrance relationships remain.
    for (int id : List.of(10102001, 10102003, 10202001, 10202004, 20231001, 20232001)) {
      var floor = gson.fromJson("{\"FloorID\":" + id + ",\"GroupInstanceList\":[]}", FloorInfo.class);
      DailyMissionAcceptance46Test.set(floor, "loaded", true);
      GameData.getFloorInfos().put("P" + id / 1000 + "_F" + id, floor);
    }
    select(3020101);
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameData.getMazePlaneExcelMap().values().clear(); oldPlanes.forEach(GameData.getMazePlaneExcelMap()::add);
    GameData.getMapEntranceExcelMap().values().clear(); oldEntrances.forEach(GameData.getMapEntranceExcelMap()::add);
    GameData.getFloorInfos().clear(); GameData.getFloorInfos().putAll(oldFloors);
    GameplayDefinitions.dailyPool.clear(); GameplayDefinitions.dailyPool.addAll(oldPool);
  }

  void select(int id) throws Exception {
    selected = id;
    fixture.dailyRows = new JsonArray(); fixture.dailyRows.add(daily.get(id)); fixture.loadCatalog();
    actor = fresh();
    clock(TODAY);
    actor.getDailyManager().ensureDay();
  }

  Actor fresh() throws Exception {
    var value = new Actor();
    DailyMissionAcceptance46Test.set(value, "uid", 123456784);
    value.getGameplay().getMainMissions().put(daily.get(selected).get("UnlockMainMission").getAsInt(), DailyMissionAcceptance46Test.closed(daily.get(selected).get("UnlockMainMission").getAsInt()));
    value.getGameplay().getMainMissions().put(1010403, DailyMissionAcceptance46Test.closed(1010403));
    return value;
  }

  void clock(Instant instant) {
    now = instant;
    actor.getMissionManager().setClock(Clock.fixed(instant, ZoneOffset.UTC));
    actor.getDailyManager().setClock(Clock.fixed(instant, ZoneOffset.UTC));
  }

  void move(int plane, int floor) {
    var entry = GameData.getMapEntranceExcelMap().values().stream()
        .filter(row -> row.getPlaneID() == plane && row.getFloorID() == floor)
        .min(Comparator.comparingInt(MapEntranceExcel::getId)).orElseThrow();
    assertTrue(actor.loadScene(plane, floor, entry.getId(), new Position(), new Position(), false));
    assertEquals(plane, actor.getScene().getPlaneId()); assertEquals(floor, actor.getScene().getFloorId());
  }

  void talk(int id) {
    if (MissionStateRecords.subCompleted(actor,id)) return;
    var sub = GameplayDefinitions.subs.get(id);
    move(sub.getLevelPlaneID(), sub.getLevelFloorID());
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(id, sub.getParamStr1()));
  }

  void start() {
    actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainDoing(actor, selected));
    var graph = GameData.getMissionInfos().get(selected);
    for (int id : graph.getStartSubMissionList()) assertNotNull(actor.getMissionManager().sub(id));
    assertEquals(0, actor.getGameplay().getDailyPoints());
  }

  void complete() {
    if (selected == 3012001) {
      talk(301200101); talk(301200107);
      assertEquals(0, actor.getMissionManager().sub(301200108).getProgress());
      move(10102, 10102003);
      assertEquals(1, actor.getMissionManager().sub(301200108).getProgress());
      talk(301200109);
    } else if (selected == 3020204) {
      talk(302020401); talk(302020402);
      assertEquals(0, actor.getMissionManager().sub(302020403).getProgress());
      move(10202, 10202001);
      assertEquals(1, actor.getMissionManager().sub(302020403).getProgress());
      assertTrue(MissionStateRecords.mainDoing(actor, selected));
      talk(302020404);
    } else {
      for (int step = 0; step < 64 && MissionStateRecords.mainDoing(actor, selected); step++) {
        var next = Arrays.stream(GameData.getMissionInfos().get(selected).getSubMissionList())
            .filter(sub -> "Talk".equals(sub.getFinishType())
                && MissionStateRecords.doing(actor.getMissionManager().sub(sub.getId()), sub.getId(), 1))
            .min(Comparator.comparingInt(MissionInfo.SubMissionInfo::getId)).orElseThrow();
        talk(next.getId());
      }
    }
    assertEquals(QuestState.Phase.CLOSED, actor.getGameplay().getMainMissions().get(selected).getPhase());
    assertEquals(QuestState.Phase.FINISHED, actor.getGameplay().getDailyQuests().get(2100003).getPhase());
    assertEquals(Retcode.SUCC, actor.getDailyManager().claimQuest(2100003).retcode());
    assertEquals(200, actor.getGameplay().getDailyPoints());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getDailyManager().claimQuest(2100003).retcode());
  }

  @ParameterizedTest @ValueSource(ints = {3020101,3020102,3020103,3020104,3020105,3020106,3020107,3020108,3020201,3020202,3020203,3020205,3020206,3012001,3020204})
  void allFifteenOriginalGraphsAreEligibleWithoutRewritingTheirSource(int main) {
    assertTrue(DailyMissionRepeats.supportedGraph(main));
    var graph = GameData.getMissionInfos().get(main);
    var start = GameplayDefinitions.subs.get(graph.getStartSubMissionList()[0]);
    assertEquals(List.of(main), Arrays.asList(start.getTakeParamIntList()));
    assertEquals("Auto", start.getTakeType());
  }

  @ParameterizedTest @ValueSource(ints = {3020101,3020102,3020103,3020104,3020105,3020106,3020107,3020108,3020201,3020202,3020203,3020205,3020206,3012001,3020204})
  void actualSourceTalksAndSceneLoadsPayOnceInEachDailyPeriod(int main) throws Exception {
    select(main); start(); complete();
    assertEquals(5000, actor.getScoin());
    var closed = actor.getGameplay().getMainMissions().get(main);
    assertEquals(Retcode.MISSION_ALREADY_DONE, actor.getMissionManager().accept(main));
    clock(NEXT); actor.getDailyManager().ensureDay(); start(); complete();
    assertEquals(10000, actor.getScoin());
    var snapshot = actor.getGameplay().getDailyMissionPeriods().getArchives().get(main + ":" + DailyManager.day(TODAY));
    assertNotNull(snapshot); assertSame(closed, snapshot.getMain());
    assertTrue(actor.getRewardLedger().granted("mission:" + main));
    assertTrue(actor.getRewardLedger().granted("daily-mission:" + DailyManager.day(NEXT) + ":mission:" + main));
    assertEquals(0, TodayDailyMissionCounts.count(actor, DailyManager.day(TODAY), TODAY));
    assertEquals(1, TodayDailyMissionCounts.count(actor, DailyManager.day(NEXT), NEXT));
  }

  @ParameterizedTest @ValueSource(strings = {"foreignMain", "multiple", "null", "nonStart", "duplicateStart", "takeStrings"})
  void anAutoMainReferenceCannotAuthorizeAnUnrelatedOrNonStartingObjective(String kind) throws Exception {
    var graph = GameData.getMissionInfos().get(3020101);
    var start = graph.getSubMissionList()[0];
    switch (kind) {
      case "foreignMain" -> start.setTakeParamIntList(new Integer[] {3020102});
      case "multiple" -> start.setTakeParamIntList(new Integer[] {3020101,3020101});
      case "null" -> start.setTakeParamIntList(new Integer[] {null});
      case "nonStart" -> { var sub = graph.getSubMissionList()[1]; sub.setTakeType("Auto"); sub.setTakeParamIntList(new Integer[] {3020101}); }
      case "duplicateStart" -> DailyMissionAcceptance46Test.set(graph, "startSubMissionList", new int[] {start.getId(),start.getId()});
      case "takeStrings" -> start.setTakeParamStrList(new String[] {"extra"});
      default -> fail(kind);
    }
    assertFalse(DailyMissionRepeats.supportedGraph(3020101));
    actor.getMissionManager().refresh(); assertFalse(actor.getGameplay().getMainMissions().containsKey(3020101));
  }

  @ParameterizedTest @ValueSource(strings = {"wrongPlane", "wrongFloor", "wrongEntrance", "missingTask", "extraTask", "backtrack", "storyDimension", "missingFloor"})
  void aTravelCommissionRequiresTheLiteralOriginalEntranceAndAResolvedOrdinaryFloor(String kind) throws Exception {
    select(3020204);
    var sub = GameplayDefinitions.subs.get(302020403);
    switch (kind) {
      case "wrongPlane" -> sub.setParamInt1(10102);
      case "wrongFloor" -> sub.setParamInt2(10202004);
      case "wrongEntrance" -> DailyMissionAcceptance46Test.set(GameData.getMapEntranceExcelMap().get(1020201), "FloorID", 10202004);
      case "missingTask" -> sub.setTask(null);
      case "extraTask" -> { var raw = JsonParser.parseString(sub.getTask().getSourceDefinition()).getAsJsonObject(); raw.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList").add(new JsonObject()); sub.getTask().retainSourceDefinition(raw); }
      case "backtrack" -> sub.setBackTrack(true);
      case "storyDimension" -> sub.setStoryLineID(1);
      case "missingFloor" -> GameData.getFloorInfos().remove("P10202_F10202001");
      default -> fail(kind);
    }
    assertFalse(DailyMissionRepeats.supportedGraph(3020204));
    actor.getMissionManager().refresh(); assertFalse(actor.getGameplay().getMainMissions().containsKey(3020204));
  }

  @ParameterizedTest @ValueSource(ints = {3012001,3020204})
  void aWrongSameFloorOrPrematureLoadDoesNotCompleteAnAcceptedTravelObjective(int main) throws Exception {
    select(main); start();
    int plane = main == 3012001 ? 10102 : 10202;
    int origin = main == 3012001 ? 10102001 : 10202004;
    int target = main == 3012001 ? 10102003 : 10202001;
    int objective = main == 3012001 ? 301200108 : 302020403;
    move(plane,target); assertNull(actor.getMissionManager().sub(objective));
    if (main == 3012001) { talk(301200101); talk(301200107); }
    else { talk(302020401); talk(302020402); }
    move(plane,origin); move(plane,origin);
    assertEquals(0, actor.getMissionManager().sub(objective).getProgress());
    actor.getMissionManager().onEnterFloor(plane,target);
    assertEquals(0, actor.getMissionManager().sub(objective).getProgress());
    assertEquals(0, actor.getScoin()); assertTrue(MissionStateRecords.mainDoing(actor,main));
    move(plane,target); assertEquals(1, actor.getMissionManager().sub(objective).getProgress());
  }

  @ParameterizedTest @ValueSource(ints = {3012001,3020204})
  void anUnfinishedTravelCommissionExpiresBeforeANewPeriodAndDoesNotCountAsCompleted(int main) throws Exception {
    select(main); start();
    if (main == 3012001) { talk(301200101); talk(301200107); }
    else { talk(302020401); talk(302020402); }
    int objective = main == 3012001 ? 301200108 : 302020403;
    var old = actor.getMissionManager().sub(objective);
    clock(NEXT); actor.getDailyManager().ensureDay(); start();
    var expired = actor.getGameplay().getDailyMissionPeriods().getExpired().get(main + ":" + DailyManager.day(TODAY));
    assertNotNull(expired); assertSame(old, expired.getSubs().get(objective));
    assertNull(actor.getMissionManager().sub(objective)); assertEquals(0, actor.getScoin());
    assertEquals(0, TodayDailyMissionCounts.count(actor,DailyManager.day(NEXT),NEXT));
    complete(); assertEquals(5000, actor.getScoin());
    assertTrue(actor.getGameplay().getDailyMissionPeriods().getArchives().isEmpty());
  }

  @ParameterizedTest @ValueSource(ints = {3012001,3020204})
  void actualPlayerDocumentReloadsPreserveAcceptedTravelAndBothPeriodReceipts(int main) throws Exception {
    select(main); start();
    if (main == 3012001) { talk(301200101); talk(301200107); }
    else { talk(302020401); talk(302020402); }
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client,"daily_travel");
      store.getMapper().map(Player.class,QuestState.class);
      for (int iteration=0; iteration<2; iteration++) {
        reload(store);
        int objective = main == 3012001 ? 301200108 : 302020403;
        assertEquals(0, actor.getMissionManager().sub(objective).getProgress());
        assertEquals(DailyManager.day(TODAY), DailyMissionRepeats.selectedDay(actor,main));
      }
      complete(); clock(NEXT); actor.getDailyManager().ensureDay(); start(); complete();
      for (int iteration=0; iteration<2; iteration++) {
        reload(store); actor.getMissionManager().refresh();
        assertEquals(10000,actor.getScoin()); assertEquals(200,actor.getGameplay().getDailyPoints());
        assertEquals(1,actor.getGameplay().getDailyMissionPeriods().getArchives().size());
        assertEquals(Retcode.MISSION_ALREADY_DONE,actor.getMissionManager().accept(main));
        assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN,actor.getDailyManager().claimQuest(2100003).retcode());
      }
    } finally { server.shutdownNow(); }
  }

  void reload(dev.morphia.Datastore store) throws Exception {
    var base = new Player();
    DailyMissionAcceptance46Test.set(base,"uid",123456784);
    DailyMissionAcceptance46Test.set(base,"gameplay",actor.getGameplay());
    DailyMissionAcceptance46Test.set(base,"scoin",actor.getScoin());
    DailyMissionAcceptance46Test.set(base,"planeId",actor.getPlaneId());
    DailyMissionAcceptance46Test.set(base,"floorId",actor.getFloorId());
    DailyMissionAcceptance46Test.set(base,"entryId",actor.getEntryId());
    store.save(base);
    var restored=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",123456784)).first();
    assertNotNull(restored);
    int plane=restored.getPlaneId(),floor=restored.getFloorId();
    actor=fresh();
    DailyMissionAcceptance46Test.set(actor,"gameplay",restored.getGameplay());
    DailyMissionAcceptance46Test.set(actor,"scoin",restored.getScoin());
    clock(now); move(plane,floor);
  }
}
