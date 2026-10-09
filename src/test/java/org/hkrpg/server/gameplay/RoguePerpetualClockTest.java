package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoguePerpetualClockTest {
  final UniverseLifecycleTest base = new UniverseLifecycleTest();
  static final int NEXT = 19996002;
  final Gson gson = new Gson();
  Explorer player;
  GameplayDefinitions.Miracle oldMiracle;
  GameplayDefinitions.MiracleEffect oldEffect;
  GameplayDefinitions.Miracle oldInterest;
  GameplayDefinitions.MiracleEffect oldInterestEffect;

  static class Explorer extends UniverseLifecycleTest.Explorer {
    int savedCoins = -1;

    public void save() {
      savedCoins = getGameplay().getRogue().getCoins();
    }
  }

  GameplayState.RogueRun run() {
    return player.getGameplay().getRogue();
  }

  void coins(int amount) {
    run().addCoins(amount - run().getCoins());
  }

  void owned() {
    run().getMiracles().put(67, new GameplayState.RogueMiracle(0, List.of(.05)));
  }

  @BeforeEach
  void setup() {
    base.definitions();
    GameplayDefinitions.maps.put(
        UniverseLifecycleTest.MAP,
        List.of(
            new GameplayDefinitions.Site(UniverseLifecycleTest.MAP, 1, true, List.of(2), 1),
            new GameplayDefinitions.Site(UniverseLifecycleTest.MAP, 2, false, List.of(), 2)));
    GameplayDefinitions.rooms.put(NEXT, new GameplayDefinitions.Room(NEXT, 1, 13, 9));
    GameplayDefinitions.roomPool.put(2, List.of(NEXT));
    oldMiracle = GameplayDefinitions.miracles.get(67);
    oldEffect = GameplayDefinitions.miracleEffects.get(67);
    oldInterest = GameplayDefinitions.miracles.get(15);
    oldInterestEffect = GameplayDefinitions.miracleEffects.get(15);
    GameplayDefinitions.miracles.put(67, new GameplayDefinitions.Miracle(67, 48, 67));
    GameplayDefinitions.miracleEffects.put(
        67,
        new GameplayDefinitions.MiracleEffect(
            67, List.of(new GameplayDefinitions.EffectValue(.05))));
    player = new Explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(UniverseLifecycleTest.AREA));
    run().getCleared().add(1);
    coins(100);
  }

  @AfterEach
  void cleanup() {
    base.reset();
    GameplayDefinitions.rooms.remove(NEXT);
    GameplayDefinitions.roomPool.remove(2);
    GameplayDefinitions.miracles.remove(67);
    GameplayDefinitions.miracleEffects.remove(67);
    if (oldMiracle != null) GameplayDefinitions.miracles.put(67, oldMiracle);
    if (oldEffect != null) GameplayDefinitions.miracleEffects.put(67, oldEffect);
    GameplayDefinitions.miracles.remove(15);
    GameplayDefinitions.miracleEffects.remove(15);
    if (oldInterest != null) GameplayDefinitions.miracles.put(15, oldInterest);
    if (oldInterestEffect != null) GameplayDefinitions.miracleEffects.put(15, oldInterestEffect);
  }

  @Test
  void exactAcquisitionFreezesFivePercentWithoutChargingTheCurrentRoom() {
    assertTrue(RogueMiracles.acquire(player, 67));
    assertFalse(RogueMiracles.acquire(player, 67));
    var item = run().getMiracles().get(67);
    assertEquals(List.of(.05), item.getEffectParameters());
    assertFalse(item.getAcquisitionId().isBlank());
    assertEquals(0, item.getTotalCount());
    assertEquals(0, item.getUseCount());
    assertEquals(100, run().getCoins());
  }

  @Test
  void successfulAdjacentEntryChargesOnceAndPauseResumeDoesNotChargeAgain() {
    owned();
    assertEquals(Retcode.SUCC, player.getUniverseManager().enter(2));
    assertEquals(95, run().getCoins());
    assertEquals(95, player.savedCoins);
    assertNotEquals(Retcode.SUCC, player.getUniverseManager().enter(2));
    assertEquals(95, run().getCoins());
    assertEquals(Retcode.SUCC, player.getUniverseManager().leave(false));
    assertEquals(
        Retcode.SUCC, player.getUniverseManager().resume(UniverseLifecycleTest.AREA, false));
    assertEquals(95, run().getCoins());
    assertEquals(0, run().getMiracles().get(67).getUseCount());
  }

  @Test
  void rejectedSceneChangeAndInvalidDestinationDoNotSpendAndRetryChargesOnce() {
    owned();
    assertNotEquals(Retcode.SUCC, player.getUniverseManager().enter(99));
    player.rejectScene = true;
    assertNotEquals(Retcode.SUCC, player.getUniverseManager().enter(2));
    assertEquals(100, run().getCoins());
    assertEquals(1, run().getSiteId());
    player.rejectScene = false;
    assertEquals(Retcode.SUCC, player.getUniverseManager().enter(2));
    assertEquals(95, run().getCoins());
  }

  @ParameterizedTest
  @CsvSource({"19,19", "20,19", "2147483647,2040109465"})
  void fragmentLossUsesExactDecimalRoundingDownWithoutOverflow(int balance, int remaining) {
    owned();
    coins(balance);
    assertEquals(Retcode.SUCC, player.getUniverseManager().enter(2));
    assertEquals(remaining, run().getCoins());
  }

  @Test
  void persistedAcquisitionDoesNotBorrowFutureEffectParameters() throws Exception {
    assertTrue(RogueMiracles.acquire(player, 67));
    String acquisition = run().getMiracles().get(67).getAcquisitionId();
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(player, gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    GameplayDefinitions.miracleEffects.put(
        67,
        new GameplayDefinitions.MiracleEffect(
            67, List.of(new GameplayDefinitions.EffectValue(.5))));
    assertEquals(Retcode.SUCC, player.getUniverseManager().enter(2));
    assertEquals(95, run().getCoins());
    assertEquals(acquisition, run().getMiracles().get(67).getAcquisitionId());
  }

  @Test
  void wrongAssociationsPausedAcquisitionAndOldOrCorruptSnapshotsCannotCharge() {
    GameplayDefinitions.miracles.put(67, new GameplayDefinitions.Miracle(67, 47, 67));
    assertFalse(RogueMiracles.canAcquire(player, 67));
    GameplayDefinitions.miracles.put(67, new GameplayDefinitions.Miracle(67, 48, 66));
    assertFalse(RogueMiracles.acquire(player, 67));
    GameplayDefinitions.miracles.put(67, new GameplayDefinitions.Miracle(67, 48, 67));
    assertEquals(Retcode.SUCC, player.getUniverseManager().leave(false));
    assertFalse(RogueMiracles.acquire(player, 67));
    assertEquals(
        Retcode.SUCC, player.getUniverseManager().resume(UniverseLifecycleTest.AREA, false));
    run().getMiracles().put(67, new GameplayState.RogueMiracle(0));
    assertEquals(Retcode.SUCC, player.getUniverseManager().enter(2));
    assertEquals(100, run().getCoins());
    run().getMiracles().put(67, new GameplayState.RogueMiracle(0, List.of(.5)));
    RogueMiracles.onRoomEntered(player);
    assertEquals(100, run().getCoins());
  }

  @Test
  void interestPrecedesTheClockUnderTheDocumentedProjectStackingRule() {
    owned();
    GameplayDefinitions.miracles.put(15, new GameplayDefinitions.Miracle(15, 15, 15));
    GameplayDefinitions.miracleEffects.put(
        15,
        new GameplayDefinitions.MiracleEffect(
            15, List.of(new GameplayDefinitions.EffectValue(.06))));
    assertTrue(RogueMiracles.acquire(player, 15));
    assertEquals(Retcode.SUCC, player.getUniverseManager().enter(2));
    assertEquals(101, run().getCoins());
    assertEquals(101, player.savedCoins);
  }
}
