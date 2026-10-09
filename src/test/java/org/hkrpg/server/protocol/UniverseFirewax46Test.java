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

class UniverseFirewax46Test {
  @TempDir Path root;
  final UniverseMiracleShopTest fixture = new UniverseMiracleShopTest();
  final Map<Integer, GameplayDefinitions.Miracle> priorRows = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> priorEffects = new HashMap<>();
  static final int BUFF = UniverseMiracleShopTest.BUFF + 100;
  // Pinned RogueMiracleEffect descriptions cross-checked with RogueBuffType text and icons.
  static final int[] PATHS = {120, 126, 124, 125, 121, 122, 123};

  @BeforeEach
  void setup() throws Exception {
    for (int id = 22; id <= 28; id++) {
      priorRows.put(id, GameplayDefinitions.miracles.get(id));
      priorEffects.put(id, GameplayDefinitions.miracleEffects.get(id));
      GameplayDefinitions.miracles.remove(id);
      GameplayDefinitions.miracleEffects.remove(id);
    }
    fixture.root = root;
    fixture.setup();
    GameplayDefinitions.miracles.remove(4);
    GameplayDefinitions.miracleEffects.remove(4);
    GameplayDefinitions.blessings.clear();
    for (int path = 0; path < PATHS.length; path++)
      for (int index = 0; index < 6; index++) {
        int id = BUFF + path * 10 + index;
        GameData.getMazeBuffExcelMap()
            .add(new Gson().fromJson("{\"ID\":" + id + ",\"Lv\":1}", MazeBuffExcel.class));
        GameplayDefinitions.blessings.add(
            new GameplayDefinitions.Blessing(id, 1, PATHS[path], "Common", 1, List.of()));
      }
    effect(22, 0);
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
    GameplayDefinitions.blessings.removeIf(
        b -> b.MazeBuffID() >= BUFF && b.MazeBuffID() <= BUFF + 65);
    GameData.getMazeBuffExcelMap()
        .values()
        .removeIf(b -> b.getBuffId() >= BUFF && b.getBuffId() <= BUFF + 65);
    for (int id = 22; id <= 28; id++) {
      GameplayDefinitions.miracles.remove(id);
      GameplayDefinitions.miracleEffects.remove(id);
      if (priorRows.get(id) != null) GameplayDefinitions.miracles.put(id, priorRows.get(id));
      if (priorEffects.get(id) != null)
        GameplayDefinitions.miracleEffects.put(id, priorEffects.get(id));
    }
  }

  void effect(int id, double... values) {
    GameplayDefinitions.miracles.put(id, new GameplayDefinitions.Miracle(id, id - 3, id));
    GameplayDefinitions.miracleEffects.put(
        id,
        new GameplayDefinitions.MiracleEffect(
            id, Arrays.stream(values).mapToObj(GameplayDefinitions.EffectValue::new).toList()));
  }

  GameplayState.RogueRun run() {
    return fixture.run();
  }

  void pathReward(int path) {
    assertEquals(1, run().getBlessings().size());
    var entry = run().getBlessings().entrySet().iterator().next();
    assertEquals(1, entry.getValue());
    assertEquals(
        path,
        GameplayDefinitions.blessings.stream()
            .filter(b -> b.MazeBuffID() == entry.getKey())
            .findFirst()
            .orElseThrow()
            .RogueBuffType());
    assertNotNull(GameData.getMazeBuffExcelMap().get(entry.getKey(), 1));
    assertTrue(fixture.p().getGameplay().getRogueCollectedBuffs().contains(entry.getKey()));
  }

  void victoryMenu() {
    var room = GameplayDefinitions.rooms.get(run().getRoomId());
    GameplayDefinitions.rooms.put(
        room.RogueRoomID(),
        new GameplayDefinitions.Room(
            room.RogueRoomID(), 1, room.MapEntrance(), room.GroupID(), room.GroupWithContent()));
    run().getRerollCosts().addAll(List.of(0, 0));
    run().clear();
    assertEquals(3, fixture.p().getUniverseManager().offerBlessings().size());
  }

  @Test
  void allSevenFirewaxesGrantExactlyOneCorrespondingPathWithoutDowngradingOwnedBlessings() {
    for (int id = 22; id <= 28; id++) {
      effect(id, 0);
      run().getBlessings().clear();
      assertTrue(RogueMiracles.acquire(fixture.p(), id));
      pathReward(PATHS[id - 22]);
      var owned = Map.copyOf(run().getBlessings());
      assertFalse(RogueMiracles.acquire(fixture.p(), id));
      assertEquals(owned, run().getBlessings());
      assertEquals(List.of(0.0), run().getMiracles().get(id).getEffectParameters());
      assertEquals(0, run().getMiracles().get(id).getTotalCount());
    }
  }

  @Test
  void nativeMerchantPurchaseAndPendingSelectionGrantTheSamePathAndRejectRetries()
      throws Exception {
    assertEquals(List.of(22), fixture.quotes().stream().map(RogueMiracleShop.Quote::id).toList());
    var response =
        fixture.call(
            5656,
            fixture.request(UniverseMiracleShopTest.ENTITY, 22, false),
            "BuyRogueShopMiracleScRsp");
    assertEquals(0, Gameplay46.number(response, "retcode"));
    pathReward(120);
    assertEquals(100, run().getCoins());
    var before = Map.copyOf(run().getBlessings());
    assertNotEquals(Retcode.SUCC, fixture.buy(22));
    assertEquals(before, run().getBlessings());
    effect(23, 0);
    run().getBlessings().clear();
    run().offerMiracles(List.of(23));
    var session = fixture.merchant.fixture.s;
    var request = Gameplay46.builder(session, "OFEDNNJIJFL");
    var choice = Gameplay46.builder(session, "JINFLJMPPHN");
    Gameplay46.set(choice, "OJPGDDFJCHB", 23);
    Gameplay46.set(request, "MPLGEEGOAMC", run().getSelectionId());
    Gameplay46.set(request, "CCCIFGFHGIL", choice.build());
    assertTrue(VersionedPacketHandler.handle(session, 5676, request.build().toByteArray()));
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    pathReward(126);
    assertFalse(run().pending());
    before = Map.copyOf(run().getBlessings());
    assertTrue(VersionedPacketHandler.handle(session, 5676, request.build().toByteArray()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    assertEquals(before, run().getBlessings());
    assertEquals(100, run().getCoins());
  }

  @Test
  void disappearingTargetPathClosesTheSavedQuoteWithoutChargingOrBorrowingAnotherPath() {
    assertTrue(fixture.quotes().getFirst().available());
    GameplayDefinitions.blessings.removeIf(b -> b.RogueBuffType() == 120);
    var quote = fixture.quotes().getFirst();
    assertEquals(200, quote.cost());
    assertFalse(quote.available());
    assertEquals(Retcode.REQ_PARA_INVALID, fixture.buy(22));
    assertEquals(300, run().getCoins());
    assertTrue(run().getMiracles().isEmpty());
    assertTrue(run().getBlessings().isEmpty());
  }

  @Test
  void noEligiblePathDoesNotOfferTheCurioOrDowngradeExistingBlessings() {
    GameplayDefinitions.blessings.removeIf(b -> b.RogueBuffType() != 120);
    for (var buff : GameplayDefinitions.blessings) run().getBlessings().put(buff.MazeBuffID(), 2);
    var before = Map.copyOf(run().getBlessings());
    assertTrue(fixture.quotes().isEmpty());
    assertFalse(RogueMiracles.acquire(fixture.p(), 22));
    assertEquals(before, run().getBlessings());
    assertEquals(300, run().getCoins());
  }

  @Test
  void onlyMatchedRowsAndTheSingleKnownZeroParameterAreAccepted() {
    assertTrue(RogueMiracles.supported(22));
    for (double[] invalid :
        List.of(
            new double[] {1}, new double[] {-1}, new double[] {0, 0}, new double[] {Double.NaN})) {
      effect(22, invalid);
      assertFalse(RogueMiracles.supported(22));
    }
    effect(22, 0);
    GameplayDefinitions.miracles.put(22, new GameplayDefinitions.Miracle(22, 19, 23));
    assertFalse(RogueMiracles.supported(22));
  }

  @Test
  void oldOwnershipAndTerminalRunsCannotAcquireAnotherImmediateBlessing() {
    run().getMiracles().put(22, new GameplayState.RogueMiracle(0));
    assertFalse(RogueMiracles.acquire(fixture.p(), 22));
    assertTrue(run().getBlessings().isEmpty());
    run().getMiracles().clear();
    run().finish(GameplayState.RogueRun.Phase.ABANDONED);
    assertFalse(RogueMiracles.canAcquire(fixture.p(), 22));
    assertFalse(RogueMiracles.acquire(fixture.p(), 22));
    assertTrue(run().getBlessings().isEmpty());
  }

  @Test
  void savedPurchaseAndCollectionPrecedeNativeImmediateEffectNotifications() throws Exception {
    fixture.quotes();
    int saved = fixture.p().saves;
    var notified = new TreeMap<Integer, Integer>();
    var observer =
        new Gameplay46HandlerTest.Session() {
          public Player getPlayer() {
            return fixture.p();
          }

          public void sendClientPacket(String name, byte[] bytes) {
            super.sendClientPacket(name, bytes);
            assertEquals(saved + 1, fixture.p().saves);
            assertEquals(100, run().getCoins());
            assertTrue(run().getMiracles().containsKey(22));
            if (name.equals("SyncRogueCommonActionResultScNotify")) {
              var detail =
                  (DynamicMessage)
                      Gameplay46.value(InitialData46Test.first(response, "CLGGKLNPLCK"), "detail");
              var field = detail.getDescriptorForType().findFieldByName("IFOOEPKGGAG");
              if (detail.hasField(field)) {
                var buff = (DynamicMessage) detail.getField(field);
                int id = Gameplay46.number(buff, "buff_id");
                assertTrue(fixture.p().getGameplay().getRogueCollectedBuffs().contains(id));
                notified.put(id, Gameplay46.number(buff, "HAGDKKBAAKK"));
              }
            }
          }
        };
    fixture.p().setSession(observer);
    var online = Player.class.getDeclaredField("loggedIn");
    online.setAccessible(true);
    online.setBoolean(fixture.p(), true);
    try {
      assertEquals(Retcode.SUCC, fixture.buy(22));
      pathReward(120);
      assertEquals(run().getBlessings(), notified);
    } finally {
      online.setBoolean(fixture.p(), false);
    }
  }

  @Test
  void victoryDrawsFavorTheHeldPathWithoutRepeatingCandidates() throws Exception {
    GameplayDefinitions.blessings.removeIf(b -> !Set.of(120, 124).contains(b.RogueBuffType()));
    var prior = run();
    var room = GameplayDefinitions.rooms.get(prior.getRoomId());
    GameplayDefinitions.rooms.put(
        room.RogueRoomID(),
        new GameplayDefinitions.Room(
            room.RogueRoomID(), 1, room.MapEntrance(), room.GroupID(), room.GroupWithContent()));
    var field = GameplayState.RogueRun.class.getDeclaredField("runId");
    field.setAccessible(true);
    int uniform = 0, favored = 0;
    for (int seed = 0; seed < 500; seed++)
      for (boolean wax : List.of(false, true)) {
        var run = new GameplayState.RogueRun(prior.getAreaId(), prior.getMapId(), prior.getRooms());
        run.enter(prior.getSiteId(), prior.getRoomId());
        field.set(run, "firewax-weight-" + seed);
        if (wax) run.getMiracles().put(22, new GameplayState.RogueMiracle(0, List.of(0.0)));
        fixture.p().getGameplay().setRogue(run);
        run.clear();
        var offers = fixture.p().getUniverseManager().offerBlessings();
        assertEquals(3, offers.size());
        assertEquals(3, new HashSet<>(offers).size());
        int count = (int) offers.stream().filter(id -> id < BUFF + 6).count();
        if (wax) favored += count;
        else uniform += count;
      }
    assertTrue(
        favored > uniform + 100,
        "A supported firewax did not increase its path's victory probability: "
            + uniform
            + " / "
            + favored);
  }

  @Test
  void frozenWeightsSurviveResourceRemovalAndDoNotBorrowLaterAcquisitions() {
    assertTrue(RogueMiracles.acquire(fixture.p(), 22));
    victoryMenu();
    var weights = Map.copyOf(run().getOfferWeights());
    assertEquals(Set.copyOf(run().getOfferPool()), weights.keySet());
    weights.forEach((id, weight) -> assertEquals(id < BUFF + 6 ? 2 : 1, weight));
    run().getMiracles().remove(22);
    GameplayDefinitions.miracleEffects.remove(22);
    effect(24, 0);
    run().getMiracles().put(24, new GameplayState.RogueMiracle(0, List.of(0.0)));
    GameplayDefinitions.blessings.clear();
    assertEquals(
        Retcode.SUCC, fixture.p().getUniverseManager().rerollBlessings(run().getSelectionId()));
    assertEquals(weights, run().getOfferWeights());
    assertEquals(3, new HashSet<>(run().getOffers()).size());
    assertEquals(300, run().getCoins());
    assertTrue(run().validOfferLevels());
    assertEquals(
        Retcode.SUCC,
        fixture
            .p()
            .getUniverseManager()
            .selectBlessing(run().getSelectionId(), run().getOffers().getFirst()));
    assertTrue(run().getOfferWeights().isEmpty());
  }

  @Test
  void malformedFrozenWeightsRejectBothNativeSelectionAndRerollWithoutDebit() throws Exception {
    assertTrue(RogueMiracles.acquire(fixture.p(), 22));
    victoryMenu();
    var good = Map.copyOf(run().getOfferWeights());
    var owned = Map.copyOf(run().getBlessings());
    int id = run().getOfferPool().getFirst();
    for (int bad : List.of(0, 3, Integer.MAX_VALUE, -1)) {
      run().getOfferWeights().clear();
      run().getOfferWeights().putAll(good);
      if (bad == -1) run().getOfferWeights().remove(id);
      else run().getOfferWeights().put(id, bad);
      assertFalse(run().validOfferLevels());
      assertTrue(fixture.p().getUniverseManager().blessingRerollCost().isEmpty());
      assertEquals(
          Retcode.REQ_PARA_INVALID,
          fixture.p().getUniverseManager().rerollBlessings(run().getSelectionId()));
      var session = fixture.merchant.fixture.s;
      var req = Gameplay46.builder(session, "OFEDNNJIJFL");
      Gameplay46.set(req, "MPLGEEGOAMC", run().getSelectionId());
      var choice = Gameplay46.builder(session, "BPLNKHGAILK");
      Gameplay46.set(choice, "BAOFIGFFMLN", run().getOffers().getFirst());
      Gameplay46.set(req, "DLKIKBIDJDB", choice.build());
      assertTrue(VersionedPacketHandler.handle(session, 5676, req.build().toByteArray()));
      assertEquals(
          Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
      assertEquals(owned, run().getBlessings());
      assertEquals(300, run().getCoins());
      assertTrue(run().pending());
    }
  }

  @Test
  void concurrentMerchantPurchaseHasOnePathRewardAndOneFragmentDebit() throws Exception {
    fixture.quotes();
    try (var workers = Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<Callable<Retcode>>();
      for (int i = 0; i < 6; i++) calls.add(() -> fixture.buy(22));
      int successes = 0;
      for (var response : workers.invokeAll(calls))
        if (response.get() == Retcode.SUCC) successes++;
        else assertEquals(Retcode.REQ_PARA_INVALID, response.get());
      assertEquals(1, successes);
    }
    pathReward(120);
    assertEquals(100, run().getCoins());
  }

  @Test
  void actualMorphiaReloadPreservesImmediateRewardWeightsAndLegacyUniformMenus() throws Exception {
    fixture.quotes();
    assertEquals(Retcode.SUCC, fixture.buy(22));
    pathReward(120);
    var immediate = Map.copyOf(run().getBlessings());
    victoryMenu();
    var weights = Map.copyOf(run().getOfferWeights());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "firewax");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456769);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, fixture.p().getGameplay());
      store.save(document);
      gameplay.set(fixture.p(), store.find(Player.class).first().getGameplay());
      assertEquals(weights, run().getOfferWeights());
      assertEquals(immediate, run().getBlessings());
      assertEquals(List.of(0.0), run().getMiracles().get(22).getEffectParameters());
      GameplayDefinitions.miracleEffects.remove(22);
      assertEquals(
          Retcode.SUCC, fixture.p().getUniverseManager().rerollBlessings(run().getSelectionId()));
      assertEquals(weights, run().getOfferWeights());
      assertEquals(immediate, run().getBlessings());
      assertNotEquals(Retcode.SUCC, fixture.buy(22));
      assertEquals(100, run().getCoins());
      var pool = List.copyOf(run().getOfferPool());
      var levels = Map.copyOf(run().getOfferLevels());
      run().offer(pool.subList(0, 3), pool, List.of(0), levels);
      assertTrue(run().getOfferWeights().isEmpty());
      assertTrue(run().validOfferLevels());
      gameplay.set(document, fixture.p().getGameplay());
      store.save(document);
      var restored = store.find(Player.class).first().getGameplay().getRogue();
      assertTrue(restored.getOfferWeights().isEmpty());
      assertEquals(immediate, restored.getBlessings());
      assertEquals(1, restored.offerWeight(pool.getFirst()));
      assertEquals(100, restored.getCoins());
    } finally {
      mongo.shutdown();
    }
  }
}
