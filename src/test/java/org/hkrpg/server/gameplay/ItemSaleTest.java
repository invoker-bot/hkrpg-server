package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.inventory.tabs.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.types.ObjectId;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ItemSaleTest {
  final Gson gson = ResourceLoader.getGson();
  JsonArray fixture;
  final List<Runnable> restorations = new ArrayList<>();
  List<GameplayDefinitions.DailyPool> oldPool;
  int deliveryLimit = Integer.MAX_VALUE;
  String interruptPhase;
  final List<BasePacket> packets = new ArrayList<>();
  GameSession transport;
  final Player player =
      new Player() {
        final Inventory actual =
            new Inventory(this) {
              public List<GameItem> addItems(List<GameItem> values, boolean hint) {
                var delivered = new ArrayList<GameItem>();
                for (var v : values.subList(0, Math.min(deliveryLimit, values.size())))
                  delivered.add(
                      new GameItem(v.getExcel(), v.getCount()) {
                        public void save() {}
                      });
                return super.addItems(delivered, hint);
              }
            };

        public Inventory getInventory() {
          return actual;
        }

        public void save() {
          var attempt = getGameplay().getItemSale().getLastAttempt();
          if (attempt != null && Objects.equals(interruptPhase, attempt.getPhase()))
            throw new IllegalStateException("Isolated decomposition save interruption");
        }

        public void sendPacket(BasePacket packet) {
          packets.add(packet);
          if (transport != null) transport.send(packet);
        }
      };
  final InventoryService service = new InventoryService(null);

  <T extends GameResource> void install(ExcelMap<T> map, T row) {
    var old = map.get(row.getId());
    restorations.add(
        () -> {
          map.keySet().remove(row.getId());
          if (old != null) map.add(old);
        });
    map.add(row);
  }

  JsonObject row(String kind) {
    return fixture.asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(r -> kind.equals(r.get("kind").getAsString()))
        .findFirst()
        .orElseThrow()
        .getAsJsonObject("row")
        .deepCopy();
  }

  @BeforeEach
  void setup() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/item-sale-4.6.json")) {
      assertNotNull(in);
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    oldPool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    for (var value : fixture) {
      var r = value.getAsJsonObject();
      var raw = r.getAsJsonObject("row");
      switch (r.get("kind").getAsString()) {
        case "quest" -> install(GameData.getQuestExcelMap(), gson.fromJson(raw, QuestExcel.class));
        case "rule" ->
            install(GameData.getFinishWayExcelMap(), gson.fromJson(raw, FinishWayExcel.class));
        case "reward" -> {
          var reward = gson.fromJson(raw, RewardExcel.class);
          reward.onLoad();
          install(GameData.getRewardExcelMap(), reward);
        }
        case "item" -> install(GameData.getItemExcelMap(), gson.fromJson(raw, ItemExcel.class));
        case "relic" -> {
          var relic = gson.fromJson(raw, RelicExcel.class);
          install(GameData.getRelicExcelMap(), relic);
          relic.onLoad();
        }
        case "equipment" -> {
          var equip = gson.fromJson(raw, EquipmentExcel.class);
          install(GameData.getEquipExcelMap(), equip);
          equip.onLoad();
        }
      }
    }
    var gate = new QuestState(4010125);
    gate.advance(1, 1, 1);
    player.getGameplay().getMainMissions().put(4010125, gate);
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
  }

  @AfterEach
  void cleanup() {
    Collections.reverse(restorations);
    restorations.forEach(Runnable::run);
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
  }

  GameItem item(int id, int count) {
    var value =
        new GameItem(GameData.getItemExcelMap().get(id), count) {
          public void save() {}
        };
    value.setOwner(player);
    player.getInventory().getItems().put(value.getInternalUid(), value);
    player.getInventory().getTabByItemType(value.getItemMainType()).onAddItem(value);
    return value;
  }

  ItemParam cost(GameItem value) {
    return new ItemParam(ItemParamType.UNIQUE, value.getInternalUid(), 1);
  }

  List<GameItem> sell(boolean materials, ItemParam... costs) {
    return service.sellItems(player, materials, Arrays.asList(costs));
  }

  int stock(int id) {
    var value = player.getInventory().getMaterialByItemId(id);
    return value == null ? 0 : value.getCount();
  }

  void intact(GameItem value) {
    assertSame(value, player.getInventory().getItemByUid(value.getInternalUid()));
    assertEquals(1, value.getCount());
  }

  QuestState select() {
    GameplayDefinitions.dailyPool.add(
        gson.fromJson(row("pool"), GameplayDefinitions.DailyPool.class));
    player.getDailyManager().ensureDay();
    return player.getGameplay().getDailyQuests().get(2100109);
  }

  void field(GameItem value, String name, Object data) throws Exception {
    var f = GameItem.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(value, data);
  }

  @ParameterizedTest
  @ValueSource(ints = {31011, 41011, 51011, 61011, 20000, 21000})
  void baseLevelSourceItemsReturnExactlyTheListedMaterials(int id) {
    var value = item(id, 1);
    var result = sell(true, cost(value));
    assertNotNull(result);
    assertNull(player.getInventory().getItemByUid(value.getInternalUid()));
    for (var r : value.getExcel().getReturnItemIDList())
      assertEquals(r.getCount(), stock(r.getId()));
    assertEquals(0, stock(235));
  }

  @Test
  void fiveStarRelicCanReturnTheOriginalTenRemains() {
    var value = item(61011, 1);
    assertNotNull(sell(false, cost(value)));
    assertEquals(10, stock(235));
    assertEquals(0, stock(232));
  }

  @Test
  void duplicateUniqueIdCannotManufactureExtraRewards() {
    var value = item(31011, 1);
    assertNull(sell(true, cost(value), cost(value)));
    intact(value);
    assertEquals(0, stock(231));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 2, Integer.MAX_VALUE})
  void uniqueItemQuantityMustBeExactlyOne(int count) {
    var value = item(31011, 1);
    assertNull(sell(true, new ItemParam(ItemParamType.UNIQUE, value.getInternalUid(), count)));
    intact(value);
    assertEquals(0, stock(231));
  }

  @Test
  void equippedRelicCannotBeDeleted() throws Exception {
    var value = item(31011, 1);
    field(value, "equipAvatarId", new ObjectId());
    assertNull(sell(true, cost(value)));
    intact(value);
    assertEquals(0, stock(231));
  }

  @Test
  void wrongOwnerCannotReceiveARewardWithoutARealDebit() throws Exception {
    var value = item(31011, 1);
    field(value, "ownerUid", 1);
    assertNull(sell(true, cost(value)));
    intact(value);
    assertEquals(0, stock(231));
  }

  @Test
  void sourceCannotBeSoldThroughAPileId() {
    var value = item(31011, 1);
    assertNull(sell(true, new ItemParam(31011, 1)));
    intact(value);
  }

  @Test
  void originalUnsellableFiveStarLightConeNeverBecomesRelicRemains() {
    var value = item(23000, 1);
    assertNull(sell(false, cost(value)));
    intact(value);
    assertEquals(0, stock(235));
  }

  @Test
  void aDetachedItemDefinitionIsRejected() {
    var value = item(31011, 1);
    value.setExcel(gson.fromJson(gson.toJsonTree(value.getExcel()), ItemExcel.class));
    assertNull(sell(true, cost(value)));
    intact(value);
    assertEquals(0, stock(231));
  }

  @Test
  void mixedBatchIsFullyCheckedBeforeDeletingItsFirstItem() {
    var value = item(31011, 1);
    var second = item(41011, 1);
    second.setLocked(true);
    assertNull(sell(true, cost(value), cost(second)));
    intact(value);
    intact(second);
    assertEquals(0, stock(231));
    assertEquals(0, stock(232));
  }

  @Test
  void fullReturnPileCannotDestroyTheInput() {
    var value = item(31011, 1);
    var full = item(231, GameData.getItemExcelMap().get(231).getPileLimit());
    assertNull(sell(true, cost(value)));
    intact(value);
    assertEquals(full.getExcel().getPileLimit(), stock(231));
  }

  @Test
  void missingReturnDefinitionCannotDestroyTheInput() {
    var value = item(31011, 1);
    GameData.getItemExcelMap().keySet().remove(231);
    assertNull(sell(true, cost(value)));
    intact(value);
  }

  @Test
  void noInventorySpaceForANewReturnPileCannotDestroyTheInput() {
    var value = item(31011, 1);
    player.getInventory().createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(0));
    assertNull(sell(true, cost(value)));
    intact(value);
    assertEquals(0, stock(231));
  }

  @Test
  void emptyBatchIsRejected() {
    assertNull(sell(true));
  }

  @Test
  void retryAfterActualRemovalCannotCreateAnotherReturn() {
    var value = item(31011, 1);
    assertNotNull(sell(true, cost(value)));
    assertNull(sell(true, cost(value)));
    assertEquals(3, stock(231));
  }

  @Test
  void originalDailyIsSelectedOnlyAfterItsSourcePrerequisite() {
    assertNotNull(select());
    assertEquals(0, player.getGameplay().getDailyQuests().get(2100109).getProgress());
  }

  @Test
  void lockedSourcePrerequisiteKeepsTheDailyUnavailable() {
    player.getGameplay().getMainMissions().clear();
    assertNull(select());
  }

  @Test
  void actualRelicDeletionAndFullDeliveryCompleteTheDailyAndAwardOneHundredPointsOnce() {
    var state = select();
    assertNotNull(state);
    var value = item(31011, 1);
    assertNotNull(sell(true, cost(value)));
    assertEquals(1, state.getProgress());
    assertEquals(QuestState.Phase.FINISHED, state.getPhase());
    assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(2100109).retcode());
    assertEquals(100, player.getGameplay().getDailyPoints());
    assertNotEquals(Retcode.SUCC, player.getDailyManager().claimQuest(2100109).retcode());
    assertEquals(100, player.getGameplay().getDailyPoints());
    assertEquals(0, stock(23));
  }

  @Test
  void lightConeDecompositionCannotCompleteRelicDaily() {
    var state = select();
    assertNotNull(state);
    var value = item(20000, 1);
    assertNotNull(sell(true, cost(value)));
    assertEquals(0, state.getProgress());
  }

  @Test
  void incompleteActualReturnDeliveryCannotCompleteRelicDaily() {
    var state = select();
    assertNotNull(state);
    var value = item(31011, 1);
    deliveryLimit = 0;
    assertNull(sell(true, cost(value)));
    assertEquals(0, state.getProgress());
    assertEquals(0, stock(231));
  }

  @Test
  void twoConcurrentRequestsCannotDoubleDeleteOrDoubleGrant() throws Exception {
    var value = item(31011, 1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> sell(true, cost(value)));
      var b = pool.submit(() -> sell(true, cost(value)));
      assertEquals(1, (a.get() != null ? 1 : 0) + (b.get() != null ? 1 : 0));
      assertEquals(3, stock(231));
    }
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

    public void send(BasePacket packet) {}

    public void sendClientPacket(String name, byte[] bytes) {
      assertEquals("SellItemScRsp", name);
      try {
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    void sell(byte[] body) throws Exception {
      assertTrue(VersionedPacketHandler.handle(this, 511, body));
    }

    int code() {
      return (Integer)
          response.getField(response.getDescriptorForType().findFieldByName("retcode"));
    }

    Map<Integer, Integer> returned() {
      var list =
          (DynamicMessage)
              response.getField(
                  response.getDescriptorForType().findFieldByName("return_item_list"));
      var result = new TreeMap<Integer, Integer>();
      for (var value :
          (List<?>) list.getField(list.getDescriptorForType().findFieldByName("item_list"))) {
        var item = (DynamicMessage) value;
        result.put(
            (Integer) item.getField(item.getDescriptorForType().findFieldByName("item_id")),
            (Integer) item.getField(item.getDescriptorForType().findFieldByName("num")));
      }
      return result;
    }
  }

  interface Encoder {
    void encode(CodedOutputStream out) throws IOException;
  }

  byte[] wire(Encoder encoder) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    encoder.encode(out);
    out.flush();
    return bytes.toByteArray();
  }

  byte[] nativeCost(int type, int uid) throws IOException {
    return wire(out -> out.writeUInt32(type, uid));
  }

  byte[] nativeRequest(byte[] entry, boolean materials) throws IOException {
    return wire(
        out -> {
          out.writeByteArray(2, wire(cost -> cost.writeByteArray(2, entry)));
          out.writeBool(12, materials);
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeBooleanTwelveActuallySelectsTheFiveStarRelicReturn(boolean materials)
      throws Exception {
    var value = item(61011, 1);
    var session = new NativeSession();
    session.sell(nativeRequest(nativeCost(4, value.getInternalUid()), materials));
    assertEquals(0, session.code());
    assertEquals(materials ? Map.of(232, 3) : Map.of(235, 10), session.returned());
    assertNull(player.getInventory().getItemByUid(value.getInternalUid()));
  }

  @Test
  void nativeEquipmentOneofIsActuallyDebitedAndReturnsLightConeMaterial() throws Exception {
    var value = item(20000, 1);
    var session = new NativeSession();
    session.sell(nativeRequest(nativeCost(2, value.getInternalUid()), true));
    assertEquals(0, session.code());
    assertEquals(Map.of(221, 1), session.returned());
    assertNull(player.getInventory().getItemByUid(value.getInternalUid()));
  }

  @Test
  void nativeFiveHundredLightConesSendOneDeletionUpdateAndReturnAllMaterials() throws Exception {
    var inputs = new ArrayList<GameItem>();
    for (int i = 0; i < 500; i++) inputs.add(item(20000, 1));
    var body = wire(out -> {
      out.writeByteArray(2, wire(cost -> {
        for (var value : inputs) cost.writeByteArray(2, nativeCost(2, value.getInternalUid()));
      }));
      out.writeBool(12, true);
    });
    var session = new NativeSession();
    session.sell(body);
    assertEquals(0, session.code());
    assertEquals(Map.of(221, 500), session.returned());
    assertEquals(500, stock(221));
    assertTrue(inputs.stream().allMatch(value -> player.getInventory().getItemByUid(value.getInternalUid()) == null));
    var deletionUpdates = packets.stream()
        .map(BasePacket::getData)
        .filter(emu.lunarcore.proto.PlayerSyncScNotifyOuterClass.PlayerSyncScNotify.class::isInstance)
        .map(emu.lunarcore.proto.PlayerSyncScNotifyOuterClass.PlayerSyncScNotify.class::cast)
        .filter(update -> update.getDelEquipmentList().length() > 0).toList();
    assertEquals(1, deletionUpdates.size(), "One batch must not queue 500 individual sync packets");
    assertEquals(500, deletionUpdates.getFirst().getDelEquipmentList().length());
  }

  @Test
  void nativeInterruptedSaleAlwaysReturnsAnErrorWithoutDebitingTheItems() throws Exception {
    var value = item(20000, 1);
    interruptPhase = "RESERVED";
    var session = new NativeSession();
    session.sell(nativeRequest(nativeCost(2, value.getInternalUid()), true));
    assertNotEquals(0, session.code());
    intact(value);
    assertEquals(0, stock(221));
    assertEquals("RESERVED", player.getGameplay().getItemSale().getLastAttempt().getPhase());
  }

  @Test
  void failedMiddleDeletionPublishesOnlyConfirmedRemovalsAndKeepsThePlanPending() throws Exception {
    var first = item(20000, 1);
    var failed = new GameItem(GameData.getItemExcelMap().get(20000), 1) {
      public void save() { if (getCount() == 0) throw new IllegalStateException("Isolated delete failure"); }
    };
    failed.setOwner(player);
    player.getInventory().getItems().put(failed.getInternalUid(), failed);
    player.getInventory().getTabByItemType(failed.getItemMainType()).onAddItem(failed);
    var last = item(20000, 1);
    var body = wire(out -> out.writeByteArray(2, wire(cost -> {
      for (var value : List.of(first, failed, last))
        cost.writeByteArray(2, nativeCost(2, value.getInternalUid()));
    })));
    var session = new NativeSession();
    session.sell(body);
    assertNotEquals(0, session.code());
    assertEquals(0, stock(221));
    assertEquals("RESERVED", player.getGameplay().getItemSale().getLastAttempt().getPhase());
    intact(last);
    var deletionUpdates = packets.stream().map(BasePacket::getData)
        .filter(emu.lunarcore.proto.PlayerSyncScNotifyOuterClass.PlayerSyncScNotify.class::isInstance)
        .map(emu.lunarcore.proto.PlayerSyncScNotifyOuterClass.PlayerSyncScNotify.class::cast)
        .filter(update -> update.getDelEquipmentList().length() > 0).toList();
    assertEquals(1, deletionUpdates.size());
    assertEquals(1, deletionUpdates.getFirst().getDelEquipmentList().length());
    assertEquals(first.getInternalUid(), deletionUpdates.getFirst().getDelEquipmentList().get(0));
    session.sell(nativeRequest(nativeCost(2, last.getInternalUid()), true));
    assertNotEquals(0, session.code());
    intact(last);
  }

  class FramedSession extends GameSession {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    final List<emu.lunarcore.server.packet.PacketCodec.Frame> frames = new ArrayList<>();

    FramedSession() { super(null); setState(SessionState.ACTIVE); }
    public Player getPlayer() { return player; }
    public ProtocolBridge getProtocol() { return bridge; }
    protected void writeTransport(byte[] bytes) {
      var input = io.netty.buffer.Unpooled.wrappedBuffer(bytes);
      try {
        while (input.isReadable()) frames.add(emu.lunarcore.server.packet.PacketCodec.decodeNext(input));
      } finally { input.release(); }
    }
    void receive(byte[] body) throws Exception {
      var header = wire(out -> out.writeUInt32(1, 73451));
      var bytes = io.netty.buffer.Unpooled.wrappedBuffer(
          emu.lunarcore.server.packet.PacketCodec.encode(511, header, body));
      try { onMessage(bytes); } finally { bytes.release(); }
    }
  }

  @Test
  void fiveHundredDeletionsAndReplyKeepNativePacketCorrelation() throws Exception {
    var inputs = new ArrayList<GameItem>();
    for (int i = 0; i < 500; i++) inputs.add(item(20000, 1));
    var body = wire(out -> {
      out.writeByteArray(2, wire(cost -> {
        for (var value : inputs) cost.writeByteArray(2, nativeCost(2, value.getInternalUid()));
      }));
      out.writeBool(12, true);
    });
    var session = new FramedSession();
    transport = session;
    session.receive(body);
    var sync = session.frames.stream().filter(p -> p.cmdId() == session.bridge.clientCommand("PlayerSyncScNotify"))
        .map(p -> {
          try { return session.bridge.clientSchema().parse("PlayerSyncScNotify", p.body()); }
          catch (IOException invalid) { throw new AssertionError(invalid); }
        }).filter(m -> ((List<?>) m.getField(m.getDescriptorForType().findFieldByName("del_equipment_list"))).size() > 0)
        .toList();
    assertEquals(1, sync.size());
    assertEquals(500, ((List<?>) sync.getFirst().getField(sync.getFirst().getDescriptorForType()
        .findFieldByName("del_equipment_list"))).size());
    var replies = session.frames.stream().filter(p -> p.cmdId() == 517).toList();
    assertEquals(1, replies.size());
    assertArrayEquals(wire(out -> out.writeUInt32(1, 73451)), replies.getFirst().header());
    var reply = session.bridge.clientSchema().parse("SellItemScRsp", replies.getFirst().body());
    assertEquals(0, reply.getField(reply.getDescriptorForType().findFieldByName("retcode")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknownRequest",
        "repeatedCostData",
        "repeatedBoolean",
        "badBoolean",
        "unknownEntry",
        "repeatedOneof",
        "twoOneofs",
        "uintOverflow",
        "signedOverflow",
        "zeroUid",
        "emptyEntry",
        "pile",
        "blindPet",
        "wrongCostDataWire",
        "wrongUidWire",
        "duplicateUid",
        "relicAsEquipment",
        "emptyBatch"
      })
  void nativeMalformedOrWrongItemTypeCannotDebitOrReturnAnything(String kind) throws Exception {
    var value = item(31011, 1);
    int uid = value.getInternalUid();
    byte[] entry = nativeCost(4, uid);
    switch (kind) {
      case "unknownEntry" ->
          entry =
              wire(
                  out -> {
                    out.writeUInt32(4, uid);
                    out.writeUInt32(15, 1);
                  });
      case "repeatedOneof" ->
          entry =
              wire(
                  out -> {
                    out.writeUInt32(4, uid);
                    out.writeUInt32(4, uid);
                  });
      case "twoOneofs" ->
          entry =
              wire(
                  out -> {
                    out.writeUInt32(4, uid);
                    out.writeUInt32(2, uid);
                  });
      case "uintOverflow" -> entry = wire(out -> out.writeUInt64(4, 0x100000000L));
      case "signedOverflow" -> entry = wire(out -> out.writeUInt64(4, 0x80000000L));
      case "zeroUid" -> entry = nativeCost(4, 0);
      case "emptyEntry" -> entry = new byte[0];
      case "pile" -> entry = wire(out -> out.writeByteArray(10, new byte[0]));
      case "blindPet" -> entry = wire(out -> out.writeUInt64(1, uid));
      case "wrongUidWire" -> entry = wire(out -> out.writeByteArray(4, new byte[0]));
      case "relicAsEquipment" -> entry = nativeCost(2, uid);
    }
    byte[] costEntry = entry;
    byte[] body =
        wire(
            out -> {
              if (kind.equals("emptyBatch")) out.writeByteArray(2, new byte[0]);
              else if (kind.equals("wrongCostDataWire")) out.writeUInt32(2, 1);
              else
                out.writeByteArray(
                    2,
                    wire(
                        cost -> {
                          cost.writeByteArray(2, costEntry);
                          if (kind.equals("duplicateUid")) cost.writeByteArray(2, costEntry);
                        }));
              out.writeBool(12, true);
              switch (kind) {
                case "unknownRequest" -> out.writeUInt32(15, 1);
                case "repeatedCostData" -> out.writeByteArray(2, new byte[0]);
                case "repeatedBoolean" -> out.writeBool(12, false);
                case "badBoolean" -> out.writeUInt32(12, 2);
              }
            });
    var session = new NativeSession();
    session.sell(body);
    assertNotEquals(0, session.code());
    assertTrue(session.returned().isEmpty());
    intact(value);
    assertEquals(0, stock(231));
  }

  @ParameterizedTest
  @ValueSource(strings = {"RESERVED", "DEBITED"})
  void anInterruptedSaveFreezesTheActualPlanAndPreventsAnAutomaticReplay(String phase) {
    var value = item(31011, 1);
    interruptPhase = phase;
    assertThrows(IllegalStateException.class, () -> sell(true, cost(value)));
    interruptPhase = null;
    var attempt = player.getGameplay().getItemSale().getLastAttempt();
    assertEquals(phase, attempt.getPhase());
    assertEquals(Map.of(231, 3), attempt.getReturns());
    assertEquals(Map.of(231, 0), attempt.getBefore());
    assertEquals(31011, attempt.getInputs().getFirst().getItemId());
    if (phase.equals("RESERVED")) intact(value);
    else assertNull(player.getInventory().getItemByUid(value.getInternalUid()));
    assertNull(sell(true, cost(value)));
    assertEquals(0, stock(231));
    var restored = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    assertFalse(restored.getItemSale().available());
    assertEquals(phase, restored.getItemSale().getLastAttempt().getPhase());
  }

  @Test
  void partialReturnRecordsRealDeltaAndBlocksAnotherSale() {
    var first = item(31011, 1);
    var second = item(41011, 1);
    deliveryLimit = 1;
    assertNull(sell(true, cost(first), cost(second)));
    var attempt = player.getGameplay().getItemSale().getLastAttempt();
    assertEquals("PARTIAL", attempt.getPhase());
    assertEquals(Map.of(231, 3), attempt.getReceived());
    assertFalse(player.getGameplay().getItemSale().available());
    var third = item(51011, 1);
    assertNull(sell(true, cost(third)));
    intact(third);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "paramType",
        "progressZero",
        "progressTwo",
        "int1",
        "int2",
        "int3",
        "string",
        "list",
        "items",
        "backTrack"
      })
  void unknownRelicDailyRuleIsNeverSelected(String kind) {
    var rule = row("rule");
    switch (kind) {
      case "paramType" -> rule.addProperty("ParamType", "Equal");
      case "progressZero" -> rule.addProperty("Progress", 0);
      case "progressTwo" -> rule.addProperty("Progress", 2);
      case "int1" -> rule.addProperty("ParamInt1", 1);
      case "int2" -> rule.addProperty("ParamInt2", 1);
      case "int3" -> rule.addProperty("ParamInt3", 1);
      case "string" -> rule.addProperty("ParamStr1", "unknown");
      case "list" -> rule.getAsJsonArray("ParamIntList").add(1);
      case "items" -> {
        var entry = new JsonObject();
        entry.addProperty("ItemID", 231);
        entry.addProperty("ItemNum", 1);
        rule.getAsJsonArray("ParamItemList").add(entry);
      }
      case "backTrack" -> rule.addProperty("IsBackTrack", true);
    }
    GameData.getFinishWayExcelMap().add(gson.fromJson(rule, FinishWayExcel.class));
    assertNull(select());
  }

  @Test
  void aNewDailyDayCannotReuseAnOldUniqueIdToCompleteItsFreshObjective() {
    var first = select();
    assertNotNull(first);
    var value = item(31011, 1);
    assertNotNull(sell(true, cost(value)));
    assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(2100109).retcode());
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
    var next = player.getGameplay().getDailyQuests().get(2100109);
    assertNotNull(next);
    assertEquals(0, next.getProgress());
    assertEquals(0, player.getGameplay().getDailyPoints());
    assertNull(sell(true, cost(value)));
    assertEquals(0, next.getProgress());
    var fresh = item(41011, 1);
    assertNotNull(sell(true, cost(fresh)));
    assertEquals(1, next.getProgress());
  }

  @Test
  void moreThanTheOriginalFiveHundredUniqueItemsCannotBeDeleted() {
    var costs = new ArrayList<ItemParam>();
    var items = new ArrayList<GameItem>();
    for (int i = 0; i < 501; i++) {
      var value = item(31011, 1);
      items.add(value);
      costs.add(cost(value));
    }
    assertNull(service.sellItems(player, true, costs));
    items.forEach(this::intact);
    assertEquals(0, stock(231));
  }

  @ParameterizedTest
  @ValueSource(strings = {"RESERVED", "DEBITED", "COMPLETE", "PARTIAL"})
  void actualMorphiaReloadKeepsTheOriginalPlanAndRealReturnReceipt(String phase) throws Exception {
    var value = item(31011, 1);
    if (phase.equals("RESERVED") || phase.equals("DEBITED")) {
      interruptPhase = phase;
      assertThrows(IllegalStateException.class, () -> sell(true, cost(value)));
      interruptPhase = null;
    } else if (phase.equals("PARTIAL")) {
      deliveryLimit = 0;
      assertNull(sell(true, cost(value)));
    } else assertNotNull(sell(true, cost(value)));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "item-sale");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var persisted = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(persisted, 123460032);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(persisted, player.getGameplay());
      store.save(persisted);
      var loaded = store.find(Player.class).first();
      assertNotNull(loaded);
      var actual = loaded.getGameplay().getItemSale();
      var attempt = actual.getLastAttempt();
      assertEquals(phase, attempt.getPhase());
      assertEquals(phase.equals("COMPLETE"), actual.available());
      assertEquals(Map.of(231, 3), attempt.getReturns());
      assertEquals(Map.of(231, 0), attempt.getBefore());
      assertEquals(phase.equals("COMPLETE") ? Map.of(231, 3) : Map.of(), attempt.getReceived());
      assertEquals(31011, attempt.getInputs().getFirst().getItemId());
      assertEquals(value.getInternalUid(), attempt.getInputs().getFirst().getUniqueId());
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {31011, 20000})
  void missingActualEquipmentOrRelicBindingCannotManufactureAReturn(int id) {
    var value = item(id, 1);
    if (id == 31011) {
      GameData.getRelicExcelMap().keySet().remove(id);
      value.getExcel().setRelicExcel(null);
    } else {
      GameData.getEquipExcelMap().keySet().remove(id);
      value.getExcel().setEquipmentExcel(null);
    }
    assertNull(sell(true, cost(value)));
    intact(value);
    assertEquals(0, stock(id == 31011 ? 231 : 221));
  }
}
