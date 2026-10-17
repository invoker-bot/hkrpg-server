package org.hkrpg.server.protocol;

import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** The source-matched encrypted opening movie; the full video catalog remains unsupported. */
final class VideoVersionKeys46 {
  // Data: hkrpg-go ef47e49a233133c46d70ab5a4deb4bc71ae49571,
  // data/VideoVersionKey.json, videoKeyInfoList id 8 (AGPL-3.0).
  // See docs/opening-video-key-4.6.md for independent native routing and cross-checks.
  private static final int OPENING_VIDEO_ID = 8;
  private static final long OPENING_VIDEO_KEY = 70_220_919L;

  static void send(GameSession session, byte[] request) throws IOException {
    var body = new ByteArrayOutputStream();
    var output = CodedOutputStream.newInstance(body);
    output.writeUInt32(5, request.length == 0 ? 0 : Retcode.REQ_PARA_INVALID.getVal());
    if (request.length == 0) {
      var entry = new ByteArrayOutputStream();
      var key = CodedOutputStream.newInstance(entry);
      // Native LGHGDCFAHGM.MergeFrom: wire 6 -> +0x30, wire 14 -> +0x18.
      // BKLKPCOJLAB.GJAFOMJPJIO places ordinary list 15 into the movie-id dictionary.
      key.writeUInt64(6, OPENING_VIDEO_KEY);
      key.writeUInt32(14, OPENING_VIDEO_ID);
      key.flush();
      output.writeByteArray(15, entry.toByteArray());
    }
    output.flush();
    session.sendClientPacket(27, body.toByteArray());
  }

  private VideoVersionKeys46() {}
}
