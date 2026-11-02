package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import org.hkrpg.server.gameplay.TrainCakeCatch;
import org.hkrpg.server.gameplay.TrainCakeCatchState;

/** Native box/search replies and the local owner's read-only social profile. */
final class TrainCakeCatch46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    String request = switch (command) {
      case 10157 -> "TrainCakeCatchOpenBox";
      case 10144 -> "TrainCakeCatchSearch";
      case 9641 -> "SocialPlayGetData";
      default -> null;
    };
    if (request == null || !session.getProtocol().experimental()) return false;
    if (session.getState() != SessionState.ACTIVE || session.getPlayer() == null) return true;
    var player = session.getPlayer();
    synchronized (player) {
      var code = Retcode.REQ_PARA_INVALID;
      TrainCakeCatchState state = null;
      int cat = 0;
      boolean valid = bytes != null && bytes.length == 0;
      if (command == 10144) {
        cat = readCat(bytes);
        valid = cat > 0;
      }
      if (valid) {
        if (command == 9641) {
          try { state = TrainCakeCatch.snapshot(player); code = Retcode.SUCC; }
          catch (RuntimeException invalid) { code = Retcode.PLAYER_DATA_ERROR; }
        } else {
          var result = command == 10157 ? TrainCakeCatch.open(player, TrainCakeCatch.now())
              : TrainCakeCatch.search(player, cat, TrainCakeCatch.now());
          code = result.retcode(); state = result.state();
        }
      }
      var response = builder(session, request + "ScRsp");
      set(response, "retcode", code.getVal());
      if (command == 9641) {
        // SyncTrainRoomGateData dereferences the profile even for an unplayed module.
        set(response, "ELDIONMAAHO", profile(session,
            code == Retcode.SUCC ? state : new TrainCakeCatchState()));
        set(response, "NDDJFLPBHBL", builder(session, "HAIFIDAHGJG").build());
      } else if (code == Retcode.SUCC) {
        if (command == 10157) {
          set(response, "CMHKFNFKGOI", state.getRefreshAt());
          addSpots(session, response, state);
        } else {
          set(response, "PCPDHEIEJFO", cat);
          set(response, "NIELDCLPOLA", count(session, cat, state.getCounts().get(cat)));
          addWeekRecord(session, response, state);
          // Notify only committed captures; never grant inventory/performance progress on open.
          var notify = builder(session, "TrainCakeCatchDataScNotify");
          add(notify, "NIELDCLPOLA", count(session, cat, state.getCounts().get(cat)));
          session.sendClientPacket("TrainCakeCatchDataScNotify", notify.build().toByteArray());
        }
      }
      if (command != 9641) SceneActionDiagnostics46.rejected(session, request + "CsReq",
          "train-cat-box-preconditions", code, 0, 0, 0, 0);
      session.sendClientPacket(request + "ScRsp", response.build().toByteArray());
    }
    return true;
  }

  private static int readCat(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > 16) return 0;
    try {
      var input = CodedInputStream.newInstance(bytes);
      if (input.readTag() != 88) return 0; // native field 11, unsigned cat identity
      long cat = input.readUInt64();
      return input.isAtEnd() && cat >= 1 && cat <= 27 ? (int) cat : 0;
    } catch (IOException invalid) { return 0; }
  }

  private static DynamicMessage profile(GameSession s, TrainCakeCatchState state) {
    var result = builder(s, "KPBCIIFIAGI");
    set(result, "CMHKFNFKGOI", state.getRefreshAt());
    set(result, "NLJBJMDPBGN", builder(s, "BFAKMCIJFCB").build());
    set(result, "KABGCGENKLK", builder(s, "CGLJLBDFNBK").build());
    if (state.getRefreshAt() > TrainCakeCatch.now()) {
      addSpots(s, result, state);
      addWeekRecord(s, result, state);
    }
    for (var entry : state.getCounts().entrySet())
      add(result, "NIELDCLPOLA", count(s, entry.getKey(), entry.getValue()));
    return result.build();
  }

  private static void addSpots(GameSession s, DynamicMessage.Builder response, TrainCakeCatchState state) {
    for (var spot : state.getSpots()) {
      var row = builder(s, "IKAHKMNCFDN");
      set(row, "PCPDHEIEJFO", spot.getCat()); set(row, "slot", spot.getSlot());
      add(response, "BJPHDKEBOBK", row.build());
    }
  }

  private static DynamicMessage count(GameSession s, int cat, int count) {
    var row = builder(s, "JOFHMCJPBCE");
    set(row, "PCPDHEIEJFO", cat); set(row, "count", count);
    return row.build();
  }

  private static void addWeekRecord(GameSession s, DynamicMessage.Builder response, TrainCakeCatchState state) {
    if (state.getCaught().isEmpty()) return;
    var row = builder(s, "PJFIMFJPMCN");
    // Native _RefreshCakeCatchRecordHistory indexes by room owner, then actual caught cat IDs.
    set(row, "PCPDHEIEJFO", s.getPlayer().getUid());
    for (int cat : state.getCaught()) add(row, "LPDFMJPFGJH", cat);
    add(response, "CEMFBJPOLNP", row.build());
  }

  private TrainCakeCatch46() {}
}
