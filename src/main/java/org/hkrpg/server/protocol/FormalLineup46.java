package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.*;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.*;
import java.util.*;

/** Active normal formal roster; native CN replacement reply is command719 fields7/9. */
public final class FormalLineup46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 704) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var code = Retcode.REQ_PARA_INVALID;
      int index = 0;
      var type = session.getProtocol().clientSchema().message("ReplaceLineupCsReq");
      if (Parkour46.wireValid(type, bytes, 0)) {
        var req = session.getProtocol().clientSchema().parse("ReplaceLineupCsReq", bytes);
        index = number(req, "index");
        if (only(req, "index", "plane_id", "lineup_slot_list", "leader_slot")
            && req.getUnknownFields().asMap().isEmpty()) {
          var p = session.getPlayer();
          var g = p.getGameplay();
          var lineup = p.getCurrentLineup();
          if (p.isInBattle()
              || p.getInstance() != null
              || g.getCurrentMissionRaid() != 0
              || g.getTrialRun() != null
              || g.getRogue().isInside()
              || g.getStoryLines().getCurrentId() != 0
              || g.getStoryLines().isTransitionPending()
              || g.getParkourVenue().inside()
              || g.getParkour().getActiveLevel() != 0
              || g.getRhythm().getActiveLevel() != 0) code = Retcode.SYSTEM_BUSY;
          else if (lineup != null
              && !lineup.isExtraLineup()
              && index == p.getLineupManager().getCurrentIndex()
              && (number(req, "plane_id") == 0 || number(req, "plane_id") == p.getPlaneId())
              && p.getScene() != null) {
            var rows = (List<?>) value(req, "lineup_slot_list");
            var slots = new TreeMap<Integer, Integer>();
            int leader = number(req, "leader_slot");
            boolean valid =
                !rows.isEmpty() && rows.size() <= 4 && leader >= 0 && leader < rows.size();
            for (Object raw : rows) {
              var row = (DynamicMessage) raw;
              int slot = number(row, "slot"), id = number(row, "id");
              var avatar = p.getAvatarById(id);
              valid &=
                  row.getUnknownFields().asMap().isEmpty()
                      && only(row, "slot", "id", "avatar_type")
                      && "AvatarType_AvatarFormalType"
                          .equals(
                              ((Descriptors.EnumValueDescriptor) value(row, "avatar_type"))
                                  .getName())
                      && slot >= 0
                      && slot < rows.size()
                      && id > 0
                      && slots.putIfAbsent(slot, id) == null
                      && avatar != null
                      && avatar.getAvatarId() == id
                      && avatar.getAvatarType() == AvatarType.AVATAR_FORMAL_TYPE
                      && p.getAvatars().getAvatars().get(id) == avatar;
            }
            if (valid
                && slots.size() == rows.size()
                && new HashSet<>(slots.values()).size() == slots.size()) {
              var roster = new ArrayList<>(slots.values());
              if (!roster.equals(lineup.getAvatars()) || leader != lineup.getLeader()) {
                int oldLeader =
                    lineup.isActiveSlot(lineup.getLeader())
                        ? lineup.getAvatars().get(lineup.getLeader())
                        : 0;
                lineup.setLeader(leader);
                lineup.replace(roster);
                p.save();
                if (oldLeader != roster.get(leader))
                  p.getMissionManager().onTeamLeaderChange(roster.get(leader));
              }
              code = Retcode.SUCC;
            }
          }
        }
      }
      var buffer = new ByteArrayOutputStream();
      var out = CodedOutputStream.newInstance(buffer);
      if (index != 0) out.writeUInt32(7, index);
      if (code != Retcode.SUCC) out.writeUInt32(9, code.getVal());
      out.flush();
      session.sendClientPacket(719, buffer.toByteArray());
    }
    return true;
  }

  private FormalLineup46() {}
}
