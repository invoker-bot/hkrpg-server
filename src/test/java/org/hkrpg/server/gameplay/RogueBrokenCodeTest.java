package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueBrokenCodeTest {
  static final Map<Integer, List<Double>> VALUES =
      Map.of(
          45,
          List.of(0.0),
          47,
          List.of(.3),
          49,
          List.of(.5, .35, .5),
          51,
          List.of(.35),
          53,
          List.of(.35),
          55,
          List.of(1.0));
  final RogueRepairedCodeTest base = new RogueRepairedCodeTest();
  final Map<Integer, GameplayDefinitions.Miracle> oldMiracles = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();
  final Map<Integer, MazeBuffExcel> oldBuffs = new HashMap<>();

  @BeforeEach
  void setup() {
    base.setup();
    VALUES.forEach(
        (id, values) -> {
          oldMiracles.put(id, GameplayDefinitions.miracles.get(id));
          oldEffects.put(id, GameplayDefinitions.miracleEffects.get(id));
          oldBuffs.put(id, GameData.getMazeBuffExcelMap().get(base.bindingId(id), 1));
          GameplayDefinitions.miracles.put(
              id, new GameplayDefinitions.Miracle(id, base.displayId(id + 1), id));
          base.effect(id, values);
          base.binding(id, "StageAbility_" + base.bindingId(id), bindingValues(id));
        });
    // Leave another source-linked enemy alive so three distinct wins do not close the room.
    fixture().monster(9, 999);
  }

  static List<Double> bindingValues(int id) {
    return id == 45 ? List.of() : id == 49 ? List.of(.5, .35) : VALUES.get(id);
  }

  UniverseCombatFragmentsTest fixture() {
    return base.base.fixture;
  }

  GameplayState.RogueRun run() {
    return base.base.run();
  }

  void win(int instance) {
    fixture()
        .finish(fixture().battle(fixture().monster(8, instance)), BattleEndStatus.BATTLE_END_WIN);
  }

  void acquire(int id) {
    assertTrue(RogueMiracles.acquire(fixture().p, id));
  }

  @AfterEach
  void cleanup() {
    base.cleanup();
    VALUES
        .keySet()
        .forEach(
            id -> {
              GameplayDefinitions.miracles.remove(id);
              GameplayDefinitions.miracleEffects.remove(id);
              if (oldMiracles.get(id) != null)
                GameplayDefinitions.miracles.put(id, oldMiracles.get(id));
              if (oldEffects.get(id) != null)
                GameplayDefinitions.miracleEffects.put(id, oldEffects.get(id));
              GameData.getMazeBuffExcelMap()
                  .values()
                  .removeIf(v -> v.getID() == base.bindingId(id));
              if (oldBuffs.get(id) != null) GameData.getMazeBuffExcelMap().add(oldBuffs.get(id));
            });
  }

  @ParameterizedTest
  @ValueSource(ints = {45, 47, 49, 51, 53, 55})
  void threeDistinctSourceLinkedWinsReplaceTheCurioAndTheNextBattleUsesTheRepairedAbility(int id)
      throws Exception {
    acquire(id);
    var original = run().getMiracles().get(id);
    assertEquals(3, original.getTotalCount());
    assertEquals(VALUES.get(id), original.getEffectParameters());
    assertFalse(original.getAcquisitionId().isBlank());
    String vitals = fixture().gson.toJson(run().getVitals());
    int mp = run().getMp();
    for (int i = 1; i <= 3; i++) {
      var battle = fixture().battle(fixture().monster(8, i));
      assertTrue(battle.hasBuff(base.bindingId(id)));
      assertFalse(battle.hasBuff(base.bindingId(id + 1)));
      var nativeBuff = base.nativeBindings(battle).getFirst();
      assertEquals(
          base.bindingId(id),
          nativeBuff.getField(nativeBuff.getDescriptorForType().findFieldByName("id")));
      assertTrue(
          ((List<?>)
                  nativeBuff.getField(
                      nativeBuff.getDescriptorForType().findFieldByName("dynamic_values")))
              .isEmpty());
      fixture().finish(battle, BattleEndStatus.BATTLE_END_WIN);
      fixture().p.getUniverseManager().onBattleFinish(battle);
      if (i < 3) {
        assertSame(original, run().getMiracles().get(id));
        assertEquals(i, original.getUseCount());
        assertFalse(run().getMiracles().containsKey(id + 1));
      }
    }
    assertFalse(run().getMiracles().containsKey(id));
    var repaired = run().getMiracles().get(id + 1);
    assertNotNull(repaired);
    assertEquals(base.VALUES.get(id + 1), repaired.getEffectParameters());
    assertEquals(0, repaired.getTotalCount());
    assertEquals(0, repaired.getUseCount());
    assertNotEquals(original.getAcquisitionId(), repaired.getAcquisitionId());
    assertEquals(3, run().getProcessedBattles().size());
    var next = fixture().battle(fixture().monster(8, 4));
    assertFalse(next.hasBuff(base.bindingId(id)));
    assertTrue(next.hasBuff(base.bindingId(id + 1)));
    assertEquals(mp, run().getMp());
    assertEquals(vitals, fixture().gson.toJson(run().getVitals()));
  }

  @ParameterizedTest
  @ValueSource(ints = {45, 47, 49, 51, 53, 55})
  void bothDirectionsRejectOwningBothVariants(int id) {
    acquire(id);
    assertFalse(RogueMiracles.canAcquire(fixture().p, id + 1));
    assertFalse(RogueMiracles.acquire(fixture().p, id + 1));
    run().getMiracles().clear();
    acquire(id + 1);
    assertFalse(RogueMiracles.canAcquire(fixture().p, id));
    assertFalse(RogueMiracles.acquire(fixture().p, id));
  }

  @ParameterizedTest
  @ValueSource(ints = {45, 47, 49, 51, 53, 55})
  void acquisitionRequiresTheExactPairAndBothResourceAbilities(int id) {
    base.effect(id + 1, List.of(.9));
    assertFalse(RogueMiracles.acquire(fixture().p, id));
    base.effect(id + 1, base.VALUES.get(id + 1));
    base.binding(id, "StageAbility_" + base.bindingId(id + 1), bindingValues(id));
    assertFalse(RogueMiracles.acquire(fixture().p, id));
    base.binding(id, "StageAbility_" + base.bindingId(id), bindingValues(id));
    assertTrue(RogueMiracles.supported(id));
  }

  @ParameterizedTest
  @ValueSource(ints = {45, 47, 49, 51, 53, 55})
  void anAcquisitionAfterPreparationCannotBorrowTheEarlierBattle(int id) {
    var battle = fixture().battle(fixture().monster(8, 1));
    acquire(id);
    fixture().p.getUniverseManager().onBattlePrepared(battle);
    fixture().finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(0, run().getMiracles().get(id).getUseCount());
    win(2);
    assertEquals(1, run().getMiracles().get(id).getUseCount());
  }

  @Test
  void allSixRepairTogetherAndConcurrentReplayNeverAdvancesTwice() throws Exception {
    for (int id : VALUES.keySet()) acquire(id);
    var battle = fixture().battle(fixture().monster(8, 1));
    fixture().finish(battle, BattleEndStatus.BATTLE_END_WIN);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
      for (int i = 0; i < 8; i++)
        tasks.add(
            () -> {
              fixture().p.getUniverseManager().onBattleFinish(battle);
              return null;
            });
      for (var result : pool.invokeAll(tasks)) result.get();
    }
    for (int id : VALUES.keySet()) assertEquals(1, run().getMiracles().get(id).getUseCount());
    win(2);
    win(3);
    assertEquals(base.VALUES.keySet(), run().getMiracles().keySet());
  }

  @Test
  void quitPausedAndWrongStageBattlesDoNotRepair() {
    acquire(45);
    fixture().finish(fixture().battle(fixture().monster(8, 1)), BattleEndStatus.BATTLE_END_QUIT);
    assertEquals(0, run().getMiracles().get(45).getUseCount());
    run().setInside(false);
    win(2);
    assertEquals(0, run().getMiracles().get(45).getUseCount());
    run().setInside(true);
    var enemy = fixture().monster(8, 3);
    enemy.setEventId(123);
    fixture().finish(fixture().battle(enemy), BattleEndStatus.BATTLE_END_WIN);
    assertEquals(0, run().getMiracles().get(45).getUseCount());
  }

  @Test
  void aReplacementAcquisitionCannotBorrowThePreparedUuid() {
    acquire(45);
    var battle = fixture().battle(fixture().monster(8, 1));
    run().getMiracles().remove(45);
    acquire(45);
    fixture().finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(0, run().getMiracles().get(45).getUseCount());
  }

  @Test
  void repairUsesTheAcquiredPairParametersAfterCurrentResourcesChange() {
    acquire(49);
    win(1);
    win(2);
    base.effect(50, List.of(.9));
    win(3);
    assertFalse(run().getMiracles().containsKey(49));
    assertEquals(List.of(.5, .25), run().getMiracles().get(50).getEffectParameters());
  }

  @Test
  void invalidStoredRepairPairsDoNotApplyAnAbilityOrAdvance() {
    for (String replacement : List.of("[]", "null", "[0.9]")) {
      acquire(45);
      var json = fixture().gson.toJsonTree(run().getMiracles().get(45)).getAsJsonObject();
      json.add("codeRepairParameters", com.google.gson.JsonParser.parseString(replacement));
      run().getMiracles().put(45, fixture().gson.fromJson(json, GameplayState.RogueMiracle.class));
      assertFalse(fixture().battle(fixture().monster(8, 10)).hasBuff(base.bindingId(45)));
      win(11);
      assertEquals(0, run().getMiracles().get(45).getUseCount());
      run().getMiracles().clear();
    }
    // A pre-feature snapshot cannot invent its repaired parameters or past wins.
    run().getMiracles().put(45, new GameplayState.RogueMiracle(3, List.of(0.0)));
    win(12);
    assertEquals(0, run().getMiracles().get(45).getUseCount());
  }

  @Test
  void actualMorphiaTwoLoadsRestoreProgressAndFrozenPairsBeforeTheThirdWin() throws Exception {
    var acquisitions = new HashMap<Integer, String>();
    for (int id : VALUES.keySet()) {
      acquire(id);
      acquisitions.put(id, run().getMiracles().get(id).getAcquisitionId());
    }
    win(1);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "broken-codes");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456781);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      for (int used = 1; used <= 2; used++) {
        gameplay.set(document, fixture().p.getGameplay());
        store.save(document);
        gameplay.set(
            fixture().p, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
        for (int id : VALUES.keySet()) {
          var item = run().getMiracles().get(id);
          assertEquals(used, item.getUseCount());
          assertEquals(3, item.getTotalCount());
          assertEquals(acquisitions.get(id), item.getAcquisitionId());
          assertEquals(RogueRepairedCodeTest.VALUES.get(id + 1), item.getCodeRepairParameters());
          assertThrows(
              UnsupportedOperationException.class, () -> item.getCodeRepairParameters().clear());
        }
        win(used + 1);
      }
      assertEquals(RogueRepairedCodeTest.VALUES.keySet(), run().getMiracles().keySet());
      assertEquals(3, run().getProcessedBattles().size());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void allProgressAndReplacementPacketsObserveCommittedStateAndTheNativeOneof() throws Exception {
    acquire(45);
    var notifications = new ArrayList<com.google.protobuf.DynamicMessage>();
    int[] beforeSave = {fixture().p.saves};
    var session =
        new emu.lunarcore.server.game.GameSession(null) {
          public org.hkrpg.server.protocol.ProtocolBridge getProtocol() {
            return org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
          }

          public void sendClientPacket(String name, byte[] bytes) {
            assertTrue(fixture().p.saves > beforeSave[0], "Notification preceded the player save");
            if (!name.equals("SyncRogueCommonActionResultScNotify")) return;
            try {
              notifications.add(getProtocol().clientSchema().parse(name, bytes));
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          }
        };
    var loggedIn = emu.lunarcore.game.player.Player.class.getDeclaredField("loggedIn");
    loggedIn.setAccessible(true);
    fixture().p.setSession(session);
    loggedIn.setBoolean(fixture().p, true);
    try {
      for (int i = 1; i <= 3; i++) {
        beforeSave[0] = fixture().p.saves;
        win(i);
      }
      assertEquals(3, notifications.size());
      for (int i = 0; i < 3; i++) {
        var notify = notifications.get(i);
        assertTrue(notify.getUnknownFields().asMap().isEmpty());
        var action =
            (com.google.protobuf.DynamicMessage)
                ((List<?>) proto(notify, "CLGGKLNPLCK")).getFirst();
        var detail = (com.google.protobuf.DynamicMessage) proto(action, "detail");
        if (i < 2) {
          var update = (com.google.protobuf.DynamicMessage) proto(detail, "KDJBDMLDMHG");
          var item = (com.google.protobuf.DynamicMessage) proto(update, "miracle");
          assertEquals(45, proto(item, "LJCEOGPOGBE"));
          assertEquals(i + 1, proto(item, "DGGCFDKJDPO"));
          assertEquals(3, proto(item, "total_count"));
        } else {
          var field = detail.getDescriptorForType().findFieldByName("OMFLCEBLLMI");
          assertEquals(1957, field.getNumber());
          assertTrue(detail.hasField(field));
          var replace = (com.google.protobuf.DynamicMessage) detail.getField(field);
          assertEquals(
              1, replace.getDescriptorForType().findFieldByName("BJIBCOHEGIJ").getNumber());
          assertEquals(
              14, replace.getDescriptorForType().findFieldByName("HLCPFLOPGBM").getNumber());
          assertEquals(45, proto(replace, "BJIBCOHEGIJ"));
          var item = (com.google.protobuf.DynamicMessage) proto(replace, "HLCPFLOPGBM");
          assertEquals(46, proto(item, "LJCEOGPOGBE"));
          assertEquals(0, proto(item, "DGGCFDKJDPO"));
          assertEquals(0, proto(item, "total_count"));
          assertFalse(run().getMiracles().containsKey(45));
        }
      }
    } finally {
      loggedIn.setBoolean(fixture().p, false);
    }
  }

  static Object proto(com.google.protobuf.DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }
}
