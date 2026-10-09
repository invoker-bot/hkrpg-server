package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.*;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.send.PacketGetAvatarDataScRsp;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;

class PlayerOutfit46Test {
  final Map<Integer, ItemExcel> oldItems = new HashMap<>();
  final Map<Integer, PlayerOutfitBaseExcel> oldOutfits = new HashMap<>();

  @BeforeEach
  void setup() {
    row(1000, 0, "HeadDecor");
    row(1005, 227005, "HeadDecor");
    row(1006, 227006, "HeadDecor");
    row(3001, 229001, "Coat");
    row(4001, 227015, "BackDecor");
    row(9999, 229999, "HeadDecor", "Coat");
  }

  void row(int id, int item, String... slots) {
    oldOutfits.put(id, GameData.getPlayerOutfitBaseExcelMap().get(id));
    var data = Map.of("OutfitID", id, "ItemID", item, "SlotTypeList", slots);
    GameData.getPlayerOutfitBaseExcelMap()
        .add(new Gson().fromJson(new Gson().toJson(data), PlayerOutfitBaseExcel.class));
    if (item == 0) return;
    oldItems.put(item, GameData.getItemExcelMap().get(item));
    GameData.getItemExcelMap()
        .add(
            new Gson()
                .fromJson(
                    "{\"ID\":"
                        + item
                        + ",\"ItemMainType\":\"Usable\",\"ItemSubType\":\"PlayerOutfit\",\"PileLimit\":1}",
                    ItemExcel.class));
  }

  @AfterEach
  void cleanup() {
    oldItems.forEach(
        (id, row) -> {
          if (row == null) GameData.getItemExcelMap().keySet().remove((int) id);
          else GameData.getItemExcelMap().add(row);
        });
    oldOutfits.forEach(
        (id, row) -> {
          if (row == null) GameData.getPlayerOutfitBaseExcelMap().keySet().remove((int) id);
          else GameData.getPlayerOutfitBaseExcelMap().add(row);
        });
  }

  static class Session extends GameSession {
    int saves;
    boolean interrupt;
    final List<DynamicMessage> syncs = new ArrayList<>();
    final List<Integer> savedCountsAtSync = new ArrayList<>();
    DynamicMessage response;
    final Player owner =
        new Player() {
          public void save() {
            saves++;
            if (interrupt) throw new IllegalStateException("write interrupted");
          }

          public void sendPacket(BasePacket packet) {}
        };

    Session() throws Exception {
      super(null);
      setState(SessionState.ACTIVE);
      var f = Player.class.getDeclaredField("unlocks");
      f.setAccessible(true);
      f.set(owner, new PlayerUnlockData());
    }

    public Player getPlayer() {
      return owner;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    public void send(BasePacket p) {}

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        var m = getProtocol().clientSchema().parse(name, bytes);
        if (name.equals("PlayerSyncScNotify")) {
          syncs.add(m);
          savedCountsAtSync.add(saves);
        } else response = m;
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }

    void stock(int item) {
      assertTrue(
          owner
              .getInventory()
              .addItem(
                  new GameItem(GameData.getItemExcelMap().get(item), 1) {
                    public void save() {}
                  }));
    }
  }

  byte[] request(Session s, List<Integer> ids) throws Exception {
    var child = Gameplay46.builder(s, "CJLCPMDGIBO");
    ids.forEach(id -> Gameplay46.add(child, "EAKOLIJOEPA", id));
    var req = Gameplay46.builder(s, "SetPlayerOutfitCsReq");
    Gameplay46.set(req, "DHKFCDAGHDM", child.build());
    return req.build().toByteArray();
  }

  int invoke(Session s, List<Integer> ids) throws Exception {
    return raw(s, request(s, ids));
  }

  int raw(Session s, byte[] bytes) throws Exception {
    assertTrue(
        VersionedPacketHandler.handle(
            s, s.getProtocol().clientCommand("SetPlayerOutfitCsReq"), bytes),
        "The actual outfit request must be dispatched");
    assertNotNull(s.response);
    return Gameplay46.number(s.response, "PLDKENJLKME");
  }

  List<?> sync(Session s) {
    return (List<?>)
        Gameplay46.value(
            (DynamicMessage) Gameplay46.value(s.syncs.getLast(), "DHKFCDAGHDM"), "EAKOLIJOEPA");
  }

  List<?> query(Session s) throws Exception {
    var core = new PacketGetAvatarDataScRsp(s.owner);
    var msg =
        s.getProtocol()
            .clientSchema()
            .parse(
                "GetAvatarDataScRsp",
                s.getProtocol()
                    .translate("GetAvatarDataScRsp", core.getData().toByteArray(), true));
    return (List<?>)
        Gameplay46.value((DynamicMessage) Gameplay46.value(msg, "DHKFCDAGHDM"), "EAKOLIJOEPA");
  }

  @Test
  void earnedInventoryDecorationCanBeEquippedAndQueriesReflectItAfterSave() throws Exception {
    var s = new Session();
    s.stock(227005);
    assertEquals(0, invoke(s, List.of(1005)));
    assertEquals(List.of(1005), sync(s));
    assertEquals(List.of(1005), query(s));
    assertTrue(s.owner.getInventory().hasItem(227005, 1));
    assertEquals(List.of(1), s.savedCountsAtSync);
    assertFalse(s.owner.getUnlocks().getPlayerOutfits().contains(1005));
  }

  @Test
  void unownedOrUnknownOrDefaultDecorationCannotManufactureAnEquippedState() throws Exception {
    var s = new Session();
    for (var ids : List.of(List.of(1005), List.of(1999), List.of(1000), List.of(-1))) {
      assertNotEquals(0, invoke(s, ids));
      assertEquals(0, s.saves);
      assertTrue(s.syncs.isEmpty());
      assertTrue(query(s).isEmpty());
    }
  }

  @Test
  void replacementPreservesNonConflictingSlotsAndEmptyListClears() throws Exception {
    var s = new Session();
    for (int i : List.of(227005, 227006, 229001, 227015)) s.stock(i);
    assertEquals(0, invoke(s, List.of(1005, 3001, 4001)));
    assertEquals(0, invoke(s, List.of(1006, 3001, 4001)));
    assertEquals(List.of(1006, 3001, 4001), query(s));
    assertEquals(0, invoke(s, List.of()));
    assertTrue(query(s).isEmpty());
    assertTrue(sync(s).isEmpty());
    assertEquals(3, s.saves);
  }

  @Test
  void conflictingSlotsOrDuplicateIdsRejectTheWholeReplacement() throws Exception {
    var s = new Session();
    for (int i : List.of(227005, 227006, 229001, 229999)) s.stock(i);
    assertEquals(0, invoke(s, List.of(1005)));
    int saves = s.saves;
    for (var ids :
        List.of(
            List.of(1005, 1006),
            List.of(1005, 1005),
            List.of(1005, 9999),
            List.of(3001, 9999),
            List.of(1005, 4001))) {
      assertNotEquals(0, invoke(s, ids));
      assertEquals(List.of(1005), query(s));
      assertEquals(saves, s.saves);
    }
  }

  @Test
  void identicalReplacementAndRepeatedClearAreIdempotent() throws Exception {
    var s = new Session();
    s.stock(227005);
    assertEquals(0, invoke(s, List.of()));
    assertEquals(0, s.saves);
    assertEquals(0, invoke(s, List.of(1005)));
    assertEquals(0, invoke(s, List.of(1005)));
    assertEquals(1, s.saves);
    assertEquals(0, invoke(s, List.of()));
    assertEquals(0, invoke(s, List.of()));
    assertEquals(2, s.saves);
  }

  @Test
  void inactiveSessionNeverChangesOutfits() throws Exception {
    var s = new Session();
    s.stock(227005);
    s.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(s, 330, request(s, List.of(1005))));
    assertEquals(0, s.saves);
    assertNull(s.response);
  }

  @Test
  void malformedNestedFieldsCannotTruncateOrPartiallyEquip() throws Exception {
    var s = new Session();
    s.stock(227005);
    var good = request(s, List.of(1005));
    var doubled = new byte[good.length * 2];
    System.arraycopy(good, 0, doubled, 0, good.length);
    System.arraycopy(good, 0, doubled, good.length, good.length);
    for (byte[] b :
        List.of(
            new byte[0],
            new byte[] {8, 1},
            new byte[] {10, 2, 16, 1},
            new byte[] {10, 1, 10},
            doubled,
            new byte[4097])) {
      assertNotEquals(0, raw(s, b));
      assertEquals(0, s.saves);
      assertTrue(query(s).isEmpty());
    }
    var child = new ByteArrayOutputStream();
    var c = CodedOutputStream.newInstance(child);
    c.writeUInt64(1, 0x1000003edL);
    c.flush();
    var outer = new ByteArrayOutputStream();
    var o = CodedOutputStream.newInstance(outer);
    o.writeByteArray(1, child.toByteArray());
    o.flush();
    assertNotEquals(0, raw(s, outer.toByteArray()));
    assertEquals(0, s.saves);
  }

  @Test
  void packedAndUnpackedListsUseTheSameOwnedIds() throws Exception {
    var s = new Session();
    s.stock(227005);
    s.stock(229001);
    assertEquals(0, invoke(s, List.of(1005, 3001)));
    var child = new ByteArrayOutputStream();
    var c = CodedOutputStream.newInstance(child);
    c.writeUInt32(1, 1005);
    c.writeUInt32(1, 3001);
    c.flush();
    var outer = new ByteArrayOutputStream();
    var o = CodedOutputStream.newInstance(outer);
    o.writeByteArray(1, child.toByteArray());
    o.flush();
    assertEquals(0, raw(s, outer.toByteArray()));
    assertEquals(1, s.saves);
    assertEquals(List.of(1005, 3001), query(s));
  }

  @Test
  void failedSaveCannotPublishOutfitSyncOrSuccess() throws Exception {
    var s = new Session();
    s.stock(227005);
    s.interrupt = true;
    assertThrows(IllegalStateException.class, () -> invoke(s, List.of(1005)));
    assertTrue(s.syncs.isEmpty());
    assertNull(s.response);
    s.interrupt = false;
    assertTrue(query(s).isEmpty());
    assertEquals(0, invoke(s, List.of(1005)));
    assertEquals(List.of(1005), query(s));
  }

  @Test
  void sourceConfigurationMustNameValidDisjointSlotsAndARealOutfitItem() throws Exception {
    var s = new Session();
    s.stock(227005);
    var row = GameData.getPlayerOutfitBaseExcelMap().get(1005);
    for (String body :
        List.of(
            "{\"OutfitID\":1005,\"ItemID\":227005,\"SlotTypeList\":[\"Unknown\"]}",
            "{\"OutfitID\":1005,\"ItemID\":227005,\"SlotTypeList\":[\"HeadDecor\",\"HeadDecor\"]}",
            "{\"OutfitID\":1005,\"ItemID\":227005}")) {
      GameData.getPlayerOutfitBaseExcelMap()
          .add(new Gson().fromJson(body, PlayerOutfitBaseExcel.class));
      assertNotEquals(0, invoke(s, List.of(1005)));
      assertEquals(0, s.saves);
    }
    GameData.getPlayerOutfitBaseExcelMap().add(row);
    var item = GameData.getItemExcelMap().get(227005);
    GameData.getItemExcelMap()
        .add(
            new Gson()
                .fromJson(
                    "{\"ID\":227005,\"ItemMainType\":\"Material\",\"PileLimit\":1}",
                    ItemExcel.class));
    assertNotEquals(0, invoke(s, List.of(1005)));
    assertEquals(0, s.saves);
    GameData.getItemExcelMap().add(item);
  }

  @Test
  void anOversizedBatchCannotReplaceValidSavedEquipment() throws Exception {
    var s = new Session();
    s.stock(227005);
    assertEquals(0, invoke(s, List.of(1005)));
    assertNotEquals(0, invoke(s, Collections.nCopies(103, 1005)));
    assertEquals(List.of(1005), query(s));
    assertEquals(1, s.saves);
  }

  @Test
  void reversedOrderDoesNotSaveANewCombinationOfTheSameSlots() throws Exception {
    var s = new Session();
    s.stock(227005);
    s.stock(229001);
    assertEquals(0, invoke(s, List.of(3001, 1005)));
    assertEquals(List.of(1005, 3001), query(s));
    assertEquals(0, invoke(s, List.of(1005, 3001)));
    assertEquals(1, s.saves);
  }

  @Test
  void eightConcurrentIdenticalRequestsSaveOneState() throws Exception {
    var s = new Session();
    s.stock(227005);
    var bytes = request(s, List.of(1005));
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 8; i++)
        futures.add(pool.submit(() -> VersionedPacketHandler.handle(s, 330, bytes)));
      for (var f : futures) assertTrue(f.get());
    }
    assertEquals(1, s.saves);
    assertEquals(8, s.syncs.size());
    assertEquals(List.of(1005), query(s));
  }

  @Test
  void absentLegacyStateAndReturnedCollectionsDoNotManufactureAnUnlock() throws Exception {
    var s = new Session();
    var field = org.hkrpg.server.gameplay.GameplayState.class.getDeclaredField("equippedOutfits");
    field.setAccessible(true);
    field.set(s.owner.getGameplay(), null);
    assertTrue(query(s).isEmpty());
    assertEquals(0, s.saves);
    assertEquals(0, invoke(s, List.of()));
    assertEquals(0, s.saves);
    s.stock(227005);
    assertEquals(0, invoke(s, List.of(1005)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> s.owner.getGameplay().getEquippedOutfits().clear());
    assertEquals(List.of(1005), query(s));
  }

  @Test
  void actualMorphiaLoadsKeepEquippedIdsAndClearedState() throws Exception {
    var s = new Session();
    s.stock(227005);
    assertEquals(0, invoke(s, List.of(1005)));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "outfit");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456786);
      var state = Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(document, s.owner.getGameplay());
      store.save(document);
      state.set(s.owner, store.find(Player.class).first().getGameplay());
      assertEquals(List.of(1005), query(s));
      assertEquals(0, invoke(s, List.of()));
      state.set(document, s.owner.getGameplay());
      store.save(document);
      state.set(s.owner, store.find(Player.class).first().getGameplay());
      assertTrue(query(s).isEmpty());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void publicPlayerDetailIncludesTheSavedOutfitContainer() throws Exception {
    var s = new Session();
    s.stock(227005);
    assertEquals(0, invoke(s, List.of(1005)));
    var name = Player.class.getDeclaredField("name");
    name.setAccessible(true);
    name.set(s.owner, "Outfit tester");
    var signature = Player.class.getDeclaredField("signature");
    signature.setAccessible(true);
    signature.set(s.owner, "");
    var core = s.owner.toDetailInfo();
    assertEquals(1005, core.getPlayerOutfitData().getOutfitIdList().get(0));
    var msg =
        s.getProtocol()
            .clientSchema()
            .parse(
                "CJLCPMDGIBO",
                s.getProtocol()
                    .translate("PlayerOutfitInfo", core.getPlayerOutfitData().toByteArray(), true));
    assertEquals(List.of(1005), Gameplay46.value(msg, "EAKOLIJOEPA"));
  }
}
