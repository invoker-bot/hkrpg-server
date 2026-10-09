package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueRankAggroMiraclesTest {
  final RogueBattleMiraclesTest base = new RogueBattleMiraclesTest();
  final Map<Integer, GameplayDefinitions.Miracle> oldMiracles = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();
  final Map<Integer, MazeBuffExcel> oldBuffs = new HashMap<>();

  int buff(int id) {
    return id == 62 ? 633108 : 633109;
  }

  int display(int id) {
    return id == 62 ? 43 : 47;
  }

  List<Double> parameters(int id) {
    return id == 62 ? List.of(1.0) : List.of(1.0, 5.0);
  }

  @BeforeEach
  void setup() throws Exception {
    base.setup();
    var avatarData = new emu.lunarcore.game.avatar.AvatarData();
    avatarData.setRank(5);
    var data = emu.lunarcore.game.avatar.GameAvatar.class.getDeclaredField("data");
    data.setAccessible(true);
    data.set(base.fixture.p.battleAvatar, avatarData);
    for (int id : List.of(62, 66)) {
      var miracle = GameplayDefinitions.miracles.get(id);
      var effect = GameplayDefinitions.miracleEffects.get(id);
      var row = GameData.getMazeBuffExcelMap().get(buff(id), 1);
      if (miracle != null) oldMiracles.put(id, miracle);
      if (effect != null) oldEffects.put(id, effect);
      if (row != null) oldBuffs.put(buff(id), row);
      GameplayDefinitions.miracles.put(id, new GameplayDefinitions.Miracle(id, display(id), id));
      GameplayDefinitions.miracleEffects.put(
          id,
          new GameplayDefinitions.MiracleEffect(
              id, parameters(id).stream().map(GameplayDefinitions.EffectValue::new).toList()));
      binding(id);
    }
  }

  void binding(int id) {
    int buff = buff(id);
    GameData.getMazeBuffExcelMap()
        .add(
            base.fixture.gson.fromJson(
                "{\"ID\":"
                    + buff
                    + ",\"Lv\":1,\"LvMax\":1,\"InBattleBindingType\":\"StageAbilityBeforeCharacterBorn\","
                    + "\"InBattleBindingKey\":\"StageAbility_"
                    + buff
                    + "\",\"ParamList\":"
                    + (id == 62 ? "[{\"Value\":1}]" : "[{\"Value\":5},{\"Value\":5}]")
                    + "}",
                MazeBuffExcel.class));
  }

  @AfterEach
  void cleanup() {
    base.cleanup();
    for (int id : List.of(62, 66)) {
      GameplayDefinitions.miracles.remove(id);
      GameplayDefinitions.miracleEffects.remove(id);
      GameData.getMazeBuffExcelMap()
          .values()
          .removeIf(v -> v.getID() == buff(id) && v.getLv() == 1);
    }
    GameplayDefinitions.miracles.putAll(oldMiracles);
    GameplayDefinitions.miracleEffects.putAll(oldEffects);
    oldBuffs.values().forEach(GameData.getMazeBuffExcelMap()::add);
  }

  void owned(int id) {
    base.run().getMiracles().put(id, new GameplayState.RogueMiracle(0, parameters(id)));
  }

  @ParameterizedTest
  @ValueSource(ints = {62, 66})
  void acquisitionFreezesPermanentParametersWithoutChangingTheExplorationParty(int id) {
    int mp = base.run().getMp();
    var ranks =
        base.run().getRoster().stream()
            .map(v -> base.fixture.p.getAvatarById(v).getRank())
            .toList();
    assertTrue(RogueMiracles.acquire(base.fixture.p, id));
    assertFalse(RogueMiracles.acquire(base.fixture.p, id));
    var item = base.run().getMiracles().get(id);
    assertEquals(parameters(id), item.getEffectParameters());
    assertFalse(item.getAcquisitionId().isBlank());
    assertEquals(0, item.getTotalCount());
    assertEquals(0, item.getUseCount());
    assertEquals(mp, base.run().getMp());
    assertEquals(
        ranks,
        base.run().getRoster().stream()
            .map(v -> base.fixture.p.getAvatarById(v).getRank())
            .toList());
  }

  @ParameterizedTest
  @ValueSource(ints = {62, 66})
  void nativeActualEncounterCarriesTheResourceAbilityWithoutRewritingAvatarRanksOrVitals(int id)
      throws Exception {
    owned(id);
    String vitals = base.fixture.gson.toJson(base.run().getVitals());
    var ranks =
        base.run().getRoster().stream()
            .map(v -> base.fixture.p.getAvatarById(v).getRank())
            .toList();
    var battle = base.encounter();
    var binding = battle.getBuffs().get(buff(id));
    assertNotNull(binding, "The acquired rank/aggro resource ability is missing");
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var message =
        bridge
            .clientSchema()
            .parse(
                "SceneBattleInfo",
                bridge.translate("SceneBattleInfo", battle.toProto().toByteArray(), true));
    var bindings =
        (List<com.google.protobuf.DynamicMessage>)
            message.getField(message.getDescriptorForType().findFieldByName("buff_list"));
    assertEquals(1, bindings.size());
    var row = bindings.getFirst();
    assertEquals(buff(id), row.getField(row.getDescriptorForType().findFieldByName("id")));
    assertEquals(1, row.getField(row.getDescriptorForType().findFieldByName("level")));
    assertEquals(-1, row.getField(row.getDescriptorForType().findFieldByName("owner_index")));
    assertEquals(-1, row.getField(row.getDescriptorForType().findFieldByName("wave_flag")));
    assertEquals(
        List.of(), row.getField(row.getDescriptorForType().findFieldByName("dynamic_values")));
    assertEquals(vitals, base.fixture.gson.toJson(base.run().getVitals()));
    assertEquals(
        ranks,
        base.run().getRoster().stream()
            .map(v -> base.fixture.p.getAvatarById(v).getRank())
            .toList());
  }

  @ParameterizedTest
  @ValueSource(ints = {62, 66})
  void savedAcquisitionStillBindsAfterCurrentCurioDefinitionsChange(int id) throws Exception {
    assertTrue(RogueMiracles.acquire(base.fixture.p, id));
    String acquisition = base.run().getMiracles().get(id).getAcquisitionId();
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(
        base.fixture.p,
        base.fixture.gson.fromJson(
            base.fixture.gson.toJson(base.fixture.p.getGameplay()), GameplayState.class));
    GameplayDefinitions.miracles.put(id, new GameplayDefinitions.Miracle(id, 1, 1));
    GameplayDefinitions.miracleEffects.remove(id);
    assertTrue(base.encounter().hasBuff(buff(id)));
    assertEquals(acquisition, base.run().getMiracles().get(id).getAcquisitionId());
  }

  @ParameterizedTest
  @ValueSource(ints = {62, 66})
  void unavailableSourceBindingAndOldSnapshotCannotGrantTheEffect(int id) {
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == buff(id));
    assertFalse(RogueMiracles.canAcquire(base.fixture.p, id));
    owned(id);
    assertFalse(base.encounter().hasBuff(buff(id)));
    binding(id);
    base.run().getMiracles().put(id, new GameplayState.RogueMiracle(0));
    assertFalse(base.encounter().hasBuff(buff(id)));
  }

  @ParameterizedTest
  @ValueSource(ints = {62, 66})
  void lateAcquisitionCannotChangeThePreparedBattleOnRetry(int id) {
    var battle = base.encounter();
    assertTrue(RogueMiracles.acquire(base.fixture.p, id));
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertFalse(battle.hasBuff(buff(id)));
  }

  @Test
  void simultaneousRankAndAggroEffectsCoexistWithOtherEntryCuriosOnce() {
    owned(62);
    owned(66);
    base.snapshots();
    var battle = base.encounter();
    base.fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertEquals(Set.of(633102, 633108, 633109, 633111), battle.getBuffs().keySet());
    base.run().getMiracles().clear();
    assertEquals(4, battle.toProto().getBuffList().length());
  }
}
