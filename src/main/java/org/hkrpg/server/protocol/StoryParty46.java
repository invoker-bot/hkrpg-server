package org.hkrpg.server.protocol;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.StoryLineState;

/** Native story party identity, absent from the frozen lite schema. */
public final class StoryParty46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 704) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var req = session.getProtocol().clientSchema().parse("ReplaceLineupCsReq", bytes);
    int story = Gameplay46.number(req, "game_story_line_id");
    int extra =
        ((EnumValueDescriptor)
                req.getField(req.getDescriptorForType().findFieldByName("extra_lineup_type")))
            .getNumber();
    if (story == 0
        && (extra != 13 || session.getPlayer().getGameplay().getStoryLines().getCurrentId() == 0))
      return false;
    var player = session.getPlayer();
    synchronized (player) {
      Retcode code = Retcode.REQ_PARA_INVALID;
      var state = player.getGameplay().getStoryLines();
      @SuppressWarnings("unchecked")
      var rows = (List<DynamicMessage>) Gameplay46.value(req, "lineup_slot_list");
      int leader = Gameplay46.number(req, "leader_slot");
      boolean valid =
          Parkour46.wireValid(req.getDescriptorForType(), bytes, 0)
              && req.getUnknownFields().asMap().isEmpty()
              && Gameplay46.only(
                  req,
                  "index",
                  "extra_lineup_type",
                  "lineup_slot_list",
                  "leader_slot",
                  "plane_id",
                  "game_story_line_id")
              && extra == 0
              && story > 0
              && story == state.getCurrentId()
              && Gameplay46.number(req, "index") == 0
              && (Gameplay46.number(req, "plane_id") == 0
                  || Gameplay46.number(req, "plane_id") == player.getPlaneId())
              && !rows.isEmpty()
              && rows.size() <= 4
              && leader >= 0
              && leader < rows.size();
      var slots = new TreeMap<Integer, Integer>();
      for (var row : rows) {
        int slot = Gameplay46.number(row, "slot"), id = Gameplay46.number(row, "id");
        var avatarType = (EnumValueDescriptor) Gameplay46.value(row, "avatar_type");
        valid &=
            row.getUnknownFields().asMap().isEmpty()
                && Gameplay46.only(row, "id", "slot", "avatar_type")
                && "AvatarType_AvatarTrialType".equals(avatarType.getName())
                && id > 0
                && slot >= 0
                && slot < rows.size()
                && slots.putIfAbsent(slot, id) == null;
      }
      if (valid && slots.size() == rows.size()) {
        if (player.isInBattle() || state.isTransitionPending()) code = Retcode.SYSTEM_BUSY;
        else {
          var lineup = player.getStoryLineManager().lineup();
          if (lineup != null && lineup.tryReplace(new ArrayList<>(slots.values()), leader))
            code = Retcode.SUCC;
        }
      }
      // The CN Replace response uses fields7/9, as independently checked for RogueLineup46.
      var buffer = new java.io.ByteArrayOutputStream();
      var out = CodedOutputStream.newInstance(buffer);
      if (Gameplay46.number(req, "index") != 0) out.writeUInt32(7, Gameplay46.number(req, "index"));
      if (code != Retcode.SUCC) out.writeUInt32(9, code.getVal());
      out.flush();
      session.sendClientPacket(719, buffer.toByteArray());
    }
    return true;
  }

  public static byte[] enrich(ProtocolSchema schema, String name, byte[] bytes, Player player) {
    var type = schema.find(name);
    if (player == null || type == null || !containsParty(type, new HashSet<>())) return bytes;
    synchronized (player) {
      var state = player.getGameplay().getStoryLines();
      var run = player.getStoryLineManager().current();
      if (state.getCurrentId() == 0 || run == null || run.getId() != state.getCurrentId())
        return bytes;
      try {
        var original = schema.parse(name, bytes);
        var result = transform(original, run, 0);
        return result == original ? bytes : result.toByteArray();
      } catch (IOException invalid) {
        throw new ProtocolException("Malformed outgoing native story party: " + name);
      }
    }
  }

  private static boolean containsParty(Descriptor type, Set<Descriptor> visiting) {
    if ("LineupInfo".equals(type.getName())) return true;
    if (!visiting.add(type)) return false;
    boolean found =
        type.getFields().stream()
            .filter(f -> f.getJavaType() == FieldDescriptor.JavaType.MESSAGE)
            .anyMatch(f -> containsParty(f.getMessageType(), visiting));
    visiting.remove(type);
    return found;
  }

  private static DynamicMessage transform(
      DynamicMessage message, StoryLineState.Run run, int depth) {
    if (depth > 32)
      throw new ProtocolException("Outgoing story party nesting exceeds supported depth");
    var type = message.getDescriptorForType();
    if ("LineupInfo".equals(type.getName())) {
      var extra = type.findFieldByName("extra_lineup_type");
      if (((EnumValueDescriptor) message.getField(extra)).getNumber() != 13
          || Gameplay46.number(message, "index") != 0) return message;
      var avatars = (List<?>) message.getField(type.findFieldByName("avatar_list"));
      if (avatars.size() != run.getRoster().size()) return message;
      for (int slot = 0; slot < avatars.size(); slot++) {
        var avatar = (DynamicMessage) avatars.get(slot);
        if (Gameplay46.number(avatar, "id") != run.getRoster().get(slot)
            || Gameplay46.number(avatar, "slot") != slot) return message;
      }
      int story = Gameplay46.number(message, "game_story_line_id");
      if (story != 0 && story != run.getId()) return message;
      // CN PKPICFPDHEO: +0x48=field15, +0x4c=field9. _GetTeamData
      // routes nonzero extra to GetExtraTeam before checking GetStoryLineTeam.
      var result =
          message.toBuilder()
              .setField(extra, extra.getEnumType().findValueByNumber(0))
              .setField(type.findFieldByName("game_story_line_id"), run.getId());
      var roster = type.findFieldByName("story_line_avatar_id_list");
      var available = type.findFieldByName("available_trial_avatar_i_ds");
      result.clearField(roster).clearField(available);
      run.getRoster().forEach(id -> result.addRepeatedField(roster, id));
      // Field8/+0x18 is copied into TeamData.AvailableTrialAvatarIDs/+0x48.
      run.getAvailableTrials().stream()
          .sorted()
          .forEach(id -> result.addRepeatedField(available, id));
      return result.build();
    }
    DynamicMessage.Builder changed = null;
    for (var entry : message.getAllFields().entrySet()) {
      var field = entry.getKey();
      if (field.getJavaType() != FieldDescriptor.JavaType.MESSAGE
          || !containsParty(field.getMessageType(), new HashSet<>())) continue;
      if (field.isRepeated()) {
        var rows = (List<?>) entry.getValue();
        for (int i = 0; i < rows.size(); i++) {
          var row = (DynamicMessage) rows.get(i);
          var result = transform(row, run, depth + 1);
          if (result != row) {
            if (changed == null) changed = message.toBuilder();
            changed.setRepeatedField(field, i, result);
          }
        }
      } else {
        var row = (DynamicMessage) entry.getValue();
        var result = transform(row, run, depth + 1);
        if (result != row) {
          if (changed == null) changed = message.toBuilder();
          changed.setField(field, result);
        }
      }
    }
    return changed == null ? message : changed.build();
  }

  private StoryParty46() {}
}
