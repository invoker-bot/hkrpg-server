package org.hkrpg.server.protocol;

import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Bounded keys for the opening movies; the full video catalog remains unsupported. */
final class VideoVersionKeys46 {
  // Data: hkrpg-go ef47e49a233133c46d70ab5a4deb4bc71ae49571,
  // data/VideoVersionKey.json, videoKeyInfoList id 8 (AGPL-3.0).
  // See docs/opening-video-key-4.6.md for independent native routing and cross-checks.
  private static final int OPENING_VIDEO_ID = 8;
  private static final long OPENING_VIDEO_KEY = 70_220_919L;
  // Video 21: pinned 4.6 AES inputs and asset-matched HCA component.
  // The raw 4.6 tuple's HCA input does not match the installed 4.6 movie assets;
  // the calibrated component is independently checked against both audio tracks.
  private static final int POST_BOSS_VIDEO_ID = 21;
  private static final long POST_BOSS_VIDEO_KEY = 73_772_858_016_446_646L;
  private static final long POST_BOSS_HCA_COMPONENT = 3_385_157_949_291_746L;
  private static final long POST_BOSS_AES_COMPONENT = 5_627_075_162_192_835_560L;

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
      entry.reset();
      key = CodedOutputStream.newInstance(entry);
      key.writeUInt64(6, POST_BOSS_VIDEO_KEY);
      key.writeUInt64(8, POST_BOSS_HCA_COMPONENT);
      key.writeUInt64(13, POST_BOSS_AES_COMPONENT);
      key.writeUInt32(14, POST_BOSS_VIDEO_ID);
      key.flush();
      output.writeByteArray(15, entry.toByteArray());
    }
    output.flush();
    session.sendClientPacket(27, body.toByteArray());
  }

  private VideoVersionKeys46() {}
}
