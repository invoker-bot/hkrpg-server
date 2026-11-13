package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import emu.lunarcore.LunarCore;
import emu.lunarcore.server.packet.Retcode;
import org.hkrpg.server.GameDiagnostics;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercise real admission failures and the shared bounded diagnostic window. */
class BoxingClubRejections46Test {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
  final BoxingClubRounds46Test fixture = new BoxingClubRounds46Test();
  final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  Logger logger;

  @BeforeEach
  void prepare() throws Exception {
    fixture.root = root;
    fixture.load();
    GameDiagnostics.flush(true);
    logger = (Logger) LunarCore.getLogger();
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void restore() throws Exception {
    logger.detachAppender(logs);
    GameDiagnostics.flush(true);
    fixture.restore();
  }

  String output() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  void denied(String reason) throws Exception {
    fixture.match(1, 1001);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), fixture.number(fixture.session.response, "retcode"));
    assertTrue(output().contains("reason=" + reason), output());
    assertTrue(output().contains("command=MatchBoxingClubOpponentCsReq subject=1"), output());
    assertTrue(output().contains("panel=50000 module=5000002"), output());
    assertEquals(0, fixture.session.actor.saves);
    assertEquals(0, fixture.session.actor.grants);
  }

  @Test
  void aClosedWindowIsDiagnosedWithoutStartingAMatch() throws Exception {
    GameplayDefinitions.windows.clear();
    denied("activity-window-closed");
  }

  @ParameterizedTest
  @ValueSource(strings = {"level", "mission"})
  void originalUnlockGatesRemainEnforcedAndAreDiagnosed(String gate) throws Exception {
    if (gate.equals("level")) fixture.session.actor.setLevel(20);
    else fixture.session.actor.getGameplay().getMainMissions().remove(1011001);
    denied("unlock-condition");
  }

  @Test
  void unknownChallengeIsDiagnosedWithoutTreatingItAsAFirstSeasonEntry() throws Exception {
    fixture.match(999, 1001);
    assertNotEquals(0, fixture.number(fixture.session.response, "retcode"));
    assertTrue(output().contains("subject=999"), output());
    assertTrue(output().contains("reason=unknown-challenge"), output());
    assertEquals(0, fixture.session.actor.saves);
  }

  @Test
  void malformedWireIsDiagnosedWithoutLoggingItsContents() throws Exception {
    assertTrue(VersionedPacketHandler.handle(fixture.session, 4205, new byte[] {(byte) 0x80}));
    assertNotEquals(0, fixture.number(fixture.session.response, "retcode"));
    assertTrue(output().contains("reason=invalid-request"), output());
    assertEquals(0, fixture.session.actor.saves);
  }

  @Test
  void repeatedFailuresFoldButADifferentReasonGetsItsOwnDetail() throws Exception {
    GameplayDefinitions.windows.clear();
    fixture.match(1, 1001);
    fixture.match(1, 1001);
    fixture.session.actor.setLevel(20);
    fixture.match(1, 1001);
    GameDiagnostics.flush(true);
    assertEquals(2, logs.list.stream().filter(e -> e.getFormattedMessage().contains("Gameplay result:")).count());
    assertTrue(output().contains("reason=activity-window-closed"), output());
    assertTrue(output().contains("reason=unlock-condition"), output());
    assertTrue(output().contains("Diagnostic repeats"), output());
    assertTrue(output().contains("repeated=1"), output());
  }
}
