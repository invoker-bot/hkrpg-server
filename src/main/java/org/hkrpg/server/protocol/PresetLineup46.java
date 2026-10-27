package org.hkrpg.server.protocol;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/** Persistent favourite flags for ordinary teams on the pinned CN4.6 wire protocol. */
public final class PresetLineup46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 793) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var player = session.getPlayer();
    synchronized (player) {
      Retcode code = Retcode.REQ_PARA_INVALID;
      int index = 0;
      boolean favourite = false;
      var schema = session.getProtocol().clientSchema();
      var descriptor = schema.message("MarkPresetLineupCsReq");
      if (Parkour46.wireValid(descriptor, bytes, 0)) {
        var request = schema.parse("MarkPresetLineupCsReq", bytes);
        index = Gameplay46.number(request, "index");
        favourite = (Boolean) Gameplay46.value(request, "_is_favourite");
        var teams = player.getLineupManager().getLineups();
        if (request.getUnknownFields().asMap().isEmpty()
            && Gameplay46.only(request, "index", "_is_favourite")
            && index >= 0 && index < teams.length && teams[index] != null
            && !teams[index].isExtraLineup()) {
          var team = teams[index];
          if (team.isFavourite() != favourite) {
            boolean previous = team.isFavourite();
            team.setFavourite(favourite);
            try {
              team.save();
              code = Retcode.SUCC;
            } catch (RuntimeException failure) {
              team.setFavourite(previous);
              emu.lunarcore.LunarCore.getLogger().warn("Could not save favourite team {}", index, failure);
              code = Retcode.SYSTEM_BUSY;
            }
          } else {
            code = Retcode.SUCC;
          }
        }
      }
      // SetTeamFavourite is the 4.6 response whose schema name is absent from the command table.
      var buffer = new ByteArrayOutputStream();
      var out = CodedOutputStream.newInstance(buffer);
      if (index != 0) out.writeUInt32(7, index);
      if (favourite && code == Retcode.SUCC) out.writeBool(9, true);
      if (code != Retcode.SUCC) out.writeUInt32(13, code.getVal());
      out.flush();
      session.sendClientPacket(773, buffer.toByteArray());
    }
    return true;
  }

  /** The legacy LineupInfo has no favourite field; add it only to real normal-team snapshots. */
  public static byte[] enrich(ProtocolSchema schema, String name, byte[] bytes, Player player) {
    if (player == null || !("GetAllLineupDataScRsp".equals(name)
        || "GetCurLineupDataScRsp".equals(name) || "SyncLineupNotify".equals(name))) return bytes;
    synchronized (player) {
      try {
        var message = schema.parse(name, bytes);
        var field = message.getDescriptorForType().findFieldByName(
            name.equals("GetAllLineupDataScRsp") ? "lineup_list" : "lineup");
        if (field == null) return bytes;
        var result = message.toBuilder();
        boolean changed = false;
        if (field.isRepeated()) {
          var rows = (List<?>) message.getField(field);
          for (int i = 0; i < rows.size(); i++) {
            var original = (DynamicMessage) rows.get(i);
            var enriched = enrichLineup(original, player, name);
            if (enriched != original) {
              result.setRepeatedField(field, i, enriched);
              changed = true;
            }
          }
        } else if (message.hasField(field)) {
          var original = (DynamicMessage) message.getField(field);
          var enriched = enrichLineup(original, player, name);
          if (enriched != original) {
            result.setField(field, enriched);
            changed = true;
          }
        }
        return changed ? result.build().toByteArray() : bytes;
      } catch (IOException ex) {
        throw new ProtocolException("Malformed outgoing normal-team snapshot: " + name);
      }
    }
  }

  private static DynamicMessage enrichLineup(DynamicMessage row, Player player, String name) {
    int index = Gameplay46.number(row, "index");
    var teams = player.getLineupManager().getLineups();
    if (index < 0 || index >= teams.length || teams[index] == null) return row;
    var descriptor = row.getDescriptorForType();
    var extra = (EnumValueDescriptor) Gameplay46.value(row, "extra_lineup_type");
    if (extra.getNumber() != 0 || Gameplay46.number(row, "game_story_line_id") != 0
        || (Boolean) Gameplay46.value(row, "_is_virtual")) return row;
    PlayerLineup source = name.equals("GetCurLineupDataScRsp")
        ? player.getCurrentLineup() : player.getLineupManager().getLineupByIndex(index);
    if (name.equals("GetAllLineupDataScRsp")
        && index == player.getLineupManager().getCurrentIndex()
        && player.getRaidTrialManager().inside()) source = player.getCurrentLineup();
    if (source != teams[index]) return row;
    var field = descriptor.findFieldByName("_is_favourite");
    if (field == null || (Boolean) row.getField(field) == source.isFavourite()) return row;
    return row.toBuilder().setField(field, source.isFavourite()).build();
  }

  private PresetLineup46() {}
}
