package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.battle.Battle;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;

class RogueBattleMiraclesTest {
  final UniverseCombatFragmentsTest fixture = new UniverseCombatFragmentsTest();
  final Map<Integer, GameplayDefinitions.Miracle> oldMiracles = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();
  final Map<Integer, MazeBuffExcel> oldBuffs = new HashMap<>();
  MapEntranceExcel oldEntry;

  @BeforeEach
  void setup() {
    fixture.setup();
    oldEntry = GameData.getMapEntranceExcelMap().get(12);
    GameData.getMapEntranceExcelMap()
        .add(
            fixture.gson.fromJson(
                "{\"ID\":12,\"PlaneID\":199962,\"FloorID\":199962001}", MapEntranceExcel.class));
    for (int id : List.of(8, 71)) {
      if (GameplayDefinitions.miracles.containsKey(id))
        oldMiracles.put(id, GameplayDefinitions.miracles.get(id));
      if (GameplayDefinitions.miracleEffects.containsKey(id))
        oldEffects.put(id, GameplayDefinitions.miracleEffects.get(id));
      GameplayDefinitions.miracles.put(
          id, new GameplayDefinitions.Miracle(id, id == 8 ? 8 : 52, id));
      GameplayDefinitions.miracleEffects.put(
          id,
          new GameplayDefinitions.MiracleEffect(
              id,
              id == 8
                  ? List.of(
                      new GameplayDefinitions.EffectValue(.3),
                      new GameplayDefinitions.EffectValue(5))
                  : List.of(new GameplayDefinitions.EffectValue(2))));
    }
    for (int buff : List.of(633102, 633111)) {
      var prior = GameData.getMazeBuffExcelMap().get(buff, 1);
      if (prior != null) oldBuffs.put(buff, prior);
      binding(buff, buff == 633102 ? .3 : 2, "StageAbility_" + buff);
    }
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameData.getMapEntranceExcelMap().keySet().remove(12);
    if (oldEntry != null) GameData.getMapEntranceExcelMap().add(oldEntry);
    for (int id : List.of(8, 71)) {
      GameplayDefinitions.miracles.remove(id);
      GameplayDefinitions.miracleEffects.remove(id);
    }
    GameplayDefinitions.miracles.putAll(oldMiracles);
    GameplayDefinitions.miracleEffects.putAll(oldEffects);
    for (int buff : List.of(633102, 633111))
      GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == buff && v.getLv() == 1);
    oldBuffs.values().forEach(GameData.getMazeBuffExcelMap()::add);
  }

  void binding(int buff, double value, String key) {
    GameData.getMazeBuffExcelMap()
        .add(
            fixture.gson.fromJson(
                "{\"ID\":"
                    + buff
                    + ",\"Lv\":1,\"LvMax\":1,"
                    + "\"InBattleBindingType\":\"StageAbilityBeforeCharacterBorn\","
                    + "\"InBattleBindingKey\":\""
                    + key
                    + "\",\"ParamList\":[{\"Value\":"
                    + value
                    + "}]}",
                MazeBuffExcel.class));
  }

  GameplayState.RogueRun run() {
    return fixture.p.getGameplay().getRogue();
  }

  void snapshots() {
    run().getMiracles().put(8, new GameplayState.RogueMiracle(0, List.of(.3, 5.0)));
    run().getMiracles().put(71, new GameplayState.RogueMiracle(0, List.of(2.0)));
  }

  Battle encounter() {
    return fixture.battle(fixture.monster(8, 1));
  }

  @Test
  void theExactParchmentResourceCanBeAcquiredOnceWithFrozenParameters() {
    assertTrue(RogueMiracles.acquire(fixture.p, 8));
    assertFalse(RogueMiracles.acquire(fixture.p, 8));
    assertEquals(List.of(.3, 5.0), run().getMiracles().get(8).getEffectParameters());
    assertEquals(0, run().getMiracles().get(8).getTotalCount());
  }

  @Test
  void theExactMechanicalClockResourceCanBeAcquiredWithoutSpendingTechniquePoints() {
    int mp = run().getMp();
    assertTrue(RogueMiracles.acquire(fixture.p, 71));
    assertEquals(List.of(2.0), run().getMiracles().get(71).getEffectParameters());
    assertEquals(mp, run().getMp());
  }

  @Test
  void actualRoomCombatBindsBothEffectsWithoutChangingServerHealthOrTechniquePoints() {
    snapshots();
    int mp = run().getMp();
    int hp = run().getVitals().get(199961).getHp();
    var battle = encounter();
    assertTrue(battle.hasBuff(633102), "Parchment's enemy-entry ability is missing");
    assertTrue(battle.hasBuff(633111), "Clock's first-wave boost-point ability is missing");
    assertEquals(-1, battle.getBuffs().get(633102).getWaveFlag());
    assertEquals(-1, battle.getBuffs().get(633111).getOwnerIndex());
    assertEquals(mp, run().getMp());
    assertEquals(hp, run().getVitals().get(199961).getHp());
    fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertEquals(2, battle.getBuffs().size());
  }

  @Test
  void thePreparedBattleRetainsItsEffectsAfterOwnershipAndCurrentResourcesDisappear() {
    snapshots();
    var battle = encounter();
    run().getMiracles().clear();
    GameplayDefinitions.miracles.remove(8);
    GameplayDefinitions.miracleEffects.remove(71);
    GameData.getMazeBuffExcelMap()
        .values()
        .removeIf(v -> Set.of(633102, 633111).contains(v.getID()));
    assertTrue(battle.hasBuff(633102));
    assertTrue(battle.hasBuff(633111));
    assertEquals(2, battle.toProto().getBuffList().length());
  }

  @Test
  void acquisitionAfterPreparationDoesNotInjectIntoTheExistingBattle() {
    var battle = encounter();
    snapshots();
    assertFalse(battle.hasBuff(633102));
    assertFalse(battle.hasBuff(633111));
  }

  @Test
  void aPreparationRetryCannotBorrowCuriosAcquiredAfterTheOriginalPreparation() {
    var battle = encounter();
    snapshots();
    fixture.p.getUniverseManager().onBattlePrepared(battle);
    assertFalse(battle.hasBuff(633102));
    assertFalse(battle.hasBuff(633111));
  }

  @Test
  void incorrectDisplayEffectAndParameterAssociationsNeverBecomeAcquirable() {
    for (int id : List.of(8, 71)) {
      var row = GameplayDefinitions.miracles.get(id);
      GameplayDefinitions.miracles.put(id, new GameplayDefinitions.Miracle(id, 1, id));
      assertFalse(RogueMiracles.canAcquire(fixture.p, id));
      GameplayDefinitions.miracles.put(
          id, new GameplayDefinitions.Miracle(id, row.MiracleDisplayID(), 1));
      assertFalse(RogueMiracles.acquire(fixture.p, id));
      GameplayDefinitions.miracles.put(id, row);
      GameplayDefinitions.miracleEffects.put(
          id,
          new GameplayDefinitions.MiracleEffect(
              id, List.of(new GameplayDefinitions.EffectValue(1))));
      assertFalse(RogueMiracles.canAcquire(fixture.p, id));
    }
    assertTrue(run().getMiracles().isEmpty());
  }

  @Test
  void aMissingOrWrongResourceAbilityCannotBeOfferedOrSilentlySubstituted() {
    binding(633102, .9, "StageAbility_633102");
    assertFalse(RogueMiracles.canAcquire(fixture.p, 8));
    binding(633111, 2, "StageAbility_633125");
    assertFalse(RogueMiracles.acquire(fixture.p, 71));
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 633111);
    assertFalse(RogueMiracles.supported(71));
    assertTrue(run().getMiracles().isEmpty());
  }

  @Test
  void invalidAndLegacyOwnedRecordsCannotBorrowTheCurrentEffectDefinition() {
    run().getMiracles().put(8, new GameplayState.RogueMiracle(0));
    run().getMiracles().put(71, new GameplayState.RogueMiracle(1, List.of(2.0)));
    var battle = encounter();
    assertFalse(battle.hasBuff(633102));
    assertFalse(battle.hasBuff(633111));
    run().getMiracles().put(8, new GameplayState.RogueMiracle(0, List.of(.9, 5.0)));
    run().getMiracles().put(71, new GameplayState.RogueMiracle(0, List.of(3.0)));
    battle = fixture.battle(fixture.monster(8, 2));
    assertTrue(battle.getBuffs().isEmpty());
  }

  @Test
  void pausedWrongEntryAndWrongRoomAssignmentCannotApplyAnExplorationCurio() {
    snapshots();
    run().setInside(false);
    assertTrue(encounter().getBuffs().isEmpty());
    run().setInside(true);
    fixture.p.entry = 11;
    assertTrue(encounter().getBuffs().isEmpty());
    fixture.p.entry = 12;
    run().getRooms().put(1, UniverseCombatFragmentsTest.ROOM + 1);
    assertTrue(encounter().getBuffs().isEmpty());
    assertEquals(50, run().getCoins());
  }

  @Test
  void aStageOnlyOrUnmappedNpcBattleDoesNotHaveTheValidatedUniverseIdentity() {
    snapshots();
    var battle =
        new Battle(
            fixture.p,
            fixture.p.getCurrentLineup(),
            GameData.getStageExcelMap().get(UniverseCombatFragmentsTest.STAGE));
    assertTrue(battle.getBuffs().isEmpty());
    assertTrue(fixture.battle(fixture.monster(77, 1)).getBuffs().isEmpty());
    GameplayDefinitions.rogueMonsters.remove(111);
    assertTrue(encounter().getBuffs().isEmpty());
  }

  @Test
  void frozenOwnershipSurvivesGsonReloadWithoutBorrowingChangedCurrentParameters()
      throws Exception {
    assertTrue(RogueMiracles.acquire(fixture.p, 8));
    assertTrue(RogueMiracles.acquire(fixture.p, 71));
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(
        fixture.p,
        fixture.gson.fromJson(fixture.gson.toJson(fixture.p.getGameplay()), GameplayState.class));
    GameplayDefinitions.miracleEffects.put(
        8,
        new GameplayDefinitions.MiracleEffect(8, List.of(new GameplayDefinitions.EffectValue(.9))));
    GameplayDefinitions.miracles.remove(71);
    assertEquals(List.of(.3, 5.0), run().getMiracles().get(8).getEffectParameters());
    assertEquals(List.of(2.0), run().getMiracles().get(71).getEffectParameters());
    var battle = encounter();
    assertTrue(battle.hasBuff(633102));
    assertTrue(battle.hasBuff(633111));
  }

  @Test
  void corruptOrMissingFutureAbilityDefinitionsDoNotChangeThePreparedBattle() {
    snapshots();
    var prepared = encounter();
    binding(633102, .9, "StageAbility_633102");
    binding(633111, 1, "StageAbility_633111");
    assertEquals(2, prepared.toProto().getBuffList().length());
    var future = fixture.battle(fixture.monster(8, 2));
    assertTrue(future.getBuffs().isEmpty());
    binding(633102, .3, "StageAbility_633102");
    binding(633111, 2, "StageAbility_633111");
    assertEquals(2, fixture.battle(fixture.monster(8, 3)).getBuffs().size());
    assertEquals(List.of(.3, 5.0), run().getMiracles().get(8).getEffectParameters());
  }

  @Test
  void actualMorphiaTwoLoadsPreserveBothAcquisitionsAndTheirBattleBindings() throws Exception {
    assertTrue(RogueMiracles.acquire(fixture.p, 8));
    assertTrue(RogueMiracles.acquire(fixture.p, 71));
    String parchment = run().getMiracles().get(8).getAcquisitionId();
    String clock = run().getMiracles().get(71).getAcquisitionId();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "battleMiracles");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456789);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      for (int i = 0; i < 2; i++) {
        gameplay.set(document, fixture.p.getGameplay());
        store.save(document);
        gameplay.set(
            fixture.p, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
        assertEquals(parchment, run().getMiracles().get(8).getAcquisitionId());
        assertEquals(clock, run().getMiracles().get(71).getAcquisitionId());
        assertEquals(List.of(.3, 5.0), run().getMiracles().get(8).getEffectParameters());
        assertEquals(List.of(2.0), run().getMiracles().get(71).getEffectParameters());
        assertEquals(2, fixture.battle(fixture.monster(8, i + 1)).getBuffs().size());
        assertEquals(50, run().getCoins());
      }
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void theNativeBattleContainsOnlyTheTwoValidatedResourceBindings() throws Exception {
    snapshots();
    var battle = encounter();
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var message =
        bridge
            .clientSchema()
            .parse(
                "SceneBattleInfo",
                bridge.translate("SceneBattleInfo", battle.toProto().toByteArray(), true));
    var field = message.getDescriptorForType().findFieldByName("buff_list");
    var values = (List<com.google.protobuf.DynamicMessage>) message.getField(field);
    assertEquals(
        Set.of(633102, 633111),
        values.stream()
            .map(v -> (Integer) v.getField(v.getDescriptorForType().findFieldByName("id")))
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(2, values.size());
  }
}
