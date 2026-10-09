package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Whole original single-owner rows; ordinary scene/formal actor are explicit fixtures. */
class SingleStageEventDifficulty46Test {
  @TempDir Path root;
  final StageEventDifficulty46Test source = new StageEventDifficulty46Test();
  JsonObject original;
  static final int[] EVENTS = {
    20003011, 20004025, 20131015, 20132013, 20212013, 20221216, 20222020,
    20231051, 20241202, 20311050, 20321050, 20331053, 40123003, 20332072,
    20411062, 20411070, 20413065, 20413072, 20423080, 20451080, 20462060,
    20462080, 10307030, 20501090, 20501201, 20511080, 20531080, 20561080
  };

  @BeforeEach
  void prepare() throws Exception {
    source.root = root;
    source.prepare();
    original =
        source.baseline.fixture.source.helper.fixture.fixture("single-stage-events-4.6.json");
    var records = original.getAsJsonObject("records");
    Files.writeString(
        root.resolve("ExcelOutput/DifficultyAdjustmentStage.json"),
        records.get("ExcelOutput/DifficultyAdjustmentStage.json").toString());
    for (var raw : records.getAsJsonArray("ExcelOutput/PlaneEvent.json"))
      GameData.getPlaneEventExcelMap().add(source.gson.fromJson(raw, PlaneEventExcel.class));
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

  Battle enter(int event) throws Exception {
    var type = source.session.getProtocol().clientSchema().message("SceneEnterStageCsReq");
    var out =
        source.call(
            "SceneEnterStageCsReq",
            DynamicMessage.newBuilder(type)
                .setField(type.findFieldByName("event_id"), event)
                .build());
    assertEquals(0, RaidDifficultyAdjustment46Test.number(out, "retcode"));
    return Objects.requireNonNull(source.baseline.fixture.player.getBattle());
  }

  void originalStage(Battle battle, int event, int world) {
    int stageId = event * 10 + world;
    var raw =
        StreamSupport.stream(
                original
                    .getAsJsonObject("records")
                    .getAsJsonArray("ExcelOutput/StageConfig.json")
                    .spliterator(),
                false)
            .map(JsonElement::getAsJsonObject)
            .filter(row -> row.get("StageID").getAsInt() == stageId)
            .findFirst()
            .orElseThrow();
    assertEquals(stageId, battle.getStage().getId());
    assertEquals(raw.get("Level").getAsInt(), ((StageExcel) battle.getStage()).getLevel());
    assertEquals(world, battle.toProto().getWorldLevel());
    assertEquals(battle.getStage().getMonsterWaves().size(), battle.getWaves().size());
    for (int i = 0; i < battle.getWaves().size(); i++)
      assertEquals(
          battle.getStage().getMonsterWaves().get(i), battle.getWaves().get(i).getMonsters());
  }

  @ParameterizedTest
  @MethodSource("selections")
  void allSingleEventsUseTheirOwnOriginalSelectedStage(int event, int world, int difficulty)
      throws Exception {
    var player = source.baseline.fixture.player;
    player.world = world;
    assertEquals(
        0, RaidDifficultyAdjustment46Test.number(source.update(event, 2, difficulty), "retcode"));
    var battle = enter(event);
    originalStage(battle, event, world - difficulty);
    var wire =
        (DynamicMessage)
            RaidDifficultyAdjustment46Test.field(source.session.response, "battle_info");
    assertEquals(world - difficulty, RaidDifficultyAdjustment46Test.number(wire, "world_level"));
    assertEquals(world, player.getWorldLevel());
    assertEquals(2, player.getCurrentLineup().getMp());
  }

  @ParameterizedTest
  @MethodSource("defaults")
  void everyUnselectedWorldUsesOriginalDefaultWithoutInventingPreferences(int event, int world)
      throws Exception {
    var player = source.baseline.fixture.player;
    player.world = world;
    int saves = player.saves;
    originalStage(enter(event), event, world);
    assertTrue(player.getGameplay().getStageEventDifficultySettings().snapshot().isEmpty());
    assertEquals(saves, player.saves);
  }

  @Test
  void multiplePreferencesStayIndependentAndQueryInStableOrder() throws Exception {
    assertEquals(
        0, RaidDifficultyAdjustment46Test.number(source.update(20561080, 2, 1), "retcode"));
    assertEquals(
        0, RaidDifficultyAdjustment46Test.number(source.update(20004025, 2, 0), "retcode"));
    assertEquals(
        0, RaidDifficultyAdjustment46Test.number(source.update(20003011, 2, 1), "retcode"));
    assertEquals(
        List.of(
            source.baseline.choice(20003011, 2, 1),
            source.baseline.choice(20004025, 2, 0),
            source.baseline.choice(20561080, 2, 1)),
        RaidDifficultyAdjustment46Test.field(
            source.call("DifficultyAdjustmentGetDataCsReq", null), "content_package_list"));
    originalStage(enter(20004025), 20004025, 1);
  }

  @Test
  void anotherEventCannotBorrowAnEasyPreference() throws Exception {
    assertEquals(
        0, RaidDifficultyAdjustment46Test.number(source.update(20561080, 2, 1), "retcode"));
    originalStage(enter(20004025), 20004025, 1);
  }

  @ParameterizedTest
  @CsvSource({"1,0", "1,1", "2,0", "2,1", "6,0", "6,1"})
  void aRaidRetainsItsFrozenSourceOneWorldDespiteTheOppositeEventPreference(
      int world, int difficulty) throws Exception {
    var player = source.baseline.fixture.player;
    player.world = world;
    assertEquals(
        0,
        RaidDifficultyAdjustment46Test.number(
            source.update(20321050, 2, 1 - difficulty), "retcode"));
    assertEquals(
        0, RaidDifficultyAdjustment46Test.number(source.update(43306, 1, difficulty), "retcode"));
    assertEquals(Retcode.SUCC, new MissionRaid(player).enter(43306, 0, 0, 0, List.of()));
    var battle = Objects.requireNonNull(source.service.startEventBattle(player, 20321050));
    originalStage(battle, 20321050, world - difficulty);
    assertFalse(battle instanceof StageEventDifficultyBattle);
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        20101012, 20111010, 20123009, 20223206, 20223217, 20223222, 20312050, 20332050, 10306051,
        43001201, 20242065, 20421065, 20421075, 10441051
      })
  void missingGroupedOwnerRowsRemainRejected(int event) throws Exception {
    int saves = source.baseline.fixture.player.saves;
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        RaidDifficultyAdjustment46Test.number(source.update(event, 2, 1), "retcode"));
    assertEquals(saves, source.baseline.fixture.player.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "null", "true", "\"invalid\""})
  void malformedOtherOwnerAliasesRejectWithoutThrowingOrSaving(String invalid) throws Exception {
    var rows =
        original
            .getAsJsonObject("records")
            .getAsJsonArray("ExcelOutput/DifficultyAdjustmentStage.json")
            .deepCopy();
    rows.add(JsonParser.parseString("{\"ID\":99,\"EventIDs\":[" + invalid + "]}"));
    Files.writeString(root.resolve("ExcelOutput/DifficultyAdjustmentStage.json"), rows.toString());
    int saves = source.baseline.fixture.player.saves;
    assertNull(assertDoesNotThrow(() -> StageEventDifficultySettings.identity(20561080)));
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        RaidDifficultyAdjustment46Test.number(source.update(20561080, 2, 1), "retcode"));
    assertEquals(saves, source.baseline.fixture.player.saves);
  }

  @Test
  void duplicateAliasCannotGiveOneEventTwoOwners() throws Exception {
    var rows =
        original
            .getAsJsonObject("records")
            .getAsJsonArray("ExcelOutput/DifficultyAdjustmentStage.json")
            .deepCopy();
    rows.add(JsonParser.parseString("{\"ID\":99,\"EventIDs\":[20561080]}"));
    Files.writeString(root.resolve("ExcelOutput/DifficultyAdjustmentStage.json"), rows.toString());
    assertNull(StageEventDifficultySettings.identity(20561080));
    assertNotEquals(
        0, RaidDifficultyAdjustment46Test.number(source.update(20561080, 2, 1), "retcode"));
  }

  @Test
  void addingOtherVerifiedRowsPreservesTheExistingSavedEventIdentity() throws Exception {
    String full = Objects.requireNonNull(StageEventDifficultySettings.identity(20003011));
    Files.writeString(
        root.resolve("ExcelOutput/DifficultyAdjustmentStage.json"),
        source
            .original
            .getAsJsonObject("records")
            .get("ExcelOutput/DifficultyAdjustmentStage.json")
            .toString());
    assertEquals(full, StageEventDifficultySettings.identity(20003011));
  }

  @Test
  void aStaleSecondPreferenceCannotPartiallyPublishAValidFirstOne() throws Exception {
    assertEquals(
        0, RaidDifficultyAdjustment46Test.number(source.update(20003011, 2, 0), "retcode"));
    assertEquals(
        0, RaidDifficultyAdjustment46Test.number(source.update(20561080, 2, 1), "retcode"));
    GameData.getStageExcelMap().get(205610800).getMonsterWaves().getFirst().set(0, 999999);
    var response = source.call("DifficultyAdjustmentGetDataCsReq", null);
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        RaidDifficultyAdjustment46Test.number(response, "retcode"));
    assertEquals(List.of(), RaidDifficultyAdjustment46Test.field(response, "content_package_list"));
    assertNull(source.service.startEventBattle(source.baseline.fixture.player, 20003011));
  }

  @ParameterizedTest
  @CsvSource({"1,0", "1,1", "6,0", "6,1"})
  void actualPlayerMorphiaRestoresTheCompletePreferenceSetTwice(int world, int difficulty)
      throws Exception {
    var player = source.baseline.fixture.player;
    player.world = world;
    for (int event : EVENTS)
      assertEquals(
          0, RaidDifficultyAdjustment46Test.number(source.update(event, 2, difficulty), "retcode"));
    String expected = source.gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "single-stage-events-fixture");
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
        assertEquals(expected, source.gson.toJson(saved.getGameplay()));
        var copy =
            new DefaultEntryMissionRaid46Test.WorldActor(source.baseline.fixture.source.helper);
        copy.world = world;
        MissionTrialProvisioning46Test.set(copy, "gameplay", saved.getGameplay());
        var formal =
            new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(199986));
        formal.setOwner(copy);
        copy.getAvatars().getAvatars().put(199986, formal);
        copy.getCurrentLineup().getAvatars().add(199986);
        copy.getCurrentLineup().setMp(2);
        source.baseline.fixture.source.helper.position(copy, player.getEntryId());
        for (int event : EVENTS) {
          originalStage(
              Objects.requireNonNull(source.service.startEventBattle(copy, event)),
              event,
              world - difficulty);
          copy.setBattle(null);
        }
        assertEquals(expected, source.gson.toJson(copy.getGameplay()));
        assertEquals(0, copy.saves);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
