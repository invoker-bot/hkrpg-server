package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual client finish command 5646, request field 2, response 5631 / retcode field 9. */
class UniverseDialogueFinish46Test {
  @TempDir Path root;
  final UniverseDialogueRewards46Test fixture = new UniverseDialogueRewards46Test();

  @BeforeEach void setup() throws Exception { fixture.root = root; fixture.setup(); }
  @AfterEach void cleanup() { fixture.cleanup(); }

  static byte[] request(long unique) throws Exception {
    var raw = new ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(raw);
    wire.writeUInt64(2, unique);
    wire.flush();
    return raw.toByteArray();
  }

  int finish(byte[] bytes) throws Exception {
    var session = fixture.fixture.session;
    int before = session.nativePackets.size();
    assertTrue(VersionedPacketHandler.handle(session, 5646, bytes),
        "The actual End request must not remain unhandled");
    assertEquals(before + 1, session.nativePackets.size(), "End must receive exactly one acknowledgement");
    var packet = session.nativePackets.getLast();
    assertEquals(5631, packet.getKey());
    var response = session.getProtocol().clientSchema().parse("LPDPAJGFBMM", packet.getValue());
    assertTrue(response.getUnknownFields().asMap().isEmpty());
    var retcode = response.getDescriptorForType().findFieldByNumber(9);
    assertEquals("retcode", retcode.getName());
    return (Integer) response.getField(retcode);
  }

  int selected(int option, boolean acquire) throws Exception {
    int unique = fixture.unique();
    assertEquals(0, fixture.choose(unique, option));
    if (option == 14102 && acquire) {
      var run = fixture.run();
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
          fixture.fixture.session.explorer.getUniverseManager()
              .selectBlessing(run.getSelectionId(), run.getOffers().getFirst()));
    }
    return unique;
  }

  @ParameterizedTest @ValueSource(ints = {14101, 14102, 14103})
  void endingAndRetryingCompletedEventAcknowledgesWithoutRepeatingRewards(int option) throws Exception {
    int unique = selected(option, true);
    var before = fixture.snapshot();
    int saves = fixture.fixture.session.explorer.saves;
    assertEquals(0, finish(request(unique)));
    assertEquals(0, finish(request(unique)));
    assertEquals(before, fixture.snapshot());
    assertEquals(saves, fixture.fixture.session.explorer.saves);
  }

  @Test void endingPendingBlessingPreservesTheExactUnclaimedMenu() throws Exception {
    int unique = selected(14102, false);
    var before = fixture.snapshot();
    int saves = fixture.fixture.session.explorer.saves;
    assertEquals(0, finish(request(unique)));
    assertEquals(before, fixture.snapshot());
    assertEquals(saves, fixture.fixture.session.explorer.saves);
    assertTrue(fixture.run().pending());
    assertTrue(fixture.run().getCleared().isEmpty());
  }

  @Test void unselectedEventCannotBeCompletedByTheEndRequest() throws Exception {
    int unique = fixture.unique();
    var before = fixture.snapshot();
    assertNotEquals(0, finish(request(unique)));
    assertEquals(before, fixture.snapshot());
  }

  @Test void differentUniqueIdentityCannotCloseTheCurrentEvent() throws Exception {
    int unique = selected(14103, true);
    var before = fixture.snapshot();
    assertNotEquals(0, finish(request(unique + 1)));
    assertEquals(before, fixture.snapshot());
  }

  @Test void savedCompletionCanBeAcknowledgedAfterSceneAndPlayerRestoration() throws Exception {
    int unique = selected(14102, true);
    var f = fixture.fixture;
    f.session.explorer.state = f.gson.fromJson(f.gson.toJson(f.session.explorer.state), GameplayState.class);
    f.reloadScene();
    var before = fixture.snapshot();
    assertEquals(0, finish(request(unique)));
    assertEquals(before, fixture.snapshot());
  }

  @Test void pausedRunCannotAcknowledgeAnInSceneEndRequest() throws Exception {
    int unique = selected(14103, true);
    fixture.run().setInside(false);
    var before = fixture.snapshot();
    assertNotEquals(0, finish(request(unique)));
    assertEquals(before, fixture.snapshot());
  }

  @Test void corruptReceiptCannotBeTurnedIntoASuccessfulCompletion() throws Exception {
    int unique = selected(14103, true);
    var f = fixture.fixture;
    var json = f.gson.toJsonTree(f.session.explorer.state).getAsJsonObject();
    var dialogues = json.getAsJsonObject("rogue").getAsJsonObject("dialogues");
    dialogues.entrySet().iterator().next().getValue().getAsJsonObject()
        .getAsJsonObject("rewardState").addProperty("source", "wrong source");
    f.session.explorer.state = f.gson.fromJson(json, GameplayState.class);
    f.reloadScene();
    var before = fixture.snapshot();
    assertNotEquals(0, finish(request(unique)));
    assertEquals(before, fixture.snapshot());
  }

  @Test void clearedFlagAloneCannotReplaceAnEventRewardReceipt() throws Exception {
    int unique = fixture.unique();
    fixture.run().clear();
    var before = fixture.snapshot();
    assertNotEquals(0, finish(request(unique)));
    assertEquals(before, fixture.snapshot());
  }

  @Test void malformedEndRequestsReceiveErrorsWithoutChangingProgress() throws Exception {
    int unique = selected(14103, true);
    var before = fixture.snapshot();
    var duplicate = new ByteArrayOutputStream();
    duplicate.write(request(unique)); duplicate.write(request(unique));
    for (byte[] raw : List.of(new byte[0], new byte[]{16}, new byte[]{24, 1},
        new byte[]{21, 1, 0, 0, 0}, request(0), request(-1), request((1L << 32) + unique),
        duplicate.toByteArray(), new byte[16385])) {
      assertNotEquals(0, finish(raw));
      assertEquals(before, fixture.snapshot());
    }
  }

  @Test void unauthenticatedEndRequestDoesNotReadOrPublishPlayerState() throws Exception {
    var session = fixture.fixture.session;
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session, 5646, request(1)));
    assertTrue(session.nativePackets.isEmpty());
  }

  @Test void legacyProtocolDoesNotConsumeTheNativeFinishCommand() throws Exception {
    var session = new GameSession(null) {
      @Override public ProtocolBridge getProtocol() { return ProtocolBridge.forVersion("4.2.0"); }
    };
    assertFalse(RogueDialogue46.handle(session, 5646, request(1)));
  }

  @Test void finishReplyCarriesTheClickPacketIdentityAndNotificationsCannotConsumeIt() throws Exception {
    var bridge = fixture.fixture.session.getProtocol();
    assertEquals(5631, bridge.clientResponseCommand(5646));
    assertEquals(0, ProtocolBridge.forVersion("4.2.0").clientResponseCommand(5646));
    for (int id : new int[]{1, 73597, Integer.MIN_VALUE, -1}) {
      var head = new ByteArrayOutputStream();
      var wire = CodedOutputStream.newInstance(head);
      wire.writeUInt32(1, id); wire.flush();
      var correlation = new ReplyCorrelation46();
      try (var scope = correlation.enter(bridge, 5646, head.toByteArray())) {
        int index = 0;
        for (int command : new int[]{5691, 5631, 5631}) {
          var packet = emu.lunarcore.server.packet.PacketCodec.encode(command, new byte[0], new byte[0]);
          var input = io.netty.buffer.Unpooled.wrappedBuffer(correlation.outbound(packet));
          try {
            var frame = emu.lunarcore.server.packet.PacketCodec.decodeNext(input);
            assertEquals(command, frame.cmdId());
            assertArrayEquals(index == 1 ? head.toByteArray() : new byte[0], frame.header());
            index++;
          } finally { input.release(); }
        }
      }
    }
  }
}
