package org.hkrpg.server.protocol;

import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.HashSet;
import org.hkrpg.server.gameplay.CavalryBeastQuests;

/** Native 915 quest/group/instance fields report the original loaded cavalry NPC observation. */
final class CavalryActivity46 {
  private static final int QUEST = 6029860;

  static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    var protocol = session.getProtocol();
    if (!protocol.experimental() || command != protocol.clientCommand("FinishQuestCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    DynamicMessage request;
    try {
      request = protocol.clientSchema().parse("FinishQuestCsReq", bytes);
    } catch (InvalidProtocolBufferException invalid) {
      send(session, Retcode.REQ_PARA_INVALID);
      return true;
    }
    int id = Gameplay46.number(request, "quest_id");
    if (id != QUEST && !CavalryBeastQuests.belongs(GameData.getQuestExcelMap().get(id)) && !owns(bytes))
      return false;
    var code = wire(bytes)
        ? CavalryBeastQuests.finish(session.getPlayer(), id,
            Gameplay46.number(request, "group_id"), Gameplay46.number(request, "prop_id"))
        : Retcode.REQ_PARA_INVALID;
    send(session, code);
    return true;
  }

  private static void send(GameSession session, Retcode code) {
    var response = Gameplay46.builder(session, "FinishQuestScRsp");
    // Frozen 4.6 BICDPPOGHND field 11, matched by ProtocolBridge and the original writer.
    Gameplay46.set(response, "PLDKENJLKME", code.getVal());
    session.sendClientPacket("FinishQuestScRsp", response.build().toByteArray());
  }

  // An earlier owned quest cannot be redirected into legacy handling by a duplicate final field.
  private static boolean owns(byte[] bytes) {
    var input = CodedInputStream.newInstance(bytes);
    try {
      for (int fields = 0; fields < 32 && !input.isAtEnd(); fields++) {
        int tag = input.readTag();
        if (tag == 56) {
          long id = varint(input);
          if (id == QUEST || id > 0 && id <= Integer.MAX_VALUE
              && CavalryBeastQuests.belongs(GameData.getQuestExcelMap().get((int) id))) return true;
        } else if (!input.skipField(tag)) return false;
      }
    } catch (IOException invalid) {
      return false;
    }
    return false;
  }

  private static boolean wire(byte[] bytes) {
    if (bytes.length > 64) return false;
    var input = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    try {
      while (!input.isAtEnd()) {
        long tag = varint(input);
        if (tag != 56 && tag != 64 && tag != 88 || !seen.add((int) tag)) return false;
        if (Long.compareUnsigned(varint(input), 0xffffffffL) > 0) return false;
      }
      return seen.contains(56) && seen.contains(64) && seen.contains(88);
    } catch (IOException invalid) {
      return false;
    }
  }

  private static long varint(CodedInputStream input) throws IOException {
    long value = 0;
    for (int i = 0; i < 10; i++) {
      int next = input.readRawByte() & 255;
      if (i == 9 && next > 1) throw new InvalidProtocolBufferException("Overflowing cavalry quest varint");
      value |= (long) (next & 127) << (i * 7);
      if ((next & 128) == 0) return value;
    }
    throw new InvalidProtocolBufferException("Malformed cavalry quest varint");
  }

  private CavalryActivity46() {}
}
