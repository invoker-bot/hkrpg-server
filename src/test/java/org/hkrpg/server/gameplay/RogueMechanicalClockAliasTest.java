package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;

class RogueMechanicalClockAliasTest {
  final RogueBattleMiraclesTest base = new RogueBattleMiraclesTest();
  GameplayDefinitions.Miracle previous;

  @BeforeEach
  void setup() {
    base.setup();
    previous = GameplayDefinitions.miracles.get(10);
    GameplayDefinitions.miracles.put(10, new GameplayDefinitions.Miracle(10, 52, 71));
  }

  @AfterEach
  void cleanup() {
    base.cleanup();
    GameplayDefinitions.miracles.remove(10);
    if (previous != null) GameplayDefinitions.miracles.put(10, previous);
  }

  @Test
  void exactSourceAliasUsesEffectSeventyOneAndKeepsActualOwnedIdTen() {
    int mp = base.run().getMp();
    assertTrue(RogueMiracles.canAcquire(base.fixture.p, 10));
    assertTrue(RogueMiracles.acquire(base.fixture.p, 10));
    assertFalse(base.run().getMiracles().containsKey(71));
    var item = base.run().getMiracles().get(10);
    assertEquals(List.of(2.0), item.getEffectParameters());
    assertEquals(0, item.getUseCount());
    assertEquals(0, item.getTotalCount());
    assertFalse(item.getAcquisitionId().isBlank());
    assertEquals(mp, base.run().getMp());
    assertFalse(RogueMiracles.acquire(base.fixture.p, 10));
  }

  @Test
  void actualRoomBattleBindsOneNegativeFirstWaveAbilityForTheAlias() {
    assertTrue(RogueMiracles.acquire(base.fixture.p, 10));
    var battle = base.encounter();
    assertTrue(battle.hasBuff(633111));
    assertEquals(1, battle.getBuffs().size());
    assertEquals(-1, battle.getBuffs().get(633111).getWaveFlag());
    assertEquals(0, battle.getBuffs().get(633111).toProto().getDynamicValues().length());
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertEquals(1, battle.getBuffs().size());
  }

  @Test
  void nativeBattleResponseCarriesTheSameResourceBindingWithoutRenumberingOwnership()
      throws Exception {
    assertTrue(RogueMiracles.acquire(base.fixture.p, 10));
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var m =
        bridge
            .clientSchema()
            .parse(
                "SceneBattleInfo",
                bridge.translate(
                    "SceneBattleInfo", base.encounter().toProto().toByteArray(), true));
    var buffs = (List<?>) m.getField(m.getDescriptorForType().findFieldByName("buff_list"));
    assertEquals(1, buffs.size());
    var b = (com.google.protobuf.DynamicMessage) buffs.getFirst();
    assertEquals(633111, b.getField(b.getDescriptorForType().findFieldByName("id")));
    assertEquals(Set.of(10), base.run().getMiracles().keySet());
  }

  @Test
  void canonicalAndAliasCannotBeAcquiredTogetherInEitherOrder() {
    for (var ids : List.of(List.of(10, 71), List.of(71, 10))) {
      base.run().getMiracles().clear();
      assertTrue(RogueMiracles.acquire(base.fixture.p, ids.getFirst()));
      assertFalse(RogueMiracles.canAcquire(base.fixture.p, ids.getLast()));
      assertFalse(RogueMiracles.acquire(base.fixture.p, ids.getLast()));
      assertEquals(1, base.run().getMiracles().size());
    }
  }

  @Test
  void bothIdsInACorruptRecordCannotApplyAnEffectTwiceOrPublishItAsValid() {
    base.run().getMiracles().put(10, new GameplayState.RogueMiracle(0, List.of(2.0)));
    base.run().getMiracles().put(71, new GameplayState.RogueMiracle(0, List.of(2.0)));
    assertFalse(RogueMiracles.active(base.run(), 10));
    assertFalse(RogueMiracles.active(base.run(), 71));
    assertFalse(base.encounter().hasBuff(633111));
  }

  @Test
  void wrongDisplayEffectAndAliasRowIdentityCannotBorrowTheCanonicalEffect() {
    for (var row :
        List.of(
            new GameplayDefinitions.Miracle(10, 1, 71),
            new GameplayDefinitions.Miracle(10, 52, 10),
            new GameplayDefinitions.Miracle(71, 52, 71))) {
      GameplayDefinitions.miracles.put(10, row);
      assertFalse(RogueMiracles.acquire(base.fixture.p, 10));
    }
    assertTrue(base.run().getMiracles().isEmpty());
  }

  @Test
  void absentOrWrongCanonicalParametersAndBindingCannotBeAcquired() {
    GameplayDefinitions.miracleEffects.remove(71);
    assertFalse(RogueMiracles.acquire(base.fixture.p, 10));
    GameplayDefinitions.miracleEffects.put(
        71,
        new GameplayDefinitions.MiracleEffect(71, List.of(new GameplayDefinitions.EffectValue(3))));
    assertFalse(RogueMiracles.acquire(base.fixture.p, 10));
    GameplayDefinitions.miracleEffects.put(
        71,
        new GameplayDefinitions.MiracleEffect(71, List.of(new GameplayDefinitions.EffectValue(2))));
    base.binding(633111, 3, "StageAbility_633111");
    assertFalse(RogueMiracles.acquire(base.fixture.p, 10));
    base.binding(633111, 2, "StageAbility_633111");
    assertTrue(RogueMiracles.acquire(base.fixture.p, 10));
  }

  @Test
  void aPreparedBindingSurvivesRemovalOfTheAliasAndCurrentEffectDefinition() {
    assertTrue(RogueMiracles.acquire(base.fixture.p, 10));
    var battle = base.encounter();
    base.run().getMiracles().clear();
    GameplayDefinitions.miracles.remove(10);
    GameplayDefinitions.miracleEffects.remove(71);
    assertTrue(battle.hasBuff(633111));
    assertEquals(1, battle.toProto().getBuffList().length());
  }

  @Test
  void aliasAcquiredAfterBattlePreparationCannotBeInjectedIntoThatBattle() {
    var battle = base.encounter();
    base.run().getMiracles().put(10, new GameplayState.RogueMiracle(0, List.of(2.0)));
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertFalse(battle.hasBuff(633111));
  }

  @Test
  void aliasOwnershipAndFrozenAcquisitionActuallySurviveMorphiaReload() throws Exception {
    assertTrue(RogueMiracles.acquire(base.fixture.p, 10));
    var acquired = base.run().getMiracles().get(10).getAcquisitionId();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var a = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + a.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-alias");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var doc = new emu.lunarcore.game.player.Player();
      var uid = doc.getClass().getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(doc, 123456788);
      var state = doc.getClass().getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(doc, base.fixture.p.getGameplay());
      store.save(doc);
      var restored = store.find(emu.lunarcore.game.player.Player.class).first();
      var run = restored.getGameplay().getRogue();
      assertEquals(Set.of(10), run.getMiracles().keySet());
      assertEquals(acquired, run.getMiracles().get(10).getAcquisitionId());
      assertTrue(RogueMiracles.active(run, 10));
      assertFalse(RogueMiracles.canAcquire(restored, 71));
      assertEquals(List.of(2.0), run.getMiracles().get(10).getEffectParameters());
    } finally {
      mongo.shutdownNow();
    }
  }
}
