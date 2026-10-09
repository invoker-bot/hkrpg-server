package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;

class RogueBerserkMiracleTest {
  final RogueBattleMiraclesTest base = new RogueBattleMiraclesTest();
  GameplayDefinitions.Miracle oldMiracle;
  GameplayDefinitions.MiracleEffect oldEffect;
  MazeBuffExcel oldBuff;

  @BeforeEach
  void setup() {
    base.setup();
    oldMiracle = GameplayDefinitions.miracles.get(59);
    oldEffect = GameplayDefinitions.miracleEffects.get(59);
    oldBuff = GameData.getMazeBuffExcelMap().get(633107, 1);
    GameplayDefinitions.miracles.put(59, new GameplayDefinitions.Miracle(59, 40, 59));
    GameplayDefinitions.miracleEffects.put(59, effect(.5, .2));
    binding(.5, .2, "StageAbility_633107");
  }

  GameplayDefinitions.MiracleEffect effect(double... values) {
    return new GameplayDefinitions.MiracleEffect(
        59, Arrays.stream(values).mapToObj(GameplayDefinitions.EffectValue::new).toList());
  }

  void binding(double attack, double loss, String key) {
    GameData.getMazeBuffExcelMap()
        .add(
            base.fixture.gson.fromJson(
                "{\"ID\":633107,\"Lv\":1,\"LvMax\":1,"
                    + "\"InBattleBindingType\":\"StageAbilityBeforeCharacterBorn\","
                    + "\"InBattleBindingKey\":\""
                    + key
                    + "\",\"ParamList\":["
                    + "{\"Value\":"
                    + attack
                    + "},{\"Value\":"
                    + loss
                    + "}]}",
                MazeBuffExcel.class));
  }

  @AfterEach
  void cleanup() {
    base.cleanup();
    GameplayDefinitions.miracles.remove(59);
    GameplayDefinitions.miracleEffects.remove(59);
    if (oldMiracle != null) GameplayDefinitions.miracles.put(59, oldMiracle);
    if (oldEffect != null) GameplayDefinitions.miracleEffects.put(59, oldEffect);
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 633107 && v.getLv() == 1);
    if (oldBuff != null) GameData.getMazeBuffExcelMap().add(oldBuff);
  }

  void owned() {
    base.run().getMiracles().put(59, new GameplayState.RogueMiracle(0, List.of(.5, .2)));
  }

  @Test
  void acquisitionFreezesThePermanentCurioWithoutChangingExplorationVitalsOrPoints() {
    int mp = base.run().getMp();
    String vitals = base.fixture.gson.toJson(base.run().getVitals());
    assertTrue(RogueMiracles.acquire(base.fixture.p, 59));
    assertFalse(RogueMiracles.acquire(base.fixture.p, 59));
    var item = base.run().getMiracles().get(59);
    assertEquals(List.of(.5, .2), item.getEffectParameters());
    assertFalse(item.getAcquisitionId().isBlank());
    assertEquals(0, item.getTotalCount());
    assertEquals(0, item.getUseCount());
    assertEquals(mp, base.run().getMp());
    assertEquals(vitals, base.fixture.gson.toJson(base.run().getVitals()));
  }

  @Test
  void actualEncounterSendsTheResourceAbilityAndLeavesTargetAndTurnEffectsToIt() throws Exception {
    owned();
    String vitals = base.fixture.gson.toJson(base.run().getVitals());
    int lineupHp =
        base.fixture.p.getAvatarById(199961).getCurrentHp(base.fixture.p.getCurrentLineup());
    var battle = base.encounter();
    var binding = battle.getBuffs().get(633107);
    assertNotNull(binding, "The acquired berserk/HP-loss resource ability is missing");
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var message =
        bridge
            .clientSchema()
            .parse(
                "SceneBattleInfo",
                bridge.translate("SceneBattleInfo", battle.toProto().toByteArray(), true));
    var rows =
        (List<com.google.protobuf.DynamicMessage>)
            message.getField(message.getDescriptorForType().findFieldByName("buff_list"));
    assertEquals(1, rows.size());
    var row = rows.getFirst();
    assertEquals(633107, row.getField(row.getDescriptorForType().findFieldByName("id")));
    assertEquals(1, row.getField(row.getDescriptorForType().findFieldByName("level")));
    assertEquals(-1, row.getField(row.getDescriptorForType().findFieldByName("owner_index")));
    assertEquals(-1, row.getField(row.getDescriptorForType().findFieldByName("wave_flag")));
    assertEquals(
        List.of(), row.getField(row.getDescriptorForType().findFieldByName("dynamic_values")));
    assertEquals(vitals, base.fixture.gson.toJson(base.run().getVitals()));
    assertEquals(
        lineupHp,
        base.fixture.p.getAvatarById(199961).getCurrentHp(base.fixture.p.getCurrentLineup()));
    assertEquals(0, base.run().getMiracles().get(59).getUseCount());
  }

  @Test
  void persistedAcquisitionKeepsItsParametersAndIdentityAfterCurrentDefinitionsChange()
      throws Exception {
    assertTrue(RogueMiracles.acquire(base.fixture.p, 59));
    String acquisition = base.run().getMiracles().get(59).getAcquisitionId();
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(
        base.fixture.p,
        base.fixture.gson.fromJson(
            base.fixture.gson.toJson(base.fixture.p.getGameplay()), GameplayState.class));
    GameplayDefinitions.miracles.remove(59);
    GameplayDefinitions.miracleEffects.put(59, effect(.9, .9));
    assertEquals(acquisition, base.run().getMiracles().get(59).getAcquisitionId());
    assertEquals(List.of(.5, .2), base.run().getMiracles().get(59).getEffectParameters());
    assertTrue(base.encounter().hasBuff(633107));
  }

  @Test
  void acquisitionAfterPreparationCannotBeBorrowedByAPreparationRetry() {
    var battle = base.encounter();
    assertTrue(RogueMiracles.acquire(base.fixture.p, 59));
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertFalse(battle.hasBuff(633107));
  }

  @Test
  void wrongDisplayEffectIdentityAndParametersAreNeverOffered() {
    for (var row :
        List.of(
            new GameplayDefinitions.Miracle(59, 39, 59),
            new GameplayDefinitions.Miracle(59, 40, 58),
            new GameplayDefinitions.Miracle(58, 40, 59))) {
      GameplayDefinitions.miracles.put(59, row);
      assertFalse(RogueMiracles.canAcquire(base.fixture.p, 59));
    }
    GameplayDefinitions.miracles.put(59, new GameplayDefinitions.Miracle(59, 40, 59));
    for (var row :
        List.of(
            effect(.5),
            effect(.5, .3),
            effect(.5, .2, 1),
            new GameplayDefinitions.MiracleEffect(
                58,
                List.of(
                    new GameplayDefinitions.EffectValue(.5),
                    new GameplayDefinitions.EffectValue(.2))))) {
      GameplayDefinitions.miracleEffects.put(59, row);
      assertFalse(RogueMiracles.acquire(base.fixture.p, 59));
    }
    assertTrue(base.run().getMiracles().isEmpty());
  }

  @Test
  void wrongOrMissingResourceAbilityCannotBeSilentlySubstituted() {
    binding(.5, .3, "StageAbility_633107");
    assertFalse(RogueMiracles.supported(59));
    binding(.5, .2, "StageAbility_633104");
    assertFalse(RogueMiracles.canAcquire(base.fixture.p, 59));
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 633107);
    assertFalse(RogueMiracles.acquire(base.fixture.p, 59));
    owned();
    assertFalse(base.encounter().hasBuff(633107));
  }

  @Test
  void incompleteLegacyOrInvalidPermanentRecordsCannotGainCurrentResourceParameters() {
    for (var item :
        List.of(
            new GameplayState.RogueMiracle(0),
            new GameplayState.RogueMiracle(1, List.of(.5, .2)),
            new GameplayState.RogueMiracle(0, List.of(.5, .3)),
            base.fixture.gson.fromJson(
                "{\"totalCount\":0,\"useCount\":0,\"effectParameters\":[0.5,0.2],\"acquisitionId\":\"\"}",
                GameplayState.RogueMiracle.class))) {
      base.run().getMiracles().put(59, item);
      assertFalse(base.encounter().hasBuff(633107));
    }
  }

  @Test
  void pausedWrongEntryWrongRoomAndUnmappedNpcCannotApplyTheEffect() {
    owned();
    base.run().setInside(false);
    assertFalse(base.encounter().hasBuff(633107));
    base.run().setInside(true);
    base.fixture.p.entry = 11;
    assertFalse(base.encounter().hasBuff(633107));
    base.fixture.p.entry = 12;
    assertFalse(base.fixture.battle(base.fixture.monster(77, 2)).hasBuff(633107));
    base.run().getRooms().put(1, UniverseCombatFragmentsTest.ROOM + 1);
    assertFalse(base.encounter().hasBuff(633107));
  }

  @Test
  void anAlreadyPreparedBattleKeepsItsBindingAfterOwnershipOrResourceDamage() {
    owned();
    var prepared = base.encounter();
    assertTrue(prepared.hasBuff(633107));
    base.run().getMiracles().clear();
    binding(.5, .3, "StageAbility_633107");
    base.fixture.p.getUniverseManager().onBattlePrepared(prepared);
    assertEquals(1, prepared.toProto().getBuffList().length());
    owned();
    assertFalse(base.fixture.battle(base.fixture.monster(8, 2)).hasBuff(633107));
  }

  @Test
  void berserkCoexistsWithOtherEntryAbilitiesOnceAndAcquisitionRequiresExploration() {
    base.run().finish(GameplayState.RogueRun.Phase.FINISHED);
    assertFalse(RogueMiracles.acquire(base.fixture.p, 59));
    base.run().finish(GameplayState.RogueRun.Phase.EXPLORING);
    owned();
    base.snapshots();
    var battle = base.encounter();
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertEquals(Set.of(633102, 633107, 633111), battle.getBuffs().keySet());
  }
}
