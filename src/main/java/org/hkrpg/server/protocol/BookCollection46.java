package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import org.hkrpg.server.gameplay.BookCollection;

/** CN4.6 detail record 9 is consumed by PlayerBoardInfo.BookCount in the pinned local client. */
public final class BookCollection46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    var protocol = session.getProtocol();
    if (!protocol.experimental() || command != protocol.clientCommand("GetPlayerDetailInfoCsReq"))
      return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var response = builder(session, "GetPlayerDetailInfoScRsp");
    int uid = readUid(bytes);
    if (uid <= 0) set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
    else {
      var requester = session.getPlayer();
      var target =
          uid == requester.getUid()
              ? requester
              : session.getServer() == null ? null : session.getServer().getPlayerByUid(uid, true);
      if (target == null)
        set(response, "retcode", 1); // Preserve the inherited missing-player result.
      else {
        synchronized (target) {
          int books;
          if (target == requester || target.isOnline()) books = BookCollection.count(target);
          else {
            try (var rows =
                LunarCore.getGameDatabase().getObjects(GameItem.class, "ownerUid", uid)) {
              books = BookCollection.stored(uid, rows::iterator);
            }
          }
          var detail = builder(session, "PlayerDetailInfo");
          var core = target.toDetailInfo();
          core.clearRecordInfo()
              .clearDisplaySettings()
              .clearHeadIconFrameInfo()
              .clearPlayerOutfitData()
              .clearChallengeDetail()
              .clearUnkDetail()
              .clearPlatformType()
              .clearShowDisplayAvatars();
          detail.mergeFrom(protocol.translate("PlayerDetailInfo", core.toByteArray(), true));
          var record = builder(session, "PlayerRecordInfo");
          // Native EAFMJGEGIDG.MergeFrom tag 0x48 stores +0x3c, then the board reads it into +0x78.
          // Frozen descriptor name is JJKIKOBAJLF; names/layout in reference binaries are not
          // reused.
          set(record, "JJKIKOBAJLF", books);
          set(detail, "record_info", record.build());
          set(response, "detail_info", detail.build());
        }
      }
    }
    session.sendClientPacket("GetPlayerDetailInfoScRsp", response.build().toByteArray());
    return true;
  }

  private static int readUid(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > 16) return -1;
    try {
      var input = CodedInputStream.newInstance(bytes);
      if (input.readTag() != 56) return -1;
      long uid = input.readUInt64();
      if (uid <= 0 || uid > Integer.MAX_VALUE || input.readTag() != 0 || !input.isAtEnd())
        return -1;
      return (int) uid;
    } catch (IOException invalid) {
      return -1;
    }
  }

  private BookCollection46() {}
}
