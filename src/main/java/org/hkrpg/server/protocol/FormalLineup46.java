package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.*;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.*;
import java.util.*;

/** Normal formal roster; native CN replacement reply is command719 fields7/9. */
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
          var current = p.getCurrentLineup();
          var teams = p.getLineupManager().getLineups();
          var lineup = index >= 0 && index < teams.length ? teams[index] : null;
          boolean station = p.getMissionTrialManager().inside()
              && p.getMissionTrialManager().editableStation();
          boolean stationCurrent = station && index == p.getLineupManager().getCurrentIndex();
          if (p.isInBattle()
              || p.getInstance() != null
              || p.getMissionTrialManager().inside() && !station
              || p.getRaidTrialManager().inside()
              || current == null
              || current.isExtraLineup() && !station
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
              && (number(req, "plane_id") == 0 || number(req, "plane_id") == p.getPlaneId())
              && p.getScene() != null) {
            var rows = (List<?>) value(req, "lineup_slot_list");
            var slots = new TreeMap<Integer, Integer>();
            var actors = new HashSet<Integer>();
            int leader = number(req, "leader_slot");
            boolean valid = rows.size() <= 4 && leader >= 0 && leader < 4
                && (rows.isEmpty() ? lineup != current && !stationCurrent && leader == 0 : true);
            for (Object raw : rows) {
              var row = (DynamicMessage) raw;
              int slot = number(row, "slot"), id = number(row, "id");
              var avatar = p.getAvatarById(id);
              String avatarType = ((Descriptors.EnumValueDescriptor) value(row, "avatar_type")).getName();
              int canonical = avatar == null ? 0 : avatar.getAvatarId();
              boolean owned = avatar != null && avatar.getAvatarType() == AvatarType.AVATAR_FORMAL_TYPE
                  && p.getAvatars().getAvatars().get(canonical) == avatar
                  && avatar.getOwner() == p && avatar.getOwnerUid() == p.getUid();
              boolean formal = owned && avatarType.equals("AvatarType_AvatarFormalType") && canonical == id;
              var path = p.getAvatars().getMultiPathById(id);
              var pathRow = emu.lunarcore.data.GameData.getMultiplePathAvatarExcelMap().get(id);
              boolean fixedPath = owned && avatarType.equals("AvatarType_AvatarFixedPathType")
                  && path != null && path == avatar.getMultiPath() && pathRow != null
                  && path.getOwner() == p && path.getOwnerUid() == p.getUid()
                  && pathRow.getId() == id && pathRow.getBaseAvatarID() == canonical;
              boolean trial = stationCurrent && avatarType.equals("AvatarType_AvatarTrialType")
                  && id == 1011003 && avatar instanceof emu.lunarcore.game.trial.TrialAvatar
                  && avatar.getAvatarId() == id && avatar.getOwner() == p
                  && avatar.getOwnerUid() == p.getUid();
              valid &= row.getUnknownFields().asMap().isEmpty() && only(row, "slot", "id", "avatar_type")
                  && slot >= 0 && slot < 4 && id > 0 && (formal || fixedPath || trial)
                  && slots.putIfAbsent(slot, canonical) == null
                  && avatar != null && actors.add(avatar.getActorId());
            }
            if (valid
                && slots.size() == rows.size()
                && new HashSet<>(slots.values()).size() == slots.size()
                && (rows.isEmpty() || slots.containsKey(leader))) {
              var roster = new ArrayList<>(slots.values());
              int compactLeader = rows.isEmpty() ? 0 : new ArrayList<>(slots.keySet()).indexOf(leader);
              if (stationCurrent) {
                code = p.getMissionTrialManager().replaceStation(roster, compactLeader);
              } else {
                code = Retcode.SUCC;
                if (!roster.equals(lineup.getAvatars()) || compactLeader != lineup.getLeader()) {
                  var previous = new ArrayList<>(lineup.getAvatars());
                  int previousLeader = lineup.getLeader();
                  int oldLeader = lineup.isActiveSlot(previousLeader) ? previous.get(previousLeader) : 0;
                  lineup.getAvatars().clear();
                  lineup.getAvatars().addAll(roster);
                  lineup.setLeader(compactLeader);
                  try { lineup.save(); }
                  catch (RuntimeException failed) {
                    lineup.getAvatars().clear();
                    lineup.getAvatars().addAll(previous);
                    lineup.setLeader(previousLeader);
                    code = Retcode.SYSTEM_BUSY;
                  }
                  if (code == Retcode.SUCC) {
                    if (lineup == current) p.getScene().syncLineup();
                    lineup.refreshLineup();
                    if (lineup == current && !roster.isEmpty() && oldLeader != roster.get(compactLeader))
                      p.getMissionManager().onTeamLeaderChange(roster.get(compactLeader));
                  }
                }
              }
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
