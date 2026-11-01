package org.hkrpg.server.protocol;

import com.google.protobuf.*;
import emu.lunarcore.proto.PVEBattleResultCsReqOuterClass.PVEBattleResultCsReq;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.server.packet.send.PacketPVEBattleResultScRsp;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.MissionBattleKills;

/** Keeps native monster deaths before the legacy schema drops monster_list. */
final class BattleResult46 {
  static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    var protocol = session.getProtocol();
    if (!protocol.experimental() || command != protocol.clientCommand("PVEBattleResultCsReq"))
      return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var player = session.getPlayer();
    synchronized (player) {
      var type = protocol.clientSchema().message("PVEBattleResultCsReq");
      if (!identityValid(type, bytes, 0)) {
        SceneActionDiagnostics46.battleRejected(session, "wire-shape", 0, 0, List.of());
        session.send(new PacketPVEBattleResultScRsp());
        return true;
      }
      var nativeRequest = protocol.clientSchema().parse("PVEBattleResultCsReq", bytes);
      var monsters = monsters((DynamicMessage) Gameplay46.value(nativeRequest, "stt"));
      var energies = energies((DynamicMessage) Gameplay46.value(nativeRequest, "stt"));
      if (monsters == null || energies == null) {
        SceneActionDiagnostics46.battleRejected(session, "monster-or-energy", 0, 0, List.of());
        session.send(new PacketPVEBattleResultScRsp());
        return true;
      }
      var core = PVEBattleResultCsReq.parseFrom(protocol.inbound(command, bytes).body());
      var battle =
          session
              .getServer()
              .getBattleService()
              .finishBattle(
                  player,
                  core.getBattleId(),
                  core.getStageId(),
                  core.getEndStatus(),
                  core.getStt(),
                  monsters,
                  energies);
      if (battle == null) {
        var ids = new ArrayList<Integer>();
        for (var avatar : core.getStt().getBattleAvatarList()) {
          if (ids.size() == 8) break;
          ids.add(avatar.getId());
        }
        String reason = session.getServer().getBattleService().rejectionReason(
            player, core.getBattleId(), core.getStageId(), core.getEndStatus(), core.getStt(), monsters, energies);
        if (reason == null) reason = "post-validation";
        if (reason.startsWith("avatar-health") || reason.startsWith("avatar-energy")) {
          var readings = new ArrayList<String>();
          for (var avatar : core.getStt().getBattleAvatarList()) {
            if (readings.size() == 8) break;
            var value = avatar.getAvatarStatus();
            readings.add(avatar.getId() + ":" + value.getLeftHp() + "/" + value.getMaxHp()
                + ":" + value.getLeftSp() + "/" + value.getMaxSp());
          }
          reason += " vitals=" + readings;
        } else if (reason.equals("battle-event-energy")) {
          var active = player.getBattle();
          reason += " events=" + energies.stream().limit(8).toList()
              + " allowed=" + (active == null ? List.of() : active.getBattleEvents());
        }
        SceneActionDiagnostics46.battleRejected(session, reason, core.getBattleId(), core.getStageId(), ids);
      }
      session.send(
          battle == null
              ? new PacketPVEBattleResultScRsp()
              : new PacketPVEBattleResultScRsp(core, battle));
    }
    return true;
  }

  static List<MissionBattleKills.Monster> monsters(DynamicMessage statistics) {
    if (!statistics.getUnknownFields().asMap().isEmpty()) return null;
    var rows = (List<?>) Gameplay46.value(statistics, "monster_list");
    if (rows.size() > 1024) return null;
    var result = new ArrayList<MissionBattleKills.Monster>();
    for (var value : rows) {
      var row = (DynamicMessage) value;
      int death =
          ((Descriptors.EnumValueDescriptor) Gameplay46.value(row, "death_source")).getNumber();
      if (!row.getUnknownFields().asMap().isEmpty() || death < 0 || death > 3) return null;
      result.add(
          new MissionBattleKills.Monster(
              Gameplay46.number(row, "entity_id"),
              Gameplay46.number(row, "monster_id"),
              Gameplay46.number(row, "stage_id"),
              death));
    }
    return List.copyOf(result);
  }

  /**
   * Bound identity fields use raw varints; unrelated named telemetry follows the existing bridge.
   */
  static boolean identityValid(Descriptors.Descriptor type, byte[] bytes, int depth) {
    if (depth > 4 || bytes.length > 1048576) return false;
    var input = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    var customKeys = new HashSet<String>();
    var targetCategories = new HashSet<Long>();
    int rows = 0;
    try {
      int tag;
      while ((tag = input.readTag()) != 0) {
        var field = type.findFieldByNumber(tag >>> 3);
        if (field == null || !field.isRepeated() && !seen.add(field.getNumber())) return false;
        if (field.getName().equals("custom_values")) {
          if ((tag & 7) != 2 || customKeys.size() >= 128) return false;
          var key = customKey(input.readByteArray());
          if (key == null || !customKeys.add(key)) return false;
        } else if (field.getName().equals("battle_target_info")) {
          if ((tag & 7) != 2 || targetCategories.size() >= BattleTargetReports46.MAX_CATEGORIES)
            return false;
          var key = BattleTargetReports46.category(input.readByteArray());
          if (key == null || !targetCategories.add(key)) return false;
        } else if (Set.of("stt", "monster_list", "BDCHEJKPOAF", "status", "sp_bar")
            .contains(field.getName())) {
          if ((tag & 7) != 2
              || ++rows > 1024
              || !identityValid(field.getMessageType(), input.readByteArray(), depth + 1))
            return false;
        } else if (Set.of(
                "battle_id",
                "stage_id",
                "entity_id",
                "monster_id",
                "death_source",
                "end_status",
                "round_cnt",
                "battle_event_id",
                "cur_sp",
                "max_sp")
            .contains(field.getName())) {
          if ((tag & 7) != 0) return false;
          long value = input.readUInt64();
          if (Long.compareUnsigned(value, Integer.MAX_VALUE) > 0
              || field.getName().equals("death_source") && value > 3) return false;
          if (field.getName().equals("end_status")
              && field.getEnumType().findValueByNumber((int) value) == null) return false;
        } else if (!input.skipField(tag)) return false;
      }
      return true;
    } catch (IOException ex) {
      return false;
    }
  }

  private static String customKey(byte[] bytes) throws IOException {
    if (bytes.length > 1024) return null;
    var input = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    String key = null;
    int tag;
    while ((tag = input.readTag()) != 0) {
      if (!seen.add(tag >>> 3)) return null;
      if (tag == 10) key = input.readStringRequireUtf8();
      else if (tag == 21) {
        if (!Float.isFinite(input.readFloat())) return null;
      } else return null;
    }
    return org.hkrpg.server.gameplay.MissionBattleCustomValues.validKey(key) ? key : null;
  }

  static List<org.hkrpg.server.gameplay.RogueResonance.Energy> energies(DynamicMessage statistics) {
    var rows = (List<?>) Gameplay46.value(statistics, "BDCHEJKPOAF");
    if (rows.size() > 64) return null;
    var out = new ArrayList<org.hkrpg.server.gameplay.RogueResonance.Energy>();
    var seen = new HashSet<Integer>();
    for (var value : rows) {
      var row = (DynamicMessage) value;
      int id = Gameplay46.number(row, "battle_event_id");
      if (id <= 0 || !seen.add(id) || !row.getUnknownFields().asMap().isEmpty()) return null;
      for (var field : row.getDescriptorForType().getFields())
        if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.DOUBLE
            && !Double.isFinite((double) row.getField(field))) return null;
      var status = (DynamicMessage) Gameplay46.value(row, "status");
      if (!status.getUnknownFields().asMap().isEmpty()) return null;
      if (!status.hasField(status.getDescriptorForType().findFieldByName("sp_bar"))) {
        out.add(new org.hkrpg.server.gameplay.RogueResonance.Energy(id, -1, -1));
        continue;
      }
      var bar = (DynamicMessage) Gameplay46.value(status, "sp_bar");
      if (!bar.getUnknownFields().asMap().isEmpty()) return null;
      var energy =
          new org.hkrpg.server.gameplay.RogueResonance.Energy(
              id, Gameplay46.number(bar, "cur_sp"), Gameplay46.number(bar, "max_sp"));
      if (!energy.valid()) return null;
      out.add(energy);
    }
    return List.copyOf(out);
  }

  private BattleResult46() {}
}
