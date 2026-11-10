package org.hkrpg.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GameDiagnosticsWindowTest {
  @Test void duplicatesNeverConsumeTheBudgetForADifferentCause() {
    var window = new GameDiagnostics.Window(2);
    assertTrue(window.admit("first", 0));
    for (int i = 0; i < 100; i++) assertFalse(window.admit("first", i));
    assertTrue(window.admit("another", 1_000));
    assertFalse(window.admit("overflow", 2_000));
    assertFalse(window.admit("another", 3_000));
    assertNull(window.drain(GameDiagnostics.INTERVAL - 1, false));
    assertEquals(new GameDiagnostics.Summary(101, 1, List.of("first x100", "another x1")),
        window.drain(GameDiagnostics.INTERVAL, false));
    assertTrue(window.admit("first", GameDiagnostics.INTERVAL));
    assertNull(window.drain(GameDiagnostics.INTERVAL, true));
  }

  @Test void normalStopFlushesTheLastWindowEvenDuringABriefBurst() {
    var window = new GameDiagnostics.Window(1);
    assertTrue(window.admit("blocked", 11));
    assertFalse(window.admit("blocked", 12));
    assertEquals(new GameDiagnostics.Summary(1, 0, List.of("blocked x1")), window.drain(15, true));
    assertNull(window.drain(16, true));
  }
}
