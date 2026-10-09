package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual inventory, original ordinary book rows; saving is a fixture until the reload test. */
class BookCollection46Test {
  final Gson gson = emu.lunarcore.data.ResourceLoader.getGson();
  final List<ItemExcel> oldItems = new ArrayList<>();
  Actor actor;

  static class Actor extends Player {
    int saves;

    @Override
    public void save() {
      saves++;
    }
  }

  static class Item extends GameItem {
    Item(ItemExcel excel, int amount) {
      super(excel, amount);
    }

    @Override
    public void save() {}
  }

  static class Session extends GameSession {
    final Player actor;
    DynamicMessage response;
    int responses;

    Session(Player actor) {
      super(null);
      this.actor = actor;
      setState(SessionState.ACTIVE);
    }

    @Override
    public Player getPlayer() {
      return actor;
    }

    @Override
    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    @Override
    public void sendClientPacket(String name, byte[] bytes) {
      assertEquals("GetPlayerDetailInfoScRsp", name);
      try {
        response = getProtocol().clientSchema().parse(name, bytes);
        responses++;
      } catch (Exception error) {
        throw new AssertionError(error);
      }
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    oldItems.addAll(GameData.getItemExcelMap().values());
    GameData.getItemExcelMap().values().clear();
    try (var input = getClass().getResourceAsStream("/fixtures/book-collection-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(
                      Objects.requireNonNull(input).readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(13, rows.size());
      for (var raw : rows) {
        var entry = raw.getAsJsonObject();
        if (entry.get("kind").getAsString().equals("item"))
          GameData.getItemExcelMap().add(gson.fromJson(entry.get("row"), ItemExcel.class));
      }
    }
    actor = new Actor();
    DailyMissionAcceptance46Test.set(actor, "uid", 123456804);
    DailyMissionAcceptance46Test.set(actor, "signature", "");
    assertTrue(actor.setNickname("Book collection fixture"));
    actor.saves = 0;
  }

  @AfterEach
  void restore() {
    GameData.getItemExcelMap().values().clear();
    oldItems.forEach(GameData.getItemExcelMap()::add);
  }

  GameItem give(int id, int amount) {
    assertTrue(actor.getInventory().addItem(new Item(GameData.getItemExcelMap().get(id), amount)));
    return actor.getInventory().getMaterialByItemId(id);
  }

  static void setItem(GameItem item, String field, Object value) throws Exception {
    var target = GameItem.class.getDeclaredField(field);
    target.setAccessible(true);
    target.set(item, value);
  }

  @Test
  void noBookReportsZeroInsteadOfFixed200() {
    assertEquals(0, actor.getDisplayInfo().getBookCount());
    assertEquals(0, actor.saves);
  }

  @Test
  void originalMissionRewardBookCountsOne() {
    give(190543, 1);
    assertEquals(1, actor.getDisplayInfo().getBookCount());
  }

  @Test
  void repeatedStackIsOneCollectedBook() {
    give(190543, 1);
    give(190543, 17);
    assertEquals(18, actor.getInventory().getMaterialByItemId(190543).getCount());
    assertEquals(1, actor.getDisplayInfo().getBookCount());
  }

  @Test
  void differentVolumesInSameOriginalSeriesCountSeparately() {
    give(190110, 2);
    give(190212, 3);
    assertEquals(2, actor.getDisplayInfo().getBookCount());
  }

  @Test
  void ordinaryMaterialsAndMissionItemsDoNotBecomeBooks() {
    give(212, 3);
    give(140424, 1);
    assertEquals(0, actor.getDisplayInfo().getBookCount());
  }

  DynamicMessage request(Session session) throws Exception {
    var input =
        DynamicMessage.newBuilder(
            session.getProtocol().clientSchema().message("GetPlayerDetailInfoCsReq"));
    input.setField(input.getDescriptorForType().findFieldByName("uid"), actor.getUid());
    assertTrue(VersionedPacketHandler.handle(session, 2905, input.build().toByteArray()));
    assertEquals(1, session.responses);
    assertTrue(session.response.getUnknownFields().asMap().isEmpty());
    var detail =
        (DynamicMessage)
            session.response.getField(
                session.response.getDescriptorForType().findFieldByName("detail_info"));
    assertEquals(
        actor.getUid(), detail.getField(detail.getDescriptorForType().findFieldByName("uid")));
    return (DynamicMessage)
        detail.getField(detail.getDescriptorForType().findFieldByName("record_info"));
  }

  @Test
  void nativeDetailZeroBookUsesMatchedRecordFieldNine() throws Exception {
    var record = request(new Session(actor));
    assertEquals(0, record.getField(record.getDescriptorForType().findFieldByNumber(9)));
    assertTrue(record.getUnknownFields().asMap().isEmpty());
    assertEquals(0, actor.saves);
  }

  @Test
  void nativeDetailReportsActualUniqueBooksWithoutStackInflation() throws Exception {
    give(190543, 19);
    give(190110, 2);
    give(190212, 5);
    give(212, 3);
    var record = request(new Session(actor));
    assertEquals(3, record.getField(record.getDescriptorForType().findFieldByNumber(9)));
    assertEquals(0, actor.saves);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "zero",
        "negative",
        "overflow",
        "owner",
        "discarded",
        "equipped",
        "legacy-equipped",
        "stale-excel",
        "wrong-store-key",
        "missing-definition"
      })
  void invalidStockCannotInflateLegacyOrNativeBookCount(String fault) throws Exception {
    var book = give(190543, 1);
    switch (fault) {
      case "zero" -> setItem(book, "count", 0);
      case "negative" -> setItem(book, "count", -1);
      case "overflow" -> setItem(book, "count", 100000);
      case "owner" -> setItem(book, "ownerUid", actor.getUid() + 1);
      case "discarded" -> book.setDiscarded(true);
      case "equipped" -> setItem(book, "equipAvatarId", new org.bson.types.ObjectId());
      case "legacy-equipped" -> setItem(book, "equipAvatarExcelId", 1202);
      case "stale-excel" ->
          book.setExcel(gson.fromJson(gson.toJson(book.getExcel()), ItemExcel.class));
      case "wrong-store-key" -> {
        actor.getInventory().getItems().remove(book.getInternalUid());
        actor.getInventory().getItems().put(book.getInternalUid() + 1, book);
      }
      case "missing-definition" -> GameData.getItemExcelMap().values().clear();
      default -> fail(fault);
    }
    assertEquals(0, actor.getDisplayInfo().getBookCount());
    var record = request(new Session(actor));
    assertEquals(0, record.getField(record.getDescriptorForType().findFieldByNumber(9)));
    assertEquals(0, actor.saves);
  }

  @Test
  void ordinaryHiddenBookIsAnOwnedIdentityWithoutInventingReadingState() {
    give(190149, 1);
    assertEquals(1, actor.getDisplayInfo().getBookCount());
    assertTrue(actor.getGameplay().getStoryValues().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"empty", "zero", "duplicate", "unknown", "wire", "truncated", "alias", "negative"})
  void invalidNativeUidRejectsBeforeLookupAndLeavesStockUnchanged(String fault) throws Exception {
    give(190543, 3);
    var out = new java.io.ByteArrayOutputStream();
    var coded = com.google.protobuf.CodedOutputStream.newInstance(out);
    switch (fault) {
      case "empty" -> {}
      case "zero" -> coded.writeUInt64(7, 0);
      case "duplicate" -> {
        coded.writeUInt64(7, actor.getUid());
        coded.writeUInt64(7, actor.getUid());
      }
      case "unknown" -> {
        coded.writeUInt64(7, actor.getUid());
        coded.writeUInt64(1, 1);
      }
      case "wire" -> coded.writeString(7, "wrong");
      case "truncated" -> {
        coded.writeRawByte(56);
        coded.writeRawByte(128);
      }
      case "alias" -> coded.writeUInt64(7, (1L << 32) + actor.getUid());
      case "negative" -> coded.writeUInt64(7, -1L);
      default -> fail(fault);
    }
    coded.flush();
    var session = new Session(actor);
    assertTrue(VersionedPacketHandler.handle(session, 2905, out.toByteArray()));
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
    assertFalse(
        session.response.hasField(
            session.response.getDescriptorForType().findFieldByName("detail_info")));
    assertEquals(3, actor.getInventory().getMaterialByItemId(190543).getCount());
    assertEquals(0, actor.saves);
  }

  @Test
  void nativeDetailWaitsForActiveSession() throws Exception {
    var session = new Session(actor);
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session, 2905, new byte[0]));
    assertEquals(0, session.responses);
  }

  @Test
  void legacyRequestRemainsWithLegacyRegistry() throws Exception {
    var session =
        new Session(actor) {
          @Override
          public ProtocolBridge getProtocol() {
            return ProtocolBridge.forVersion("4.2.0");
          }
        };
    assertFalse(VersionedPacketHandler.handle(session, 2905, new byte[0]));
    assertEquals(0, session.responses);
  }

  @Test
  void nativeBookFieldUsesConsumerAndReaderEvidenceRatherThanReferenceOffsets() throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/book-collection-client-4.6.json")) {
      var proof =
          JsonParser.parseString(
                  new String(
                      Objects.requireNonNull(input).readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonObject();
      assertFalse(proof.get("clientExecutableStarted").getAsBoolean());
      assertFalse(proof.get("clientModified").getAsBoolean());
      assertFalse(proof.get("clientAccepted").getAsBoolean());
      assertEquals(
          "8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3",
          proof.getAsJsonObject("files").get("GameAssembly.dll").getAsString());
      assertEquals(
          "09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130",
          proof.getAsJsonObject("files").get("global-metadata.dat").getAsString());
      var protocol = ProtocolBridge.forVersion("4.6.0");
      assertEquals(2905, protocol.clientCommand("GetPlayerDetailInfoCsReq"));
      assertEquals(2975, protocol.clientCommand("GetPlayerDetailInfoScRsp"));
      assertEquals(
          "uid",
          protocol
              .clientSchema()
              .message("GetPlayerDetailInfoCsReq")
              .findFieldByNumber(7)
              .getName());
      assertEquals(
          "detail_info",
          protocol
              .clientSchema()
              .message("GetPlayerDetailInfoScRsp")
              .findFieldByNumber(7)
              .getName());
      assertEquals(
          "JJKIKOBAJLF",
          protocol.clientSchema().message("PlayerRecordInfo").findFieldByNumber(9).getName());
      assertTrue(
          proof
              .getAsJsonObject("nativeRecordReader")
              .getAsJsonArray("instructions")
              .toString()
              .contains("1e1faaeb: mov dword ptr [rdi + 0x3c], eax"));
      assertTrue(
          proof
              .getAsJsonObject("nativeCollectionConsumer")
              .getAsJsonArray("instructions")
              .toString()
              .contains("dbda9f5: mov dword ptr [rdi + 0x78], eax"));
      assertTrue(
          proof
              .getAsJsonObject("nativeGetter")
              .getAsJsonArray("instructions")
              .toString()
              .contains("dbdbd50: mov eax, dword ptr [rcx + 0x78]"));
    }
  }

  @Test
  void storedBooksRequireActualRowsAndOwnerAndCannotBecomeLifetimeUnlocks() throws Exception {
    var book = new GameItem(GameData.getItemExcelMap().get(190543), 3);
    setItem(book, "ownerUid", actor.getUid());
    assertEquals(0, BookCollection.stored(actor.getUid(), List.of(book)));
    setItem(book, "id", new org.bson.types.ObjectId());
    assertEquals(1, BookCollection.stored(actor.getUid(), List.of(book, book)));
    assertEquals(0, BookCollection.stored(0, List.of(book)));
    assertEquals(0, BookCollection.stored(actor.getUid(), null));
    assertEquals(0, BookCollection.stored(actor.getUid() + 1, List.of(book)));
    setItem(book, "count", 0);
    assertEquals(0, BookCollection.stored(actor.getUid(), List.of(book)));
    assertEquals(0, BookCollection.count(actor));
  }

  @Test
  void twoMorphiaReloadsKeepThreeUniqueBooksAndReadOfflineWithoutWrites() throws Exception {
    int uid = actor.getUid();
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "source_book_collection");
      store
          .getMapper()
          .map(Player.class, emu.lunarcore.game.quest.QuestState.class, GameItem.class);
      var base = new Player();
      DailyMissionAcceptance46Test.set(base, "uid", uid);
      DailyMissionAcceptance46Test.set(base, "name", "Book collection fixture");
      DailyMissionAcceptance46Test.set(base, "signature", "");
      store.save(base);
      for (int[] values : new int[][] {{190543, 19}, {190110, 2}, {190212, 5}, {190543, 7}}) {
        var book = new GameItem(GameData.getItemExcelMap().get(values[0]), values[1]);
        DailyMissionAcceptance46Test.set(book, "ownerUid", uid);
        store.save(book);
      }
      var foreign = new GameItem(GameData.getItemExcelMap().get(190149), 1);
      DailyMissionAcceptance46Test.set(foreign, "ownerUid", uid + 1);
      store.save(foreign);
      for (int iteration = 0; iteration < 2; iteration++) {
        var restored =
            store
                .find(Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                .first();
        assertNotNull(restored);
        var rows = store.find(GameItem.class).iterator().toList();
        assertEquals(5, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.getExcel() == null));
        assertEquals(3, BookCollection.stored(uid, rows));
        assertEquals(0, BookCollection.count(restored));
        for (var row : rows)
          if (row.getOwnerUid() == uid) assertTrue(restored.getInventory().loadItem(row));
        assertEquals(3, BookCollection.count(restored));
        var record = request(new Session(restored));
        assertEquals(3, record.getField(record.getDescriptorForType().findFieldByNumber(9)));
        assertEquals(5, store.find(GameItem.class).count());
        assertEquals(0, actor.saves);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
