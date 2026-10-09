package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseBrokenClock46Test {
  final UniverseEnhance46Test fixture = new UniverseEnhance46Test();
  final Gson gson = new Gson();
  final Map<Integer, GameplayDefinitions.Miracle> oldMiracles = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();
  CapturingSession session;

  static class CapturingSession extends UniverseEnhance46Test.Session {
    final List<DynamicMessage> replacements = new ArrayList<>();
    final List<Integer> replacementSaves = new ArrayList<>();

    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      if (name.equals("SyncRogueCommonActionResultScNotify")) {
        var actions = (List<DynamicMessage>) Gameplay46.value(response, "CLGGKLNPLCK");
        if (actions.stream()
            .anyMatch(
                action -> {
                  var detail = (DynamicMessage) Gameplay46.value(action, "detail");
                  return detail.hasField(
                      detail.getDescriptorForType().findFieldByName("OMFLCEBLLMI"));
                })) {
          replacements.add(response);
          replacementSaves.add(explorer.saves);
        }
      }
    }
  }

  @BeforeEach
  void setup() throws Exception {
    for (int id : List.of(9, 70)) {
      oldMiracles.put(id, GameplayDefinitions.miracles.get(id));
      oldEffects.put(id, GameplayDefinitions.miracleEffects.get(id));
    }
    fixture.setup();
    session = new CapturingSession();
    fixture.s = session;
    fixture.begin();
    try (var input =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/rogue-broken-clock-4.6.json"),
            StandardCharsets.UTF_8)) {
      var rows = JsonParser.parseReader(input).getAsJsonObject().getAsJsonObject("rows");
      for (var row : rows.getAsJsonArray("RogueMiracle")) {
        var value = gson.fromJson(row, GameplayDefinitions.Miracle.class);
        GameplayDefinitions.miracles.put(value.MiracleID(), value);
      }
      for (var row : rows.getAsJsonArray("RogueMiracleEffect")) {
        var value = gson.fromJson(row, GameplayDefinitions.MiracleEffect.class);
        GameplayDefinitions.miracleEffects.put(value.MiracleEffectID(), value);
      }
    }
    assertTrue(RogueMiracles.acquire(player(), 70));
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    for (int id : List.of(9, 70)) {
      GameplayDefinitions.miracles.remove(id);
      GameplayDefinitions.miracleEffects.remove(id);
      if (oldMiracles.get(id) != null) GameplayDefinitions.miracles.put(id, oldMiracles.get(id));
      if (oldEffects.get(id) != null)
        GameplayDefinitions.miracleEffects.put(id, oldEffects.get(id));
    }
  }

  Player player() {
    return session.explorer;
  }

  GameplayState.RogueRun run() {
    return player().getGameplay().getRogue();
  }

  GameplayState.RogueMiracle clock() {
    return run().getMiracles().get(70);
  }

  void counter(long value) throws Exception {
    var field = GameplayState.RogueMiracle.class.getDeclaredField("extraFragmentsSpent");
    field.setAccessible(true);
    field.setLong(clock(), value);
  }

  Retcode paid(int cost, int extra) {
    return RogueMiracles.spend(
        run(),
        new GameplayState.RogueFragmentCost(
            cost, extra, extra == 0 ? "" : clock().getAcquisitionId()));
  }

  void replaceGameplay(GameplayState state) throws Exception {
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(player(), state);
  }

  void reload() throws Exception {
    replaceGameplay(gson.fromJson(gson.toJson(player().getGameplay()), GameplayState.class));
  }

  void online() throws Exception {
    player().setSession(session);
    var field = Player.class.getDeclaredField("loggedIn");
    field.setAccessible(true);
    field.setBoolean(player(), true);
  }

  void snapshot(String value) throws Exception {
    var data = JsonParser.parseString(gson.toJson(clock())).getAsJsonObject();
    data.add("clockBreakParameters", JsonParser.parseString(value));
    run().getMiracles().put(70, gson.fromJson(data, GameplayState.RogueMiracle.class));
  }

  void transformed(int coins) {
    assertFalse(run().getMiracles().containsKey(70), "The source company clock was not replaced");
    var broken = run().getMiracles().get(9);
    assertNotNull(broken, "The broken clock is missing");
    assertEquals(List.of(499.0, 999.0), broken.getEffectParameters());
    assertFalse(broken.getAcquisitionId().isBlank());
    assertEquals(0, broken.getTotalCount());
    assertEquals(0, broken.getUseCount());
    assertEquals(coins, run().getCoins());
    assertEquals(1, run().getMiracleFragmentGrants().size());
    var receipt = run().getMiracleFragmentGrants().values().iterator().next();
    assertTrue(receipt.isSettled());
    assertEquals(999, receipt.getFragments());
  }

  @Test
  void companyAcquisitionFreezesTheOriginalBrokenClockParameters() {
    var data = JsonParser.parseString(gson.toJson(clock())).getAsJsonObject();
    assertTrue(data.has("clockBreakParameters"), "No frozen break parameters were saved");
    assertEquals(JsonParser.parseString("[499.0,999.0]"), data.get("clockBreakParameters"));
    assertFalse(RogueMiracles.canAcquire(player(), 9));
    assertFalse(RogueMiracles.acquire(player(), 9));
  }

  @Test
  void projectStrictThresholdKeepsTheCompanyClockAt499ExtraFragments() throws Exception {
    counter(498);
    assertEquals(Retcode.SUCC, paid(5, 1));
    assertEquals(499, clock().getExtraFragmentsSpent());
    assertEquals(295, run().getCoins());
    assertFalse(run().getMiracles().containsKey(9));
  }

  @Test
  void crossingTheThresholdReplacesAndCredits999Once() throws Exception {
    counter(499);
    assertEquals(Retcode.SUCC, paid(5, 1));
    transformed(1294);
    assertFalse(RogueMiracles.canAcquire(player(), 70));
    assertFalse(RogueMiracles.acquire(player(), 70));
    assertEquals(Retcode.SUCC, RogueMiracles.spend(run(), new GameplayState.RogueFragmentCost(5)));
    transformed(1289);
  }

  @Test
  void nativeEnhancementSavesConversionAndWalletBeforeOneReplacementNotification()
      throws Exception {
    online();
    counter(475);
    assertEquals(125, fixture.price(fixture.query()));
    int before = session.explorer.saves;
    assertEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    transformed(1174);
    assertEquals(2, run().getBlessings().get(UniverseEnhance46Test.BUFF));
    assertEquals(before + 1, session.explorer.saves);
    assertEquals(List.of(before + 1), session.replacementSaves);
    assertEquals(1, session.replacements.size());
    var action = InitialData46Test.first(session.replacements.getFirst(), "CLGGKLNPLCK");
    var detail = (DynamicMessage) Gameplay46.value(action, "detail");
    var replacement = (DynamicMessage) Gameplay46.value(detail, "OMFLCEBLLMI");
    assertEquals(70, Gameplay46.number(replacement, "BJIBCOHEGIJ"));
    var item = (DynamicMessage) Gameplay46.value(replacement, "HLCPFLOPGBM");
    assertEquals(9, Gameplay46.number(item, "LJCEOGPOGBE"));
    assertNotEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    transformed(1174);
    assertEquals(1, session.replacements.size());
  }

  @Test
  void jsonReloadKeepsTheFrozenBreakPlanAfterResourceChanges() throws Exception {
    counter(475);
    fixture.query();
    String acquisition = clock().getAcquisitionId();
    reload();
    GameplayDefinitions.miracles.remove(9);
    GameplayDefinitions.miracleEffects.remove(9);
    assertEquals(acquisition, clock().getAcquisitionId());
    assertEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    transformed(1174);
    reload();
    assertNotEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    transformed(1174);
  }

  @Test
  void walletOverflowRejectsTheWholePurchaseBeforeDebitUpgradeOrReplacement() throws Exception {
    counter(475);
    fixture.query();
    run().addCoins(Integer.MAX_VALUE - 300);
    int saves = session.explorer.saves;
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(Integer.MAX_VALUE, run().getCoins());
    assertEquals(475, clock().getExtraFragmentsSpent());
    assertEquals(1, run().getBlessings().get(UniverseEnhance46Test.BUFF));
    assertFalse(run().getMiracles().containsKey(9));
    assertTrue(run().getMiracleFragmentGrants().isEmpty());
    assertEquals(saves, session.explorer.saves);
  }

  @Test
  void failedDebitCannotCrossTheThreshold() throws Exception {
    counter(499);
    run().spend(300);
    assertEquals(Retcode.ITEM_NOT_ENOUGH, paid(5, 1));
    assertEquals(499, clock().getExtraFragmentsSpent());
    assertEquals(0, run().getCoins());
    assertTrue(run().getMiracleFragmentGrants().isEmpty());
  }

  @Test
  void previouslyQuotedDifferentAcquisitionCannotBreakANewClock() throws Exception {
    counter(475);
    fixture.query();
    run().getMiracles().remove(70);
    assertTrue(RogueMiracles.acquire(player(), 70));
    counter(475);
    assertEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(175, run().getCoins());
    assertEquals(475, clock().getExtraFragmentsSpent());
    assertFalse(run().getMiracles().containsKey(9));
  }

  @Test
  void malformedFrozenBreakParametersDoNotDebitTheAlreadyQuotedExpense() throws Exception {
    counter(475);
    fixture.query();
    snapshot("[499,998]");
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(300, run().getCoins());
    assertEquals(475, clock().getExtraFragmentsSpent());
  }

  @Test
  void oldCounterOnlyRecordDoesNotBorrowNewlyInstalledBreakDefinitions() throws Exception {
    run().getMiracles().put(70, new GameplayState.RogueMiracle(0, List.of(.25)));
    counter(499);
    assertEquals(Retcode.SUCC, paid(5, 1));
    assertEquals(500, clock().getExtraFragmentsSpent());
    assertFalse(run().getMiracles().containsKey(9));
    assertEquals(295, run().getCoins());
  }

  @Test
  void alreadyOwnedBrokenClockCannotBeOverwrittenByConversion() throws Exception {
    counter(499);
    var owned = new GameplayState.RogueMiracle(0, List.of(499.0, 999.0));
    run().getMiracles().put(9, owned);
    assertEquals(Retcode.PLAYER_DATA_ERROR, paid(5, 1));
    assertSame(owned, run().getMiracles().get(9));
    assertEquals(300, run().getCoins());
    assertEquals(499, clock().getExtraFragmentsSpent());
  }

  @Test
  void concurrentDuplicateEnhancementsCreateOneGrantAndOneBrokenClock() throws Exception {
    counter(475);
    fixture.query();
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 6; i++)
        tasks.add(() -> RogueEnhancement.enhance(player(), UniverseEnhance46Test.BUFF));
      int successes = 0;
      for (var result : workers.invokeAll(tasks)) if (result.get() == Retcode.SUCC) successes++;
      assertEquals(1, successes);
    }
    transformed(1174);
  }

  @Test
  void morphiaReloadKeepsTheQuotedConversionAndSettledRewardAcrossTwoSaves() throws Exception {
    counter(475);
    fixture.query();
    String acquisition = clock().getAcquisitionId();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "brokenClock");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456769);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, player().getGameplay());
      store.save(document);
      replaceGameplay(store.find(Player.class).first().getGameplay());
      assertEquals(acquisition, clock().getAcquisitionId());
      GameplayDefinitions.miracles.remove(9);
      GameplayDefinitions.miracleEffects.remove(9);
      assertEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
      transformed(1174);
      gameplay.set(document, player().getGameplay());
      store.save(document);
      replaceGameplay(store.find(Player.class).first().getGameplay());
      assertNotEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
      transformed(1174);
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void anUnknownBrokenClockSourceCannotBeBorrowedLaterByAnExistingAcquisition() throws Exception {
    run().getMiracles().remove(70);
    GameplayDefinitions.miracles.put(9, new GameplayDefinitions.Miracle(9, 51, 9));
    assertTrue(RogueMiracles.acquire(player(), 70));
    counter(499);
    GameplayDefinitions.miracles.put(9, new GameplayDefinitions.Miracle(9, 55, 9));
    assertEquals(Retcode.SUCC, paid(5, 1));
    assertEquals(500, clock().getExtraFragmentsSpent());
    assertEquals(295, run().getCoins());
    assertFalse(run().getMiracles().containsKey(9));
  }

  @Test
  void nullFrozenBreakParametersRejectBeforeChargingOrCreatingAReward() throws Exception {
    counter(499);
    snapshot("null");
    assertEquals(Retcode.PLAYER_DATA_ERROR, paid(5, 1));
    assertEquals(300, run().getCoins());
    assertEquals(499, clock().getExtraFragmentsSpent());
    assertTrue(run().getMiracleFragmentGrants().isEmpty());
  }
}
