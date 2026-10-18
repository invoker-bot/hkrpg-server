package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.TextJoinExcel;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextJoinInitial46Test {
  private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  private byte[] query(int... ids) {
    var type = bridge.clientSchema().message("TextJoinQueryCsReq");
    var data = DynamicMessage.newBuilder(type);
    for (int id : ids) data.addRepeatedField(type.findFieldByNumber(3), id);
    return data.build().toByteArray();
  }

  private DynamicMessage call(byte[] bytes) throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    assertTrue(VersionedPacketHandler.handle(session, 3805, bytes));
    assertEquals(SessionState.ACTIVE, session.getState());
    assertFalse(session.closed);
    assertEquals(List.of(3875), session.sent);
    return bridge.clientSchema().parse("TextJoinQueryScRsp", session.lastClientBody);
  }

  private static TextJoinExcel row(int id, int... items) {
    return new TextJoinExcel() {
      @Override public int getId() { return id; }
      @Override public IntArrayList getTextJoinItemList() { return new IntArrayList(items); }
    };
  }

  private static TextJoinExcel avatarDefault(int id) {
    // Pinned 4.6 rows 55-58 and 83-86: item IDs name default text, not avatars.
    return emu.lunarcore.util.JsonUtils.decode(
        "{\"TextJoinID\":" + id + ",\"DefaultItem\":" + id * 10
            + ",\"IsOverride\":true,\"Type\":\"AvatarID\",\"TextJoinItemList\":["
            + id * 10 + "]}", TextJoinExcel.class);
  }

  @Test void avatarDefaultsRemainClientTextWithoutInventingServerAvatarOverrides() throws Exception {
    var map = GameData.getTextJoinExcelMap();
    var previous = new ArrayList<>(map.values());
    map.keySet().clear();
    int[] ids = {55, 56, 57, 58, 83, 84, 85, 86};
    for (int id : ids) map.add(avatarDefault(id));
    try {
      var reply = call(query(ids));
      assertEquals(0, InitialData46Test.field(reply, "retcode"));
      // Native AvatarID default factory resolves DefaultItem through TextJoinItem;
      // a response row would instead mark server state and perform an AvatarID lookup.
      assertEquals(0, reply.getRepeatedFieldCount(reply.getDescriptorForType().findFieldByNumber(13)));
    } finally {
      map.keySet().clear();
      previous.forEach(map::add);
    }
  }

  @Test void avatarDefaultsDoNotRemoveNormalSelectionsOrTheirDuplicates() throws Exception {
    var map = GameData.getTextJoinExcelMap();
    var previous = new ArrayList<>(map.values());
    map.keySet().clear();
    map.add(avatarDefault(55));
    map.add(avatarDefault(86));
    map.add(row(18, 181, 180));
    map.add(row(19, 192, 190));
    try {
      var reply = call(query(55, 18, 86, 19, 18, 55));
      assertEquals(0, InitialData46Test.field(reply, "retcode"));
      var rows = (List<?>) reply.getField(reply.getDescriptorForType().findFieldByNumber(13));
      assertEquals(3, rows.size());
      for (int i = 0; i < rows.size(); i++) {
        var info = (DynamicMessage) rows.get(i);
        assertEquals(i == 1 ? 19 : 18, info.getField(info.getDescriptorForType().findFieldByNumber(10)));
        assertEquals(i == 1 ? 192 : 181, info.getField(info.getDescriptorForType().findFieldByNumber(15)));
      }
    } finally {
      map.keySet().clear();
      previous.forEach(map::add);
    }
  }

  @Test void nativeQueryPreservesOrderDuplicatesAndActualConfiguredSelections() throws Exception {
    var map = GameData.getTextJoinExcelMap();
    var previous = new ArrayList<>(map.values());
    map.keySet().clear();
    map.add(row(18, 181, 180));
    map.add(row(19, 192, 190));
    try {
      var reply = call(query(18, 19, 18));
      assertEquals(0, InitialData46Test.field(reply, "retcode"));
      var rows = (List<?>) reply.getField(reply.getDescriptorForType().findFieldByNumber(13));
      assertEquals(3, rows.size());
      for (int i = 0; i < 3; i++) {
        var info = (DynamicMessage) rows.get(i);
        // Native e20b239 reads field 10 (+0x24) as the join dictionary key.
        assertEquals(i == 1 ? 19 : 18, info.getField(info.getDescriptorForType().findFieldByNumber(10)));
        assertEquals(i == 1 ? 192 : 181, info.getField(info.getDescriptorForType().findFieldByNumber(15)));
        assertEquals("", info.getField(info.getDescriptorForType().findFieldByNumber(7)));
      }
    } finally {
      map.keySet().clear();
      previous.forEach(map::add);
    }
  }

  @Test void unknownCatalogIdUsesTheInheritedBoundedDefaultWithoutWritingPlayerData() throws Exception {
    var map = GameData.getTextJoinExcelMap();
    var previous = map.get(28);
    map.keySet().remove(28);
    try {
      var reply = call(query(28));
      var info = (DynamicMessage) ((List<?>) reply.getField(
          reply.getDescriptorForType().findFieldByNumber(13))).getFirst();
      assertEquals(28, info.getField(info.getDescriptorForType().findFieldByNumber(10)));
      assertEquals(280, info.getField(info.getDescriptorForType().findFieldByNumber(15)));
    } finally { if (previous != null) map.add(previous); }
  }

  @Test void malformedUnknownOversizedAndOverflowingQueriesReturnAnErrorWithoutPartialRows() throws Exception {
    int[] oversized = new int[1025];
    java.util.Arrays.fill(oversized, 18);
    for (byte[] bytes : List.of(new byte[] {(byte) 0x80}, new byte[] {8, 1},
        new byte[] {24, (byte) 0x92, (byte) 0x80, (byte) 0x80, (byte) 0x80, 16},
        new byte[] {26, 5, (byte) 0x92, (byte) 0x80, (byte) 0x80, (byte) 0x80, 16},
        new byte[] {26, 2, 18},
        query(0), query(18, 0), query(-1), query(Integer.MAX_VALUE), query(oversized))) {
      var reply = call(bytes);
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(reply, "retcode"));
      assertEquals(0, reply.getRepeatedFieldCount(reply.getDescriptorForType().findFieldByNumber(13)));
    }
  }

  @Test void packedAndUnpackedIdsHaveTheSameResponse() throws Exception {
    assertArrayEquals(call(query(18, 19)).toByteArray(),
        call(new byte[] {24, 18, 24, 19}).toByteArray());
  }

  @Test void unauthenticatedQueriesCannotReadTextState() throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    session.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(session, 3805, query(18)));
    assertTrue(session.sent.isEmpty());
  }
}
