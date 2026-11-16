package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.SessionState;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An online client must be told about the server's actual 04:00 day boundary. */
class DailyRefresh46Test {
  static final Instant BEFORE = Instant.parse("2026-11-15T19:59:59Z");
  static final Instant MONDAY = Instant.parse("2026-11-15T20:00:00Z");
  static final Instant TUESDAY = Instant.parse("2026-11-16T20:00:00Z");

  static class Session extends Gameplay46HandlerTest.Session {
    final List<DynamicMessage> notifications = new ArrayList<>();
    boolean failNext;
    Session() { player.setSession(this); }
    @Override public void sendClientPacket(String name, byte[] bytes) {
      if (name.equals("DailyRefreshNotify") && failNext) {
        failNext = false;
        throw new IllegalStateException("injected send failure");
      }
      super.sendClientPacket(name, bytes);
      if (name.equals("DailyRefreshNotify")) notifications.add(response);
    }
  }

  // Reflection keeps this regression executable on the old version, before the missing
  // refresh implementation is introduced.
  static void refresh(Session session, Instant instant) throws Exception {
    Class.forName("org.hkrpg.server.protocol.DailyRefresh46")
        .getMethod("refresh", Player.class, Instant.class).invoke(null, session.player, instant);
  }

  @Test void notifiesExactlyOnceOnTheRealDailyBoundaryWithoutChangingProgress() throws Exception {
    var session = new Session();
    var before = new Gson().toJson(session.player.getGameplay());
    refresh(session, BEFORE);
    refresh(session, BEFORE);
    assertTrue(session.notifications.isEmpty(), "Login and ordinary ticks do not imply a rollover");
    refresh(session, MONDAY);
    assertEquals(1, session.notifications.size());
    assertEquals(Math.toIntExact(LocalDate.of(2026, 11, 16).toEpochDay()),
        Gameplay46.number(session.notifications.getFirst(), "MDIIJKBHBBI"));
    refresh(session, MONDAY.plusSeconds(30));
    assertEquals(1, session.notifications.size(), "Repeated ticks must not repeat the refresh");
    refresh(session, TUESDAY);
    assertEquals(2, session.notifications.size());
    assertEquals(Math.toIntExact(LocalDate.of(2026, 11, 17).toEpochDay()),
        Gameplay46.number(session.notifications.getLast(), "MDIIJKBHBBI"));
    refresh(session, MONDAY);
    refresh(session, TUESDAY);
    assertEquals(2, session.notifications.size(), "A backwards clock correction cannot duplicate a day");
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
  }

  @Test void aFailedSendCanBeRetriedAndInactiveSessionsAreNotNotified() throws Exception {
    var session = new Session();
    refresh(session, BEFORE);
    session.failNext = true;
    assertThrows(InvocationTargetException.class, () -> refresh(session, MONDAY));
    assertTrue(session.notifications.isEmpty());
    refresh(session, MONDAY);
    assertEquals(1, session.notifications.size());
    session.setState(SessionState.INACTIVE);
    refresh(session, TUESDAY);
    assertEquals(1, session.notifications.size());
  }
}
