package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;
import org.hkrpg.server.gameplay.PlayerRoomDisplay;
import org.hkrpg.server.gameplay.PlayerRoomDisplayState;

/** 4.6 personal-room display snapshots and full replacement requests. */
final class PlayerRoomDisplay46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental() || command != 8096 && command != 8026) return false;
    if (session.getState() != SessionState.ACTIVE || session.getPlayer() == null) return true;
    if (command == 8026) return autoFill(session, bytes);
    synchronized (session.getPlayer()) {
      var code = Retcode.REQ_PARA_INVALID;
      String reason = "invalid-wire";
      PlayerRoomDisplayState state = null;
      try {
        var schema = session.getProtocol().clientSchema();
        var descriptor = schema.message("TrainPartyBuildSelfDisplayAreaDiyCsReq");
        if (bytes != null && Parkour46.wireValid(descriptor, bytes, 0)) {
          var request = schema.parse("TrainPartyBuildSelfDisplayAreaDiyCsReq", bytes);
          var displayField = descriptor.findFieldByName("ACLKMFCDMED");
          if (request.hasField(displayField)) {
            var display = (DynamicMessage) request.getField(displayField);
            var items = new TreeMap<Integer, Integer>();
            var photos = new TreeMap<Integer, PlayerRoomDisplayState.Picture>();
            var badgeItems = new TreeMap<Integer, PlayerRoomDisplayState.Badge>();
            var dynamics = (List<?>) value(display, "GOEJIMNENDC");
            var pictures = (List<?>) value(display, "OFOKOHGPEDO");
            var badges = (List<?>) value(display, "POLMJFIKNHN");
            boolean valid = dynamics.size() <= 6 && pictures.size() <= 6 && badges.size() <= 9;
            for (var raw : dynamics) {
              var row = (DynamicMessage) raw;
              valid &= items.putIfAbsent(number(row, "dice_slot_id"), number(row, "diy_dynamic_id")) == null;
            }
            for (var raw : pictures) {
              var row = (DynamicMessage) raw;
              valid &= photos.putIfAbsent(number(row, "slot"),
                  new PlayerRoomDisplayState.Picture(number(row, "id"), number(row, "type"))) == null;
            }
            for (var raw : badges) {
              var row = (DynamicMessage) raw;
              valid &= badgeItems.putIfAbsent(number(row, "dice_slot_id"),
                  new PlayerRoomDisplayState.Badge(number(row, "EIKLMFPMJJD"),
                      (Long) value(row, "expire_time"))) == null;
            }
            if (valid) {
              var result = PlayerRoomDisplay.replace(session.getPlayer(), items, photos, badgeItems);
              code = result.code(); reason = result.reason(); state = result.state();
            } else reason = "duplicate-or-oversize";
          }
        }
      } catch (java.io.IOException invalid) {
        reason = "invalid-wire";
      } catch (RuntimeException failed) {
        code = Retcode.SERVER_INTERNAL_ERROR;
        reason = "server-error";
        GameDiagnostics.packetFailure(session, command, false, "handler", failed);
      }
      var response = builder(session, "TrainPartyBuildSelfDisplayAreaDiyScRsp");
      set(response, "retcode", code.getVal());
      if (code == Retcode.SUCC) set(response, "ACLKMFCDMED", display(session, state));
      else GameDiagnostics.outcome(session, "TrainPartyBuildSelfDisplayAreaDiyCsReq", 0,
          code, reason, "");
      session.sendClientPacket(8047, response.build().toByteArray());
    }
    return true;
  }

  private static boolean autoFill(GameSession session, byte[] bytes) {
    synchronized (session.getPlayer()) {
      var descriptor = session.getProtocol().clientSchema().message("TrainPartySetBadgeAutoFillCsReq");
      var code = Retcode.REQ_PARA_INVALID;
      String reason = "invalid-wire";
      boolean closed = true, changed = false;
      try {
        if (bytes != null && Parkour46.wireValid(descriptor, bytes, 0)) {
          var request = session.getProtocol().clientSchema().parse("TrainPartySetBadgeAutoFillCsReq", bytes);
          closed = (Boolean)value(request, "PELJACANAFH");
          var result = PlayerRoomDisplay.setBadgeAutoFillClosed(session.getPlayer(), closed);
          code = result.code(); reason = result.reason(); changed = "saved".equals(result.reason());
        }
      } catch (java.io.IOException invalid) {
        reason = "invalid-wire";
      } catch (RuntimeException failure) {
        code = Retcode.SERVER_INTERNAL_ERROR; reason = "server-error";
        GameDiagnostics.packetFailure(session, 8026, false, "handler", failure);
      }
      if (code != Retcode.SUCC) {
        var old = session.getPlayer().getGameplay().getPlayerRoomDisplay();
        closed = old == null || old.isBadgeAutoFillClosed();
        GameDiagnostics.outcome(session, "TrainPartySetBadgeAutoFillCsReq", 0, code, reason, "");
      }
      var response = builder(session, "TrainPartySetBadgeAutoFillScRsp");
      set(response, "retcode", code.getVal()); set(response, "PELJACANAFH", closed);
      session.sendClientPacket(8080, response.build().toByteArray());
      // The native badge-update callback asks for a fresh TrainParty snapshot.
      if (code == Retcode.SUCC && changed) Badge46.notifyOwned(session.getPlayer());
    }
    return true;
  }

  static DynamicMessage display(GameSession session, PlayerRoomDisplayState state) {
    var display = builder(session, "OIIPJFDNPAH");
    for (var item : state.getItems().entrySet()) {
      var row = builder(session, "AreaDynamicInfo");
      set(row, "dice_slot_id", item.getKey()); set(row, "diy_dynamic_id", item.getValue());
      add(display, "GOEJIMNENDC", row.build());
    }
    for (var picture : state.getPictures().entrySet()) {
      var row = builder(session, "CLLBJDEJFNF");
      set(row, "slot", picture.getKey()); set(row, "id", picture.getValue().getId());
      set(row, "type", picture.getValue().getType());
      add(display, "OFOKOHGPEDO", row.build());
    }
    for (var badge : state.getBadges().entrySet()) {
      var row = builder(session, "POFNNGOCEOK");
      set(row, "dice_slot_id", badge.getKey());
      set(row, "EIKLMFPMJJD", badge.getValue().getId());
      set(row, "expire_time", badge.getValue().getExpireTime());
      add(display, "POLMJFIKNHN", row.build());
    }
    return display.build();
  }

  private PlayerRoomDisplay46() {}
}
