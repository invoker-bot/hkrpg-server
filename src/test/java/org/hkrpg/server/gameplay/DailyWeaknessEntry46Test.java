package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.BasePacket;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class DailyWeaknessEntry46Test {
  final Gson gson = emu.lunarcore.data.ResourceLoader.getGson();
  final List<Runnable> restorations = new ArrayList<>();
  List<GameplayDefinitions.DailyPool> oldPool;
  int nonMatchingStage;

  static class Actor extends Player {
    final Map<Integer, emu.lunarcore.game.avatar.GameAvatar> owned = new HashMap<>();
    final emu.lunarcore.game.player.lineup.LineupManager teams =
        new emu.lunarcore.game.player.lineup.LineupManager(this);
    emu.lunarcore.game.scene.Scene scene;
    int saves;

    @Override
    public void save() {
      saves++;
    }

    @Override
    public void sendPacket(BasePacket packet) {}

    @Override
    public emu.lunarcore.game.player.lineup.LineupManager getLineupManager() {
      return teams;
    }

    @Override
    public emu.lunarcore.game.avatar.GameAvatar getAvatarById(int id) {
      return owned.get(id);
    }

    @Override
    public emu.lunarcore.game.scene.Scene getScene() {
      return scene;
    }

    @Override
    public int getPlaneId() {
      return 199983;
    }

    @Override
    public int getFloorId() {
      return 199983001;
    }

    @Override
    public boolean removeFoodBuffs(int type) {
      return false;
    }
  }

  final Actor player = new Actor();
  final emu.lunarcore.game.battle.BattleService service =
      new emu.lunarcore.game.battle.BattleService(null) {
        @Override
        protected void grantDrops(emu.lunarcore.game.battle.Battle battle) {}
      };
  final List<Runnable> buffRestorations = new ArrayList<>();

  void avatar(int id) {
    var excel = GameData.getAvatarExcelMap().get(id);
    var attack = new emu.lunarcore.game.battle.skills.MazeSkill(excel, 1);
    excel.setMazeAttack(attack);
    var actor =
        new emu.lunarcore.game.avatar.GameAvatar() {
          @Override
          public int getAvatarId() {
            return id;
          }

          @Override
          public AvatarExcel getExcel() {
            return excel;
          }

          @Override
          public void save() {}

          @Override
          public emu.lunarcore.proto.BattleAvatarOuterClass.BattleAvatar toBattleProto(
              emu.lunarcore.game.player.lineup.PlayerLineup lineup, int index) {
            return emu.lunarcore.proto.BattleAvatarOuterClass.BattleAvatar.newInstance()
                .setId(id)
                .setHp(10000);
          }
        };
    actor.setEntityId(100001);
    player.owned.put(id, actor);
    player.getCurrentLineup().getAvatars().clear();
    player.getCurrentLineup().getAvatars().add(id);
    player.scene.getAvatars().clear();
    player.scene.getAvatars().put(id, actor);
    player.scene.getAvatarEntityIds().clear();
    player.scene.getAvatarEntityIds().add(actor.getEntityId());
  }

  emu.lunarcore.game.scene.entity.EntityMonster monster(int entity) {
    var group = new emu.lunarcore.data.config.GroupInfo();
    group.setId(7);
    var info =
        gson.fromJson(
            "{\"ID\":" + entity + ",\"Pos\":{},\"Rot\":{}}",
            emu.lunarcore.data.config.MonsterInfo.class);
    var monster =
        new emu.lunarcore.game.scene.entity.EntityMonster(
            player.scene, GameData.getNpcMonsterExcelMap().get(1002020), group, info);
    monster.setEntityId(entity);
    monster.setCustomStage(201342210);
    player.scene.getEntities().put(entity, monster);
    return monster;
  }

  emu.lunarcore.game.battle.Battle enterWeaknessBattle(int entity) {
    var actor = player.getAvatarById(player.getCurrentLineup().getAvatars().getFirst());
    monster(entity);
    service.attack(
        player,
        actor.getEntityId(),
        7,
        actor.getExcel().getMazeAttack(),
        new it.unimi.dsi.fastutil.ints.IntOpenHashSet(new int[] {entity}),
        new it.unimi.dsi.fastutil.ints.IntOpenHashSet());
    return Objects.requireNonNull(player.getBattle(), "Actual scene attack did not enter battle");
  }

  <T extends GameResource> void install(ExcelMap<T> map, T value) {
    var old = map.get(value.getId());
    restorations.add(
        () -> {
          map.keySet().remove(value.getId());
          if (old != null) map.add(old);
        });
    map.add(value);
  }

  @BeforeEach
  void setup() throws Exception {
    oldPool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    try (var input = getClass().getResourceAsStream("/fixtures/daily-weakness-entry-4.6.json")) {
      assertNotNull(input);
      var fixture =
          JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      assertEquals(
          "724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
      nonMatchingStage = fixture.get("nonMatchingPrimaryStage").getAsInt();
      for (var value : fixture.getAsJsonArray("records")) {
        var row = value.getAsJsonObject().getAsJsonObject("row");
        switch (value.getAsJsonObject().get("kind").getAsString()) {
          case "pool" ->
              GameplayDefinitions.dailyPool.add(
                  gson.fromJson(row, GameplayDefinitions.DailyPool.class));
          case "quest" ->
              install(GameData.getQuestExcelMap(), gson.fromJson(row, QuestExcel.class));
          case "rule" ->
              install(GameData.getFinishWayExcelMap(), gson.fromJson(row, FinishWayExcel.class));
          case "reward" -> {
            var reward = gson.fromJson(row, RewardExcel.class);
            reward.onLoad();
            install(GameData.getRewardExcelMap(), reward);
          }
          case "item" -> install(GameData.getItemExcelMap(), gson.fromJson(row, ItemExcel.class));
          case "avatar" ->
              install(GameData.getAvatarExcelMap(), gson.fromJson(row, AvatarExcel.class));
          case "npc" ->
              install(GameData.getNpcMonsterExcelMap(), gson.fromJson(row, NpcMonsterExcel.class));
          case "monster" ->
              install(GameData.getMonsterExcelMap(), gson.fromJson(row, MonsterExcel.class));
          case "stage" -> {
            var stage = gson.fromJson(row, StageExcel.class);
            stage.onLoad();
            install(GameData.getStageExcelMap(), stage);
          }
          case "buff" -> {
            var buff = gson.fromJson(row, MazeBuffExcel.class);
            var prior = GameData.getMazeBuffExcelMap().get(buff.getID(), buff.getLv());
            buffRestorations.add(
                () -> {
                  GameData.getMazeBuffExcelMap()
                      .values()
                      .removeIf(v -> v.getID() == buff.getID() && v.getLv() == buff.getLv());
                  if (prior != null) GameData.getMazeBuffExcelMap().add(prior);
                });
            GameData.getMazeBuffExcelMap().add(buff);
          }
          default -> fail("Unknown fixture record");
        }
      }
    }
    var plane = gson.fromJson("{\"PlaneID\":199983,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    player.scene =
        new emu.lunarcore.game.scene.Scene(player, plane, player.getFloorId()) {
          @Override
          public void onBattleStart(emu.lunarcore.game.battle.Battle battle) {}

          @Override
          public boolean defeatMonster(emu.lunarcore.game.scene.entity.EntityMonster monster) {
            getEntities().remove(monster.getEntityId());
            monster.setEntityId(0);
            return true;
          }
        };
    avatar(1001);
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
  }

  @AfterEach
  void cleanup() {
    buffRestorations.forEach(Runnable::run);
    Collections.reverse(restorations);
    restorations.forEach(Runnable::run);
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
  }

  @ParameterizedTest
  @ValueSource(ints = {2100123, 2100142})
  void bothAuthoredWeaknessEntryObjectivesStartDoingWithoutInventedVictory(int id) {
    var state = player.getGameplay().getDailyQuests().get(id);
    assertNotNull(state, "Original ExcellentEnterBattleAndWin objective was excluded");
    assertEquals(QuestState.Phase.DOING, state.getPhase());
    assertEquals(0, state.getProgress());
    assertEquals(0, player.getGameplay().getDailyPoints());
  }

  @Test
  void actualPrimaryIceAttackAndAcceptedVictoryCountBothOriginalObjectivesOnce() {
    for (int id : List.of(2100123, 2100142))
      player.getGameplay().getDailyQuests().putIfAbsent(id, new QuestState(id));
    var battle = enterWeaknessBattle(300001);
    assertTrue(battle.hasBuff(1000113));
    assertNotNull(
        service.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance()));
    assertEquals(1, player.getGameplay().getDailyQuests().get(2100123).getProgress());
    assertEquals(1, player.getGameplay().getDailyQuests().get(2100142).getProgress());
  }

  int progress(int id) {
    return player.getGameplay().getDailyQuests().get(id).getProgress();
  }

  String saved() {
    return gson.toJson(player.getGameplay());
  }

  emu.lunarcore.game.battle.Battle finish(
      emu.lunarcore.game.battle.Battle battle,
      emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus result) {
    return service.finishBattle(
        player,
        battle.getId(),
        battle.getStage().getId(),
        result,
        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance());
  }

  void win(int entity) {
    var battle = enterWeaknessBattle(entity);
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
  }

  @Test
  void parsedOriginalBasicAttackUsesRuntimeIndexOneAndCountsAcceptedVictory() throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/daily-weakness-entry-4.6.json")) {
      var fixture =
          JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      var file = fixture.getAsJsonObject("abilityFile");
      var raw = HexFormat.of().parseHex(file.get("rawHex").getAsString());
      assertEquals(
          file.get("resourceSHA256").getAsString(),
          HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
      var excel = player.getAvatarById(1001).getExcel();
      var abilities =
          gson.fromJson(
              new String(raw, StandardCharsets.UTF_8),
              emu.lunarcore.data.config.SkillAbilityInfo.class);
      assertTrue(abilities.parse(excel));
      assertEquals(1, excel.getMazeAttack().getIndex());
      assertEquals("LocalPlayer_Mar_7th_NormalAtk01", excel.getMazeAttack().getAbilityName());
      win(300201);
      assertEquals(1, progress(2100123));
      assertEquals(1, progress(2100142));
    }
  }

  @Test
  void threeActualWonEntriesCompleteThreeTimesAndOneTimeGoalsAndCreditOnlyClaims() {
    for (int i = 0; i < 3; i++) {
      win(300001 + i);
      assertEquals(i + 1, progress(2100123));
      assertEquals(1, progress(2100142));
      assertEquals(0, player.getGameplay().getDailyPoints());
    }
    assertEquals(
        QuestState.Phase.FINISHED, player.getGameplay().getDailyQuests().get(2100123).getPhase());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        player.getDailyManager().claimQuest(2100123).retcode());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        player.getDailyManager().claimQuest(2100142).retcode());
    assertEquals(200, player.getGameplay().getDailyPoints());
    assertNotEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        player.getDailyManager().claimQuest(2100123).retcode());
    assertNotEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        player.getDailyManager().claimQuest(2100142).retcode());
    win(300005);
    assertEquals(3, progress(2100123));
    assertEquals(200, player.getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void clientSelectorOrTechniqueNumberCannotStandInForTheParsedBasicAttack(int index) {
    var excel = player.getAvatarById(1001).getExcel();
    excel.setMazeAttack(new emu.lunarcore.game.battle.skills.MazeSkill(excel, index));
    win(300202);
    assertEquals(0, progress(2100123));
    assertEquals(0, progress(2100142));
  }

  @Test
  void earlyClaimDoesNotInventWeaknessVictoriesOrActivityPoints() {
    assertNotEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        player.getDailyManager().claimQuest(2100123).retcode());
    assertNotEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        player.getDailyManager().claimQuest(2100142).retcode());
    assertEquals(0, progress(2100123));
    assertEquals(0, player.getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @EnumSource(
      value = emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.class,
      names = {"BATTLE_END_LOSE", "BATTLE_END_QUIT"})
  void lossAndQuitConsumeNoDailyProgressAndCannotBeReplayedAsAVictory(
      emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus outcome) {
    var battle = enterWeaknessBattle(300011);
    assertNotNull(finish(battle, outcome));
    assertEquals(0, progress(2100123));
    assertEquals(0, progress(2100142));
    assertNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    player.setBattle(battle);
    battle.setResult(emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN);
    player.getDailyManager().onWeaknessEntryOutcome(battle);
    assertEquals(0, progress(2100123));
  }

  @Test
  void wrongElementAtEntryCannotBorrowALaterMatchingAvatar() {
    avatar(1003);
    var battle = enterWeaknessBattle(300021);
    assertTrue(battle.hasBuff(1000112));
    avatar(1001);
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, progress(2100123));
    assertEquals(0, progress(2100142));
  }

  @Test
  void aLaterWrongElementDoesNotRewriteTheActualIceEntry() {
    var battle = enterWeaknessBattle(300022);
    avatar(1003);
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, progress(2100123));
  }

  @Test
  void anAssistingIceWeakMonsterCannotTurnTheNonmatchingPrimaryHitIntoExcellentEntry() {
    var actor = player.getAvatarById(1001);
    var primary = monster(300031);
    primary.setCustomStage(nonMatchingStage);
    monster(300032);
    service.attack(
        player,
        actor.getEntityId(),
        7,
        actor.getExcel().getMazeAttack(),
        new it.unimi.dsi.fastutil.ints.IntOpenHashSet(new int[] {300031}),
        new it.unimi.dsi.fastutil.ints.IntOpenHashSet(new int[] {300032}));
    var battle = Objects.requireNonNull(player.getBattle());
    assertEquals(2, battle.getNpcMonsters().size());
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, progress(2100123));
  }

  @Test
  void multipleMatchingPrimaryHitsStillCountOneWonEntry() {
    var actor = player.getAvatarById(1001);
    monster(300033);
    monster(300034);
    service.attack(
        player,
        actor.getEntityId(),
        7,
        actor.getExcel().getMazeAttack(),
        new it.unimi.dsi.fastutil.ints.IntOpenHashSet(new int[] {300033, 300034}),
        new it.unimi.dsi.fastutil.ints.IntOpenHashSet());
    var battle = Objects.requireNonNull(player.getBattle());
    assertEquals(2, battle.getNpcMonsters().size());
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, progress(2100123));
  }

  @Test
  void aMonsterAmbushAndAStageShortcutNeverCreateAnElementalPlayerEntry() {
    var enemy = monster(300041);
    service.attack(
        player,
        enemy.getEntityId(),
        7,
        null,
        new it.unimi.dsi.fastutil.ints.IntOpenHashSet(new int[] {100001}),
        new it.unimi.dsi.fastutil.ints.IntOpenHashSet());
    var battle = Objects.requireNonNull(player.getBattle());
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, progress(2100123));
    var shortcut =
        new emu.lunarcore.game.battle.Battle(
            player, player.getCurrentLineup(), GameData.getStageExcelMap().get(201342210));
    player.setBattle(shortcut);
    assertNotNull(
        finish(
            shortcut,
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, progress(2100123));
  }

  @Test
  void callbacksBeforeAcceptedSettlementAndAfterDuplicateVictoryDoNotCountAgain() {
    var battle = enterWeaknessBattle(300051);
    battle.setResult(emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN);
    player.getDailyManager().onWeaknessEntryOutcome(battle);
    assertEquals(0, progress(2100123));
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    String before = saved();
    assertNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    player.getDailyManager().onWeaknessEntryOutcome(battle);
    assertEquals(before, saved());
  }

  @Test
  void copyingAnEntryProofToAnotherBattleCannotCreditItsVictory() {
    var entry = enterWeaknessBattle(300052);
    var shortcut =
        new emu.lunarcore.game.battle.Battle(
            player, player.getCurrentLineup(), GameData.getStageExcelMap().get(201342210));
    shortcut.setDailyWeaknessEntry(entry.getDailyWeaknessEntry());
    player.setBattle(shortcut);
    assertNotNull(
        finish(
            shortcut,
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, progress(2100123));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void crossingDailyBoundaryCannotCreditOldOrNewObjectiveInstances(boolean queryFirst) {
    var before = player.getGameplay().getDailyQuests().get(2100123);
    var battle = enterWeaknessBattle(300061);
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC));
    if (queryFirst) player.getDailyManager().ensureDay();
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, before.getProgress());
    assertEquals(0, progress(2100123));
    assertEquals(0, progress(2100142));
    assertNotSame(before, player.getGameplay().getDailyQuests().get(2100123));
  }

  @Test
  void replacementObjectivesAcceptedAfterEntryCannotBorrowTheEarlierProof() {
    var battle = enterWeaknessBattle(300062);
    for (int id : List.of(2100123, 2100142))
      player.getGameplay().getDailyQuests().put(id, new QuestState(id));
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, progress(2100123));
    assertEquals(0, progress(2100142));
  }

  @Test
  void anInvalidResultRetainsTheRealBattleAndUnconsumedEntryForOneValidRetry() {
    var battle = enterWeaknessBattle(300071);
    assertNull(
        service.finishBattle(
            player,
            battle.getId() + 1,
            battle.getStage().getId(),
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance()));
    assertSame(battle, player.getBattle());
    assertEquals(0, progress(2100123));
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, progress(2100123));
  }

  @Test
  void concurrentAcceptedVictoryRequestsCountOnlyOneEntry() throws Exception {
    var battle = enterWeaknessBattle(300072);
    var start = new java.util.concurrent.CountDownLatch(1);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(12);
    try {
      var results = new ArrayList<java.util.concurrent.Future<emu.lunarcore.game.battle.Battle>>();
      for (int i = 0; i < 24; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return finish(
                      battle,
                      emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN);
                }));
      start.countDown();
      int accepted = 0;
      for (var result : results)
        if (result.get(30, java.util.concurrent.TimeUnit.SECONDS) != null) accepted++;
      assertEquals(1, accepted);
      assertEquals(1, progress(2100123));
      assertEquals(1, progress(2100142));
    } finally {
      pool.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "threshold",
        "param1",
        "param2",
        "param3",
        "text",
        "list",
        "items",
        "comparison",
        "backtrack"
      })
  void unsupportedOriginalGoalShapesCannotBorrowValidEntryProgress(String kind) {
    var row = gson.toJsonTree(GameData.getFinishWayExcelMap().get(2100123)).getAsJsonObject();
    switch (kind) {
      case "threshold" -> row.addProperty("Progress", 2);
      case "param1" -> row.addProperty("ParamInt1", 1);
      case "param2" -> row.addProperty("ParamInt2", 1);
      case "param3" -> row.addProperty("ParamInt3", 1);
      case "text" -> row.addProperty("ParamStr1", "other");
      case "list" -> row.add("ParamIntList", JsonParser.parseString("[1]"));
      case "items" ->
          row.add("ParamItemList", JsonParser.parseString("[{\"ItemID\":1,\"ItemNum\":1}]"));
      case "comparison" -> row.addProperty("ParamType", "Equal");
      case "backtrack" -> row.addProperty("IsBackTrack", true);
    }
    GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    win(300081);
    assertEquals(0, progress(2100123));
    assertEquals(1, progress(2100142));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing-weakness",
        "null-weakness",
        "duplicate-weakness",
        "missing-buff",
        "wrong-binding"
      })
  void malformedOrMissingEntryDefinitionsCannotBeRestoredLaterToInventEligibility(String kind) {
    var source = GameData.getMazeBuffExcelMap().get(1000113, 1);
    var monsters = new HashMap<Integer, MonsterExcel>();
    for (int id : List.of(1002030, 1002040, 1002050))
      monsters.put(id, GameData.getMonsterExcelMap().get(id));
    if (kind.equals("missing-buff"))
      GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 1000113 && v.getLv() == 1);
    else if (kind.equals("wrong-binding")) {
      var row = gson.toJsonTree(source).getAsJsonObject();
      row.addProperty("InBattleBindingKey", "wrong");
      GameData.getMazeBuffExcelMap().add(gson.fromJson(row, MazeBuffExcel.class));
    } else
      for (var entry : monsters.entrySet()) {
        var row = gson.toJsonTree(entry.getValue()).getAsJsonObject();
        if (kind.equals("missing-weakness")) row.remove("StanceWeakList");
        if (kind.equals("null-weakness")) row.add("StanceWeakList", JsonNull.INSTANCE);
        if (kind.equals("duplicate-weakness"))
          row.add("StanceWeakList", JsonParser.parseString("[\"Ice\",\"Ice\"]"));
        GameData.getMonsterExcelMap().add(gson.fromJson(row, MonsterExcel.class));
      }
    var battle = enterWeaknessBattle(300091);
    monsters.values().forEach(GameData.getMonsterExcelMap()::add);
    GameData.getMazeBuffExcelMap().add(source);
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, progress(2100123));
  }

  @Test
  void entryProofPreservesTheActualAttackDespiteLaterWeaknessAndBindingChanges() {
    var battle = enterWeaknessBattle(300092);
    for (int id : List.of(1002030, 1002040, 1002050)) {
      var row = gson.toJsonTree(GameData.getMonsterExcelMap().get(id)).getAsJsonObject();
      row.add("StanceWeakList", new JsonArray());
      GameData.getMonsterExcelMap().add(gson.fromJson(row, MonsterExcel.class));
    }
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 1000113 && v.getLv() == 1);
    assertNotNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, progress(2100123));
  }

  @ParameterizedTest
  @ValueSource(strings = {"one-win", "three-wins", "both-claimed"})
  void actualPlayerMorphiaRestoresAcceptedVictoriesAndClaimedPointsTwiceWithoutReadChanges(
      String phase) throws Exception {
    int victories = phase.equals("one-win") ? 1 : 3;
    for (int i = 0; i < victories; i++) win(300101 + i);
    if (phase.equals("both-claimed")) {
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC,
          player.getDailyManager().claimQuest(2100123).retcode());
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC,
          player.getDailyManager().claimQuest(2100142).retcode());
    }
    var before = JsonParser.parseString(saved());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "daily-weakness-entry");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.set(document, 199995);
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(document, player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored = Objects.requireNonNull(store.find(Player.class).first());
        assertEquals(before, JsonParser.parseString(gson.toJson(restored.getGameplay())));
        field.set(player, restored.getGameplay());
        String loaded = saved();
        int saves = player.saves;
        player.getDailyManager().ensureDay();
        assertEquals(loaded, saved());
        assertEquals(saves, player.saves);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
