package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original all-type query, with isolated prerequisite and inventory fixtures. */
class OfferingQuery46Test {
  @TempDir Path root;
  final KraterOffering46Test fixture = new KraterOffering46Test();
  final Gson gson = ResourceLoader.getGson();
  final JsonObject catalog =
      JsonParser.parseReader(
              new java.io.InputStreamReader(
                  Objects.requireNonNull(
                      getClass().getResourceAsStream("/fixtures/offering-query-4.6.json")),
                  StandardCharsets.UTF_8))
          .getAsJsonObject();

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    for (String section : List.of("records", "groups", "headers", "additionalTables"))
      for (var entry : catalog.getAsJsonObject(section).entrySet())
        fixture.write(entry.getKey(), entry.getValue());
    for (String name : List.of("MainMission", "FuncUnlockData")) {
      String path = "ExcelOutput/" + name + ".json";
      String key = name.equals("MainMission") ? "MainMissionID" : "UnlockID";
      var merged = catalog.getAsJsonObject("records").getAsJsonArray(path).deepCopy();
      var known = new HashSet<Integer>();
      merged.forEach(row -> known.add(row.getAsJsonObject().get(key).getAsInt()));
      for (var row : fixture.source.getAsJsonObject("records").getAsJsonArray(path))
        if (known.add(row.getAsJsonObject().get(key).getAsInt())) merged.add(row.deepCopy());
      fixture.write(path, merged);
    }
    for (var row :
        catalog.getAsJsonObject("records").getAsJsonArray("ExcelOutput/MainMission.json"))
      GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
    var subIds = new HashSet<Integer>();
    for (var row : catalog.getAsJsonObject("records").getAsJsonArray("ExcelOutput/SubMission.json"))
      subIds.add(row.getAsJsonObject().get("SubMissionID").getAsInt());
    for (var header : catalog.getAsJsonObject("headers").entrySet())
      for (var row : header.getValue().getAsJsonObject().getAsJsonArray("SubMissionList")) {
        var sub = gson.fromJson(row, MissionInfo.SubMissionInfo.class);
        if (subIds.contains(sub.getId())) GameplayDefinitions.subs.put(sub.getId(), sub);
      }
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
  }

  DynamicMessage query(List<Integer> ids) throws Exception {
    var descriptor = fixture.session.protocol.clientSchema().message("GetOfferingInfoCsReq");
    var b = DynamicMessage.newBuilder(descriptor);
    ids.forEach(id -> b.addRepeatedField(descriptor.findFieldByName("offering_id_list"), id));
    return raw(b.build().toByteArray());
  }

  DynamicMessage raw(byte[] body) throws Exception {
    var session = fixture.session;
    assertTrue(
        VersionedPacketHandler.handle(
            session, session.protocol.clientCommand("GetOfferingInfoCsReq"), body));
    assertEquals("GetOfferingInfoScRsp", session.name);
    return session.response;
  }

  static int number(DynamicMessage message, String key) {
    return KraterOffering46Test.number(message, key);
  }

  static List<DynamicMessage> infos(DynamicMessage message) {
    return ((List<?>) KraterOffering46Test.field(message, "offering_info_list"))
        .stream().map(DynamicMessage.class::cast).toList();
  }

  DynamicMessage info(int id) throws Exception {
    var response = query(List.of(id));
    assertEquals(0, number(response, "retcode"));
    assertEquals(1, infos(response).size());
    return infos(response).getFirst();
  }

  void completeMain(int id) {
    var state = new QuestState(id);
    state.advance(1, 1, 1);
    state.reserveReward();
    state.closeReward();
    fixture.player.getGameplay().getMainMissions().put(id, state);
  }

  void completeSub(int id) {
    var definition = GameplayDefinitions.subs.get(id);
    completeMain(definition.getMainMissionID());
    var state = new QuestState(id);
    state.advance(1, Math.max(1, definition.getProgress()), 1);
    fixture.player.getGameplay().getSubMissions().put(id, state);
  }

  @Test
  void clientAllFifteenTypeQueryKeepsItsOrderAndDoesNotWritePlayerProgress() throws Exception {
    var ids = IntStream.rangeClosed(1, 15).boxed().toList();
    var response = query(ids);
    assertEquals(0, number(response, "retcode"));
    assertEquals(ids, infos(response).stream().map(i -> number(i, "offering_id")).toList());
    for (var row : infos(response)) {
      assertEquals(0, number(row, "offering_level"));
      assertEquals(0, number(row, "level_exp"));
      assertEquals(0, number(row, "total_exp"));
      assertEquals(List.of(), KraterOffering46Test.field(row, "has_taken_reward_id_list"));
    }
    assertEquals(0, fixture.player.saves);
    assertEquals(0, fixture.player.debits);
    assertEquals(0, fixture.player.grants);
    assertTrue(fixture.player.getGameplay().getOfferings().isEmpty());
  }

  @Test
  void mixedQueryKeepsActualPaidAndClaimedOfferingSixHistory() throws Exception {
    fixture.stock(1300);
    assertEquals(0, fixture.submit(fixture.actor()));
    assertEquals(
        0,
        number(fixture.request("TakeOfferingRewardCsReq", List.of(6), fixture.actor()), "retcode"));
    int saves = fixture.player.saves;
    var response = query(List.of(10, 6, 12, 1));
    assertEquals(0, number(response, "retcode"));
    var row = infos(response).get(1);
    assertEquals(6, number(row, "offering_level"));
    assertEquals(1300, number(row, "total_exp"));
    assertEquals(List.of(6), KraterOffering46Test.field(row, "has_taken_reward_id_list"));
    assertEquals(saves, fixture.player.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4, 5, 10})
  void sourceQuestUnlockIsReportedOnlyAfterItsRealPrerequisiteCompletes(int id) throws Exception {
    assertEquals(1, number(info(id), "offering_state"));
    switch (id) {
      case 1 -> completeSub(103040105);
      case 2 -> completeMain(1032101);
      case 4 -> completeSub(802510113);
      case 5 -> completeMain(8026101);
      case 10 -> completeMain(1040505);
      default -> throw new AssertionError(id);
    }
    assertEquals(2, number(info(id), "offering_state"));
    assertEquals(0, number(info(id), "offering_level"));
    assertEquals(0, fixture.player.saves);
  }

  @Test
  void cancelledMainAndSubHistoryCannotAdvertiseAnUnlockedEntry() throws Exception {
    completeMain(1040505);
    fixture.player.getGameplay().getCancelledMissions().add(1040505);
    assertEquals(1, number(info(10), "offering_state"));
    completeSub(103040105);
    fixture.player.getGameplay().getCancelledSubs().add(103040105);
    assertEquals(1, number(info(1), "offering_state"));
  }

  @Test
  void sourceSubWithoutItsParentHistoryStaysLocked() throws Exception {
    completeSub(103040105);
    fixture.player.getGameplay().getMainMissions().remove(1030401);
    assertEquals(1, number(info(1), "offering_state"));
  }

  @Test
  void loadedPrerequisiteMutationCannotUnlockSourceEntry() throws Exception {
    completeSub(103040105);
    var source = GameplayDefinitions.subs.get(103040105);
    var changed = gson.toJsonTree(source).getAsJsonObject();
    changed.addProperty("MainMissionID", 1040104);
    GameplayDefinitions.subs.put(
        103040105, gson.fromJson(changed, MissionInfo.SubMissionInfo.class));
    assertEquals(1, number(info(1), "offering_state"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 16, -1})
  void unknownTypeRejectsTheWholeBatchWithoutPartialEntries(int id) throws Exception {
    var response = query(List.of(6, id, 12));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), number(response, "retcode"));
    assertTrue(infos(response).isEmpty());
    assertEquals(0, fixture.player.saves);
  }

  @Test
  void duplicateKnownTypeRejectsTheWholeBatch() throws Exception {
    var response = query(List.of(1, 6, 1));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), number(response, "retcode"));
    assertTrue(infos(response).isEmpty());
  }

  @Test
  void oversizedAndTruncatedUint32QueriesDoNotLeakPartialRows() throws Exception {
    var field =
        fixture
            .session
            .protocol
            .clientSchema()
            .message("GetOfferingInfoCsReq")
            .findFieldByName("offering_id_list")
            .getNumber();
    var bytes = new java.io.ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(bytes);
    wire.writeUInt64(field, 0x100000001L);
    wire.flush();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), number(raw(bytes.toByteArray()), "retcode"));
    assertTrue(infos(fixture.session.response).isEmpty());
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        number(raw(new byte[] {(byte) ((field << 3) | 2), 3, 1}), "retcode"));
    assertTrue(infos(fixture.session.response).isEmpty());
  }

  @Test
  void changedOriginalTypeDefinitionRejectsFullClientQuery() throws Exception {
    Path path = root.resolve("ExcelOutput/OfferingTypeConfig.json");
    var original = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    original.get(13).getAsJsonObject().addProperty("MaxLevel", 81);
    Files.writeString(path, gson.toJson(original));
    var response = query(IntStream.rangeClosed(1, 15).boxed().toList());
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), number(response, "retcode"));
    assertTrue(infos(response).isEmpty());
  }

  @Test
  void packedAndUnpackedOriginalFullQueriesBothPreserveTheOriginalOrder() throws Exception {
    var ids = IntStream.rangeClosed(1, 15).boxed().toList();
    int field =
        fixture
            .session
            .protocol
            .clientSchema()
            .message("GetOfferingInfoCsReq")
            .findFieldByName("offering_id_list")
            .getNumber();
    for (boolean packed : new boolean[] {false, true}) {
      var body = new java.io.ByteArrayOutputStream();
      var wire = CodedOutputStream.newInstance(body);
      if (packed) {
        var payload = new java.io.ByteArrayOutputStream();
        var inner = CodedOutputStream.newInstance(payload);
        for (int id : ids) inner.writeUInt32NoTag(id);
        inner.flush();
        wire.writeByteArray(field, payload.toByteArray());
      } else for (int id : ids) wire.writeUInt32(field, id);
      wire.flush();
      var response = raw(body.toByteArray());
      assertEquals(0, number(response, "retcode"));
      assertEquals(ids, infos(response).stream().map(row -> number(row, "offering_id")).toList());
    }
  }

  @Test
  void warmedCatalogCannotHideSameSizeAndTimestampSourceChanges() throws Exception {
    assertEquals(0, number(info(7), "offering_level"));
    Path path = root.resolve("ExcelOutput/OfferingTypeConfig.json");
    var before = Files.getLastModifiedTime(path);
    long size = Files.size(path);
    var json = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    json.get(0).getAsJsonObject().addProperty("ItemID", 120004);
    Files.writeString(path, gson.toJson(json));
    Files.setLastModifiedTime(path, before);
    assertEquals(size, Files.size(path));
    var response = query(List.of(7, 1, 6));
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), number(response, "retcode"));
    assertTrue(infos(response).isEmpty());
  }

  @Test
  void missingLoadedMainDefinitionDoesNotUnlockAnOtherwiseFinishedRecord() throws Exception {
    completeMain(1040505);
    GameData.getMainMissionExcelMap().keySet().remove(1040505);
    assertEquals(1, number(info(10), "offering_state"));
  }

  @Test
  void unknownStoredOfferingProgressIsRejectedInsteadOfReplacedByZero() throws Exception {
    fixture.player.getGameplay().getOfferings().put(10, new GameplayState.Offering());
    var response = query(List.of(1, 10, 6));
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), number(response, "retcode"));
    assertTrue(infos(response).isEmpty());
    assertEquals(0, fixture.player.saves);
  }

  @Test
  void corruptStoredOfferingSixRejectsTheWholeBatchBeforeAnyEntryIsPublished() throws Exception {
    var state = gson.toJsonTree(fixture.player.getGameplay().getKraterOffering()).getAsJsonObject();
    state.addProperty("version", 2);
    fixture.player.getGameplay().kraterOffering(gson.fromJson(state, KraterOfferingState.class));
    var response = query(List.of(1, 6, 12));
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), number(response, "retcode"));
    assertTrue(infos(response).isEmpty());
    assertEquals(0, fixture.player.saves);
  }

  @Test
  void unknownWireFieldRejectsTheWholeBatch() throws Exception {
    var descriptor = fixture.session.protocol.clientSchema().message("GetOfferingInfoCsReq");
    var body = new java.io.ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(body);
    wire.writeUInt32(descriptor.findFieldByName("offering_id_list").getNumber(), 1);
    wire.writeUInt32(999, 7);
    wire.flush();
    var response = raw(body.toByteArray());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), number(response, "retcode"));
    assertTrue(infos(response).isEmpty());
  }
}
