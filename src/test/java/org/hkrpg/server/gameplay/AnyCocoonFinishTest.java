package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.util.*;
import org.junit.jupiter.api.*;

class AnyCocoonFinishTest {
  static final int QUEST = 19997021, RULE = 19997022;
  final MissionFarmingBattleTest fixture = new MissionFarmingBattleTest();

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameData.getQuestExcelMap().keySet().remove(QUEST);
    GameData.getFinishWayExcelMap().keySet().remove(RULE);
  }

  void row(String type) {
    GameData.getCocoonExcelMap()
        .add(
            fixture.gson.fromJson(
                "{\"ID\":"
                    + MissionFarmingBattleTest.COCOON
                    + ",\"WorldLevel\":0,\"CocoonType\":\""
                    + type
                    + "\",\"MappingInfoID\":"
                    + MissionFarmingBattleTest.COCOON
                    + ",\"StageIDList\":[199969221],\"StaminaCost\":10,\"MaxChallengeCnt\":6}",
                CocoonExcel.class));
  }

  Battle battle(MissionFarmingBattleTest.Farmer p, int waves) {
    var base = fixture.battle(p, StageType.Cocoon);
    var b = new Battle(p, p.getCurrentLineup(), Collections.nCopies(waves, base.getStage()));
    b.setCocoonId(MissionFarmingBattleTest.COCOON);
    b.setCocoonWave(waves);
    b.setStaminaCost(waves * 10);
    p.setBattle(b);
    return b;
  }

  Battle finish(MissionFarmingBattleTest.Farmer p, Battle b, BattleEndStatus outcome) {
    return new MissionFarmingBattleTest.Service()
        .finishBattle(p, b.getId(), b.getStage().getId(), outcome, BattleStatistics.newInstance());
  }

  void quest(int kind, int category, int goal) {
    GameData.getQuestExcelMap()
        .add(
            fixture.gson.fromJson(
                "{\"QuestID\":"
                    + QUEST
                    + ",\"QuestType\":"
                    + kind
                    + ",\"FinishWayID\":"
                    + RULE
                    + ",\"UnlockType\":\"AutoUnlock\"}",
                QuestExcel.class));
    GameData.getFinishWayExcelMap()
        .add(
            fixture.gson.fromJson(
                "{\"ID\":"
                    + RULE
                    + ",\"FinishType\":\"AnyCocoonFinish\",\"ParamType\":\"ListContain\",\"ParamIntList\":["
                    + category
                    + "],\"Progress\":"
                    + goal
                    + "}",
                FinishWayExcel.class));
  }

  @Test
  void relicVictoryCompletesTheAcceptedCategoryThreeStoryTarget() {
    row("TYPE_RELIC");
    fixture.mission("AnyCocoonFinish", ",\"ParamType\":\"ListContain\",\"ParamIntList\":[3]");
    var p = new MissionFarmingBattleTest.Farmer();
    p.getMissionManager().accept(MissionFarmingBattleTest.MAIN);
    assertNotNull(finish(p, battle(p, 1), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, p.getMissionManager().sub(MissionFarmingBattleTest.SUB).getProgress());
  }

  @Test
  void weeklyVictoryCompletesTheAcceptedCategoryTwoStoryTarget() {
    row("TYPE_WEEK");
    fixture.mission("AnyCocoonFinish", ",\"ParamType\":\"ListContain\",\"ParamIntList\":[2]");
    var p = new MissionFarmingBattleTest.Farmer();
    p.getMissionManager().accept(MissionFarmingBattleTest.MAIN);
    assertNotNull(finish(p, battle(p, 1), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, p.getMissionManager().sub(MissionFarmingBattleTest.SUB).getProgress());
  }

  @Test
  void normalCocoonAchievementCountsActualAcceptedWavesAndRejectsDuplicateReports() {
    row("TYPE_NORMAL");
    quest(1, 1, 10);
    var p = new MissionFarmingBattleTest.Farmer();
    p.getQuestStates().put(QUEST, new QuestState(QUEST));
    var b = battle(p, 3);
    assertNotNull(finish(p, b, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(3, p.getQuestStates().get(QUEST).getProgress());
    assertEquals(70, p.getStamina());
    assertNull(finish(p, b, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(3, p.getQuestStates().get(QUEST).getProgress());
  }

  @Test
  void aSupportedDailyCategoryIsSelectedAndReceivesOnlyItsMatchingVictory() {
    row("TYPE_RELIC");
    quest(5, 3, 5);
    GameplayDefinitions.dailyPool.add(new GameplayDefinitions.DailyPool(QUEST, 1));
    var p = new MissionFarmingBattleTest.Farmer();
    p.getDailyManager().ensureDay();
    assertNotNull(p.getGameplay().getDailyQuests().get(QUEST));
    assertNotNull(finish(p, battle(p, 3), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(3, p.getGameplay().getDailyQuests().get(QUEST).getProgress());
  }

  @Test
  void nullCategoryListDoesNotAbortAnotherAcceptedVictory() {
    row("TYPE_NORMAL");
    quest(1, 1, 10);
    var config =
        fixture.gson.toJsonTree(GameData.getFinishWayExcelMap().get(RULE)).getAsJsonObject();
    config.add("ParamIntList", com.google.gson.JsonNull.INSTANCE);
    GameData.getFinishWayExcelMap().add(fixture.gson.fromJson(config, FinishWayExcel.class));
    var p = new MissionFarmingBattleTest.Farmer();
    p.getQuestStates().put(QUEST, new QuestState(QUEST));
    assertNotNull(finish(p, battle(p, 1), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, p.getQuestStates().get(QUEST).getProgress());
  }

  @Test
  void staleDailyAndCycleRecordsCannotAccumulateInThePermanentQuestDomain() {
    for (int kind : List.of(3, 5, 2, 0)) {
      row("TYPE_NORMAL");
      quest(kind, 1, 10);
      var p = new MissionFarmingBattleTest.Farmer();
      p.getQuestStates().put(QUEST, new QuestState(QUEST));
      assertNotNull(finish(p, battle(p, 1), BattleEndStatus.BATTLE_END_WIN));
      assertEquals(0, p.getQuestStates().get(QUEST).getProgress(), "questType=" + kind);
    }
  }

  @Test
  void aNewlyAcceptedStageOrSpecificCocoonTargetWaitsForAnotherBattle() {
    for (String type : List.of("StageWin", "CocoonFinish", "AnyCocoonFinish")) {
      row("TYPE_NORMAL");
      String parameter =
          switch (type) {
            case "StageWin" -> ",\"ParamInt1\":199969221";
            case "CocoonFinish" -> ",\"ParamType\":\"ListContain\",\"ParamIntList\":[19996922]";
            default -> ",\"ParamType\":\"ListContain\",\"ParamIntList\":[1]";
          };
      fixture.missions.mission(
          MissionFarmingBattleTest.MAIN,
          "Manual",
          Integer.toString(MissionFarmingBattleTest.SUB + 1),
          "[{\"ID\":199969221,\"MainMissionID\":19996922,\"TakeType\":\"Auto\","
              + "\"FinishType\":\"AnyCocoonFinish\",\"ParamType\":\"ListContain\",\"ParamIntList\":[1]},"
              + "{\"ID\":199969222,\"MainMissionID\":19996922,\"TakeType\":\"MultiSequence\","
              + "\"TakeParamIntList\":[199969221],\"FinishType\":\""
              + type
              + "\""
              + parameter
              + "}]");
      var p = new MissionFarmingBattleTest.Farmer();
      p.getMissionManager().accept(MissionFarmingBattleTest.MAIN);
      assertNotNull(finish(p, battle(p, 1), BattleEndStatus.BATTLE_END_WIN));
      assertEquals(1, p.getMissionManager().sub(MissionFarmingBattleTest.SUB).getProgress());
      assertEquals(
          0, p.getMissionManager().sub(MissionFarmingBattleTest.SUB + 1).getProgress(), type);
      assertNotNull(finish(p, battle(p, 1), BattleEndStatus.BATTLE_END_WIN));
      assertEquals(
          1, p.getMissionManager().sub(MissionFarmingBattleTest.SUB + 1).getProgress(), type);
    }
  }

  @Test
  void wrongCategoriesDoNotCompleteStoryAchievementOrDailyGoals() {
    row("TYPE_WEEK");
    fixture.mission("AnyCocoonFinish", ",\"ParamType\":\"ListContain\",\"ParamIntList\":[3]");
    quest(1, 3, 5);
    var p = new MissionFarmingBattleTest.Farmer();
    p.getMissionManager().accept(MissionFarmingBattleTest.MAIN);
    p.getQuestStates().put(QUEST, new QuestState(QUEST));
    assertNotNull(finish(p, battle(p, 2), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, p.getMissionManager().sub(MissionFarmingBattleTest.SUB).getProgress());
    assertEquals(0, p.getQuestStates().get(QUEST).getProgress());
    quest(5, 3, 5);
    GameplayDefinitions.dailyPool.add(new GameplayDefinitions.DailyPool(QUEST, 1));
    p = new MissionFarmingBattleTest.Farmer();
    p.getDailyManager().ensureDay();
    assertNotNull(finish(p, battle(p, 1), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, p.getGameplay().getDailyQuests().get(QUEST).getProgress());
  }

  @Test
  void invalidRuleRequirementsRemainPending() {
    for (String extra :
        List.of(
            "\"ParamType\":\"Equal\"",
            "\"ParamIntList\":[]",
            "\"ParamIntList\":[1,1]",
            "\"ParamIntList\":[0]",
            "\"ParamIntList\":[4]",
            "\"ParamInt1\":1",
            "\"ParamInt2\":1",
            "\"ParamInt3\":1",
            "\"ParamStr1\":\"unknown\"",
            "\"ParamItemList\":[{\"ItemID\":1,\"ItemNum\":1}]",
            "\"Progress\":0",
            "\"ParamIntList\":[null]")) {
      row("TYPE_NORMAL");
      quest(1, 1, 10);
      var data =
          fixture.gson.toJsonTree(GameData.getFinishWayExcelMap().get(RULE)).getAsJsonObject();
      fixture
          .gson
          .fromJson("{" + extra + "}", com.google.gson.JsonObject.class)
          .entrySet()
          .forEach(e -> data.add(e.getKey(), e.getValue()));
      GameData.getFinishWayExcelMap().add(fixture.gson.fromJson(data, FinishWayExcel.class));
      var p = new MissionFarmingBattleTest.Farmer();
      p.getQuestStates().put(QUEST, new QuestState(QUEST));
      assertNotNull(finish(p, battle(p, 1), BattleEndStatus.BATTLE_END_WIN));
      assertEquals(0, p.getQuestStates().get(QUEST).getProgress(), extra);
    }
  }

  @Test
  void unknownCategoryMissingWorldAndInconsistentBattleMetadataDoNotCount() {
    for (int situation = 0; situation < 7; situation++) {
      row(situation == 0 ? "TYPE_UNKNOWN" : "TYPE_NORMAL");
      quest(1, 1, 10);
      var p = new MissionFarmingBattleTest.Farmer();
      p.getQuestStates().put(QUEST, new QuestState(QUEST));
      var b = battle(p, 1);
      switch (situation) {
        case 1 -> b.setWorldLevel(1);
        case 2 -> b.setCocoonId(MissionFarmingBattleTest.COCOON + 1);
        case 3 -> b.setCocoonWave(2);
        case 4 -> b.setStaminaCost(11);
        case 5 -> b.setCocoonWave(7);
        case 6 -> {
          var data =
              fixture
                  .gson
                  .toJsonTree(GameData.getCocoonExcelMap().get(MissionFarmingBattleTest.COCOON, 0))
                  .getAsJsonObject();
          data.add("StageIDList", fixture.gson.toJsonTree(List.of(199969222)));
          GameData.getCocoonExcelMap().add(fixture.gson.fromJson(data, CocoonExcel.class));
        }
      }
      assertNotNull(finish(p, b, BattleEndStatus.BATTLE_END_WIN));
      assertEquals(0, p.getQuestStates().get(QUEST).getProgress(), "situation=" + situation);
    }
  }

  @Test
  void wrongIdentityInvalidRosterLossQuitAndInsufficientStaminaCannotCount() {
    row("TYPE_NORMAL");
    quest(1, 1, 10);
    var p = new MissionFarmingBattleTest.Farmer();
    p.getQuestStates().put(QUEST, new QuestState(QUEST));
    var b = battle(p, 3);
    var service = new MissionFarmingBattleTest.Service();
    assertNull(
        service.finishBattle(
            p,
            b.getId() + 1,
            b.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    var invalid =
        BattleStatistics.newInstance()
            .addBattleAvatarList(
                emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo.newInstance()
                    .setId(999));
    assertNull(
        service.finishBattle(
            p, b.getId(), b.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, invalid));
    p.stamina = 29;
    assertNull(finish(p, b, BattleEndStatus.BATTLE_END_WIN));
    assertNotNull(finish(p, b, BattleEndStatus.BATTLE_END_LOSE));
    assertNotNull(finish(p, battle(p, 1), BattleEndStatus.BATTLE_END_QUIT));
    assertEquals(0, p.getQuestStates().get(QUEST).getProgress());
    assertEquals(29, p.stamina);
  }

  @Test
  void dailyResetStartsAFreshCategoryCounterAndRejectsAnOldResult() {
    row("TYPE_NORMAL");
    quest(5, 1, 10);
    GameplayDefinitions.dailyPool.add(new GameplayDefinitions.DailyPool(QUEST, 1));
    var p = new MissionFarmingBattleTest.Farmer();
    p.getDailyManager()
        .setClock(
            java.time.Clock.fixed(
                java.time.Instant.parse("2026-10-02T19:59:59Z"), java.time.ZoneOffset.UTC));
    var first = battle(p, 3);
    assertNotNull(finish(p, first, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(3, p.getGameplay().getDailyQuests().get(QUEST).getProgress());
    p.getDailyManager()
        .setClock(
            java.time.Clock.fixed(
                java.time.Instant.parse("2026-10-02T20:00:00Z"), java.time.ZoneOffset.UTC));
    p.getDailyManager().ensureDay();
    assertEquals(0, p.getGameplay().getDailyQuests().get(QUEST).getProgress());
    assertNull(finish(p, first, BattleEndStatus.BATTLE_END_WIN));
    assertNotNull(finish(p, battle(p, 1), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, p.getGameplay().getDailyQuests().get(QUEST).getProgress());
  }

  @Test
  void concurrentVictoryReportsCreditTheAcceptedWavesOnce() throws Exception {
    row("TYPE_NORMAL");
    quest(1, 1, 20);
    var p = new MissionFarmingBattleTest.Farmer();
    p.getQuestStates().put(QUEST, new QuestState(QUEST));
    var b = battle(p, 6);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var futures = new ArrayList<java.util.concurrent.Future<Battle>>();
      for (int i = 0; i < 6; i++)
        futures.add(
            executor.submit(
                () -> {
                  assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                  return finish(p, b, BattleEndStatus.BATTLE_END_WIN);
                }));
      start.countDown();
      int accepted = 0;
      for (var future : futures)
        if (future.get(5, java.util.concurrent.TimeUnit.SECONDS) != null) accepted++;
      assertEquals(1, accepted);
    }
    assertEquals(6, p.getQuestStates().get(QUEST).getProgress());
    assertEquals(40, p.stamina);
  }

  @Test
  void clientCompletionCannotBypassTheCategoryBattleRequirement() {
    quest(1, 1, 10);
    var p = new MissionFarmingBattleTest.Farmer();
    p.getQuestStates().put(QUEST, new QuestState(QUEST));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.QUEST_STATUS_ERROR,
        p.getQuestManager().finishByClient(QUEST));
    assertEquals(0, p.getQuestStates().get(QUEST).getProgress());
  }

  @Test
  void permanentQuestTypeFourAndMultipleAllowedCategoriesUseTheSameAcceptedEvent() {
    row("TYPE_WEEK");
    quest(4, 2, 10);
    var data = fixture.gson.toJsonTree(GameData.getFinishWayExcelMap().get(RULE)).getAsJsonObject();
    data.add("ParamIntList", fixture.gson.toJsonTree(List.of(1, 2, 3)));
    GameData.getFinishWayExcelMap().add(fixture.gson.fromJson(data, FinishWayExcel.class));
    var p = new MissionFarmingBattleTest.Farmer();
    p.getQuestStates().put(QUEST, new QuestState(QUEST));
    assertNotNull(finish(p, battle(p, 2), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(2, p.getQuestStates().get(QUEST).getProgress());
  }

  @Test
  void actualMorphiaReloadRetainsCategoryCountsAndDoesNotReplayFinishedStoryActions()
      throws Exception {
    row("TYPE_NORMAL");
    quest(1, 1, 5);
    fixture.mission("AnyCocoonFinish", ",\"ParamType\":\"ListContain\",\"ParamIntList\":[1]");
    fixture.incrementAction();
    var p = new MissionFarmingBattleTest.Farmer();
    p.getMissionManager().accept(MissionFarmingBattleTest.MAIN);
    p.getQuestStates().put(QUEST, new QuestState(QUEST));
    assertNotNull(finish(p, battle(p, 3), BattleEndStatus.BATTLE_END_WIN));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "cocoon-categories");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      var quests = emu.lunarcore.game.player.Player.class.getDeclaredField("questStates");
      uid.setAccessible(true);
      gameplay.setAccessible(true);
      quests.setAccessible(true);
      uid.setInt(document, 123456831);
      for (int iteration = 0; iteration < 2; iteration++) {
        gameplay.set(document, p.getGameplay());
        quests.set(document, p.getQuestStates());
        store.save(document);
        var loaded = store.find(emu.lunarcore.game.player.Player.class).first();
        gameplay.set(p, loaded.getGameplay());
        quests.set(p, loaded.getQuestStates());
        assertEquals(iteration == 0 ? 3 : 5, p.getQuestStates().get(QUEST).getProgress());
        assertNotNull(finish(p, battle(p, 2), BattleEndStatus.BATTLE_END_WIN));
        assertEquals(5, p.getQuestStates().get(QUEST).getProgress());
        assertEquals(
            1,
            p.getGameplay()
                .getStoryValues()
                .get("mission:" + MissionFarmingBattleTest.MAIN + ":farm"));
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void oneRelicChallengeWithTwoMonsterWavesCountsAsOneCompletion() {
    row("TYPE_RELIC");
    quest(1, 3, 10);
    var p = new MissionFarmingBattleTest.Farmer();
    p.getQuestStates().put(QUEST, new QuestState(QUEST));
    var stage =
        new emu.lunarcore.game.battle.BattleStage() {
          public int getId() {
            return 199969221;
          }

          public StageType getStageType() {
            return StageType.Cocoon;
          }

          public List<it.unimi.dsi.fastutil.ints.IntList> getMonsterWaves() {
            return List.of(
                new it.unimi.dsi.fastutil.ints.IntArrayList(new int[] {1}),
                new it.unimi.dsi.fastutil.ints.IntArrayList(new int[] {2}));
          }
        };
    var b = new Battle(p, p.getCurrentLineup(), stage);
    b.setCocoonId(MissionFarmingBattleTest.COCOON);
    b.setCocoonWave(1);
    b.setStaminaCost(10);
    p.setBattle(b);
    assertEquals(2, b.getWaves().size());
    assertNotNull(finish(p, b, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, p.getQuestStates().get(QUEST).getProgress());
  }
}
