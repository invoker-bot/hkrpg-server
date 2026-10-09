package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UniverseBuffShopTest {
  @TempDir Path root;
  final UniverseEnhance46Test fixture = new UniverseEnhance46Test();
  java.lang.reflect.Field configField;
  Object oldConfig;
  static final int BUFF = UniverseEnhance46Test.BUFF, ENTITY = UniverseEnhance46Test.ENTITY;
  static final String GRAPH = "Config/Level/RogueDialogue/Shop.json";

  @BeforeEach
  void setup() throws Exception {
    fixture.setup();
    fixture.begin();
    fixture.s.explorer.getGameplay().getRogue().getBlessings().clear();
    configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    oldConfig = configField.get(null);
    var config = new Config();
    config.resourceDir = root.toString();
    configField.set(null, config);
    Files.createDirectories(root.resolve(GRAPH).getParent());
    graph("BuffShop");
    merchant(GRAPH);
  }

  void graph(String page) throws Exception {
    Files.writeString(
        root.resolve(GRAPH),
        "{\"OnStartSequece\":[{\"TaskList\":[{\"$type\":\"RPG.GameCore.RoguePropOpenPage\",\"Type\":\""
            + page
            + "\"}]}]}");
  }

  void merchant(String graph) {
    var group =
        new Gson()
            .fromJson(
                "{\"NPCList\":[{\"ID\":400001,\"NPCID\":1007,\"Dialog\":{\"LevelGraph\":\""
                    + graph
                    + "\"}}]}",
                GroupInfo.class);
    group.setId(8);
    fixture.s.explorer.floor.getGroups().put(8, group);
    var npc = new EntityNpc(fixture.s.explorer.getScene(), group, group.getNPCList().getFirst());
    npc.setEntityId(ENTITY);
    fixture.s.explorer.getScene().getEntities().put(ENTITY, npc);
  }

  @AfterEach
  void cleanup() throws Exception {
    configField.set(null, oldConfig);
    fixture.cleanup();
    GameplayDefinitions.blessings.removeIf(
        v -> v.MazeBuffID() > BUFF && v.MazeBuffID() <= BUFF + 3);
    GameData.getMazeBuffExcelMap()
        .values()
        .removeIf(v -> v.getID() > BUFF && v.getID() <= BUFF + 3);
  }

  List<RogueBuffShop.Quote> quotes() {
    return RogueBuffShop.quotes(fixture.s.explorer, ENTITY);
  }

  Retcode buy(Integer... ids) {
    return RogueBuffShop.buy(fixture.s.explorer, ENTITY, List.of(ids));
  }

  GameplayState.RogueRun run() {
    return fixture.s.explorer.getGameplay().getRogue();
  }

  @Test
  void actualMerchantSellsAdvertisedBlessingOnceAndKeepsTheSoldQuote() {
    var quote = quotes();
    assertEquals(1, quote.size());
    assertEquals(BUFF, quote.getFirst().id());
    assertEquals(1, quote.getFirst().level());
    assertEquals(100, quote.getFirst().cost());
    assertFalse(quote.getFirst().purchased());
    int saves = fixture.s.explorer.saves;
    assertEquals(Retcode.SUCC, buy(BUFF));
    assertEquals(200, run().getCoins());
    assertEquals(1, run().getBlessings().get(BUFF));
    assertEquals(saves + 1, fixture.s.explorer.saves);
    assertTrue(quotes().getFirst().purchased());
    assertTrue(quotes().getFirst().owned());
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    assertEquals(200, run().getCoins());
  }

  @Test
  void purchaseRequiresSavedQuoteAndValidWholeBatch() {
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    quotes();
    for (var ids : List.of(List.<Integer>of(), List.of(BUFF, BUFF), List.of(BUFF, BUFF + 7)))
      assertNotEquals(Retcode.SUCC, RogueBuffShop.buy(fixture.s.explorer, ENTITY, ids));
    assertEquals(300, run().getCoins());
    assertTrue(run().getBlessings().isEmpty());
    assertEquals(Retcode.SUCC, buy(BUFF));
  }

  @Test
  void insufficientFundsDoNotConsumeStockAndLaterPurchaseCanSucceed() {
    quotes();
    assertTrue(run().spend(250));
    assertEquals(Retcode.ITEM_NOT_ENOUGH, buy(BUFF));
    assertFalse(quotes().getFirst().purchased());
    run().addCoins(50);
    assertEquals(Retcode.SUCC, buy(BUFF));
    assertEquals(0, run().getCoins());
  }

  @Test
  void alreadyOwnedOrMissingResourceCannotCharge() {
    quotes();
    run().getBlessings().put(BUFF, 1);
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    run().getBlessings().clear();
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == BUFF);
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    assertEquals(300, run().getCoins());
  }

  @Test
  void actualNpcGroupInstancePageAndCurrentRunAreRequired() throws Exception {
    assertEquals(1, quotes().size());
    var npc = (EntityNpc) fixture.s.explorer.getScene().getEntityById(ENTITY);
    npc.setInstId(400002);
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    npc.setInstId(400001);
    npc.setGroupId(9);
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    npc.setGroupId(8);
    npc.setNpcId(1013);
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    npc.setNpcId(1007);
    graph("MiracleShop");
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    graph("BuffShop");
    run().setInside(false);
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    run().setInside(true);
    run().getMiracleOffers().add(4);
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    run().getMiracleOffers().clear();
    assertEquals(300, run().getCoins());
    assertEquals(Retcode.SUCC, buy(BUFF));
  }

  @Test
  void savedPricesAndStockSurviveReloadAndSceneEntityRenumbering() throws Exception {
    quotes();
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(
        fixture.s.explorer,
        new Gson()
            .fromJson(new Gson().toJson(fixture.s.explorer.getGameplay()), GameplayState.class));
    fixture.buff(1, "Legendary");
    assertEquals(100, quotes().getFirst().cost());
    var npc = (EntityNpc) fixture.s.explorer.getScene().getEntities().remove(ENTITY);
    npc.setEntityId(ENTITY + 1);
    fixture.s.explorer.getScene().getEntities().put(ENTITY + 1, npc);
    assertEquals(100, RogueBuffShop.quotes(fixture.s.explorer, ENTITY + 1).getFirst().cost());
    assertEquals(Retcode.SUCC, RogueBuffShop.buy(fixture.s.explorer, ENTITY + 1, List.of(BUFF)));
    gameplay.set(
        fixture.s.explorer,
        new Gson()
            .fromJson(new Gson().toJson(fixture.s.explorer.getGameplay()), GameplayState.class));
    assertNotEquals(Retcode.SUCC, RogueBuffShop.buy(fixture.s.explorer, ENTITY + 1, List.of(BUFF)));
    assertEquals(200, run().getCoins());
  }

  @Test
  void concurrentPurchasesDebitOneQuote() throws Exception {
    quotes();
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Future<Retcode>>();
      for (int i = 0; i < 6; i++)
        tasks.add(
            workers.submit(
                () -> {
                  gate.await();
                  return buy(BUFF);
                }));
      gate.countDown();
      int successes = 0;
      for (var task : tasks)
        if (task.get(8, java.util.concurrent.TimeUnit.SECONDS) == Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(200, run().getCoins());
    }
  }

  byte[] request(int entity, boolean refresh, Integer... ids) throws Exception {
    var buffer = new java.io.ByteArrayOutputStream();
    var out = com.google.protobuf.CodedOutputStream.newInstance(buffer);
    if (ids.length == 0) {
      out.writeUInt32(15, entity);
      if (refresh) out.writeBool(6, true);
    } else {
      out.writeUInt32(2, entity);
      var packed = new java.io.ByteArrayOutputStream();
      var entries = com.google.protobuf.CodedOutputStream.newInstance(packed);
      for (int id : ids) entries.writeUInt32NoTag(id);
      entries.flush();
      out.writeByteArray(6, packed.toByteArray());
    }
    out.flush();
    return buffer.toByteArray();
  }

  com.google.protobuf.DynamicMessage call(int command, byte[] body, String response)
      throws Exception {
    assertTrue(VersionedPacketHandler.handle(fixture.s, command, body));
    assertEquals(response, fixture.s.responseName);
    return fixture.s.response;
  }

  @Test
  void nativeQueryAndPackedPurchaseReturnActualLevelPriceAndSoldFlags() throws Exception {
    var query = call(5616, request(ENTITY, false), "GetRogueShopBuffInfoScRsp");
    assertEquals(0, Gameplay46.number(query, "retcode"));
    var shop = (com.google.protobuf.DynamicMessage) Gameplay46.value(query, "HABJHGPFGEB");
    var row = InitialData46Test.first(shop, "buff_list");
    assertEquals(BUFF, Gameplay46.number(row, "buff_id"));
    assertEquals(1, Gameplay46.number(row, "JBGENFIKGGF"));
    assertEquals(false, Gameplay46.value(row, "GCIHGKCJOGB"));
    assertEquals(false, Gameplay46.value(row, "BELPGDAGFIH"));
    var price = (com.google.protobuf.DynamicMessage) Gameplay46.value(row, "cost_data");
    var item = InitialData46Test.first(price, "item_list");
    var pile = (com.google.protobuf.DynamicMessage) Gameplay46.value(item, "pile_item");
    assertEquals(31, Gameplay46.number(pile, "item_id"));
    assertEquals(100, Gameplay46.number(pile, "item_num"));
    var bought = call(5615, request(ENTITY, false, BUFF), "BuyRogueShopBuffScRsp");
    assertEquals(0, Gameplay46.number(bought, "retcode"));
    row =
        InitialData46Test.first(
            (com.google.protobuf.DynamicMessage) Gameplay46.value(bought, "HABJHGPFGEB"),
            "buff_list");
    assertEquals(true, Gameplay46.value(row, "GCIHGKCJOGB"));
    assertEquals(true, Gameplay46.value(row, "BELPGDAGFIH"));
    assertEquals(200, run().getCoins());
    assertNotEquals(
        0,
        Gameplay46.number(
            call(5615, request(ENTITY, false, BUFF), "BuyRogueShopBuffScRsp"), "retcode"));
  }

  @Test
  void nativeInvalidAndUnsupportedRefreshCannotRepriceOrDebit() throws Exception {
    var query = call(5616, request(ENTITY, true), "GetRogueShopBuffInfoScRsp");
    assertNotEquals(0, Gameplay46.number(query, "retcode"));
    assertNotEquals(
        0,
        Gameplay46.number(
            call(5615, request(ENTITY, false, BUFF), "BuyRogueShopBuffScRsp"), "retcode"));
    call(5616, request(ENTITY, false), "GetRogueShopBuffInfoScRsp");
    var good = request(ENTITY, false, BUFF);
    var doubled = new byte[good.length * 2];
    System.arraycopy(good, 0, doubled, 0, good.length);
    System.arraycopy(good, 0, doubled, good.length, good.length);
    for (var invalid :
        List.of(
            new byte[0],
            doubled,
            new byte[] {16, (byte) 128},
            request(ENTITY, false, BUFF, BUFF),
            request(ENTITY + 1, false, BUFF)))
      assertNotEquals(
          0, Gameplay46.number(call(5615, invalid, "BuyRogueShopBuffScRsp"), "retcode"));
    assertEquals(300, run().getCoins());
    assertEquals(Retcode.SUCC, buy(BUFF));
  }

  @Test
  void limitedStockAndBatchPurchaseStayAtomicWhenOneItemIsInvalid() {
    for (int id = BUFF + 1; id <= BUFF + 3; id++) {
      GameplayDefinitions.blessings.add(
          new GameplayDefinitions.Blessing(id, 1, 100, "Common", 1, List.of()));
      GameData.getMazeBuffExcelMap()
          .add(
              new Gson()
                  .fromJson(
                      "{\"ID\":" + id + ",\"Lv\":1}",
                      emu.lunarcore.data.excel.MazeBuffExcel.class));
    }
    var stock = quotes();
    assertEquals(3, stock.size());
    assertEquals(stock, quotes());
    int first = stock.getFirst().id(), second = stock.get(1).id();
    assertNotEquals(Retcode.SUCC, buy(first, BUFF + 10));
    assertEquals(300, run().getCoins());
    assertTrue(run().getBlessings().isEmpty());
    assertEquals(Retcode.SUCC, buy(first, second));
    assertEquals(100, run().getCoins());
    assertEquals(Set.of(first, second), run().getBlessings().keySet());
    assertEquals(2, quotes().stream().filter(RogueBuffShop.Quote::purchased).count());
  }

  @Test
  void corruptSavedQuoteIsPreservedAndRejectedByNativeQueryAndPurchase() throws Exception {
    quotes();
    var plan = run().getBlessingShops().values().iterator().next();
    var cost = GameplayState.RogueShopOffer.class.getDeclaredField("cost");
    cost.setAccessible(true);
    cost.setInt(plan.getOffers().get(BUFF), -1);
    assertNotEquals(
        0,
        Gameplay46.number(
            call(5616, request(ENTITY, false), "GetRogueShopBuffInfoScRsp"), "retcode"));
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    assertEquals(300, run().getCoins());
    assertSame(plan, run().getBlessingShops().values().iterator().next());
  }

  @Test
  void unauthenticatedWrongVersionAndWidePackedIdsCannotOpenOrBuy() throws Exception {
    fixture.s.setState(emu.lunarcore.server.packet.SessionState.WAITING_FOR_LOGIN);
    int saves = fixture.s.explorer.saves;
    assertTrue(VersionedPacketHandler.handle(fixture.s, 5616, request(ENTITY, false)));
    assertNull(fixture.s.responseName);
    assertEquals(saves, fixture.s.explorer.saves);
    fixture.s.setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    var old =
        new UniverseEnhance46Test.Session() {
          public ProtocolBridge getProtocol() {
            return ProtocolBridge.forVersion("4.2.0");
          }
        };
    assertFalse(VersionedPacketHandler.handle(old, 5616, request(ENTITY, false)));
    call(5616, request(ENTITY, false), "GetRogueShopBuffInfoScRsp");
    var unknown =
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                9, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(ENTITY).build())
            .build()
            .toByteArray();
    var wide =
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                2, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(ENTITY).build())
            .addField(
                6,
                com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                    .addVarint((1L << 32) + BUFF)
                    .build())
            .build()
            .toByteArray();
    for (var invalid :
        List.of(unknown, wide, request(ENTITY, false, BUFF, BUFF + 1, BUFF + 2, BUFF + 3)))
      assertNotEquals(
          0, Gameplay46.number(call(5615, invalid, "BuyRogueShopBuffScRsp"), "retcode"));
    assertEquals(300, run().getCoins());
    assertTrue(run().getBlessings().isEmpty());
  }

  @Test
  void morphiaReloadPreservesWalletStockPricesAndCompletedTransaction() throws Exception {
    quotes();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-buff-shop");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456774);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      gameplay.set(fixture.s.explorer, store.find(Player.class).first().getGameplay());
      fixture.buff(1, "Legendary");
      assertEquals(100, quotes().getFirst().cost());
      assertEquals(Retcode.SUCC, buy(BUFF));
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      gameplay.set(fixture.s.explorer, store.find(Player.class).first().getGameplay());
      assertTrue(quotes().getFirst().purchased());
      assertNotEquals(Retcode.SUCC, buy(BUFF));
      assertEquals(200, run().getCoins());
      assertEquals(1, run().getBlessings().get(BUFF));
      assertEquals(Set.of(BUFF), fixture.s.explorer.getGameplay().getRogueCollectedBuffs());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void onlySuccessfulPurchaseAdvancesPersistentBlessingCollectionUnlocks() {
    int unlock = 19997270, rule = 19997271;
    var oldUnlock = GameplayDefinitions.rogueUnlocks.get(unlock);
    var oldRule = GameplayDefinitions.rogueRules.get(rule);
    try {
      GameplayDefinitions.rogueUnlocks.put(
          unlock, new GameplayDefinitions.RogueUnlock(unlock, rule));
      GameplayDefinitions.rogueRules.put(
          rule,
          new GameplayDefinitions.RogueRule(
              rule, "RogueUnlockBuff", "Equal", 100, 0, 0, "", List.of(), List.of(), 1));
      assertFalse(RogueUnlocks.open(fixture.s.explorer, unlock));
      assertNotEquals(Retcode.SUCC, buy(BUFF));
      quotes();
      assertFalse(RogueUnlocks.open(fixture.s.explorer, unlock));
      assertEquals(Retcode.SUCC, buy(BUFF));
      assertTrue(RogueUnlocks.open(fixture.s.explorer, unlock));
      assertEquals(Set.of(BUFF), fixture.s.explorer.getGameplay().getRogueCollectedBuffs());
      assertNotEquals(Retcode.SUCC, buy(BUFF));
      assertEquals(Set.of(BUFF), fixture.s.explorer.getGameplay().getRogueCollectedBuffs());
    } finally {
      GameplayDefinitions.rogueUnlocks.remove(unlock);
      GameplayDefinitions.rogueRules.remove(rule);
      if (oldUnlock != null) GameplayDefinitions.rogueUnlocks.put(unlock, oldUnlock);
      if (oldRule != null) GameplayDefinitions.rogueRules.put(rule, oldRule);
    }
  }

  @Test
  void nativeAvailabilityTurnsOffWhenAnAdvertisedResourceDisappears() throws Exception {
    var query = call(5616, request(ENTITY, false), "GetRogueShopBuffInfoScRsp");
    var row =
        InitialData46Test.first(
            (com.google.protobuf.DynamicMessage) Gameplay46.value(query, "HABJHGPFGEB"),
            "buff_list");
    assertEquals(false, Gameplay46.value(row, "GCIHGKCJOGB"));
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == BUFF);
    query = call(5616, request(ENTITY, false), "GetRogueShopBuffInfoScRsp");
    row =
        InitialData46Test.first(
            (com.google.protobuf.DynamicMessage) Gameplay46.value(query, "HABJHGPFGEB"),
            "buff_list");
    assertEquals(true, Gameplay46.value(row, "GCIHGKCJOGB"));
    assertEquals(false, Gameplay46.value(row, "BELPGDAGFIH"));
    assertFalse(quotes().getFirst().purchased());
    assertNotEquals(Retcode.SUCC, buy(BUFF));
    assertEquals(300, run().getCoins());
  }
}
