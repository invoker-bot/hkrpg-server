package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseClockRevival46Test {
  final UniverseRevive46Test fixture = new UniverseRevive46Test();
  final Map<Integer, GameplayDefinitions.Miracle> oldRows = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();
  final List<Integer> nativePrices = new ArrayList<>(), notifySaves = new ArrayList<>();

  void online() throws Exception {
    var session =
        new UniverseRevive46Test.Session() {
          public emu.lunarcore.game.player.Player getPlayer() {
            return fixture.s.explorer;
          }

          public void sendClientPacket(String name, byte[] bytes) {
            super.sendClientPacket(name, bytes);
            if (name.equals("SyncRogueReviveInfoScNotify")) {
              nativePrices.add(
                  fixture.price(
                      (com.google.protobuf.DynamicMessage)
                          Gameplay46.value(response, "MFEODNJNGDP")));
              notifySaves.add(fixture.s.explorer.saves);
            }
          }
        };
    fixture.s.explorer.setSession(session);
    var flag = emu.lunarcore.game.player.Player.class.getDeclaredField("loggedIn");
    flag.setAccessible(true);
    flag.setBoolean(fixture.s.explorer, true);
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.setup();
    fixture.begin();
    UniverseClockExpenditure46Test.clockRows(oldRows, oldEffects);
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 70));
  }

  @AfterEach
  void cleanup() {
    UniverseClockExpenditure46Test.restoreClockRows(oldRows, oldEffects);
    fixture.cleanup();
  }

  GameplayState.RogueRun run() {
    return fixture.s.explorer.getGameplay().getRogue();
  }

  GameplayState.RogueMiracle clock() {
    return run().getMiracles().get(70);
  }

  void counter(long n) throws Exception {
    var f = GameplayState.RogueMiracle.class.getDeclaredField("extraFragmentsSpent");
    f.setAccessible(true);
    f.setLong(clock(), n);
  }

  int revive() throws Exception {
    return UniverseRevive46Test.code(
        fixture.revive(List.of(UniverseRevive46Test.AVATAR), UniverseRevive46Test.ENTITY));
  }

  @Test
  void nativeRevivalInfoAndActualDebitIncludeSavedClockSurcharge() throws Exception {
    assertEquals(100, fixture.price(RogueRevival46.info(fixture.s)));
    assertEquals(0, revive());
    assertEquals(0, run().getCoins());
    assertEquals(20, clock().getExtraFragmentsSpent());
    assertEquals(10000, run().getVitals().get(UniverseRevive46Test.AVATAR).getHp());
    assertNotEquals(0, revive());
    assertEquals(20, clock().getExtraFragmentsSpent());
  }

  @Test
  void insufficientClockPriceDoesNotReviveOrConsumeBaseCost() throws Exception {
    run().spend(1);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), revive());
    assertEquals(99, run().getCoins());
    assertEquals(0, clock().getExtraFragmentsSpent());
    assertEquals(0, run().getVitals().get(UniverseRevive46Test.AVATAR).getHp());
  }

  @Test
  void revivalBreaksClockAndNextNativeInfoReturnsBasePrice() throws Exception {
    counter(490);
    int saves = fixture.s.explorer.saves;
    assertEquals(0, revive());
    assertFalse(run().getMiracles().containsKey(70));
    assertNotNull(run().getMiracles().get(9));
    assertEquals(999, run().getCoins());
    assertEquals(80, fixture.price(RogueRevival46.info(fixture.s)));
    assertEquals(saves + 1, fixture.s.explorer.saves);
    assertNotEquals(0, revive());
    assertEquals(999, run().getCoins());
  }

  @Test
  void overflowCannotChargeOrRestoreHealth() throws Exception {
    counter(Long.MAX_VALUE);
    int saves = fixture.s.explorer.saves;
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), revive());
    assertEquals(100, run().getCoins());
    assertEquals(0, run().getVitals().get(UniverseRevive46Test.AVATAR).getHp());
    assertEquals(saves, fixture.s.explorer.saves);
  }

  @Test
  void concurrentNativeRevivalIncrementsTheClockOnce() throws Exception {
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 4; i++)
        tasks.add(
            () ->
                RogueRevival.revive(
                    fixture.s.explorer,
                    List.of(UniverseRevive46Test.AVATAR),
                    UniverseRevive46Test.ENTITY));
      long count = 0;
      for (var task : pool.invokeAll(tasks)) if (task.get() == Retcode.SUCC) count++;
      assertEquals(1, count);
    }
    assertEquals(0, run().getCoins());
    assertEquals(20, clock().getExtraFragmentsSpent());
  }

  @Test
  void brokenClockPublishesBaseRevivalPriceAfterSavingTheSameTransaction() throws Exception {
    counter(490);
    online();
    int saves = fixture.s.explorer.saves;
    assertEquals(0, revive());
    assertEquals(List.of(80), nativePrices);
    assertEquals(List.of(saves + 1), notifySaves);
  }
}
