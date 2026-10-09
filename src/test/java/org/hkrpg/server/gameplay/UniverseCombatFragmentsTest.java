package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class UniverseCombatFragmentsTest {
  static final int ROOM = 19996001, NPC = 1999791, STAGE = 19997901;
  final Gson gson = new Gson();
  NpcMonsterExcel priorNpc;
  StageExcel priorStage;
  Explorer p;

  static class Explorer extends UniverseLifecycleTest.Explorer {
    Scene cached;
    int saves;
    final emu.lunarcore.game.avatar.GameAvatar battleAvatar =
        new emu.lunarcore.game.avatar.GameAvatar() {
          public int getAvatarId() {
            return 199961;
          }

          public emu.lunarcore.proto.BattleAvatarOuterClass.BattleAvatar toBattleProto(
              emu.lunarcore.game.player.lineup.PlayerLineup lineup, int index) {
            return emu.lunarcore.proto.BattleAvatarOuterClass.BattleAvatar.newInstance()
                .setId(199961)
                .setHp(getCurrentHp(lineup));
          }

          public void save() {}
        };

    public emu.lunarcore.game.avatar.GameAvatar getAvatarById(int id) {
      return id == 199961 ? battleAvatar : null;
    }

    public Scene getScene() {
      if (cached == null || cached.getPlaneId() != getPlaneId()) cached = super.getScene();
      return cached;
    }

    public void save() {
      saves++;
    }

    public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {}

    public boolean removeFoodBuffs(int type) {
      return false;
    }
  }

  @BeforeEach
  void setup() {
    GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", "50");
    GameplayDefinitions.rooms.put(
        ROOM, new GameplayDefinitions.Room(ROOM, 1, 12, 8, Map.of(8, 11, 9, 11)));
    GameplayDefinitions.maps.put(
        1999601, List.of(new GameplayDefinitions.Site(1999601, 1, true, List.of(2), 1)));
    GameplayDefinitions.rogueMonsters.put(
        111, new GameplayDefinitions.RogueMonster(111, NPC, STAGE));
    priorNpc = GameData.getNpcMonsterExcelMap().get(NPC);
    GameData.getNpcMonsterExcelMap()
        .add(gson.fromJson("{\"ID\":" + NPC + "}", NpcMonsterExcel.class));
    priorStage = GameData.getStageExcelMap().get(STAGE);
    var stage = gson.fromJson("{\"StageID\":" + STAGE + ",\"MonsterList\":[]}", StageExcel.class);
    stage.onLoad();
    GameData.getStageExcelMap().add(stage);
    p = new Explorer();
    p.entry = 12;
    var run = new GameplayState.RogueRun(19996, 1999601, Map.of(1, ROOM));
    run.setupTeam(p, List.of(199961));
    run.enter(1, ROOM);
    p.getGameplay().setRogue(run);
  }

  @AfterEach
  void cleanup() {
    GameplayDefinitions.rogueConstants.remove("Start_Rogue_Coin_Base");
    GameplayDefinitions.rooms.remove(ROOM);
    GameplayDefinitions.maps.remove(1999601);
    GameplayDefinitions.rogueMonsters.remove(111);
    if (priorNpc == null) GameData.getNpcMonsterExcelMap().keySet().remove(NPC);
    else GameData.getNpcMonsterExcelMap().add(priorNpc);
    if (priorStage == null) GameData.getStageExcelMap().keySet().remove(STAGE);
    else GameData.getStageExcelMap().add(priorStage);
  }

  EntityMonster monster(int groupId, int instance) {
    var group = new GroupInfo();
    group.setId(groupId);
    var info = gson.fromJson("{\"ID\":" + instance + ",\"Pos\":{},\"Rot\":{}}", MonsterInfo.class);
    var monster =
        new EntityMonster(p.getScene(), GameData.getNpcMonsterExcelMap().get(NPC), group, info);
    monster.setEntityId(instance);
    monster.setEventId(STAGE);
    monster.setCustomStage(STAGE);
    p.getScene().getEntities().put(instance, monster);
    return monster;
  }

  Battle battle(EntityMonster... monsters) {
    return new Battle(p, p.getCurrentLineup(), List.of(monsters));
  }

  void finish(Battle battle, BattleEndStatus result) {
    battle.setResult(result);
    if (result == BattleEndStatus.BATTLE_END_WIN) {
      for (var monster : battle.getNpcMonsters()) {
        p.getScene().getEntities().remove(monster.getEntityId());
        monster.setEntityId(0);
      }
    }
    p.getUniverseManager().onBattleFinish(battle);
  }

  @Test
  void aRealWonEncounterPaysOnceBeforeTheOtherContentGroupIsCleared() {
    var enemy = monster(8, 1);
    monster(9, 2);
    var battle = battle(enemy);
    finish(battle, BattleEndStatus.BATTLE_END_WIN);
    int balance = p.getGameplay().getRogue().getCoins();
    assertTrue(
        balance >= 70 && balance <= 90, "Each won encounter must receive its frozen fragments");
    assertFalse(p.getGameplay().getRogue().getCleared().contains(1));
    assertTrue(p.saves > 0);
    p.getUniverseManager().onBattleFinish(battle);
    assertEquals(balance, p.getGameplay().getRogue().getCoins());
  }

  @Test
  void linkedMonstersUseTheActualUniqueEncounterCount() {
    var battle = battle(monster(8, 1), monster(9, 2));
    finish(battle, BattleEndStatus.BATTLE_END_WIN);
    int balance = p.getGameplay().getRogue().getCoins();
    assertTrue(balance >= 90 && balance <= 130);
    assertTrue(p.getGameplay().getRogue().getCleared().contains(1));
    p.getUniverseManager().onBattleFinish(battle);
    assertEquals(balance, p.getGameplay().getRogue().getCoins());
  }

  @Test
  void losingQuittingOrBossBattlesNeverInventCombatFragments() {
    finish(battle(monster(8, 1)), BattleEndStatus.BATTLE_END_QUIT);
    assertEquals(50, p.getGameplay().getRogue().getCoins());
    GameplayDefinitions.rooms.put(
        ROOM, new GameplayDefinitions.Room(ROOM, 7, 12, 8, Map.of(8, 11, 9, 11)));
    finish(battle(monster(8, 2)), BattleEndStatus.BATTLE_END_WIN);
    assertEquals(50, p.getGameplay().getRogue().getCoins());
  }

  @Test
  void changingTheMonsterListAfterPreparationCannotIncreaseTheGrant() {
    var first = monster(8, 1);
    var battle = battle(first);
    battle.getNpcMonsters().add(monster(9, 2));
    finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(50, p.getGameplay().getRogue().getCoins());
    assertFalse(p.getGameplay().getRogue().getCleared().contains(1));
  }

  @Test
  void duplicateNoncurrentOrUnmappedCombatantsCannotCreateAnyGrant() {
    var first = monster(8, 1);
    finish(battle(first, first), BattleEndStatus.BATTLE_END_WIN);
    assertEquals(50, p.getGameplay().getRogue().getCoins());
    first = monster(8, 2);
    p.getScene().getEntities().remove(2);
    finish(battle(first), BattleEndStatus.BATTLE_END_WIN);
    assertEquals(50, p.getGameplay().getRogue().getCoins());
    GameplayDefinitions.rogueMonsters.remove(111);
    finish(battle(monster(9, 3)), BattleEndStatus.BATTLE_END_WIN);
    assertEquals(50, p.getGameplay().getRogue().getCoins());
    assertTrue(p.getGameplay().getRogue().getCombatFragmentGrants().isEmpty());
  }

  @Test
  void changingBoundIdentityOrLeavingTheRunPreventsPayment() {
    var enemy = monster(8, 1);
    var battle = battle(enemy);
    enemy.setEventId(STAGE + 1);
    finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(50, p.getGameplay().getRogue().getCoins());
    battle = battle(monster(8, 2));
    p.getGameplay().getRogue().setInside(false);
    finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(50, p.getGameplay().getRogue().getCoins());
    p.getGameplay().getRogue().setInside(true);
    finish(battle(monster(8, 3)), BattleEndStatus.BATTLE_END_LOSE);
    assertEquals(50, p.getGameplay().getRogue().getCoins());
    assertEquals(GameplayState.RogueRun.Phase.FAILED, p.getGameplay().getRogue().getPhase());
  }

  @Test
  void validatedBattleServiceOutcomeAndConcurrentRetriesCreditOnlyOneGrant() throws Exception {
    var battle = battle(monster(8, 1));
    monster(9, 2);
    p.setBattle(battle);
    var service =
        new emu.lunarcore.game.battle.BattleService(null) {
          protected void grantDrops(Battle ignored) {}
        };
    var stats = emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance();
    assertNull(
        service.finishBattle(p, battle.getId() + 1, STAGE, BattleEndStatus.BATTLE_END_WIN, stats));
    assertEquals(50, p.getGameplay().getRogue().getCoins());
    assertNotNull(
        service.finishBattle(p, battle.getId(), STAGE, BattleEndStatus.BATTLE_END_WIN, stats));
    int paid = p.getGameplay().getRogue().getCoins();
    assertTrue(paid >= 70 && paid <= 90);
    try (var executor = Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 6; i++)
        tasks.add(
            () -> {
              p.getUniverseManager().onBattleFinish(battle);
              return null;
            });
      for (var result : executor.invokeAll(tasks)) result.get();
    }
    assertEquals(paid, p.getGameplay().getRogue().getCoins());
    assertEquals(1, p.getGameplay().getRogue().getCombatFragmentGrants().size());
    assertNull(
        service.finishBattle(p, battle.getId(), STAGE, BattleEndStatus.BATTLE_END_WIN, stats));
  }

  @Test
  void concurrentFirstWinCallbacksPayOnceEvenBeforeTheRoomIsCleared() throws Exception {
    var enemy = monster(8, 1);
    var battle = battle(enemy);
    monster(9, 2);
    battle.setResult(BattleEndStatus.BATTLE_END_WIN);
    p.getScene().getEntities().remove(1);
    enemy.setEntityId(0);
    try (var executor = Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 6; i++)
        tasks.add(
            () -> {
              p.getUniverseManager().onBattleFinish(battle);
              return null;
            });
      for (var result : executor.invokeAll(tasks)) result.get();
    }
    assertEquals(1, p.getGameplay().getRogue().getCombatFragmentGrants().size());
    int balance = p.getGameplay().getRogue().getCoins();
    assertTrue(balance >= 70 && balance <= 90);
    assertFalse(p.getGameplay().getRogue().getCleared().contains(1));
  }

  @Test
  void morphiaRestoreKeepsConfirmedOverflowGrantsWithoutRepricingOrDoublePayment()
      throws Exception {
    var run = p.getGameplay().getRogue();
    run.addCoins(Integer.MAX_VALUE - 60);
    var battle = battle(monster(8, 1));
    monster(9, 2);
    finish(battle, BattleEndStatus.BATTLE_END_WIN);
    var grant = run.getCombatFragmentGrants().values().iterator().next();
    assertFalse(grant.isSettled());
    assertEquals(Integer.MAX_VALUE - 10, run.getCoins());
    int amount = grant.getFragments();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "universe-combat-fragments");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var doc = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(doc, 123456781);
      var state = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(doc, p.getGameplay());
      store.save(doc);
      state.set(p, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      GameplayDefinitions.rogueConstants.clear();
      var restored = p.getGameplay().getRogue();
      assertFalse(restored.getCombatFragmentGrants().values().iterator().next().isSettled());
      assertTrue(restored.spend(100));
      p.getUniverseManager().settleResults();
      assertEquals(Integer.MAX_VALUE - 110 + amount, restored.getCoins());
      assertTrue(restored.getCombatFragmentGrants().values().iterator().next().isSettled());
      state.set(doc, p.getGameplay());
      store.save(doc);
      state.set(p, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      p.getUniverseManager().settleResults();
      p.getUniverseManager().onBattleFinish(battle);
      assertEquals(Integer.MAX_VALUE - 110 + amount, p.getGameplay().getRogue().getCoins());
      client
          .getDatabase("universe-combat-fragments")
          .getCollection("players")
          .updateMany(
              new org.bson.Document(),
              new org.bson.Document(
                  "$unset", new org.bson.Document("gameplay.rogue.combatFragmentGrants", "")));
      state.set(p, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      p.getUniverseManager().settleResults();
      assertTrue(p.getGameplay().getRogue().getCombatFragmentGrants().isEmpty());
      assertEquals(Integer.MAX_VALUE - 110 + amount, p.getGameplay().getRogue().getCoins());
    } finally {
      mongo.shutdown();
    }
  }

  @Test
  void actualSceneAttackAndSettlementUseTheIsolatedRogueRosterAndVitals() {
    var normal = p.getLineupManager().getLineupByIndex(0, 0);
    p.battleAvatar.setCurrentHp(normal, 5700);
    var rogue = p.getCurrentLineup();
    p.battleAvatar.setCurrentHp(rogue, 2300);
    var enemy = monster(8, 1);
    monster(9, 2);
    int casterId = p.battleAvatar.getEntityId();
    assertTrue(p.getScene().getAvatarEntityIds().contains(casterId));
    assertSame(p.battleAvatar, p.getScene().getAvatarByEntityId(casterId));
    var service =
        new emu.lunarcore.game.battle.BattleService(null) {
          protected void grantDrops(Battle ignored) {}
        };
    service.attack(
        p,
        casterId,
        101,
        null,
        new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet(new int[] {enemy.getEntityId()}),
        new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet());
    var battle = p.getBattle();
    assertNotNull(battle);
    assertSame(
        rogue, battle.getLineup(), "Scene attack must use the actual active universe lineup");
    var stats = emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance();
    stats.addBattleAvatarList(
        emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo.newInstance()
            .setId(199961)
            .setAvatarStatus(
                emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty.newInstance()
                    .setMaxHp(100)
                    .setMaxSp(100)
                    .setLeftHp(77)
                    .setLeftSp(9)));
    assertNotNull(
        service.finishBattle(p, battle.getId(), STAGE, BattleEndStatus.BATTLE_END_WIN, stats));
    assertEquals(7700, p.battleAvatar.getCurrentHp(rogue));
    assertEquals(900, p.battleAvatar.getCurrentSp(rogue));
    assertEquals(5700, p.battleAvatar.getCurrentHp(normal));
    assertTrue(p.getGameplay().getRogue().getCoins() >= 70);
  }
}
