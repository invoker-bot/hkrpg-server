package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** Native 2534/2525/2502; verified 10 -> ExpeditionInfo field 15, hourly data and rewards. */
public final class Expedition46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || !Set.of(2534, 2525, 2502).contains(command))
      return false;
    if (session.getState() != SessionState.ACTIVE || session.getPlayer() == null) return true;
    var player = session.getPlayer();
    synchronized (player) {
      var manager = new ExpeditionManager(player);
      if (command == 2534) {
        var snapshot = manager.snapshot();
        var reply = Gameplay46.builder(session, "GetExpeditionDataScRsp");
        Gameplay46.set(
            reply,
            "retcode",
            (bytes != null && bytes.length == 0 ? snapshot.retcode() : Retcode.REQ_PARA_INVALID)
                .getVal());
        add(reply, "CAALNNFJIAH", snapshot.unlocked());
        addAssignments(session, reply, "expedition_info", snapshot.active());
        Gameplay46.set(reply, "total_expedition_count", snapshot.teams());
        Gameplay46.set(reply, "BJMGDBCHEAN", snapshot.rewardTime());
        session.sendClientPacket("GetExpeditionDataScRsp", reply.build().toByteArray());
      } else {
        var ids = command == 2525 ? decode(bytes) : null;
        var result =
            command == 2525 && ids != null
                ? manager.set(ids)
                : command == 2502 && bytes != null && bytes.length == 0 ? manager.take() : null;
        String name =
            command == 2525 ? "AcceptMultipleExpeditionScRsp" : "TakeMultipleExpeditionRewardScRsp";
        var reply = Gameplay46.builder(session, name);
        Gameplay46.set(
            reply,
            "retcode",
            (result == null ? Retcode.REQ_PARA_INVALID : result.retcode()).getVal());
        var snapshot = manager.snapshot();
        Gameplay46.set(
            reply, "BJMGDBCHEAN", result == null ? snapshot.rewardTime() : result.rewardTime());
        if (command == 2525)
          addAssignments(
              session,
              reply,
              "accept_multi_expedition",
              result == null ? snapshot.active() : result.active());
        else if (result != null) add(reply, "NPCMKBNJABA", result.taken());
        if (result != null && result.retcode() == Retcode.SUCC)
          for (var rewards : result.rewards()) {
            var list = ItemList.newInstance();
            rewards.toItemList().forEach(i -> list.addItemList(i.toProto()));
            reply.addRepeatedField(
                reply.getDescriptorForType().findFieldByName("DJOBNPIAEJP"),
                session
                    .getProtocol()
                    .clientSchema()
                    .parse(
                        "ItemList",
                        session.getProtocol().translate("ItemList", list.toByteArray(), true)));
          }
        session.sendClientPacket(name, reply.build().toByteArray());
      }
    }
    return true;
  }

  private static void add(DynamicMessage.Builder reply, String field, List<Integer> values) {
    values.forEach(
        v -> reply.addRepeatedField(reply.getDescriptorForType().findFieldByName(field), v));
  }

  private static void addAssignments(
      GameSession session, DynamicMessage.Builder reply, String field, List<Integer> ids) {
    for (int id : ids) {
      var row = Gameplay46.builder(session, "ExpeditionInfo");
      Gameplay46.set(row, "id", id);
      reply.addRepeatedField(reply.getDescriptorForType().findFieldByName(field), row.build());
    }
  }

  private static List<Integer> decode(byte[] bytes) {
    if (bytes == null || bytes.length > 512) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      var ids = new ArrayList<Integer>();
      int tag;
      while ((tag = input.readTag()) != 0) {
        if (tag != 82 || ids.size() >= 4) return null;
        var row = CodedInputStream.newInstance(input.readByteArray());
        if (row.readTag() != 120) return null;
        long id = row.readUInt64();
        if (id <= 0 || id > Integer.MAX_VALUE || !row.isAtEnd()) return null;
        ids.add((int) id);
      }
      return ids;
    } catch (IOException invalid) {
      return null;
    }
  }

  private Expedition46() {}
}
