package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.hkrpg.server.GameDiagnostics;
import org.hkrpg.server.gameplay.BadgeCatalog;
import org.hkrpg.server.gameplay.PlayerRoomDisplay;

/** The 4.6 badge list is a snapshot of actual persisted badge items. */
public final class Badge46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental() || command != 7172) return false;
    if (session.getState() != SessionState.ACTIVE || session.getPlayer() == null) return true;
    synchronized (session.getPlayer()) {
      var code = Retcode.SUCC;
      String reason = "";
      DynamicMessage info = null;
      if (bytes == null || bytes.length != 0) {
        code = Retcode.REQ_PARA_INVALID; reason = "invalid-wire";
      } else {
        try {
          var owned = BadgeCatalog.load().owned(session.getPlayer());
          info = info(session, owned);
        } catch (Exception unavailable) {
          code = Retcode.PLAYER_DATA_ERROR; reason = "badge-source-unavailable";
        }
      }
      if (code != Retcode.SUCC) GameDiagnostics.outcome(session, "GetBadgeInfoCsReq", 0, code, reason, "");
      try {
        var body = new ByteArrayOutputStream();
        var output = CodedOutputStream.newInstance(body);
        output.writeUInt32(6, code.getVal());
        if (info != null) output.writeByteArray(11, info.toByteArray());
        output.flush();
        session.sendClientPacket(7173, body.toByteArray());
      } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    return true;
  }

  private static DynamicMessage info(GameSession session, Map<Integer, Long> owned) {
    var info = builder(session, "JEGMGPOFHGG");
    for (var badge : owned.entrySet()) {
      var row = builder(session, "FGLAJHMCJLL");
      set(row, "EIKLMFPMJJD", badge.getKey());
      set(row, "expire_time", badge.getValue());
      add(info, "CPMCCCODGAB", row.build());
    }
    return info.build();
  }

  /** The item is already durably saved. Notification failures must not undo its receipt. */
  public static void acquired(Player player) {
    var session = player.getSession();
    try {
      var result = PlayerRoomDisplay.badgeAcquired(player);
      if (result.code() != Retcode.SUCC)
        GameDiagnostics.outcome(session, "BadgeAcquired", 0, result.code(), result.reason(), "");
      notifyOwned(player);
    } catch (RuntimeException failure) {
      GameDiagnostics.packetFailure(session, 7180, false, "handler", failure);
    }
  }

  static void notifyOwned(Player player) {
    var session = player.getSession();
    if (session == null || !session.getProtocol().experimental()
        || session.getState() != SessionState.ACTIVE || session.getPlayer() != player) return;
    try {
      var notify = builder(session, "BadgeInfoScNotify");
      set(notify, "AIANIJCIBGE", info(session, BadgeCatalog.load().owned(player)));
      session.sendClientPacket(7180, notify.build().toByteArray());
    } catch (Exception unavailable) {
      GameDiagnostics.outcome(session, "BadgeInfoScNotify", 0, Retcode.PLAYER_DATA_ERROR,
          "badge-source-unavailable", "");
    }
  }

  private Badge46() {}
}
