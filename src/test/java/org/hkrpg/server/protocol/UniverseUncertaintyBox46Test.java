package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UniverseUncertaintyBox46Test {
  @TempDir Path root;
  final UniverseMiracleShopTest fixture = new UniverseMiracleShopTest();
  GameplayDefinitions.Miracle priorMiracle;
  GameplayDefinitions.MiracleEffect priorEffect;
  static final int BUFF = UniverseMiracleShopTest.BUFF;

  @BeforeEach
  void setup() throws Exception {
    priorMiracle = GameplayDefinitions.miracles.get(5);
    priorEffect = GameplayDefinitions.miracleEffects.get(5);
    fixture.root = root;
    fixture.setup();
    GameplayDefinitions.miracles.remove(4);
    GameplayDefinitions.miracleEffects.remove(4);
    effect(1, 2);
    for (int offset = 1; offset <= 3; offset++) {
      int id = BUFF + offset;
      GameData.getMazeBuffExcelMap()
          .add(new Gson().fromJson("{\"ID\":" + id + ",\"Lv\":1}", MazeBuffExcel.class));
      GameplayDefinitions.blessings.add(
          new GameplayDefinitions.Blessing(
              id,
              1,
              100,
              offset == 1 ? "Common" : offset == 2 ? "Rare" : "Legendary",
              1,
              List.of()));
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
    GameplayDefinitions.miracles.remove(5);
    GameplayDefinitions.miracleEffects.remove(5);
    if (priorMiracle != null) GameplayDefinitions.miracles.put(5, priorMiracle);
    if (priorEffect != null) GameplayDefinitions.miracleEffects.put(5, priorEffect);
  }

  void effect(double... values) {
    GameplayDefinitions.miracles.put(5, new GameplayDefinitions.Miracle(5, 5, 5));
    GameplayDefinitions.miracleEffects.put(
        5,
        new GameplayDefinitions.MiracleEffect(
            5, Arrays.stream(values).mapToObj(GameplayDefinitions.EffectValue::new).toList()));
  }

  GameplayState.RogueRun run() {
    return fixture.run();
  }

  RogueMiracleShop.Quote quote() {
    var quotes = fixture.quotes();
    assertEquals(1, quotes.size());
    assertEquals(5, quotes.getFirst().id());
    return quotes.getFirst();
  }

  void granted() {
    assertTrue(run().getBlessings().size() >= 1 && run().getBlessings().size() <= 2);
    run()
        .getBlessings()
        .forEach(
            (id, level) -> {
              assertTrue(id >= BUFF && id <= BUFF + 3);
              assertEquals(1, level);
              assertNotNull(GameData.getMazeBuffExcelMap().get(id, level));
            });
    assertTrue(
        fixture
            .p()
            .getGameplay()
            .getRogueCollectedBuffs()
            .containsAll(run().getBlessings().keySet()));
  }

  @Test
  void nativePurchaseGrantsOneOrTwoDistinctValidBlessingsAndRejectsAnotherPurchase()
      throws Exception {
    quote();
    var query =
        fixture.call(
            5675,
            fixture.request(UniverseMiracleShopTest.ENTITY, 0, false),
            "GetRogueShopMiracleInfoScRsp");
    assertEquals(0, Gameplay46.number(query, "retcode"));
    assertEquals(5, Gameplay46.number(fixture.row(query), "LJCEOGPOGBE"));
    var response =
        fixture.call(
            5656,
            fixture.request(UniverseMiracleShopTest.ENTITY, 5, false),
            "BuyRogueShopMiracleScRsp");
    assertEquals(0, Gameplay46.number(response, "retcode"));
    granted();
    assertEquals(100, run().getCoins());
    assertEquals(List.of(1.0, 2.0), run().getMiracles().get(5).getEffectParameters());
    var before = Map.copyOf(run().getBlessings());
    assertNotEquals(Retcode.SUCC, fixture.buy(5));
    assertEquals(before, run().getBlessings());
    assertEquals(100, run().getCoins());
    assertTrue(quote().purchased());
  }

  @Test
  void aPoolThatDisappearsAfterQuotationLeavesThePriceButRejectsWithoutChargingOrGranting() {
    assertTrue(quote().available());
    GameplayDefinitions.blessings.clear();
    var unavailable = quote();
    assertEquals(200, unavailable.cost());
    assertFalse(unavailable.available());
    assertEquals(Retcode.REQ_PARA_INVALID, fixture.buy(5));
    assertEquals(300, run().getCoins());
    assertFalse(run().getMiracles().containsKey(5));
    assertTrue(run().getBlessings().isEmpty());
    assertFalse(quote().purchased());
  }

  @Test
  void alreadyOwnedHigherLevelAndMissingOrLockedCandidatesAreNotDowngradedOrGranted() {
    run().getBlessings().put(BUFF, 2);
    GameplayDefinitions.blessings.removeIf(b -> b.MazeBuffID() != BUFF);
    assertTrue(fixture.quotes().isEmpty());
    assertEquals(Retcode.REQ_PARA_INVALID, fixture.buy(5));
    assertEquals(Map.of(BUFF, 2), run().getBlessings());
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.blessings.add(
        new GameplayDefinitions.Blessing(BUFF + 1, 1, 100, "Common", 1, List.of(199979999)));
    GameplayDefinitions.blessings.add(
        new GameplayDefinitions.Blessing(BUFF + 2, 1, 100, "Common", 2, List.of()));
    GameplayDefinitions.blessings.add(
        new GameplayDefinitions.Blessing(BUFF + 3, 1, 100, "Unknown", 1, List.of()));
    assertTrue(fixture.quotes().isEmpty());
    assertEquals(300, run().getCoins());
    assertFalse(run().getMiracles().containsKey(5));
  }

  @Test
  void aKnownDrawOfTwoGrantsTwoDifferentActualBlessingsInsteadOfAlwaysReturningOne()
      throws Exception {
    assertEquals(0, run().getSelectionId());
    var id = GameplayState.RogueRun.class.getDeclaredField("runId");
    id.setAccessible(true);
    id.set(run(), "uncertainty-two-rewards");
    quote();
    assertEquals(Retcode.SUCC, fixture.buy(5));
    granted();
    assertEquals(2, run().getBlessings().size());
    assertEquals(100, run().getCoins());
  }

  @Test
  void theOnlyEligibleBlessingIsGrantedExactlyOnceRatherThanDuplicatedToReachTwo() {
    GameplayDefinitions.blessings.removeIf(b -> b.MazeBuffID() != BUFF);
    quote();
    assertEquals(Retcode.SUCC, fixture.buy(5));
    assertEquals(Map.of(BUFF, 1), run().getBlessings());
    assertNotEquals(Retcode.SUCC, fixture.buy(5));
    assertEquals(Map.of(BUFF, 1), run().getBlessings());
    assertEquals(100, run().getCoins());
  }

  @Test
  void acquisitionRequiresMatchedRowsAndTheKnownOneToTwoRange() {
    assertTrue(RogueMiracles.supported(5));
    for (var invalid :
        List.of(
            new double[] {1},
            new double[] {0, 2},
            new double[] {1, 3},
            new double[] {2, 1},
            new double[] {0.5, 2},
            new double[] {1, 2, 0},
            new double[] {1, Double.NaN})) {
      effect(invalid);
      assertFalse(RogueMiracles.supported(5));
    }
    effect(1, 2);
    GameplayDefinitions.miracles.put(5, new GameplayDefinitions.Miracle(5, 5, 3));
    assertFalse(RogueMiracles.supported(5));
  }

  @Test
  void concurrentPurchaseGrantsOnlyOneSavedRewardSetAndOneFragmentDebit() throws Exception {
    quote();
    try (var workers = Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<Callable<Retcode>>();
      for (int i = 0; i < 6; i++) calls.add(() -> fixture.buy(5));
      int successes = 0;
      for (var response : workers.invokeAll(calls)) {
        if (response.get() == Retcode.SUCC) successes++;
        else assertEquals(Retcode.REQ_PARA_INVALID, response.get());
      }
      assertEquals(1, successes);
    }
    granted();
    assertEquals(100, run().getCoins());
  }

  @Test
  void nativePendingMiracleSelectionGrantsTheSameImmediateEffectAndClearsTheMenu()
      throws Exception {
    run().offerMiracles(List.of(5));
    int batch = run().getSelectionId();
    var session = fixture.merchant.fixture.s;
    var request = Gameplay46.builder(session, "OFEDNNJIJFL");
    var choice = Gameplay46.builder(session, "JINFLJMPPHN");
    Gameplay46.set(choice, "OJPGDDFJCHB", 5);
    Gameplay46.set(request, "MPLGEEGOAMC", batch);
    Gameplay46.set(request, "CCCIFGFHGIL", choice.build());
    assertTrue(VersionedPacketHandler.handle(session, 5676, request.build().toByteArray()));
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    granted();
    assertFalse(run().pending());
    assertEquals(300, run().getCoins());
    var before = Map.copyOf(run().getBlessings());
    assertTrue(VersionedPacketHandler.handle(session, 5676, request.build().toByteArray()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    assertEquals(before, run().getBlessings());
  }

  @Test
  void morphiaRestoreKeepsActualGrantedIdsAndCannotRerollThemByBuyingAgain() throws Exception {
    quote();
    assertEquals(Retcode.SUCC, fixture.buy(5));
    granted();
    var expected = Map.copyOf(run().getBlessings());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "uncertainty-box");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456764);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, fixture.p().getGameplay());
      store.save(document);
      var restored = store.find(Player.class).first();
      gameplay.set(fixture.p(), restored.getGameplay());
      GameplayDefinitions.miracleEffects.remove(5);
      GameplayDefinitions.blessings.clear();
      assertEquals(expected, run().getBlessings());
      assertFalse(quote().available());
      assertNotEquals(Retcode.SUCC, fixture.buy(5));
      assertEquals(expected, run().getBlessings());
      gameplay.set(document, fixture.p().getGameplay());
      store.save(document);
      restored = store.find(Player.class).first();
      assertEquals(expected, restored.getGameplay().getRogue().getBlessings());
      assertTrue(restored.getGameplay().getRogueCollectedBuffs().containsAll(expected.keySet()));
      assertEquals(100, restored.getGameplay().getRogue().getCoins());
    } finally {
      mongo.shutdown();
    }
  }

  @Test
  void immediateBlessingAndMiracleNotificationsObserveTheSavedPurchaseAndCollectedIds()
      throws Exception {
    quote();
    int saved = fixture.p().saves;
    var blessings = new HashMap<Integer, Integer>();
    var names = new ArrayList<String>();
    var observer =
        new Gameplay46HandlerTest.Session() {
          public Player getPlayer() {
            return fixture.p();
          }

          public void sendClientPacket(String name, byte[] bytes) {
            super.sendClientPacket(name, bytes);
            assertEquals(saved + 1, fixture.p().saves);
            assertEquals(100, run().getCoins());
            assertTrue(quote().purchased());
            assertTrue(run().getMiracles().containsKey(5));
            names.add(name);
            if (name.equals("SyncRogueCommonActionResultScNotify")) {
              var detail =
                  (DynamicMessage)
                      Gameplay46.value(InitialData46Test.first(response, "CLGGKLNPLCK"), "detail");
              var field = detail.getDescriptorForType().findFieldByName("IFOOEPKGGAG");
              if (detail.hasField(field)) {
                var buff = (DynamicMessage) detail.getField(field);
                int id = Gameplay46.number(buff, "buff_id");
                assertTrue(fixture.p().getGameplay().getRogueCollectedBuffs().contains(id));
                blessings.put(id, Gameplay46.number(buff, "HAGDKKBAAKK"));
              }
            }
          }
        };
    fixture.p().setSession(observer);
    var online = Player.class.getDeclaredField("loggedIn");
    online.setAccessible(true);
    online.setBoolean(fixture.p(), true);
    try {
      assertEquals(Retcode.SUCC, fixture.buy(5));
      granted();
      assertEquals(run().getBlessings(), blessings);
      assertEquals(
          blessings.size() + 1,
          Collections.frequency(names, "SyncRogueCommonActionResultScNotify"));
      assertEquals("SyncRogueVirtualItemInfoScNotify", names.getLast());
    } finally {
      online.setBoolean(fixture.p(), false);
    }
  }

  @Test
  void anOldOwnedBoxWithoutRewardHistoryIsPreservedWithoutAutomaticNewBlessings() {
    quote();
    run().getMiracles().put(5, new GameplayState.RogueMiracle(0));
    assertFalse(quote().available());
    assertNotEquals(Retcode.SUCC, fixture.buy(5));
    assertFalse(RogueMiracles.acquire(fixture.p(), 5));
    assertTrue(run().getBlessings().isEmpty());
    assertEquals(300, run().getCoins());
  }
}
