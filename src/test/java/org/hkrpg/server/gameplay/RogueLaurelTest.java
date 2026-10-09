package org.hkrpg.server.gameplay;

import static emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.battle.BattleService;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.util.*;
import org.junit.jupiter.api.*;

class RogueLaurelTest {
  final UniverseCombatFragmentsTest fixture = new UniverseCombatFragmentsTest();
  GameplayDefinitions.Miracle previous;
  GameplayDefinitions.MiracleEffect previousEffect;
  int dropCalls;
  final BattleService service =
      new BattleService(null) {
        protected void grantDrops(Battle ignored) {
          dropCalls++;
        }
      };

  @BeforeEach
  void setup() {
    previous = GameplayDefinitions.miracles.get(61);
    previousEffect = GameplayDefinitions.miracleEffects.get(61);
    fixture.setup();
    GameplayDefinitions.miracles.put(61, new GameplayDefinitions.Miracle(61, 42, 61));
    GameplayDefinitions.miracleEffects.put(
        61,
        new GameplayDefinitions.MiracleEffect(61, List.of(new GameplayDefinitions.EffectValue(1))));
    GameplayDefinitions.maps.put(
        1999601,
        List.of(
            new GameplayDefinitions.Site(1999601, 1, true, List.of(2), 1),
            new GameplayDefinitions.Site(1999601, 2, false, List.of(), 7)));
    fixture.monster(9, 777);
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameplayDefinitions.miracles.remove(61);
    GameplayDefinitions.miracleEffects.remove(61);
    if (previous != null) GameplayDefinitions.miracles.put(61, previous);
    if (previousEffect != null) GameplayDefinitions.miracleEffects.put(61, previousEffect);
  }

  GameplayState.RogueRun run() {
    return fixture.p.getGameplay().getRogue();
  }

  void snapshot() {
    run().getMiracles().put(61, new GameplayState.RogueMiracle(1, List.of(1.0)));
  }

  Battle encounter(int instance) {
    var b = fixture.battle(fixture.monster(8, instance));
    fixture.p.setBattle(b);
    return b;
  }

  BattleStatistics stats() {
    return BattleStatistics.newInstance()
        .addBattleAvatarList(
            emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo.newInstance()
                .setId(199961)
                .setAvatarStatus(
                    emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty.newInstance()
                        .setMaxHp(100)
                        .setMaxSp(100)
                        .setLeftHp(0)
                        .setLeftSp(9)));
  }

  Battle finish(Battle b, BattleEndStatus result) {
    return service.finishBattle(
        fixture.p, b.getId(), UniverseCombatFragmentsTest.STAGE, result, stats());
  }

  @Test
  void strictAcquisitionHasOneUseAndCannotBeRepeated() {
    assertTrue(RogueMiracles.acquire(fixture.p, 61));
    assertFalse(RogueMiracles.acquire(fixture.p, 61));
    assertEquals(1, run().getMiracles().get(61).getTotalCount());
    assertEquals(0, run().getMiracles().get(61).getUseCount());
  }

  @Test
  void anAcceptedNonfinalLossBecomesOneVictoryAndHealsTheEntireDownloadedRoster() {
    snapshot();
    var normal = fixture.p.teams.getLineupByIndex(0, 0);
    fixture.p.battleAvatar.setCurrentHp(normal, 5700);
    fixture.p.battleAvatar.setCurrentHp(fixture.p.getCurrentLineup(), 0);
    run().getVitals().put(199962, new GameplayState.RogueVitals(2400, 1700));
    var b = encounter(1);
    assertSame(b, finish(b, BATTLE_END_LOSE));
    assertEquals(BATTLE_END_WIN, b.getResult());
    assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run().getPhase());
    assertEquals(10000, run().getVitals().get(199961).getHp());
    assertEquals(10000, run().getVitals().get(199962).getHp());
    assertEquals(1700, run().getVitals().get(199962).getSp());
    assertEquals(900, run().getVitals().get(199961).getSp());
    assertEquals(5700, fixture.p.battleAvatar.getCurrentHp(normal));
    assertTrue(run().getMiracles().get(61).broken());
    assertEquals(1, run().getMiracles().get(61).getUseCount());
    assertTrue(run().getCoins() >= 70 && run().getCoins() <= 90);
    assertFalse(fixture.p.getScene().getEntities().containsKey(1));
    assertEquals(1, dropCalls);
    assertNull(finish(b, BATTLE_END_LOSE));
    assertEquals(1, dropCalls);
  }

  @Test
  void evenANonbossRoomAtTheMapEndpointCannotBeRescued() {
    snapshot();
    GameplayDefinitions.maps.put(
        1999601, List.of(new GameplayDefinitions.Site(1999601, 1, true, List.of(), 1)));
    var b = encounter(1);
    assertSame(b, finish(b, BATTLE_END_LOSE));
    assertEquals(BATTLE_END_LOSE, b.getResult());
    assertEquals(GameplayState.RogueRun.Phase.FAILED, run().getPhase());
    assertEquals(0, run().getMiracles().get(61).getUseCount());
    assertEquals(50, run().getCoins());
    assertEquals(0, dropCalls);
  }

  @Test
  void anIntermediateBossWithANextAreaCanBeRescuedWithoutInventingBossFragments() {
    snapshot();
    GameplayDefinitions.rooms.put(
        UniverseCombatFragmentsTest.ROOM,
        new GameplayDefinitions.Room(
            UniverseCombatFragmentsTest.ROOM, 7, 12, 8, Map.of(8, 11, 9, 11)));
    var b = encounter(1);
    assertNotNull(finish(b, BATTLE_END_LOSE));
    assertEquals(BATTLE_END_WIN, b.getResult());
    assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run().getPhase());
    assertEquals(50, run().getCoins());
    assertEquals(1, run().getMiracles().get(61).getUseCount());
  }

  @Test
  void theBrokenLaurelCannotRescueASecondLoss() {
    snapshot();
    assertEquals(BATTLE_END_WIN, finish(encounter(1), BATTLE_END_LOSE).getResult());
    assertEquals(BATTLE_END_LOSE, finish(encounter(2), BATTLE_END_LOSE).getResult());
    assertEquals(GameplayState.RogueRun.Phase.FAILED, run().getPhase());
    assertEquals(1, run().getMiracles().get(61).getUseCount());
    assertEquals(1, dropCalls);
  }

  @Test
  void genuineWinsAndQuitsLeaveTheSingleRescueAvailable() {
    snapshot();
    assertEquals(BATTLE_END_QUIT, finish(encounter(1), BATTLE_END_QUIT).getResult());
    assertEquals(0, run().getMiracles().get(61).getUseCount());
    assertEquals(BATTLE_END_WIN, finish(encounter(2), BATTLE_END_WIN).getResult());
    assertEquals(0, run().getMiracles().get(61).getUseCount());
    assertEquals(BATTLE_END_WIN, finish(encounter(3), BATTLE_END_LOSE).getResult());
    assertEquals(1, run().getMiracles().get(61).getUseCount());
    assertEquals(2, dropCalls);
  }

  @Test
  void wrongIdsAndInvalidStatsCannotSpendTheCurioOrRestoreHealth() {
    snapshot();
    run().getVitals().get(199961).setHp(2300);
    var b = encounter(1);
    assertNull(
        service.finishBattle(
            fixture.p, b.getId() + 1, UniverseCombatFragmentsTest.STAGE, BATTLE_END_LOSE, stats()));
    assertNull(
        service.finishBattle(
            fixture.p, b.getId(), UniverseCombatFragmentsTest.STAGE + 1, BATTLE_END_LOSE, stats()));
    var invalid = stats();
    invalid.getMutableBattleAvatarList().get(0).getMutableAvatarStatus().setLeftHp(Double.NaN);
    assertNull(
        service.finishBattle(
            fixture.p, b.getId(), UniverseCombatFragmentsTest.STAGE, BATTLE_END_LOSE, invalid));
    assertEquals(0, run().getMiracles().get(61).getUseCount());
    assertEquals(2300, run().getVitals().get(199961).getHp());
    assertEquals(BATTLE_END_WIN, finish(b, BATTLE_END_LOSE).getResult());
  }

  @Test
  void acquiringTheCurioAfterPreparationCannotRetroactivelyRescueALoss() {
    var b = encounter(1);
    assertTrue(RogueMiracles.acquire(fixture.p, 61));
    assertEquals(BATTLE_END_LOSE, finish(b, BATTLE_END_LOSE).getResult());
    assertEquals(0, run().getMiracles().get(61).getUseCount());
  }

  @Test
  void replacingTheAcquisitionCannotBorrowTheOriginalBattlesRescue() {
    snapshot();
    var b = encounter(1);
    var original = run().getMiracles().get(61).getAcquisitionId();
    run().getMiracles().remove(61);
    assertTrue(RogueMiracles.acquire(fixture.p, 61));
    assertNotEquals(original, run().getMiracles().get(61).getAcquisitionId());
    assertEquals(BATTLE_END_LOSE, finish(b, BATTLE_END_LOSE).getResult());
    assertEquals(0, run().getMiracles().get(61).getUseCount());
  }

  @Test
  void anAlteredCombatantCannotBeTurnedIntoAVictory() {
    snapshot();
    var b = encounter(1);
    b.getNpcMonsters().getFirst().setEventId(UniverseCombatFragmentsTest.STAGE + 1);
    assertEquals(BATTLE_END_LOSE, finish(b, BATTLE_END_LOSE).getResult());
    assertEquals(0, run().getMiracles().get(61).getUseCount());
    assertEquals(0, dropCalls);
    assertEquals(50, run().getCoins());
  }

  @Test
  void removingCurrentDefinitionsDoesNotEraseThePreparedAcquisition() {
    snapshot();
    var b = encounter(1);
    GameplayDefinitions.miracles.remove(61);
    GameplayDefinitions.miracleEffects.remove(61);
    assertEquals(BATTLE_END_WIN, finish(b, BATTLE_END_LOSE).getResult());
    assertTrue(run().getMiracles().get(61).broken());
  }

  @Test
  void invalidDisplayParametersAndLegacyOwnershipNeverInventARescue() {
    GameplayDefinitions.miracles.put(61, new GameplayDefinitions.Miracle(61, 41, 61));
    assertFalse(RogueMiracles.canAcquire(fixture.p, 61));
    assertFalse(RogueMiracles.acquire(fixture.p, 61));
    GameplayDefinitions.miracles.put(61, new GameplayDefinitions.Miracle(61, 42, 61));
    for (double value : new double[] {0, 2, -1, Double.NaN}) {
      GameplayDefinitions.miracleEffects.put(
          61,
          new GameplayDefinitions.MiracleEffect(
              61, List.of(new GameplayDefinitions.EffectValue(value))));
      assertFalse(RogueMiracles.acquire(fixture.p, 61));
    }
    run().getMiracles().put(61, new GameplayState.RogueMiracle(1));
    assertEquals(BATTLE_END_LOSE, finish(encounter(1), BATTLE_END_LOSE).getResult());
    assertEquals(0, run().getMiracles().get(61).getUseCount());
  }

  @Test
  void aDanglingNextAreaDoesNotProveThatTheEncounterIsNonfinal() {
    snapshot();
    GameplayDefinitions.maps.put(
        1999601, List.of(new GameplayDefinitions.Site(1999601, 1, true, List.of(999), 1)));
    assertEquals(BATTLE_END_LOSE, finish(encounter(1), BATTLE_END_LOSE).getResult());
    assertEquals(0, run().getMiracles().get(61).getUseCount());
  }

  @Test
  void sixConcurrentAcceptedReportsConsumeExactlyOneRescueAndOneVictory() throws Exception {
    snapshot();
    var b = encounter(1);
    int accepted = 0;
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<Battle>>();
      for (int i = 0; i < 6; i++) tasks.add(() -> finish(b, BATTLE_END_LOSE));
      for (var task : executor.invokeAll(tasks)) if (task.get() != null) accepted++;
    }
    assertEquals(1, accepted);
    assertEquals(BATTLE_END_WIN, b.getResult());
    assertEquals(1, dropCalls);
    assertEquals(1, run().getMiracles().get(61).getUseCount());
    assertEquals(1, run().getCombatFragmentGrants().size());
    assertEquals(1, run().getProcessedBattles().size());
  }

  @Test
  void theConvertedVictoryParticipatesInTheExistingDebtCounterOnce() {
    snapshot();
    run().getMiracles().put(60, new GameplayState.RogueMiracle(5, List.of(5.0)));
    assertEquals(BATTLE_END_WIN, finish(encounter(1), BATTLE_END_LOSE).getResult());
    assertEquals(1, run().getMiracles().get(60).getUseCount());
    assertEquals(1, run().getMiracles().get(61).getUseCount());
    assertEquals(50, run().getCoins());
    assertTrue(run().getCombatFragmentGrants().isEmpty());
  }

  @Test
  void actualMorphiaReloadPreservesAnUnusedThenBrokenLaurelAndRestoredHealth() throws Exception {
    snapshot();
    run().getVitals().get(199961).setHp(2300);
    String acquired = run().getMiracles().get(61).getAcquisitionId();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "laurel_restore");
      var document = new emu.lunarcore.game.player.Player();
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, fixture.p.getGameplay());
      store.save(document);
      gameplay.set(
          fixture.p, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      assertEquals(acquired, run().getMiracles().get(61).getAcquisitionId());
      GameplayDefinitions.miracles.remove(61);
      GameplayDefinitions.miracleEffects.remove(61);
      assertEquals(BATTLE_END_WIN, finish(encounter(1), BATTLE_END_LOSE).getResult());
      gameplay.set(document, fixture.p.getGameplay());
      store.save(document);
      gameplay.set(
          fixture.p, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      assertEquals(10000, run().getVitals().get(199961).getHp());
      assertEquals(acquired, run().getMiracles().get(61).getAcquisitionId());
      assertTrue(run().getMiracles().get(61).broken());
      assertEquals(BATTLE_END_LOSE, finish(encounter(2), BATTLE_END_LOSE).getResult());
      assertEquals(1, run().getMiracles().get(61).getUseCount());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void theNativeBattleResponseReportsTheEffectiveVictory() throws Exception {
    snapshot();
    var b = finish(encounter(1), BATTLE_END_LOSE);
    class NativeSession extends emu.lunarcore.server.game.GameSession {
      NativeSession() {
        super(null);
      }

      public org.hkrpg.server.protocol.ProtocolBridge getProtocol() {
        return org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
      }
    }
    fixture.p.setSession(new NativeSession());
    var packet =
        new emu.lunarcore.server.packet.send.PacketPVEBattleResultScRsp(
            emu.lunarcore.proto.PVEBattleResultCsReqOuterClass.PVEBattleResultCsReq.newInstance(),
            b);
    var protocol = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    var nativeResponse =
        protocol
            .clientSchema()
            .parse(
                "PVEBattleResultScRsp",
                protocol.translate("PVEBattleResultScRsp", packet.getData().toByteArray(), true));
    assertTrue(nativeResponse.getUnknownFields().asMap().isEmpty());
    assertEquals(
        "BATTLE_END_WIN",
        nativeResponse
            .getField(nativeResponse.getDescriptorForType().findFieldByName("end_status"))
            .toString());
    assertEquals(
        b.getId(),
        nativeResponse.getField(
            nativeResponse.getDescriptorForType().findFieldByName("battle_id")));
  }

  @Test
  void theConvertedVictoryClearsTheActualRoomAndInvokesItsCallbackOnce() {
    snapshot();
    fixture.p.getScene().getEntities().remove(777);
    var b = encounter(1);
    var seen = new ArrayList<BattleEndStatus>();
    b.setOnFinish(done -> seen.add(done.getResult()));
    assertNotNull(finish(b, BATTLE_END_LOSE));
    assertTrue(run().getCleared().contains(1));
    assertEquals(List.of(BATTLE_END_WIN), seen);
    assertNull(finish(b, BATTLE_END_LOSE));
    assertEquals(List.of(BATTLE_END_WIN), seen);
  }

  @Test
  void changingATerminalGraphAfterPreparationCannotCreateARescue() {
    snapshot();
    var graph = GameplayDefinitions.maps.get(1999601);
    GameplayDefinitions.maps.put(
        1999601, List.of(new GameplayDefinitions.Site(1999601, 1, true, List.of(), 1)));
    var b = encounter(1);
    GameplayDefinitions.maps.put(1999601, graph);
    assertEquals(BATTLE_END_LOSE, finish(b, BATTLE_END_LOSE).getResult());
    assertEquals(0, run().getMiracles().get(61).getUseCount());
  }

  @Test
  void realUncategorizedPathResonanceRowCannotAbortVictorySettlement() {
    int reward = 19997981;
    var original = List.copyOf(GameplayDefinitions.blessings);
    var priorBuff = emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(reward, 1);
    try {
      GameplayDefinitions.blessings.clear();
      // RogueBuff 620001 is a real v1 resonance row without RogueBuffCategory.
      GameplayDefinitions.blessings.add(
          new GameplayDefinitions.Blessing(620001, 1, 120, null, 1, List.of()));
      GameplayDefinitions.blessings.add(
          new GameplayDefinitions.Blessing(reward, 1, 120, "Common", 1, List.of()));
      emu.lunarcore.data.GameData.getMazeBuffExcelMap()
          .add(
              fixture.gson.fromJson(
                  "{\"ID\":" + reward + ",\"Lv\":1}",
                  emu.lunarcore.data.excel.MazeBuffExcel.class));
      snapshot();
      fixture.p.getScene().getEntities().remove(777);
      var b = encounter(1);
      assertNotNull(finish(b, BATTLE_END_LOSE));
      assertEquals(BATTLE_END_WIN, b.getResult());
      assertNull(fixture.p.getBattle());
      assertTrue(run().getCleared().contains(1));
      assertEquals(List.of(reward), run().getOffers());
      assertFalse(run().getOfferPool().contains(620001));
      assertEquals(1, run().getMiracles().get(61).getUseCount());
    } finally {
      GameplayDefinitions.blessings.clear();
      GameplayDefinitions.blessings.addAll(original);
      if (priorBuff == null)
        emu.lunarcore.data.GameData.getMazeBuffExcelMap()
            .values()
            .remove(emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(reward, 1));
      else emu.lunarcore.data.GameData.getMazeBuffExcelMap().add(priorBuff);
    }
  }
}
