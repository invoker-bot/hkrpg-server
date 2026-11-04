package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.RogueEventNpc;
import org.hkrpg.server.gameplay.RogueDialogueRewards;

/** Native NPC/option reads and sender are recorded in client46-rogue-dialogue-evidence.json. */
public final class RogueDialogue46 {
  public static List<RogueEventNpc> active(Scene scene) {
    if (scene == null) return List.of();
    return scene.getEntities().values().stream()
        .filter(RogueEventNpc.class::isInstance)
        .map(RogueEventNpc.class::cast)
        .filter(RogueEventNpc::valid)
        .sorted(Comparator.comparingInt(n -> n.binding().getUniqueId()))
        .toList();
  }

  public static void persist(Scene scene) {
    var actors = active(scene);
    for (var actor : actors)
      if (RogueDialogueRewards.bind(scene.getPlayer(), actor.binding())) actor.markChanged();
    if (actors.stream().anyMatch(RogueEventNpc::needsPersistence)) {
      scene.getPlayer().save();
      actors.forEach(RogueEventNpc::markPersisted);
    }
  }

  public static DynamicMessage npcInfo(ProtocolSchema schema, RogueEventNpc actor) {
    var info = DynamicMessage.newBuilder(schema.message("NpcRogueGameInfo"));
    set(info, "AIAFEKGBFIF", actor.binding().getUniqueId());
    set(info, "OEANPIHCHHF", actor.binding().getDialogue());
    set(info, "MJBKNMJHOGP", actor.binding().getProgress());
    return info.build();
  }

  private static DynamicMessage dialogue(GameSession session, RogueEventNpc actor) {
    var row = builder(session, "HFEOGNAMHCG");
    set(row, "AIAFEKGBFIF", actor.binding().getUniqueId());
    var owner = builder(session, "LEHBIBDGEHG");
    var basic = builder(session, "HEOBLDJAOPE");
    set(basic, "OEANPIHCHHF", actor.binding().getDialogue());
    set(basic, "MJBKNMJHOGP", actor.binding().getProgress());
    set(owner, "MFAEGBJBJBE", basic.build());
    set(row, "owner", owner.build());
    for (var source : actor.options()) {
      var option = builder(session, "KDODFHMDAJI");
      set(option, "HAFOKMHCGFM", source.id());
      set(option, "is_valid", RogueDialogueRewards.selectable(session.getPlayer(), actor, source.id()));
      set(option, "is_selected", RogueDialogueRewards.selected(actor, source.id()));
      add(row, "KFNMIEMBJGD", option.build());
    }
    return row.build();
  }

  /** The native event UI filters options against this cache; a scene NPC alone is insufficient. */
  public static void sync(GameSession session) {
    if (!session.getProtocol().experimental() || session.getState() != SessionState.ACTIVE) return;
    synchronized (session.getPlayer()) {
      var scene = session.getPlayer().getScene();
      persist(scene);
      var notify = builder(session, "SyncRogueCommonDialogueDataScNotify");
      for (var actor : active(scene)) add(notify, "dialogue_list", dialogue(session, actor));
      // This native notification replaces the whole cache, including an empty room on departure.
      session.sendClientPacket("SyncRogueCommonDialogueDataScNotify", notify.build().toByteArray());
    }
  }

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    boolean query =
        command == session.getProtocol().clientCommand("GetRogueCommonDialogueDataCsReq");
    boolean select =
        command == session.getProtocol().clientCommand("SelectRogueCommonDialogueOptionCsReq");
    if (!query && !select) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    String name =
        query ? "GetRogueCommonDialogueDataCsReq" : "SelectRogueCommonDialogueOptionCsReq";
    if (select && !NativeUnsignedActionWire.valid(session.getProtocol().clientSchema().message(name), bytes, 0)) {
      var response = builder(session, "SelectRogueCommonDialogueOptionScRsp");
      set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
      session.sendClientPacket("SelectRogueCommonDialogueOptionScRsp", response.build().toByteArray());
      return true;
    }
    var request = session.getProtocol().clientSchema().parse(name, bytes);
    if (!request.getUnknownFields().asMap().isEmpty()
        || (query
            ? !request.getAllFields().isEmpty()
            : !only(request, "AIAFEKGBFIF", "HAFOKMHCGFM")))
      throw new ProtocolException("Unknown rogue dialogue fields");
    synchronized (session.getPlayer()) {
      var scene = session.getPlayer().getScene();
      var actors = active(scene);
      if (query) {
        persist(scene);
        var response = builder(session, "GetRogueCommonDialogueDataScRsp");
        for (var actor : actors) add(response, "dialogue_list", dialogue(session, actor));
        session.sendClientPacket("GetRogueCommonDialogueDataScRsp", response.build().toByteArray());
      } else {
        int unique = number(request, "AIAFEKGBFIF"), option = number(request, "HAFOKMHCGFM");
        var actor =
            actors.stream()
                .filter(
                    n ->
                        n.binding().getUniqueId() == unique
                            && n.options().stream().anyMatch(o -> o.id() == option))
                .findFirst().orElse(null);
        if (actor != null) persist(scene);
        var result = actor == null ? Retcode.REQ_PARA_INVALID
            : RogueDialogueRewards.select(session.getPlayer(), actor, option);
        var response = builder(session, "SelectRogueCommonDialogueOptionScRsp");
        set(
            response,
            "retcode",
            result.getVal());
        set(response, "AIAFEKGBFIF", unique);
        set(response, "HAFOKMHCGFM", option);
        if (result == Retcode.SUCC) set(response, "ONOLNGOHEMF", dialogue(session, actor));
        session.sendClientPacket(
            "SelectRogueCommonDialogueOptionScRsp", response.build().toByteArray());
      }
    }
    return true;
  }

  private RogueDialogue46() {}
}
