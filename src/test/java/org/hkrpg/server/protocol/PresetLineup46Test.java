package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.AvatarUpgrade46Test.join;
import static org.hkrpg.server.protocol.AvatarUpgrade46Test.scalar;
import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.UnknownFieldSet;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketGetAllLineupDataScRsp;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import io.netty.buffer.Unpooled;
import java.util.List;
import org.junit.jupiter.api.*;

/** The native favourite request must complete and its saved state must reach the team snapshot. */
class PresetLineup46Test {
  final MissionRaid46Test fixture = new MissionRaid46Test();
  FormalLineup46Test.Session session;

  @BeforeEach
  void prepare() {
    fixture.setup();
    session = new FormalLineup46Test.Session();
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
  }

  UnknownFieldSet mark(byte[] request) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 793, request));
    assertEquals(773, session.command);
    return UnknownFieldSet.parseFrom(session.payload);
  }

  int value(UnknownFieldSet msg, int number) {
    return msg.hasField(number) ? msg.getField(number).getVarintList().getFirst().intValue() : 0;
  }

  boolean marked(int index) throws Exception {
    var packet = new PacketGetAllLineupDataScRsp(session);
    var bytes = session.getProtocol().outboundFrames(packet.build(), session.actor);
    var input = Unpooled.wrappedBuffer(bytes);
    try {
      var frame = PacketCodec.decodeNext(input);
      assertEquals(738, frame.cmdId());
      var msg = session.getProtocol().clientSchema().parse("GetAllLineupDataScRsp", frame.body());
      var rows = (List<?>) msg.getField(msg.getDescriptorForType().findFieldByName("lineup_list"));
      var row = (DynamicMessage) rows.get(index);
      return (Boolean) row.getField(row.getDescriptorForType().findFieldByName("_is_favourite"));
    } finally {
      input.release();
    }
  }

  @Test
  void markAndUnmarkOnlyTheSelectedNormalTeamAndSendItsStateInTheSnapshot() throws Exception {
    var marked = mark(join(scalar(3, 1), scalar(9, 0)));
    assertEquals(0, value(marked, 13));
    assertEquals(1, value(marked, 9));
    assertEquals(0, value(marked, 7));
    assertTrue(marked(0));
    assertFalse(marked(1));
    assertEquals(1, session.actor.lineupSaves);
    mark(join(scalar(3, 1), scalar(9, 0)));
    assertEquals(1, session.actor.lineupSaves, "repeat must be idempotent");
    var unmarked = mark(new byte[0]);
    assertEquals(0, value(unmarked, 13));
    assertEquals(0, value(unmarked, 9));
    assertFalse(marked(0));
    assertEquals(2, session.actor.lineupSaves);
  }

  @Test
  void invalidAndAmbiguousIndicesDoNotMutateAnyTeam() throws Exception {
    for (byte[] request : List.of(scalar(9, 9), join(scalar(9, 0), scalar(9, 1)),
        join(scalar(3, 2), scalar(9, 0)), scalar(15, 1))) {
      var rsp = mark(request);
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), value(rsp, 13));
      assertFalse(marked(0));
      assertEquals(0, session.actor.lineupSaves);
    }
  }

  @Test
  void markingAnotherTeamLeavesTheActiveTeamAndItsMembersUntouched() throws Exception {
    session.actor.getLineupManager().getLineups()[1] = new PlayerLineup(session.actor, 1) {
      @Override public void save() { session.actor.lineupSaves++; }
    };
    var roster = List.copyOf(session.actor.getCurrentLineup().getAvatars());
    var response = mark(join(scalar(3, 1), scalar(9, 1)));
    assertEquals(0, value(response, 13));
    assertEquals(1, value(response, 7));
    assertFalse(marked(0));
    assertTrue(marked(1));
    assertEquals(roster, session.actor.getCurrentLineup().getAvatars());
  }

  @Test
  void savingFailureReturnsAnErrorAndRestoresTheInMemoryFlag() throws Exception {
    var teams = session.actor.getLineupManager().getLineups();
    teams[0] = new PlayerLineup(session.actor, 0) {
      @Override public void save() { throw new IllegalStateException("simulated storage failure"); }
    };
    teams[0].getAvatars().add(MissionRaid46Test.AVATAR);
    var response = mark(join(scalar(3, 1), scalar(9, 0)));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), value(response, 13));
    assertFalse(marked(0));
    assertEquals(List.of(MissionRaid46Test.AVATAR), teams[0].getAvatars());
  }

  @Test
  void temporaryLineupSnapshotsDoNotInheritTheNormalTeamFlag() throws Exception {
    mark(join(scalar(3, 1), scalar(9, 0)));
    var schema = session.getProtocol().clientSchema();
    var parent = DynamicMessage.newBuilder(schema.message("GetCurLineupDataScRsp"));
    var lineupField = parent.getDescriptorForType().findFieldByName("lineup");
    var lineup = DynamicMessage.newBuilder(lineupField.getMessageType());
    lineup.setField(lineup.getDescriptorForType().findFieldByName("_is_virtual"), true);
    parent.setField(lineupField, lineup.build());
    var original = parent.build().toByteArray();
    assertArrayEquals(original,
        PresetLineup46.enrich(schema, "GetCurLineupDataScRsp", original, session.actor));
  }

  @Test
  void nonstandardResponseNameStillCompletesTheClientsMatchingRequest() throws Exception {
    var correlation = new ReplyCorrelation46();
    var header = scalar(1, 123);
    try (var ignored = correlation.enter(session.getProtocol(), 793, header)) {
      var input = Unpooled.wrappedBuffer(correlation.outbound(PacketCodec.encode(773, new byte[0], new byte[0])));
      try {
        assertArrayEquals(header, PacketCodec.decodeNext(input).header());
      } finally {
        input.release();
      }
    }
  }

  @Test
  void replacementResponseAlsoCorrelatesDespiteItsCollidingDescriptorName() throws Exception {
    var correlation = new ReplyCorrelation46();
    var header = scalar(1, 456);
    try (var ignored = correlation.enter(session.getProtocol(), 704, header)) {
      var input = Unpooled.wrappedBuffer(correlation.outbound(PacketCodec.encode(719, new byte[0], new byte[0])));
      try {
        assertArrayEquals(header, PacketCodec.decodeNext(input).header());
      } finally {
        input.release();
      }
    }
  }
}
