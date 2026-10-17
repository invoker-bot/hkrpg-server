package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.BackGroundMusicExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.GetJukeboxDataScRspOuterClass.GetJukeboxDataScRsp;
import emu.lunarcore.proto.UnlockedMusicOuterClass.UnlockedMusic;
import emu.lunarcore.server.game.GameServerPacketHandler;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.SessionState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JukeboxInitial46Test {
  private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  private static DynamicMessage child(DynamicMessage parent, int number) {
    var field = parent.getDescriptorForType().findFieldByNumber(number);
    assertTrue(parent.hasField(field), "Native music callback requires " + field.getFullName());
    return (DynamicMessage) parent.getField(field);
  }

  @Test void nonemptyCatalogAndSelectedMusicUseTheVerifiedNested46Shape() throws Exception {
    var core = GetJukeboxDataScRsp.newInstance().setPlayingId(210123)
        .addMusicList(UnlockedMusic.newInstance().setId(210000).setGroupId(1).setUnkbool(true))
        .addMusicList(UnlockedMusic.newInstance().setId(210123).setGroupId(3).setUnkbool(false));
    var bytes = core.toByteArray();
    var packet = bridge.outbound(CmdId.GetJukeboxDataScRsp, bytes);
    assertEquals(3125, packet.cmdId());
    var reply = bridge.clientSchema().parse("GetJukeboxDataScRsp", packet.body());
    var rows = (List<?>) reply.getField(reply.getDescriptorForType().findFieldByNumber(4));
    assertEquals(2, rows.size());
    for (int i = 0; i < 2; i++) {
      var row = (DynamicMessage) rows.get(i);
      assertEquals(i == 0 ? 210000 : 210123, InitialData46Test.field(row, "id"));
      assertEquals(i == 0 ? 1 : 3, InitialData46Test.field(row, "group_id"));
      assertEquals(i == 0, row.getField(row.getDescriptorForType().findFieldByNumber(8)));
    }
    var player = child(reply, 12);
    var selected = child(player, 1);
    assertEquals(210123, InitialData46Test.field(selected, "id"));
    assertEquals(false, InitialData46Test.field(selected, "is_pause"));
    assertEquals(0, player.getRepeatedFieldCount(player.getDescriptorForType().findFieldByNumber(2)));
    assertEquals(false, player.getField(player.getDescriptorForType().findFieldByNumber(3)));
    assertArrayEquals(bytes, ProtocolBridge.forVersion("4.2.0")
        .outbound(CmdId.GetJukeboxDataScRsp, bytes).body());
  }

  @Test void emptyCatalogStillPreservesTheActualSelectionAndErrorCode() throws Exception {
    var core = GetJukeboxDataScRsp.newInstance().setPlayingId(210000).setRetcode(100);
    var packet = bridge.outbound(CmdId.GetJukeboxDataScRsp, core.toByteArray());
    var reply = bridge.clientSchema().parse("GetJukeboxDataScRsp", packet.body());
    assertEquals(100, InitialData46Test.field(reply, "retcode"));
    assertEquals(210000, InitialData46Test.field(child(child(reply, 12), 1), "id"));
    assertEquals(0, reply.getRepeatedFieldCount(reply.getDescriptorForType().findFieldByNumber(4)));
  }

  @Test void unknownFieldsRemainRejectedRatherThanBeingSilentlyDiscarded() {
    assertThrows(ProtocolException.class, () -> bridge.outbound(
        CmdId.GetJukeboxDataScRsp, new byte[] {(byte) 0xf8, 0x07, 1}));
  }

  @Test void realJukeboxHandlerNoLongerClosesTheAuthenticatedSession() throws Exception {
    var map = GameData.getBackGroundMusicExcelMap();
    var previous = new ArrayList<>(map.values());
    map.keySet().clear();
    map.add(new Gson().fromJson("{\"ID\":210000,\"GroupID\":1}", BackGroundMusicExcel.class));
    try {
      class Session extends GameSession {
        boolean closed;
        final List<ProtocolBridge.Packet> writes = new ArrayList<>();
        final Player actor = new Player() { @Override public int getCurrentBgm() { return 210000; } };
        Session() { super(null); setState(SessionState.ACTIVE); }
        @Override public ProtocolBridge getProtocol() { return bridge; }
        @Override public Player getPlayer() { return actor; }
        @Override public void close() { closed = true; }
        @Override public void send(BasePacket packet) {
          writes.add(bridge.outbound(packet.getCmdId(), packet.getData().toByteArray()));
        }
      }
      var session = new Session();
      var handlers = new GameServerPacketHandler();
      handlers.handle(session, CmdId.GetJukeboxDataCsReq, new byte[0]);
      assertFalse(session.closed, "The observed music catalog response must not disconnect login");
      assertEquals(SessionState.ACTIVE, session.getState());
      assertEquals(1, session.writes.size());
      assertEquals(3125, session.writes.getFirst().cmdId());
    } finally {
      map.keySet().clear();
      previous.forEach(map::add);
    }
  }
}
