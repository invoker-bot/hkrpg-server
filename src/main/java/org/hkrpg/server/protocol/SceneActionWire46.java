package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;

/** Admission before protobuf can truncate uint identities or merge singular fields. */
final class SceneActionWire46 {
  static boolean rejectMalformed(GameSession session, String name, byte[] bytes) {
    var error = invalid(session.getProtocol().clientSchema().message(name), bytes, 0, "");
    if (error == null) return false;
    SceneActionDiagnostics46.rejected(session, name, "wire:" + error,
        Retcode.REQ_PARA_INVALID, 0, 0, 0, 0);
    Gameplay46.send(session, name.replace("CsReq", "ScRsp"),
        Map.of("retcode", Retcode.REQ_PARA_INVALID.getVal()));
    return true;
  }

  static String invalid(Descriptor type, byte[] bytes, int depth, String path) {
    if (depth > 4 || bytes.length > 16384) return path + "size";
    var in = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    var counts = new HashMap<Integer, Integer>();
    try {
      int tag;
      while ((tag = in.readTag()) != 0) {
        int number = tag >>> 3, wire = tag & 7;
        String location = path + number;
        var field = type.findFieldByNumber(number);
        if (field == null) return location + ":unknown";
        if (!field.isRepeated() && !seen.add(number)) return location + ":duplicate";
        if (field.getType() == FieldDescriptor.Type.MESSAGE) {
          if (wire != 2) return location + ":type";
          if (counts.merge(number, 1, Integer::sum) > 128) return location + ":count";
          var error = invalid(field.getMessageType(), in.readByteArray(), depth + 1, location + ".");
          if (error != null) return error;
        } else if (field.getType() == FieldDescriptor.Type.STRING) {
          if (wire != 2) return location + ":type";
          in.readStringRequireUtf8();
        } else if (field.isRepeated() && field.isPackable() && wire == 2) {
          var packed = CodedInputStream.newInstance(in.readByteArray());
          while (!packed.isAtEnd()) {
            if (counts.merge(number, 1, Integer::sum) > 128) return location + ":count";
            if (!scalar(field, packed)) return location + ":value";
          }
        } else {
          if (wire != 0) return location + ":type";
          if (counts.merge(number, 1, Integer::sum) > 128) return location + ":count";
          if (!scalar(field, in)) return location + ":value";
        }
      }
      return null;
    } catch (IOException malformed) {
      return path + "encoding";
    }
  }

  private static boolean scalar(FieldDescriptor field, CodedInputStream in) throws IOException {
    long value = in.readUInt64();
    // Interaction context really is UInt64; Vector coordinates use 32-bit zig-zag.
    if (field.getType() == FieldDescriptor.Type.UINT64) return true;
    if (Long.compareUnsigned(value, 0xffffffffL) > 0) return false;
    return switch (field.getType()) {
      case UINT32, SINT32 -> true;
      case ENUM -> field.getEnumType().findValueByNumber((int) value) != null;
      default -> false;
    };
  }

  private SceneActionWire46() {}
}
