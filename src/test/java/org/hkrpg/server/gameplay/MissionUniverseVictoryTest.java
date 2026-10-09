package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class MissionUniverseVictoryTest {
  static final int MAIN = 19996921, SUB = 199969211, AREA = 19996, PROGRESS = 19996;
  final GameplayProgressTest missions = new GameplayProgressTest();
  final UniverseCombatFragmentsTest combat = new UniverseCombatFragmentsTest();

  void mission(String parameters) {
    missions.mission(
        MAIN,
        "Manual",
        Integer.toString(SUB),
        "[{\"ID\":"
            + SUB
            + ",\"MainMissionID\":"
            + MAIN
            + ",\"TakeType\":\"Auto\",\"FinishType\":\"RoguePassAreaProgress\",\"ParamType\":\"Equal\",\"ParamInt1\":"
            + PROGRESS
            + parameters
            + "}]");
  }

  void area() {
    GameplayDefinitions.areas.put(AREA, new GameplayDefinitions.Area(AREA, PROGRESS, 1, 0, 0));
  }

  @AfterEach
  void cleanup() {
    missions.reset();
    emu.lunarcore.data.GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    emu.lunarcore.data.GameData.getMissionInfos().remove(MAIN);
  }

  @Test
  void acceptedObjectiveReadsOnlyTheConfirmedSuccessfulAreaHistory() {
    area();
    mission(",\"Progress\":2");
    var p = new GameplayProgressTest.TestPlayer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    for (int i = 1; i <= 2; i++) {
      var run = new GameplayState.RogueRun(AREA, 1999601, Map.of(1, 19996001));
      p.getGameplay().setRogue(run);
      assertFalse(p.getGameplay().recordRogueWin(run));
      run.finish(GameplayState.RogueRun.Phase.FINISHED);
      assertTrue(p.getGameplay().recordRogueWin(run));
      assertFalse(p.getGameplay().recordRogueWin(run));
      p.getMissionManager().refresh();
      assertEquals(i, p.getMissionManager().sub(SUB).getProgress());
    }
    assertEquals(QuestState.Phase.FINISHED, p.getMissionManager().sub(SUB).getPhase());
    assertEquals(2, p.getGameplay().getRogueWins().get(AREA));
  }

  @Test
  void actualLastRoomVictoryAdvancesAnAcceptedObjectiveWithoutAnotherQuery() {
    combat.setup();
    String floorKey = "P" + combat.p.getPlaneId() + "_F" + combat.p.getFloorId();
    var oldFloor =
        emu.lunarcore.data.GameData.getFloorInfos()
            .put(floorKey, new emu.lunarcore.data.config.FloorInfo());
    try {
      area();
      mission(",\"Progress\":1");
      GameplayDefinitions.maps.put(
          1999601, List.of(new GameplayDefinitions.Site(1999601, 1, true, List.of(), 1)));
      var p = combat.p;
      assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
      var battle = combat.battle(combat.monster(8, 1));
      combat.finish(battle, BattleEndStatus.BATTLE_END_WIN);
      assertEquals(GameplayState.RogueRun.Phase.FINISHED, p.getGameplay().getRogue().getPhase());
      assertEquals(1, p.getGameplay().getRogueWins().get(AREA));
      assertEquals(QuestState.Phase.FINISHED, p.getMissionManager().sub(SUB).getPhase());
      int saves = p.saves;
      p.getUniverseManager().onBattleFinish(battle);
      assertEquals(saves, p.saves);
      assertEquals(1, p.getGameplay().getRogueWins().get(AREA));
    } finally {
      if (oldFloor == null) emu.lunarcore.data.GameData.getFloorInfos().remove(floorKey);
      else emu.lunarcore.data.GameData.getFloorInfos().put(floorKey, oldFloor);
      combat.cleanup();
    }
  }

  void win(Player p, int id) {
    var run = new GameplayState.RogueRun(id, 1999601, Map.of(1, 19996001));
    p.getGameplay().setRogue(run);
    run.finish(GameplayState.RogueRun.Phase.FINISHED);
    assertTrue(p.getGameplay().recordRogueWin(run));
  }

  @Test
  void unrecognizedResourceArgumentsRemainPendingDespiteSuccessfulHistory() {
    area();
    for (String extra :
        List.of(
            ",\"ParamType\":\"NotEqual\"",
            ",\"ParamInt1\":-1",
            ",\"ParamInt2\":1",
            ",\"ParamInt3\":1",
            ",\"ParamStr1\":\"unknown\"",
            ",\"ParamIntList\":[1]",
            ",\"ParamItemList\":[{\"ItemID\":1,\"ItemNum\":1}]",
            ",\"Progress\":0",
            ",\"Progress\":-1")) {
      mission(extra);
      var p = new GameplayProgressTest.TestPlayer();
      win(p, AREA);
      assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
      p.getMissionManager().refresh();
      assertEquals(QuestState.Phase.DOING, p.getMissionManager().sub(SUB).getPhase(), extra);
      assertEquals(0, p.getMissionManager().sub(SUB).getProgress(), extra);
      assertFalse(p.getGameplay().getStoryActions().containsKey(SUB), extra);
    }
  }

  @Test
  void wrongProgressUnknownOrMismatchedAreaAndInvalidCountsCannotSatisfyTheObjective() {
    mission(",\"Progress\":1");
    var p = new GameplayProgressTest.TestPlayer();
    win(p, AREA);
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    GameplayDefinitions.areas.put(AREA, new GameplayDefinitions.Area(AREA + 1, PROGRESS, 1, 0, 0));
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    GameplayDefinitions.areas.put(AREA, new GameplayDefinitions.Area(AREA, PROGRESS + 1, 1, 0, 0));
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    area();
    for (Integer count : Arrays.asList(null, -1, 0)) {
      p.getGameplay().getRogueWins().put(AREA, count);
      p.getMissionManager().refresh();
      assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    }
    p.getGameplay().getRogueWins().put(AREA, 1);
    p.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, p.getMissionManager().sub(SUB).getPhase());
  }

  @Test
  void failedAndAbandonedRunsDoNotCreateSuccessfulHistoryOrCompleteAcceptedObjectives() {
    area();
    mission(",\"Progress\":1");
    for (var outcome :
        List.of(
            GameplayState.RogueRun.Phase.EXPLORING,
            GameplayState.RogueRun.Phase.FAILED,
            GameplayState.RogueRun.Phase.ABANDONED)) {
      var p = new GameplayProgressTest.TestPlayer();
      assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
      var run = new GameplayState.RogueRun(AREA, 1999601, Map.of(1, 19996001));
      p.getGameplay().setRogue(run);
      if (outcome != GameplayState.RogueRun.Phase.EXPLORING) run.finish(outcome);
      assertFalse(p.getGameplay().recordRogueWin(run));
      p.getMissionManager().refresh();
      assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
      assertTrue(p.getGameplay().getRogueWins().isEmpty());
    }
  }

  void incrementAction() {
    var sub = GameplayDefinitions.subs.get(SUB);
    sub.setFinishActionList(
        new com.google.gson.Gson()
            .fromJson(
                "[{\"FinishActionType\":\"AddCustomValue\",\"FinishActionParaString\":[\"win\",\"1\"]}]",
                MissionInfo.FinishAction[].class));
  }

  @Test
  void concurrentRefreshCompletesTheDeclaredActionOnceAndCapsProgress() throws Exception {
    area();
    mission(",\"Progress\":2");
    incrementAction();
    var p = new GameplayProgressTest.TestPlayer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    win(p, AREA);
    win(p, AREA);
    win(p, AREA);
    try (var pool = Executors.newFixedThreadPool(6)) {
      var ready = new CountDownLatch(6);
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 6; i++)
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                  } catch (InterruptedException e) {
                    throw new AssertionError(e);
                  }
                  p.getMissionManager().refresh();
                }));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      for (var future : futures) future.get(5, TimeUnit.SECONDS);
    }
    assertEquals(2, p.getMissionManager().sub(SUB).getProgress());
    assertEquals(1, p.getGameplay().getStoryValues().get("mission:" + MAIN + ":win"));
    assertFalse(p.getGameplay().getStoryActions().get(SUB).isPending());
  }

  @Test
  void actualMorphiaReloadPreservesProgressAndDoesNotRepeatTheFinishAction() throws Exception {
    area();
    mission(",\"Progress\":2");
    incrementAction();
    var p = new GameplayProgressTest.TestPlayer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    win(p, AREA);
    p.getMissionManager().refresh();
    assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "mission-universe-victory");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456792);
      var state = Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(document, p.getGameplay());
      store.save(document);
      state.set(p, store.find(Player.class).first().getGameplay());
      p.getMissionManager().refresh();
      assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
      win(p, AREA);
      p.getMissionManager().refresh();
      state.set(document, p.getGameplay());
      store.save(document);
      state.set(p, store.find(Player.class).first().getGameplay());
      p.getMissionManager().refresh();
      assertEquals(2, p.getMissionManager().sub(SUB).getProgress());
      assertEquals(1, p.getGameplay().getStoryValues().get("mission:" + MAIN + ":win"));
      assertEquals(2, p.getGameplay().getRogueWins().get(AREA));
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void cancelledObjectivesAndUnsupportedActionsStayPendingEvenAfterRecordedVictory() {
    area();
    mission(",\"Progress\":1");
    var p = new GameplayProgressTest.TestPlayer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    win(p, AREA);
    p.getGameplay().getCancelledSubs().add(SUB);
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    p.getGameplay().getCancelledSubs().clear();
    var sub = GameplayDefinitions.subs.get(SUB);
    sub.setFinishActionList(
        new com.google.gson.Gson()
            .fromJson(
                "[{\"FinishActionType\":\"UnknownAction\"}]", MissionInfo.FinishAction[].class));
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    assertFalse(p.getGameplay().getStoryActions().containsKey(SUB));
    p.getGameplay().getCancelledMissions().add(MAIN);
    sub.setFinishActionList(new MissionInfo.FinishAction[0]);
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
  }
}
