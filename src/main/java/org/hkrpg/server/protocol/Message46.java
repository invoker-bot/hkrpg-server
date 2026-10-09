package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** Phone message fields verified against the pinned CN 4.6 message calls. */
public final class Message46 {
  static void handle(GameSession s, String name, DynamicMessage req) {
    var p = s.getPlayer();
    var manager = new MessageManager(p);
    switch (name) {
      case "GetNpcStatusCsReq" -> {
        p.getMissionManager().refresh();
        var b = builder(s, "GetNpcStatusScRsp");
        var contacts =
            GameplayDefinitions.messageGroups.values().stream()
                .map(GameplayDefinitions.MessageGroup::MessageContactsID)
                .distinct()
                .sorted()
                .toList();
        for (int contact : contacts) {
          var groups =
              GameplayDefinitions.messageGroups.values().stream()
                  .filter(g -> g.MessageContactsID() == contact)
                  .toList();
          if (groups.stream()
              .noneMatch(
                  g ->
                      g.MessageSectionIDList().stream()
                          .anyMatch(p.getGameplay().getPhoneSections()::containsKey))) continue;
          var row = builder(s, "NpcStatus");
          set(row, "npc_id", contact);
          set(
              row,
              "is_finish",
              groups.stream()
                  .allMatch(
                      g ->
                          !g.MessageSectionIDList().isEmpty()
                              && g.MessageSectionIDList().stream().allMatch(manager::finished)));
          add(b, "npc_status_list", row.build());
        }
        s.sendClientPacket("GetNpcStatusScRsp", b.build().toByteArray());
      }
      case "GetNpcMessageGroupCsReq" -> {
        @SuppressWarnings("unchecked")
        var contacts = (List<Integer>) value(req, "BJIPBBFIBPD");
        var b = builder(s, "GetNpcMessageGroupScRsp");
        if (!only(req, "BJIPBBFIBPD")
            || contacts.size() > 100
            || contacts.stream().anyMatch(id -> id <= 0))
          set(b, "retcode", Retcode.REQ_PARA_INVALID.getVal());
        else {
          p.getMissionManager().refresh();
          for (var group :
              GameplayDefinitions.messageGroups.values().stream()
                  .filter(g -> contacts.contains(g.MessageContactsID()))
                  .sorted(Comparator.comparingInt(GameplayDefinitions.MessageGroup::ID))
                  .toList()) add(b, "message_group_list", groupData(s, group));
        }
        s.sendClientPacket("GetNpcMessageGroupScRsp", b.build().toByteArray());
      }
      case "GetMissionMessageCsReq" -> {
        p.getMissionManager().refresh();
        var b = builder(s, "GetMissionMessageScRsp");
        for (var state : new TreeMap<>(p.getGameplay().getPhoneSections()).values()) {
          var config = GameplayDefinitions.messageSections.get(state.getId());
          if (config != null && config.IsPerformMessage() && state.isFinished())
            add(b, "HNIPIODEBNN", state.getId());
        }
        // PFMHKKKAGCD has no verified semantic mapping; it never manufactures state.
        s.sendClientPacket("GetMissionMessageScRsp", b.build().toByteArray());
      }
      case "FinishItemIdCsReq" -> {
        int id = number(req, "item_id"), text = number(req, "text_id");
        var code =
            only(req, "item_id", "text_id") ? manager.choose(id, text) : Retcode.REQ_PARA_INVALID;
        send(
            s,
            "FinishItemIdScRsp",
            Map.of("retcode", code.getVal(), "item_id", id, "text_id", text));
      }
      case "FinishSectionIdCsReq" -> {
        int id = number(req, "section_id");
        var code = only(req, "section_id") ? manager.finishSection(id) : Retcode.REQ_PARA_INVALID;
        send(s, "FinishSectionIdScRsp", Map.of("retcode", code.getVal(), "section_id", id));
      }
      case "FinishPerformSectionIdCsReq" -> {
        var field = req.getDescriptorForType().findFieldByName("HBMLBLAJKKL");
        var data = (DynamicMessage) value(req, "HBMLBLAJKKL");
        boolean valid =
            req.hasField(field)
                && only(req, "HBMLBLAJKKL")
                && data.getUnknownFields().asMap().isEmpty()
                && only(data, "MGPGDLIJKLM", "DPPAFBAHEFH");
        int id = number(data, "MGPGDLIJKLM");
        @SuppressWarnings("unchecked")
        var items = (List<DynamicMessage>) value(data, "DPPAFBAHEFH");
        var choices = new ArrayList<Integer>();
        for (var item : items) {
          valid &=
              item.getUnknownFields().asMap().isEmpty()
                  && only(item, "item_id", "text_id")
                  && number(item, "text_id") == 0
                  && number(item, "item_id") > 0;
          choices.add(number(item, "item_id"));
        }
        var code = valid ? manager.finishPerformance(id, choices) : Retcode.REQ_PARA_INVALID;
        var b = builder(s, "FinishPerformSectionIdScRsp");
        set(b, "retcode", code.getVal());
        if (code == Retcode.SUCC) {
          var row = builder(s, "LGMDACIPFHL");
          set(row, "MGPGDLIJKLM", id);
          items.forEach(item -> add(row, "DPPAFBAHEFH", item));
          set(b, "MIINDKFMBBM", row.build());
        }
        s.sendClientPacket("FinishPerformSectionIdScRsp", b.build().toByteArray());
      }
      case "FinishMultiPerformSectionIdCsReq" -> {
        @SuppressWarnings("unchecked")
        var sections = (List<DynamicMessage>) value(req, "CGKBNIMCFKH");
        boolean valid = only(req, "CGKBNIMCFKH") && !sections.isEmpty() && sections.size() <= 32;
        var submissions = new LinkedHashMap<Integer, List<Integer>>();
        for (var data : sections) {
          int id = number(data, "MGPGDLIJKLM");
          valid &=
              id > 0
                  && data.getUnknownFields().asMap().isEmpty()
                  && only(data, "MGPGDLIJKLM", "DPPAFBAHEFH");
          @SuppressWarnings("unchecked")
          var items = (List<DynamicMessage>) value(data, "DPPAFBAHEFH");
          var choices = new ArrayList<Integer>();
          valid &= items.size() <= 1024;
          for (var item : items) {
            valid &=
                item.getUnknownFields().asMap().isEmpty()
                    && only(item, "item_id", "text_id")
                    && number(item, "text_id") == 0
                    && number(item, "item_id") > 0;
            choices.add(number(item, "item_id"));
          }
          if (submissions.putIfAbsent(id, choices) != null) valid = false;
        }
        var code = valid ? manager.finishPerformances(submissions) : Retcode.REQ_PARA_INVALID;
        var b = builder(s, "FinishMultiPerformSectionIdScRsp");
        set(b, "PLDKENJLKME", code.getVal());
        if (code == Retcode.SUCC)
          for (var data : sections) {
            var row = builder(s, "LGMDACIPFHL");
            set(row, "MGPGDLIJKLM", number(data, "MGPGDLIJKLM"));
            @SuppressWarnings("unchecked")
            var items = (List<DynamicMessage>) value(data, "DPPAFBAHEFH");
            items.forEach(item -> add(row, "DPPAFBAHEFH", item));
            add(b, "JCALNIODLLN", row.build());
          }
        s.sendClientPacket("FinishMultiPerformSectionIdScRsp", b.build().toByteArray());
      }
      default -> throw new ProtocolException("Unsupported message request");
    }
  }

  private static int status(GameSession s, GameplayDefinitions.MessageGroup g) {
    var states =
        g.MessageSectionIDList().stream()
            .map(s.getPlayer().getGameplay().getPhoneSections()::get)
            .filter(Objects::nonNull)
            .toList();
    return states.isEmpty()
        ? 0
        : states.stream().allMatch(GameplayState.PhoneSection::isFinished) ? 2 : 1;
  }

  private static DynamicMessage groupData(GameSession s, GameplayDefinitions.MessageGroup g) {
    var b = builder(s, "MessageGroup");
    set(b, "id", g.ID());
    enumValue(b, "status", status(s, g));
    GameplayState.PhoneSection current = null;
    for (int id : g.MessageSectionIDList()) {
      var state = s.getPlayer().getGameplay().getPhoneSections().get(id);
      if (state == null) continue;
      var row = builder(s, "MessageSection");
      set(row, "id", id);
      enumValue(row, "status", state.isFinished() ? 2 : 1);
      for (int item : state.getHistory()) {
        var entry = builder(s, "MessageItem");
        set(entry, "item_id", item);
        add(row, "item_list", entry.build());
      }
      state.getOffered().forEach(item -> add(row, "message_item_list", item));
      add(b, "message_section_list", row.build());
      if (current == null
          || !state.isFinished()
          || current.isFinished() && state.getRefreshed() >= current.getRefreshed())
        current = state;
    }
    if (current != null) {
      set(b, "message_section_id", current.getId());
      set(b, "refresh_time", current.getRefreshed());
    }
    return b.build();
  }

  private static void enumValue(DynamicMessage.Builder b, String name, int value) {
    set(
        b,
        name,
        b.getDescriptorForType().findFieldByName(name).getEnumType().findValueByNumber(value));
  }

  public static void sync(
      Player p, GameplayDefinitions.MessageGroup g, GameplayState.PhoneSection state) {
    if (g == null
        || !p.isOnline()
        || p.getSession() == null
        || !p.getSession().getProtocol().experimental()) return;
    var s = p.getSession();
    var b = builder(s, "PlayerSyncScNotify");
    var sync = builder(s, "SyncStatus");
    var group = builder(s, "GroupStatus");
    set(group, "group_id", g.ID());
    set(group, "refresh_time", state.getRefreshed());
    enumValue(group, "group_status", status(s, g));
    add(sync, "message_group_status", group.build());
    var section = builder(s, "SectionStatus");
    set(section, "section_id", state.getId());
    enumValue(section, "section_status", state.isFinished() ? 2 : 1);
    add(sync, "section_status", section.build());
    set(b, "sync_status", sync.build());
    s.sendClientPacket("PlayerSyncScNotify", b.build().toByteArray());
  }

  private Message46() {}
}
