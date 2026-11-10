package emu.lunarcore.server.game;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.hkrpg.server.GameDiagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameDiagnosticsIntegrationTest {
  private final Logger logger = (Logger) LunarCore.getLogger();
  private final ListAppender<ILoggingEvent> capture = new ListAppender<>();

  @BeforeEach void startCapture() {
    capture.start();
    logger.addAppender(capture);
  }

  @AfterEach void stopCapture() {
    logger.detachAppender(capture);
    capture.stop();
  }

  @Test void distinctUnsupportedWireCommandsAreNamedButRepeatsAreQuiet() {
    GameDiagnostics.flush(true);
    var session = new ReplyCorrelation46Test.Session();
    for (int i = 0; i < 15; i++) session.request("GetFiveDimMoneyDataCsReq", new byte[0]);
    session.request("GetFiveDimGameDataCsReq", new byte[0]);
    var events = capture.list.stream().map(ILoggingEvent::getFormattedMessage)
        .filter(message -> message.contains("inbound command:")).toList();
    assertEquals(2, events.size());
    assertTrue(events.get(0).contains("GetFiveDimMoneyDataCsReq"));
    assertTrue(events.get(1).contains("GetFiveDimGameDataCsReq"));
    assertTrue(events.get(0).contains("session="));
    assertTrue(events.get(0).contains("at="));
    GameDiagnostics.flush(true);
    assertTrue(capture.list.stream().map(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains("topic=UNHANDLED repeated=14")
            && message.contains("2274/GetFiveDimMoneyDataCsReq x14")));
  }

  @Test void badRequestHeaderNamesTheFailedStageAndCommand() {
    var session = new ReplyCorrelation46Test.Session();
    session.request("BuyGoodsCsReq", new byte[] {8, 1, 8, 2});
    assertTrue(capture.list.stream().map(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains("step=reply-header")
            && message.contains("BuyGoodsCsReq") && message.contains("Invalid request packet ID")));
  }

  @Test void recurringTickFailuresKeepOneSanitizedStackAndDoNotStopOtherPlayers() throws Exception {
    var registry = new Int2ObjectOpenHashMap<Player>();
    var ticks = new java.util.concurrent.atomic.AtomicInteger();
    registry.put(123, new Player() {
      @Override public void onTick(long timestamp, long delta) {
        throw new IllegalStateException("token=private-for-test");
      }
    });
    registry.put(124, new Player() {
      @Override public void onTick(long timestamp, long delta) { ticks.incrementAndGet(); }
    });
    var server = GameServerTickLockTest.detached(registry);
    for (int i = 0; i < 12; i++) GameServerTickLockTest.tick(server);
    assertEquals(12, ticks.get());
    var errors = capture.list.stream()
        .filter(row -> row.getFormattedMessage().contains("Player tick error")).toList();
    assertEquals(1, errors.size());
    var error = errors.getFirst();
    assertNull(error.getThrowableProxy());
    assertTrue(error.getFormattedMessage().contains("IllegalStateException"));
    assertTrue(error.getFormattedMessage().contains(".onTick("));
    assertFalse(error.getFormattedMessage().contains("private-for-test"));
  }
}
