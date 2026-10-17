package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.mail.Mail;
import emu.lunarcore.game.mail.Mailbox;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class MailSnapshot46Test {
  private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  static class Actor extends Player {
    final List<Mail> mails = new ArrayList<>();
    final Mailbox mailbox = new Mailbox(this) {
      @Override public int size() { return mails.size(); }
      @Override public Iterator<Mail> iterator() { return List.copyOf(mails).iterator(); }
    };
    @Override public Mailbox getMailbox() { return mailbox; }
    @Override public void save() { fail("A mailbox query must not save the player"); }
  }

  static class Session extends PlayerReturnInitial46Test.Session {
    final Actor actor = new Actor();
    @Override public Player getPlayer() { return actor; }
  }

  private byte[] query(int start, int count) {
    var type = bridge.clientSchema().message("GetMailCsReq");
    return DynamicMessage.newBuilder(type).setField(type.findFieldByNumber(4), start)
        .setField(type.findFieldByNumber(9), count).build().toByteArray();
  }

  private Session mailbox(int size) {
    var session = new Session();
    for (int i = size; i > 0; i--) {
      var mail = new Mail("Subject " + i, "Local", "Body " + i) {
        @Override public void save() { fail("A mailbox query must not save or mark mail read"); }
      };
      mail.setUniqueId(i);
      mail.setOwner(session.actor);
      session.actor.mails.add(mail);
    }
    return session;
  }

  private DynamicMessage call(Session session, byte[] body) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 834, body));
    assertFalse(session.closed);
    assertEquals(SessionState.ACTIVE, session.getState());
    assertEquals(825, session.sent.getLast());
    return bridge.clientSchema().parse("GetMailScRsp", session.lastClientBody);
  }

  private Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  private List<Integer> ids(DynamicMessage message) {
    return ((List<?>) field(message, "mail_list")).stream()
        .map(row -> (Integer) field((DynamicMessage) row, "id")).toList();
  }

  @Test void nativeCountOnlyQueryDoesNotDisconnectOrDiscardTheFollowingFrame() {
    var session = mailbox(1);
    var input = Unpooled.wrappedBuffer(
        PacketCodec.encode(834, new byte[0], query(0, 50)),
        PacketCodec.encode(3805, new byte[0], new byte[] {24, 18}));
    try { session.onMessage(input); } finally { input.release(); }
    assertFalse(session.closed, "The observed two-byte mail query must keep the session alive");
    assertEquals(SessionState.ACTIVE, session.getState());
    assertEquals(List.of(825, 3875), session.sent);
  }

  @Test void pagesAreBoundedStableAndPreserveActualMailWithoutMutation() throws Exception {
    var session = mailbox(53);
    var first = call(session, query(0, 50));
    assertEquals(0, field(first, "retcode"));
    assertEquals(0, field(first, "start"));
    assertEquals(53, field(first, "total_num"));
    assertEquals(false, field(first, "is_end"));
    assertEquals(java.util.stream.IntStream.rangeClosed(1, 50).boxed().toList(), ids(first));
    var last = call(session, query(50, 50));
    assertEquals(50, field(last, "start"));
    assertEquals(53, field(last, "total_num"));
    assertEquals(true, field(last, "is_end"));
    assertEquals(List.of(51, 52, 53), ids(last));
    var mail = (DynamicMessage) ((List<?>) field(last, "mail_list")).getFirst();
    assertEquals("Subject 51", field(mail, "title"));
    assertEquals("Body 51", field(mail, "content"));
    assertEquals(false, field(mail, "is_read"));
    assertEquals(List.of(), field(last, "notice_mail_list"));
    assertEquals(53, session.actor.mails.size());
    assertTrue(session.actor.mails.stream().noneMatch(Mail::isRead));
  }

  @Test void omittedCountAndOversizedUnsignedCountUseTheBoundedDefault() throws Exception {
    var session = mailbox(60);
    var defaultPage = call(session, new byte[0]);
    assertEquals(50, ids(defaultPage).size());
    assertArrayEquals(defaultPage.toByteArray(), call(session, query(0, -1)).toByteArray());
    assertEquals(List.of(3, 4), ids(call(session, query(2, 2))));
  }

  @Test void emptyMailboxAndUnsignedOutOfRangeOffsetTerminatePaging() throws Exception {
    var empty = call(mailbox(0), query(0, 50));
    assertEquals(0, field(empty, "total_num"));
    assertEquals(true, field(empty, "is_end"));
    var beyond = call(mailbox(3), query(-1, 50));
    assertEquals(-1, field(beyond, "start"));
    assertEquals(3, field(beyond, "total_num"));
    assertEquals(true, field(beyond, "is_end"));
    assertEquals(List.of(), ids(beyond));
  }

  @Test void invalidWireReturnsAnErrorWithoutMailOrDisconnect() throws Exception {
    for (byte[] body : List.of(new byte[] {(byte) 0x80}, new byte[] {8, 1},
        new byte[] {34, 0}, new byte[] {32, 0, 32, 1},
        new byte[] {72, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 16},
        new byte[129])) {
      var reply = call(mailbox(1), body);
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(reply, "retcode"));
      assertEquals(List.of(), ids(reply));
    }
  }

  @Test void unauthenticatedMailQueryCannotReadTheMailbox() throws Exception {
    var session = mailbox(1);
    session.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(session, 834, query(0, 50)));
    assertTrue(session.sent.isEmpty());
  }

  @Test void overflowingWireTagCannotAliasAValidPagingField() throws Exception {
    var reply = call(mailbox(1), new byte[] {(byte) 0xa0, (byte) 0x80,
        (byte) 0x80, (byte) 0x80, 0x10, 0}); // 2^32 + tag 32, not tag 32.
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(reply, "retcode"));
    assertEquals(List.of(), ids(reply));
  }

  @Test void legacyFullSnapshotKeepsItsOriginalWireBytes() {
    var session = mailbox(53);
    var packet = new emu.lunarcore.server.packet.send.PacketGetMailScRsp(session.actor);
    assertArrayEquals(packet.getData().toByteArray(), ProtocolBridge.forVersion("4.2.0")
        .outbound(packet.getCmdId(), packet.getData().toByteArray()).body());
  }
}
