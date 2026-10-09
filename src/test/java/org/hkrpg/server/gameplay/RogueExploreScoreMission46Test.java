package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Source target/score tables; acceptance and one cleared room are explicit domain fixtures. */
class RogueExploreScoreMission46Test {
  static final int MAIN = 4030006, SUB = 403000601;
  static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
  final Gson gson = new Gson();
  JsonObject fixture;
  final Map<Integer, MissionInfo> oldInfos = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final Map<Integer, GameplayDefinitions.Area> oldAreas = new HashMap<>();
  final Map<String, JsonObject> oldCycle = new HashMap<>();
  final Map<String, String> oldRogue = new HashMap<>();
  Actor player;

  static class Actor extends Player {
    int saves;
    boolean interrupt;

    @Override
    public void save() {
      saves++;
      if (interrupt) {
        interrupt = false;
        throw new IllegalStateException("After actual score reservation and credit");
      }
    }
  }

  @BeforeEach
  void install() throws Exception {
    oldInfos.putAll(GameData.getMissionInfos());
    oldSubs.putAll(GameplayDefinitions.subs);
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldAreas.putAll(GameplayDefinitions.areas);
    oldCycle.putAll(GameplayDefinitions.cycleConstants);
    oldRogue.putAll(GameplayDefinitions.rogueConstants);
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    GameData.getMainMissionExcelMap().values().clear();
    GameplayDefinitions.areas.clear();
    GameplayDefinitions.cycleConstants.clear();
    GameplayDefinitions.rogueConstants.clear();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/rogue-explore-score-mission-4.6.json"),
            StandardCharsets.UTF_8)) {
      fixture = JsonParser.parseReader(reader).getAsJsonObject();
    }
    var sub = gson.fromJson(fixture.get("sub"), MissionInfo.SubMissionInfo.class);
    var task = gson.fromJson(fixture.get("script"), MissionTaskInfo.class);
    task.retainSourceDefinition(fixture.get("script"));
    sub.setTask(task);
    GameplayDefinitions.subs.put(SUB, sub);
    GameData.getMissionInfos().put(MAIN, gson.fromJson(fixture.get("main"), MissionInfo.class));
    GameData.getMainMissionExcelMap()
        .add(
            emu.lunarcore.data.ResourceLoader.getGson()
                .fromJson(fixture.get("mainExcel"), MainMissionExcel.class));
    GameplayDefinitions.areas.put(
        110, gson.fromJson(fixture.get("area"), GameplayDefinitions.Area.class));
    for (var x : fixture.getAsJsonArray("cycleConstants")) {
      var row = x.getAsJsonObject();
      GameplayDefinitions.cycleConstants.put(
          row.get("ConstValueName").getAsString(), row.getAsJsonObject("Value"));
    }
    for (var x : fixture.getAsJsonArray("rogueConstants")) {
      var row = x.getAsJsonObject();
      GameplayDefinitions.rogueConstants.put(
          row.get("ConstRogueName").getAsString(), row.get("ConstValue").getAsString());
    }
    player = new Actor();
    time(player);
  }

  void time(Player p) {
    p.getCycleScoreManager().setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    p.getMissionManager().setClock(Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void restore() {
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldInfos);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameplayDefinitions.areas.clear();
    GameplayDefinitions.areas.putAll(oldAreas);
    GameplayDefinitions.cycleConstants.clear();
    GameplayDefinitions.cycleConstants.putAll(oldCycle);
    GameplayDefinitions.rogueConstants.clear();
    GameplayDefinitions.rogueConstants.putAll(oldRogue);
  }

  void accept() {
    player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    player.getGameplay().getSubMissions().put(SUB, new QuestState(SUB));
  }

  GameplayState.RogueResult result(GameplayState.RogueRun.Phase outcome, int rooms) {
    var run = new GameplayState.RogueRun(110, 101, Map.of(1, 100011));
    run.enter(1, 100011);
    if (rooms > 0) run.clear();
    run.finish(outcome);
    var result = new GameplayState.RogueResult(player, run, outcome, 0);
    player.getGameplay().getRogueResults().put(run.getRunId(), result);
    return result;
  }

  void pending() {
    assertEquals(0, player.getMissionManager().sub(SUB).getProgress());
  }

  void finished() {
    completed();
    assertEquals(260, player.getCycleScoreManager().snapshot().orElseThrow().score());
  }

  void completed() {
    assertEquals(1, player.getMissionManager().sub(SUB).getProgress());
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(SUB).getPhase());
  }

  @ParameterizedTest
  @ValueSource(strings = {"FINISHED", "FAILED", "ABANDONED"})
  void actualPositiveServerCreditAdvancesTheOriginalObjectiveOnce(String outcome) {
    accept();
    var result = result(GameplayState.RogueRun.Phase.valueOf(outcome), 1);
    player.getCycleScoreManager().settle(result);
    player.getMissionManager().refresh();
    finished();
    var before = gson.toJson(player.getGameplay());
    player.getCycleScoreManager().settle(result);
    player.getMissionManager().refresh();
    assertEquals(before, gson.toJson(player.getGameplay()));
  }

  @Test
  void anUnsettledTerminalResultCannotStandInForAnActualCredit() {
    accept();
    result(GameplayState.RogueRun.Phase.FINISHED, 1);
    player.getMissionManager().refresh();
    pending();
    assertTrue(player.getGameplay().getCycleWeeks().isEmpty());
  }

  @Test
  void oldCreditedScoreCannotCompleteANewlyAcceptedTask() {
    var result = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    player.getCycleScoreManager().settle(result);
    accept();
    player.getCycleScoreManager().settle(result);
    player.getMissionManager().refresh();
    pending();
  }

  @Test
  void emptyExplorationHasNoPositiveCreditToConsume() {
    accept();
    var result = result(GameplayState.RogueRun.Phase.FINISHED, 0);
    player.getCycleScoreManager().settle(result);
    player.getMissionManager().refresh();
    pending();
  }

  @Test
  void missingScoreResourceCannotManufactureEarnedPoints() {
    accept();
    GameplayDefinitions.areas.clear();
    var result = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    player.getCycleScoreManager().settle(result);
    player.getMissionManager().refresh();
    pending();
  }

  @ParameterizedTest
  @ValueSource(strings = {"main", "sub"})
  void cancelledObjectivesCannotConsumeTheCredit(String target) {
    accept();
    if (target.equals("main")) player.getGameplay().getCancelledMissions().add(MAIN);
    else player.getGameplay().getCancelledSubs().add(SUB);
    player.getCycleScoreManager().settle(result(GameplayState.RogueRun.Phase.FINISHED, 1));
    player.getMissionManager().refresh();
    pending();
  }

  @Test
  void changedScriptCannotBorrowTheOriginalScoreEvent() {
    accept();
    GameplayDefinitions.subs
        .get(SUB)
        .getTask()
        .retainSourceDefinition(
            JsonParser.parseString(
                "{\"Type\":\"Mission\",\"OnInitSequece\":[],\"OnStartSequece\":[{\"TaskList\":[{\"$type\":\"RPG.GameCore.UnknownMissionAction\"}]}]}"));
    player.getCycleScoreManager().settle(result(GameplayState.RogueRun.Phase.FINISHED, 1));
    player.getMissionManager().refresh();
    pending();
  }

  @Test
  void anInterruptionAfterCreditCanRecoverTheTargetWithoutCreditingAgain() {
    accept();
    var result = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    player.interrupt = true;
    assertThrows(IllegalStateException.class, () -> player.getCycleScoreManager().settle(result));
    assertTrue(result.isCycleSettled());
    pending();
    player.getMissionManager().refresh();
    finished();
    player.getCycleScoreManager().settle(result);
    finished();
  }

  @Test
  void theFrozenCreditTargetSurvivesPlayerJsonRestoration() {
    accept();
    var result = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    player.interrupt = true;
    assertThrows(IllegalStateException.class, () -> player.getCycleScoreManager().settle(result));
    var restored = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    player.getGameplay().getMainMissions().clear();
    player.getGameplay().getMainMissions().putAll(restored.getMainMissions());
    player.getGameplay().getSubMissions().clear();
    player.getGameplay().getSubMissions().putAll(restored.getSubMissions());
    player.getGameplay().getRogueResults().clear();
    player.getGameplay().getRogueResults().putAll(restored.getRogueResults());
    player.getGameplay().getCycleWeeks().clear();
    player.getGameplay().getCycleWeeks().putAll(restored.getCycleWeeks());
    player.getMissionManager().refresh();
    finished();
  }

  @Test
  void anOrphanTerminalResultCannotCreditScoreOrObserveTheTarget() {
    accept();
    var terminal = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    player.getGameplay().getRogueResults().clear();
    player.getCycleScoreManager().settle(terminal);
    assertFalse(terminal.isCycleSettled());
    assertTrue(player.getGameplay().getCycleWeeks().isEmpty());
    assertNull(terminal.getExploreMissionTargets());
    pending();
  }

  @Test
  void duplicateResultAliasesCannotReserveTheSameCredit() {
    accept();
    var terminal = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    player.getGameplay().getRogueResults().put("another-owned-key", terminal);
    player.getCycleScoreManager().settle(terminal);
    assertFalse(terminal.isCycleSettled());
    assertTrue(player.getGameplay().getCycleWeeks().isEmpty());
    pending();
  }

  @Test
  void aStillExploringRunCannotBeCreditedAsTerminalExploration() {
    accept();
    var terminal = result(GameplayState.RogueRun.Phase.EXPLORING, 1);
    player.getCycleScoreManager().settle(terminal);
    assertFalse(terminal.isCycleSettled());
    assertTrue(player.getGameplay().getCycleWeeks().isEmpty());
    pending();
  }

  @Test
  void anAlreadyObservedButUncreditedRecordCannotPartiallyChangeAWeek() {
    accept();
    var terminal = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    var data = gson.toJsonTree(terminal).getAsJsonObject();
    data.add("exploreMissionTargets", new JsonObject());
    var damaged = gson.fromJson(data, GameplayState.RogueResult.class);
    player.getGameplay().getRogueResults().replaceAll((key, value) -> damaged);
    var before = gson.toJson(player.getGameplay());
    assertDoesNotThrow(() -> player.getCycleScoreManager().settle(damaged));
    assertEquals(before, gson.toJson(player.getGameplay()));
    pending();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missingTarget",
        "signature",
        "unsettled",
        "scoreMismatch",
        "roomCount",
        "outcome"
      })
  void damagedCreditedHistoryCannotProveAnObjective(String corruption) {
    accept();
    var terminal = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    player.interrupt = true;
    assertThrows(IllegalStateException.class, () -> player.getCycleScoreManager().settle(terminal));
    var data = gson.toJsonTree(terminal).getAsJsonObject();
    switch (corruption) {
      case "missingTarget" -> data.remove("exploreMissionTargets");
      case "signature" ->
          data.getAsJsonObject("exploreMissionTargets")
              .addProperty(Integer.toString(SUB), "another-target");
      case "unsettled" -> data.addProperty("cycleSettled", false);
      case "scoreMismatch" -> data.addProperty("roomScore", 1);
      case "roomCount" -> data.addProperty("passedRooms", 0);
      case "outcome" -> data.addProperty("outcome", "EXPLORING");
      default -> fail(corruption);
    }
    var damaged = gson.fromJson(data, GameplayState.RogueResult.class);
    player.getGameplay().getRogueResults().replaceAll((key, value) -> damaged);
    player.getMissionManager().refresh();
    pending();
    assertEquals(260, player.getCycleScoreManager().snapshot().orElseThrow().score());
  }

  @Test
  void aCreditMarkerNeedsTheMatchingActuallyCreditedWeek() {
    accept();
    var terminal = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    player.interrupt = true;
    assertThrows(IllegalStateException.class, () -> player.getCycleScoreManager().settle(terminal));
    player.getGameplay().getCycleWeeks().clear();
    player.getMissionManager().refresh();
    pending();
    player.getCycleScoreManager().settle(terminal);
    assertTrue(player.getGameplay().getCycleWeeks().isEmpty());
  }

  @Test
  void changedSourceAfterCreditWaitsForItsOriginalFrozenTarget() {
    accept();
    var terminal = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    player.interrupt = true;
    assertThrows(IllegalStateException.class, () -> player.getCycleScoreManager().settle(terminal));
    var task = GameplayDefinitions.subs.get(SUB).getTask();
    task.retainSourceDefinition(
        JsonParser.parseString("{\"Type\":\"Mission\",\"UnknownSequence\":[]}"));
    player.getMissionManager().refresh();
    pending();
    task.retainSourceDefinition(fixture.get("script"));
    player.getMissionManager().refresh();
    finished();
  }

  @Test
  void concurrentQueriesAndSettlementCreditOneOwnedResultOnce() throws Exception {
    accept();
    var terminal = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      var jobs = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 32; i++)
        jobs.add(
            executor.submit(
                () -> {
                  player.getCycleScoreManager().settle(terminal);
                  player.getCycleScoreManager().settleSaved();
                  player.getMissionManager().refresh();
                }));
      for (var job : jobs) job.get(10, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    finished();
    assertEquals(260, player.getCycleScoreManager().snapshot().orElseThrow().score());
    assertEquals(1, player.getGameplay().getRogueResults().size());
  }

  @Test
  void aFullWeekLeavesTheResultAndTargetUnreservedUntilCreditCanSucceed() {
    accept();
    var terminal = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    var window = player.getCycleScoreManager().window().orElseThrow();
    var week = new GameplayState.CycleWeek(window.start(), window.end());
    assertTrue(week.credit(Integer.MAX_VALUE));
    player.getGameplay().getCycleWeeks().put(window.key(), week);
    var before = gson.toJson(player.getGameplay());
    player.getCycleScoreManager().settle(terminal);
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertNull(terminal.getExploreMissionTargets());
    assertFalse(terminal.isCycleSettled());
    pending();
  }

  @Test
  void aSavedTerminalResultCreditsItsFrozenWeekAcrossARefreshBoundary() {
    accept();
    var terminal = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    var old = player.getCycleScoreManager().window().orElseThrow();
    player
        .getCycleScoreManager()
        .setClock(Clock.fixed(NOW.plus(Duration.ofDays(7)), ZoneOffset.UTC));
    player.getCycleScoreManager().settle(terminal);
    completed();
    assertEquals(260, player.getGameplay().getCycleWeeks().get(old.key()).getScore());
    assertEquals(0, player.getCycleScoreManager().snapshot().orElseThrow().score());
    player.getCycleScoreManager().settleSaved();
    assertEquals(260, player.getGameplay().getCycleWeeks().get(old.key()).getScore());
  }

  @Test
  void anotherEarnedResultAddsScoreWithoutObservingAnAlreadyFinishedTarget() {
    accept();
    var first = result(GameplayState.RogueRun.Phase.ABANDONED, 1);
    player.getCycleScoreManager().settle(first);
    finished();
    var next = result(GameplayState.RogueRun.Phase.FAILED, 1);
    player.getCycleScoreManager().settle(next);
    completed();
    assertTrue(next.isCycleSettled());
    assertEquals(Map.of(), next.getExploreMissionTargets());
    assertEquals(520, player.getCycleScoreManager().snapshot().orElseThrow().score());
    player.getCycleScoreManager().settleSaved();
    assertEquals(520, player.getCycleScoreManager().snapshot().orElseThrow().score());
  }

  @Test
  void actualMorphiaPlayerReloadRetainsTheCreditTargetAndDoesNotCreditAgain() throws Exception {
    accept();
    var terminal = result(GameplayState.RogueRun.Phase.FINISHED, 1);
    player.interrupt = true;
    assertThrows(IllegalStateException.class, () -> player.getCycleScoreManager().settle(terminal));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue_explore_score");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      var document = new Player();
      uid.setInt(document, 123456798);
      gameplay.set(document, player.getGameplay());
      store.save(document);
      var restored =
          store
              .find(Player.class)
              .filter(dev.morphia.query.filters.Filters.eq("_id", 123456798))
              .first();
      assertNotNull(restored);
      player = new Actor();
      gameplay.set(player, restored.getGameplay());
      time(player);
      assertNotNull(
          player
              .getGameplay()
              .getRogueResults()
              .values()
              .iterator()
              .next()
              .getExploreMissionTargets());
      player.getMissionManager().refresh();
      finished();
      player.getCycleScoreManager().settleSaved();
      finished();
      gameplay.set(document, player.getGameplay());
      store.save(document);
      var complete =
          store
              .find(Player.class)
              .filter(dev.morphia.query.filters.Filters.eq("_id", 123456798))
              .first();
      assertEquals(1, complete.getGameplay().getSubMissions().get(SUB).getProgress());
      assertEquals(
          260, complete.getGameplay().getCycleWeeks().values().iterator().next().getScore());
    } finally {
      server.shutdownNow();
    }
  }
}
