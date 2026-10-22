package org.hkrpg.server.protocol;

import emu.lunarcore.LunarCore;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import java.util.WeakHashMap;

/** Bounded rejection metadata only; no packet dumps, ability strings, credentials or inventory. */
final class SceneActionDiagnostics46 {
  private static final WeakHashMap<GameSession, Budget> BUDGETS = new WeakHashMap<>();

  static final class Budget {
    private long start;
    private int emitted;
    boolean allow(long now) {
      if (emitted == 0 || now - start >= 60_000_000_000L) {
        start = now;
        emitted = 0;
      }
      if (emitted >= 8) return false;
      emitted++;
      return true;
    }
  }

  static void rejected(GameSession session, String command, String reason, Retcode error,
      int use, int actor, int hits, int assists) {
    if (error == Retcode.SUCC) return;
    synchronized (BUDGETS) {
      var budget = BUDGETS.get(session);
      if (budget == null) {
        if (BUDGETS.size() >= 256) return;
        budget = new Budget();
        BUDGETS.put(session, budget);
      }
      if (!budget.allow(System.nanoTime())) return;
    }
    var player = session.getPlayer();
    LunarCore.getLogger().warn(
        "Scene action rejected: command={} uid={} floor={} reason={} retcode={} use={} actor={} hits={} assists={}",
        command, player.getUid(), player.getFloorId(), reason, error.getVal(),
        Integer.toUnsignedString(use), actor, hits, assists);
  }

  private SceneActionDiagnostics46() {}
}
