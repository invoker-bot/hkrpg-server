package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueBeautyRobeTest {
  final RogueBattleMiraclesTest base = new RogueBattleMiraclesTest();
  GameplayDefinitions.Miracle oldMiracle;
  GameplayDefinitions.MiracleEffect oldEffect;
  MazeBuffExcel oldBuff;

  @BeforeEach
  void setup() {
    base.setup();
    oldMiracle = GameplayDefinitions.miracles.get(14);
    oldEffect = GameplayDefinitions.miracleEffects.get(14);
    oldBuff = GameData.getMazeBuffExcelMap().get(633104, 1);
    GameplayDefinitions.miracles.put(14, new GameplayDefinitions.Miracle(14, 12, 14));
    GameplayDefinitions.miracleEffects.put(14, effect(100, .16, 5));
    binding(100, .2, "StageAbility_633104");
  }

  GameplayDefinitions.MiracleEffect effect(double... values) {
    return new GameplayDefinitions.MiracleEffect(
        14, Arrays.stream(values).mapToObj(GameplayDefinitions.EffectValue::new).toList());
  }

  void binding(double threshold, double ratio, String key) {
    GameData.getMazeBuffExcelMap()
        .add(
            base.fixture.gson.fromJson(
                "{\"ID\":633104,\"Lv\":1,\"LvMax\":1,"
                    + "\"InBattleBindingType\":\"StageAbilityBeforeCharacterBorn\","
                    + "\"InBattleBindingKey\":\""
                    + key
                    + "\",\"ParamList\":[{\"Value\":"
                    + threshold
                    + "},{\"Value\":"
                    + ratio
                    + "}]}",
                MazeBuffExcel.class));
  }

  @AfterEach
  void cleanup() {
    base.cleanup();
    GameplayDefinitions.miracles.remove(14);
    GameplayDefinitions.miracleEffects.remove(14);
    if (oldMiracle != null) GameplayDefinitions.miracles.put(14, oldMiracle);
    if (oldEffect != null) GameplayDefinitions.miracleEffects.put(14, oldEffect);
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 633104 && v.getLv() == 1);
    if (oldBuff != null) GameData.getMazeBuffExcelMap().add(oldBuff);
  }

  void owned() {
    base.run().getMiracles().put(14, new GameplayState.RogueMiracle(0, List.of(100.0, .16, 5.0)));
  }

  void balance(int coins) {
    base.run().addCoins(coins - base.run().getCoins());
  }

  Map<String, Float> nativeParameters(Battle battle) throws Exception {
    var binding = battle.getBuffs().get(633104);
    assertNotNull(binding, "The fragment-dependent beauty robe ability is missing");
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var sceneBattle =
        bridge
            .clientSchema()
            .parse(
                "SceneBattleInfo",
                bridge.translate("SceneBattleInfo", battle.toProto().toByteArray(), true));
    var rows =
        (List<DynamicMessage>)
            sceneBattle.getField(sceneBattle.getDescriptorForType().findFieldByName("buff_list"));
    var row =
        rows.stream()
            .filter(
                value ->
                    Objects.equals(
                        633104, value.getField(value.getDescriptorForType().findFieldByName("id"))))
            .findFirst()
            .orElseThrow();
    assertEquals(1, row.getField(row.getDescriptorForType().findFieldByName("level")));
    assertEquals(-1, row.getField(row.getDescriptorForType().findFieldByName("owner_index")));
    assertEquals(-1, row.getField(row.getDescriptorForType().findFieldByName("wave_flag")));
    var parameters =
        (List<DynamicMessage>)
            row.getField(row.getDescriptorForType().findFieldByName("dynamic_values"));
    var result = new HashMap<String, Float>();
    for (var value : parameters) {
      var fields = value.getDescriptorForType();
      assertNull(
          result.put(
              (String) value.getField(fields.findFieldByName("key")),
              (Float) value.getField(fields.findFieldByName("value"))),
          "Duplicate dynamic key");
    }
    return result;
  }

  @Test
  void acquisitionFreezesTheExactCurioWithoutSpendingFragmentsOrChangingVitals() {
    int coins = base.run().getCoins(), mp = base.run().getMp();
    String vitals = base.fixture.gson.toJson(base.run().getVitals());
    assertTrue(RogueMiracles.acquire(base.fixture.p, 14));
    assertFalse(RogueMiracles.acquire(base.fixture.p, 14));
    var item = base.run().getMiracles().get(14);
    assertEquals(List.of(100.0, .16, 5.0), item.getEffectParameters());
    assertFalse(item.getAcquisitionId().isBlank());
    assertEquals(0, item.getTotalCount());
    assertEquals(0, item.getUseCount());
    assertEquals(coins, base.run().getCoins());
    assertEquals(mp, base.run().getMp());
    assertEquals(vitals, base.fixture.gson.toJson(base.run().getVitals()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 99, 100, 101, 299, 300, 16_777_215})
  void actualBattleCarriesTheBalanceAndAcquiredRatioInsteadOfTheResourceDefault(int coins)
      throws Exception {
    owned();
    balance(coins);
    String vitals = base.fixture.gson.toJson(base.run().getVitals());
    assertEquals(
        Map.of("ItemNumber", (float) coins, "#ADF_1", 100f, "#ADF_2", .16f),
        nativeParameters(base.encounter()));
    assertEquals(coins, base.run().getCoins());
    assertEquals(vitals, base.fixture.gson.toJson(base.run().getVitals()));
  }

  @Test
  void preparationRetryKeepsItsOriginalBalanceWhileTheNextEncounterUsesTheNewBalance()
      throws Exception {
    owned();
    balance(299);
    var first = base.encounter();
    balance(99);
    base.fixture.p.getUniverseManager().onBattlePrepared(first);
    assertEquals(299f, nativeParameters(first).get("ItemNumber"));
    base.fixture.finish(first, BattleEndStatus.BATTLE_END_QUIT);
    var second = base.fixture.battle(base.fixture.monster(8, 2));
    assertEquals(99f, nativeParameters(second).get("ItemNumber"));
    assertEquals(99, base.run().getCoins());
    assertEquals(0, base.run().getMiracles().get(14).getUseCount());
  }

  @Test
  void savedAcquisitionRetainsItsIdentityAndParametersAfterDefinitionChanges() throws Exception {
    assertTrue(RogueMiracles.acquire(base.fixture.p, 14));
    balance(1234);
    String acquisition = base.run().getMiracles().get(14).getAcquisitionId();
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(
        base.fixture.p,
        base.fixture.gson.fromJson(
            base.fixture.gson.toJson(base.fixture.p.getGameplay()), GameplayState.class));
    GameplayDefinitions.miracles.remove(14);
    GameplayDefinitions.miracleEffects.put(14, effect(1, 1, 1));
    assertEquals(acquisition, base.run().getMiracles().get(14).getAcquisitionId());
    assertEquals(
        Map.of("ItemNumber", 1234f, "#ADF_1", 100f, "#ADF_2", .16f),
        nativeParameters(base.encounter()));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 16_777_216, Integer.MAX_VALUE})
  void balancesOutsideTheVerifiedFloatRangeDoNotCreateAnUnreliableDamageBinding(int coins) {
    owned();
    balance(coins);
    assertFalse(base.encounter().hasBuff(633104));
    assertEquals(coins, base.run().getCoins());
  }

  @Test
  void wrongDisplayEffectOrParameterShapesAreNotOffered() {
    for (var row :
        List.of(
            new GameplayDefinitions.Miracle(14, 13, 14),
            new GameplayDefinitions.Miracle(14, 12, 15),
            new GameplayDefinitions.Miracle(15, 12, 14))) {
      GameplayDefinitions.miracles.put(14, row);
      assertFalse(RogueMiracles.canAcquire(base.fixture.p, 14));
    }
    GameplayDefinitions.miracles.put(14, new GameplayDefinitions.Miracle(14, 12, 14));
    for (var row :
        List.of(
            effect(100, .2, 5),
            effect(100, .16),
            effect(0, .16, 5),
            effect(100, .16, 6),
            effect(100, .16, 5, 1),
            new GameplayDefinitions.MiracleEffect(
                15,
                List.of(
                    new GameplayDefinitions.EffectValue(100),
                    new GameplayDefinitions.EffectValue(.16),
                    new GameplayDefinitions.EffectValue(5))))) {
      GameplayDefinitions.miracleEffects.put(14, row);
      assertFalse(RogueMiracles.acquire(base.fixture.p, 14));
    }
    assertTrue(base.run().getMiracles().isEmpty());
  }

  @Test
  void missingOrAlteredSourceAbilityCannotBeSilentlySubstituted() {
    binding(100, .16, "StageAbility_633104");
    assertFalse(RogueMiracles.supported(14));
    binding(100, .2, "StageAbility_633107");
    assertFalse(RogueMiracles.acquire(base.fixture.p, 14));
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 633104);
    owned();
    assertFalse(base.encounter().hasBuff(633104));
  }

  @Test
  void legacyDamagedAndBrokenPermanentRecordsDoNotBorrowNewParameters() {
    var broken = new GameplayState.RogueMiracle(1, List.of(100.0, .16, 5.0));
    broken.consume();
    for (var item :
        List.of(
            new GameplayState.RogueMiracle(0),
            new GameplayState.RogueMiracle(1, List.of(100.0, .16, 5.0)),
            new GameplayState.RogueMiracle(0, List.of(100.0, .2, 5.0)),
            broken,
            base.fixture.gson.fromJson(
                "{\"totalCount\":0,\"useCount\":0,"
                    + "\"effectParameters\":[100,0.16,5],\"acquisitionId\":\"\"}",
                GameplayState.RogueMiracle.class))) {
      base.run().getMiracles().put(14, item);
      assertFalse(base.encounter().hasBuff(633104));
    }
  }

  @Test
  void lateAcquisitionCannotBeBorrowedByAPreparedBattle() {
    var battle = base.encounter();
    assertTrue(RogueMiracles.acquire(base.fixture.p, 14));
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertFalse(battle.hasBuff(633104));
  }

  @Test
  void explorationIsRequiredAndTheAbilityCoexistsWithOtherEntryEffectsOnce() {
    base.run().finish(GameplayState.RogueRun.Phase.FINISHED);
    assertFalse(RogueMiracles.acquire(base.fixture.p, 14));
    base.run().finish(GameplayState.RogueRun.Phase.EXPLORING);
    assertTrue(RogueMiracles.acquire(base.fixture.p, 14));
    base.snapshots();
    var battle = base.encounter();
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertEquals(Set.of(633102, 633104, 633111), battle.getBuffs().keySet());
  }

  @Test
  void pausedWrongEntryAndUnmappedContentCannotUseTheCurio() {
    owned();
    base.run().setInside(false);
    assertFalse(base.encounter().hasBuff(633104));
    base.run().setInside(true);
    base.fixture.p.entry = 11;
    assertFalse(base.encounter().hasBuff(633104));
    base.fixture.p.entry = 12;
    assertFalse(base.fixture.battle(base.fixture.monster(77, 2)).hasBuff(633104));
    base.run().getRooms().put(1, UniverseCombatFragmentsTest.ROOM + 1);
    assertFalse(base.encounter().hasBuff(633104));
  }
}
