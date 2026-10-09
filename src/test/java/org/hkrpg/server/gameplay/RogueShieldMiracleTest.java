package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;

class RogueShieldMiracleTest {
  final RogueBattleMiraclesTest base = new RogueBattleMiraclesTest();
  GameplayDefinitions.Miracle oldMiracle;
  GameplayDefinitions.MiracleEffect oldEffect;
  MazeBuffExcel oldBuff;

  @BeforeEach
  void setup() {
    base.setup();
    oldMiracle = GameplayDefinitions.miracles.get(19);
    oldEffect = GameplayDefinitions.miracleEffects.get(19);
    oldBuff = GameData.getMazeBuffExcelMap().get(633105, 1);
    GameplayDefinitions.miracles.put(19, new GameplayDefinitions.Miracle(19, 16, 19));
    GameplayDefinitions.miracleEffects.put(19, effect(1, 3, 5));
    base.binding(633105, 3, "StageAbility_633105");
  }

  GameplayDefinitions.MiracleEffect effect(double... values) {
    return new GameplayDefinitions.MiracleEffect(
        19, Arrays.stream(values).mapToObj(GameplayDefinitions.EffectValue::new).toList());
  }

  @AfterEach
  void cleanup() {
    base.cleanup();
    GameplayDefinitions.miracles.remove(19);
    GameplayDefinitions.miracleEffects.remove(19);
    if (oldMiracle != null) GameplayDefinitions.miracles.put(19, oldMiracle);
    if (oldEffect != null) GameplayDefinitions.miracleEffects.put(19, oldEffect);
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 633105 && v.getLv() == 1);
    if (oldBuff != null) GameData.getMazeBuffExcelMap().add(oldBuff);
  }

  void owned() {
    base.run().getMiracles().put(19, new GameplayState.RogueMiracle(0, List.of(1.0, 3.0, 5.0)));
  }

  @Test
  void exactDiscAcquisitionFreezesItsParametersAndDoesNotSpendExplorationPoints() {
    int mp = base.run().getMp();
    assertTrue(RogueMiracles.acquire(base.fixture.p, 19));
    assertFalse(RogueMiracles.acquire(base.fixture.p, 19));
    var item = base.run().getMiracles().get(19);
    assertEquals(List.of(1.0, 3.0, 5.0), item.getEffectParameters());
    assertEquals(0, item.getTotalCount());
    assertEquals(0, item.getUseCount());
    assertFalse(item.getAcquisitionId().isBlank());
    assertEquals(mp, base.run().getMp());
  }

  @Test
  void aValidatedRoomBattleUsesTheActualFirstWaveShieldAndDebuffImmunityAbility() {
    owned();
    int hp = base.run().getVitals().get(199961).getHp();
    var battle = base.encounter();
    assertTrue(battle.hasBuff(633105));
    var buff = battle.getBuffs().get(633105);
    assertEquals(1, buff.getLevel());
    assertEquals(-1, buff.getOwnerIndex());
    assertEquals(-1, buff.getWaveFlag());
    assertEquals(hp, base.run().getVitals().get(199961).getHp());
    assertEquals(0, base.run().getMiracles().get(19).getUseCount());
  }

  @Test
  void nativeBattleCarriesOnlyTheDeclaredShieldBinding() throws Exception {
    owned();
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var message =
        bridge
            .clientSchema()
            .parse(
                "SceneBattleInfo",
                bridge.translate(
                    "SceneBattleInfo", base.encounter().toProto().toByteArray(), true));
    var rows =
        (List<com.google.protobuf.DynamicMessage>)
            message.getField(message.getDescriptorForType().findFieldByName("buff_list"));
    assertEquals(1, rows.size());
    var buff = rows.getFirst();
    assertEquals(633105, buff.getField(buff.getDescriptorForType().findFieldByName("id")));
    assertTrue(
        ((List<?>) buff.getField(buff.getDescriptorForType().findFieldByName("dynamic_values")))
            .isEmpty());
  }

  @Test
  void frozenDiscSurvivesGsonReloadAndChangedCurrentCurioDefinitions() throws Exception {
    assertTrue(RogueMiracles.acquire(base.fixture.p, 19));
    var id = base.run().getMiracles().get(19).getAcquisitionId();
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(
        base.fixture.p,
        base.fixture.gson.fromJson(
            base.fixture.gson.toJson(base.fixture.p.getGameplay()), GameplayState.class));
    GameplayDefinitions.miracleEffects.put(19, effect(1, 10, 5));
    GameplayDefinitions.miracles.remove(19);
    assertEquals(id, base.run().getMiracles().get(19).getAcquisitionId());
    assertTrue(base.encounter().hasBuff(633105));
  }

  @Test
  void aLaterAcquisitionAndRepeatedPreparationCannotInjectTheShieldIntoAnOldBattle() {
    var battle = base.encounter();
    owned();
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertFalse(battle.hasBuff(633105));
  }

  @Test
  void wrongAssociationsParametersAndMissingBindingsRejectAcquisition() {
    for (var row :
        List.of(
            new GameplayDefinitions.Miracle(19, 15, 19),
            new GameplayDefinitions.Miracle(19, 16, 18))) {
      GameplayDefinitions.miracles.put(19, row);
      assertFalse(RogueMiracles.canAcquire(base.fixture.p, 19));
    }
    GameplayDefinitions.miracles.put(19, new GameplayDefinitions.Miracle(19, 16, 19));
    GameplayDefinitions.miracleEffects.put(19, effect(1, 10, 5));
    assertFalse(RogueMiracles.acquire(base.fixture.p, 19));
    GameplayDefinitions.miracleEffects.put(19, effect(1, 3, 5));
    base.binding(633105, 10, "StageAbility_633105");
    assertFalse(RogueMiracles.supported(19));
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 633105);
    assertFalse(RogueMiracles.supported(19));
  }

  @Test
  void legacyWrongLifetimeAndWrongFrozenParametersDoNotAcquireTheCurrentEffect() {
    for (var item :
        List.of(
            new GameplayState.RogueMiracle(0),
            new GameplayState.RogueMiracle(1, List.of(1.0, 3.0, 5.0)),
            new GameplayState.RogueMiracle(0, List.of(1.0, 10.0, 5.0)))) {
      base.run().getMiracles().put(19, item);
      assertFalse(base.encounter().hasBuff(633105));
    }
  }

  @Test
  void pausedWrongRoomAndUnmappedNpcCannotBindTheShield() {
    owned();
    base.run().setInside(false);
    assertFalse(base.encounter().hasBuff(633105));
    base.run().setInside(true);
    base.fixture.p.entry = 11;
    assertFalse(base.encounter().hasBuff(633105));
    base.fixture.p.entry = 12;
    assertFalse(base.fixture.battle(base.fixture.monster(77, 2)).hasBuff(633105));
  }

  @Test
  void futureResourceDamageCannotMutateAnAlreadyPreparedShield() {
    owned();
    var prepared = base.encounter();
    assertTrue(prepared.hasBuff(633105));
    base.binding(633105, 10, "StageAbility_633105");
    assertFalse(base.fixture.battle(base.fixture.monster(8, 2)).hasBuff(633105));
    assertTrue(prepared.hasBuff(633105));
    assertEquals(1, prepared.toProto().getBuffList().length());
  }

  @Test
  void shieldCoexistsWithEntryDamageAndBoostPointAbilitiesWithoutDuplicatePreparation() {
    owned();
    base.snapshots();
    var battle = base.encounter();
    assertEquals(Set.of(633102, 633105, 633111), battle.getBuffs().keySet());
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertEquals(3, battle.getBuffs().size());
  }
}
