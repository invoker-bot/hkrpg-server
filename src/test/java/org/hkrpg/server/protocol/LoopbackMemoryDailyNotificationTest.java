package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.Unpooled;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import kcp.highway.Ukcp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A real response frame can contain the pre-daily snapshot emitted by story completion. */
class LoopbackMemoryDailyNotificationTest {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");

  private LoopbackMissionChallengeProbe probe(boolean claimed, boolean resume) throws Exception {
    var probe = new LoopbackMissionChallengeProbe();
    for (var name : new String[] {"verifyDaily", "resume", "step"}) {
      var field = LoopbackMissionChallengeProbe.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(probe, name.equals("step") ? 6 : name.equals("verifyDaily") || resume);
    }
    if (claimed) {
      var field = LoopbackMissionChallengeProbe.class.getDeclaredField("result");
      field.setAccessible(true);
      ((Map<String, Object>) field.get(probe)).put("nativeActivityPointsRemainVirtual", true);
    }
    return probe;
  }

  private void receive(LoopbackMissionChallengeProbe probe, int progress, int status)
      throws Exception {
    var response = DynamicMessage.newBuilder(protocol.clientSchema().message("GetQuestDataScRsp"));
    var list = response.getDescriptorForType().findFieldByName("quest_list");
    var row = DynamicMessage.newBuilder(list.getMessageType());
    row.setField(row.getDescriptorForType().findFieldByName("id"), 2100119);
    row.setField(row.getDescriptorForType().findFieldByName("progress"), progress);
    var state = row.getDescriptorForType().findFieldByName("status");
    row.setField(state, state.getEnumType().findValueByNumber(status));
    response.addRepeatedField(list, row.build());
    var bytes =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand("GetQuestDataScRsp"),
                new byte[0],
                response.build().toByteArray()));
    var receive =
        LoopbackMissionChallengeProbe.class.getDeclaredMethod(
            "receive", io.netty.buffer.ByteBuf.class, Ukcp.class);
    receive.setAccessible(true);
    try {
      receive.invoke(probe, bytes, null);
      assertFalse(bytes.isReadable());
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) throw cause;
      throw failure;
    } finally {
      bytes.release();
    }
  }

  @ParameterizedTest
  @CsvSource({"0,1", "1,2"})
  void aPreClaimNotificationDoesNotPretendTheRewardWasAlreadyClaimed(int progress, int status)
      throws Exception {
    var probe = probe(false, false);
    assertDoesNotThrow(() -> receive(probe, progress, status));
  }

  @ParameterizedTest
  @CsvSource({"0,2", "1,1", "1,3"})
  void inconsistentPreClaimNotificationsAreStillRejected(int progress, int status)
      throws Exception {
    var probe = probe(false, false);
    assertThrows(IllegalStateException.class, () -> receive(probe, progress, status));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aConfirmedClaimOrRestoredClaimRequiresTheClosedState(boolean resume) throws Exception {
    var probe = probe(!resume, resume);
    assertDoesNotThrow(() -> receive(probe, 1, 3));
  }

  @ParameterizedTest
  @CsvSource({"false,0,1", "false,1,2", "true,0,1", "true,1,2"})
  void aClaimedDayCannotRegressToAnUnclaimedSnapshot(boolean resume, int progress, int status)
      throws Exception {
    var probe = probe(!resume, resume);
    assertThrows(IllegalStateException.class, () -> receive(probe, progress, status));
  }
}
