package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketGetBagScRsp;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FormulaLearningTest {
  final Gson gson = new Gson();
  JsonArray fixture;
  List<GameplayDefinitions.DailyPool> oldPool;
  final List<Runnable> restorations = new ArrayList<>();
  boolean interruptDebit;
  final Player player =
      new Player() {
        final Inventory actual =
            new Inventory(this) {
              public List<GameItem> addItems(List<GameItem> values, boolean hint) {
                var proposed =
                    values.stream()
                        .map(
                            v ->
                                new GameItem(v.getExcel(), v.getCount()) {
                                  public void save() {}
                                })
                        .map(v -> (GameItem) v)
                        .toList();
                return super.addItems(proposed, hint);
              }
            };

        public Inventory getInventory() {
          return actual;
        }

        public void save() {
          var receipt = getGameplay().getLastItemUse();
          if (interruptDebit && receipt != null && "DEBITED".equals(receipt.getPhase()))
            throw new IllegalStateException("Isolated save interruption");
        }

        public void sendPacket(BasePacket packet) {}
      };

  <T extends GameResource> void install(ExcelMap<T> map, T row) {
    var old = map.get(row.getId());
    restorations.add(
        () -> {
          map.keySet().remove(row.getId());
          if (old != null) map.add(old);
        });
    map.add(row);
  }

  JsonObject row(String kind, int id) {
    return fixture.asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(
            r ->
                kind.equals(r.get("kind").getAsString())
                    && r.getAsJsonObject("row").get("ID").getAsInt() == id)
        .findFirst()
        .orElseThrow()
        .getAsJsonObject("row")
        .deepCopy();
  }

  @BeforeEach
  void setup() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/formula-learning-4.6.json")) {
      assertNotNull(in);
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    oldPool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    for (var value : fixture) {
      var r = value.getAsJsonObject();
      var raw = r.getAsJsonObject("row").deepCopy();
      if ("recipe".equals(r.get("kind").getAsString()))
        install(GameData.getItemComposeExcelMap(), gson.fromJson(raw, ItemComposeExcel.class));
      else {
        raw.addProperty("ItemName", raw.getAsJsonObject("ItemName").get("Hash").getAsLong());
        install(GameData.getItemExcelMap(), gson.fromJson(raw, ItemExcel.class));
      }
    }
  }

  @AfterEach
  void cleanup() {
    Collections.reverse(restorations);
    restorations.forEach(Runnable::run);
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
  }

  GameItem stock(int id, int count) {
    var item =
        new GameItem(GameData.getItemExcelMap().get(id), count) {
          public void save() {}
        };
    assertTrue(
        player.getInventory().addItem(item),
        "Original formula or material cannot be held in actual inventory");
    return player.getInventory().getMaterialByItemId(id);
  }

  @Test
  void originalRecipeBookCanRemainOwnedUntilItIsActuallyUsed() {
    var item = stock(404005, 2);
    assertNotNull(item);
    assertEquals(2, item.getCount());
    assertEquals(player.getUid(), item.getOwnerUid());
    assertSame(item, player.getInventory().getItemByUid(item.getInternalUid()));
    assertEquals(0, player.getGameplay().getDailyPoints());
  }

  int count(int id) {
    var item = player.getInventory().getMaterialByItemId(id);
    return item == null ? 0 : item.getCount();
  }

  List<GameItem> compose() {
    return new InventoryService(null).composeItem(player, 2102, 1, List.of());
  }

  ConsumableUse.Result learn(int count, int avatar) {
    return new ConsumableUse(player).use(404005, count, avatar);
  }

  @Test
  void actualOriginalBookUseUnlocksTheRecipeWithoutAnInventedItemUseConfig() {
    stock(408001, 1);
    stock(180002, 1);
    stock(404005, 1);
    assertNull(compose());
    assertEquals(1, count(408001));
    assertEquals(1, count(180002));
    assertNull(GameData.getItemUseExcelMap().get(404005));
    var result = learn(1, 0);
    assertEquals(Retcode.SUCC, result.retcode());
    assertEquals(1, result.consumed());
    assertTrue(result.items().isEmpty());
    assertEquals(0, count(404005));
    assertEquals("COMPLETE", player.getGameplay().getLastItemUse().getPhase());
    var crafted = compose();
    assertNotNull(crafted);
    assertEquals(400005, crafted.getFirst().getItemId());
    assertEquals(1, count(400005));
    assertEquals(0, count(408001));
    assertEquals(0, count(180002));
    assertEquals(0, player.getGameplay().getDailyPoints());
  }

  @Test
  void learningTheSameOriginalFormulaAgainDoesNotConsumeTheRemainingBook() {
    stock(404005, 2);
    assertEquals(Retcode.SUCC, learn(1, 0).retcode());
    assertEquals(1, count(404005));
    assertNotEquals(Retcode.SUCC, learn(1, 0).retcode());
    assertEquals(1, count(404005));
    assertEquals(1, player.getGameplay().getLastItemUse().getSequence());
  }

  @ParameterizedTest
  @ValueSource(strings = {"zero", "multiple", "target", "owner", "locked"})
  void invalidBookUseCannotLearnOrConsume(String kind) {
    var book = stock(404005, 2);
    int amount = 1, avatar = 0;
    switch (kind) {
      case "zero" -> amount = 0;
      case "multiple" -> amount = 2;
      case "target" -> avatar = 1202;
      case "owner" ->
          book.setOwner(
              new Player() {
                public int getUid() {
                  return 99;
                }
              });
      case "locked" -> book.setLocked(true);
    }
    assertNotEquals(Retcode.SUCC, learn(amount, avatar).retcode());
    assertEquals(2, count(404005));
    assertNull(player.getGameplay().getLastItemUse());
    stock(408001, 1);
    stock(180002, 1);
    assertNull(compose());
    assertEquals(1, count(408001));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknownType",
        "unknownFunction",
        "changedBook",
        "missingOutput",
        "duplicateMapping"
      })
  void unknownOrAmbiguousRecipeMappingCannotConsumeTheBook(String kind) {
    stock(404005, 1);
    var raw = row("recipe", 2102);
    switch (kind) {
      case "unknownType" -> raw.addProperty("FormulaType", "Unidentified46");
      case "unknownFunction" -> raw.addProperty("FuncType", "Unidentified46");
      case "changedBook" -> raw.addProperty("FormulaRequire", 404006);
      case "missingOutput" -> raw.addProperty("ItemID", Integer.MAX_VALUE);
      case "duplicateMapping" -> {
        var copy = raw.deepCopy();
        copy.addProperty("ID", 19999821);
        install(GameData.getItemComposeExcelMap(), gson.fromJson(copy, ItemComposeExcel.class));
      }
    }
    GameData.getItemComposeExcelMap().add(gson.fromJson(raw, ItemComposeExcel.class));
    assertNotEquals(Retcode.SUCC, learn(1, 0).retcode());
    assertEquals(1, count(404005));
    assertNull(player.getGameplay().getLastItemUse());
  }

  @Test
  void concurrentBookUseConsumesOnlyOneAndCreatesOneLearningReceipt() throws Exception {
    stock(404005, 2);
    var gate = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var a =
          workers.submit(
              () -> {
                gate.await();
                return learn(1, 0);
              });
      var b =
          workers.submit(
              () -> {
                gate.await();
                return learn(1, 0);
              });
      gate.countDown();
      var first = a.get(10, TimeUnit.SECONDS);
      var second = b.get(10, TimeUnit.SECONDS);
      assertEquals(
          1,
          (first.retcode() == Retcode.SUCC ? 1 : 0) + (second.retcode() == Retcode.SUCC ? 1 : 0));
      assertEquals(1, count(404005));
      assertEquals(1, player.getGameplay().getLastItemUse().getSequence());
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void interruptedActualDebitKeepsLearningPendingAndCannotUnlockCrafting() {
    stock(404005, 1);
    stock(408001, 1);
    stock(180002, 1);
    interruptDebit = true;
    assertThrows(IllegalStateException.class, () -> learn(1, 0));
    interruptDebit = false;
    assertEquals(0, count(404005));
    assertEquals("DEBITED", player.getGameplay().getLastItemUse().getPhase());
    assertNull(compose());
    assertEquals(1, count(408001));
    stock(404005, 1);
    assertEquals(Retcode.SYSTEM_BUSY, learn(1, 0).retcode());
    assertEquals(1, count(404005));
  }

  class NativeSession extends GameSession {
    DynamicMessage response;

    NativeSession() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    void use(int amount) throws Exception {
      var type = getProtocol().clientSchema().message("UseItemCsReq");
      var b =
          DynamicMessage.newBuilder(type)
              .setField(type.findFieldByName("use_item_id"), 404005)
              .setField(type.findFieldByName("use_item_count"), amount);
      assertTrue(VersionedPacketHandler.handle(this, 558, b.build().toByteArray()));
    }
  }

  @Test
  void native556CarriesTheActuallyLearnedBookInField15() throws Exception {
    stock(404005, 1);
    var session = new NativeSession();
    session.use(1);
    assertEquals(
        0,
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(
        404005,
        session.response.getField(session.response.getDescriptorForType().findFieldByNumber(15)));
    assertEquals(
        1,
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("use_item_count")));
    assertEquals(0, count(404005));
  }

  @Test
  void actualBagPacketRestoresOnlyKnownSavedFormulasInNativeField13() throws Exception {
    stock(404005, 1);
    assertEquals(Retcode.SUCC, learn(1, 0).retcode());
    var session = new NativeSession();
    var bridge = session.getProtocol();
    var frame =
        Unpooled.wrappedBuffer(
            bridge.outboundFrames(new PacketGetBagScRsp(session).build(), player));
    try {
      var packet = PacketCodec.decodeNext(frame);
      assertNotNull(packet);
      assertEquals(525, packet.cmdId());
      var bag = bridge.clientSchema().parse("GetBagScRsp", packet.body());
      assertEquals(List.of(404005), bag.getField(bag.getDescriptorForType().findFieldByNumber(13)));
      assertEquals(List.of(), bag.getField(bag.getDescriptorForType().findFieldByNumber(15)));
      assertEquals(0, count(404005));
    } finally {
      frame.release();
    }
  }

  @Test
  void rejectedNativeBookUseCannotPublishAFormulaId() throws Exception {
    stock(404005, 2);
    var session = new NativeSession();
    session.use(2);
    assertNotEquals(
        0,
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(
        0,
        session.response.getField(session.response.getDescriptorForType().findFieldByNumber(15)));
    assertEquals(
        0,
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("use_item_count")));
    assertEquals(2, count(404005));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualMorphiaReloadRetainsConfirmedLearningOrPendingDebit(boolean pending) throws Exception {
    stock(404005, 1);
    if (pending) {
      interruptDebit = true;
      assertThrows(IllegalStateException.class, () -> learn(1, 0));
      interruptDebit = false;
    } else assertEquals(Retcode.SUCC, learn(1, 0).retcode());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "formula-learning");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var persisted = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(persisted, 123460027);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(persisted, player.getGameplay());
      store.save(persisted);
      var loaded = store.find(Player.class).first();
      assertNotNull(loaded);
      assertEquals(pending ? Set.of() : Set.of(404005), loaded.getGameplay().getLearnedFormulas());
      assertEquals(
          pending ? "DEBITED" : "COMPLETE", loaded.getGameplay().getLastItemUse().getPhase());
      if (pending)
        assertEquals(Retcode.SYSTEM_BUSY, new ConsumableUse(loaded).use(404005, 1, 0).retcode());
      else
        assertTrue(FormulaLearning.unlocked(loaded, GameData.getItemComposeExcelMap().get(2102)));
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void oldOrNullLearningStateDoesNotUnlockRecipesImplicitly() {
    for (String value : List.of("{}", "{\"learnedFormulas\":null}")) {
      var state = gson.fromJson(value, GameplayState.class);
      assertTrue(state.getLearnedFormulas().isEmpty());
      assertTrue(state.learnFormula(404005));
      assertFalse(state.learnFormula(404005));
      assertFalse(state.learnFormula(0));
    }
  }

  @Test
  void returnedLearningSetCannotBeUsedToBypassActualBookConsumption() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> player.getGameplay().getLearnedFormulas().add(404005));
    stock(408001, 1);
    stock(180002, 1);
    assertNull(compose());
  }

  @Test
  void savedLearningDoesNotPermitAnUnknownHotChangedRecipe() {
    stock(404005, 1);
    assertEquals(Retcode.SUCC, learn(1, 0).retcode());
    var raw = row("recipe", 2102);
    raw.addProperty("FuncType", "Unidentified46");
    GameData.getItemComposeExcelMap().add(gson.fromJson(raw, ItemComposeExcel.class));
    stock(408001, 1);
    stock(180002, 1);
    assertNull(compose());
    assertEquals(1, count(408001));
  }

  @Test
  void deniedRealCompositionCannotBeSerializedAsNativeSuccess() throws Exception {
    stock(408001, 1);
    stock(180002, 1);
    assertNull(compose());
    var legacy = new emu.lunarcore.server.packet.send.PacketComposeItemScRsp(2102, 1, compose());
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var packet = bridge.outbound(legacy.getCmdId(), legacy.getData().toByteArray());
    assertEquals(508, packet.cmdId());
    var response = bridge.clientSchema().parse("ComposeItemScRsp", packet.body());
    assertNotEquals(
        0, response.getField(response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(1, count(408001));
    assertEquals(1, count(180002));
    assertEquals(0, count(400005));
  }

  @Test
  void deniedSelectedRelicResponseCannotBeSerializedAsNativeSuccess() throws Exception {
    var legacy = new emu.lunarcore.server.packet.send.PacketComposeSelectedRelicScRsp(0, null);
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var packet = bridge.outbound(legacy.getCmdId(), legacy.getData().toByteArray());
    assertEquals(585, packet.cmdId());
    var response = bridge.clientSchema().parse("ComposeSelectedRelicScRsp", packet.body());
    assertNotEquals(
        0, response.getField(response.getDescriptorForType().findFieldByName("retcode")));
  }

  @Test
  void emptyNativeMailSnapshotRequestReachesTheExistingFieldlessHandler() {
    var bridge = ProtocolBridge.forVersion("4.6.0");
    int command = bridge.clientCommand("GetMailCsReq");
    var accepted = assertDoesNotThrow(() -> bridge.inbound(command, new byte[0]));
    assertEquals(CmdId.GetMailCsReq, accepted.cmdId());
    assertArrayEquals(new byte[0], accepted.body());
    var type = bridge.clientSchema().message("GetMailCsReq");
    var paging = DynamicMessage.newBuilder(type).setField(type.findFieldByName("start"), 1).build();
    assertThrows(ProtocolException.class, () -> bridge.inbound(command, paging.toByteArray()));
  }
}
