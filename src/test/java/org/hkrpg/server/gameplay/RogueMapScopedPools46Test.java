package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.server.packet.Retcode;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual source map 101 and authored room rows; unlock and player scene are explicit fixtures. */
class RogueMapScopedPools46Test {
  @TempDir Path directory;
  final Map<Field, Object> saved = new HashMap<>();
  final Map<Integer, emu.lunarcore.data.config.MissionInfo> oldMissions = new HashMap<>();
  Object oldConfig;
  Field config;
  Path resources, data;

  @BeforeEach
  @SuppressWarnings({"rawtypes", "unchecked"})
  void prepare() throws Exception {
    for (var field : GameplayDefinitions.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isPublic(field.getModifiers()))
        continue;
      field.setAccessible(true);
      var value = field.get(null);
      if (value instanceof Map map) saved.put(field, new HashMap(map));
      else if (value instanceof Set set) saved.put(field, new HashSet(set));
      else if (value instanceof List list) saved.put(field, new ArrayList(list));
      else if (field.getType() == boolean.class) saved.put(field, value);
    }
    oldMissions.putAll(GameData.getMissionInfos());
    config = LunarCore.class.getDeclaredField("config");
    config.setAccessible(true);
    oldConfig = config.get(null);
    resources = directory.resolve("resources");
    data = directory.resolve("data");
    Files.createDirectories(resources.resolve("ExcelOutput"));
    Files.createDirectories(data);
    var settings = new Config();
    settings.dataDir = data.toString();
    settings.resourceDir = resources.toString();
    config.set(null, settings);
    // Source data is supplied from a fixed Git fixture; no operator directory is read or changed.
    try (var reader =
        new java.io.InputStreamReader(
            getClass().getResourceAsStream("/fixtures/rogue-map-scoped-pools-4.6.json"),
            java.nio.charset.StandardCharsets.UTF_8)) {
      var fixture = JsonParser.parseReader(reader).getAsJsonObject();
      var area = fixture.getAsJsonObject("area").deepCopy();
      area.addProperty("UnlockID", 0);
      write(
          resources.resolve("ExcelOutput/RogueAreaConfig.json"), new Gson().toJson(List.of(area)));
      write(resources.resolve("ExcelOutput/RogueMap.json"), fixture.get("sites").toString());
      write(resources.resolve("ExcelOutput/RogueRoom.json"), fixture.get("rooms").toString());
      write(data.resolve("RogueMapGen.json"), fixture.get("legacyPools").toString());
      write(data.resolve("RogueMapGenByMap.json"), fixture.get("mappedPools").toString());
    }
  }

  void write(Path path, String value) throws Exception {
    Files.writeString(path, value);
  }

  @AfterEach
  @SuppressWarnings({"rawtypes", "unchecked"})
  void restore() throws Exception {
    for (var item : saved.entrySet()) {
      var field = item.getKey();
      var current = field.get(null);
      if (current instanceof Map map) {
        map.clear();
        map.putAll((Map) item.getValue());
      } else if (current instanceof Collection collection) {
        collection.clear();
        collection.addAll((Collection) item.getValue());
      } else field.set(null, item.getValue());
    }
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldMissions);
    config.set(null, oldConfig);
  }

  @Test
  void originalLinearMapCanStartWithItsExplicitMapScopedRoomPool() {
    GameplayDefinitions.load(resources);
    var player = new UniverseLifecycleTest.Explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(110));
    assertEquals(101, player.getGameplay().getRogue().getMapId());
    assertEquals(
        Map.of(1, 304, 2, 302, 3, 303, 4, 304, 5, 305, 6, 306, 7, 307),
        player.getGameplay().getRogue().getRooms());
    assertEquals(304, player.getGameplay().getRogue().getRoomId());
    assertEquals(8000103, player.getEntryId());
  }

  @Test
  void firstStoryUniverseUsesItsAuthoredRoomInsteadOfTheLegacyGenericPool() {
    GameplayDefinitions.load(resources);
    GameplayDefinitions.areas.put(100, new GameplayDefinitions.Area(100, 0, 1, 0, 0));
    GameplayDefinitions.maps.put(1, List.of(new GameplayDefinitions.Site(1, 1, true, List.of(), 0)));
    GameplayDefinitions.rooms.put(
        100, new GameplayDefinitions.Room(100, 1, 8000101, 10,
            Map.of(10, 1006, 3, 1001, 4, 1002, 5, 1003, 11, 1004, 12, 1005)));
    // The existing operator file configures map 101 only; the legacy site 1 is another world.
    assertFalse(GameplayDefinitions.roomPool.get(1).contains(100));
    assertFalse(GameplayDefinitions.roomPoolByMap.containsKey(1));
    assertEquals(Map.of(1, 100), RogueMapPools.assign(1, 10001, 100).orElseThrow().rooms());
    assertEquals(Map.of(1, 100), RogueMapPools.assign(1, 10002, 100).orElseThrow().rooms());
    var oldFloor = GameData.getFloorInfos().get("P80001_F80001001");
    var oldEntry = GameData.getMapEntranceExcelMap().get(8000101);
    var gson = emu.lunarcore.data.ResourceLoader.getGson();
    var floor = new emu.lunarcore.data.config.FloorInfo();
    var group = gson.fromJson("{\"AnchorList\":[{\"ID\":1,\"PosX\":-23.854,"
        + "\"PosY\":0.005451,\"PosZ\":236.15,\"RotY\":180}]}",
        emu.lunarcore.data.config.GroupInfo.class);
    group.setId(10);
    floor.getGroups().put(10, group);
    GameData.getFloorInfos().put("P80001_F80001001", floor);
    GameData.getMapEntranceExcelMap().add(gson.fromJson(
        "{\"ID\":8000101,\"PlaneID\":80001,\"FloorID\":80001001}",
        emu.lunarcore.data.excel.MapEntranceExcel.class));
    try {
      var player = new UniverseLifecycleTest.Explorer();
      assertEquals(Retcode.SUCC, player.getUniverseManager().start(100));
      assertEquals(8000101, player.getEntryId());
      assertEquals(100, player.getGameplay().getRogue().getRoomId());
      assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, player.getUniverseManager().start(100));
    } finally {
      if (oldFloor == null) GameData.getFloorInfos().remove("P80001_F80001001");
      else GameData.getFloorInfos().put("P80001_F80001001", oldFloor);
      if (oldEntry == null) GameData.getMapEntranceExcelMap().keySet().remove(8000101);
      else GameData.getMapEntranceExcelMap().add(oldEntry);
    }
  }

  @Test
  void missingOrAlteredTutorialRoomCannotFallBackToAnUnrelatedWorld() {
    GameplayDefinitions.load(resources);
    GameplayDefinitions.areas.put(100, new GameplayDefinitions.Area(100, 0, 1, 0, 0));
    GameplayDefinitions.maps.put(1, List.of(new GameplayDefinitions.Site(1, 1, true, List.of(), 0)));
    GameplayDefinitions.rooms.remove(100);
    assertTrue(RogueMapPools.assign(1, 10001, 100).isEmpty());
    GameplayDefinitions.rooms.put(100, new GameplayDefinitions.Room(100, 1, 8000102, 10));
    assertTrue(RogueMapPools.assign(1, 10001, 100).isEmpty());
  }

  @Test
  void firstStoryBossUsesTheExistingLevelSevenTramplerEncounterWhenItsLinkIsMissing() {
    GameplayDefinitions.load(resources);
    var room = new GameplayDefinitions.Room(100, 1, 8000101, 10,
        Map.of(10, 1006, 3, 1001, 4, 1002, 5, 1003, 11, 1004, 12, 1005));
    GameplayDefinitions.rooms.put(100, room);
    GameplayDefinitions.rogueMonsterGroups.remove(1006);
    GameplayDefinitions.rogueMonsterGroups.put(1101,
        new GameplayDefinitions.RogueMonsterGroup(1101, Map.of(11011, 1)));
    GameplayDefinitions.rogueMonsters.put(11011,
        new GameplayDefinitions.RogueMonster(11011, 8013010, 80011011));
    var run = new GameplayState.RogueRun(100, 1, Map.of(1, 100));
    run.enter(1, 100);
    assertEquals(1101, RogueBossEncounters.content(run, room, 10));
    assertEquals(1001, RogueBossEncounters.content(run, room, 3));
    var other = new GameplayState.RogueRun(101, 1, Map.of(1, 100));
    other.enter(1, 100);
    assertEquals(1006, RogueBossEncounters.content(other, room, 10));
    GameplayDefinitions.rogueMonsterGroups.remove(1101);
    assertNull(RogueBossEncounters.content(run, room, 10));
    GameplayDefinitions.rogueMonsterGroups.put(1101,
        new GameplayDefinitions.RogueMonsterGroup(1101, Map.of(11011, 2)));
    assertNull(RogueBossEncounters.content(run, room, 10));
    GameplayDefinitions.rogueMonsterGroups.put(1006,
        new GameplayDefinitions.RogueMonsterGroup(1006, Map.of(10061, 1)));
    assertEquals(1006, RogueBossEncounters.content(run, room, 10));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknownMap",
        "unknownSite",
        "unknownRoom",
        "booleanRoom",
        "decimalRoom",
        "stringRoom",
        "badKey",
        "extraRoot",
        "negativeRoom",
        "wrongPool"
      })
  void invalidScopedConfigurationCannotBeSilentlyFiltered(String corruption) throws Exception {
    var root =
        JsonParser.parseString(Files.readString(data.resolve("RogueMapGenByMap.json")))
            .getAsJsonObject();
    var maps = root.getAsJsonObject("maps");
    var pool = maps.getAsJsonObject("101");
    switch (corruption) {
      case "unknownMap" -> maps.add("999999", new JsonObject());
      case "unknownSite" -> pool.add("999999", JsonParser.parseString("[304]"));
      case "unknownRoom" -> pool.add("1", JsonParser.parseString("[999999]"));
      case "booleanRoom" -> pool.add("1", JsonParser.parseString("[true]"));
      case "decimalRoom" -> pool.add("1", JsonParser.parseString("[304.5]"));
      case "stringRoom" -> pool.add("1", JsonParser.parseString("[\"304\"]"));
      case "badKey" -> pool.add("01", JsonParser.parseString("[304]"));
      case "extraRoot" -> root.addProperty("unknown", 1);
      case "negativeRoom" -> pool.add("1", JsonParser.parseString("[-304]"));
      case "wrongPool" -> pool.add("1", new JsonObject());
      default -> fail(corruption);
    }
    var path = data.resolve("RogueMapGenByMap.json");
    write(path, root.toString());
    var bytes = Files.readAllBytes(path);
    assertThrows(IllegalStateException.class, () -> GameplayDefinitions.load(resources));
    assertArrayEquals(bytes, Files.readAllBytes(path));
    assertTrue(GameplayDefinitions.roomPoolByMap.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "duplicate",
        "dangling",
        "cycle",
        "disconnected",
        "wrongMap",
        "twoStarts",
        "noStart",
        "self",
        "duplicateEdge"
      })
  void brokenSourceTopologyCannotPublishAnExploration(String corruption) {
    GameplayDefinitions.load(resources);
    var sites = new ArrayList<>(GameplayDefinitions.maps.get(101));
    var first = sites.getFirst();
    var last = sites.getLast();
    switch (corruption) {
      case "duplicate" -> sites.add(first);
      case "dangling" ->
          sites.set(6, new GameplayDefinitions.Site(101, 7, false, List.of(999999), 0));
      case "cycle" -> sites.set(6, new GameplayDefinitions.Site(101, 7, false, List.of(2), 0));
      case "disconnected" ->
          sites.set(5, new GameplayDefinitions.Site(101, 6, false, List.of(), 0));
      case "wrongMap" ->
          sites.set(6, new GameplayDefinitions.Site(999999, last.SiteID(), false, List.of(), 0));
      case "twoStarts" -> sites.set(6, new GameplayDefinitions.Site(101, 7, true, List.of(), 0));
      case "noStart" -> sites.set(0, new GameplayDefinitions.Site(101, 1, false, List.of(2), 0));
      case "self" -> sites.set(6, new GameplayDefinitions.Site(101, 7, false, List.of(7), 0));
      case "duplicateEdge" ->
          sites.set(0, new GameplayDefinitions.Site(101, 1, true, List.of(2, 2), 0));
      default -> fail(corruption);
    }
    GameplayDefinitions.maps.put(101, sites);
    var player = new UniverseLifecycleTest.Explorer();
    var before = new Gson().toJson(player.getGameplay());
    var party = List.copyOf(player.getCurrentLineup().getAvatars());
    assertEquals(Retcode.ROGUE_AREA_INVALID, player.getUniverseManager().start(110));
    assertEquals(before, new Gson().toJson(player.getGameplay()));
    assertEquals(11, player.getEntryId());
    assertEquals(party, player.getCurrentLineup().getAvatars());
  }

  @Test
  void anExplicitEmptyScopedPoolDisablesThatSiteWithoutLegacyFallback() throws Exception {
    var root =
        JsonParser.parseString(Files.readString(data.resolve("RogueMapGenByMap.json")))
            .getAsJsonObject();
    root.getAsJsonObject("maps").getAsJsonObject("101").add("1", new JsonArray());
    write(data.resolve("RogueMapGenByMap.json"), root.toString());
    GameplayDefinitions.load(resources);
    assertFalse(GameplayDefinitions.roomPool.get(1).isEmpty());
    var player = new UniverseLifecycleTest.Explorer();
    assertEquals(Retcode.ROGUE_AREA_INVALID, player.getUniverseManager().start(110));
    assertEquals(11, player.getEntryId());
  }

  @Test
  void aMissingScopedFilePreservesLegacyPoolSelection() throws Exception {
    Files.delete(data.resolve("RogueMapGenByMap.json"));
    GameplayDefinitions.load(resources);
    // Only the first legacy pool is fully loaded by this deliberately small resource fixture.
    for (int site : List.of(2, 3, 4, 5, 6, 7)) GameplayDefinitions.roomPool.put(site, List.of(304));
    var player = new UniverseLifecycleTest.Explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(110));
    assertTrue(
        GameplayDefinitions.roomPool.get(1).contains(player.getGameplay().getRogue().getRoomId()));
    assertTrue(GameplayDefinitions.roomPoolByMap.isEmpty());
  }

  @Test
  void installingDefaultsPreservesExistingOperatorFiles() throws Exception {
    var names = List.of("RogueMapGen.json", "RogueMapGenByMap.json");
    var before = new HashMap<String, byte[]>();
    for (var name : names) before.put(name, Files.readAllBytes(data.resolve(name)));
    org.hkrpg.server.DefaultData.install(data);
    for (var name : names)
      assertArrayEquals(before.get(name), Files.readAllBytes(data.resolve(name)));
  }
}
