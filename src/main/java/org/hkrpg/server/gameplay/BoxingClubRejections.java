package org.hkrpg.server.gameplay;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.Arrays;
import org.hkrpg.server.GameDiagnostics;

/** Read-only failure metadata; the admission predicates remain owned by each season. */
public final class BoxingClubRejections {
  public static void record(GameSession session, String command, int id, int code, boolean parsed) {
    var player = session.getPlayer();
    int panel = id >= 1 && id <= 5 ? 50000 : id >= 6 && id <= 12 ? 40003 : 0;
    int module = 0;
    String reason = "state-party-or-choice";
    if (!parsed) reason = "invalid-request";
    else if (code == Retcode.SYSTEM_BUSY.getVal()) reason = "activity-conflict";
    else if (command.equals("GetBoxingClubInfoCsReq")) reason = "catalog-or-progress";
    else if (command.equals("GiveUpBoxingClubChallengeCsReq")) reason = "active-challenge-mismatch";
    else if (panel == 50000) {
      var rules = BoxingClubRules.load();
      var challenge = rules == null ? null : rules.catalog.challenges().get(id);
      if (challenge != null) module = challenge.module();
      String access = rules == null ? "source-missing-or-changed"
          : rules.unavailableReason(player, challenge, Instant.now());
      if (access != null) reason = access;
    } else if (panel == 40003) {
      var rules = BoxingClubResonanceRules.load();
      var challenge = rules == null ? null : rules.catalog.challenges().get(id);
      if (challenge != null) module = challenge.module();
      String access = rules == null ? "source-missing-or-changed"
          : rules.unavailableReason(player, challenge, Instant.now());
      if (access != null) reason = access;
    } else reason = "unknown-challenge";
    var result = Arrays.stream(Retcode.values()).filter(r -> r.getVal() == code).findFirst().orElse(null);
    GameDiagnostics.outcome(session, command, id, result, reason,
        "panel=" + panel + " module=" + module + " level=" + player.getLevel()
            + " world=" + player.getWorldLevel());
  }

  private BoxingClubRejections() {}
}
