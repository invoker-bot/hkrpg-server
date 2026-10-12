package org.hkrpg.server.gameplay;

import static org.hkrpg.server.protocol.Gameplay46.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Whole original resources, actual generic battle settlement, explicit isolated route/roster. */
class RogueBlessingPersistence46Test {
  @TempDir Path directory;
  final Gson gson = new Gson();
  final Map<Field, Object> saved = new HashMap<>();
  final List<MazeBuffExcel> oldMaze = new ArrayList<>();
  RogueMonsterSelectionTest encounter;
  Explorer player;
  Session session;
  JsonObject proof;
  Field config, aeons, events, handbook;
  Object oldConfig, oldAeons, oldEvents, oldHandbook;

  static class Explorer extends RogueMonsterSelectionTest.Explorer {
    boolean failNext;
    JsonElement stored;
    final List<String> order = new ArrayList<>();
    Session session;
    Runnable persistence = () -> {};

    public boolean isOnline() {
      return session != null;
    }

    public GameSession getSession() {
      return session;
    }

    public void save() {
      if (failNext) {
        failNext = false;
        throw new IllegalStateException("before-player-save");
      }
      persistence.run();
      saves++;
      stored = new Gson().toJsonTree(getGameplay());
      order.add("save");
    }

    public void sendPacket(BasePacket packet) {
      order.add("legacy-notify");
    }
  }

  static class Session extends GameSession {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    final Explorer player;
    final List<DynamicMessage> replies = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    final ThreadLocal<DynamicMessage> responseByThread = new ThreadLocal<>();

    Session(Explorer player) {
      super(null);
      this.player = player;
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return bridge;
    }

    public void send(BasePacket packet) {}

    public synchronized void sendClientPacket(String name, byte[] bytes) {
      try {
        var parsed = bridge.clientSchema().parse(name, bytes);
        names.add(name);
        replies.add(parsed);
        player.order.add(name);
        if (name.equals("HandleRogueCommonPendingActionScRsp")) responseByThread.set(parsed);
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
    }

    DynamicMessage response() {
      if (responseByThread.get() != null) return responseByThread.get();
      for (int i = names.size() - 1; i >= 0; i--)
        if (names.get(i).equals("HandleRogueCommonPendingActionScRsp")) return replies.get(i);
      throw new AssertionError("Missing real pending-action response");
    }

    void clear() {
      names.clear();
      replies.clear();
      player.order.clear();
      responseByThread.remove();
    }
  }

  @BeforeEach
  @SuppressWarnings({"rawtypes", "unchecked"})
  void setup() throws Exception {
    for (var field : GameplayDefinitions.class.getDeclaredFields()) {
      if (!Modifier.isPublic(field.getModifiers()) || !Modifier.isStatic(field.getModifiers()))
        continue;
      Object value = field.get(null);
      if (value instanceof Map map) saved.put(field, new HashMap(map));
      else if (value instanceof Set set) saved.put(field, new HashSet(set));
      else if (value instanceof List list) saved.put(field, new ArrayList(list));
      else if (field.getType() == boolean.class) saved.put(field, value);
    }
    oldMaze.addAll(GameData.getMazeBuffExcelMap().values());
    config = LunarCore.class.getDeclaredField("config");
    config.setAccessible(true);
    oldConfig = config.get(null);
    aeons = RogueAeonCatalog.class.getDeclaredField("catalogue");
    aeons.setAccessible(true);
    oldAeons = aeons.get(null);
    events = RogueResonance.class.getDeclaredField("events");
    events.setAccessible(true);
    oldEvents = events.get(null);
    handbook = RogueHandbookCatalog.class.getDeclaredField("miracles");
    handbook.setAccessible(true);
    oldHandbook = handbook.get(null);
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-blessing-persistence-4.6.json")) {
      proof =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    var settings = new Config();
    settings.resourceDir = directory.resolve("resources").toString();
    settings.dataDir = directory.resolve("data").toString();
    config.set(null, settings);
    Files.createDirectories(Path.of(settings.dataDir));
    var output = Files.createDirectories(Path.of(settings.resourceDir, "ExcelOutput"));
    for (var table : proof.getAsJsonObject("tables").entrySet())
      Files.writeString(
          output.resolve(table.getKey() + ".json"),
          table.getValue().getAsJsonObject().get("rows").toString());
    GameData.getMazeBuffExcelMap().values().clear();
    for (var row :
        proof.getAsJsonObject("tables").getAsJsonObject("RogueMazeBuff").getAsJsonArray("rows"))
      GameData.getMazeBuffExcelMap().add(gson.fromJson(row, MazeBuffExcel.class));
    loadOnlyOwnedDefinitions(Path.of(settings.resourceDir));
    encounter = new RogueMonsterSelectionTest();
    player = new Explorer();
    encounter.player = player;
    encounter.setup();
    GameplayDefinitions.maps.put(
        199956,
        List.of(
            new GameplayDefinitions.Site(199956, 1, true, List.of(2), 1),
            new GameplayDefinitions.Site(199956, 2, false, List.of(), 1)));
    session = new Session(player);
    player.session = session;
  }

  @AfterEach
  @SuppressWarnings({"rawtypes", "unchecked"})
  void restore() throws Exception {
    if (encounter != null) encounter.restore();
    GameData.getMazeBuffExcelMap().values().clear();
    oldMaze.forEach(GameData.getMazeBuffExcelMap()::add);
    for (var entry : saved.entrySet()) {
      var current = entry.getKey().get(null);
      if (current instanceof Map map) {
        map.clear();
        map.putAll((Map) entry.getValue());
      } else if (current instanceof Collection collection) {
        collection.clear();
        collection.addAll((Collection) entry.getValue());
      } else entry.getKey().set(null, entry.getValue());
    }
    if (aeons != null) aeons.set(null, oldAeons);
    if (events != null) events.set(null, oldEvents);
    if (handbook != null) handbook.set(null, oldHandbook);
    if (config != null) config.set(null, oldConfig);
  }

  void loadOnlyOwnedDefinitions(Path root) {
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.rogueConstants.clear();
    GameplayDefinitions.rogueTalents.clear();
    GameplayDefinitions.rogueBuffGroups.clear();
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracleEffects.clear();
    GameplayDefinitions.miracleGroups.clear();
    GameplayDefinitions.rows(
        root, "RogueBuff", GameplayDefinitions.Blessing.class, GameplayDefinitions.blessings::add);
    GameplayDefinitions.rows(
        root,
        "ConstValueRogue",
        GameplayDefinitions.RogueConstant.class,
        row -> GameplayDefinitions.rogueConstants.put(row.ConstRogueName(), row.ConstValue()));
    GameplayDefinitions.rows(
        root,
        "RogueTalent",
        GameplayDefinitions.RogueTalent.class,
        row -> GameplayDefinitions.rogueTalents.put(row.TalentID(), row));
    GameplayDefinitions.rows(
        root,
        "RogueBuffGroup",
        GameplayDefinitions.RogueBuffGroup.class,
        row -> GameplayDefinitions.rogueBuffGroups.put(row.GMLOGNJAIGI(), row));
    GameplayDefinitions.rows(
        root,
        "RogueMiracle",
        GameplayDefinitions.Miracle.class,
        row -> GameplayDefinitions.miracles.put(row.MiracleID(), row));
    GameplayDefinitions.rows(
        root,
        "RogueMiracleEffect",
        GameplayDefinitions.MiracleEffect.class,
        row -> GameplayDefinitions.miracleEffects.put(row.MiracleEffectID(), row));
    GameplayDefinitions.rows(
        root,
        "RogueMiracleGroup",
        GameplayDefinitions.MiracleGroup.class,
        row -> GameplayDefinitions.miracleGroups.put(row.RogueMiracleGroupID(), row));
    RogueAeonCatalog.load(root);
    RogueHandbookCatalog.load(root);
  }

  GameplayState.RogueRun victoryMenu() {
    return victoryMenu(0, List.of(), false);
  }

  GameplayState.RogueRun victoryMenu(
      int priorNormals, List<Integer> held, boolean clockAtBreakLimit) {
    player
        .getGameplay()
        .getRogueTalents()
        .add(11); // Explicit saved eligibility, no talent purchase claim.
    var run = new GameplayState.RogueRun(200, 199956, Map.of(1, 200112, 2, 200112), null, 1);
    run.setupTeam(player, List.of(199961));
    run.enter(1, 200112);
    run.setInside(true);
    player.getGameplay().setRogue(run);
    encounter.reload();
    assertNotNull(run.getResonance(), "Full original resonance plan must exist before the battle");
    run.getResonance().getNormals().stream()
        .sorted()
        .limit(priorNormals)
        .forEach(id -> run.getBlessings().put(id, 1));
    for (int id : held)
      assertNotNull(
          RogueMiracles.acquireDeferred(player, id),
          "Explicit source-bound held miracle fixture "
              + id
              + " source="
              + GameplayDefinitions.miracles.get(id)
              + " effect="
              + GameplayDefinitions.miracleEffects.get(id)
              + " supported="
              + RogueMiracles.supported(id)
              + " handbook="
              + RogueHandbookCatalog.canRecord(player, id)
              + " phase="
              + run.getPhase());
    if (clockAtBreakLimit) {
      var clock = run.getMiracles().get(70);
      assertNotNull(clock);
      clock.recordExtraFragments(clock.getClockBreakParameters().getFirst().intValue());
    }
    var target = encounter.actor();
    assertNotNull(target);
    var binding = RogueMonsterSelection.current(player.getScene(), encounter.group);
    assertNotNull(binding);
    assertSame(GameData.getStageExcelMap().get(target.getEventId()), target.getCustomStage());
    var battle = new Battle(player, player.getCurrentLineup(), List.of(target));
    player.setBattle(battle);
    assertFalse(battle.getWaves().isEmpty());
    assertSame(
        battle,
        new BattleService(null) {
          protected void grantDrops(Battle ignored) {}
        }.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertNull(player.getBattle());
    assertTrue(run.getCleared().contains(1));
    assertEquals(held.contains(1) ? 2 : 3, run.getOffers().size());
    assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run.getPhase());
    if (held.isEmpty())
      assertEquals(
          30,
          player.getUniverseManager().blessingRerollCost().orElseThrow(),
          "Frozen original [31:30] price, not a test replacement");
    assertEquals(
        gson.toJsonTree(player.getGameplay()),
        player.stored,
        "The real battle menu preparation was saved successfully");
    session.clear();
    return run;
  }

  byte[] choose(GameplayState.RogueRun run, int buff) {
    var request = builder(session, "OFEDNNJIJFL");
    set(request, "MPLGEEGOAMC", run.getSelectionId());
    var selection = builder(session, "BPLNKHGAILK");
    set(selection, "BAOFIGFFMLN", buff);
    set(request, "DLKIKBIDJDB", selection.build());
    return request.build().toByteArray();
  }

  byte[] reroll(GameplayState.RogueRun run) {
    var request = builder(session, "OFEDNNJIJFL");
    set(request, "MPLGEEGOAMC", run.getSelectionId());
    set(request, "KBNLGEMNDEN", builder(session, "DEMENEAAGAA").build());
    return request.build().toByteArray();
  }

  void request(byte[] bytes) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 5676, bytes));
  }

  static DynamicMessage.Builder builder(Session session, String name) {
    return DynamicMessage.newBuilder(session.getProtocol().clientSchema().message(name));
  }

  static int number(DynamicMessage message, String name) {
    return ((Number) message.getField(message.getDescriptorForType().findFieldByName(name)))
        .intValue();
  }

  static void set(DynamicMessage.Builder message, String name, Object value) {
    message.setField(
        Objects.requireNonNull(message.getDescriptorForType().findFieldByName(name)), value);
  }

  void exactFailedSave(byte[] request) throws Exception {
    var state = player.getGameplay();
    var run = state.getRogue();
    var plan = run.getResonance();
    var lineup = player.getCurrentLineup();
    var before = gson.toJsonTree(state);
    var stored = player.stored.deepCopy();
    int saves = player.saves;
    session.clear();
    player.failNext = true;
    assertEquals(
        "before-player-save",
        assertThrows(IllegalStateException.class, () -> request(request)).getMessage());
    assertAll(
        () -> assertEquals(before, gson.toJsonTree(state)),
        () -> assertEquals(stored, player.stored),
        () -> assertSame(state, player.getGameplay()),
        () -> assertSame(run, state.getRogue()),
        () -> assertSame(plan, run.getResonance()),
        () -> assertSame(lineup, player.getCurrentLineup()),
        () -> assertEquals(saves, player.saves),
        () -> assertTrue(session.names.isEmpty()),
        () -> assertTrue(player.order.isEmpty()));
    request(request);
    assertEquals(0, number(session.response(), "retcode"));
    assertEquals(saves + 1, player.saves);
    assertEquals(gson.toJsonTree(state), player.stored);
    assertEquals("save", player.order.getFirst());
    assertEquals("HandleRogueCommonPendingActionScRsp", player.order.getLast());
  }

  @Test
  void completeOriginalBattleProducesAndPersistsAnOrdinarySelectableMenu() throws Exception {
    var run = victoryMenu();
    int buff = run.getOffers().getFirst();
    request(choose(run, buff));
    assertEquals(0, number(session.response(), "retcode"));
    assertTrue(run.getBlessings().containsKey(buff));
    assertTrue(player.getGameplay().getRogueCollectedBuffs().contains(buff));
    assertEquals(gson.toJsonTree(player.getGameplay()), player.stored);
    assertEquals("save", player.order.getFirst());
    assertEquals("HandleRogueCommonPendingActionScRsp", player.order.getLast());
  }

  @Test
  void firstChoiceSaveFailureRetainsTheExactVictoryMenuAndSameRequestCanRetry() throws Exception {
    var run = victoryMenu();
    var plan = run.getResonance();
    var lineup = player.getCurrentLineup();
    int buff = run.getOffers().getFirst();
    byte[] request = choose(run, buff);
    var before = gson.toJsonTree(player.getGameplay());
    var stored = player.stored.deepCopy();
    player.failNext = true;
    var failure = assertThrows(IllegalStateException.class, () -> request(request));
    assertEquals("before-player-save", failure.getMessage());
    assertAll(
        () ->
            assertEquals(
                before,
                gson.toJsonTree(player.getGameplay()),
                "Every Gameplay/Run/permanent/queue/pricing field rolls back"),
        () -> assertEquals(stored, player.stored),
        () -> assertSame(run, player.getGameplay().getRogue()),
        () -> assertSame(plan, run.getResonance()),
        () -> assertSame(lineup, player.getCurrentLineup()),
        () ->
            assertTrue(
                player.order.isEmpty(),
                "Failed first save publishes no packet or success response"));
    request(request);
    assertEquals(0, number(session.response(), "retcode"));
    assertTrue(run.getBlessings().containsKey(buff));
    assertEquals(gson.toJsonTree(player.getGameplay()), player.stored);
    assertEquals("save", player.order.getFirst());
    int saves = player.saves;
    request(request);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), number(session.response(), "retcode"));
    assertEquals(saves, player.saves);
  }

  @Test
  void firstRerollSaveFailureRetainsOriginalPricePoolCurrencyAndSameRequestCanRetry()
      throws Exception {
    var run = victoryMenu();
    var plan = run.getResonance();
    var lineup = player.getCurrentLineup();
    byte[] request = reroll(run);
    var before = gson.toJsonTree(player.getGameplay());
    var stored = player.stored.deepCopy();
    int coins = run.getCoins();
    player.failNext = true;
    var failure = assertThrows(IllegalStateException.class, () -> request(request));
    assertEquals("before-player-save", failure.getMessage());
    assertAll(
        () ->
            assertEquals(
                before,
                gson.toJsonTree(player.getGameplay()),
                "All menu/exclusion/count/currency/miracle fields roll back"),
        () -> assertEquals(stored, player.stored),
        () -> assertSame(run, player.getGameplay().getRogue()),
        () -> assertSame(plan, run.getResonance()),
        () -> assertSame(lineup, player.getCurrentLineup()),
        () ->
            assertTrue(
                player.order.isEmpty(),
                "Failed first save publishes no debit/menu notification or response"));
    request(request);
    assertEquals(0, number(session.response(), "retcode"));
    assertEquals(coins - 30, run.getCoins());
    assertEquals(1, run.getOfferRerolls());
    assertEquals(gson.toJsonTree(player.getGameplay()), player.stored);
    assertEquals("save", player.order.getFirst());
    int saves = player.saves;
    request(request);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), number(session.response(), "retcode"));
    assertEquals(saves, player.saves);
  }

  GameplayState.RogueRun thirdNormalMenu() {
    for (int i = 0; i < 100; i++) {
      var run = victoryMenu(2, List.of(), false);
      if (run.getOffers().stream().anyMatch(run.getResonance().getNormals()::contains)) return run;
    }
    throw new AssertionError("Original ordinary pool must expose a selected-path candidate");
  }

  @Test
  void thirdOriginalNormalChoiceRollsBackTheNewResonanceQueueAndPreservesLivePlan()
      throws Exception {
    var run = thirdNormalMenu();
    int buff =
        run.getOffers().stream()
            .filter(run.getResonance().getNormals()::contains)
            .findFirst()
            .orElseThrow();
    var plan = run.getResonance();
    exactFailedSave(choose(run, buff));
    assertEquals(List.of(plan.getBasic()), run.getOffers());
    assertEquals(-1, run.getResonanceSp());
    assertTrue(run.getBlessings().containsKey(buff));
    assertSame(plan, run.getResonance());
    assertEquals(
        1, session.names.stream().filter("SyncRogueCommonPendingActionScNotify"::equals).count());
  }

  @Test
  void choosingTheQueuedBasicResonanceRollsBackSpActivationUntilSaveAndRetriesOnce()
      throws Exception {
    var run = thirdNormalMenu();
    int buff =
        run.getOffers().stream()
            .filter(run.getResonance().getNormals()::contains)
            .findFirst()
            .orElseThrow();
    request(choose(run, buff));
    assertEquals(List.of(run.getResonance().getBasic()), run.getOffers());
    assertEquals(-1, run.getResonanceSp());
    session.clear();
    exactFailedSave(choose(run, run.getResonance().getBasic()));
    assertEquals(10000, run.getResonanceSp());
    assertTrue(run.getResonance().earned(run));
    assertFalse(run.pending());
  }

  @ParameterizedTest
  @ValueSource(strings = {"faith", "clock", "free", "beacon-double"})
  void realVictoryRerollKeepsEveryFrozenMiraclePriceMenuAndCounterOnFailedSave(String effect)
      throws Exception {
    var held =
        switch (effect) {
          case "faith" -> List.of(13);
          case "clock" -> List.of(70);
          case "free" -> List.of(2);
          default -> List.of(1, 69);
        };
    var run = victoryMenu(0, held, false);
    int cost = player.getUniverseManager().blessingRerollCost().orElseThrow();
    assertEquals(
        switch (effect) {
          case "faith" -> 21;
          case "clock" -> 37;
          case "free" -> 0;
          default -> 30;
        },
        cost);
    int coins = run.getCoins();
    var first = List.copyOf(run.getOffers());
    // Reloading public inputs cannot reprice the already saved original menu.
    GameplayDefinitions.rogueConstants.put("Roll_Buff_Cost", "[31:1]");
    GameplayDefinitions.rogueTalents.remove(11);
    exactFailedSave(reroll(run));
    assertEquals(coins - cost, run.getCoins());
    assertEquals(1, run.getOfferRerolls());
    assertTrue(Collections.disjoint(first, run.getOffers()));
    if (effect.equals("clock")) assertEquals(7, run.getMiracles().get(70).getExtraFragmentsSpent());
    if (effect.equals("beacon-double")) {
      assertEquals(2, run.getOfferChoiceTotal());
      assertEquals(1, run.getOfferEnhancementCount());
    }
    var callback =
        (DynamicMessage)
            session
                .response()
                .getField(session.response().getDescriptorForType().findFieldByName("JFHKDEIDMOG"));
    var offered =
        (DynamicMessage)
            callback.getField(callback.getDescriptorForType().findFieldByName("PCHGEGFBKLF"));
    assertEquals(1, number(offered, "PCGFIOKLIBE"));
  }

  @Test
  void breakingTheOriginalClockDuringRerollRollsBackUuidGrantAndReplacementNotification()
      throws Exception {
    var run = victoryMenu(0, List.of(70), true);
    var clock = run.getMiracles().get(70);
    String acquisition = clock.getAcquisitionId();
    int grant = clock.getClockBreakParameters().get(1).intValue();
    int coins = run.getCoins();
    assertEquals(37, player.getUniverseManager().blessingRerollCost().orElseThrow());
    exactFailedSave(reroll(run));
    assertFalse(run.getMiracles().containsKey(70));
    assertNotNull(run.getMiracles().get(9));
    assertEquals(coins - 37 + grant, run.getCoins());
    assertEquals(1, run.getMiracleFragmentGrants().size());
    assertTrue(run.getMiracleFragmentGrants().containsKey("clock:70:" + acquisition));
    assertEquals(
        1, session.names.stream().filter("SyncRogueCommonActionResultScNotify"::equals).count());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"oldBatch", "unknownBuff", "missingBinding", "outside", "battle", "malformed"})
  void rejectedRealSelectionNeverSavesMutatesOrPublishesAnAcquisition(String damage)
      throws Exception {
    var run = victoryMenu();
    int buff = run.getOffers().getFirst();
    byte[] request = choose(run, buff);
    if (damage.equals("oldBatch")) {
      var value = builder(session, "OFEDNNJIJFL").mergeFrom(request);
      set(value, "MPLGEEGOAMC", run.getSelectionId() + 1);
      request = value.build().toByteArray();
    }
    if (damage.equals("unknownBuff")) request = choose(run, Integer.MAX_VALUE);
    if (damage.equals("missingBinding"))
      GameData.getMazeBuffExcelMap()
          .values()
          .removeIf(row -> row.getID() == buff && row.getLv() == run.offerLevel(buff));
    if (damage.equals("outside")) run.setInside(false);
    if (damage.equals("battle"))
      player.setBattle(
          new Battle(
              player,
              player.getCurrentLineup(),
              GameData.getStageExcelMap().values().iterator().next()));
    if (damage.equals("malformed")) {
      var value = builder(session, "OFEDNNJIJFL").mergeFrom(request);
      value.mergeFrom(new byte[] {8, 1});
      request = value.build().toByteArray();
    }
    var before = gson.toJsonTree(player.getGameplay());
    int saves = player.saves;
    session.clear();
    request(request);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), number(session.response(), "retcode"));
    assertEquals(before, gson.toJsonTree(player.getGameplay()));
    assertEquals(saves, player.saves);
    assertEquals(List.of("HandleRogueCommonPendingActionScRsp"), session.names);
  }

  @ParameterizedTest
  @ValueSource(strings = {"oldBatch", "poor", "excludedPool", "outside", "battle", "malformed"})
  void rejectedRealRerollNeverDebitsConsumesSavesOrPublishesNewMenu(String damage)
      throws Exception {
    var run = victoryMenu();
    byte[] request = reroll(run);
    if (damage.equals("oldBatch")) {
      var value = builder(session, "OFEDNNJIJFL").mergeFrom(request);
      set(value, "MPLGEEGOAMC", run.getSelectionId() + 1);
      request = value.build().toByteArray();
    }
    if (damage.equals("poor")) run.spend(run.getCoins());
    if (damage.equals("excludedPool")) run.getOfferExcluded().addAll(run.getOfferPool());
    if (damage.equals("outside")) run.setInside(false);
    if (damage.equals("battle"))
      player.setBattle(
          new Battle(
              player,
              player.getCurrentLineup(),
              GameData.getStageExcelMap().values().iterator().next()));
    if (damage.equals("malformed")) {
      var value = builder(session, "OFEDNNJIJFL").mergeFrom(request);
      set(
          value,
          "KBNLGEMNDEN",
          builder(session, "DEMENEAAGAA").mergeFrom(new byte[] {8, 1}).build());
      request = value.build().toByteArray();
    }
    var before = gson.toJsonTree(player.getGameplay());
    int saves = player.saves;
    session.clear();
    request(request);
    assertEquals(
        (damage.equals("poor") ? Retcode.ITEM_NOT_ENOUGH : Retcode.REQ_PARA_INVALID).getVal(),
        number(session.response(), "retcode"));
    assertEquals(before, gson.toJsonTree(player.getGameplay()));
    assertEquals(saves, player.saves);
    assertEquals(List.of("HandleRogueCommonPendingActionScRsp"), session.names);
  }

  @Test
  void simultaneousNativeRetriesAfterTheFailedFirstSaveConsumeTheOriginalPaidRollOnce()
      throws Exception {
    var run = victoryMenu();
    byte[] request = reroll(run);
    var before = gson.toJsonTree(player.getGameplay());
    int coins = run.getCoins(), saves = player.saves;
    player.failNext = true;
    assertThrows(IllegalStateException.class, () -> request(request));
    assertEquals(before, gson.toJsonTree(player.getGameplay()));
    assertTrue(player.order.isEmpty());
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<java.util.concurrent.Callable<Integer>>();
      for (int i = 0; i < 6; i++)
        calls.add(
            () -> {
              request(request);
              return number(session.response(), "retcode");
            });
      int success = 0;
      for (var result : workers.invokeAll(calls)) {
        int code = result.get();
        if (code == 0) success++;
        else assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code);
      }
      assertEquals(1, success);
    }
    assertEquals(coins - 30, run.getCoins());
    assertEquals(1, run.getOfferRerolls());
    assertEquals(saves + 1, player.saves);
    assertEquals(gson.toJsonTree(player.getGameplay()), player.stored);
  }

  @Test
  void twoActualPlayerReadsRetainThePreFailureMenuAndTheSuccessfullyRetriedChoice()
      throws Exception {
    var run = victoryMenu();
    int buff = run.getOffers().getFirst();
    byte[] request = choose(run, buff);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var providers =
          org.bson.codecs.configuration.CodecRegistries.fromCodecs(
              new emu.lunarcore.database.codecs.IntSetCodec(),
              new emu.lunarcore.database.codecs.IntListCodec(),
              new emu.lunarcore.database.codecs.Int2IntMapCodec());
      var options =
          dev.morphia.mapping.MapperOptions.builder()
              .storeEmpties(true)
              .storeNulls(false)
              .codecProvider(providers)
              .build();
      var store =
          dev.morphia.Morphia.createDatastore(client, "ordinary_blessing_failure_retry", options);
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456781);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, player.getGameplay());
      store.save(document);
      player.persistence = () -> store.save(document);
      var collection =
          client.getDatabase("ordinary_blessing_failure_retry").getCollection("players");
      var before = gson.toJsonTree(player.getGameplay());
      var bsonBefore = collection.find().first().toJson();
      player.failNext = true;
      assertThrows(IllegalStateException.class, () -> request(request));
      assertEquals(before, gson.toJsonTree(player.getGameplay()));
      assertTrue(player.order.isEmpty());
      assertEquals(
          bsonBefore,
          collection.find().first().toJson(),
          "Failure before first persistence made no document write");
      Player previous = null;
      for (int i = 0; i < 2; i++) {
        var restored = store.find(Player.class).first();
        assertNotSame(previous, restored);
        assertEquals(before, gson.toJsonTree(restored.getGameplay()));
        previous = restored;
      }
      request(request);
      assertEquals(0, number(session.response(), "retcode"));
      var after = gson.toJsonTree(player.getGameplay());
      previous = null;
      for (int i = 0; i < 2; i++) {
        var restored = store.find(Player.class).first();
        assertNotSame(previous, restored);
        assertEquals(after, gson.toJsonTree(restored.getGameplay()));
        assertTrue(restored.getGameplay().getRogue().getBlessings().containsKey(buff));
        assertTrue(restored.getGameplay().getRogueCollectedBuffs().contains(buff));
        assertEquals(run.getRunId(), restored.getGameplay().getRogue().getRunId());
        assertEquals(
            run.getResonance().getSourceRows(),
            restored.getGameplay().getRogue().getResonance().getSourceRows());
        previous = restored;
      }
    } finally {
      player.persistence = () -> {};
      mongo.shutdownNow();
    }
  }
}
