package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.*;
import org.hkrpg.server.gameplay.TrainCakeCatch;
import org.hkrpg.server.gameplay.TrainCakeDiyState;

/** Native arrangement request and the player's durable, validated visual layout. */
final class TrainCakeDiy46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    if (command != 10160 || !session.getProtocol().experimental()) return false;
    if (session.getState() != SessionState.ACTIVE || session.getPlayer() == null) return true;
    var player = session.getPlayer();
    var response = builder(session, "TrainCakeCatchDiyScRsp");
    var result = Retcode.REQ_PARA_INVALID;
    try {
      if (bytes == null || bytes.length == 0 || bytes.length > 8192) throw new IllegalArgumentException();
      var req = session.getProtocol().clientSchema().parse("TrainCakeCatchDiyCsReq", bytes);
      var field = req.getDescriptorForType().findFieldByName("NLJBJMDPBGN");
      if (!req.getUnknownFields().asMap().isEmpty() || !only(req, "NLJBJMDPBGN") || !req.hasField(field))
        throw new IllegalArgumentException();
      var next = read((DynamicMessage) req.getField(field));
      var membership = TrainCakeSocial46.registry(session).current(session);
      boolean social = TrainCakeSocial46.inSocialScene(player);
      if (social && (membership == null || membership.ownerUid() != player.getUid()))
        result = Retcode.INTERACT_TYPE_INVALID;
      else if (!social && membership != null) result = Retcode.PLAYER_DATA_ERROR;
      else {
        result = TrainCakeCatch.admissionForDiy(player, social);
        if (result == Retcode.SUCC) result = TrainCakeDiyState.update(player, next);
      }
      if (result == Retcode.SUCC) set(response, "NLJBJMDPBGN", toProto(session, player));
    } catch (Exception invalid) { result = Retcode.REQ_PARA_INVALID; }
    set(response, "retcode", result.getVal());
    SceneActionDiagnostics46.rejected(session, "TrainCakeCatchDiyCsReq", "train-cake-arrange", result, 0, 0, 0, 0);
    session.sendClientPacket("TrainCakeCatchDiyScRsp", response.build().toByteArray());
    if (result == Retcode.SUCC) TrainCakeSocial46.ownerBoxOpened(session);
    return true;
  }

  private static TrainCakeDiyState read(DynamicMessage layout) {
    if (!layout.getUnknownFields().asMap().isEmpty() || !only(layout, "PKMHMGAEAGO", "PNHLAAMNGFE", "GLOGEMFDMJG"))
      throw new IllegalArgumentException();
    int theme = ((EnumValueDescriptor) value(layout, "GLOGEMFDMJG")).getNumber();
    var grounds = new ArrayList<TrainCakeDiyState.Ground>();
    for (var item : (List<?>) value(layout, "PKMHMGAEAGO")) {
      var row = (DynamicMessage) item;
      if (!row.getUnknownFields().asMap().isEmpty() || !only(row, "dice_slot_id", "pose", "DMIOBEKLDED"))
        throw new IllegalArgumentException();
      var cats = new ArrayList<Integer>();
      for (var cat : (List<?>) value(row, "DMIOBEKLDED")) cats.add((Integer) cat);
      grounds.add(new TrainCakeDiyState.Ground(number(row, "dice_slot_id"), number(row, "pose"), cats));
    }
    var frames = new ArrayList<TrainCakeDiyState.Frame>();
    for (var item : (List<?>) value(layout, "PNHLAAMNGFE")) {
      var row = (DynamicMessage) item;
      if (!row.getUnknownFields().asMap().isEmpty() || !only(row, "PCPDHEIEJFO", "slot"))
        throw new IllegalArgumentException();
      frames.add(new TrainCakeDiyState.Frame(number(row, "slot"), number(row, "PCPDHEIEJFO")));
    }
    return new TrainCakeDiyState(theme, grounds, frames);
  }

  static DynamicMessage toProto(GameSession session, Player player) {
    var layout = TrainCakeDiyState.current(player);
    var result = builder(session, "BFAKMCIJFCB");
    var theme = result.getDescriptorForType().findFieldByName("GLOGEMFDMJG");
    result.setField(theme, theme.getEnumType().findValueByNumber(layout.theme()));
    for (var ground : layout.grounds()) {
      var row = builder(session, "IIONNFELFEF");
      set(row, "dice_slot_id", ground.slot()); set(row, "pose", ground.pose());
      for (int cat : ground.cats()) add(row, "DMIOBEKLDED", cat);
      add(result, "PKMHMGAEAGO", row.build());
    }
    for (var frame : layout.frames()) {
      var row = builder(session, "HJACHMJFEAH");
      set(row, "slot", frame.slot()); set(row, "PCPDHEIEJFO", frame.cat());
      add(result, "PNHLAAMNGFE", row.build());
    }
    return result.build();
  }
  private TrainCakeDiy46() {}
}
