package org.hkrpg.server.protocol;

import emu.lunarcore.LunarCore;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import java.util.WeakHashMap;

/** Bounded rejection metadata only; no packet dumps, ability strings, credentials or inventory. */
final class SceneActionDiagnostics46 {
  private static final WeakHashMap<GameSession, Budget> BUDGETS = new WeakHashMap<>();

  static final class Budget {
    private long start;
    private int emitted;
    boolean allow(long now) {
      if (emitted == 0 || now - start >= 60_000_000_000L) {
        start = now;
        emitted = 0;
      }
      if (emitted >= 8) return false;
      emitted++;
      return true;
    }
  }

  static void rejected(GameSession session, String command, String reason, Retcode error,
      int use, int actor, int hits, int assists) {
    if (error == Retcode.SUCC) return;
    if (!allow(session)) return;
    var player = session.getPlayer();
    LunarCore.getLogger().warn(
        "Scene action rejected: command={} uid={} floor={} reason={} retcode={} use={} actor={} hits={} assists={}",
        command, player.getUid(), player.getFloorId(), reason, error.getVal(),
        Integer.toUnsignedString(use), actor, hits, assists);
  }

  static void battleRejected(GameSession session, String reason, int battleId, int stageId,
      java.util.List<Integer> reportedAvatars) {
    if (!allow(session)) return;
    var player = session.getPlayer();
    var active = player.getBattle();
    LunarCore.getLogger().warn(
        "Battle result rejected: uid={} floor={} reason={} battle={} stage={} activeBattle={} participants={} reported={}",
        player.getUid(), player.getFloorId(), reason, battleId, stageId,
        active == null ? 0 : active.getId(),
        active == null ? java.util.Set.of() : active.getParticipantIds().stream().sorted().limit(8).toList(),
        reportedAvatars.stream().limit(8).toList());
  }

  /** Bounded gameplay IDs only; imported field names are not assumed to be semantic evidence. */
  static void rogueEntry(GameSession session, int area, java.util.List<?> regular,
      java.util.List<?> trials, int aeon, java.util.List<?> other,
      java.util.Set<Integer> unknown, boolean shapeValid, Retcode result) {
    if (!allow(session)) return;
    LunarCore.getLogger().info(
        "Second tutorial start: uid={} area={} regular={} trials={} aeon={} other={} unknownFields={} shapeValid={} retcode={}",
        session.getPlayer().getUid(), area, regular.stream().limit(4).toList(),
        trials.stream().limit(4).toList(), aeon, other.stream().limit(4).toList(),
        unknown.stream().sorted().limit(12).toList(), shapeValid, result.getVal());
  }

  private static boolean allow(GameSession session) {
    synchronized (BUDGETS) {
      var budget = BUDGETS.get(session);
      if (budget == null) {
        if (BUDGETS.size() >= 256) return false;
        budget = new Budget();
        BUDGETS.put(session, budget);
      }
      return budget.allow(System.nanoTime());
    }
  }

  private SceneActionDiagnostics46() {}
}
