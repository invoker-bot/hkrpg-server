package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class TrialActivityWireTest {
  @Test
  void aRealTrialFinishPreservesTheStageAndStatusInTheCn46Notification() throws Exception {
    var bridge = ProtocolBridge.forVersion("4.6.0");
    class Session extends emu.lunarcore.server.game.GameSession {
      byte[] frame;

      Session() {
        super(null);
      }

      public ProtocolBridge getProtocol() {
        return bridge;
      }

      public void sendClientPacket(String name, byte[] bytes) {
        frame = PacketCodec.encode(getProtocol().clientCommand(name), new byte[0], bytes);
      }
    }
    var session = new Session();
    TrialActivity46.finished(session, 310010);
    var input = Unpooled.wrappedBuffer(session.frame);
    try {
      var frame = PacketCodec.decodeNext(input);
      assertEquals(2646, frame.cmdId());
      assertFalse(input.isReadable());
      var message = bridge.clientSchema().parse("CurTrialActivityScNotify", frame.body());
      assertEquals(310010, message.getField(message.getDescriptorForType().findFieldByNumber(1)));
      assertEquals(
          1,
          ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                  message.getField(message.getDescriptorForType().findFieldByNumber(10)))
              .getNumber());
      assertTrue(message.getUnknownFields().asMap().isEmpty());
    } finally {
      input.release();
    }
  }
}
