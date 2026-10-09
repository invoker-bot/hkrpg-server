package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import org.hkrpg.server.gameplay.DicePveMissionReports;

/** Classic 9293/9271 only, matched to native sends, writers and consumers. */
public final class DicePve46 {
  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental()
        || command != s.getProtocol().clientCommand("DiceCombatFinishPveStageCsReq")) return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    synchronized (s.getPlayer()) {
      var code = Retcode.REQ_PARA_INVALID;
      int stage = 0;
      var result = new DicePveMissionReports.Result(code, java.util.List.of());
      if (validWire(bytes)) {
        var req = s.getProtocol().clientSchema().parse("DiceCombatFinishPveStageCsReq", bytes);
        stage = number(req, "MPDHNFNCIEA");
        result =
            DicePveMissionReports.finish(
                s.getPlayer(),
                stage,
                (boolean) value(req, "is_win"),
                (boolean) value(req, "AINKFEINNAK"),
                Integer.toUnsignedLong(number(req, "IMKHIDEBGEB")));
      }
      var out = builder(s, "FinishPveStageScRsp");
      set(out, "retcode", result.code().getVal());
      if (result.code() == Retcode.SUCC) {
        set(out, "MPDHNFNCIEA", stage);
        set(out, "is_win", true);
        set(out, "reward", items(s, result.items()));
      }
      s.sendClientPacket("FinishPveStageScRsp", out.build().toByteArray());
    }
    return true;
  }

  private static boolean validWire(byte[] bytes) {
    if (bytes.length > 4096) return false;
    var seen = new java.util.HashSet<Integer>();
    var input = CodedInputStream.newInstance(bytes);
    try {
      int tag;
      while ((tag = input.readTag()) != 0) {
        int field = tag >>> 3;
        if ((tag & 7) != 0 || !java.util.Set.of(1, 2, 4, 10).contains(field) || !seen.add(field))
          return false;
        long value = input.readUInt64();
        if (Long.compareUnsigned(value, field == 1 || field == 2 ? 1 : 0xffffffffL) > 0)
          return false;
      }
      return input.isAtEnd();
    } catch (IOException malformed) {
      return false;
    }
  }

  private DicePve46() {}
}
