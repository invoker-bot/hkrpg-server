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
    if (command == 8026) {
      var descriptor = session.getProtocol().clientSchema().message("TrainPartySetBadgeAutoFillCsReq");
      boolean valid = bytes != null && Parkour46.wireValid(descriptor, bytes, 0);
      var code = valid ? Retcode.FEATURE_SWITCH_CLOSED : Retcode.REQ_PARA_INVALID;
      var response = builder(session, "TrainPartySetBadgeAutoFillScRsp");
      set(response, "retcode", code.getVal());
      GameDiagnostics.outcome(session, "TrainPartySetBadgeAutoFillCsReq", 0, code,
          valid ? "badge-state-unavailable" : "invalid-wire", "");
      session.sendClientPacket(8080, response.build().toByteArray());
      return true;
    }
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
            var dynamics = (List<?>) value(display, "GOEJIMNENDC");
            var pictures = (List<?>) value(display, "OFOKOHGPEDO");
            var badges = (List<?>) value(display, "POLMJFIKNHN");
            boolean valid = dynamics.size() <= 6 && pictures.size() <= 6 && badges.isEmpty();
            for (var raw : dynamics) {
              var row = (DynamicMessage) raw;
              valid &= items.putIfAbsent(number(row, "dice_slot_id"), number(row, "diy_dynamic_id")) == null;
            }
            for (var raw : pictures) {
              var row = (DynamicMessage) raw;
              valid &= photos.putIfAbsent(number(row, "slot"),
                  new PlayerRoomDisplayState.Picture(number(row, "id"), number(row, "type"))) == null;
            }
            if (valid) {
              var result = PlayerRoomDisplay.replace(session.getPlayer(), items, photos);
              code = result.code(); reason = result.reason(); state = result.state();
            } else reason = badges.isEmpty() ? "duplicate-or-oversize" : "badge-state-unavailable";
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
    return display.build();
  }

  private PlayerRoomDisplay46() {}
}
