package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.CocoonExcel;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.player.lineup.*;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import it.unimi.dsi.fastutil.ints.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class MissionFarmingBattleTest {
  static final int MAIN = 19996922, SUB = 199969221, COCOON = 19996922;
  final GameplayProgressTest missions = new GameplayProgressTest();
  final Gson gson = new Gson();

  static class Farmer extends GameplayProgressTest.TestPlayer {
    final LineupManager teams = new LineupManager(this);

    public LineupManager getLineupManager() {
      return teams;
    }

    public boolean removeFoodBuffs(int type) {
      return false;
    }

    int stamina = 100;

    public int getStamina() {
      return stamina;
    }

    public void spendStamina(int amount) {
      stamina -= amount;
    }
  }

  static class Service extends BattleService {
    Service() {
      super(null);
    }

    protected void grantDrops(Battle battle) {}
  }

  void mission(String type, String parameters) {
    missions.mission(
        MAIN,
        "Manual",
        Integer.toString(SUB),
        "[{\"ID\":"
            + SUB
            + ",\"MainMissionID\":"
            + MAIN
            + ",\"TakeType\":\"Auto\",\"FinishType\":\""
            + type
            + "\""
            + parameters
            + "}]");
  }

  @AfterEach
  void cleanup() {
    missions.reset();
    GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    GameData.getMissionInfos().remove(MAIN);
    GameData.getCocoonExcelMap().values().removeIf(row -> row.getCocoonId() == COCOON);
  }

  Battle battle(Farmer p, StageType type) {
    BattleStage stage =
        new BattleStage() {
          public int getId() {
            return 199969221;
          }

          public StageType getStageType() {
            return type;
          }

          public List<IntList> getMonsterWaves() {
            return List.of(new IntArrayList(new int[] {1}));
          }
        };
    var battle = new Battle(p, p.getCurrentLineup(), stage);
    p.setBattle(battle);
    return battle;
  }

  @Test
  void confirmedCocoonVictoryCompletesOnlyItsAcceptedDeclaredCocoon() {
    GameData.getCocoonExcelMap()
        .add(gson.fromJson("{\"ID\":" + COCOON + ",\"WorldLevel\":0}", CocoonExcel.class));
    mission("CocoonFinish", ",\"ParamType\":\"ListContain\",\"ParamIntList\":[" + COCOON + "]");
    var p = new Farmer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    var battle = battle(p, StageType.Cocoon);
    battle.setCocoonId(COCOON);
    battle.setCocoonWave(1);
    assertNotNull(
        new Service()
            .finishBattle(
                p,
                battle.getId(),
                battle.getStage().getId(),
                BattleEndStatus.BATTLE_END_WIN,
                BattleStatistics.newInstance()));
    assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void confirmedFarmElementVictoryCompletesAnAcceptedNoParameterObjective() {
    mission("AnyFarmElementFinish", ",\"ParamType\":\"NoPara\"");
    var p = new Farmer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    var battle = battle(p, StageType.FarmElement);
    assertNotNull(
        new Service()
            .finishBattle(
                p,
                battle.getId(),
                battle.getStage().getId(),
                BattleEndStatus.BATTLE_END_WIN,
                BattleStatistics.newInstance()));
    assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void wrongBattleIdentityInvalidStatisticsLossAndQuitDoNotCompleteTheObjective() {
    mission("AnyFarmElementFinish", ",\"ParamType\":\"NoPara\"");
    var p = new Farmer();
    var service = new Service();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    var current = battle(p, StageType.FarmElement);
    assertNull(
        service.finishBattle(
            p,
            current.getId() + 1,
            current.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertNull(
        service.finishBattle(
            p,
            current.getId(),
            current.getStage().getId() + 1,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    var stats = BattleStatistics.newInstance();
    stats.addBattleAvatarList(
        emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo.newInstance().setId(999));
    assertNull(
        service.finishBattle(
            p, current.getId(), current.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, stats));
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    assertNotNull(
        service.finishBattle(
            p,
            current.getId(),
            current.getStage().getId(),
            BattleEndStatus.BATTLE_END_LOSE,
            BattleStatistics.newInstance()));
    current = battle(p, StageType.FarmElement);
    assertNotNull(
        service.finishBattle(
            p,
            current.getId(),
            current.getStage().getId(),
            BattleEndStatus.BATTLE_END_QUIT,
            BattleStatistics.newInstance()));
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void extraRequirementsAndUnknownComparisonsAreNotSilentlyIgnored() {
    for (String extra :
        List.of(
            ",\"ParamType\":\"Equal\"",
            ",\"ParamInt1\":1",
            ",\"ParamInt2\":1",
            ",\"ParamInt3\":1",
            ",\"ParamStr1\":\"unknown\"",
            ",\"ParamIntList\":[1]",
            ",\"ParamItemList\":[{\"ItemID\":1,\"ItemNum\":1}]",
            ",\"Progress\":2")) {
      mission("AnyFarmElementFinish", ",\"ParamType\":\"NoPara\"" + extra);
      var p = new Farmer();
      assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
      var current = battle(p, StageType.FarmElement);
      assertNotNull(
          new Service()
              .finishBattle(
                  p,
                  current.getId(),
                  current.getStage().getId(),
                  BattleEndStatus.BATTLE_END_WIN,
                  BattleStatistics.newInstance()));
      assertEquals(0, p.getMissionManager().sub(SUB).getProgress(), extra);
    }
  }

  @Test
  void unknownCocoonMissingWorldRowUnstartedWavesAndWrongSceneCannotComplete() {
    for (int situation = 0; situation < 6; situation++) {
      GameData.getCocoonExcelMap().add(gson.fromJson("{\"ID\":" + COCOON + "}", CocoonExcel.class));
      mission("CocoonFinish", ",\"ParamType\":\"ListContain\",\"ParamIntList\":[" + COCOON + "]");
      var p = new Farmer();
      if (situation == 4) GameplayDefinitions.subs.get(SUB).setLevelFloorID(19996);
      if (situation == 5)
        GameplayDefinitions.subs.get(SUB).setParamIntList(new Integer[] {COCOON, COCOON});
      assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
      var current = battle(p, StageType.Cocoon);
      current.setCocoonId(situation == 0 ? COCOON + 1 : COCOON);
      current.setWorldLevel(situation == 1 ? 1 : 0);
      current.setCocoonWave(situation == 2 ? 0 : 1);
      if (situation == 3)
        GameplayDefinitions.subs.get(SUB).setParamIntList(new Integer[] {COCOON + 1});
      assertNotNull(
          new Service()
              .finishBattle(
                  p,
                  current.getId(),
                  current.getStage().getId(),
                  BattleEndStatus.BATTLE_END_WIN,
                  BattleStatistics.newInstance()));
      assertEquals(0, p.getMissionManager().sub(SUB).getProgress(), "situation=" + situation);
    }
  }

  @Test
  void newlyUnlockedFarmingObjectiveWaitsForTheNextActualBattle() {
    missions.mission(
        MAIN,
        "Manual",
        (SUB + "," + (SUB + 1)),
        "[{\"ID\":"
            + SUB
            + ",\"MainMissionID\":"
            + MAIN
            + ",\"TakeType\":\"Auto\",\"FinishType\":\"AnyFarmElementFinish\",\"ParamType\":\"NoPara\"},"
            + "{\"ID\":"
            + (SUB + 1)
            + ",\"MainMissionID\":"
            + MAIN
            + ",\"TakeType\":\"MultiSequence\",\"TakeParamIntList\":["
            + SUB
            + "],\"FinishType\":\"AnyFarmElementFinish\",\"ParamType\":\"NoPara\"}]");
    var p = new Farmer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    var service = new Service();
    var first = battle(p, StageType.FarmElement);
    assertNotNull(
        service.finishBattle(
            p,
            first.getId(),
            first.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
    assertEquals(0, p.getMissionManager().sub(SUB + 1).getProgress());
    assertNull(
        service.finishBattle(
            p,
            first.getId(),
            first.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    var second = battle(p, StageType.FarmElement);
    assertNotNull(
        service.finishBattle(
            p,
            second.getId(),
            second.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertEquals(1, p.getMissionManager().sub(SUB + 1).getProgress());
  }

  void incrementAction() {
    GameplayDefinitions.subs
        .get(SUB)
        .setFinishActionList(
            gson.fromJson(
                "[{\"FinishActionType\":\"AddCustomValue\",\"FinishActionParaString\":[\"farm\",\"1\"]}]",
                emu.lunarcore.data.config.MissionInfo.FinishAction[].class));
  }

  @Test
  void concurrentBattleSettlementFinishesItsStoryActionExactlyOnce() throws Exception {
    mission("AnyFarmElementFinish", ",\"ParamType\":\"NoPara\"");
    incrementAction();
    var p = new Farmer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    var current = battle(p, StageType.FarmElement);
    var service = new Service();
    int finished = 0;
    try (var pool = Executors.newFixedThreadPool(6)) {
      var ready = new CountDownLatch(6);
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<Battle>>();
      for (int i = 0; i < 6; i++)
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  return service.finishBattle(
                      p,
                      current.getId(),
                      current.getStage().getId(),
                      BattleEndStatus.BATTLE_END_WIN,
                      BattleStatistics.newInstance());
                }));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      for (var f : futures) if (f.get(5, TimeUnit.SECONDS) != null) finished++;
    }
    assertEquals(1, finished);
    assertEquals(1, p.getGameplay().getStoryValues().get("mission:" + MAIN + ":farm"));
    assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void actualMorphiaReloadDoesNotRepeatFinishedFarmingActionsOnAnotherVictory() throws Exception {
    mission("AnyFarmElementFinish", ",\"ParamType\":\"NoPara\"");
    incrementAction();
    var p = new Farmer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    var service = new Service();
    var current = battle(p, StageType.FarmElement);
    assertNotNull(
        service.finishBattle(
            p,
            current.getId(),
            current.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "mission-farming-victory");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456793);
      var state = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(document, p.getGameplay());
      store.save(document);
      state.set(p, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      current = battle(p, StageType.FarmElement);
      assertNotNull(
          service.finishBattle(
              p,
              current.getId(),
              current.getStage().getId(),
              BattleEndStatus.BATTLE_END_WIN,
              BattleStatistics.newInstance()));
      assertEquals(1, p.getGameplay().getStoryValues().get("mission:" + MAIN + ":farm"));
      assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void actualCocoonFactoryUsesItsSavedBattleIdentityWavesAndStamina() {
    int stageId = 199969221;
    var oldStage = GameData.getStageExcelMap().get(stageId);
    var stage =
        gson.fromJson(
            "{\"StageID\":"
                + stageId
                + ",\"StageType\":\"Cocoon\",\"MonsterList\":[{\"Monster0\":1}]}",
            emu.lunarcore.data.excel.StageExcel.class);
    stage.onLoad();
    GameData.getStageExcelMap().add(stage);
    try {
      GameData.getCocoonExcelMap()
          .add(
              gson.fromJson(
                  "{\"ID\":"
                      + COCOON
                      + ",\"WorldLevel\":1,\"MappingInfoID\":12,\"StageIDList\":["
                      + stageId
                      + "],\"MaxChallengeCnt\":6,\"StaminaCost\":10}",
                  CocoonExcel.class));
      mission("CocoonFinish", ",\"ParamType\":\"ListContain\",\"ParamIntList\":[" + COCOON + "]");
      var p = new Farmer();
      assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
      var service = new Service();
      var current = service.startCocoon(p, COCOON, 1, 3);
      assertNotNull(current);
      assertEquals(3, current.getWaves().size());
      assertEquals(30, current.getStaminaCost());
      assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
      assertNotNull(
          service.finishBattle(
              p,
              current.getId(),
              stageId,
              BattleEndStatus.BATTLE_END_WIN,
              BattleStatistics.newInstance()));
      assertEquals(70, p.stamina);
      assertEquals(1, p.getMissionManager().sub(SUB).getProgress());
    } finally {
      if (oldStage == null) GameData.getStageExcelMap().keySet().remove(stageId);
      else GameData.getStageExcelMap().add(oldStage);
    }
  }
}
