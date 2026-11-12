package org.hkrpg.server;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import org.junit.jupiter.api.*;

class ProgressFailureDiagnosticsTest {
  final Logger logger = (Logger) LunarCore.getLogger();
  final ListAppender<ILoggingEvent> capture = new ListAppender<>();

  @BeforeEach void start() {
    GameDiagnostics.flush(true);
    capture.start();
    logger.addAppender(capture);
  }

  @AfterEach void stop() {
    GameDiagnostics.flush(true);
    logger.detachAppender(capture);
    capture.stop();
  }

  @Test void actionFailureKeepsTheStepAndStageWhileSuppressingRepeatedPrivateExceptions() {
    var player = new Player();
    var failure = new IllegalStateException("token=private-fixture-value");
    for (int i = 0; i < 12; i++)
      GameDiagnostics.storyActionInterrupted(player, 198869201, 0, "reserve-save", failure);
    GameDiagnostics.storyActionInterrupted(player, 198869201, 1, "reserve-save", failure);
    GameDiagnostics.storyActionInterrupted(player, 198869201, 0, "pending-cursor", null);
    var details = capture.list.stream()
        .filter(row -> row.getFormattedMessage().contains("Story action interrupted:")).toList();
    assertEquals(3, details.size());
    assertTrue(details.getFirst().getFormattedMessage().contains("sub=198869201 index=0 stage=reserve-save error=IllegalStateException"));
    assertTrue(details.get(1).getFormattedMessage().contains("index=1"));
    assertTrue(details.get(2).getFormattedMessage().contains("stage=pending-cursor"));
    assertTrue(details.stream().allMatch(row -> row.getThrowableProxy() == null
        && !row.getFormattedMessage().contains("private-fixture-value")));
    GameDiagnostics.flush(true);
    assertTrue(capture.list.stream().anyMatch(row -> row.getFormattedMessage().contains("topic=PROGRESS repeated=11")));
  }

  @Test void questPendingRetriesAreCountedAndDifferentQuestsAndReasonsStayDistinct() {
    var player = new Player();
    for (int i = 0; i < 12; i++)
      GameDiagnostics.questRewardInterrupted(player, 198869391, 198869392, "pending-claim");
    GameDiagnostics.questRewardInterrupted(player, 198869394, 198869392, "pending-claim");
    GameDiagnostics.questRewardInterrupted(player, 198869391, 198869392, "partial-delivery");
    var details = capture.list.stream().map(ILoggingEvent::getFormattedMessage)
        .filter(message -> message.contains("Quest reward incomplete:")).toList();
    assertEquals(3, details.size());
    assertTrue(details.getFirst().contains("quest=198869391 reward=198869392 reason=pending-claim reservation=claiming"));
    assertTrue(details.get(1).contains("quest=198869394"));
    assertTrue(details.get(2).contains("reason=partial-delivery"));
    assertTrue(details.stream().allMatch(message -> message.contains("session=") && message.contains("at=")));
    GameDiagnostics.flush(true);
    assertTrue(capture.list.stream().anyMatch(row -> row.getFormattedMessage().contains("topic=PROGRESS repeated=11")));
  }
}
