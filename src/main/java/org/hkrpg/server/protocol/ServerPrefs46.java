package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import org.hkrpg.server.gameplay.ServerPrefsState;

/** CN4.6 preferences: update 6160/6116, all 6134/6125, individual 6105/6175. */
public final class ServerPrefs46 {
  private record Update(int id, byte[] data) {}

  public static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental()
        || command != 6160 && command != 6134 && command != 6105) return false;
    if (session.getState() != SessionState.ACTIVE || session.getPlayer() == null) return true;
    synchronized (session.getPlayer()) {
      var state = session.getPlayer().getGameplay().getServerPrefs();
      String name = command == 6160 ? "UpdateServerPrefsDataScRsp"
          : command == 6134 ? "GetAllServerPrefsDataScRsp" : "GetServerPrefsDataScRsp";
      var response = builder(session, name);
      Retcode result = Retcode.SUCC;
      if (command == 6160) {
        var update = readUpdate(bytes);
        if (update == null) result = Retcode.REQ_PARA_INVALID;
        else {
          set(response, "server_prefs_id", update.id());
          result = state == null ? Retcode.PLAYER_DATA_ERROR
              : state.update(session.getPlayer(), update.id(), update.data());
        }
      } else {
        int id = command == 6105 ? readId(bytes) : 0;
        if (bytes == null || command == 6134 && bytes.length != 0 || command == 6105 && id <= 0)
          result = Retcode.REQ_PARA_INVALID;
        else if (state == null || !state.valid()) result = Retcode.PLAYER_DATA_ERROR;
        else if (command == 6134) state.snapshot().forEach((key, data) ->
            add(response, "server_prefs_list", row(session, key, data)));
        else set(response, "server_prefs", row(session, id, state.snapshot().getOrDefault(id, new byte[0])));
      }
      set(response, "retcode", result.getVal());
      session.sendClientPacket(name, response.build().toByteArray());
    }
    return true;
  }

  private static DynamicMessage row(GameSession session, int id, byte[] bytes) {
    var row = builder(session, "ServerPrefs");
    set(row, "server_prefs_id", id);
    set(row, "data", ByteString.copyFrom(bytes));
    return row.build();
  }

  private static Update readUpdate(byte[] bytes) {
    if (bytes == null || bytes.length > ServerPrefsState.MAX_VALUE_BYTES + 32) return null;
    try {
      var outer = CodedInputStream.newInstance(bytes);
      if (outer.readTag() != 122) return null; // message field 15
      var nested = CodedInputStream.newInstance(outer.readByteArray());
      if (!outer.isAtEnd()) return null;
      int id = 0;
      byte[] data = new byte[0];
      boolean seenData = false;
      for (int tag; (tag = nested.readTag()) != 0;) {
        if (tag == 24 && id == 0) {
          long value = nested.readUInt64();
          if (value <= 0 || value > Integer.MAX_VALUE) return null;
          id = (int) value;
        } else if (tag == 106 && !seenData) {
          seenData = true;
          data = nested.readByteArray();
          if (data.length > ServerPrefsState.MAX_VALUE_BYTES) return null;
        } else return null;
      }
      return id > 0 && nested.isAtEnd() ? new Update(id, data) : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private static int readId(byte[] bytes) {
    if (bytes == null || bytes.length > 6) return 0;
    try {
      var input = CodedInputStream.newInstance(bytes);
      if (input.readTag() != 16) return 0; // IANCKAHGKMI.CFBDEAHMPKF, field 2
      long id = input.readUInt64();
      return input.isAtEnd() && id > 0 && id <= Integer.MAX_VALUE ? (int) id : 0;
    } catch (IOException invalid) {
      return 0;
    }
  }

  private ServerPrefs46() {}
}
