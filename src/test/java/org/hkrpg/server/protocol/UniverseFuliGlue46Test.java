package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseFuliGlue46Test {
  final UniverseReroll46Test fixture = new UniverseReroll46Test();
  GameplayDefinitions.Miracle priorMiracle;
  GameplayDefinitions.MiracleEffect priorEffect;

  @BeforeEach
  void setup() {
    priorMiracle = GameplayDefinitions.miracles.get(7);
    priorEffect = GameplayDefinitions.miracleEffects.get(7);
    fixture.setup();
    effect(1);
    GameplayDefinitions.blessings.replaceAll(
        b ->
            new GameplayDefinitions.Blessing(
                b.MazeBuffID(),
                b.MazeBuffLevel(),
                100,
                b.MazeBuffID() > 19997303 ? "Legendary" : "Common",
                1,
                List.of()));
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameplayDefinitions.miracles.remove(7);
    GameplayDefinitions.miracleEffects.remove(7);
    if (priorMiracle != null) GameplayDefinitions.miracles.put(7, priorMiracle);
    if (priorEffect != null) GameplayDefinitions.miracleEffects.put(7, priorEffect);
  }

  void effect(double value) {
    GameplayDefinitions.miracles.put(7, new GameplayDefinitions.Miracle(7, 7, 7));
    GameplayDefinitions.miracleEffects.put(
        7,
        new GameplayDefinitions.MiracleEffect(
            7, List.of(new GameplayDefinitions.EffectValue(value))));
  }

  GameplayState.RogueRun run() {
    return fixture.s.explorer.getGameplay().getRogue();
  }

  void start() throws Exception {
    assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 7));
  }

  void victory() {
    run().clear();
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
  }

  void legendary() {
    assertEquals(3, run().getOffers().size());
    assertTrue(run().getOfferPool().stream().allMatch(id -> id > 19997303));
    for (var row : (List<?>) Gameplay46.value(fixture.offer(), "LEOFNINDJDB")) {
      var buff = (DynamicMessage) row;
      assertTrue(Gameplay46.number(buff, "buff_id") > 19997303);
      assertEquals(1, Gameplay46.number(buff, "HAGDKKBAAKK")); // Rarity is not enhancement level.
    }
  }

  @Test
  void victoryFreezesOnlyLegendaryCandidatesConsumesOneUseAndLeavesTheNextRoomOrdinary()
      throws Exception {
    start();
    var item = run().getMiracles().get(7);
    assertEquals(1, item.getTotalCount());
    assertEquals(0, item.getUseCount());
    victory();
    legendary();
    assertEquals(1, item.getUseCount());
    assertTrue(item.broken());
    var current = List.copyOf(run().getOffers());
    assertEquals(current, fixture.s.explorer.getUniverseManager().offerBlessings());
    assertEquals(1, item.getUseCount());
    assertEquals(
        Retcode.SUCC,
        fixture
            .s
            .explorer
            .getUniverseManager()
            .selectBlessing(run().getSelectionId(), current.getFirst()));
    assertEquals(1, run().getBlessings().get(current.getFirst()));
    run().enter(2, run().getRoomId()); // State fixture, not a real portal flow.
    victory();
    assertTrue(run().getOfferPool().contains(19997301));
    assertEquals(1, item.getUseCount());
    assertEquals(100, run().getCoins());
  }

  @Test
  void refreshUsesTheFrozenLegendaryPoolAfterBreakageWithoutAnotherDurabilityCharge()
      throws Exception {
    start();
    victory();
    var first = List.copyOf(run().getOffers());
    assertTrue(run().getMiracles().get(7).broken());
    GameplayDefinitions.blessings.replaceAll(
        b -> new GameplayDefinitions.Blessing(b.MazeBuffID(), 1, 100, "Common", 1, List.of()));
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    legendary();
    assertTrue(Collections.disjoint(first, run().getOffers()));
    assertEquals(1, run().getMiracles().get(7).getUseCount());
    assertEquals(70, run().getCoins());
  }

  @Test
  void unavailableLegendaryPoolDoesNotConsumeDurabilityOrInventLowerRarityOptions()
      throws Exception {
    GameplayDefinitions.blessings.removeIf(b -> "Legendary".equals(b.RogueBuffCategory()));
    start();
    run().clear();
    assertTrue(fixture.s.explorer.getUniverseManager().offerBlessings().isEmpty());
    assertEquals(0, run().getMiracles().get(7).getUseCount());
    assertFalse(run().getMiracles().get(7).broken());
    assertFalse(run().pending());
    assertEquals(100, run().getCoins());
  }

  @Test
  void lateAcquisitionKeepsTheExistingOrdinaryMenuAndTriggersAtTheNextVictory() throws Exception {
    fixture.begin();
    var first = List.copyOf(run().getOffers());
    assertTrue(run().getOfferPool().contains(19997301));
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 7));
    assertEquals(first, fixture.s.explorer.getUniverseManager().offerBlessings());
    assertEquals(0, run().getMiracles().get(7).getUseCount());
    assertEquals(
        Retcode.SUCC,
        fixture
            .s
            .explorer
            .getUniverseManager()
            .selectBlessing(run().getSelectionId(), first.getFirst()));
    run().enter(2, run().getRoomId());
    victory();
    legendary();
    assertTrue(run().getMiracles().get(7).broken());
  }

  @Test
  void legacyMismatchedLifetimeAndAlreadyBrokenItemsKeepTheOrdinaryPool() throws Exception {
    var broken = new GameplayState.RogueMiracle(1, List.of(1.0));
    assertTrue(broken.consume());
    for (var item :
        List.of(
            new GameplayState.RogueMiracle(1),
            new GameplayState.RogueMiracle(0, List.of(1.0)),
            new GameplayState.RogueMiracle(2, List.of(1.0)),
            new GameplayState.RogueMiracle(1, List.of(1.0, 1.0)),
            broken)) {
      assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
      run().getMiracles().put(7, item);
      int used = item.getUseCount();
      victory();
      assertTrue(run().getOfferPool().contains(19997301));
      assertEquals(used, item.getUseCount());
      assertSame(item, run().getMiracles().get(7));
      fixture.s.explorer.getUniverseManager().leave(false);
      fixture.s.explorer.getUniverseManager().quit(Universe46LifecycleTest.AREA);
    }
  }

  @Test
  void frozenAcquisitionStillTriggersAfterItsCurrentResourceRowsAreRemoved() throws Exception {
    start();
    GameplayDefinitions.miracles.remove(7);
    GameplayDefinitions.miracleEffects.remove(7);
    victory();
    legendary();
    assertEquals(List.of(1.0), run().getMiracles().get(7).getEffectParameters());
    assertTrue(run().getMiracles().get(7).broken());
  }

  @Test
  void acquisitionAcceptsOnlyTheMatchedKnownSingleLifetimeParameter() {
    assertTrue(RogueMiracles.supported(7));
    for (double invalid : new double[] {0, -1, 0.5, 2, Double.NaN, Double.POSITIVE_INFINITY}) {
      effect(invalid);
      assertFalse(RogueMiracles.supported(7));
    }
    effect(1);
    GameplayDefinitions.miracles.put(7, new GameplayDefinitions.Miracle(7, 7, 3));
    assertFalse(RogueMiracles.supported(7));
    effect(1);
    GameplayDefinitions.miracleEffects.put(
        7,
        new GameplayDefinitions.MiracleEffect(3, List.of(new GameplayDefinitions.EffectValue(1))));
    assertFalse(RogueMiracles.supported(7));
    effect(1);
    GameplayDefinitions.miracleEffects.put(
        7,
        new GameplayDefinitions.MiracleEffect(
            7,
            List.of(
                new GameplayDefinitions.EffectValue(1), new GameplayDefinitions.EffectValue(1))));
    assertFalse(RogueMiracles.supported(7));
  }

  @Test
  void concurrentMenuRequestsReuseOneSavedMenuAndConsumeOneLifetime() throws Exception {
    start();
    run().clear();
    try (var workers = Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<Callable<List<Integer>>>();
      for (int i = 0; i < 6; i++)
        calls.add(() -> fixture.s.explorer.getUniverseManager().offerBlessings());
      for (var result : workers.invokeAll(calls)) assertEquals(run().getOffers(), result.get());
    }
    legendary();
    assertEquals(1, run().getSelectionId());
    assertEquals(1, run().getMiracles().get(7).getUseCount());
  }

  @Test
  void morphiaRestoreKeepsTheBrokenItemAndLegendaryMenuWithoutReconsumingIt() throws Exception {
    start();
    victory();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "fuli-glue");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456766);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      var restored = store.find(Player.class).first();
      gameplay.set(fixture.s.explorer, restored.getGameplay());
      var before = List.copyOf(run().getOffers());
      assertEquals(before, fixture.s.explorer.getUniverseManager().offerBlessings());
      assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
      legendary();
      assertEquals(1, run().getMiracles().get(7).getUseCount());
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      restored = store.find(Player.class).first();
      assertTrue(restored.getGameplay().getRogue().getMiracles().get(7).broken());
      assertEquals(1, restored.getGameplay().getRogue().getOfferRerolls());
      assertEquals(70, restored.getGameplay().getRogue().getCoins());
      assertTrue(
          restored.getGameplay().getRogue().getOfferPool().stream().allMatch(id -> id > 19997303));
    } finally {
      mongo.shutdown();
    }
  }

  @Test
  void glueAndTheOtherMenuMiraclesKeepRarityLevelAndFreeCostsDistinct() throws Exception {
    start();
    run().getMiracles().put(2, new GameplayState.RogueMiracle(0, List.of(1.0)));
    run().getMiracles().put(3, new GameplayState.RogueMiracle(0, List.of(1.0)));
    victory();
    legendary();
    assertEquals(List.of(0, 30), run().getOfferRerollCosts());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    legendary();
    assertEquals(100, run().getCoins());
    assertEquals(1, run().getMiracles().get(7).getUseCount());
    assertEquals(0, run().getMiracles().get(2).getUseCount());
    assertEquals(0, run().getMiracles().get(3).getUseCount());
  }

  @Test
  void initialExtraAndUnfinishedOrFailedRoomQueriesDoNotTriggerTheGlue() throws Exception {
    start();
    assertTrue(fixture.s.explorer.getUniverseManager().offerBlessings().isEmpty());
    var method =
        UniverseManager.class.getDeclaredMethod(
            "createBlessingOffer", GameplayState.RogueRun.class, Set.class, boolean.class);
    method.setAccessible(true);
    method.invoke(fixture.s.explorer.getUniverseManager(), run(), null, false);
    assertTrue(run().getOfferPool().contains(19997301));
    assertEquals(0, run().getMiracles().get(7).getUseCount());
    run().finish(GameplayState.RogueRun.Phase.FAILED);
    assertTrue(fixture.s.explorer.getUniverseManager().offerBlessings().isEmpty());
    assertEquals(0, run().getMiracles().get(7).getUseCount());
  }

  @Test
  void nativeBreakageNotificationObservesTheSavedMenuAndConsumedLifetime() throws Exception {
    int[] saves = {0};
    var actor =
        new Player() {
          public void save() {
            saves[0]++;
          }
        };
    var run = new GameplayState.RogueRun(100, Universe46LifecycleTest.MAP, Map.of());
    run.enter(1, Universe46LifecycleTest.ROOM);
    run.clear();
    actor.getGameplay().setRogue(run);
    assertTrue(RogueMiracles.acquire(actor, 7));
    int savesBeforeMenu = saves[0];
    var notifications = new ArrayList<DynamicMessage>();
    var session =
        new Gameplay46HandlerTest.Session() {
          public Player getPlayer() {
            return actor;
          }

          public void sendClientPacket(String name, byte[] bytes) {
            super.sendClientPacket(name, bytes);
            if (name.equals("SyncRogueCommonActionResultScNotify")) {
              assertEquals(savesBeforeMenu + 1, saves[0]);
              assertEquals(3, run.getOffers().size());
              assertTrue(run.getMiracles().get(7).broken());
              notifications.add(response);
            }
          }
        };
    actor.setSession(session);
    var online = Player.class.getDeclaredField("loggedIn");
    online.setAccessible(true);
    online.setBoolean(actor, true);
    try {
      assertEquals(3, actor.getUniverseManager().offerBlessings().size());
      assertEquals(1, notifications.size());
      var detail =
          (DynamicMessage)
              Gameplay46.value(
                  InitialData46Test.first(notifications.getFirst(), "CLGGKLNPLCK"), "detail");
      var miracle =
          (DynamicMessage)
              Gameplay46.value((DynamicMessage) Gameplay46.value(detail, "KDJBDMLDMHG"), "miracle");
      assertEquals(7, Gameplay46.number(miracle, "LJCEOGPOGBE"));
      assertEquals(1, Gameplay46.number(miracle, "DGGCFDKJDPO"));
      assertEquals(1, Gameplay46.number(miracle, "total_count"));
      actor.getUniverseManager().offerBlessings();
      assertEquals(1, notifications.size());
    } finally {
      online.setBoolean(actor, false);
    }
  }
}
