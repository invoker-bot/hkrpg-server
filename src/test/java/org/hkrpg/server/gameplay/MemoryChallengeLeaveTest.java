package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.server.packet.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;

class MemoryChallengeLeaveTest {
  final MissionChallengeWinTest fixture = new MissionChallengeWinTest();
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");

  @BeforeEach
  void resources() {
    fixture.resources();
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
  }

  class Session extends MissionChallengeWinTest.Challenger.SilentSession {
    final MissionChallengeWinTest.Challenger player;
    byte[] reply;
    String name;

    Session(MissionChallengeWinTest.Challenger player) {
      this.player = player;
    }

    public emu.lunarcore.game.player.Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return protocol;
    }

    public SessionState getState() {
      return SessionState.ACTIVE;
    }

    public void sendClientPacket(String name, byte[] bytes) {
      this.name = name;
      reply = bytes;
    }
  }

  int leave(Session session, byte[] data) throws Exception {
    assertTrue(
        MemoryChallenge46.handle(session, protocol.clientCommand("LeaveChallengeCsReq"), data));
    assertEquals("LeaveChallengeScRsp", session.name);
    var message = protocol.clientSchema().parse(session.name, session.reply);
    assertTrue(message.getUnknownFields().asMap().isEmpty());
    return (Integer) message.getField(message.getDescriptorForType().findFieldByName("retcode"));
  }

  @Test
  void successfulNativeLeaveReturnsSuccessAndClearsTheChallenge() throws Exception {
    var p = fixture.player(false);
    var session = new Session(p);
    assertEquals(0, leave(session, new byte[0]));
    assertNull(p.getInstance());
    assertEquals(0, p.getCurrentLineup().getExtraLineupType());
  }

  @Test
  void rejectedReturnReportsFailureAndPreservesTheInstanceAndParty() throws Exception {
    var p = fixture.player(false);
    p.rejectEnter = true;
    var instance = p.getInstance();
    assertEquals(Retcode.FAIL.getVal(), leave(new Session(p), new byte[0]));
    assertSame(instance, p.getInstance());
    assertEquals(1, p.getCurrentLineup().getExtraLineupType());
  }

  @Test
  void repeatedLeaveCannotClaimAnotherSuccess() throws Exception {
    var p = fixture.player(false);
    var session = new Session(p);
    assertEquals(0, leave(session, new byte[0]));
    assertEquals(Retcode.CHALLENGE_NOT_DOING.getVal(), leave(session, new byte[0]));
    assertNull(p.getInstance());
  }

  @Test
  void unexpectedRequestFieldsLeaveTheActiveChallengeIntact() throws Exception {
    var p = fixture.player(false);
    var instance = p.getInstance();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), leave(new Session(p), new byte[] {8, 1}));
    assertSame(instance, p.getInstance());
    assertEquals(1, p.getCurrentLineup().getExtraLineupType());
  }

  @Test
  void challengeWithChangedEntranceCannotClearAnUnrelatedInstance() throws Exception {
    var p = fixture.player(false);
    var instance = p.getInstance();
    p.entry++;
    assertEquals(Retcode.CHALLENGE_NOT_DOING.getVal(), leave(new Session(p), new byte[0]));
    assertSame(instance, p.getInstance());
    assertEquals(1, p.getCurrentLineup().getExtraLineupType());
  }

  @Test
  void nativeHistoryIncludesStoredMemoryStarsWithoutUnsupportedBossExtensions() throws Exception {
    var p = fixture.player(false);
    var memory = new emu.lunarcore.game.challenge.ChallengeHistory();
    memory.setChallengeId(MissionChallengeWinTest.CHALLENGE);
    memory.setStars(5);
    memory.getExtraData();
    p.getChallengeManager().getHistory().put(memory.getChallengeId(), memory);
    int bossId = MissionChallengeWinTest.CHALLENGE + 1;
    var old = emu.lunarcore.data.GameData.getChallengeExcelMap().get(bossId);
    var boss =
        new com.google.gson.Gson()
            .fromJson("{\"ID\":" + bossId + "}", emu.lunarcore.data.excel.ChallengeExcel.class);
    boss.setBossExcel(new emu.lunarcore.data.excel.ChallengeBossExtraExcel());
    emu.lunarcore.data.GameData.getChallengeExcelMap().add(boss);
    var history = new emu.lunarcore.game.challenge.ChallengeHistory();
    history.setChallengeId(bossId);
    history.getExtraData();
    p.getChallengeManager().getHistory().put(bossId, history);
    try {
      var session = new Session(p);
      assertTrue(
          MemoryChallenge46.handle(
              session, protocol.clientCommand("GetChallengeCsReq"), new byte[0]));
      assertEquals("GetChallengeScRsp", session.name);
      var reply = protocol.clientSchema().parse(session.name, session.reply);
      var rows =
          (java.util.List<?>)
              reply.getField(reply.getDescriptorForType().findFieldByName("challenge_list"));
      var matches =
          rows.stream()
              .map(value -> (com.google.protobuf.DynamicMessage) value)
              .filter(
                  value ->
                      (Integer)
                              value.getField(
                                  value.getDescriptorForType().findFieldByName("challenge_id"))
                          == memory.getChallengeId())
              .toList();
      assertEquals(1, matches.size());
      assertTrue(
          rows.stream()
              .map(value -> (com.google.protobuf.DynamicMessage) value)
              .noneMatch(
                  value ->
                      (Integer)
                              value.getField(
                                  value.getDescriptorForType().findFieldByName("challenge_id"))
                          == bossId));
      var row = matches.getFirst();
      assertEquals(
          memory.getChallengeId(),
          row.getField(row.getDescriptorForType().findFieldByName("challenge_id")));
      assertEquals(5, row.getField(row.getDescriptorForType().findFieldByName("star")));
      assertFalse(row.hasField(row.getDescriptorForType().findFieldByName("stage_info")));
      assertSame(memory, p.getChallengeManager().getHistory().get(memory.getChallengeId()));
    } finally {
      emu.lunarcore.data.GameData.getChallengeExcelMap().keySet().remove(bossId);
      if (old != null) emu.lunarcore.data.GameData.getChallengeExcelMap().add(old);
    }
  }

  @Test
  void nativeHistoryRejectsUnknownRequestFieldsWithoutTouchingTheChallenge() throws Exception {
    var p = fixture.player(false);
    var instance = p.getInstance();
    var session = new Session(p);
    assertTrue(
        MemoryChallenge46.handle(
            session, protocol.clientCommand("GetChallengeCsReq"), new byte[] {8, 1}));
    var reply = protocol.clientSchema().parse(session.name, session.reply);
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        reply.getField(reply.getDescriptorForType().findFieldByName("retcode")));
    assertSame(instance, p.getInstance());
  }
}
