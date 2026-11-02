package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import org.junit.jupiter.api.Test;

class CocoonEntry46Test {
  static class Session extends GameSession {
    final Player player = new Player();
    String responseName;
    DynamicMessage response;
    String version = "4.6.0";

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion(version);
    }

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        responseName = name;
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }

  @Test
  void ordinaryInvalidEntryStillSendsItsNativeResponseDespiteAbsentLegacyResponseCommand()
      throws Exception {
    var session = new Session();
    assertTrue(VersionedPacketHandler.handle(session, 1432, new byte[0]));
    assertEquals("StartCocoonStageScRsp", session.responseName);
    assertEquals(1484, session.getProtocol().clientCommand(session.responseName));
    assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
    assertNull(session.player.getBattle());
  }

  @Test
  void quickEntryRejectsACountWithoutACocoonWithAnActualNativeFailureResponse() throws Exception {
    var session = new Session();
    // Native challenge count field6 does not supply the required cocoon identity.
    assertTrue(VersionedPacketHandler.handle(session, 1360, new byte[] {48, 1}));
    assertEquals("QuickStartCocoonStageScRsp", session.responseName);
    assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
    assertNull(session.player.getBattle());
  }

  @Test
  void unauthenticatedEntriesCannotParseOrCreateBattles() throws Exception {
    for (int command : new int[] {1432, 1360}) {
      var session = new Session();
      session.setState(SessionState.WAITING_FOR_TOKEN);
      assertTrue(VersionedPacketHandler.handle(session, command, new byte[] {(byte) 128}));
      assertNull(session.response);
      assertNull(session.player.getBattle());
    }
  }

  @Test
  void duplicateTruncatedWideUnknownAndNestedOptionsCannotCreateBattles() throws Exception {
    byte[][] ordinary = {
      {32, 1, 32, 2}, // Repeated scalar wave.
      {(byte) 128}, // Truncated tag.
      {56, (byte) 233, 7, 32, (byte) 129, (byte) 128, (byte) 128, (byte) 128, 16}, // 2^32+1 waves.
      {56, (byte) 233, 7, 32, 1, 80, 1}, // Ambiguous old wave plus native count.
      {56, (byte) 233, 7, 32, 1, 122, 0}, // Unsupported lineup/context field15.
      {56, (byte) 233, 7, 32, 1, 112, 1}, // Unknown field14.
      {56, (byte) 233, 7, 32, 1, 64, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 15}
    };
    for (byte[] bytes : ordinary) {
      var session = new Session();
      assertTrue(VersionedPacketHandler.handle(session, 1432, bytes));
      assertEquals("StartCocoonStageScRsp", session.responseName);
      assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
      assertEquals(0, Gameplay46.number(session.response, "cocoon_id"));
      assertFalse(
          session.response.hasField(
              session.response.getDescriptorForType().findFieldByName("battle_info")));
      assertNull(session.player.getBattle());
    }
    for (byte[] bytes : new byte[][] {{24, 1, 24, 2}, {58, 0}, {90, 0}, {56, (byte) 128}}) {
      var session = new Session();
      assertTrue(VersionedPacketHandler.handle(session, 1360, bytes));
      assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
      assertNull(session.player.getBattle());
    }
  }

  @Test
  void nativeAdapterDoesNotReplaceLegacyCommandsOrUnrelatedRequests() throws Exception {
    var session = new Session();
    assertFalse(Cocoon46.handle(session, 44, new byte[0]));
    session.version = "4.2.0";
    assertFalse(
        Cocoon46.handle(
            session, emu.lunarcore.server.packet.CmdId.StartCocoonStageCsReq, new byte[0]));
    assertFalse(
        Cocoon46.handle(
            session, emu.lunarcore.server.packet.CmdId.QuickStartCocoonStageCsReq, new byte[0]));
    assertNull(session.response);
  }
}
