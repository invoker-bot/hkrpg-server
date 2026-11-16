package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.hkrpg.server.gameplay.TrainCakeCatch;
import org.hkrpg.server.gameplay.TrainCakeCatchState;
import org.hkrpg.server.gameplay.TrainCakeVisits;

/** Native box/search replies and the local owner's read-only social profile. */
final class TrainCakeCatch46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    if (TrainCakeSocial46.handle(session, command, bytes)) return true;
    if (TrainCakeDiy46.handle(session, command, bytes)) return true;
    String request = switch (command) {
      case 10157 -> "TrainCakeCatchOpenBox";
      case 10144 -> "TrainCakeCatchSearch";
      case 9641 -> "SocialPlayGetData";
      default -> null;
    };
    if (request == null || !session.getProtocol().experimental()) return false;
    if (session.getState() != SessionState.ACTIVE || session.getPlayer() == null) return true;
    var player = session.getPlayer();
    boolean notifyRoom = false;
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
          try {
            state = TrainCakeCatch.snapshot(player);
            TrainCakeVisits.totalCounts(player);
            TrainCakeVisits.records(player, TrainCakeCatch.now());
            org.hkrpg.server.gameplay.TrainCakeDiyState.current(player);
            code = Retcode.SUCC;
          }
          catch (RuntimeException invalid) { code = Retcode.PLAYER_DATA_ERROR; }
        } else {
          var result = command == 10157 ? TrainCakeCatch.open(player, TrainCakeCatch.now())
              : TrainCakeCatch.search(player, cat, TrainCakeCatch.now());
          code = result.retcode(); state = result.state();
          if (code == Retcode.SUCC) try {
            player.getMissionManager().onTrainCakeAction(command == 10157);
          } catch (RuntimeException failed) {
            code = Retcode.SERVER_INTERNAL_ERROR;
            org.hkrpg.server.GameDiagnostics.outcome(session, request + "CsReq", 0, code,
                "train-cat-goal-sync-failed", "");
          }
        }
      }
      var response = builder(session, request + "ScRsp");
      set(response, "retcode", code.getVal());
      if (command == 9641) {
        // SyncTrainRoomGateData dereferences the profile even for an unplayed module.
        set(response, "ELDIONMAAHO", profile(session,
            code == Retcode.SUCC ? state : new TrainCakeCatchState(), code == Retcode.SUCC));
        // ParkPetDiyController also consumes this reply: an absent field 14 is null,
        // whereas a present empty HHCNMGNAIBG clears unimplemented area placements.
        var social = builder(session, "HAIFIDAHGJG");
        set(social, "JIPPNPILKDJ", builder(session, "HHCNMGNAIBG").build());
        set(response, "NDDJFLPBHBL", social.build());
      } else if (code == Retcode.SUCC) {
        if (command == 10157) {
          set(response, "CMHKFNFKGOI", state.getRefreshAt());
          addSpots(session, response, state);
        } else {
          set(response, "PCPDHEIEJFO", cat);
          int total = TrainCakeVisits.totalCounts(player).get(cat);
          set(response, "NIELDCLPOLA", count(session, cat, total));
          addWeekRecord(session, response, state);
          // Notify only committed captures; never grant inventory/performance progress on open.
          var notify = builder(session, "TrainCakeCatchDataScNotify");
          add(notify, "NIELDCLPOLA", count(session, cat, total));
          session.sendClientPacket("TrainCakeCatchDataScNotify", notify.build().toByteArray());
        }
      }
      if (command != 9641) SceneActionDiagnostics46.rejected(session, request + "CsReq",
          "train-cat-box-preconditions", code, 0, 0, 0, 0);
      session.sendClientPacket(request + "ScRsp", response.build().toByteArray());
      notifyRoom = command == 10157 && code == Retcode.SUCC;
    }
    // Do not hold the owner's lock while inspecting or notifying other sessions.
    if (notifyRoom) TrainCakeSocial46.ownerBoxOpened(session);
    return true;
  }

  static int readCat(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > 16) return 0;
    try {
      var input = CodedInputStream.newInstance(bytes);
      if (input.readTag() != 88) return 0; // native field 11, unsigned cat identity
      long cat = input.readUInt64();
      return input.isAtEnd() && cat >= 1 && cat <= 27 ? (int) cat : 0;
    } catch (IOException invalid) { return 0; }
  }

  private static DynamicMessage profile(GameSession s, TrainCakeCatchState state, boolean valid) {
    var result = builder(s, "KPBCIIFIAGI");
    set(result, "CMHKFNFKGOI", valid
        ? TrainCakeCatch.clientRefreshAt(s.getPlayer(), state, TrainCakeCatch.now()) : state.getRefreshAt());
    set(result, "NLJBJMDPBGN", valid ? TrainCakeDiy46.toProto(s, s.getPlayer()) : builder(s, "BFAKMCIJFCB").build());
    set(result, "KABGCGENKLK", builder(s, "CGLJLBDFNBK").build());
    if (state.getRefreshAt() > TrainCakeCatch.now()) addSpots(s, result, state);
    if (valid) addWeekRecord(s, result, state);
    var counts = valid ? TrainCakeVisits.totalCounts(s.getPlayer()) : state.getCounts();
    for (var entry : counts.entrySet())
      add(result, "NIELDCLPOLA", count(s, entry.getKey(), entry.getValue()));
    return result.build();
  }

  private static void addSpots(GameSession s, DynamicMessage.Builder response, TrainCakeCatchState state) {
    for (var spot : state.getSpots()) {
      var row = builder(s, "IKAHKMNCFDN");
      set(row, "PCPDHEIEJFO", spot.getCat()); set(row, "slot", TrainCakeCatch.clientSlot(spot.getSlot()));
      add(response, "BJPHDKEBOBK", row.build());
    }
  }

  static DynamicMessage count(GameSession s, int cat, int count) {
    var row = builder(s, "JOFHMCJPBCE");
    set(row, "PCPDHEIEJFO", cat); set(row, "count", count);
    return row.build();
  }

  static void addWeekRecord(GameSession s, DynamicMessage.Builder response, TrainCakeCatchState state) {
    long now = TrainCakeCatch.now();
    // The client indexes the history by cat ID and looks up the room-owner UID
    // in that cat's set. Storage uses the opposite ordering, so transpose here.
    var roomsByCat = new TreeMap<Integer, Set<Integer>>();
    if (state.getRefreshAt() > now)
      for (int cat : state.getCaught())
        roomsByCat.computeIfAbsent(cat, ignored -> new TreeSet<>()).add(s.getPlayer().getUid());
    for (var entry : TrainCakeVisits.records(s.getPlayer(), now).entrySet())
      for (int cat : entry.getValue())
        roomsByCat.computeIfAbsent(cat, ignored -> new TreeSet<>()).add(entry.getKey());
    for (var entry : roomsByCat.entrySet()) {
      var row = builder(s, "PJFIMFJPMCN");
      set(row, "PCPDHEIEJFO", entry.getKey());
      for (int owner : entry.getValue()) add(row, "LPDFMJPFGJH", owner);
      add(response, "CEMFBJPOLNP", row.build());
    }
  }

  private TrainCakeCatch46() {}
}
