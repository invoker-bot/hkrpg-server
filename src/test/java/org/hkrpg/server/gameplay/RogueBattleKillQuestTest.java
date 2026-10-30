package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual original wave and placement; roster and isolated floor are explicit domain fixtures. */
class RogueBattleKillQuestTest {
  static final int STAGE = 80010011;
  static final String FLOOR = "P80001_F80001001";

  static class Explorer extends UniverseCombatFragmentsTest.Explorer {
    Scene scene;
    boolean failSave;

    @Override
    public void save() {
      if (failSave) throw new IllegalStateException("simulated save failure");
      super.save();
    }

    public Scene getScene() {
      return scene;
    }

    public int getEntryId() {
      return 8000101;
    }

    public int getPlaneId() {
      return 80001;
    }

    public int getFloorId() {
      return 80001001;
    }
  }

  Explorer player = new Explorer();
  final Gson gson = ResourceLoader.getGson();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldRules = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final Map<Integer, MonsterExcel> oldMonsters = new HashMap<>();
  final Map<Integer, JsonObject> rules = new HashMap<>();
  ItemExcel oldItem;
  MapEntranceExcel oldEntry;
  FloorInfo oldFloor;
  GameplayDefinitions.Room oldRoom;
  GameplayDefinitions.RogueMonster oldRogueMonster;
  NpcMonsterExcel oldNpc;
  StageExcel oldStage;
  MazePlaneExcel plane;
  FloorInfo floor;
  GroupInfo group;
  final BattleService service =
      new BattleService(null) {
        protected void grantDrops(Battle ignored) {}
      };

  @BeforeEach
  void setup() throws Exception {
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldRules.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldItem = GameData.getItemExcelMap().get(1);
    oldEntry = GameData.getMapEntranceExcelMap().get(8000101);
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    oldRoom = GameplayDefinitions.rooms.get(100);
    oldRogueMonster = GameplayDefinitions.rogueMonsters.get(10011);
    oldNpc = GameData.getNpcMonsterExcelMap().get(8011020);
    oldStage = GameData.getStageExcelMap().get(STAGE);
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-battle-kill-quests-4.6.json")) {
      var source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(19, source.size());
      for (var raw : source) {
        var spec = raw.getAsJsonObject();
        var row = spec.getAsJsonObject("row").deepCopy();
        switch (spec.get("kind").getAsString()) {
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" -> {
            var r = gson.fromJson(row, FinishWayExcel.class);
            GameData.getFinishWayExcelMap().add(r);
            rules.put(r.getId(), row);
          }
          case "reward" -> {
            var r = gson.fromJson(row, RewardExcel.class);
            r.onLoad();
            GameData.getRewardExcelMap().add(r);
          }
          case "item" -> {
            hash(row, "ItemName");
            GameData.getItemExcelMap().add(gson.fromJson(row, ItemExcel.class));
          }
          case "room" -> {
            var r = gson.fromJson(row, GameplayDefinitions.Room.class);
            GameplayDefinitions.rooms.put(r.RogueRoomID(), r);
          }
          case "entry" ->
              GameData.getMapEntranceExcelMap().add(gson.fromJson(row, MapEntranceExcel.class));
          case "plane" -> {
            hash(row, "PlaneName");
            plane = gson.fromJson(row, MazePlaneExcel.class);
          }
          case "floor" -> floor = gson.fromJson(row, FloorInfo.class);
          case "group" -> {
            group = gson.fromJson(row, GroupInfo.class);
            group.setId(3);
          }
          case "rogueMonster" -> {
            var r = gson.fromJson(row, GameplayDefinitions.RogueMonster.class);
            GameplayDefinitions.rogueMonsters.put(r.RogueMonsterID(), r);
          }
          case "npc" -> {
            hash(row, "NPCName");
            GameData.getNpcMonsterExcelMap().add(gson.fromJson(row, NpcMonsterExcel.class));
          }
          case "stage" -> {
            hash(row, "StageName");
            var r = gson.fromJson(row, StageExcel.class);
            r.onLoad();
            GameData.getStageExcelMap().add(r);
          }
          case "monster" -> {
            hash(row, "MonsterName");
            var r = gson.fromJson(row, MonsterExcel.class);
            oldMonsters.put(r.getId(), GameData.getMonsterExcelMap().get(r.getId()));
            GameData.getMonsterExcelMap().add(r);
          }
          case "achievement" -> {}
          default -> fail(spec.toString());
        }
      }
    }
    floor.getGroups().put(3, group);
    GameData.getFloorInfos().put(FLOOR, floor);
    assertTrue(player.setNickname("BattleKillGoals"));
    begin();
  }

  void hash(JsonObject row, String key) {
    row.addProperty(key, row.getAsJsonObject(key).get("Hash").getAsLong());
  }

  @AfterEach
  void restore() {
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldRules.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getItemExcelMap().keySet().remove(1);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
    GameData.getMapEntranceExcelMap().keySet().remove(8000101);
    if (oldEntry != null) GameData.getMapEntranceExcelMap().add(oldEntry);
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    GameplayDefinitions.rooms.remove(100);
    if (oldRoom != null) GameplayDefinitions.rooms.put(100, oldRoom);
    GameplayDefinitions.rogueMonsters.remove(10011);
    if (oldRogueMonster != null) GameplayDefinitions.rogueMonsters.put(10011, oldRogueMonster);
    GameData.getNpcMonsterExcelMap().keySet().remove(8011020);
    if (oldNpc != null) GameData.getNpcMonsterExcelMap().add(oldNpc);
    GameData.getStageExcelMap().keySet().remove(STAGE);
    if (oldStage != null) GameData.getStageExcelMap().add(oldStage);
    for (var e : oldMonsters.entrySet()) {
      GameData.getMonsterExcelMap().keySet().remove(e.getKey());
      if (e.getValue() != null) GameData.getMonsterExcelMap().add(e.getValue());
    }
  }

  void begin() {
    var run = new GameplayState.RogueRun(100, 199956, Map.of(1, 100));
    run.setupTeam(player, List.of(199961));
    player.getGameplay().setRogue(run);
    run.enter(1, 100);
    run.setInside(true);
    player.scene = new Scene(player, plane, 80001001);
    player.scene.setEntryId(8000101);
  }

  Battle battle() {
    var monster =
        player.scene.getEntities().values().stream()
            .filter(EntityMonster.class::isInstance)
            .map(EntityMonster.class::cast)
            .filter(m -> m.getInstId() == 200001)
            .findFirst()
            .orElseThrow();
    assertEquals(3, monster.getGroupId());
    assertEquals(200001, monster.getInstId());
    var battle = new Battle(player, player.getCurrentLineup(), List.of(monster));
    player.setBattle(battle);
    assertEquals(STAGE, battle.getStage().getId());
    assertEquals(
        List.of(8011020, 8011010), new ArrayList<>(battle.getWaves().getFirst().getMonsters()));
    return battle;
  }

  List<MissionBattleKills.Monster> reports(int killed) {
    return killed == 1
        ? List.of(new MissionBattleKills.Monster(5001, 8011020, STAGE, 1))
        : List.of(
            new MissionBattleKills.Monster(5001, 8011020, STAGE, 1),
            new MissionBattleKills.Monster(5002, 8011010, STAGE, 2));
  }

  void kill(int total) {
    for (int i = 0; i < total; i += 2) {
      if (i > 0) begin();
      var b = battle();
      assertNotNull(
          service.finishBattle(
              player,
              b.getId(),
              STAGE,
              BattleEndStatus.BATTLE_END_WIN,
              BattleStatistics.newInstance(),
              reports(Math.min(2, total - i))));
    }
  }

  QuestState state(int id) {
    var q = player.getQuestManager().states().stream().filter(r -> r.getQuestId() == id).toList();
    assertEquals(1, q.size());
    return q.getFirst();
  }

  void held(int id) {
    assertTrue(player.getQuestManager().states().stream().noneMatch(q -> q.getQuestId() == id));
    assertNotEquals(Retcode.SUCC, player.getQuestManager().claim(id).retcode());
    assertEquals(0, player.getHcoin());
  }

  @Test
  void oneWonOriginalEncounterCreditsItsTwoReportedBattleDeaths() {
    kill(2);
    assertEquals(2, state(4090301).getProgress());
  }

  @Test
  void fourteenActualDeathsDoNotFinishTheFifteenGoal() {
    kill(14);
    assertEquals(14, state(4090301).getProgress());
    assertEquals(QuestState.Phase.DOING, state(4090301).getPhase());
  }

  @Test
  void fifteenActualDeathsPayTheOriginalFiveJadesOnce() {
    kill(15);
    assertEquals(Retcode.SUCC, player.getQuestManager().claim(4090301).retcode());
    assertEquals(5, player.getHcoin());
    assertNotEquals(Retcode.SUCC, player.getQuestManager().claim(4090301).retcode());
  }

  @Test
  void nineHundredNinetyNineDeathsCannotFinishTheLargerGoal() {
    kill(999);
    assertEquals(999, state(4090303).getProgress());
    assertEquals(QuestState.Phase.DOING, state(4090303).getPhase());
  }

  @Test
  void aThousandActualDeathsPayBothOriginalGoalsForFifteenJades() {
    kill(1000);
    for (int id : List.of(4090301, 4090303))
      assertEquals(Retcode.SUCC, player.getQuestManager().claim(id).retcode());
    assertEquals(15, player.getHcoin());
  }

  @Test
  void repeatedSettlementCannotCreditTheSameActualDeathsAgain() {
    var b = battle();
    assertNotNull(
        service.finishBattle(
            player,
            b.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            reports(2)));
    assertNull(
        service.finishBattle(
            player,
            b.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            reports(2)));
    assertEquals(2, state(4090301).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void eachSupportedDeathSourceCreditsOnlyItsActualReportedMonster(int death) {
    var b = battle();
    assertNotNull(
        service.finishBattle(
            player,
            b.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            List.of(new MissionBattleKills.Monster(5001, 8011020, STAGE, death))));
    assertEquals(1, state(4090301).getProgress());
  }

  @Test
  void aVictoryWithoutReportedDeathsDoesNotInferKillsFromWaveSize() {
    var b = battle();
    assertNotNull(
        service.finishBattle(
            player,
            b.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertEquals(0, state(4090301).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3})
  void survivingAndEscapedReportsCannotInventKills(int death) {
    var b = battle();
    assertNotNull(
        service.finishBattle(
            player,
            b.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            List.of(new MissionBattleKills.Monster(5001, 8011020, STAGE, death))));
    assertEquals(0, state(4090301).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3})
  void losingOrQuittingNeverCreditReportedKills(int outcome) {
    var b = battle();
    assertNotNull(
        service.finishBattle(
            player,
            b.getId(),
            STAGE,
            BattleEndStatus.forNumber(outcome),
            BattleStatistics.newInstance(),
            reports(2)));
    assertEquals(0, state(4090301).getProgress());
  }

  @Test
  void callingTheRoomCallbackWithoutAcceptedSettlementCannotCreateKills() {
    var b = battle();
    b.setResult(BattleEndStatus.BATTLE_END_WIN);
    b.setReportedMonsters(reports(2));
    player.getUniverseManager().onBattleFinish(b);
    assertEquals(0, state(4090301).getProgress());
  }

  @Test
  void fabricatedCompletedGoalWithoutActualHistoryCannotPay() {
    state(4090301);
    assertTrue(player.getQuestStates().get(4090301).advance(15, 15, 1));
    held(4090301);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void unsupportedOriginalKillConditionShapesCannotPublishOrPay(int mode) throws Exception {
    var row = rules.get(4090301).deepCopy();
    switch (mode) {
      case 0 -> row.addProperty("ParamType", "Equal");
      case 1 -> row.addProperty("ParamInt1", 1);
      case 2 -> row.addProperty("ParamStr1", "unknown");
      case 3 -> row.getAsJsonArray("ParamIntList").add(1);
      case 4 -> row.getAsJsonArray("ParamItemList").add(new JsonObject());
      case 5 -> row.addProperty("Progress", Integer.MAX_VALUE);
      default -> fail();
    }
    GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    held(4090301);
  }

  @ParameterizedTest
  @ValueSource(strings = {"rogueMapping", "roomContents", "assignedRoom"})
  void changedSourceBindingCannotCreditFrozenDeaths(String changed) {
    var b = battle();
    switch (changed) {
      case "rogueMapping" ->
          GameplayDefinitions.rogueMonsters.put(
              10011, new GameplayDefinitions.RogueMonster(10011, 8011020, STAGE));
      case "roomContents" -> GameplayDefinitions.rooms.get(100).GroupWithContent().put(3, 1002);
      case "assignedRoom" -> player.getGameplay().getRogue().getRooms().put(1, 201);
      default -> fail();
    }
    assertNotNull(
        service.finishBattle(
            player,
            b.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            reports(2)));
    assertEquals(0, state(4090301).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrongStage",
        "unknownMonster",
        "duplicateEntity",
        "overRoster",
        "invalidDeath",
        "negativeEntity",
        "mixedUnknown",
        "missingStage"
      })
  void invalidReportedRosterCannotCreditEvenItsValidPrefix(String invalid) {
    var b = battle();
    var first = reports(1).getFirst();
    List<MissionBattleKills.Monster> rows =
        switch (invalid) {
          case "wrongStage" -> List.of(new MissionBattleKills.Monster(5001, 8011020, STAGE + 1, 1));
          case "unknownMonster" ->
              List.of(new MissionBattleKills.Monster(5001, 99999999, STAGE, 1));
          case "duplicateEntity" ->
              List.of(first, new MissionBattleKills.Monster(5001, 8011010, STAGE, 1));
          case "overRoster" ->
              List.of(first, new MissionBattleKills.Monster(5002, 8011020, STAGE, 1));
          case "invalidDeath" -> List.of(new MissionBattleKills.Monster(5001, 8011020, STAGE, 4));
          case "negativeEntity" -> List.of(new MissionBattleKills.Monster(-1, 8011020, STAGE, 1));
          case "mixedUnknown" ->
              List.of(first, new MissionBattleKills.Monster(5002, 99999999, STAGE, 1));
          case "missingStage" -> List.of(new MissionBattleKills.Monster(5001, 8011020, 0, 1));
          default -> throw new AssertionError(invalid);
        };
    assertNotNull(
        service.finishBattle(
            player,
            b.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            rows));
    assertEquals(0, state(4090301).getProgress());
    assertEquals(0, player.getGameplay().getRogueBattleKills().total());
    assertTrue(player.getGameplay().getRogue().getRecordedBattleKills().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "negative", "capacity"})
  void damagedOrExhaustedHistoryCannotRecordAnotherBattle(String shape) throws Exception {
    if (shape.equals("null")) {
      var f = GameplayState.class.getDeclaredField("rogueBattleKills");
      f.setAccessible(true);
      f.set(player.getGameplay(), null);
    } else {
      var f = RogueBattleKillState.class.getDeclaredField("count");
      f.setAccessible(true);
      f.setInt(
          player.getGameplay().getRogueBattleKills(),
          shape.equals("capacity") ? Integer.MAX_VALUE - 1 : -1);
    }
    var b = battle();
    assertNotNull(
        service.finishBattle(
            player,
            b.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            reports(2)));
    assertTrue(player.getGameplay().getRogue().getRecordedBattleKills().isEmpty());
    if (shape.equals("capacity"))
      assertEquals(Integer.MAX_VALUE - 1, player.getGameplay().getRogueBattleKills().total());
    else held(4090301);
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "nonCanonical", "capacity"})
  void damagedOrFullReceiptsCannotCreditAnotherBattle(String shape) throws Exception {
    var receipts = player.getGameplay().getRogue().getRecordedBattleKills();
    if (shape.equals("null")) {
      var f = GameplayState.RogueRun.class.getDeclaredField("recordedBattleKills");
      f.setAccessible(true);
      f.set(player.getGameplay().getRogue(), null);
    } else if (shape.equals("nonCanonical")) receipts.add("01:123456789");
    else for (int i = 1; i <= 4096; i++) receipts.add(i + ":123456789");
    var b = battle();
    assertNotNull(
        service.finishBattle(
            player,
            b.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            reports(2)));
    assertEquals(0, player.getGameplay().getRogueBattleKills().total());
    assertEquals(0, state(4090301).getProgress());
  }

  @Test
  void concurrentSettlementsAcceptAndCreditTheTwoActualDeathsOnce() throws Exception {
    var b = battle();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var jobs = new ArrayList<java.util.concurrent.Callable<Boolean>>();
      for (int i = 0; i < 24; i++)
        jobs.add(
            () ->
                service.finishBattle(
                        player,
                        b.getId(),
                        STAGE,
                        BattleEndStatus.BATTLE_END_WIN,
                        BattleStatistics.newInstance(),
                        reports(2))
                    != null);
      int successful = 0;
      for (var task : pool.invokeAll(jobs)) if (task.get()) successful++;
      assertEquals(1, successful);
      assertEquals(2, player.getGameplay().getRogueBattleKills().total());
      assertEquals(1, player.getGameplay().getRogue().getRecordedBattleKills().size());
    }
  }

  @Test
  void concurrentClaimsOfTheActualFifteenGoalPayFiveJadesOnce() throws Exception {
    kill(15);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var jobs = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 24; i++)
        jobs.add(() -> player.getQuestManager().claim(4090301).retcode());
      int successful = 0;
      for (var task : pool.invokeAll(jobs)) if (task.get() == Retcode.SUCC) successful++;
      assertEquals(1, successful);
      assertEquals(5, player.getHcoin());
    }
  }

  void copyPlayer(
      emu.lunarcore.game.player.Player from,
      emu.lunarcore.game.player.Player to,
      List<String> names)
      throws Exception {
    for (String name : names) {
      var f = emu.lunarcore.game.player.Player.class.getDeclaredField(name);
      f.setAccessible(true);
      f.set(to, f.get(from));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoPlayerMorphiaReloadsRetainAThousandDeathsAndBothRewardStates(boolean claimed)
      throws Exception {
    kill(1000);
    if (claimed)
      for (int id : List.of(4090301, 4090303))
        assertEquals(Retcode.SUCC, player.getQuestManager().claim(id).retcode());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "battle-kill-achievements");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      copyPlayer(player, document, List.of("name", "gameplay", "questStates", "hcoin"));
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456784);
      store.save(document);
      var first = store.find(emu.lunarcore.game.player.Player.class).first();
      assertEquals(1000, first.getGameplay().getRogueBattleKills().total());
      assertEquals(1, first.getGameplay().getRogue().getRecordedBattleKills().size());
      assertEquals(claimed ? 15 : 0, first.getHcoin());
      for (int id : List.of(4090301, 4090303))
        assertEquals(
            claimed ? QuestState.Phase.CLOSED : QuestState.Phase.FINISHED,
            first.getQuestStates().get(id).getPhase());
      store.save(first);
      var second = store.find(emu.lunarcore.game.player.Player.class).first();
      copyPlayer(second, player, List.of("gameplay", "questStates", "hcoin"));
      for (int id : List.of(4090301, 4090303))
        assertEquals(
            claimed ? Retcode.QUEST_REWARD_ALREADY_TAKEN : Retcode.SUCC,
            player.getQuestManager().claim(id).retcode());
      assertEquals(15, player.getHcoin());
      assertEquals(1000, state(4090303).getProgress());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void acceptedDeathsAndReceiptAreSavedBeforeQuestAndActorRemovalNotifications() throws Exception {
    var saved = new ArrayList<Integer>();
    var notices = new ArrayList<emu.lunarcore.server.packet.BasePacket>();
    var session = new emu.lunarcore.server.game.GameSession(null) {};
    var actor =
        new Explorer() {
          public emu.lunarcore.server.game.GameSession getSession() {
            return session;
          }

          public void save() {
            saved.add(getGameplay().getRogueBattleKills().total());
            if (saved.getLast() > 0)
              assertEquals(1, getGameplay().getRogue().getRecordedBattleKills().size());
          }

          public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {
            if (getGameplay().getRogueBattleKills().total() > 0) {
              assertEquals(getGameplay().getRogueBattleKills().total(), saved.getLast());
              notices.add(packet);
            }
          }
        };
    copyPlayer(player, actor, List.of("name", "gameplay", "questStates"));
    player = actor;
    player.scene = new Scene(player, plane, 80001001);
    player.scene.setEntryId(8000101);
    var b = battle();
    assertNotNull(
        service.finishBattle(
            player,
            b.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            reports(2)));
    assertEquals(
        1,
        notices.stream()
            .filter(emu.lunarcore.server.packet.send.PacketGetQuestDataScRsp.class::isInstance)
            .count());
    assertEquals(
        1,
        notices.stream()
            .filter(
                emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify.class::isInstance)
            .count());
    assertEquals(2, state(4090301).getProgress());
  }

  @Test
  void savedDeathsRecoverTheGoalIfTheProgressCallbackWasInterrupted() {
    kill(15);
    player.getQuestStates().put(4090301, new QuestState(4090301));
    assertEquals(15, state(4090301).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(4090301).getPhase());
    assertEquals(0, player.getHcoin());
  }

  @Test
  void wonSourceEncounterDoesNotRespawnAfterActualSceneRecreation() {
    // The second actor is an explicit clear-boundary fixture, not an original placement.
    var info = gson.toJsonTree(group.getMonsterList().getFirst()).getAsJsonObject();
    info.addProperty("ID", 200002);
    var remaining =
        new EntityMonster(
            player.scene,
            GameData.getNpcMonsterExcelMap().get(8011020),
            group,
            gson.fromJson(info, MonsterInfo.class));
    remaining.setCustomStage(STAGE);
    remaining.setEventId(STAGE);
    player.scene.addEntity(remaining);
    kill(2);
    assertFalse(player.getGameplay().getRogue().getCleared().contains(1));
    player.scene = new Scene(player, plane, 80001001);
    player.scene.setEntryId(8000101);
    assertTrue(
        player.scene.getEntities().values().stream().noneMatch(EntityMonster.class::isInstance));
    assertEquals(2, state(4090301).getProgress());
  }

  @Test void firstVictoryOffersABlessingBeforeOtherEncountersAreCleared() throws Exception {
    int buff = 19997101;
    var oldBuff = GameData.getMazeBuffExcelMap().get(buff, 1);
    var oldProp = GameData.getPropExcelMap().get(100);
    var oldBarrier = GameData.getPropExcelMap().get(100006);
    var old10 = floor.getGroups().get(10);
    var oldBlessings = List.copyOf(GameplayDefinitions.blessings);
    try {
      GameData.getMazeBuffExcelMap().add(gson.fromJson("{\"ID\":" + buff + ",\"Lv\":1}", MazeBuffExcel.class));
      GameplayDefinitions.blessings.clear();
      GameplayDefinitions.blessings.add(new GameplayDefinitions.Blessing(buff, 1, 100, "Common", 1, List.of()));
      var trigger = gson.fromJson("{\"ID\":100,\"PropType\":\"PROP_ORDINARY\","
          + "\"PropStateList\":[\"Closed\",\"Open\"]}", PropExcel.class);
      trigger.onLoad(); GameData.getPropExcelMap().add(trigger);
      var barrier = gson.fromJson("{\"ID\":100006,\"PropType\":\"PROP_ORDINARY\","
          + "\"PropStateList\":[\"Closed\",\"Open\"]}", PropExcel.class);
      barrier.onLoad(); GameData.getPropExcelMap().add(barrier);
      try (var in = getClass().getResourceAsStream("/fixtures/rogue-first-story-group10-4.6.json")) {
        var group10 = gson.fromJson(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8), GroupInfo.class);
        group10.setId(10); floor.getGroups().put(10, group10);
      }
      floor.onLoad();
      assertEquals("3,300001", floor.getGroups().get(10).getPropList().stream()
          .filter(p -> p.getID() == 300005).findFirst().orElseThrow().getSharedValueByKey("怪物死亡标记"),
          "Production floor loading must retain the authored encounter binding");
      var run = new GameplayState.RogueRun(100, 1, Map.of(1, 100));
      run.setupTeam(player, List.of(199961));
      run.enter(1, 100);
      player.getGameplay().setRogue(run);
      player.scene = new Scene(player, plane, 80001001);
      player.scene.setEntryId(8000101);
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(player.scene, 300005).getState());
      var laterGroup = new GroupInfo(); laterGroup.setId(4);
      var later = new EntityMonster(player.scene, new NpcMonsterExcel(), laterGroup,
          gson.fromJson("{\"ID\":200002}", MonsterInfo.class));
      later.setEntityId(99999);
      player.scene.getEntities().put(later.getEntityId(), later);
      var first = battle();
      assertNotNull(service.finishBattle(player, first.getId(), STAGE, BattleEndStatus.BATTLE_END_WIN,
          BattleStatistics.newInstance(), reports(2)));
      assertFalse(run.getCleared().contains(1), "A later encounter is still alive");
      assertTrue(run.getDefeatedRogueMonsters().contains("1:100:80001:80001001:3:200001"),
          "The first encounter must be saved before its blessing is offered");
      assertEquals(List.of(buff), run.getOffers(), "The first victory should offer its own blessing");
      var source = player.scene.getEntities().values().stream()
          .filter(emu.lunarcore.game.scene.entity.EntityProp.class::isInstance)
          .map(emu.lunarcore.game.scene.entity.EntityProp.class::cast)
          .filter(p -> p.getGroupId() == 3 && p.getInstId() == 300001).findFirst().orElseThrow();
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, source.getState());
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(player.scene, 300005).getState(),
          "The barrier remains until its blessing has been selected");
      int selection = run.getSelectionId();
      player.failSave = true;
      assertThrows(IllegalStateException.class, () -> player.getUniverseManager().selectBlessing(selection, buff));
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(player.scene, 300005).getState(),
          "A failed save must not publish a barrier opening");
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, source.getState());
      player.failSave = false;
      assertEquals(Retcode.SUCC, player.getUniverseManager().selectBlessing(selection, buff));
      assertEquals(1, run.getBlessings().get(buff));
      assertEquals(emu.lunarcore.game.enums.PropState.Open, source.getState(), "The source trigger opens after selection");
      assertEquals(emu.lunarcore.game.enums.PropState.Open, barrier(player.scene, 300005).getState(),
          "The matching barrier must open after its source encounter and blessing");
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(player.scene, 300006).getState());
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(player.scene, 300007).getState());
      assertEquals(Retcode.REQ_PARA_INVALID, player.getUniverseManager().selectBlessing(selection, buff));
      var reloaded = new Scene(player, plane, 80001001);
      var restored = reloaded.getEntities().values().stream()
          .filter(emu.lunarcore.game.scene.entity.EntityProp.class::isInstance)
          .map(emu.lunarcore.game.scene.entity.EntityProp.class::cast)
          .filter(p -> p.getGroupId() == 3 && p.getInstId() == 300001).findFirst().orElseThrow();
      assertEquals(emu.lunarcore.game.enums.PropState.Open, restored.getState(), "A reconnect preserves the opened source trigger");
      assertEquals(emu.lunarcore.game.enums.PropState.Open, barrier(reloaded, 300005).getState(),
          "The matching barrier remains open after reconnect");
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(reloaded, 300006).getState());
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(reloaded, 300007).getState());
    } finally {
      GameplayDefinitions.blessings.clear(); GameplayDefinitions.blessings.addAll(oldBlessings);
      GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == buff && v.getLv() == 1);
      if (oldBuff != null) GameData.getMazeBuffExcelMap().add(oldBuff);
      GameData.getPropExcelMap().keySet().remove(100);
      if (oldProp != null) GameData.getPropExcelMap().add(oldProp);
      GameData.getPropExcelMap().keySet().remove(100006);
      if (oldBarrier != null) GameData.getPropExcelMap().add(oldBarrier);
      if (old10 == null) floor.getGroups().remove(10); else floor.getGroups().put(10, old10);
    }
  }

  emu.lunarcore.game.scene.entity.EntityProp barrier(Scene scene, int id) {
    return scene.getEntities().values().stream()
        .filter(emu.lunarcore.game.scene.entity.EntityProp.class::isInstance)
        .map(emu.lunarcore.game.scene.entity.EntityProp.class::cast)
        .filter(p -> p.getGroupId() == 10 && p.getInstId() == id && p.getPropId() == 100006)
        .findFirst().orElseThrow();
  }

  JsonObject originalBarrierGroup() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-first-story-group10-4.6.json")) {
      return JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  void installBarrierGroup(JsonObject original) {
    var group10 = gson.fromJson(original, GroupInfo.class); group10.setId(10);
    floor.getGroups().put(10, group10);
    for (int id : List.of(4, 5)) {
      var copy = gson.fromJson(gson.toJson(group), GroupInfo.class);
      copy.setId(id); floor.getGroups().put(id, copy);
    }
    for (int id : List.of(100, 100006)) {
      var prop = gson.fromJson("{\"ID\":" + id + ",\"PropType\":\"PROP_ORDINARY\","
          + "\"PropStateList\":[\"Closed\",\"Open\"]}", PropExcel.class);
      prop.onLoad(); GameData.getPropExcelMap().add(prop);
    }
  }

  void restoreBarrierProps(PropExcel trigger, PropExcel barrier) {
    for (int id : List.of(100, 100006)) GameData.getPropExcelMap().keySet().remove(id);
    if (trigger != null) GameData.getPropExcelMap().add(trigger);
    if (barrier != null) GameData.getPropExcelMap().add(barrier);
  }

  @Test void eachEncounterOnlyOpensItsBoundBarrierAndSurvivesReload() throws Exception {
    var oldTrigger = GameData.getPropExcelMap().get(100);
    var oldBarrier = GameData.getPropExcelMap().get(100006);
    try {
      installBarrierGroup(originalBarrierGroup());
      floor.onLoad();
      var run = new GameplayState.RogueRun(100, 1, Map.of(1, 100));
      run.setupTeam(player, List.of(199961)); run.enter(1, 100); player.getGameplay().setRogue(run);
      player.scene = new Scene(player, plane, 80001001); player.scene.setEntryId(8000101);
      for (int encounter : List.of(3, 4, 5)) {
        int id = 300002 + encounter;
        assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(player.scene, id).getState());
        run.getDefeatedRogueMonsters().add("1:100:80001:80001001:" + encounter + ":200001");
        run.getFirstStoryBlessingReceipts().put(encounter, -1);
        RogueTutorialCombat.syncTrigger(player, encounter);
        assertEquals(emu.lunarcore.game.enums.PropState.Open, barrier(player.scene, id).getState());
        if (encounter < 5)
          assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(player.scene, id + 1).getState());
        run = gson.fromJson(gson.toJson(run), GameplayState.RogueRun.class);
        player.getGameplay().setRogue(run);
        player.scene = new Scene(player, plane, 80001001); player.scene.setEntryId(8000101);
        assertEquals(emu.lunarcore.game.enums.PropState.Open, barrier(player.scene, id).getState());
      }
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(player.scene, 300010).getState());
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(player.scene, 300011).getState());
    } finally { restoreBarrierProps(oldTrigger, oldBarrier); }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-victory", "unselected", "wrong-binding", "wrong-key", "extra-source",
      "wrong-type", "wrong-script", "duplicate", "other-area"})
  void incompatibleOrIncompleteEvidenceCannotOpenABarrier(String variant) throws Exception {
    var oldTrigger = GameData.getPropExcelMap().get(100);
    var oldBarrier = GameData.getPropExcelMap().get(100006);
    try {
      var data = originalBarrierGroup();
      var props = data.getAsJsonArray("PropList");
      var target = java.util.stream.StreamSupport.stream(props.spliterator(), false)
          .map(JsonElement::getAsJsonObject).filter(p -> p.get("ID").getAsInt() == 300005).findFirst().orElseThrow();
      if (variant.equals("wrong-binding"))
        target.getAsJsonObject("ValueSource").getAsJsonArray("Values").get(0).getAsJsonObject().addProperty("Value", "4,300001");
      if (variant.equals("wrong-key"))
        target.getAsJsonObject("ValueSource").getAsJsonArray("Values").get(0).getAsJsonObject().addProperty("Key", "Other");
      if (variant.equals("wrong-type"))
        target.getAsJsonObject("ValueSource").getAsJsonArray("Values").get(0).getAsJsonObject().addProperty("$type", "RPG.GameCore.SharedInt");
      if (variant.equals("extra-source"))
        target.getAsJsonObject("ValueSource").getAsJsonArray("Values").add(
            target.getAsJsonObject("ValueSource").getAsJsonArray("Values").get(0).deepCopy());
      if (variant.equals("wrong-script")) target.addProperty("InitLevelGraph", "Config/Other.json");
      if (variant.equals("duplicate")) props.add(target.deepCopy());
      installBarrierGroup(data);
      floor.onLoad();
      var run = new GameplayState.RogueRun(variant.equals("other-area") ? 101 : 100, 1, Map.of(1, 100));
      run.setupTeam(player, List.of(199961)); run.enter(1, 100); player.getGameplay().setRogue(run);
      if (!variant.equals("missing-victory"))
        run.getDefeatedRogueMonsters().add("1:100:80001:80001001:3:200001");
      run.getFirstStoryBlessingReceipts().put(3, variant.equals("unselected") ? 0 : -1);
      player.scene = new Scene(player, plane, 80001001); player.scene.setEntryId(8000101);
      assertTrue(player.scene.getEntities().values().stream()
          .filter(emu.lunarcore.game.scene.entity.EntityProp.class::isInstance)
          .map(emu.lunarcore.game.scene.entity.EntityProp.class::cast)
          .filter(p -> p.getGroupId() == 10 && p.getInstId() == 300005)
          .allMatch(p -> p.getState() == emu.lunarcore.game.enums.PropState.Closed));
      RogueTutorialCombat.syncTrigger(player, 3);
      assertEquals(emu.lunarcore.game.enums.PropState.Closed, barrier(player.scene, 300005).getState());
    } finally { restoreBarrierProps(oldTrigger, oldBarrier); }
  }

  @Test void queuedVictoriesSurviveReloadAndOfferExactlyOneSelectionEach() {
    var oldBlessings = List.copyOf(GameplayDefinitions.blessings);
    var old4 = floor.getGroups().get(4);
    int firstBuff = 19997121, secondBuff = 19997122;
    try {
      GameplayDefinitions.blessings.clear();
      for (int id : List.of(firstBuff, secondBuff)) {
        GameData.getMazeBuffExcelMap().add(gson.fromJson("{\"ID\":" + id + ",\"Lv\":1}", MazeBuffExcel.class));
        GameplayDefinitions.blessings.add(new GameplayDefinitions.Blessing(id, 1, 100, "Common", 1, List.of()));
      }
      var four = gson.fromJson(gson.toJson(group), GroupInfo.class);
      four.setId(4); floor.getGroups().put(4, four);
      var run = new GameplayState.RogueRun(100, 1, Map.of(1, 100));
      run.setupTeam(player, List.of(199961)); run.enter(1, 100);
      run.getDefeatedRogueMonsters().add("1:100:80001:80001001:3:200001");
      run.getDefeatedRogueMonsters().add("1:100:80001:80001001:4:200001");
      player.getGameplay().setRogue(run);
      player.scene = new Scene(player, plane, 80001001); player.scene.setEntryId(8000101);
      player.getUniverseManager().restoreFirstStoryBlessings();
      assertEquals(2, run.getFirstStoryBlessingReceipts().size());
      assertEquals(0, run.getFirstStoryBlessingReceipts().get(4));
      int firstSelection = run.getSelectionId();
      int selected = run.getOffers().getFirst();
      run = gson.fromJson(gson.toJson(run), GameplayState.RogueRun.class);
      player.getGameplay().setRogue(run);
      assertEquals(Retcode.SUCC, player.getUniverseManager().selectBlessing(firstSelection, selected));
      assertEquals(-1, run.getFirstStoryBlessingReceipts().get(3));
      assertTrue(run.getSelectionId() > firstSelection);
      assertEquals(run.getSelectionId(), run.getFirstStoryBlessingReceipts().get(4));
      int secondSelection = run.getSelectionId();
      int second = run.getOffers().getFirst();
      assertNotEquals(selected, second);
      assertEquals(Retcode.SUCC, player.getUniverseManager().selectBlessing(secondSelection, second));
      assertEquals(Map.of(3, -1, 4, -1), run.getFirstStoryBlessingReceipts());
      player.getUniverseManager().restoreFirstStoryBlessings();
      assertTrue(run.getOffers().isEmpty());
      assertEquals(2, run.getBlessings().size());
    } finally {
      GameplayDefinitions.blessings.clear(); GameplayDefinitions.blessings.addAll(oldBlessings);
      GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == firstBuff || v.getID() == secondBuff);
      if (old4 == null) floor.getGroups().remove(4); else floor.getGroups().put(4, old4);
    }
  }

  @Test void completedEncountersResumeAnInterruptedRoomClearOnlyWithAllGroupsLoaded() {
    var run = new GameplayState.RogueRun(100, 1, Map.of(1, 100));
    run.setupTeam(player, List.of(199961)); run.enter(1, 100);
    for (int id : List.of(3, 4, 5, 10, 11, 12))
      run.getDefeatedRogueMonsters().add("1:100:80001:80001001:" + id + ":200001");
    for (int id : List.of(3, 4, 5, 11, 12)) run.getFirstStoryBlessingReceipts().put(id, -1);
    run = gson.fromJson(gson.toJson(run), GameplayState.RogueRun.class);
    player.getGameplay().setRogue(run);
    player.scene = new Scene(player, plane, 80001001); player.scene.setEntryId(8000101);
    player.getUniverseManager().restoreFirstStoryBlessings();
    assertFalse(run.getCleared().contains(1), "An incomplete scene cannot prove the room is clear");
    for (int id : List.of(4, 5, 10, 11, 12)) {
      var placement = gson.fromJson(gson.toJson(group), GroupInfo.class);
      placement.setId(id); floor.getGroups().put(id, placement);
    }
    player.scene = new Scene(player, plane, 80001001); player.scene.setEntryId(8000101);
    player.failSave = true;
    assertThrows(IllegalStateException.class, () -> player.getUniverseManager().restoreFirstStoryBlessings());
    assertFalse(run.getCleared().contains(1), "A failed save must not acknowledge the clear");
    player.failSave = false;
    player.getUniverseManager().restoreFirstStoryBlessings();
    assertTrue(run.getCleared().contains(1), "A saved final victory must recover the interrupted clear");
    int saves = player.saves;
    player.getUniverseManager().restoreFirstStoryBlessings();
    assertEquals(saves, player.saves, "Recovery must be idempotent");
  }

}
