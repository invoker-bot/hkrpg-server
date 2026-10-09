package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Exact original EventIDs groups; preferences belong to actual event IDs, never whole groups. */
class GroupedStageEventDifficulty46Test {
  @TempDir Path root;
  final SingleStageEventDifficulty46Test source = new SingleStageEventDifficulty46Test();
  JsonObject original;
  static final int[] EVENTS = {
    20101011, 20101012, 20111009, 20111010, 20123009, 20123010, 20223205, 20223206,
    20223217, 20223218, 20223222, 20223223, 20312050, 20312051, 20332050, 20332051,
    10306051, 10306052, 10306053, 43001201, 43001206, 20242065, 20242066, 20421065,
    20421066, 20421075, 20421076, 10441051, 10441052
  };

  @BeforeEach
  void prepare() throws Exception {
    source.root = root;
    source.prepare();
    original =
        source.source.baseline.fixture.source.helper.fixture.fixture("all-stage-events-4.6.json");
    source.original = original;
    var records = original.getAsJsonObject("records");
    Files.writeString(
        root.resolve("ExcelOutput/DifficultyAdjustmentStage.json"),
        records.get("ExcelOutput/DifficultyAdjustmentStage.json").toString());
    for (var raw : records.getAsJsonArray("ExcelOutput/PlaneEvent.json"))
      GameData.getPlaneEventExcelMap().add(source.source.gson.fromJson(raw, PlaneEventExcel.class));
    for (var raw : records.getAsJsonArray("ExcelOutput/StageConfig.json")) {
      var row = ResourceLoader.getGson().fromJson(raw, StageExcel.class);
      row.onLoad();
      GameData.getStageExcelMap().add(row);
    }
  }

  @AfterEach
  void restore() throws Exception {
    source.restore();
  }

  static Stream<Arguments> selections() {
    return Arrays.stream(EVENTS)
        .boxed()
        .flatMap(
            event ->
                IntStream.rangeClosed(1, 6)
                    .boxed()
                    .flatMap(
                        world ->
                            IntStream.of(0, 1).mapToObj(diff -> Arguments.of(event, world, diff))));
  }

  static Stream<Arguments> defaults() {
    return Arrays.stream(EVENTS)
        .boxed()
        .flatMap(
            event -> IntStream.rangeClosed(0, 6).mapToObj(world -> Arguments.of(event, world)));
  }

  @ParameterizedTest
  @MethodSource("selections")
  void actualGroupedEventUsesItsOwnSelectedOriginalStage(int event, int world, int difficulty)
      throws Exception {
    var player = source.source.baseline.fixture.player;
    player.world = world;
    assertEquals(
        0,
        RaidDifficultyAdjustment46Test.number(
            source.source.update(event, 2, difficulty), "retcode"));
    source.originalStage(source.enter(event), event, world - difficulty);
    assertEquals(world, player.getWorldLevel());
    assertEquals(2, player.getCurrentLineup().getMp());
  }

  @ParameterizedTest
  @MethodSource("defaults")
  void unselectedGroupedEventRetainsDefaultWithoutPreferences(int event, int world)
      throws Exception {
    var player = source.source.baseline.fixture.player;
    player.world = world;
    int saves = player.saves;
    source.originalStage(source.enter(event), event, world);
    assertTrue(player.getGameplay().getStageEventDifficultySettings().snapshot().isEmpty());
    assertEquals(saves, player.saves);
  }

  JsonObject owner(int id, JsonArray rows) {
    return StreamSupport.stream(rows.spliterator(), false)
        .map(JsonElement::getAsJsonObject)
        .filter(r -> r.get("ID").getAsInt() == id)
        .findFirst()
        .orElseThrow();
  }

  JsonArray rows() {
    return original
        .getAsJsonObject("records")
        .getAsJsonArray("ExcelOutput/DifficultyAdjustmentStage.json")
        .deepCopy();
  }

  void rows(JsonArray rows) throws Exception {
    Files.writeString(root.resolve("ExcelOutput/DifficultyAdjustmentStage.json"), rows.toString());
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        20101012, 20111010, 20123009, 20223206, 20223217, 20223222, 20312050, 20332050, 10306051,
        43001201, 20242065, 20421065, 20421075, 10441051
      })
  void groupPeersNeverBorrowAnotherEventsPreference(int id) throws Exception {
    var aliases = owner(id, rows()).getAsJsonArray("EventIDs");
    int first = aliases.get(0).getAsInt(), second = aliases.get(1).getAsInt();
    assertEquals(
        0, RaidDifficultyAdjustment46Test.number(source.source.update(first, 2, 1), "retcode"));
    source.originalStage(source.enter(first), first, 0);
    source.source.baseline.fixture.player.setBattle(null);
    source.originalStage(source.enter(second), second, 1);
    assertEquals(
        List.of(source.source.baseline.choice(first, 2, 1)),
        RaidDifficultyAdjustment46Test.field(
            source.source.call("DifficultyAdjustmentGetDataCsReq", null), "content_package_list"));
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        20101012, 20111010, 20123009, 20223206, 20223217, 20223222, 20312050, 20332050, 10306051,
        43001201, 20242065, 20421065, 20421075, 10441051
      })
  void changedOriginalGroupOrderCannotBindNewPreferences(int id) throws Exception {
    var rows = rows();
    var aliases = owner(id, rows).getAsJsonArray("EventIDs");
    int event = aliases.get(0).getAsInt();
    var reversed = new JsonArray();
    for (int i = aliases.size() - 1; i >= 0; i--) reversed.add(aliases.get(i));
    owner(id, rows).add("EventIDs", reversed);
    rows(rows);
    int saves = source.source.baseline.fixture.player.saves;
    assertNull(StageEventDifficultySettings.identity(event));
    assertNotEquals(
        0, RaidDifficultyAdjustment46Test.number(source.source.update(event, 2, 1), "retcode"));
    assertEquals(saves, source.source.baseline.fixture.player.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "null", "true", "\"invalid\""})
  void malformedAliasNeverThrowsOrSaves(String invalid) throws Exception {
    var rows = rows();
    owner(20101012, rows).add("EventIDs", JsonParser.parseString("[20101011," + invalid + "]"));
    rows(rows);
    assertNull(assertDoesNotThrow(() -> StageEventDifficultySettings.identity(20101011)));
    int saves = source.source.baseline.fixture.player.saves;
    assertNotEquals(
        0, RaidDifficultyAdjustment46Test.number(source.source.update(20101011, 2, 1), "retcode"));
    assertEquals(saves, source.source.baseline.fixture.player.saves);
  }

  @Test
  void duplicateOriginalOwnerClosesBinding() throws Exception {
    var rows = rows();
    rows.add(owner(20101012, rows).deepCopy());
    rows(rows);
    assertNull(StageEventDifficultySettings.identity(20101011));
  }

  @Test
  void anotherOwnerCannotNameTheSameEvent() throws Exception {
    var rows = rows();
    rows.add(JsonParser.parseString("{\"ID\":99,\"EventIDs\":[20101011]}"));
    rows(rows);
    assertNull(StageEventDifficultySettings.identity(20101011));
  }

  @Test
  void missingOriginalWorldRejectsSelection() throws Exception {
    GameData.getStageExcelMap().keySet().remove(201010115);
    assertNull(StageEventDifficultySettings.identity(20101011));
    assertNotEquals(
        0, RaidDifficultyAdjustment46Test.number(source.source.update(20101011, 2, 1), "retcode"));
  }

  @Test
  void changedGroupInvalidatesTheEntireSavedCollection() throws Exception {
    assertEquals(
        0, RaidDifficultyAdjustment46Test.number(source.source.update(20101011, 2, 1), "retcode"));
    assertEquals(
        0, RaidDifficultyAdjustment46Test.number(source.source.update(20003011, 2, 0), "retcode"));
    var rows = rows();
    owner(20101012, rows).addProperty("ChangedOriginal", true);
    rows(rows);
    var reply = source.source.call("DifficultyAdjustmentGetDataCsReq", null);
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        RaidDifficultyAdjustment46Test.number(reply, "retcode"));
    assertEquals(List.of(), RaidDifficultyAdjustment46Test.field(reply, "content_package_list"));
    assertNull(
        source.source.service.startEventBattle(source.source.baseline.fixture.player, 20003011));
  }

  int[] allEvents() {
    return original.getAsJsonArray("eligibleEventIds").asList().stream()
        .mapToInt(JsonElement::getAsInt)
        .toArray();
  }

  @Test
  void fullMixedCollectionQueriesEachActualEventInSortedOrder() throws Exception {
    var expected = new ArrayList<com.google.protobuf.DynamicMessage>();
    for (int event : allEvents()) {
      int difficulty = event % 2;
      assertEquals(
          0,
          RaidDifficultyAdjustment46Test.number(
              source.source.update(event, 2, difficulty), "retcode"));
      expected.add(source.source.baseline.choice(event, 2, difficulty));
    }
    assertEquals(
        expected,
        RaidDifficultyAdjustment46Test.field(
            source.source.call("DifficultyAdjustmentGetDataCsReq", null), "content_package_list"));
  }

  @ParameterizedTest
  @CsvSource({"1,0", "1,1", "6,0", "6,1"})
  void actualPlayerMorphiaRestoresAllFiftySevenPreferencesTwice(int world, int difficulty)
      throws Exception {
    var player = source.source.baseline.fixture.player;
    player.world = world;
    for (int event : allEvents())
      assertEquals(
          0,
          RaidDifficultyAdjustment46Test.number(
              source.source.update(event, 2, difficulty), "retcode"));
    String expected = source.source.gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "all-stage-events-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      MissionTrialProvisioning46Test.set(document, "uid", player.getUid());
      MissionTrialProvisioning46Test.set(document, "worldLevel", world);
      MissionTrialProvisioning46Test.set(document, "gameplay", player.getGameplay());
      store.save(document);
      for (int attempt = 0; attempt < 2; attempt++) {
        var saved =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(expected, source.source.gson.toJson(saved.getGameplay()));
        var copy =
            new DefaultEntryMissionRaid46Test.WorldActor(
                source.source.baseline.fixture.source.helper);
        copy.world = world;
        MissionTrialProvisioning46Test.set(copy, "gameplay", saved.getGameplay());
        var formal =
            new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(199986));
        formal.setOwner(copy);
        copy.getAvatars().getAvatars().put(199986, formal);
        copy.getCurrentLineup().getAvatars().add(199986);
        copy.getCurrentLineup().setMp(2);
        source.source.baseline.fixture.source.helper.position(copy, player.getEntryId());
        for (int event : allEvents()) {
          source.originalStage(
              Objects.requireNonNull(source.source.service.startEventBattle(copy, event)),
              event,
              world - difficulty);
          copy.setBattle(null);
        }
        assertEquals(expected, source.source.gson.toJson(copy.getGameplay()));
        assertEquals(0, copy.saves);
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void anUnknownEventNeverBecomesEligible() throws Exception {
    assertNotEquals(
        0, RaidDifficultyAdjustment46Test.number(source.source.update(999999, 2, 1), "retcode"));
    assertNull(StageEventDifficultySettings.identity(999999));
  }
}
