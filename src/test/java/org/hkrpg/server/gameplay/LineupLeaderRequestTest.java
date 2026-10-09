package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.server.packet.recv.HandlerChangeLineupLeaderCsReq;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LineupLeaderRequestTest {
  @TempDir Path root;
  final StoryLineWireTest fixture = new StoryLineWireTest();
  Capture session;

  static class Capture extends StoryLineWireTest.Session {
    final List<DynamicMessage> replies = new ArrayList<>();

    Capture(Player player) {
      super(player);
    }

    @Override
    public void send(BasePacket packet) {
      try {
        var wire = getProtocol().outbound(packet.getCmdId(), packet.getData().toByteArray());
        sendClientPacket("ChangeLineupLeaderScRsp", wire.body());
      } catch (Exception invalid) {
        throw new IllegalStateException(invalid);
      }
    }

    @Override
    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      replies.add(response);
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    fixture.root = root;
    fixture.prepare();
    fixture.configureSecondTrial(true);
    assertEquals(Retcode.SUCC, fixture.fixture.enter(fixture.player));
    session = new Capture(fixture.player);
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
  }

  byte[] request(long slot) throws Exception {
    var buffer = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    out.writeUInt64(8, slot);
    out.flush();
    return buffer.toByteArray();
  }

  int number(String name) {
    return (Integer)
        session.response.getField(session.response.getDescriptorForType().findFieldByName(name));
  }

  int nativeRequest(byte[] bytes) throws Exception {
    if (!VersionedPacketHandler.handle(session, 708, bytes)) {
      var core = session.getProtocol().inbound(708, bytes);
      new HandlerChangeLineupLeaderCsReq().handle(session, core.body());
    }
    assertEquals("ChangeLineupLeaderScRsp", session.responseName);
    return number("retcode");
  }

  @Test
  void invalidSlotsNeverAcknowledgeASuccessfulChange() throws Exception {
    for (long slot : List.of(2L, 4L, 0xffffffffL)) {
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), nativeRequest(request(slot)));
      assertEquals(0, fixture.player.getCurrentLineup().getLeader());
    }
  }

  @Test
  void pendingStoryTransitionDoesNotAcknowledgeARefusedLeaderChange() throws Exception {
    var state = fixture.player.getGameplay().getStoryLines();
    state.begin(
        StoryLineLifecycleTest.STORY,
        new StoryLineState.Place(fixture.player),
        new StoryLineState.Place(fixture.player));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), nativeRequest(request(1)));
    assertEquals(0, fixture.player.getCurrentLineup().getLeader());
  }

  @Test
  void successfulStoryChangeSelectsTheActualAvailableTrial() throws Exception {
    assertEquals(Retcode.SUCC.getVal(), nativeRequest(request(1)));
    assertEquals(1, fixture.player.getCurrentLineup().getLeader());
    assertEquals(1, number("slot"));
    assertEquals(
        StoryLineLifecycleTest.SPECIAL + 1, fixture.player.getCurrentLineup().getAvatars().get(1));
  }

  @Test
  void sameLeaderRetryDoesNotSaveOrCreateAnotherMissionEvent() throws Exception {
    assertEquals(Retcode.SUCC.getVal(), nativeRequest(request(1)));
    int saves = fixture.player.saves;
    assertEquals(Retcode.SUCC.getVal(), nativeRequest(request(1)));
    assertEquals(saves, fixture.player.saves);
    assertEquals(1, fixture.player.getCurrentLineup().getLeader());
  }

  @Test
  void wideUnsignedValueCannotTruncateIntoAValidSlot() throws Exception {
    assertEquals(Retcode.SUCC.getVal(), nativeRequest(request(1)));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), nativeRequest(request(0x100000000L)));
    assertEquals(1, fixture.player.getCurrentLineup().getLeader());
  }

  @Test
  void duplicateSlotCannotSelectTheLastWireValue() throws Exception {
    byte[] first = request(0), second = request(1);
    byte[] both = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, both, first.length, second.length);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), nativeRequest(both));
    assertEquals(0, fixture.player.getCurrentLineup().getLeader());
  }

  @Test
  void extraAndWrongWireFieldsCannotPerformAChange() throws Exception {
    byte[] valid = request(1);
    byte[] unknown = Arrays.copyOf(valid, valid.length + 2);
    unknown[valid.length] = 8;
    unknown[valid.length + 1] = 1;
    for (byte[] bytes : List.of(unknown, new byte[] {66, 1, 1}, new byte[] {64, (byte) 128})) {
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), nativeRequest(bytes));
      assertEquals(0, fixture.player.getCurrentLineup().getLeader());
    }
  }

  @Test
  void pendingStoryStateAlsoBlocksTheLegacyCoreHandler() throws Exception {
    fixture
        .player
        .getGameplay()
        .getStoryLines()
        .begin(
            StoryLineLifecycleTest.STORY,
            new StoryLineState.Place(fixture.player),
            new StoryLineState.Place(fixture.player));
    new HandlerChangeLineupLeaderCsReq()
        .handle(
            session,
            emu.lunarcore.proto.ChangeLineupLeaderCsReqOuterClass.ChangeLineupLeaderCsReq
                .newInstance()
                .setSlot(1)
                .toByteArray());
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), number("retcode"));
    assertEquals(0, fixture.player.getCurrentLineup().getLeader());
  }

  @Test
  void inactiveSessionDoesNotMutateOrRespond() throws Exception {
    session.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(session, 708, request(1)));
    assertTrue(session.replies.isEmpty());
    assertEquals(0, fixture.player.getCurrentLineup().getLeader());
  }

  @Test
  void anActualBattleBlocksLeaderChangesAndSameSlotRetries() throws Exception {
    var constructor =
        emu.lunarcore.game.battle.Battle.class.getDeclaredConstructor(
            Player.class, emu.lunarcore.game.player.lineup.PlayerLineup.class);
    constructor.setAccessible(true);
    var battle = constructor.newInstance(fixture.player, fixture.player.getCurrentLineup());
    var field = Player.class.getDeclaredField("battle");
    field.setAccessible(true);
    field.set(fixture.player, battle);
    for (int slot : List.of(0, 1))
      assertEquals(Retcode.SYSTEM_BUSY.getVal(), nativeRequest(request(slot)));
    assertSame(battle, fixture.player.getBattle());
    assertEquals(0, fixture.player.getCurrentLineup().getLeader());
  }

  @Test
  void missingTrialIdentityIsReportedAsBadPlayerData() throws Exception {
    fixture.player.getStoryLineManager().current().getRoster().set(1, 1199999);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), nativeRequest(request(1)));
    assertEquals(0, fixture.player.getCurrentLineup().getLeader());
  }

  @Test
  void legacyInvalidSlotDoesNotReturnSuccess() throws Exception {
    new HandlerChangeLineupLeaderCsReq()
        .handle(
            session,
            emu.lunarcore.proto.ChangeLineupLeaderCsReqOuterClass.ChangeLineupLeaderCsReq
                .newInstance()
                .setSlot(-1)
                .toByteArray());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), number("retcode"));
    assertEquals(0, fixture.player.getCurrentLineup().getLeader());
  }

  @Test
  void ordinaryFormalLeaderRetryDoesNotWriteTheParty() throws Exception {
    var ordinary = fixture.fixture.player(true);
    session = new Capture(ordinary);
    int saves = ordinary.saves;
    assertEquals(Retcode.SUCC.getVal(), nativeRequest(request(0)));
    assertEquals(saves, ordinary.saves);
    assertEquals(StoryLineLifecycleTest.ACTOR, ordinary.getCurrentLineup().getAvatars().get(0));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), nativeRequest(request(1)));
  }
}
