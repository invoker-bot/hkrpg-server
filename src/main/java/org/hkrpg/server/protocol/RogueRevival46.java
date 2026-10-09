package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.RogueRevival;

/** Local CN client 617620 and codecs 219589/219610/219629; no Quickbuf edits. */
public final class RogueRevival46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("ReviveRogueAvatarCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var requested = decode(bytes);
    synchronized (session.getPlayer()) {
      var result =
          requested != null
              ? RogueRevival.revive(session.getPlayer(), requested.avatars(), requested.entity())
              : Retcode.REQ_PARA_INVALID;
      var response = builder(session, "ReviveRogueAvatarScRsp");
      set(response, "PLDKENJLKME", result.getVal());
      var info = info(session);
      if (info != null) set(response, "PHNAANFIFGB", info);
      session.sendClientPacket("ReviveRogueAvatarScRsp", response.build().toByteArray());
    }
    return true;
  }

  private record Request(List<Integer> avatars, int entity) {}

  /** Preserve wire multiplicity and reject values before the protobuf uint32 narrowing. */
  private static Request decode(byte[] bytes) {
    if (bytes == null || bytes.length > 16384) return null;
    var avatars = new ArrayList<Integer>();
    int entity = 0;
    try {
      var input = CodedInputStream.newInstance(bytes);
      while (!input.isAtEnd()) {
        int tag = positiveId(input);
        if (tag == 104 && entity == 0) entity = positiveId(input);
        else if (tag == 80) {
          if (avatars.size() >= 4) return null;
          avatars.add(positiveId(input));
        } else if (tag == 82) {
          int length = boundedInt(input, true);
          var packed = CodedInputStream.newInstance(input.readRawBytes(length));
          while (!packed.isAtEnd()) {
            if (avatars.size() >= 4) return null;
            avatars.add(positiveId(packed));
          }
        } else return null;
      }
      return entity > 0 && !avatars.isEmpty() && new HashSet<>(avatars).size() == avatars.size()
          ? new Request(List.copyOf(avatars), entity)
          : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private static int positiveId(CodedInputStream input) throws IOException {
    return boundedInt(input, false);
  }

  private static int boundedInt(CodedInputStream input, boolean allowZero) throws IOException {
    int value = 0;
    for (int shift = 0; shift <= 63; shift += 7) {
      int raw = input.readRawByte() & 255, part = raw & 127;
      // Check every encoded payload bit, including the tenth byte, before shifting.
      if (shift >= 31 && part != 0 || shift == 28 && part > 7)
        throw new IOException("Identifier exceeds the server integer range");
      if (shift < 31) value |= part << shift;
      if ((raw & 128) == 0) {
        if (value == 0 && !allowZero) throw new IOException("Zero identifier");
        return value;
      }
    }
    throw new IOException("Unterminated identifier");
  }

  static DynamicMessage info(GameSession session) {
    var run = session.getPlayer().getGameplay().getRogue();
    if (run.getRoster().isEmpty()
        || run.getRevivalCost() < 0
        || run.getRevivalHp() <= 0
        || run.getRevivalHp() > 10000) return null;
    var pile = builder(session, "PileItem");
    set(pile, "item_id", 31);
    try {
      set(
          pile,
          "item_num",
          org.hkrpg.server.gameplay.RogueMiracles.fragmentPlan(run, run.getRevivalCost())
              .getCost());
    } catch (ArithmeticException invalid) {
      return null;
    }
    var item = builder(session, "ItemCost");
    set(item, "pile_item", pile.build());
    var costs = builder(session, "ItemCostData");
    add(costs, "item_list", item.build());
    var info = builder(session, "KCMFAIBMOHB");
    set(info, "LNIEFKBMIPD", costs.build());
    return info.build();
  }

  static void sync(GameSession session) {
    var info = info(session);
    if (info == null) return;
    var notify = builder(session, "SyncRogueReviveInfoScNotify");
    set(notify, "MFEODNJNGDP", info);
    session.sendClientPacket("SyncRogueReviveInfoScNotify", notify.build().toByteArray());
  }

  public static void sync(emu.lunarcore.game.player.Player player) {
    if (player.isOnline()
        && player.getSession() != null
        && player.getSession().getProtocol().experimental()) sync(player.getSession());
  }

  private RogueRevival46() {}
}
