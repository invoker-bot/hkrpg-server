package org.hkrpg.server.protocol;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.*;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.PropTimelines;

/** Matched native 1483/1415 action; UUID and close-map are opaque, never reward/mission IDs. */
final class PropTimeline46 {
  static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    var protocol = session.getProtocol();
    if (!protocol.experimental()
        || command != protocol.clientCommand("ChangePropTimelineInfoCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var type = protocol.clientSchema().message("ChangePropTimelineInfoCsReq");
    Retcode code = Retcode.REQ_PARA_INVALID;
    int entity = 0;
    if (wire(type, bytes, 0)) {
      var request = protocol.clientSchema().parse("ChangePropTimelineInfoCsReq", bytes);
      entity = Gameplay46.number(request, "prop_entity_id");
      var infoField = type.findFieldByName("timeline_info");
      if (request.hasField(infoField)) {
        var info = (DynamicMessage) request.getField(infoField);
        var value = (ByteString) Gameplay46.value(info, "timeline_byte_value");
        boolean ascii = value.size() > 0 && value.size() <= 128;
        for (byte b : value.toByteArray()) if (b < 0 || b < 32 || b == 127) ascii = false;
        if (ascii && !Boolean.TRUE.equals(Gameplay46.value(info, "timeline_bool_value")))
          code =
              PropTimelines.report(
                  session.getPlayer(),
                  entity,
                  (Long) Gameplay46.value(request, "interact_id"),
                  value.toString(StandardCharsets.US_ASCII));
      }
    }
    var response = Gameplay46.builder(session, "ChangePropTimelineInfoScRsp");
    Gameplay46.set(response, "retcode", code.getVal());
    Gameplay46.set(response, "prop_entity_id", entity);
    session.sendClientPacket("ChangePropTimelineInfoScRsp", response.build().toByteArray());
    return true;
  }

  private static boolean wire(Descriptor type, byte[] bytes, int depth) {
    if (depth > 1 || bytes.length > 512) return false;
    var input = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    try {
      while (!input.isAtEnd()) {
        long rawTag = varint(input);
        if (Long.compareUnsigned(rawTag, 0xffffffffL) > 0) return false;
        int tag = (int) rawTag;
        var field = type.findFieldByNumber(tag >>> 3);
        int kind = tag & 7;
        if (field == null || !seen.add(field.getNumber())) return false;
        switch (field.getType()) {
          case UINT64 -> {
            if (kind != 0) return false;
            varint(input);
          }
          case UINT32 -> {
            if (kind != 0 || Long.compareUnsigned(varint(input), 0xffffffffL) > 0) return false;
          }
          case BOOL -> {
            if (kind != 0 || Long.compareUnsigned(varint(input), 1) > 0) return false;
          }
          case BYTES -> {
            if (kind != 2 || bytes(input, 128) == null) return false;
          }
          case MESSAGE -> {
            if (kind != 2) return false;
            var nested = bytes(input, 512);
            if (nested == null || !wire(field.getMessageType(), nested, depth + 1)) return false;
          }
          default -> {
            return false;
          }
        }
      }
      return true;
    } catch (IOException invalid) {
      return false;
    }
  }

  private static long varint(CodedInputStream input) throws IOException {
    long value = 0;
    for (int index = 0; index < 10; index++) {
      int next = input.readRawByte() & 255;
      if (index == 9 && next > 1)
        throw new InvalidProtocolBufferException("Overflowing native varint");
      value |= (long) (next & 127) << (index * 7);
      if ((next & 128) == 0) return value;
    }
    throw new InvalidProtocolBufferException("Malformed native varint");
  }

  private static byte[] bytes(CodedInputStream input, int limit) throws IOException {
    long length = varint(input);
    return Long.compareUnsigned(length, limit) > 0 ? null : input.readRawBytes((int) length);
  }

  private PropTimeline46() {}
}
