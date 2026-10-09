package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original five weighted encounters; floor isolation and test roster are explicit. */
class RogueMonsterSelectionTest {
  static final String FLOOR = "P81002_F81002001";

  static class Explorer extends UniverseCombatFragmentsTest.Explorer {
    Scene scene;

    public Scene getScene() {
      return scene;
    }

    public int getEntryId() {
      return 8100201;
    }

    public int getPlaneId() {
      return 81002;
    }

    public int getFloorId() {
      return 81002001;
    }
  }

  final Gson gson = ResourceLoader.getGson();
  Explorer player = new Explorer();
  MazePlaneExcel plane;
  FloorInfo floor, oldFloor;
  GroupInfo group;
  List<NpcMonsterExcel> oldNpcs;
  List<StageExcel> oldStages;
  List<MonsterExcel> oldMonsters;
  List<MapEntranceExcel> oldEntries;
  Map<Integer, GameplayDefinitions.Room> oldRooms;
  Map<Integer, GameplayDefinitions.RogueMonster> oldMappings;
  Map<Integer, GameplayDefinitions.RogueMonsterGroup> originalGroups;
  Map<Integer, GameplayDefinitions.RogueMonsterGroup> groups;
  boolean originalPresent;
  JsonObject groupRow;

  @BeforeEach
  void setup() throws Exception {
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    oldNpcs = new ArrayList<>(GameData.getNpcMonsterExcelMap().values());
    oldStages = new ArrayList<>(GameData.getStageExcelMap().values());
    oldMonsters = new ArrayList<>(GameData.getMonsterExcelMap().values());
    oldEntries = new ArrayList<>(GameData.getMapEntranceExcelMap().values());
    oldRooms = new HashMap<>(GameplayDefinitions.rooms);
    oldMappings = new HashMap<>(GameplayDefinitions.rogueMonsters);
    groups = GameplayDefinitions.rogueMonsterGroups;
    originalGroups = new HashMap<>(groups);
    originalPresent = GameplayDefinitions.rogueMonsterGroupsPresent;
    groups.clear();
    GameplayDefinitions.rogueMonsterGroupsPresent = true;
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-monster-selection-4.6.json")) {
      var records =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      for (var raw : records) {
        var spec = raw.getAsJsonObject();
        var row = spec.getAsJsonObject("row").deepCopy();
        switch (spec.get("kind").getAsString()) {
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
            group.setId(58);
          }
          case "monsterGroup" -> {
            groupRow = row.deepCopy();
            groups.put(
                row.get("RogueMonsterGroupID").getAsInt(),
                gson.fromJson(row, GameplayDefinitions.RogueMonsterGroup.class));
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
            GameData.getMonsterExcelMap().add(gson.fromJson(row, MonsterExcel.class));
          }
          default -> fail(spec.toString());
        }
      }
    }
    floor.getGroups().put(58, group);
    GameData.getFloorInfos().put(FLOOR, floor);
    assertTrue(player.setNickname("WeightedEncounters"));
  }

  void hash(JsonObject row, String key) {
    row.addProperty(key, row.getAsJsonObject(key).get("Hash").getAsLong());
  }

  @AfterEach
  void restore() {
    GameData.getNpcMonsterExcelMap().values().clear();
    oldNpcs.forEach(GameData.getNpcMonsterExcelMap()::add);
    GameData.getStageExcelMap().values().clear();
    oldStages.forEach(GameData.getStageExcelMap()::add);
    GameData.getMonsterExcelMap().values().clear();
    oldMonsters.forEach(GameData.getMonsterExcelMap()::add);
    GameData.getMapEntranceExcelMap().values().clear();
    oldEntries.forEach(GameData.getMapEntranceExcelMap()::add);
    GameplayDefinitions.rooms.clear();
    GameplayDefinitions.rooms.putAll(oldRooms);
    GameplayDefinitions.rogueMonsters.clear();
    GameplayDefinitions.rogueMonsters.putAll(oldMappings);
    groups.clear();
    groups.putAll(originalGroups);
    GameplayDefinitions.rogueMonsterGroupsPresent = originalPresent;
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
  }

  void begin() {
    var run = new GameplayState.RogueRun(200, 199956, Map.of(1, 200112));
    run.setupTeam(player, List.of(199961));
    player.getGameplay().setRogue(run);
    run.enter(1, 200112);
    run.setInside(true);
    reload();
  }

  void reload() {
    player.scene = new Scene(player, plane, 81002001);
    player.scene.setEntryId(8100201);
  }

  EntityMonster actor() {
    return player.scene.getEntities().values().stream()
        .filter(EntityMonster.class::isInstance)
        .map(EntityMonster.class::cast)
        .filter(m -> m.getGroupId() == 58 && m.getInstId() == 200002)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void originalWeightTableMakesAllFiveEncountersReachableInsteadOfOnlyFirst() {
    var seen = new HashSet<Integer>();
    for (int i = 0; i < 300; i++) {
      begin();
      seen.add(actor().getEventId());
    }
    assertEquals(Set.of(80030081, 80030082, 80031011, 80031012, 80031013), seen);
  }

  @Test
  void candidateWithWrongResourceLookupIdentityDoesNotSpawn() {
    GameplayDefinitions.rogueMonsters.put(30081, GameplayDefinitions.rogueMonsters.get(30082));
    begin();
    assertTrue(
        player.scene.getEntities().values().stream().noneMatch(EntityMonster.class::isInstance));
    assertTrue(player.getGameplay().getRogue().getMonsterSelections().isEmpty());
  }

  @Test
  void everyOriginalWeightTicketHasExactNineNineFourFourFourCoverage() {
    var counts = new HashMap<Integer, Integer>();
    var weights = groups.get(3008).RogueMonsterListAndWeight();
    for (int ticket = 0; ticket < 30; ticket++)
      counts.merge(RogueMonsterSelection.weighted(weights, ticket).orElseThrow(), 1, Integer::sum);
    assertEquals(Map.of(30081, 9, 30082, 9, 31011, 4, 31012, 4, 31013, 4), counts);
    assertTrue(RogueMonsterSelection.weighted(weights, -1).isEmpty());
    assertTrue(RogueMonsterSelection.weighted(weights, 30).isEmpty());
  }

  @Test
  void zeroWeightHasNoTicketAndDoesNotNeedMissingNpcOrStage() {
    var weights = groups.get(3008).RogueMonsterListAndWeight();
    weights.put(1999999, 0);
    for (int ticket = 0; ticket < 30; ticket++)
      assertNotEquals(1999999, RogueMonsterSelection.weighted(weights, ticket).orElseThrow());
    begin();
    assertNotNull(actor());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "negative",
        "null-weight",
        "null-id",
        "nonpositive-id",
        "empty",
        "all-zero",
        "overflow",
        "too-many"
      })
  void invalidWeightTableDoesNotFallbackToFirst(String damage) {
    var weights = groups.get(3008).RogueMonsterListAndWeight();
    switch (damage) {
      case "negative" -> weights.put(30081, -1);
      case "null-weight" -> weights.put(30081, null);
      case "null-id" -> weights.put(null, 1);
      case "nonpositive-id" -> weights.put(0, 1);
      case "empty" -> weights.clear();
      case "all-zero" -> weights.replaceAll((id, w) -> 0);
      case "overflow" -> weights.put(30081, Integer.MAX_VALUE);
      case "too-many" -> {
        for (int i = 1; i < 258; i++) weights.put(i, 1);
      }
    }
    begin();
    assertTrue(
        player.scene.getEntities().values().stream().noneMatch(EntityMonster.class::isInstance));
    assertTrue(player.getGameplay().getRogue().getMonsterSelections().isEmpty());
  }

  @Test
  void missingGroupInConfiguredCatalogDoesNotUseArithmeticFallback() {
    groups.clear();
    begin();
    assertTrue(
        player.scene.getEntities().values().stream().noneMatch(EntityMonster.class::isInstance));
  }

  @Test
  void repeatedActualSceneReloadsReuseOneSavedChoice() {
    begin();
    var npc = actor().getExcel().getId();
    var stage = actor().getEventId();
    var choices = player.getGameplay().getRogue().getMonsterSelections();
    assertEquals(1, choices.size());
    var saved = choices.values().iterator().next();
    int before = player.saves;
    for (int i = 0; i < 20; i++) {
      reload();
      assertEquals(npc, actor().getExcel().getId());
      assertEquals(stage, actor().getEventId());
      assertSame(saved, choices.values().iterator().next());
    }
    assertEquals(before, player.saves);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "weight-changed",
        "stage-missing",
        "npc-missing",
        "monster-missing",
        "selection-null",
        "selection-noncanonical",
        "selection-choice-null",
        "selection-source-changed"
      })
  void damagedSourceOrSavedSelectionNeverRerolls(String damage) throws Exception {
    begin();
    var choice = player.getGameplay().getRogue().getMonsterSelections().values().iterator().next();
    var selections = player.getGameplay().getRogue().getMonsterSelections();
    var keys = Set.copyOf(selections.keySet());
    switch (damage) {
      case "weight-changed" -> groups.get(3008).RogueMonsterListAndWeight().put(30081, 10);
      case "stage-missing" -> GameData.getStageExcelMap().keySet().remove(choice.getStage());
      case "npc-missing" -> GameData.getNpcMonsterExcelMap().keySet().remove(choice.getNpc());
      case "monster-missing" -> GameData.getMonsterExcelMap().keySet().remove(8011010);
      case "selection-null" -> set(player.getGameplay().getRogue(), "monsterSelections", null);
      case "selection-noncanonical" -> selections.put("01:200112:81002:81002001:58", choice);
      case "selection-choice-null" -> selections.put(keys.iterator().next(), null);
      case "selection-source-changed" -> set(choice, "content", 3007);
    }
    int saves = player.saves;
    reload();
    assertTrue(
        player.scene.getEntities().values().stream().noneMatch(EntityMonster.class::isInstance));
    assertEquals(saves, player.saves);
    if (!"selection-null".equals(damage)) assertTrue(selections.keySet().containsAll(keys));
  }

  static void set(Object target, String field, Object value) throws Exception {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        var f = type.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
        return;
      } catch (NoSuchFieldException inherited) {
      }
    }
    throw new NoSuchFieldException(field);
  }

  @Test
  void selectedOriginalTrotterWaveCountsAcceptedDeathsAndReloadOmitsEncounter() {
    for (int i = 0; i < 300; i++) {
      begin();
      if (actor().getEventId() >= 80031011 && actor().getEventId() <= 80031013) break;
    }
    var target = actor();
    assertTrue(target.getEventId() >= 80031011 && target.getEventId() <= 80031013);
    var battle = new Battle(player, player.getCurrentLineup(), List.of(target));
    player.setBattle(battle);
    var reports = new ArrayList<MissionBattleKills.Monster>();
    int entity = 5001;
    for (var wave : battle.getWaves())
      for (int monster : wave.getMonsters())
        reports.add(new MissionBattleKills.Monster(entity++, monster, wave.getStage().getId(), 1));
    int expected = reports.size();
    assertTrue(expected >= 3);
    assertNotNull(
        new BattleService(null) {
          protected void grantDrops(Battle ignored) {}
        }.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            reports));
    assertEquals(expected, player.getGameplay().getRogueBattleKills().total());
    assertEquals(1, player.getGameplay().getRogue().getDefeatedRogueMonsters().size());
    reload();
    assertTrue(
        player.scene.getEntities().values().stream().noneMatch(EntityMonster.class::isInstance));
  }

  @Test
  void sourceBindingReplacementDuringActualBattleDoesNotCreditDeaths() {
    begin();
    var target = actor();
    var battle = new Battle(player, player.getCurrentLineup(), List.of(target));
    player.setBattle(battle);
    groups.put(3008, gson.fromJson(groupRow, GameplayDefinitions.RogueMonsterGroup.class));
    var reports =
        List.of(
            new MissionBattleKills.Monster(
                5001,
                battle.getWaves().getFirst().getMonsters().getInt(0),
                battle.getStage().getId(),
                1));
    assertNotNull(
        new BattleService(null) {
          protected void grantDrops(Battle ignored) {}
        }.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            reports));
    assertEquals(0, player.getGameplay().getRogueBattleKills().total());
  }

  @Test
  void failedChoiceSaveCreatesNoActorAndRetainsNoChoice() {
    player =
        new Explorer() {
          public void save() {
            throw new IllegalStateException("selection-write-failed");
          }
        };
    begin();
    assertTrue(
        player.scene.getEntities().values().stream().noneMatch(EntityMonster.class::isInstance));
    assertTrue(player.getGameplay().getRogue().getMonsterSelections().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"full-with-current", "full-new", "overfull"})
  void boundedSelectionsAllowExistingChoiceButNeverAllocatePastCapacity(String capacity) {
    begin();
    var choice = player.getGameplay().getRogue().getMonsterSelections().values().iterator().next();
    var choices = player.getGameplay().getRogue().getMonsterSelections();
    choices.clear();
    int start = "full-new".equals(capacity) ? 2 : 1;
    int total = "overfull".equals(capacity) ? 4097 : 4096;
    for (int i = start; i < start + total; i++)
      choices.put(i + ":200112:81002:81002001:58", choice);
    int saves = player.saves;
    reload();
    assertEquals(
        "full-with-current".equals(capacity),
        player.scene.getEntities().values().stream().anyMatch(EntityMonster.class::isInstance));
    assertEquals(total, choices.size());
    assertEquals(saves, player.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-initial", "deleted", "client-only", "duplicate-placement"})
  void malformedPlacementCannotObtainNewEncounterSelection(String damage) throws Exception {
    var placement = group.getMonsterList().getFirst();
    switch (damage) {
      case "not-initial" -> set(placement, "LoadOnInitial", false);
      case "deleted" -> set(placement, "IsDelete", true);
      case "client-only" -> set(placement, "IsClientOnly", true);
      case "duplicate-placement" -> group.getMonsterList().add(placement);
    }
    begin();
    assertTrue(
        player.scene.getEntities().values().stream().noneMatch(EntityMonster.class::isInstance));
    assertTrue(player.getGameplay().getRogue().getMonsterSelections().isEmpty());
  }

  @Test
  void twoActualPlayerMorphiaReadsPreserveSelectedOriginalNpcStageAndWeights() throws Exception {
    begin();
    var npc = actor().getExcel().getId();
    var stage = actor().getEventId();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "weighted-rogue-encounters");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      set(document, "uid", 123456785);
      set(document, "name", player.getName());
      set(document, "gameplay", player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored = store.find(Player.class).first();
        var choices = restored.getGameplay().getRogue().getMonsterSelections();
        assertEquals(1, choices.size());
        var choice = choices.values().iterator().next();
        assertEquals(npc, choice.getNpc());
        assertEquals(stage, choice.getStage());
        assertEquals(groups.get(3008).RogueMonsterListAndWeight(), choice.getWeights());
        set(player, "gameplay", restored.getGameplay());
        reload();
        assertEquals(npc, actor().getExcel().getId());
        assertEquals(stage, actor().getEventId());
        store.save(restored);
      }
    } finally {
      mongo.shutdownNow();
    }
  }
}
