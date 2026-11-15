package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.SessionState;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.TrainCakeRoomRegistry;
import org.junit.jupiter.api.Test;

/** Membership capacity, switching and disconnect cleanup are checked without fake players. */
class TrainCakeRoomRegistry46Test {
  static final class Session extends Gameplay46HandlerTest.Session {
    final int uid;
    final Player actor;
    Session(int uid) { this.uid = uid; actor = new Player() { public int getUid() { return uid; } }; }
    @Override public Player getPlayer() { return actor; }
  }

  @Test void membersShareOneOwnerRoomAndTheFifthMemberIsRefused() {
    var rooms = new TrainCakeRoomRegistry();
    var sessions = new ArrayList<Session>();
    for (int uid = 1; uid <= 5; uid++) sessions.add(new Session(uid));
    for (int i = 0; i < 4; i++) {
      var joined = rooms.join(sessions.get(i), 1);
      assertNotNull(joined);
      assertEquals(1, joined.ownerUid());
      assertEquals(i + 1, joined.members().size());
    }
    assertNull(rooms.join(sessions.get(4), 1));
    assertNull(rooms.current(sessions.get(4)));
    assertEquals(4, rooms.current(sessions.get(0)).members().size());
  }

  @Test void switchDisconnectAndRejoinDoNotLeaveGhostMembers() {
    var rooms = new TrainCakeRoomRegistry();
    var first = new Session(1); var second = new Session(2); var third = new Session(3);
    rooms.join(first, 1); rooms.join(second, 1);
    assertEquals(2, rooms.current(first).members().size());
    assertEquals(2, rooms.join(second, 2).ownerUid());
    assertEquals(List.of(first), rooms.current(first).members());
    assertEquals(List.of(second), rooms.current(second).members());
    second.setState(SessionState.INACTIVE);
    assertNull(rooms.current(second));
    assertNotNull(rooms.join(third, 2));
    assertEquals(List.of(third), rooms.current(third).members());
    assertEquals(2, rooms.leave(third).ownerUid());
    assertNull(rooms.current(third));
  }

  @Test void concurrentEntriesNeverExceedFourAndDuplicateSessionsDoNotDuplicateAPlayer() throws Exception {
    var rooms = new TrainCakeRoomRegistry();
    var workers = Executors.newFixedThreadPool(8);
    try {
      var entries = new ArrayList<Callable<TrainCakeRoomRegistry.Membership>>();
      for (int uid = 1; uid <= 20; uid++) {
        var player = new Session(uid);
        entries.add(() -> rooms.join(player, 777));
      }
      var results = workers.invokeAll(entries);
      assertEquals(4, results.stream().filter(f -> { try { return f.get() != null; }
        catch (Exception e) { throw new AssertionError(e); } }).count());
      var duplicate = new Session(1);
      var existing = results.get(0).get();
      if (existing != null) {
        assertNull(rooms.join(duplicate, 777), "A second live session cannot impersonate a member");
      }
    } finally { workers.shutdownNow(); }
  }
}
