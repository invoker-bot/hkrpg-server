package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.server.game.GameSession;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseFinish46Test {
  static final int AREA = 19996, MAP = 1999601, ROOM = 19996001, ITEM = 19996901, REWARD = 19996901;
  final Gson gson = new Gson();
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path scoreRoot;

  void scoreResources() throws Exception {
    var folder = java.nio.file.Files.createDirectories(scoreRoot.resolve("ExcelOutput"));
    java.nio.file.Files.writeString(
        folder.resolve("ConstValueRogue.json"),
        """
        [{"ConstRogueName":"Rogue_Explore_Score_To_Talent_Coin_Rate","ConstValue":"0.1"}]
        """);
    GameplayDefinitions.load(scoreRoot);
    fixture();
    GameplayDefinitions.areas.put(
        AREA,
        gson.fromJson(
            "{\"RogueAreaID\":"
                + AREA
                + ",\"AreaProgress\":19996,\"Difficulty\":1,\"ScoreMap\":{\"1\":265}}",
            GameplayDefinitions.Area.class));
  }

  @Test
  void actualRoomScoresProduceTalentPointsAndNotifyTheVerifiedCurrencyFieldOnce() throws Exception {
    scoreResources();
    var s = new Session();
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    s.explorer.getUniverseManager().onBattleFinish(b);
    assertEquals(26, s.explorer.getTalentPoints());
    assertEquals(265, Gameplay46.number(finish(s), "GHCBFCIGIBF"));
    assertEquals(265, Gameplay46.number(finish(s), "score_id"));
    assertEquals(26, Gameplay46.number(finish(s), "GEBGGMAGDJB"));
    var balance =
        (DynamicMessage)
            Gameplay46.value(s.last("SyncRogueVirtualItemInfoScNotify"), "NIKDPODBIEF");
    assertEquals(26, Gameplay46.number(balance, "OFHBBKOKANF"));
    s.explorer.getUniverseManager().onBattleFinish(b);
    s.query();
    assertEquals(26, s.explorer.getTalentPoints());
    assertEquals(1, Collections.frequency(s.names, "SyncRogueVirtualItemInfoScNotify"));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-score-restore");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456780);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.explorer.getGameplay());
      assertTrue(document.tryChangeTalentPoints(26));
      store.save(document);
      var restored = store.find(Player.class).first();
      var resumed =
          new Session() {
            public Player getPlayer() {
              return restored;
            }
          };
      resumed.query();
      resumed.query();
      assertEquals(26, restored.getTalentPoints());
      assertTrue(
          restored.getGameplay().getRogueResults().values().iterator().next().isEarningsSettled());
      assertEquals(26, Gameplay46.number(finish(resumed), "GEBGGMAGDJB"));
      assertEquals(265, Gameplay46.number(finish(resumed), "GHCBFCIGIBF"));
      assertEquals(1, Collections.frequency(resumed.names, "SyncRogueFinishScNotify"));
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void defeatPaysOnlyActuallyClearedRoomsAndNeverTheUnclearedRoomOrSiteId() throws Exception {
    scoreResources();
    GameplayDefinitions.maps.put(
        MAP,
        List.of(
            new GameplayDefinitions.Site(MAP, 91, true, List.of(99), 1),
            new GameplayDefinitions.Site(MAP, 99, false, List.of(), 1)));
    GameplayDefinitions.roomPool.put(99, List.of(ROOM));
    try {
      var s = new Session();
      var won = battle(s, BattleEndStatus.BATTLE_END_WIN);
      s.explorer.getUniverseManager().onBattleFinish(won);
      assertEquals(0, s.explorer.getTalentPoints());
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC, s.explorer.getUniverseManager().enter(99));
      var group = new GroupInfo();
      group.setId(8);
      var monster =
          new EntityMonster(
              s.explorer.getScene(),
              new NpcMonsterExcel(),
              group,
              gson.fromJson("{\"ID\":1,\"Pos\":{},\"Rot\":{}}", MonsterInfo.class));
      var failed =
          new Battle(
              s.explorer,
              s.explorer.getCurrentLineup(),
              gson.fromJson("{\"StageID\":19996901}", StageExcel.class),
              false);
      failed.getNpcMonsters().add(monster);
      failed.setResult(BattleEndStatus.BATTLE_END_LOSE);
      s.explorer.getUniverseManager().onBattleFinish(failed);
      assertEquals(1, Gameplay46.number(finish(s), "GMIIMAHLIJF"));
      assertEquals(2, Gameplay46.number(finish(s), "HEOMALBNJLL"));
      assertEquals(265, Gameplay46.number(finish(s), "GHCBFCIGIBF"));
      assertEquals(26, s.explorer.getTalentPoints());
      assertEquals(0, s.attempts);
    } finally {
      GameplayDefinitions.roomPool.remove(99);
    }
  }

  @Test
  void completionFreezesScoreAndConversionBeforeAnyPersistenceCallback() throws Exception {
    scoreResources();
    var s = new Session();
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    s.afterResultSaved =
        () -> {
          GameplayDefinitions.areas.put(
              AREA,
              gson.fromJson(
                  "{\"RogueAreaID\":" + AREA + ",\"ScoreMap\":{\"1\":1000}}",
                  GameplayDefinitions.Area.class));
          GameplayDefinitions.rogueConstants.put("Rogue_Explore_Score_To_Talent_Coin_Rate", "0.9");
        };
    s.explorer.getUniverseManager().onBattleFinish(b);
    assertEquals(26, s.explorer.getTalentPoints());
    assertEquals(265, Gameplay46.number(finish(s), "GHCBFCIGIBF"));
    assertEquals(26, Gameplay46.number(finish(s), "GEBGGMAGDJB"));
    s.query();
    assertEquals(26, s.explorer.getTalentPoints());
  }

  @Test
  void overflowKeepsFrozenEarningsPendingAndNeverReportsThemAsAlreadyPaid() throws Exception {
    scoreResources();
    var s = new Session();
    var points = Player.class.getDeclaredField("talentPoints");
    points.setAccessible(true);
    points.setInt(s.explorer, Integer.MAX_VALUE - 5);
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    s.explorer.getUniverseManager().onBattleFinish(b);
    var result = s.explorer.getGameplay().getRogueResults().values().iterator().next();
    assertTrue(result.isEarningsReady());
    assertFalse(result.isEarningsSettled());
    assertEquals(0, Gameplay46.number(finish(s), "GEBGGMAGDJB"));
    assertEquals(Integer.MAX_VALUE - 5, s.explorer.getTalentPoints());
    GameplayDefinitions.rogueConstants.put("Rogue_Explore_Score_To_Talent_Coin_Rate", "0.9");
    assertTrue(s.explorer.tryChangeTalentPoints(-100));
    s.explorer.getUniverseManager().settleResults();
    assertTrue(result.isEarningsSettled());
    assertEquals(Integer.MAX_VALUE - 79, s.explorer.getTalentPoints());
    s.query();
    assertEquals(Integer.MAX_VALUE - 79, s.explorer.getTalentPoints());
  }

  @Test
  void missingScoreRowsCannotBeExtrapolatedOrRetroactivelyInventedByQueries() throws Exception {
    scoreResources();
    GameplayDefinitions.areas.put(
        AREA,
        gson.fromJson(
            "{\"RogueAreaID\":"
                + AREA
                + ",\"AreaProgress\":19996,\"Difficulty\":1,\"ScoreMap\":{\"2\":500}}",
            GameplayDefinitions.Area.class));
    var s = new Session();
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    s.explorer.getUniverseManager().onBattleFinish(b);
    var result = s.explorer.getGameplay().getRogueResults().values().iterator().next();
    assertFalse(result.isEarningsReady());
    assertEquals(0, s.explorer.getTalentPoints());
    assertEquals(0, Gameplay46.number(finish(s), "GEBGGMAGDJB"));
    GameplayDefinitions.areas.put(
        AREA,
        gson.fromJson(
            "{\"RogueAreaID\":" + AREA + ",\"ScoreMap\":{\"1\":265}}",
            GameplayDefinitions.Area.class));
    s.query();
    assertFalse(result.isEarningsReady());
    assertEquals(0, s.explorer.getTalentPoints());
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final List<String> names = new ArrayList<>();
    final List<DynamicMessage> packets = new ArrayList<>();
    int attempts, granted;
    boolean complete = true;
    Runnable afterResultSaved;
    final UniverseLifecycleTest.Explorer explorer =
        new UniverseLifecycleTest.Explorer() {
          Scene current;
          final Inventory inventory =
              new Inventory(this) {
                public GrantResult grantItems(ItemParamMap rewards, boolean hint) {
                  attempts++;
                  granted += rewards.get(ITEM);
                  var item = new GameItem(ITEM, rewards.get(ITEM));
                  item.setCount(rewards.get(ITEM));
                  return new GrantResult(List.of(item), complete);
                }
              };

          public Inventory getInventory() {
            return inventory;
          }

          public void save() {
            if (!getGameplay().getRogueResults().isEmpty() && afterResultSaved != null) {
              var action = afterResultSaved;
              afterResultSaved = null;
              action.run();
            }
          }

          public boolean isOnline() {
            return true;
          }

          public GameSession getSession() {
            return Session.this;
          }

          public Scene getScene() {
            if (current == null || current.getPlaneId() != getPlaneId()) current = super.getScene();
            return current;
          }
        };

    public Player getPlayer() {
      return explorer;
    }

    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      names.add(name);
      packets.add(response);
    }

    DynamicMessage last(String name) {
      for (int i = names.size() - 1; i >= 0; i--)
        if (names.get(i).equals(name)) return packets.get(i);
      fail("Missing native packet " + name + "; got " + names);
      return null;
    }

    void query() throws Exception {
      request("GetRogueInfoCsReq");
    }
  }

  @BeforeEach
  void fixture() {
    GameplayDefinitions.areas.put(AREA, new GameplayDefinitions.Area(AREA, 19996, 1, REWARD, 0));
    GameplayDefinitions.maps.put(
        MAP, List.of(new GameplayDefinitions.Site(MAP, 91, true, List.of(), 1)));
    GameplayDefinitions.rooms.put(ROOM, new GameplayDefinitions.Room(ROOM, 1, 12, 8));
    GameplayDefinitions.roomPool.put(91, List.of(ROOM));
    // The room pool is keyed by SiteID, while the terminal site deliberately differs from a count.
    GameData.getItemExcelMap()
        .add(gson.fromJson("{\"ID\":" + ITEM + ",\"ItemMainType\":\"Material\"}", ItemExcel.class));
    reward(7);
  }

  void reward(int quantity) {
    var config =
        gson.fromJson(
            "{\"RewardID\":" + REWARD + ",\"ItemID_1\":" + ITEM + ",\"Count_1\":" + quantity + "}",
            RewardExcel.class);
    config.onLoad();
    GameData.getRewardExcelMap().add(config);
  }

  @AfterEach
  void cleanup() {
    GameplayDefinitions.areas.remove(AREA);
    GameplayDefinitions.maps.remove(MAP);
    GameplayDefinitions.rooms.remove(ROOM);
    GameplayDefinitions.roomPool.remove(91);
    GameData.getItemExcelMap().keySet().remove(ITEM);
    GameData.getRewardExcelMap().keySet().remove(REWARD);
  }

  Battle battle(Session session, BattleEndStatus outcome) {
    var p = session.explorer;
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, p.getUniverseManager().start(AREA));
    var group = new GroupInfo();
    group.setId(8);
    var monster =
        new EntityMonster(
            p.getScene(),
            new NpcMonsterExcel(),
            group,
            gson.fromJson("{\"ID\":1,\"Pos\":{},\"Rot\":{}}", MonsterInfo.class));
    var battle =
        new Battle(
            p,
            p.getCurrentLineup(),
            gson.fromJson("{\"StageID\":19996901}", StageExcel.class),
            false);
    battle.getNpcMonsters().add(monster);
    battle.setResult(outcome);
    return battle;
  }

  DynamicMessage finish(Session s) {
    return (DynamicMessage) Gameplay46.value(s.last("SyncRogueFinishScNotify"), "NLEOMLNOKCC");
  }

  @Test
  void terminalWinReportsActualCountsRosterAndNewFirstRewardBeforeStatusTriggersLeaving() {
    var s = new Session();
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    s.explorer.getUniverseManager().onBattleFinish(b);
    var result = finish(s);
    assertEquals(true, Gameplay46.value(result, "is_win"));
    assertEquals(AREA, Gameplay46.number(result, "area_id"));
    assertEquals(1, Gameplay46.number(result, "GMIIMAHLIJF"));
    assertEquals(1, Gameplay46.number(result, "HEOMALBNJLL"));
    var record = (DynamicMessage) Gameplay46.value(result, "record_info");
    assertEquals(
        199961, InitialData46Test.field(InitialData46Test.first(record, "avatar_list"), "id"));
    var rewards = (DynamicMessage) Gameplay46.value(result, "LHPNJLBPACA");
    assertEquals(7, InitialData46Test.field(InitialData46Test.first(rewards, "item_list"), "num"));
    assertEquals(true, Gameplay46.value(s.last("SyncRogueExploreWinScNotify"), "ILOFGCAFHAH"));
    assertEquals(
        "RogueStatus_Finish",
        Gameplay46.value(s.last("SyncRogueStatusScNotify"), "status").toString());
    assertTrue(
        s.names.indexOf("SyncRogueFinishScNotify") < s.names.indexOf("SyncRogueStatusScNotify"));
    assertEquals(1, s.explorer.getGameplay().getRogueWins().get(AREA));
    s.explorer.getUniverseManager().onBattleFinish(b);
    assertEquals(1, s.attempts);
    assertEquals(1, Collections.frequency(s.names, "SyncRogueFinishScNotify"));
  }

  @Test
  void defeatHasARealFinishResultWithoutWinRewardsAndUsesTerminalStatusOnQuery() throws Exception {
    var s = new Session();
    var b = battle(s, BattleEndStatus.BATTLE_END_LOSE);
    s.explorer.getUniverseManager().onBattleFinish(b);
    assertEquals(false, Gameplay46.value(finish(s), "is_win"));
    assertEquals(0, Gameplay46.number(finish(s), "GMIIMAHLIJF"));
    assertEquals(1, Gameplay46.number(finish(s), "HEOMALBNJLL"));
    assertFalse(s.names.contains("SyncRogueExploreWinScNotify"));
    assertEquals(0, s.attempts);
    s.query();
    var game = (DynamicMessage) Gameplay46.value(s.last("GetRogueInfoScRsp"), "rogue_game_info");
    assertEquals(
        "RogueStatus_Finish",
        Gameplay46.value((DynamicMessage) Gameplay46.value(game, "CFDIKLBINHK"), "status")
            .toString());
    assertEquals(1, Collections.frequency(s.names, "SyncRogueFinishScNotify"));
  }

  @Test
  void aForeignPlayersBattleCannotFinishThisPlayersRun() {
    var s = new Session();
    var own = battle(s, BattleEndStatus.BATTLE_END_WIN);
    var foreign = new Battle(new Player(), own.getLineup(), own.getStage(), false);
    foreign.getNpcMonsters().addAll(own.getNpcMonsters());
    foreign.setResult(BattleEndStatus.BATTLE_END_LOSE);
    s.explorer.getUniverseManager().onBattleFinish(foreign);
    assertEquals(
        GameplayState.RogueRun.Phase.EXPLORING, s.explorer.getGameplay().getRogue().getPhase());
    assertFalse(s.names.contains("SyncRogueFinishScNotify"));
  }

  @Test
  void partiallyGrantedFirstRewardIsNeverAutomaticallyRepeatedOrShownAsComplete() throws Exception {
    var s = new Session();
    s.complete = false;
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    s.explorer.getUniverseManager().onBattleFinish(b);
    assertEquals(
        0,
        ((List<?>)
                Gameplay46.value(
                    (DynamicMessage) Gameplay46.value(finish(s), "LHPNJLBPACA"), "item_list"))
            .size());
    reward(1000);
    s.complete = true;
    s.query();
    s.query();
    assertEquals(1, s.attempts);
    assertEquals(7, s.granted);
    assertFalse(s.explorer.getRewardLedger().granted("rogue-first:" + AREA));
  }

  @Test
  void delayedBattleFromThePreviousSiteCannotClearAnotherSiteInTheSameScene() {
    var s = new Session();
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    var run = s.explorer.getGameplay().getRogue();
    run.getRooms().put(92, ROOM);
    run.enter(92, ROOM);
    GameplayDefinitions.maps.put(
        MAP, List.of(new GameplayDefinitions.Site(MAP, 92, true, List.of(), 1)));
    s.explorer.getUniverseManager().onBattleFinish(b);
    assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run.getPhase());
    assertTrue(run.getCleared().isEmpty());
    assertEquals(0, s.attempts);
  }

  @Test
  void aBattleContainingAnUnrelatedSceneGroupCannotProduceRogueProgress() {
    var s = new Session();
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    var group = new GroupInfo();
    group.setId(99);
    b.getNpcMonsters()
        .add(
            new EntityMonster(
                s.explorer.getScene(),
                new NpcMonsterExcel(),
                group,
                gson.fromJson("{\"ID\":2,\"Pos\":{},\"Rot\":{}}", MonsterInfo.class)));
    s.explorer.getUniverseManager().onBattleFinish(b);
    assertEquals(
        GameplayState.RogueRun.Phase.EXPLORING, s.explorer.getGameplay().getRogue().getPhase());
    assertEquals(0, s.attempts);
  }

  @Test
  void nativeQuitAcknowledgesAnAbandonedResultSoReconnectDoesNotReplayIt() throws Exception {
    var s = new Session();
    battle(s, BattleEndStatus.BATTLE_END_QUIT);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC, s.explorer.getUniverseManager().leave(false));
    var req = Gameplay46.builder(s, "QuitRogueCsReq");
    Gameplay46.set(req, "area_id", AREA);
    VersionedPacketHandler.handle(
        s, s.getProtocol().clientCommand("QuitRogueCsReq"), req.build().toByteArray());
    assertEquals(0, Gameplay46.number(s.last("QuitRogueScRsp"), "retcode"));
    assertEquals(false, Gameplay46.value(finish(s), "is_win"));
    assertTrue(s.explorer.getGameplay().getRogue().isDismissed());
    assertEquals(0, s.attempts);
  }

  @Test
  void completedResultAndItsActualRewardBytesRestoreThroughMorphiaAndReplayOncePerNewSession()
      throws Exception {
    var s = new Session();
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    s.explorer.getUniverseManager().onBattleFinish(b);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var addr = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + addr.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-finish-restore");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(s.explorer, 123456786);
      // Persist a real Player, not the test subclass used to observe notifications and inventory.
      var document = new Player();
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.explorer.getGameplay());
      uid.setInt(document, 123456786);
      store.save(document);
      var loaded = store.find(Player.class).first();
      var resumed =
          new Session() {
            public Player getPlayer() {
              return loaded;
            }
          };
      resumed.query();
      resumed.query();
      assertEquals(1, Collections.frequency(resumed.names, "SyncRogueFinishScNotify"));
      assertArrayEquals(finish(s).toByteArray(), finish(resumed).toByteArray());
      assertTrue(loaded.getRewardLedger().granted("rogue-first:" + AREA));
      assertEquals(1, loaded.getGameplay().getRogueWins().get(AREA));
      assertEquals(1, s.attempts);
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void unreservedRewardKeepsItsFrozenQuantityAndSettlesBeforeStartingTheNextRun() {
    var s = new Session();
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    var item = GameData.getItemExcelMap().get(ITEM);
    s.afterResultSaved = () -> GameData.getItemExcelMap().keySet().remove(ITEM);
    s.explorer.getUniverseManager().onBattleFinish(b);
    String old = s.explorer.getGameplay().getRogue().getRunId();
    assertEquals(0, s.attempts);
    assertFalse(s.explorer.getGameplay().getRewards().containsKey("rogue-first:" + AREA));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC, s.explorer.getUniverseManager().leave(false));
    reward(1000);
    GameData.getItemExcelMap().add(item);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC, s.explorer.getUniverseManager().start(AREA));
    assertEquals(1, s.attempts);
    assertEquals(7, s.granted);
    assertTrue(s.explorer.getGameplay().getRogueResults().get(old).isRewardSettled());
    assertNotEquals(old, s.explorer.getGameplay().getRogue().getRunId());
  }

  @Test
  void missingAreaAtSettlementRetainsAnUnreadyRewardInsteadOfReportingItAsSettled()
      throws Exception {
    var s = new Session();
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    var definition = GameplayDefinitions.areas.remove(AREA);
    s.explorer.getUniverseManager().onBattleFinish(b);
    var result =
        s.explorer
            .getGameplay()
            .getRogueResults()
            .get(s.explorer.getGameplay().getRogue().getRunId());
    assertFalse(result.isFirstRewardReady());
    assertFalse(result.isRewardSettled());
    GameplayDefinitions.areas.put(AREA, definition);
    s.query();
    assertEquals(0, s.attempts);
    assertFalse(result.isRewardSettled());
  }

  @Test
  void clearingAnAreaWithoutAFirstRewardStillRecordsItsFirstClearWithoutTouchingInventory() {
    GameplayDefinitions.areas.put(AREA, new GameplayDefinitions.Area(AREA, 19996, 1, 0, 0));
    var s = new Session();
    var b = battle(s, BattleEndStatus.BATTLE_END_WIN);
    s.explorer.getUniverseManager().onBattleFinish(b);
    assertTrue(s.explorer.getRewardLedger().granted("rogue-first:" + AREA));
    assertEquals(1, s.explorer.getGameplay().getRogueWins().get(AREA));
    assertEquals(0, s.attempts);
    assertTrue(
        ((List<?>)
                Gameplay46.value(
                    (DynamicMessage) Gameplay46.value(finish(s), "LHPNJLBPACA"), "item_list"))
            .isEmpty());
  }
}
