package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;

class RogueWeaknessMiracleTest {
  final RogueBattleMiraclesTest base = new RogueBattleMiraclesTest();
  GameplayDefinitions.Miracle oldMiracle;
  GameplayDefinitions.MiracleEffect oldEffect;
  MazeBuffExcel oldBuff;

  @BeforeEach
  void setup() {
    base.setup();
    oldMiracle = GameplayDefinitions.miracles.get(68);
    oldEffect = GameplayDefinitions.miracleEffects.get(68);
    oldBuff = GameData.getMazeBuffExcelMap().get(633110, 1);
    GameplayDefinitions.miracles.put(68, new GameplayDefinitions.Miracle(68, 49, 68));
    GameplayDefinitions.miracleEffects.put(68, effect(1, 1, 3));
    binding(1, 10);
  }

  GameplayDefinitions.MiracleEffect effect(double... values) {
    return new GameplayDefinitions.MiracleEffect(
        68, Arrays.stream(values).mapToObj(GameplayDefinitions.EffectValue::new).toList());
  }

  void binding(double chance, double duration) {
    GameData.getMazeBuffExcelMap()
        .add(
            base.fixture.gson.fromJson(
                "{\"ID\":633110,\"Lv\":1,\"LvMax\":1,\"InBattleBindingType\":\"StageAbilityBeforeCharacterBorn\","
                    + "\"InBattleBindingKey\":\"StageAbility_633110\",\"ParamList\":[{\"Value\":"
                    + chance
                    + "},{\"Value\":"
                    + duration
                    + "}]}",
                MazeBuffExcel.class));
  }

  @AfterEach
  void cleanup() {
    base.cleanup();
    GameplayDefinitions.miracles.remove(68);
    GameplayDefinitions.miracleEffects.remove(68);
    if (oldMiracle != null) GameplayDefinitions.miracles.put(68, oldMiracle);
    if (oldEffect != null) GameplayDefinitions.miracleEffects.put(68, oldEffect);
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 633110 && v.getLv() == 1);
    if (oldBuff != null) GameData.getMazeBuffExcelMap().add(oldBuff);
  }

  void owned() {
    base.run().getMiracles().put(68, new GameplayState.RogueMiracle(0, List.of(1.0, 1.0, 3.0)));
  }

  Map<String, Float> values(emu.lunarcore.game.battle.Battle battle) {
    var buff = battle.getBuffs().get(633110);
    assertNotNull(buff, "Punklorde's resource battle ability is missing");
    var result = new HashMap<String, Float>();
    for (var entry : buff.toProto().getDynamicValues()) {
      assertNull(result.put(entry.getKey(), entry.getValue()));
    }
    return result;
  }

  @Test
  void exactAcquisitionFreezesChanceCountAndThreeTurnsWithoutSpendingTechniquePoints() {
    int mp = base.run().getMp();
    assertTrue(RogueMiracles.acquire(base.fixture.p, 68));
    assertFalse(RogueMiracles.acquire(base.fixture.p, 68));
    var item = base.run().getMiracles().get(68);
    assertEquals(List.of(1.0, 1.0, 3.0), item.getEffectParameters());
    assertEquals(0, item.getTotalCount());
    assertEquals(0, item.getUseCount());
    assertFalse(item.getAcquisitionId().isBlank());
    assertEquals(mp, base.run().getMp());
  }

  @Test
  void actualRoomBattleOverridesTenDefaultTurnsWithThreeFrozenTurns() {
    owned();
    var battle = base.encounter();
    assertEquals(Map.of("#ADF_1", 1f, "#ADF_2", 3f), values(battle));
    var buff = battle.getBuffs().get(633110);
    assertEquals(1, buff.getLevel());
    assertEquals(-1, buff.getOwnerIndex());
    assertEquals(-1, buff.getWaveFlag());
    assertEquals(10, GameData.getMazeBuffExcelMap().get(633110, 1).getParamList().get(1).Value());
  }

  @Test
  void nativeBattleCarriesTheExactOverrideKeysAndFloatValues() throws Exception {
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
    assertEquals(633110, buff.getField(buff.getDescriptorForType().findFieldByName("id")));
    var entries =
        (List<com.google.protobuf.DynamicMessage>)
            buff.getField(buff.getDescriptorForType().findFieldByName("dynamic_values"));
    var result = new HashMap<String, Float>();
    for (var entry : entries) {
      assertNull(
          result.put(
              (String) entry.getField(entry.getDescriptorForType().findFieldByName("key")),
              (Float) entry.getField(entry.getDescriptorForType().findFieldByName("value"))));
    }
    assertEquals(Map.of("#ADF_1", 1f, "#ADF_2", 3f), result);
  }

  @Test
  void frozenOverridesSurviveGsonReloadAndChangesToCurrentCurioDefinitions() throws Exception {
    assertTrue(RogueMiracles.acquire(base.fixture.p, 68));
    var id = base.run().getMiracles().get(68).getAcquisitionId();
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(
        base.fixture.p,
        base.fixture.gson.fromJson(
            base.fixture.gson.toJson(base.fixture.p.getGameplay()), GameplayState.class));
    GameplayDefinitions.miracleEffects.put(68, effect(1, 1, 10));
    GameplayDefinitions.miracles.remove(68);
    assertEquals(id, base.run().getMiracles().get(68).getAcquisitionId());
    assertEquals(Map.of("#ADF_1", 1f, "#ADF_2", 3f), values(base.encounter()));
  }

  @Test
  void defaultResourceChangesCannotOfferOrSilentlySubstituteAnotherWeaknessEffect() {
    binding(.5, 10);
    assertFalse(RogueMiracles.canAcquire(base.fixture.p, 68));
    binding(1, 3);
    assertFalse(RogueMiracles.supported(68));
    binding(1, 10);
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 633110);
    assertFalse(RogueMiracles.supported(68));
    assertTrue(base.run().getMiracles().isEmpty());
  }

  @Test
  void wrongDisplayEffectOrFrozenCountIsRejected() {
    GameplayDefinitions.miracles.put(68, new GameplayDefinitions.Miracle(68, 48, 68));
    assertFalse(RogueMiracles.canAcquire(base.fixture.p, 68));
    GameplayDefinitions.miracles.put(68, new GameplayDefinitions.Miracle(68, 49, 67));
    assertFalse(RogueMiracles.acquire(base.fixture.p, 68));
    GameplayDefinitions.miracles.put(68, new GameplayDefinitions.Miracle(68, 49, 68));
    GameplayDefinitions.miracleEffects.put(68, effect(1, 2, 3));
    assertFalse(RogueMiracles.acquire(base.fixture.p, 68));
  }

  @Test
  void legacyWrongLifetimeAndWrongFrozenParametersCannotBorrowTheCurrentDefinition() {
    for (var item :
        List.of(
            new GameplayState.RogueMiracle(0),
            new GameplayState.RogueMiracle(1, List.of(1.0, 1.0, 3.0)),
            new GameplayState.RogueMiracle(0, List.of(1.0, 1.0, 10.0)),
            new GameplayState.RogueMiracle(0, List.of(1.0, 2.0, 3.0)))) {
      base.run().getMiracles().put(68, item);
      assertFalse(base.encounter().hasBuff(633110));
    }
  }

  @Test
  void pausedWrongRoomAndUnmappedNpcCannotBindWeakness() {
    owned();
    base.run().setInside(false);
    assertFalse(base.encounter().hasBuff(633110));
    base.run().setInside(true);
    base.fixture.p.entry = 11;
    assertFalse(base.encounter().hasBuff(633110));
    base.fixture.p.entry = 12;
    assertFalse(base.fixture.battle(base.fixture.monster(77, 2)).hasBuff(633110));
  }

  @Test
  void laterAcquisitionAndPrepareRetriesDoNotAddWeaknessToAnOldBattle() {
    var battle = base.encounter();
    owned();
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertFalse(battle.hasBuff(633110));
  }

  @Test
  void aPreparedOverrideIsFrozenWhenFutureBindingResourcesBecomeInvalid() {
    owned();
    var prepared = base.encounter();
    assertEquals(Map.of("#ADF_1", 1f, "#ADF_2", 3f), values(prepared));
    binding(1, 99);
    assertFalse(base.fixture.battle(base.fixture.monster(8, 2)).hasBuff(633110));
    base.run().getMiracles().clear();
    assertEquals(Map.of("#ADF_1", 1f, "#ADF_2", 3f), values(prepared));
  }

  @Test
  void weaknessCoexistsWithEntryDamageAndBoostPointsAndRepeatedPreparationDoesNotDuplicate() {
    owned();
    base.snapshots();
    var battle = base.encounter();
    assertEquals(Set.of(633102, 633110, 633111), battle.getBuffs().keySet());
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertEquals(3, battle.getBuffs().size());
    assertEquals(Map.of("#ADF_1", 1f, "#ADF_2", 3f), values(battle));
  }

  @Test
  void nativePreparationDoesNotChangeFrozenOwnershipHealthCoinsOrTechniquePoints() {
    owned();
    var before = base.fixture.gson.toJson(base.fixture.p.getGameplay());
    base.encounter();
    assertEquals(before, base.fixture.gson.toJson(base.fixture.p.getGameplay()));
  }
}
