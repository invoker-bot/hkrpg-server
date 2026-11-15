package org.hkrpg.server.gameplay;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import java.util.*;

/** Live membership only; room batches and collection records remain in the database. */
public final class TrainCakeRoomRegistry {
  public record Membership(int ownerUid, List<GameSession> members) {}
  private final Map<Integer, LinkedHashMap<Integer, GameSession>> rooms = new HashMap<>();
  private final IdentityHashMap<GameSession, Integer> owners = new IdentityHashMap<>();
  private final Map<Integer, GameSession> sessions = new HashMap<>();
  private final IdentityHashMap<GameSession, byte[]> motions = new IdentityHashMap<>();

  public synchronized boolean canJoin(GameSession session, int ownerUid) {
    prune();
    if (ownerUid <= 0 || !active(session)) return false;
    var existing = sessions.get(session.getPlayer().getUid());
    if (existing != null && existing != session) return false;
    var previous = owners.get(session);
    var destination = rooms.get(ownerUid);
    return Objects.equals(previous, ownerUid) || destination == null || destination.size() < 4;
  }

  public synchronized Membership join(GameSession session, int ownerUid) {
    prune();
    if (ownerUid <= 0 || !active(session)) return null;
    int uid = session.getPlayer().getUid();
    var existing = sessions.get(uid);
    if (existing != null && existing != session) return null;
    var previous = owners.get(session);
    var destination = rooms.get(ownerUid);
    if (!Objects.equals(previous, ownerUid) && destination != null && destination.size() >= 4)
      return null;
    if (previous != null && previous != ownerUid) remove(session, previous, uid);
    destination = rooms.computeIfAbsent(ownerUid, ignored -> new LinkedHashMap<>());
    destination.put(uid, session);
    owners.put(session, ownerUid); sessions.put(uid, session);
    return snapshot(ownerUid);
  }

  public synchronized Membership current(GameSession session) {
    prune();
    var owner = owners.get(session);
    return owner == null ? null : snapshot(owner);
  }

  public synchronized Membership members(int ownerUid) {
    prune();
    return rooms.containsKey(ownerUid) ? snapshot(ownerUid) : null;
  }

  public synchronized boolean motion(GameSession session, byte[] data) {
    prune();
    if (!owners.containsKey(session) || data == null) return false;
    motions.put(session, data.clone());
    return true;
  }

  public synchronized byte[] motion(GameSession session) {
    prune();
    var data = motions.get(session);
    return data == null ? null : data.clone();
  }

  public synchronized Membership leave(GameSession session) {
    prune();
    var owner = owners.get(session);
    if (owner == null) return null;
    int uid = session.getPlayer().getUid();
    remove(session, owner, uid);
    return snapshot(owner);
  }

  private Membership snapshot(int owner) {
    var members = rooms.get(owner);
    return new Membership(owner, members == null ? List.of() : List.copyOf(members.values()));
  }

  private static boolean active(GameSession session) {
    return session != null && session.getState() == SessionState.ACTIVE
        && session.getPlayer() != null && session.getPlayer().getUid() > 0;
  }

  private void prune() {
    for (var row : new ArrayList<>(sessions.entrySet())) {
      var session = row.getValue();
      if (!active(session) || session.getPlayer().getUid() != row.getKey()) {
        var owner = owners.get(session);
        if (owner != null) remove(session, owner, row.getKey());
      }
    }
  }

  private void remove(GameSession session, int owner, int uid) {
    var room = rooms.get(owner);
    if (room != null) {
      room.remove(uid, session);
      if (room.isEmpty()) rooms.remove(owner);
    }
    owners.remove(session);
    sessions.remove(uid, session);
    motions.remove(session);
  }
}
