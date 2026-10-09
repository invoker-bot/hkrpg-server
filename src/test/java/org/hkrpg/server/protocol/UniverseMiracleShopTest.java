package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.*;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UniverseMiracleShopTest {
  @TempDir Path root;
  final UniverseBuffShopTest merchant = new UniverseBuffShopTest();
  static final int ENTITY = UniverseEnhance46Test.ENTITY;
  static final int BUFF = UniverseEnhance46Test.BUFF;
  static final Set<Integer> IDS = Set.of(4, 6, 13, 15, 17, 20, 8);
  final Map<Integer, GameplayDefinitions.Miracle> oldRows = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    for (int id : IDS) {
      if (GameplayDefinitions.miracles.containsKey(id))
        oldRows.put(id, GameplayDefinitions.miracles.get(id));
      if (GameplayDefinitions.miracleEffects.containsKey(id))
        oldEffects.put(id, GameplayDefinitions.miracleEffects.get(id));
      GameplayDefinitions.miracles.remove(id);
      GameplayDefinitions.miracleEffects.remove(id);
    }
    merchant.root = root;
    merchant.setup();
    merchant.graph("MiracleShop");
    npc(UniverseBuffShopTest.GRAPH);
    effect(4, 1);
  }

  void effect(int id, double param) {
    GameplayDefinitions.miracles.put(id, new GameplayDefinitions.Miracle(id, id, id));
    GameplayDefinitions.miracleEffects.put(
        id,
        new GameplayDefinitions.MiracleEffect(
            id, List.of(new GameplayDefinitions.EffectValue(param))));
  }

  void npc(String path) {
    var group =
        new Gson()
            .fromJson(
                "{\"NPCList\":[{\"ID\":400001,\"NPCID\":1013,\"Dialog\":{\"LevelGraph\":\""
                    + path
                    + "\"}}]}",
                GroupInfo.class);
    group.setId(8);
    merchant.fixture.s.explorer.floor.getGroups().put(8, group);
    var npc = new EntityNpc(p().getScene(), group, group.getNPCList().getFirst());
    npc.setEntityId(ENTITY);
    p().getScene().getEntities().put(ENTITY, npc);
  }

  @AfterEach
  void cleanup() throws Exception {
    merchant.cleanup();
    for (int id : IDS) {
      GameplayDefinitions.miracles.remove(id);
      GameplayDefinitions.miracleEffects.remove(id);
    }
    GameplayDefinitions.miracles.putAll(oldRows);
    GameplayDefinitions.miracleEffects.putAll(oldEffects);
  }

  UniverseEnhance46Test.Explorer p() {
    return merchant.fixture.s.explorer;
  }

  GameplayState.RogueRun run() {
    return p().getGameplay().getRogue();
  }

  List<RogueMiracleShop.Quote> quotes() {
    return RogueMiracleShop.quotes(p(), ENTITY);
  }

  Retcode buy(int id) {
    return RogueMiracleShop.buy(p(), ENTITY, id);
  }

  @Test
  void actualMerchantSellsFrozenSupportedMiracleOnce() {
    assertEquals(List.of(4), quotes().stream().map(RogueMiracleShop.Quote::id).toList());
    assertEquals(200, quotes().getFirst().cost());
    int saves = p().saves;
    assertEquals(Retcode.SUCC, buy(4));
    assertEquals(100, run().getCoins());
    assertEquals(saves + 1, p().saves);
    assertTrue(run().getMiracles().containsKey(4));
    assertTrue(quotes().getFirst().purchased());
    assertTrue(quotes().getFirst().owned());
    assertFalse(quotes().getFirst().available());
    assertNotEquals(Retcode.SUCC, buy(4));
    assertEquals(100, run().getCoins());
  }

  @Test
  void quoteRequiredAndInsufficientFundsLeaveStockAndEffectsUntouched() {
    assertNotEquals(Retcode.SUCC, buy(4));
    quotes();
    assertNotEquals(Retcode.SUCC, buy(6));
    assertTrue(run().spend(101));
    assertEquals(Retcode.ITEM_NOT_ENOUGH, buy(4));
    assertTrue(run().getMiracles().isEmpty());
    assertFalse(quotes().getFirst().purchased());
    run().addCoins(1);
    assertEquals(Retcode.SUCC, buy(4));
    assertEquals(0, run().getCoins());
  }

  @Test
  void shopOffersOnlyImplementedUnownedEffectsAndFreezesAtMostThreeItems() {
    effect(6, .3);
    effect(13, .3);
    effect(15, .06);
    effect(17, 2);
    effect(20, 2);
    effect(8, .3);
    run().getMiracles().put(4, new GameplayState.RogueMiracle(1));
    var offered = quotes();
    assertEquals(3, offered.size());
    assertEquals(3, offered.stream().map(RogueMiracleShop.Quote::id).distinct().count());
    assertTrue(offered.stream().allMatch(q -> IDS.contains(q.id()) && q.id() != 4 && q.id() != 8));
    for (int id : IDS) effect(id, id == 6 || id == 13 || id == 15 ? .2 : 3);
    assertEquals(offered, quotes());
  }

  @Test
  void owningItemOrLosingSupportedResourceCannotDebit() {
    quotes();
    run().getMiracles().put(4, new GameplayState.RogueMiracle(1));
    assertNotEquals(Retcode.SUCC, buy(4));
    run().getMiracles().clear();
    GameplayDefinitions.miracleEffects.remove(4);
    assertNotEquals(Retcode.SUCC, buy(4));
    assertEquals(300, run().getCoins());
    assertFalse(quotes().getFirst().available());
  }

  @Test
  void actualNpcRoomGroupInstancePageAndRunStateAreRequired() throws Exception {
    quotes();
    var npc = (EntityNpc) p().getScene().getEntityById(ENTITY);
    npc.setNpcId(1007);
    assertNotEquals(Retcode.SUCC, buy(4));
    npc.setNpcId(1013);
    npc.setGroupId(9);
    assertNotEquals(Retcode.SUCC, buy(4));
    npc.setGroupId(8);
    npc.setInstId(400002);
    assertNotEquals(Retcode.SUCC, buy(4));
    npc.setInstId(400001);
    merchant.graph("BuffShop");
    assertNotEquals(Retcode.SUCC, buy(4));
    merchant.graph("MiracleShop");
    npc("Config/Level/../../outside.json");
    assertNotEquals(Retcode.SUCC, buy(4));
    npc(UniverseBuffShopTest.GRAPH);
    run().setInside(false);
    assertNotEquals(Retcode.SUCC, buy(4));
    run().setInside(true);
    run().getMiracleOffers().add(6);
    assertNotEquals(Retcode.SUCC, buy(4));
    run().getMiracleOffers().clear();
    assertEquals(300, run().getCoins());
    assertEquals(Retcode.SUCC, buy(4));
  }

  @Test
  void concurrentPurchasesApplyOneEffectAndOneDebit() throws Exception {
    quotes();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Future<Retcode>>();
      for (int i = 0; i < 6; i++) tasks.add(executor.submit(() -> buy(4)));
      int successes = 0;
      for (var task : tasks)
        if (task.get(8, java.util.concurrent.TimeUnit.SECONDS) == Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(100, run().getCoins());
      assertEquals(1, run().getMiracles().get(4).getTotalCount());
    }
  }

  @Test
  void repairPurchaseRestoresActualBrokenMiracleAndDoesNotHealTheNormalTeam() {
    effect(17, 2);
    var broken = new GameplayState.RogueMiracle(1);
    assertTrue(broken.consume());
    run().getMiracles().put(4, broken);
    int hp = p().getAvatarById(199961).getCurrentHp(p().getCurrentLineup());
    assertEquals(List.of(17), quotes().stream().map(RogueMiracleShop.Quote::id).toList());
    assertEquals(Retcode.SUCC, buy(17));
    assertFalse(broken.broken());
    assertEquals(0, broken.getUseCount());
    assertEquals(100, run().getCoins());
    assertEquals(hp, p().getAvatarById(199961).getCurrentHp(p().getCurrentLineup()));
  }

  @Test
  void upgradePurchaseAppliesSupportedResourceEffectExactlyOnce() {
    GameplayDefinitions.miracles.remove(4);
    GameplayDefinitions.miracleEffects.remove(4);
    effect(20, 2);
    run().getBlessings().put(BUFF, 1);
    assertEquals(List.of(20), quotes().stream().map(RogueMiracleShop.Quote::id).toList());
    assertEquals(Retcode.SUCC, buy(20));
    assertEquals(2, run().getBlessings().get(BUFF));
    assertNotEquals(Retcode.SUCC, buy(20));
    assertEquals(2, run().getBlessings().get(BUFF));
    assertEquals(100, run().getCoins());
  }

  void reload() throws Exception {
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(p(), new Gson().fromJson(new Gson().toJson(p().getGameplay()), GameplayState.class));
  }

  @Test
  void frozenStockPriceAndSoldRecordSurviveJsonReloadAndEntityRenumbering() throws Exception {
    var offered = quotes();
    reload();
    effect(6, .3);
    assertEquals(offered, quotes());
    var npc = (EntityNpc) p().getScene().getEntities().remove(ENTITY);
    npc.setEntityId(ENTITY + 1);
    p().getScene().getEntities().put(ENTITY + 1, npc);
    assertEquals(offered, RogueMiracleShop.quotes(p(), ENTITY + 1));
    assertEquals(Retcode.SUCC, RogueMiracleShop.buy(p(), ENTITY + 1, 4));
    reload();
    assertNotEquals(Retcode.SUCC, RogueMiracleShop.buy(p(), ENTITY + 1, 4));
    assertEquals(100, run().getCoins());
    assertTrue(RogueMiracleShop.quotes(p(), ENTITY + 1).getFirst().purchased());
  }

  byte[] request(int entity, int id, boolean refresh) throws Exception {
    var buffer = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    out.writeUInt32(12, entity);
    if (id > 0) out.writeUInt32(6, id);
    if (refresh) out.writeBool(13, true);
    out.flush();
    return buffer.toByteArray();
  }

  DynamicMessage call(int command, byte[] body, String response) throws Exception {
    assertTrue(VersionedPacketHandler.handle(merchant.fixture.s, command, body));
    assertEquals(response, merchant.fixture.s.responseName);
    return merchant.fixture.s.response;
  }

  DynamicMessage row(DynamicMessage response) {
    return InitialData46Test.first(
        (DynamicMessage) Gameplay46.value(response, "DNPCCFFFBHN"), "MAJGMMCOCIG");
  }

  @Test
  void nativeQueryAndActual5656PurchaseReturnRealCostsAndOwnership() throws Exception {
    var query = call(5675, request(ENTITY, 0, false), "GetRogueShopMiracleInfoScRsp");
    assertEquals(0, Gameplay46.number(query, "retcode"));
    assertEquals(4, Gameplay46.number(row(query), "LJCEOGPOGBE"));
    assertEquals(false, Gameplay46.value(row(query), "GCIHGKCJOGB"));
    assertEquals(false, Gameplay46.value(row(query), "BELPGDAGFIH"));
    var cost = (DynamicMessage) Gameplay46.value(row(query), "cost_data");
    var pile =
        (DynamicMessage) Gameplay46.value(InitialData46Test.first(cost, "item_list"), "pile_item");
    assertEquals(31, Gameplay46.number(pile, "item_id"));
    assertEquals(200, Gameplay46.number(pile, "item_num"));
    var bought = call(5656, request(ENTITY, 4, false), "BuyRogueShopMiracleScRsp");
    assertEquals(0, Gameplay46.number(bought, "retcode"));
    assertEquals(true, Gameplay46.value(row(bought), "GCIHGKCJOGB"));
    assertEquals(true, Gameplay46.value(row(bought), "BELPGDAGFIH"));
    assertEquals(100, run().getCoins());
    assertNotEquals(
        0,
        Gameplay46.number(
            call(5656, request(ENTITY, 4, false), "BuyRogueShopMiracleScRsp"), "retcode"));
  }

  @Test
  void nativeInvalidRefreshUnknownDuplicateTruncatedAndWideIdsCannotDebit() throws Exception {
    assertNotEquals(
        0,
        Gameplay46.number(
            call(5675, request(ENTITY, 0, true), "GetRogueShopMiracleInfoScRsp"), "retcode"));
    call(5675, request(ENTITY, 0, false), "GetRogueShopMiracleInfoScRsp");
    var good = request(ENTITY, 4, false);
    var doubled = new byte[good.length * 2];
    System.arraycopy(good, 0, doubled, 0, good.length);
    System.arraycopy(good, 0, doubled, good.length, good.length);
    var wide =
        UnknownFieldSet.newBuilder()
            .addField(12, UnknownFieldSet.Field.newBuilder().addVarint(ENTITY).build())
            .addField(6, UnknownFieldSet.Field.newBuilder().addVarint((1L << 32) + 4).build())
            .build()
            .toByteArray();
    for (var invalid :
        List.of(
            new byte[0],
            doubled,
            new byte[] {96, (byte) 128},
            new byte[] {72, 4},
            wide,
            request(ENTITY, 0, false),
            request(ENTITY + 1, 4, false)))
      assertNotEquals(
          0, Gameplay46.number(call(5656, invalid, "BuyRogueShopMiracleScRsp"), "retcode"));
    assertEquals(300, run().getCoins());
    assertTrue(run().getMiracles().isEmpty());
    assertEquals(Retcode.SUCC, buy(4));
  }

  @Test
  void unauthenticatedAndLegacySessionsDoNotOpenMerchantStock() throws Exception {
    merchant.fixture.s.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(merchant.fixture.s, 5675, request(ENTITY, 0, false)));
    assertNull(merchant.fixture.s.responseName);
    assertTrue(run().getMiracleShops().isEmpty());
    merchant.fixture.s.setState(SessionState.ACTIVE);
    var old =
        new Gameplay46HandlerTest.Session() {
          public ProtocolBridge getProtocol() {
            return ProtocolBridge.forVersion("4.2.0");
          }
        };
    assertFalse(VersionedPacketHandler.handle(old, 5675, request(ENTITY, 0, false)));
    assertEquals(1, quotes().size());
  }

  @Test
  void corruptQuoteStaysRejectedWithoutRegeneratingStock() throws Exception {
    quotes();
    String key = run().getMiracleShops().keySet().iterator().next();
    var corrupt =
        new Gson()
            .fromJson(
                "{\"offers\":{\"4\":{\"level\":1,\"cost\":-1}}}",
                GameplayState.RogueShopPlan.class);
    run().getMiracleShops().put(key, corrupt);
    assertNotEquals(Retcode.SUCC, RogueMiracleShop.status(p(), ENTITY));
    assertTrue(quotes().isEmpty());
    assertNotEquals(Retcode.SUCC, buy(4));
    assertSame(corrupt, run().getMiracleShops().get(key));
    assertEquals(300, run().getCoins());
    assertNotEquals(
        0,
        Gameplay46.number(
            call(5675, request(ENTITY, 0, false), "GetRogueShopMiracleInfoScRsp"), "retcode"));
  }

  @Test
  void morphiaRestoresMiracleLifetimeCompletedStockAndWallet() throws Exception {
    quotes();
    assertEquals(Retcode.SUCC, buy(4));
    assertTrue(run().getMiracles().get(4).consume());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-miracle-shop");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456775);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, p().getGameplay());
      store.save(document);
      gameplay.set(p(), store.find(Player.class).first().getGameplay());
      assertEquals(100, run().getCoins());
      assertTrue(run().getMiracles().get(4).broken());
      assertEquals(1, run().getMiracles().get(4).getTotalCount());
      assertTrue(quotes().getFirst().purchased());
      assertNotEquals(Retcode.SUCC, buy(4));
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void effectAndCurrencyNotificationsObserveSavedPurchase() throws Exception {
    effect(17, 2);
    var broken = new GameplayState.RogueMiracle(1);
    broken.consume();
    run().getMiracles().put(4, broken);
    quotes();
    int priorSaves = p().saves;
    var notifications = new ArrayList<String>();
    var observer =
        new Gameplay46HandlerTest.Session() {
          public Player getPlayer() {
            return p();
          }

          public void sendClientPacket(String name, byte[] bytes) {
            assertEquals(priorSaves + 1, p().saves);
            assertEquals(100, run().getCoins());
            assertFalse(run().getMiracles().get(4).broken());
            assertTrue(run().getMiracles().containsKey(17));
            assertTrue(quotes().getFirst().purchased());
            notifications.add(name);
          }
        };
    var online = Player.class.getDeclaredField("loggedIn");
    online.setAccessible(true);
    p().setSession(observer);
    online.setBoolean(p(), true);
    try {
      assertEquals(Retcode.SUCC, buy(17));
      assertEquals(
          List.of(
              "SyncRogueCommonActionResultScNotify",
              "SyncRogueCommonActionResultScNotify",
              "SyncRogueVirtualItemInfoScNotify"),
          notifications);
    } finally {
      online.setBoolean(p(), false);
    }
  }
}
