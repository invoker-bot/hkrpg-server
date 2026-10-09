package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.server.packet.Retcode;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original boss geometry and nine world encounters; unlock and route are explicit fixtures. */
class RogueWorldBossEncounters46Test {
  @TempDir Path directory;
  static final String FLOOR = "P81222_F81222001";
  final Gson gson = ResourceLoader.getGson();
  final Map<Field, Object> saved = new HashMap<>();
  final List<NpcMonsterExcel> npcs = new ArrayList<>();
  final List<StageExcel> stages = new ArrayList<>();
  final List<MonsterExcel> monsters = new ArrayList<>();
  final List<MapEntranceExcel> entries = new ArrayList<>();
  final Map<Integer, MissionInfo> missions = new HashMap<>();
  Object oldConfig;
  Field config;
  FloorInfo oldFloor;
  MazePlaneExcel plane;
  GroupInfo group;
  JsonObject fixture;
  Path resources, data;

  static class Explorer extends UniverseCombatFragmentsTest.Explorer {
    Scene actual;
    MazePlaneExcel sourcePlane;

    public Scene getScene() {
      return actual == null ? super.getScene() : actual;
    }

    public int getPlaneId() {
      return entry == 8122201 ? 81222 : super.getPlaneId();
    }

    public int getFloorId() {
      return entry == 8122201 ? 81222001 : super.getFloorId();
    }

    public boolean enterScene(int id, int teleport, boolean send) {
      if (rejectScene) return false;
      entry = id;
      actual = new Scene(this, sourcePlane, 81222001);
      actual.setEntryId(id);
      return true;
    }
  }

  @BeforeEach
  @SuppressWarnings({"rawtypes", "unchecked"})
  void setup() throws Exception {
    for (var field : GameplayDefinitions.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isPublic(field.getModifiers()))
        continue;
      var value = field.get(null);
      if (value instanceof Map map) saved.put(field, new HashMap(map));
      else if (value instanceof Set set) saved.put(field, new HashSet(set));
      else if (value instanceof List list) saved.put(field, new ArrayList(list));
      else if (field.getType() == boolean.class) saved.put(field, value);
    }
    npcs.addAll(GameData.getNpcMonsterExcelMap().values());
    stages.addAll(GameData.getStageExcelMap().values());
    monsters.addAll(GameData.getMonsterExcelMap().values());
    entries.addAll(GameData.getMapEntranceExcelMap().values());
    missions.putAll(GameData.getMissionInfos());
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    config = LunarCore.class.getDeclaredField("config");
    config.setAccessible(true);
    oldConfig = config.get(null);
    resources = directory.resolve("resources");
    data = directory.resolve("data");
    Files.createDirectories(resources.resolve("ExcelOutput"));
    Files.createDirectories(data);
    var settings = new Config();
    settings.resourceDir = resources.toString();
    settings.dataDir = data.toString();
    config.set(null, settings);
    try (var in =
        getClass().getResourceAsStream("/fixtures/rogue-world-boss-encounters-4.6.json")) {
      fixture =
          JsonParser.parseString(
                  new String(
                      Objects.requireNonNull(in).readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    var areas = fixture.getAsJsonArray("areas").deepCopy();
    var sites = new JsonArray();
    for (var raw : areas) {
      var area = raw.getAsJsonObject();
      area.addProperty("UnlockID", 0);
      int map = area.get("AreaProgress").getAsInt() * 100 + area.get("Difficulty").getAsInt();
      sites.add(
          JsonParser.parseString(
              "{\"RogueMapID\":" + map + ",\"SiteID\":1,\"IsStart\":true,\"NextSiteIDList\":[]}"));
    }
    write("RogueAreaConfig", areas);
    write("RogueMap", sites);
    var rooms = new JsonArray();
    rooms.add(fixture.get("room"));
    write("RogueRoom", rooms);
    write("RogueMonster", fixture.get("monsters"));
    write("RogueMonsterGroup", fixture.get("groups"));
    Files.writeString(data.resolve("RogueMapGen.json"), "{\"1\":[222713]}");
    Files.writeString(
        data.resolve("RogueBossGen.json"), "{\"areas\":" + fixture.get("bossContentByArea") + "}");
    GameplayDefinitions.load(resources);
    for (var raw : fixture.getAsJsonArray("npcs")) {
      var row = raw.getAsJsonObject().deepCopy();
      hash(row, "NPCName");
      GameData.getNpcMonsterExcelMap().add(gson.fromJson(row, NpcMonsterExcel.class));
    }
    for (var raw : fixture.getAsJsonArray("stages")) {
      var row = raw.getAsJsonObject().deepCopy();
      hash(row, "StageName");
      var stage = gson.fromJson(row, StageExcel.class);
      stage.onLoad();
      GameData.getStageExcelMap().add(stage);
    }
    for (var raw : fixture.getAsJsonArray("waveMonsters")) {
      var row = raw.getAsJsonObject().deepCopy();
      hash(row, "MonsterName");
      GameData.getMonsterExcelMap().add(gson.fromJson(row, MonsterExcel.class));
    }
    GameData.getMapEntranceExcelMap()
        .add(gson.fromJson(fixture.get("entry"), MapEntranceExcel.class));
    var planeRow = fixture.getAsJsonObject("plane").deepCopy();
    hash(planeRow, "PlaneName");
    plane = gson.fromJson(planeRow, MazePlaneExcel.class);
    var floor = gson.fromJson(fixture.get("floor"), FloorInfo.class);
    group = gson.fromJson(fixture.get("group"), GroupInfo.class);
    group.setId(39);
    floor.getGroups().clear();
    floor.getGroups().put(39, group);
    GameData.getFloorInfos().put(FLOOR, floor);
  }

  void write(String name, JsonElement rows) throws Exception {
    Files.writeString(resources.resolve("ExcelOutput/" + name + ".json"), rows.toString());
  }

  void hash(JsonObject row, String name) {
    if (row.has(name) && row.get(name).isJsonObject())
      row.addProperty(name, row.getAsJsonObject(name).get("Hash").getAsLong());
  }

  @AfterEach
  @SuppressWarnings({"rawtypes", "unchecked"})
  void restore() throws Exception {
    for (var entry : saved.entrySet()) {
      var current = entry.getKey().get(null);
      if (current instanceof Map map) {
        map.clear();
        map.putAll((Map) entry.getValue());
      } else if (current instanceof Collection collection) {
        collection.clear();
        collection.addAll((Collection) entry.getValue());
      } else entry.getKey().set(null, entry.getValue());
    }
    GameData.getNpcMonsterExcelMap().values().clear();
    npcs.forEach(GameData.getNpcMonsterExcelMap()::add);
    GameData.getStageExcelMap().values().clear();
    stages.forEach(GameData.getStageExcelMap()::add);
    GameData.getMonsterExcelMap().values().clear();
    monsters.forEach(GameData.getMonsterExcelMap()::add);
    GameData.getMapEntranceExcelMap().values().clear();
    entries.forEach(GameData.getMapEntranceExcelMap()::add);
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(missions);
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    config.set(null, oldConfig);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("classicAreas")
  void sharedBossGeometryUsesTheOriginalEncounterForItsWorld(int area) {
    var player = explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(area));
    var binding = RogueMonsterSelection.current(player.getScene(), group);
    assertNotNull(binding, "Missing actual source-bound boss");
    int content =
        fixture.getAsJsonObject("bossContentByArea").get(Integer.toString(area)).getAsInt();
    assertEquals(content, binding.group().RogueMonsterGroupID());
    var source =
        fixture.getAsJsonArray("monsters").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("RogueMonsterID").getAsInt() == content * 10 + 1)
            .findFirst()
            .orElseThrow();
    var actor =
        player.getScene().getEntities().values().stream()
            .filter(EntityMonster.class::isInstance)
            .map(EntityMonster.class::cast)
            .filter(m -> m.getGroupId() == 39)
            .findFirst()
            .orElseThrow();
    assertEquals(source.get("NpcMonsterID").getAsInt(), actor.getExcel().getId());
    assertEquals(source.get("EventID").getAsInt(), actor.getEventId());
    assertSame(GameData.getStageExcelMap().get(actor.getEventId()), actor.getCustomStage());
  }

  static java.util.stream.Stream<Integer> classicAreas() throws Exception {
    try (var in =
        RogueWorldBossEncounters46Test.class.getResourceAsStream(
            "/fixtures/rogue-world-boss-encounters-4.6.json")) {
      var data =
          JsonParser.parseString(
                  new String(
                      Objects.requireNonNull(in).readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonObject();
      return data.getAsJsonArray("areas").asList().stream()
          .map(r -> r.getAsJsonObject().get("RogueAreaID").getAsInt());
    }
  }

  Explorer explorer() {
    var player = new Explorer();
    player.sourcePlane = plane;
    return player;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "npc",
        "stage",
        "waveMonster",
        "group",
        "mapping",
        "zeroWeight",
        "wrongMappingKey",
        "stageType",
        "waves"
      })
  void incompleteOriginalBossCannotPublishANewRun(String damage) throws Exception {
    switch (damage) {
      case "npc" -> GameData.getNpcMonsterExcelMap().keySet().remove(1014011);
      case "stage" -> GameData.getStageExcelMap().keySet().remove(80149011);
      case "waveMonster" -> GameData.getMonsterExcelMap().keySet().remove(1014011);
      case "group" -> GameplayDefinitions.rogueMonsterGroups.remove(14901);
      case "mapping" -> GameplayDefinitions.rogueMonsters.remove(149011);
      case "zeroWeight" ->
          GameplayDefinitions.rogueMonsterGroups
              .get(14901)
              .RogueMonsterListAndWeight()
              .put(149011, 0);
      case "wrongMappingKey" ->
          GameplayDefinitions.rogueMonsters.put(
              149011, GameplayDefinitions.rogueMonsters.get(139011));
      case "stageType" ->
          RogueMonsterSelectionTest.set(
              GameData.getStageExcelMap().get(80149011),
              "StageType",
              emu.lunarcore.game.enums.StageType.Unknown);
      case "waves" ->
          RogueMonsterSelectionTest.set(
              GameData.getStageExcelMap().get(80149011), "monsterWaves", null);
    }
    var player = explorer();
    var before = new Gson().toJson(player.getGameplay());
    int saves = player.saves;
    assertEquals(Retcode.ROGUE_AREA_INVALID, player.getUniverseManager().start(140));
    assertEquals(before, new Gson().toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
    assertEquals(11, player.getEntryId());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknownArea",
        "foreignWorld",
        "missingGroup",
        "string",
        "boolean",
        "fraction",
        "leadingZero",
        "extraRoot"
      })
  void corruptOperatorBindingCannotBePartiallyPublished(String damage) throws Exception {
    var path = data.resolve("RogueBossGen.json");
    var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    var areas = json.getAsJsonObject("areas");
    switch (damage) {
      case "unknownArea" -> areas.addProperty("199999", 14901);
      case "foreignWorld" -> areas.addProperty("140", 17901);
      case "missingGroup" -> GameplayDefinitions.rogueMonsterGroups.remove(14901);
      case "string" -> areas.addProperty("140", "14901");
      case "boolean" -> areas.addProperty("140", true);
      case "fraction" -> areas.addProperty("140", 14901.5);
      case "leadingZero" -> areas.addProperty("0140", 14901);
      case "extraRoot" -> json.addProperty("unexpected", true);
    }
    Files.writeString(path, json.toString());
    var before = Files.readAllBytes(path);
    assertThrows(IllegalStateException.class, () -> RogueBossEncounters.load(path));
    assertTrue(GameplayDefinitions.bossContentByArea.isEmpty());
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "weight",
        "stageLevel",
        "stageAbility",
        "stageRoster",
        "mapping",
        "catalogRemoved",
        "planRemoved",
        "planArea",
        "planSignature",
        "planFlag"
      })
  void frozenBossCorruptionNeverSpawnsAnotherWorldOrRerolls(String damage) throws Exception {
    var player = explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(140));
    var run = player.getGameplay().getRogue();
    var choices = new Gson().toJson(run.getMonsterSelections());
    int saves = player.saves;
    switch (damage) {
      case "weight" ->
          GameplayDefinitions.rogueMonsterGroups
              .get(14901)
              .RogueMonsterListAndWeight()
              .put(149011, 2);
      case "stageLevel" ->
          RogueMonsterSelectionTest.set(GameData.getStageExcelMap().get(80149011), "Level", 99);
      case "stageAbility" ->
          RogueMonsterSelectionTest.set(
              GameData.getStageExcelMap().get(80149011),
              "StageAbilityConfig",
              List.of("InjectedAbility"));
      case "stageRoster" ->
          GameData.getStageExcelMap().get(80149011).getMonsterWaves().getFirst().set(0, 2024011);
      case "mapping" ->
          GameplayDefinitions.rogueMonsters.put(
              149011, new GameplayDefinitions.RogueMonster(149011, 2024011, 80179011));
      case "catalogRemoved" -> {
        GameplayDefinitions.rogueMonsterGroups.clear();
        GameplayDefinitions.rogueMonsterGroupsPresent = false;
      }
      case "planRemoved" -> RogueMonsterSelectionTest.set(run, "bossEncounter", null);
      case "planArea" -> RogueMonsterSelectionTest.set(run.getBossEncounter(), "area", 170);
      case "planSignature" ->
          RogueMonsterSelectionTest.set(run.getBossEncounter(), "signature", null);
      case "planFlag" -> RogueMonsterSelectionTest.set(run, "bossEncounterPlanned", false);
    }
    player.enterScene(8122201, 0, false);
    assertTrue(
        player.getScene().getEntities().values().stream()
            .noneMatch(EntityMonster.class::isInstance));
    assertEquals(choices, new Gson().toJson(run.getMonsterSelections()));
    assertEquals(saves, player.saves);
  }

  @Test
  void sourceConfigChangesCannotRebindAnExistingJsonRestoredRun() throws Exception {
    var player = explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(140));
    var saved = new Gson().fromJson(new Gson().toJson(player.getGameplay()), GameplayState.class);
    GameplayDefinitions.bossContentByArea.put(140, 17901);
    var restored = explorer();
    RogueMonsterSelectionTest.set(restored, "gameplay", saved);
    restored.enterScene(8122201, 0, false);
    assertEquals(
        14901,
        RogueMonsterSelection.current(restored.getScene(), group).group().RogueMonsterGroupID());
    assertEquals(1, saved.getRogue().getMonsterSelections().size());
    assertEquals(0, restored.saves);
  }

  @Test
  void historicalRunWithoutFrozenBossFieldsKeepsItsOriginalRoomContent() throws Exception {
    var player = explorer();
    var run = new GameplayState.RogueRun(140, 401, Map.of(1, 222713));
    run.setupTeam(player, List.of(199961));
    run.enter(1, 222713);
    player.getGameplay().setRogue(run);
    player.enterScene(8122201, 0, false);
    assertNull(run.getBossEncounter());
    assertFalse(run.isBossEncounterPlanned());
    assertEquals(
        17901,
        RogueMonsterSelection.current(player.getScene(), group).group().RogueMonsterGroupID());
  }

  @Test
  void aMissingOptionalBossFileRetainsTheLegacyPolicy() throws Exception {
    Files.delete(data.resolve("RogueBossGen.json"));
    GameplayDefinitions.load(resources);
    var player = explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(140));
    assertNull(player.getGameplay().getRogue().getBossEncounter());
    assertEquals(
        17901,
        RogueMonsterSelection.current(player.getScene(), group).group().RogueMonsterGroupID());
  }

  @Test
  void defaultInstallationPreservesOperatorBossBindings() throws Exception {
    var path = data.resolve("RogueBossGen.json");
    var before = Files.readAllBytes(path);
    org.hkrpg.server.DefaultData.install(data);
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void twoActualMorphiaPlayerReloadsKeepTheFrozenWorldAndSourceChoice() throws Exception {
    var player = explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(140));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "world-boss-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      RogueMonsterSelectionTest.set(document, "uid", 199989);
      RogueMonsterSelectionTest.set(document, "gameplay", player.getGameplay());
      store.save(document);
      String original = new Gson().toJson(player.getGameplay().getRogue());
      for (int i = 0; i < 2; i++) {
        var restored = store.find(emu.lunarcore.game.player.Player.class).first();
        assertNotNull(restored);
        assertEquals(original, new Gson().toJson(restored.getGameplay().getRogue()));
        var actual = explorer();
        RogueMonsterSelectionTest.set(actual, "gameplay", restored.getGameplay());
        actual.enterScene(8122201, 0, false);
        assertEquals(
            14901,
            RogueMonsterSelection.current(actual.getScene(), group).group().RogueMonsterGroupID());
        assertEquals(0, actual.saves);
        store.save(restored);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
