package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.RogueHandbookRewards;

/** Fixed 4.6 request list 2, returned actually paid identities 15 and ItemList 11. */
public final class RogueHandbookReward46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("TakeRogueMiracleHandbookRewardCsReq"))
      return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var result = RogueHandbookRewards.claim(session.getPlayer(), identities(bytes));
      var response = builder(session, "TakeRogueMiracleHandbookRewardScRsp");
      set(response, "retcode", result.code().getVal());
      set(response, "reward", items(session, result.items()));
      for (int id : result.identities()) add(response, "CNDLCGEFLCJ", id);
      session.sendClientPacket(
          "TakeRogueMiracleHandbookRewardScRsp", response.build().toByteArray());
    }
    return true;
  }

  private static int integer(CodedInputStream input) throws IOException {
    int start = input.getTotalBytesRead();
    long value = input.readUInt64();
    if (input.getTotalBytesRead() - start > 5 || value < 0 || value > Integer.MAX_VALUE)
      throw new IOException("Unverified uint32 alias");
    return (int) value;
  }

  static List<Integer> identities(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > 4096) return null;
    var input = CodedInputStream.newInstance(bytes);
    var values = new ArrayList<Integer>();
    try {
      while (!input.isAtEnd()) {
        int before = input.getTotalBytesRead();
        int tag = integer(input);
        if (input.getTotalBytesRead() - before != 1) return null;
        if (tag == 16) values.add(integer(input));
        else if (tag == 18) {
          int length = integer(input);
          var packed = CodedInputStream.newInstance(input.readRawBytes(length));
          while (!packed.isAtEnd()) {
            values.add(integer(packed));
            if (values.size() > 100) return null;
          }
        } else return null;
        if (values.size() > 100) return null;
      }
      return values.isEmpty() ? null : List.copyOf(values);
    } catch (IOException malformed) {
      return null;
    }
  }

  private RogueHandbookReward46() {}
}
