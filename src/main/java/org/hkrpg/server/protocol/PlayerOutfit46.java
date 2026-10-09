package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.PlayerOutfits;

/** Frozen 4.6 outfit container: outer tag 1, nested packed/unpacked IDs at tag 1. */
public final class PlayerOutfit46 {
  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental()
        || command != s.getProtocol().clientCommand("SetPlayerOutfitCsReq")) return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    synchronized (s.getPlayer()) {
      var ids = read(s, bytes);
      var code = ids == null ? Retcode.REQ_PARA_INVALID : PlayerOutfits.equip(s.getPlayer(), ids);
      if (code == Retcode.SUCC) {
        var child = builder(s, "CJLCPMDGIBO");
        s.getPlayer()
            .getGameplay()
            .getEquippedOutfits()
            .forEach(id -> add(child, "EAKOLIJOEPA", id));
        var sync = builder(s, "PlayerSyncScNotify");
        set(sync, "DHKFCDAGHDM", child.build());
        s.sendClientPacket("PlayerSyncScNotify", sync.build().toByteArray());
      }
      var out = builder(s, "SetPlayerOutfitScRsp");
      set(out, "PLDKENJLKME", code.getVal());
      s.sendClientPacket("SetPlayerOutfitScRsp", out.build().toByteArray());
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private static List<Integer> read(GameSession s, byte[] bytes) {
    if (bytes.length > 4096) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      if (input.readTag() != 10) return null;
      byte[] child = input.readByteArray();
      if (!input.isAtEnd()
          || !Offering46.wireValid(s.getProtocol().clientSchema().message("CJLCPMDGIBO"), child))
        return null;
      var parsed = s.getProtocol().clientSchema().parse("CJLCPMDGIBO", child);
      return (List<Integer>) value(parsed, "EAKOLIJOEPA");
    } catch (IOException invalid) {
      return null;
    }
  }

  private PlayerOutfit46() {}
}
