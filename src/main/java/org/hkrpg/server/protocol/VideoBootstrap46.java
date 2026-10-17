package org.hkrpg.server.protocol;

import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Source-matched initialization of the native movie decoder's modulus. */
final class VideoBootstrap46 {
  // AGPL-3.0 HyacineDH-Core dd54de89ca8df8355fae5727e49e9e2bbba55344,
  // GameServer/Server/Packet/Send/Player/PacketGetSecretKeyInfoScRsp.cs.
  // Independently agrees with Honey fe00bdb and hkrpg-go ef47e49; see the source proof
  // and pinned native parsing chain in docs/opening-video-key-4.6.md.
  private static final String VIDEO_BOOTSTRAP = "10120425825329403";

  static void send(GameSession session, byte[] request) throws IOException {
    boolean valid = validRequest(request);
    var body = new ByteArrayOutputStream();
    var output = CodedOutputStream.newInstance(body);
    output.writeUInt32(8, valid ? 0 : Retcode.REQ_PARA_INVALID.getVal());
    if (valid) {
      var entry = new ByteArrayOutputStream();
      var video = CodedOutputStream.newInstance(entry);
      // Native GFDCKPPKGAC.MergeFrom: type 2 -> +0x20, string 14 -> +0x10.
      // ba59a90 parses type VIDEO=2; 15b57d70 XORs its value into the movie modulus.
      video.writeEnum(2, 2);
      video.writeString(14, VIDEO_BOOTSTRAP);
      video.flush();
      // Native AHIEHELEMMN.MergeFrom: secret_info 15 -> +0x10, retcode 8 -> +0x28.
      // No unverified server-check/battle-time entries or challenge echo (secret_rsp).
      output.writeByteArray(15, entry.toByteArray());
    }
    output.flush();
    session.sendClientPacket(91, body.toByteArray());
  }

  private static boolean validRequest(byte[] bytes) {
    if (bytes.length == 0) return true;
    // The sole optional field is opaque bytes 11. Inspect only its bounded framing;
    // do not retain, log or echo its value, or accept uint32-overflow tag/length aliases.
    if (bytes.length > 16_384 || bytes[0] != 90) return false;
    int position = 1;
    int length = 0;
    for (int shift = 0; shift <= 14 && position < bytes.length; shift += 7) {
      int part = bytes[position++] & 0xff;
      length |= (part & 0x7f) << shift;
      if ((part & 0x80) == 0) return length == bytes.length - position;
    }
    return false;
  }

  private VideoBootstrap46() {}
}
