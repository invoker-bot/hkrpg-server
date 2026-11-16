package org.hkrpg.server.protocol;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.hkrpg.server.GameDiagnostics;

/** Notify a connected 4.6 client once when the server's 04:00 reset day changes. */
public final class DailyRefresh46 {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  // Session state is not player progress and must not survive a disconnect or be saved.
  private static final Map<GameSession, Integer> LAST_DAY =
      Collections.synchronizedMap(new WeakHashMap<>());

  public static int day(Instant serverTime) {
    long day = serverTime.atZone(ZONE).minusHours(4).toLocalDate().toEpochDay();
    if (day < 0 || day > Integer.MAX_VALUE) throw new IllegalArgumentException("Server reset day out of range");
    return (int) day;
  }

  public static void refresh(Player player, Instant serverTime) {
    var session = player.getSession();
    if (session == null || !session.getProtocol().experimental() || session.getState() != SessionState.ACTIVE) return;
    synchronized (player) {
      int current = day(serverTime);
      Integer previous = LAST_DAY.get(session);
      if (previous == null) {
        LAST_DAY.put(session, current);
        return;
      }
      // A backwards clock adjustment must not dispatch the same reset twice.
      if (current <= previous) return;
      var notify = Gameplay46.builder(session, "DailyRefreshNotify");
      Gameplay46.set(notify, "MDIIJKBHBBI", current);
      try {
        session.sendClientPacket("DailyRefreshNotify", notify.build().toByteArray());
      } catch (RuntimeException failed) {
        GameDiagnostics.outcome(session, "DailyRefreshNotify", current, Retcode.SERVER_INTERNAL_ERROR,
            "daily-refresh-send-failed", "");
        throw failed;
      }
      LAST_DAY.put(session, current);
      GameDiagnostics.outcome(session, "DailyRefreshNotify", current, Retcode.SUCC,
          "daily-refresh-boundary", "");
    }
  }

  private DailyRefresh46() {}
}
