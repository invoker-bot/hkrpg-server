package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.util.HashSet;

/** Checks raw map/list identities before protobuf normalization or the legacy bridge. */
final class BattleTargetReports46 {
  static final int MAX_CATEGORIES = 32, MAX_TARGETS = 1024;

  static Long category(byte[] bytes) throws IOException {
    if (bytes.length > 65536) return null;
    var input = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    long key = 0; // Protobuf's omitted map key is uint32 zero.
    int tag;
    while ((tag = input.readTag()) != 0) {
      if (!seen.add(tag >>> 3)) return null;
      if (tag == 8) {
        key = input.readUInt64();
        if (!uint32(key)) return null;
      } else if (tag == 18) {
        if (!targets(input.readByteArray())) return null;
      } else return null;
    }
    return key;
  }

  private static boolean targets(byte[] bytes) throws IOException {
    var input = CodedInputStream.newInstance(bytes);
    var identities = new HashSet<Long>();
    int tag;
    while ((tag = input.readTag()) != 0) {
      if (tag != 10 || identities.size() >= MAX_TARGETS) return false;
      var id = identity(input.readByteArray());
      if (id == null || !identities.add(id)) return false;
    }
    return true;
  }

  private static Long identity(byte[] bytes) throws IOException {
    var input = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    long id = 0;
    int tag;
    while ((tag = input.readTag()) != 0) {
      if ((tag != 8 && tag != 16 && tag != 24) || !seen.add(tag >>> 3)) return null;
      long value = input.readUInt64();
      if (!uint32(value)) return null;
      if (tag == 8) id = value;
    }
    return id;
  }

  private static boolean uint32(long value) {
    return Long.compareUnsigned(value, 0xffffffffL) <= 0;
  }

  private BattleTargetReports46() {}
}
