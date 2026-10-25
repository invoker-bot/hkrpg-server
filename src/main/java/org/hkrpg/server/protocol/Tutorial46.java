package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.TutorialProgress;

/**
 * Verified 4.6 ordinary tutorial and default-guide wire fields. Subguide semantics stay
 * unsupported.
 */
public final class Tutorial46 {
  private record Request(int id, int type) {}

  static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    String name =
        switch (command) {
          case 1634 -> "GetTutorial";
          case 1605 -> "GetTutorialGuide";
          case 1660 -> "UnlockTutorial";
          case 1683 -> "FinishTutorial";
          case 1658 -> "UnlockTutorialGuide";
          case 1642 -> "FinishTutorialGuide";
          default -> null;
        };
    if (name == null || !session.getProtocol().experimental()) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var player = session.getPlayer();
    synchronized (player) {
      boolean guide = name.endsWith("Guide"), query = name.startsWith("Get");
      List<Integer> expired = List.of();
      var response = builder(session, name + "ScRsp");
      if (query) {
        set(response, "retcode", bytes.length == 0 ? 0 : Retcode.REQ_PARA_INVALID.getVal());
        if (bytes.length == 0) {
          if (!guide) expired = player.getGameplay().getTutorials().expireFinishedMissions(player);
          for (var entry : player.getGameplay().getTutorials().snapshot(guide).entrySet()) {
            response.addRepeatedField(
                response
                    .getDescriptorForType()
                    .findFieldByName(guide ? "tutorial_guide_list" : "tutorial_list"),
                record(session, guide, entry.getKey(), entry.getValue()));
          }
        }
      } else {
        boolean finish = name.startsWith("Finish");
        var request =
            read(
                bytes,
                guide ? (finish ? 1 : 13) : (finish ? 3 : 12),
                guide ? (finish ? 5 : 11) : (finish ? 9 : 0));
        var result =
            request == null || guide && request.type() != 0
                ? new TutorialProgress.Result(Retcode.REQ_PARA_INVALID, 0, List.of())
                : player.getGameplay().getTutorials().change(player, guide, request.id(), finish);
        set(response, "retcode", result.retcode().getVal());
        if (request != null && result.status() > 0)
          set(
              response,
              guide ? "tutorial_guide" : "tutorial",
              record(session, guide, request.id(), result.status()));
        if (finish && guide) {
          var rewards = ItemList.newInstance();
          result.rewards().forEach(item -> rewards.addItemList(item.toProto()));
          set(
              response,
              "reward",
              session
                  .getProtocol()
                  .clientSchema()
                  .parse(
                      "ItemList",
                      session.getProtocol().translate("ItemList", rewards.toByteArray(), true)));
        }
      }
      session.sendClientPacket(name + "ScRsp", response.build().toByteArray());
      sendFinished(session, expired);
      return true;
    }
  }

  /** Publish newly expired teaching during an active connection, after its state is saved. */
  public static void refresh(emu.lunarcore.game.player.Player player) {
    var session = player.getSession();
    if (session == null
        || !session.getProtocol().experimental()
        || session.getState() != SessionState.ACTIVE) return;
    synchronized (player) {
      sendFinished(session, player.getGameplay().getTutorials().expireFinishedMissions(player));
    }
  }

  private static void sendFinished(GameSession session, List<Integer> ids) {
    for (int id : ids) {
      var response = builder(session, "FinishTutorialScRsp");
      set(response, "retcode", 0);
      set(response, "tutorial", record(session, false, id, 2));
      session.sendClientPacket("FinishTutorialScRsp", response.build().toByteArray());
    }
  }

  private static DynamicMessage record(GameSession session, boolean guide, int id, int status) {
    var value = builder(session, guide ? "TutorialGuide" : "Tutorial");
    set(value, "id", id);
    var field = value.getDescriptorForType().findFieldByName("status");
    value.setField(field, field.getEnumType().findValueByNumber(status));
    return value.build();
  }

  private static Request read(byte[] bytes, int idField, int typeField) {
    if (bytes.length > 32) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      boolean idSeen = false, typeSeen = false;
      int id = 0, type = 0, tag;
      while ((tag = input.readTag()) != 0) {
        if (tag == idField * 8) {
          long raw = input.readUInt64();
          if (idSeen || raw <= 0 || raw > Integer.MAX_VALUE) return null;
          idSeen = true;
          id = (int) raw;
        } else if (typeField != 0 && tag == typeField * 8) {
          long raw = input.readUInt64();
          if (typeSeen || raw < 0 || raw > 2) return null;
          typeSeen = true;
          type = (int) raw;
        } else return null;
      }
      return idSeen && input.isAtEnd() ? new Request(id, type) : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private Tutorial46() {}
}
