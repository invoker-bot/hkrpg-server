package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseChaosTrametes46Test {
  final UniverseReroll46Test fixture = new UniverseReroll46Test();
  GameplayDefinitions.Miracle priorMiracle;
  GameplayDefinitions.MiracleEffect priorEffect;

  @BeforeEach
  void setup() {
    priorMiracle = GameplayDefinitions.miracles.get(2);
    priorEffect = GameplayDefinitions.miracleEffects.get(2);
    fixture.setup();
    effect(1);
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameplayDefinitions.miracles.remove(2);
    GameplayDefinitions.miracleEffects.remove(2);
    if (priorMiracle != null) GameplayDefinitions.miracles.put(2, priorMiracle);
    if (priorEffect != null) GameplayDefinitions.miracleEffects.put(2, priorEffect);
  }

  void effect(double count) {
    GameplayDefinitions.miracles.put(2, new GameplayDefinitions.Miracle(2, 2, 2));
    GameplayDefinitions.miracleEffects.put(
        2,
        new GameplayDefinitions.MiracleEffect(
            2, List.of(new GameplayDefinitions.EffectValue(count))));
  }

  GameplayState.RogueRun run() {
    return fixture.s.explorer.getGameplay().getRogue();
  }

  void begin(boolean paid) throws Exception {
    if (!paid) fixture.s.explorer.getGameplay().getRogueTalents().clear();
    assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 2));
    run().clear();
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
  }

  int cost() {
    var costs = (DynamicMessage) Gameplay46.value(fixture.offer(), "DEHJDNIEKHB");
    var pile =
        (DynamicMessage) Gameplay46.value(InitialData46Test.first(costs, "item_list"), "pile_item");
    assertEquals(31, Gameplay46.number(pile, "item_id"));
    return Gameplay46.number(pile, "item_num");
  }

  @Test
  void nativeFreeRerollWorksWithoutThePaidTalentAndDoesNotDebitFragments() throws Exception {
    begin(false);
    assertEquals(List.of(0), run().getOfferRerollCosts());
    assertEquals(0, cost());
    assertEquals(1, Gameplay46.number(fixture.offer(), "EGFOLKEPNCI"));
    var first = List.copyOf(run().getOffers());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(100, run().getCoins());
    assertEquals(1, run().getOfferRerolls());
    assertTrue(Collections.disjoint(first, run().getOffers()));
    assertEquals(false, Gameplay46.value(fixture.offer(), "can_roll"));
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(100, run().getCoins());
  }

  @Test
  void freeAndPaidOpportunitiesCoexistAndOnlyThePaidOneDebitsTheSavedPrice() throws Exception {
    begin(true);
    assertEquals(List.of(0, 30), run().getOfferRerollCosts());
    assertEquals(2, Gameplay46.number(fixture.offer(), "EGFOLKEPNCI"));
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(100, run().getCoins());
    assertEquals(30, cost());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(70, run().getCoins());
    assertEquals(2, run().getOfferRerolls());
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(fixture.roll(null), "retcode"));
  }

  @Test
  void changingOrRemovingResourcesCannotRefillOrRepriceAFrozenMenu() throws Exception {
    begin(true);
    effect(2);
    GameplayDefinitions.miracleEffects.remove(2);
    GameplayDefinitions.miracles.remove(2);
    GameplayDefinitions.rogueConstants.put("Roll_Buff_Cost", "[31:1]");
    assertEquals(List.of(0, 30), run().getOfferRerollCosts());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(30, cost());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(70, run().getCoins());
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(fixture.roll(null), "retcode"));
  }

  @Test
  void obtainingTheCurioAfterAMenuDoesNotRewriteItButTheNextRoomReceivesItsFreeCount()
      throws Exception {
    fixture.begin();
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 2));
    assertEquals(List.of(30), run().getOfferRerollCosts());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(70, run().getCoins());
    assertEquals(
        Retcode.SUCC,
        fixture
            .s
            .explorer
            .getUniverseManager()
            .selectBlessing(run().getSelectionId(), run().getOffers().getFirst()));
    run().enter(2, run().getRoomId());
    run().clear();
    fixture.s.explorer.getUniverseManager().offerBlessings();
    assertEquals(List.of(0, 30), run().getOfferRerollCosts());
    assertEquals(0, run().getOfferRerolls());
    assertEquals(0, cost());
  }

  @Test
  void oldOrInvalidSavedCuriosDoNotInventFreeOpportunities() throws Exception {
    for (var record :
        List.of(
            new GameplayState.RogueMiracle(0),
            new GameplayState.RogueMiracle(0, List.of(1.5)),
            new GameplayState.RogueMiracle(1, List.of(1.0)),
            new GameplayState.RogueMiracle(0, List.of(1.0, 5.0)))) {
      assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
      run().getMiracles().put(2, record);
      run().clear();
      fixture.s.explorer.getUniverseManager().offerBlessings();
      assertEquals(List.of(30), run().getOfferRerollCosts());
      assertTrue(run().getMiracles().containsKey(2));
      assertEquals(Retcode.SUCC, fixture.s.explorer.getUniverseManager().leave(false));
      assertEquals(
          Retcode.SUCC, fixture.s.explorer.getUniverseManager().quit(Universe46LifecycleTest.AREA));
    }
  }

  @Test
  void concurrentRequestsConsumeTheSingleFreeOpportunityOnlyOnce() throws Exception {
    begin(false);
    try (var pool = Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<Callable<Retcode>>();
      int batch = run().getSelectionId();
      for (int i = 0; i < 6; i++)
        tasks.add(() -> fixture.s.explorer.getUniverseManager().rerollBlessings(batch));
      int succeeded = 0;
      for (var result : pool.invokeAll(tasks)) {
        var code = result.get();
        if (code == Retcode.SUCC) succeeded++;
        else assertEquals(Retcode.REQ_PARA_INVALID, code);
      }
      assertEquals(1, succeeded);
    }
    assertEquals(100, run().getCoins());
    assertEquals(1, run().getOfferRerolls());
  }

  @Test
  void actualMorphiaReloadPreservesUsedFreeCountAndOnlyTheRemainingPaidOpportunity()
      throws Exception {
    begin(true);
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    var first = List.copyOf(run().getOffers());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "universe-chaos-trametes");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var doc = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(doc, 123456795);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(doc, fixture.s.explorer.getGameplay());
      store.save(doc);
      gameplay.set(fixture.s.explorer, store.find(Player.class).first().getGameplay());
      GameplayDefinitions.miracleEffects.remove(2);
      GameplayDefinitions.miracles.remove(2);
      assertEquals(List.of(1.0), run().getMiracles().get(2).getEffectParameters());
      assertEquals(1, run().getOfferRerolls());
      assertEquals(List.of(0, 30), run().getOfferRerollCosts());
      assertEquals(first, run().getOffers());
      assertEquals(30, cost());
      assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
      assertEquals(70, run().getCoins());
      gameplay.set(doc, fixture.s.explorer.getGameplay());
      store.save(doc);
      gameplay.set(fixture.s.explorer, store.find(Player.class).first().getGameplay());
      assertEquals(false, Gameplay46.value(fixture.offer(), "can_roll"));
      assertEquals(
          Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(fixture.roll(null), "retcode"));
      assertEquals(70, run().getCoins());
    } finally {
      mongo.shutdown();
    }
  }

  @Test
  void noAlternativePoolAndRepeatedQueriesDoNotConsumeOrRefillTheFreeOpportunity()
      throws Exception {
    GameplayDefinitions.blessings.removeIf(b -> b.MazeBuffID() > 19997303);
    begin(false);
    var first = List.copyOf(run().getOffers());
    assertEquals(false, Gameplay46.value(fixture.offer(), "can_roll"));
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(first, fixture.s.explorer.getUniverseManager().offerBlessings());
    assertEquals(List.of(0), run().getOfferRerollCosts());
    assertEquals(0, run().getOfferRerolls());
    assertEquals(100, run().getCoins());
  }
}
