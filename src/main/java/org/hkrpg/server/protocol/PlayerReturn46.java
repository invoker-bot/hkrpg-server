package org.hkrpg.server.protocol;

import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Initial inactive profile only; return eligibility, progression and rewards are not implemented. */
final class PlayerReturn46 {
  static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 4583) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    // Honey fe00bdb Raw/dumper identifies 4583 -> 4515 as EBCEGDPGHLE. The named snapshot
    // incorrectly calls 4515 DressRelicAvatarScRsp and resolves a different Common message.
    // The pinned local client's JGDHIFOCFMM constants and EBCEGDPGHLE constants confirm these
    // numbers and retcode=3, config=12, active profile=14. Do not use the conflicting descriptor.
    var body = new ByteArrayOutputStream();
    var output = CodedOutputStream.newInstance(body);
    output.writeUInt32(3, bytes.length == 0 ? 0 : Retcode.REQ_PARA_INVALID.getVal());
    output.flush();
    session.sendClientPacket(4515, body.toByteArray());
    return true;
  }

  private PlayerReturn46() {}
}
