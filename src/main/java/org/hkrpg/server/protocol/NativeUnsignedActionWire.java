package org.hkrpg.server.protocol;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.*;
import java.io.IOException;
import java.util.*;

/** Bounded native uint32 actions, including packed dice faces; reject aliases and merged oneofs. */
final class NativeUnsignedActionWire {
  static boolean valid(Descriptor type, byte[] bytes, int depth) {
    if (depth > 4 || bytes.length > 16384) return false;
    var input = CodedInputStream.newInstance(bytes);
    var singular = new HashSet<Integer>();
    var oneofs = new HashSet<OneofDescriptor>();
    var counts = new HashMap<Integer, Integer>();
    try {
      int tag;
      while ((tag = input.readTag()) != 0) {
        int number = tag >>> 3, wire = tag & 7;
        var field = type.findFieldByNumber(number);
        if (field == null
            || !field.isRepeated() && !singular.add(number)
            || field.getContainingOneof() != null && !oneofs.add(field.getContainingOneof()))
          return false;
        if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
          if (wire != 2 || !valid(field.getMessageType(), input.readByteArray(), depth + 1))
            return false;
          if (counts.merge(number, 1, Integer::sum) > 128) return false;
        } else if (field.isRepeated()
            && field.getType() == FieldDescriptor.Type.UINT32
            && wire == 2) {
          var packed = CodedInputStream.newInstance(input.readByteArray());
          while (!packed.isAtEnd())
            if (!scalar(field, packed) || counts.merge(number, 1, Integer::sum) > 128) return false;
        } else {
          if (wire != 0 || !scalar(field, input) || counts.merge(number, 1, Integer::sum) > 128)
            return false;
        }
      }
      return true;
    } catch (IOException malformed) {
      return false;
    }
  }

  private static boolean scalar(FieldDescriptor field, CodedInputStream input) throws IOException {
    long value = input.readUInt64();
    if (Long.compareUnsigned(value, 0xffffffffL) > 0) return false;
    return switch (field.getJavaType()) {
      case INT -> field.getType() == FieldDescriptor.Type.UINT32;
      case BOOLEAN -> value <= 1;
      case ENUM -> field.getEnumType().findValueByNumber((int) value) != null;
      default -> false;
    };
  }

  private NativeUnsignedActionWire() {}
}
