package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import java.util.*;

/** Per-connection delivery baseline. Durable mission history remains on the player. */
public final class MissionNotifications46 {
  private Map<Integer, DynamicMessage> missions = Map.of();
  private Set<Integer> finished = Set.of();
  private boolean initialized;

  /** Called before login is published, or before the first full query on a connection. */
  public void initialize(GameSession session) {
    synchronized (session.getPlayer()) {
      if (!initialized) {
        var snapshot = missionSnapshot(session, true);
        if (snapshot != null) remember(snapshot);
      }
    }
  }

  void snapshotSent(GameSession session, DynamicMessage snapshot) {
    // A failed query is not authoritative and must not discard a pending valid update.
    // The login wire snapshot separates historical completions from active rows. Keep
    // the full stored baseline so the next dialogue does not resend those old rows.
    synchronized (session.getPlayer()) {
      if (number(snapshot, "retcode") == 0) {
        var current = missionSnapshot(session, true);
        if (current != null) remember(current);
      }
    }
  }

  void send(GameSession session) {
    synchronized (session.getPlayer()) {
      if (session.getState() != SessionState.ACTIVE) return;
      var current = missionSnapshot(session, true);
      if (current == null) return;
      if (!initialized) {
        remember(current);
        return;
      }
      var delta = builder(session, "MissionSync");
      for (Object raw : (List<?>) value(current, "mission_list")) {
        var row = (DynamicMessage) raw;
        if (!row.equals(missions.get(number(row, "id")))) add(delta, "mission_list", row);
      }
      for (Object id : (List<?>) value(current, "finished_main_mission_id_list")) {
        if (!finished.contains(id)) add(delta, "finished_main_mission_id_list", id);
      }
      if (!delta.getAllFields().isEmpty()) {
        var notify = builder(session, "PlayerSyncScNotify");
        set(notify, "mission_sync", delta.build());
        session.sendClientPacket("PlayerSyncScNotify", notify.build().toByteArray());
      }
      // Commit only after transport accepts the packet. Removed/reopened daily objectives
      // also leave the baseline, so their next real completion can be delivered again.
      remember(current);
    }
  }

  private void remember(DynamicMessage snapshot) {
    var next = new HashMap<Integer, DynamicMessage>();
    for (Object raw : (List<?>) value(snapshot, "mission_list")) {
      var row = (DynamicMessage) raw;
      next.put(number(row, "id"), row);
    }
    var completed = new HashSet<Integer>();
    for (Object id : (List<?>) value(snapshot, "finished_main_mission_id_list"))
      completed.add((Integer) id);
    missions = Map.copyOf(next);
    finished = Set.copyOf(completed);
    initialized = true;
  }
}
