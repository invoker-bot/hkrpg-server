package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseDimensionalDie46Test {
  final UniverseReroll46Test fixture = new UniverseReroll46Test();
  GameplayDefinitions.Miracle priorMiracle;
  GameplayDefinitions.MiracleEffect priorEffect;

  @BeforeEach
  void setup() {
    priorMiracle = GameplayDefinitions.miracles.get(1);
    priorEffect = GameplayDefinitions.miracleEffects.get(1);
    fixture.setup();
    effect(1, 1, 2);
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameplayDefinitions.miracles.remove(1);
    GameplayDefinitions.miracleEffects.remove(1);
    if (priorMiracle != null) GameplayDefinitions.miracles.put(1, priorMiracle);
    if (priorEffect != null) GameplayDefinitions.miracleEffects.put(1, priorEffect);
  }

  void effect(double... values) {
    GameplayDefinitions.miracles.put(1, new GameplayDefinitions.Miracle(1, 1, 1));
    GameplayDefinitions.miracleEffects.put(
        1,
        new GameplayDefinitions.MiracleEffect(
            1, Arrays.stream(values).mapToObj(GameplayDefinitions.EffectValue::new).toList()));
  }

  GameplayState.RogueRun run() {
    return fixture.s.explorer.getGameplay().getRogue();
  }

  void start() throws Exception {
    assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 1));
  }

  void victory() {
    run().clear();
    assertEquals(2, fixture.s.explorer.getUniverseManager().offerBlessings().size());
  }

  void counts(int current) {
    assertEquals(2, Gameplay46.number(fixture.offer(), "NKGGAOJLCMD"));
    assertEquals(current, Gameplay46.number(fixture.offer(), "EIALMEADLGF"));
  }

  DynamicMessage select(int batch, int id) throws Exception {
    var request = Gameplay46.builder(fixture.s, "OFEDNNJIJFL");
    Gameplay46.set(request, "MPLGEEGOAMC", batch);
    var choice = Gameplay46.builder(fixture.s, "BPLNKHGAILK");
    Gameplay46.set(choice, "BAOFIGFFMLN", id);
    Gameplay46.set(request, "DLKIKBIDJDB", choice.build());
    assertTrue(VersionedPacketHandler.handle(fixture.s, 5676, request.build().toByteArray()));
    assertEquals("HandleRogueCommonPendingActionScRsp", fixture.s.responseName);
    return fixture.s.response;
  }

  void finishChoices() {
    while (run().pending())
      assertEquals(
          Retcode.SUCC,
          fixture
              .s
              .explorer
              .getUniverseManager()
              .selectBlessing(run().getSelectionId(), run().getOffers().getFirst()));
  }

  @Test
  void twoNativeSelectionsKeepTheRemainingOptionAndRejectThePreviousBatch() throws Exception {
    start();
    assertEquals(2, run().getMiracles().get(1).getTotalCount());
    victory();
    counts(1);
    var offered = List.copyOf(run().getOffers());
    int batch = run().getSelectionId();
    assertEquals(0, Gameplay46.number(select(batch, offered.getFirst()), "retcode"));
    assertEquals(Map.of(offered.getFirst(), 1), run().getBlessings());
    assertEquals(List.of(offered.getLast()), run().getOffers());
    assertTrue(run().pending());
    assertEquals(batch + 1, run().getSelectionId());
    counts(2);
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        Gameplay46.number(select(batch, offered.getLast()), "retcode"));
    assertEquals(
        0, Gameplay46.number(select(run().getSelectionId(), offered.getLast()), "retcode"));
    assertFalse(run().pending());
    assertEquals(Set.copyOf(offered), run().getBlessings().keySet());
    assertEquals(1, run().getMiracles().get(1).getUseCount());
    assertEquals(100, run().getCoins());
  }

  @Test
  void theDieTriggersOncePerVictoryAndBreaksAfterTheSecondMenu() throws Exception {
    start();
    victory();
    var before = List.copyOf(run().getOffers());
    assertEquals(before, fixture.s.explorer.getUniverseManager().offerBlessings());
    assertEquals(1, run().getMiracles().get(1).getUseCount());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(1, run().getMiracles().get(1).getUseCount());
    finishChoices();
    run().enter(2, run().getRoomId());
    victory();
    assertEquals(2, run().getMiracles().get(1).getUseCount());
    assertTrue(run().getMiracles().get(1).broken());
    finishChoices();
    run().enter(3, run().getRoomId());
    run().clear();
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
    int batch = run().getSelectionId();
    assertEquals(
        Retcode.SUCC,
        fixture
            .s
            .explorer
            .getUniverseManager()
            .selectBlessing(batch, run().getOffers().getFirst()));
    assertFalse(run().pending());
    assertEquals(2, run().getMiracles().get(1).getUseCount());
  }

  @Test
  void lateAcquisitionDoesNotTurnAnExistingOrdinaryMenuIntoMultipleSelections() throws Exception {
    fixture.begin();
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 1));
    int id = run().getOffers().getFirst();
    assertEquals(
        Retcode.SUCC,
        fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), id));
    assertFalse(run().pending());
    assertEquals(0, run().getMiracles().get(1).getUseCount());
    run().enter(2, run().getRoomId());
    victory();
    counts(1);
  }

  @Test
  void aSingleEligibleCandidateDoesNotConsumeTheDieOrInventAnExtraAward() throws Exception {
    GameplayDefinitions.blessings.removeIf(b -> b.MazeBuffID() != 19997301);
    start();
    run().clear();
    assertEquals(List.of(19997301), fixture.s.explorer.getUniverseManager().offerBlessings());
    assertEquals(0, run().getMiracles().get(1).getUseCount());
    assertEquals(
        Retcode.SUCC,
        fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), 19997301));
    assertFalse(run().pending());
    assertEquals(Map.of(19997301, 1), run().getBlessings());
  }

  @Test
  void frozenParametersKeepBothChoicesAfterResourcesAndOwnershipChange() throws Exception {
    start();
    victory();
    var offered = List.copyOf(run().getOffers());
    GameplayDefinitions.miracles.remove(1);
    GameplayDefinitions.miracleEffects.remove(1);
    run().getMiracles().remove(1);
    assertEquals(
        Retcode.SUCC,
        fixture
            .s
            .explorer
            .getUniverseManager()
            .selectBlessing(run().getSelectionId(), offered.getFirst()));
    counts(2);
    assertEquals(
        Retcode.SUCC,
        fixture
            .s
            .explorer
            .getUniverseManager()
            .selectBlessing(run().getSelectionId(), offered.getLast()));
    assertEquals(Set.copyOf(offered), run().getBlessings().keySet());
    assertFalse(run().pending());
  }

  @Test
  void legacyMalformedLifetimeAndBrokenOwnedItemsDoNotCreateExtraChoices() throws Exception {
    var broken = new GameplayState.RogueMiracle(2, List.of(1.0, 1.0, 2.0));
    broken.consume();
    broken.consume();
    for (var item :
        List.of(
            new GameplayState.RogueMiracle(2),
            new GameplayState.RogueMiracle(0, List.of(1.0, 1.0, 2.0)),
            new GameplayState.RogueMiracle(1, List.of(1.0, 1.0, 2.0)),
            new GameplayState.RogueMiracle(2, List.of(1.0, 1.0, 3.0)),
            broken)) {
      assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
      run().getMiracles().put(1, item);
      run().clear();
      fixture.s.explorer.getUniverseManager().offerBlessings();
      var current = List.copyOf(run().getOffers());
      assertEquals(3, current.size());
      assertEquals(
          Retcode.SUCC,
          fixture
              .s
              .explorer
              .getUniverseManager()
              .selectBlessing(run().getSelectionId(), current.getFirst()));
      assertFalse(run().pending());
      fixture.s.explorer.getUniverseManager().leave(false);
      fixture.s.explorer.getUniverseManager().quit(Universe46LifecycleTest.AREA);
    }
  }

  @Test
  void acquisitionRequiresTheMatchedExactKnownThreeParameterVector() {
    assertTrue(RogueMiracles.supported(1));
    for (var invalid :
        List.of(
            new double[] {1, 1},
            new double[] {1, 1, 3},
            new double[] {2, 1, 2},
            new double[] {1, 2, 2},
            new double[] {1, 1, 2, 0},
            new double[] {Double.NaN, 1, 2})) {
      effect(invalid);
      assertFalse(RogueMiracles.supported(1));
    }
    effect(1, 1, 2);
    GameplayDefinitions.miracles.put(1, new GameplayDefinitions.Miracle(1, 1, 7));
    assertFalse(RogueMiracles.supported(1));
  }

  @Test
  void concurrentFirstSelectionsCannotSpendTheSecondChoiceWithTheOldBatch() throws Exception {
    start();
    victory();
    int batch = run().getSelectionId(), id = run().getOffers().getFirst();
    try (var workers = Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<Callable<Retcode>>();
      for (int i = 0; i < 6; i++)
        calls.add(() -> fixture.s.explorer.getUniverseManager().selectBlessing(batch, id));
      int successes = 0;
      for (var result : workers.invokeAll(calls)) {
        if (result.get() == Retcode.SUCC) successes++;
        else assertEquals(Retcode.REQ_PARA_INVALID, result.get());
      }
      assertEquals(1, successes);
    }
    assertTrue(run().pending());
    assertEquals(1, run().getBlessings().size());
    counts(2);
    finishChoices();
    assertEquals(2, run().getBlessings().size());
  }

  @Test
  void morphiaRestoreKeepsTheHalfCompletedMenuWithoutRegrantingTheFirstBlessing() throws Exception {
    start();
    victory();
    int first = run().getOffers().getFirst(), oldBatch = run().getSelectionId();
    assertEquals(
        Retcode.SUCC, fixture.s.explorer.getUniverseManager().selectBlessing(oldBatch, first));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "dimensional-die");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456765);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      var restored = store.find(Player.class).first();
      gameplay.set(fixture.s.explorer, restored.getGameplay());
      assertEquals(Map.of(first, 1), run().getBlessings());
      assertTrue(run().pending());
      counts(2);
      int second = run().getOffers().getFirst();
      assertEquals(
          Retcode.REQ_PARA_INVALID,
          fixture.s.explorer.getUniverseManager().selectBlessing(oldBatch, second));
      finishChoices();
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      restored = store.find(Player.class).first();
      assertEquals(
          Set.of(first, second), restored.getGameplay().getRogue().getBlessings().keySet());
      assertFalse(restored.getGameplay().getRogue().pending());
      assertEquals(1, restored.getGameplay().getRogue().getMiracles().get(1).getUseCount());
      assertEquals(100, restored.getGameplay().getRogue().getCoins());
    } finally {
      mongo.shutdown();
    }
  }

  @Test
  void corruptChoiceCountersOrAnIncompleteTwoChoiceMenuCannotGrantOrCharge() throws Exception {
    start();
    victory();
    int id = run().getOffers().getFirst(), batch = run().getSelectionId();
    var total = GameplayState.RogueRun.class.getDeclaredField("offerChoiceTotal");
    var index = GameplayState.RogueRun.class.getDeclaredField("offerChoiceIndex");
    var selection = GameplayState.RogueRun.class.getDeclaredField("selectionId");
    total.setAccessible(true);
    index.setAccessible(true);
    selection.setAccessible(true);
    for (var invalid :
        List.of(
            new int[] {3, 1},
            new int[] {2, 3},
            new int[] {-1, 1},
            new int[] {2, 0},
            new int[] {0, 1})) {
      total.setInt(run(), invalid[0]);
      index.setInt(run(), invalid[1]);
      assertEquals(
          Retcode.REQ_PARA_INVALID,
          fixture.s.explorer.getUniverseManager().selectBlessing(batch, id));
      assertEquals(
          Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(fixture.roll(null), "retcode"));
      assertTrue(run().getBlessings().isEmpty());
      assertEquals(100, run().getCoins());
    }
    total.setInt(run(), 2);
    index.setInt(run(), 1);
    selection.setInt(run(), Integer.MAX_VALUE);
    assertEquals(
        Retcode.REQ_PARA_INVALID,
        fixture.s.explorer.getUniverseManager().selectBlessing(Integer.MAX_VALUE, id));
    selection.setInt(run(), batch);
    run().getOffers().remove(1);
    assertEquals(
        Retcode.REQ_PARA_INVALID,
        fixture.s.explorer.getUniverseManager().selectBlessing(batch, id));
    assertTrue(run().getBlessings().isEmpty());
    assertEquals(100, run().getCoins());
  }

  @Test
  void theSecondNativePendingMenuIsSentAfterSavingTheFirstSelectedBlessing() throws Exception {
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
    assertTrue(RogueMiracles.acquire(actor, 1));
    int savesBeforeMenu = saves[0];
    var pending = new ArrayList<DynamicMessage>();
    var session =
        new Gameplay46HandlerTest.Session() {
          public Player getPlayer() {
            return actor;
          }

          public void sendClientPacket(String name, byte[] bytes) {
            super.sendClientPacket(name, bytes);
            if (name.equals("SyncRogueCommonPendingActionScNotify")) {
              assertTrue(saves[0] >= savesBeforeMenu + 1);
              if (!pending.isEmpty()) {
                assertEquals(savesBeforeMenu + 2, saves[0]);
                assertEquals(1, run.getBlessings().size());
              }
              pending.add(response);
            }
          }
        };
    actor.setSession(session);
    var online = Player.class.getDeclaredField("loggedIn");
    online.setAccessible(true);
    online.setBoolean(actor, true);
    try {
      assertEquals(2, actor.getUniverseManager().offerBlessings().size());
      int batch = run.getSelectionId(), first = run.getOffers().getFirst();
      assertEquals(Retcode.SUCC, actor.getUniverseManager().selectBlessing(batch, first));
      assertEquals(2, pending.size());
      var action = (DynamicMessage) Gameplay46.value(pending.getLast(), "action");
      assertEquals(batch + 1, Gameplay46.number(action, "queue_position"));
      var detail = (DynamicMessage) Gameplay46.value(action, "detail");
      var info = (DynamicMessage) Gameplay46.value(detail, "PCHGEGFBKLF");
      assertEquals(2, Gameplay46.number(info, "NKGGAOJLCMD"));
      assertEquals(2, Gameplay46.number(info, "EIALMEADLGF"));
      assertEquals(1, ((List<?>) Gameplay46.value(info, "LEOFNINDJDB")).size());
      assertEquals(
          Retcode.REQ_PARA_INVALID,
          actor.getUniverseManager().selectBlessing(batch, run.getOffers().getFirst()));
      assertEquals(
          Retcode.SUCC,
          actor
              .getUniverseManager()
              .selectBlessing(run.getSelectionId(), run.getOffers().getFirst()));
      assertEquals(savesBeforeMenu + 3, saves[0]);
      assertEquals(2, pending.size());
      assertEquals(2, run.getBlessings().size());
      assertFalse(run.pending());
    } finally {
      online.setBoolean(actor, false);
    }
  }

  @Test
  void freeRefreshBudgetIsSharedAcrossBothChoicesAndDoesNotRefillAtTheSecond() throws Exception {
    start();
    run().getMiracles().put(2, new GameplayState.RogueMiracle(0, List.of(1.0)));
    victory();
    assertEquals(List.of(0, 30), run().getOfferRerollCosts());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(
        Retcode.SUCC,
        fixture
            .s
            .explorer
            .getUniverseManager()
            .selectBlessing(run().getSelectionId(), run().getOffers().getFirst()));
    assertTrue(run().pending());
    assertEquals(1, run().getOfferRerolls());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(70, run().getCoins());
    finishChoices();
    assertEquals(2, run().getBlessings().size());
    assertEquals(1, run().getMiracles().get(1).getUseCount());
  }
}
