package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.battle.Battle;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueRepairedCodeTest {
  static final Map<Integer, List<Double>> VALUES =
      Map.of(
          46,
          List.of(40.0),
          48,
          List.of(.3),
          50,
          List.of(.5, .25),
          52,
          List.of(.25),
          54,
          List.of(.25),
          56,
          List.of(1.0));
  final RogueBattleMiraclesTest base = new RogueBattleMiraclesTest();
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
          oldBuffs.put(id, GameData.getMazeBuffExcelMap().get(bindingId(id), 1));
          GameplayDefinitions.miracles.put(
              id, new GameplayDefinitions.Miracle(id, displayId(id), id));
          effect(id, values);
          binding(id, "StageAbility_" + bindingId(id), values);
        });
  }

  static int bindingId(int id) {
    return 630968 + id;
  }

  static int displayId(int id) {
    return id - 20;
  }

  void effect(int id, List<Double> values) {
    GameplayDefinitions.miracleEffects.put(
        id,
        new GameplayDefinitions.MiracleEffect(
            id, values.stream().map(GameplayDefinitions.EffectValue::new).toList()));
  }

  void binding(int id, String key, List<Double> values) {
    var row = new com.google.gson.JsonObject();
    row.addProperty("ID", bindingId(id));
    row.addProperty("Lv", 1);
    row.addProperty("LvMax", 1);
    row.addProperty("InBattleBindingType", "StageAbilityBeforeCharacterBorn");
    row.addProperty("InBattleBindingKey", key);
    var parameters = new com.google.gson.JsonArray();
    for (double value : values) {
      var entry = new com.google.gson.JsonObject();
      entry.addProperty("Value", value);
      parameters.add(entry);
    }
    row.add("ParamList", parameters);
    GameData.getMazeBuffExcelMap().add(base.fixture.gson.fromJson(row, MazeBuffExcel.class));
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
                  .removeIf(v -> v.getID() == bindingId(id) && v.getLv() == 1);
              if (oldBuffs.get(id) != null) GameData.getMazeBuffExcelMap().add(oldBuffs.get(id));
            });
  }

  void owned(int id) {
    base.run().getMiracles().put(id, new GameplayState.RogueMiracle(0, VALUES.get(id)));
  }

  List<DynamicMessage> nativeBindings(Battle battle) throws Exception {
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var message =
        bridge
            .clientSchema()
            .parse(
                "SceneBattleInfo",
                bridge.translate("SceneBattleInfo", battle.toProto().toByteArray(), true));
    return (List<DynamicMessage>)
        message.getField(message.getDescriptorForType().findFieldByName("buff_list"));
  }

  @ParameterizedTest
  @ValueSource(ints = {46, 48, 50, 52, 54, 56})
  void theExactRepairedCurioCanBeAcquiredOnceAndHasItsActualNativeResourceBinding(int id)
      throws Exception {
    int coins = base.run().getCoins(), mp = base.run().getMp();
    String vitals = base.fixture.gson.toJson(base.run().getVitals());
    assertTrue(RogueMiracles.acquire(base.fixture.p, id));
    assertFalse(RogueMiracles.acquire(base.fixture.p, id));
    var item = base.run().getMiracles().get(id);
    assertEquals(VALUES.get(id), item.getEffectParameters());
    assertFalse(item.getAcquisitionId().isBlank());
    assertEquals(0, item.getTotalCount());
    assertEquals(0, item.getUseCount());
    var rows = nativeBindings(base.encounter());
    assertEquals(1, rows.size());
    var buff = rows.getFirst();
    var fields = buff.getDescriptorForType();
    assertEquals(bindingId(id), buff.getField(fields.findFieldByName("id")));
    assertEquals(1, buff.getField(fields.findFieldByName("level")));
    assertEquals(-1, buff.getField(fields.findFieldByName("owner_index")));
    assertEquals(-1, buff.getField(fields.findFieldByName("wave_flag")));
    assertTrue(((List<?>) buff.getField(fields.findFieldByName("dynamic_values"))).isEmpty());
    assertEquals(coins, base.run().getCoins());
    assertEquals(mp, base.run().getMp());
    assertEquals(vitals, base.fixture.gson.toJson(base.run().getVitals()));
  }

  @ParameterizedTest
  @ValueSource(ints = {46, 48, 50, 52, 54, 56})
  void wrongDisplayEffectAndParameterAssociationsCannotBeAcquired(int id) {
    GameplayDefinitions.miracles.put(
        id, new GameplayDefinitions.Miracle(id, displayId(id) + 1, id));
    assertFalse(RogueMiracles.acquire(base.fixture.p, id));
    GameplayDefinitions.miracles.put(
        id, new GameplayDefinitions.Miracle(id, displayId(id), id - 1));
    assertFalse(RogueMiracles.acquire(base.fixture.p, id));
    GameplayDefinitions.miracles.put(id, new GameplayDefinitions.Miracle(id, displayId(id), id));
    effect(id, List.of(.9));
    assertFalse(RogueMiracles.acquire(base.fixture.p, id));
    assertTrue(base.run().getMiracles().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {46, 48, 50, 52, 54, 56})
  void wrongOrMissingBindingDoesNotGrantAnUnrelatedAbility(int id) {
    binding(id, "StageAbility_" + bindingId(id - 1), VALUES.get(id));
    assertFalse(RogueMiracles.acquire(base.fixture.p, id));
    binding(id, "StageAbility_" + bindingId(id), List.of(.9));
    assertFalse(RogueMiracles.supported(id));
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == bindingId(id));
    assertFalse(RogueMiracles.supported(id));
    owned(id);
    assertFalse(base.encounter().hasBuff(bindingId(id)));
  }

  @ParameterizedTest
  @ValueSource(ints = {46, 48, 50, 52, 54, 56})
  void aPausedExplorationDoesNotApplyCodeEffectsToAFormalBattle(int id) {
    owned(id);
    base.run().setInside(false);
    assertFalse(base.encounter().hasBuff(bindingId(id)));
  }

  @ParameterizedTest
  @ValueSource(ints = {46, 48, 50, 52, 54, 56})
  void aPreparationRetryDoesNotBorrowALaterAcquisitionButTheNextBattleUsesIt(int id) {
    var before = base.encounter();
    assertTrue(RogueMiracles.acquire(base.fixture.p, id));
    base.fixture.p.getUniverseManager().onBattlePrepared(before);
    assertFalse(before.hasBuff(bindingId(id)));
    var after = base.encounter();
    assertTrue(after.hasBuff(bindingId(id)));
    effect(id, List.of(.9));
    base.fixture.p.getUniverseManager().onBattlePrepared(after);
    assertTrue(after.hasBuff(bindingId(id)));
  }

  @ParameterizedTest
  @ValueSource(ints = {46, 48, 50, 52, 54, 56})
  void legacyAndInvalidLifetimesCannotBorrowTheRepairedEffect(int id) {
    base.run().getMiracles().put(id, new GameplayState.RogueMiracle(0));
    assertFalse(base.encounter().hasBuff(bindingId(id)));
    base.run().getMiracles().put(id, new GameplayState.RogueMiracle(1, VALUES.get(id)));
    assertFalse(base.encounter().hasBuff(bindingId(id)));
    base.run().getMiracles().put(id, new GameplayState.RogueMiracle(0, List.of(.9)));
    assertFalse(base.encounter().hasBuff(bindingId(id)));
  }

  @Test
  void allSixRepairedAbilitiesCoexistWithoutChangingTheFormalParty() throws Exception {
    for (int id : VALUES.keySet()) assertTrue(RogueMiracles.acquire(base.fixture.p, id));
    var ids = new HashSet<Integer>();
    for (var row : nativeBindings(base.encounter()))
      assertTrue(ids.add((Integer) row.getField(row.getDescriptorForType().findFieldByName("id"))));
    assertEquals(Set.of(631014, 631016, 631018, 631020, 631022, 631024), ids);
    assertEquals(6, base.run().getMiracles().size());
    assertEquals(50, base.run().getCoins());
  }

  @Test
  void actualMorphiaTwoLoadsRestoreAllSixFrozenAcquisitionsAndTheirNativeBindings()
      throws Exception {
    var acquisitions = new HashMap<Integer, String>();
    for (int id : VALUES.keySet()) {
      assertTrue(RogueMiracles.acquire(base.fixture.p, id));
      acquisitions.put(id, base.run().getMiracles().get(id).getAcquisitionId());
    }
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "repaired-codes");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456780);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      for (int i = 0; i < 2; i++) {
        gameplay.set(document, base.fixture.p.getGameplay());
        store.save(document);
        gameplay.set(
            base.fixture.p,
            store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
        for (int id : VALUES.keySet()) {
          assertEquals(acquisitions.get(id), base.run().getMiracles().get(id).getAcquisitionId());
          assertEquals(VALUES.get(id), base.run().getMiracles().get(id).getEffectParameters());
        }
        assertEquals(6, nativeBindings(base.encounter()).size());
      }
    } finally {
      mongo.shutdownNow();
    }
  }
}
