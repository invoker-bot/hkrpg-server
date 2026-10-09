package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class RogueMiraclesTest {
  @AfterEach
  void reset() {
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracleEffects.clear();
    GameplayDefinitions.miracleGroups.clear();
  }

  void effect(int id, double param) {
    GameplayDefinitions.miracles.put(id, new GameplayDefinitions.Miracle(id, id, id));
    GameplayDefinitions.miracleEffects.put(
        id,
        new GameplayDefinitions.MiracleEffect(
            id, List.of(new GameplayDefinitions.EffectValue(param))));
  }

  GameplayState.RogueRun setup(UniverseLifecycleTest.Explorer p) {
    var run = new GameplayState.RogueRun(100, 1, Map.of());
    run.setupTeam(p, List.of(199961));
    p.getGameplay().setRogue(run);
    return run;
  }

  @Test
  void reviveConsumesOnceHealingDoesNotReviveAndRepairRestoresActualUses() {
    effect(4, 1);
    effect(6, .3);
    effect(17, 2);
    var p = new UniverseLifecycleTest.Explorer();
    var normalTeam = p.getCurrentLineup();
    int normalHp =
        p.getCurrentLineup().getAvatars().isEmpty()
            ? 0
            : p.avatar.getCurrentHp(p.getCurrentLineup());
    var run = setup(p);
    var vital = run.getVitals().get(199961);
    assertTrue(RogueMiracles.acquire(p, 6));
    vital.setHp(0);
    RogueMiracles.onBattleWon(p);
    assertEquals(0, vital.getHp());
    assertTrue(RogueMiracles.acquire(p, 4));
    RogueMiracles.onBattleWon(p);
    assertEquals(10000, vital.getHp());
    assertTrue(run.getMiracles().get(4).broken());
    vital.setHp(0);
    RogueMiracles.onBattleWon(p);
    assertEquals(0, vital.getHp());
    assertTrue(RogueMiracles.acquire(p, 17));
    assertFalse(run.getMiracles().get(4).broken());
    RogueMiracles.onBattleWon(p);
    assertEquals(10000, vital.getHp());
    vital.setHp(6500);
    RogueMiracles.onBattleWon(p);
    assertEquals(9500, vital.getHp());
    assertEquals(normalHp, p.avatar.getCurrentHp(normalTeam));
    assertFalse(RogueMiracles.acquire(p, 17));
  }

  @Test
  void interestAndDiscountUseResourceRatiosAndRejectUnimplementedMiracles() {
    effect(15, .06);
    effect(13, .3);
    effect(8, .3);
    var p = new UniverseLifecycleTest.Explorer();
    var run = setup(p);
    assertTrue(RogueMiracles.acquire(p, 15));
    assertTrue(RogueMiracles.acquire(p, 13));
    assertFalse(RogueMiracles.acquire(p, 8));
    assertEquals(70, RogueMiracles.enhancementCost(run, 100));
    assertEquals(91, RogueMiracles.enhancementCost(run, 130));
    RogueMiracles.onRoomEntered(p);
    assertEquals(106, run.getCoins());
    RogueMiracles.onRoomEntered(p);
    assertEquals(112, run.getCoins());
  }

  @Test
  void weightedOffersContainOnlySupportedUnownedResourceItemsAndCannotReplacePendingBlessings() {
    effect(4, 1);
    effect(6, .3);
    effect(8, .3);
    GameplayDefinitions.miracleGroups.put(
        10000, new GameplayDefinitions.MiracleGroup(10000, Map.of(4, 10, 6, 20, 8, 1, 999, 2)));
    var p = new UniverseLifecycleTest.Explorer();
    var run = setup(p);
    assertTrue(RogueMiracles.acquire(p, 4));
    assertEquals(List.of(6), p.getUniverseManager().offerMiracles(10000));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID,
        p.getUniverseManager().selectMiracle(run.getSelectionId(), 8));
    assertEquals(List.of(6), run.getMiracleOffers());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        p.getUniverseManager().selectMiracle(run.getSelectionId(), 6));
    assertTrue(p.getUniverseManager().offerMiracles(10000).isEmpty());
    run.offer(List.of(19996100));
    assertTrue(p.getUniverseManager().offerMiracles(10000).isEmpty());
    assertEquals(List.of(19996100), run.getOffers());
  }
  @Test void acquiredUpgradeEnhancesOnlyBuffsWithTheNextResourceAndBattleLevel() {
    effect(20,2);
    var fixture=new UniverseBlessingTest();
    fixture.buff(1999391,1);fixture.buff(1999391,2);
    fixture.buff(1999392,1);fixture.buff(1999392,2);
    fixture.buff(1999393,1);
    var p=new UniverseLifecycleTest.Explorer();var run=setup(p);
    run.getBlessings().put(1999391,1);run.getBlessings().put(1999392,1);run.getBlessings().put(1999393,1);
    try {
      assertTrue(RogueMiracles.acquire(p,20));
      assertEquals(2,run.getBlessings().get(1999391));
      assertEquals(2,run.getBlessings().get(1999392));
      assertEquals(1,run.getBlessings().get(1999393));
      assertFalse(RogueMiracles.acquire(p,20));
      assertEquals(100,run.getCoins());
    } finally {
      GameplayDefinitions.blessings.removeIf(b->b.MazeBuffID()>=1999391&&b.MazeBuffID()<=1999393);
    }
  }
}
