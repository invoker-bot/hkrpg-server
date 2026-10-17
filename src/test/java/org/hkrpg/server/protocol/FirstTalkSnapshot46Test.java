package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.FirstNpcTalkInfoOuterClass.FirstNpcTalkInfo;
import emu.lunarcore.proto.GetFirstTalkNpcScRspOuterClass.GetFirstTalkNpcScRsp;
import emu.lunarcore.server.game.GameServerPacketHandler;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.SessionState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FirstTalkSnapshot46Test {
  private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  private static Object field(DynamicMessage row, int number) {
    return row.getField(row.getDescriptorForType().findFieldByNumber(number));
  }

  @Test void nonemptyNpcReplyPreservesIdsOrderDuplicatesAndErrorWithoutInventingMeetings() throws Exception {
    var core = GetFirstTalkNpcScRsp.newInstance().setRetcode(100);
    for (int id : new int[] {10001, 10002, 10001})
      core.addNpcTalkInfoList(FirstNpcTalkInfo.newInstance().setNpcId(id));
    var packet = bridge.outbound(CmdId.GetFirstTalkNpcScRsp, core.toByteArray());
    assertEquals(2116, packet.cmdId());
    var reply = bridge.clientSchema().parse("GetFirstTalkNpcScRsp", packet.body());
    assertEquals(100, field(reply, 8));
    var rows = (List<?>) field(reply, 2);
    assertEquals(3, rows.size());
    for (int i = 0; i < rows.size(); i++) {
      var row = (DynamicMessage) rows.get(i);
      assertEquals(i == 1 ? 10002 : 10001, field(row, 1));
      assertEquals(false, field(row, 3));
      assertTrue(row.getUnknownFields().asMap().isEmpty());
    }
    assertArrayEquals(core.toByteArray(), ProtocolBridge.forVersion("4.2.0")
        .outbound(CmdId.GetFirstTalkNpcScRsp, core.toByteArray()).body());
  }

  @Test void actualCoreHandlerKeepsTheSessionActiveAndLaterQueryReachesItsHandler() throws Exception {
    class Session extends PlayerReturnInitial46Test.Session {
      final List<ProtocolBridge.Packet> replies = new ArrayList<>();
      @Override public void send(BasePacket packet) {
        replies.add(bridge.outbound(packet.getCmdId(), packet.getData().toByteArray()));
      }
    }
    var session = new Session();
    var type = bridge.clientSchema().message("GetFirstTalkNpcCsReq");
    var query = DynamicMessage.newBuilder(type).addRepeatedField(type.findFieldByNumber(4), 10001)
        .addRepeatedField(type.findFieldByNumber(4), 10002).build();
    var request = bridge.inbound(2160, query.toByteArray());
    assertEquals(CmdId.GetFirstTalkNpcCsReq, request.cmdId());
    new GameServerPacketHandler().handle(session, request.cmdId(), request.body());
    assertFalse(session.closed, "The observed NPC query must not disconnect the loaded scene");
    assertEquals(SessionState.ACTIVE, session.getState());
    assertEquals(2116, session.replies.getFirst().cmdId());
    var reply = bridge.clientSchema().parse("GetFirstTalkNpcScRsp", session.replies.getFirst().body());
    assertEquals(2, ((List<?>) field(reply, 2)).size());
    assertTrue(VersionedPacketHandler.handle(session, 3805, new byte[] {24, 18}));
    assertTrue(session.clientBodies.containsKey("TextJoinQueryScRsp"));
  }

  @Test void emptyRequestProducesAnEmptyListWithoutSyntheticMeetingRecords() throws Exception {
    var core = GetFirstTalkNpcScRsp.newInstance().setRetcode(0);
    var reply = bridge.clientSchema().parse("GetFirstTalkNpcScRsp",
        bridge.outbound(CmdId.GetFirstTalkNpcScRsp, core.toByteArray()).body());
    assertEquals(0, field(reply, 8));
    assertEquals(List.of(), field(reply, 2));
  }

  @Test void unknownNestedNpcFieldsRemainRejected() {
    // Core reply field4 contains NPC id12 plus an unknown field1.
    assertThrows(ProtocolException.class, () -> bridge.outbound(CmdId.GetFirstTalkNpcScRsp,
        new byte[] {34, 4, 96, 1, 8, 1}));
  }
}
