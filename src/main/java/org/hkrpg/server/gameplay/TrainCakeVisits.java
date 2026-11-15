package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Captures in other players' rooms belong exclusively to the visiting player. */
public final class TrainCakeVisits {
  public static Retcode capture(Player player, TrainCakeRoomBatch room, int cat, long now) {
    synchronized (player) {
      try {
        String source = TrainCakeCatch.source();
        var own = TrainCakeCatch.snapshot(player);
        var previous = checked(player, source);
        validateWeek(previous, now);
        if (room == null || room.ownerUid() == player.getUid() || !room.valid(source, now)
            || room.spots().stream().noneMatch(spot -> spot.getCat() == cat))
          return Retcode.REQ_PARA_INVALID;
        var rooms = previous != null && previous.refreshAt() > now
            ? new TreeMap<>(previous.rooms()) : new TreeMap<Integer, Set<Integer>>();
        var caught = new TreeSet<>(rooms.getOrDefault(room.ownerUid(), Set.of()));
        if (caught.contains(cat)) return Retcode.SUCC;
        if (TrainCakeCatch.weeklyRemaining(player, now) <= 0) return Retcode.INTERACT_TYPE_INVALID;
        var counts = new TreeMap<>(previous == null ? Map.<Integer, Integer>of() : previous.counts());
        int visitorCount = counts.getOrDefault(cat, 0);
        if (visitorCount + own.getCounts().getOrDefault(cat, 0) >= 13)
          return Retcode.INTERACT_TYPE_INVALID;
        caught.add(cat); rooms.put(room.ownerUid(), caught);
        counts.put(cat, visitorCount + 1);
        var next = new TrainCakeVisitState(source, room.refreshAt(), rooms, counts);
        if (!next.valid(player.getUid(), source)) return Retcode.PLAYER_DATA_ERROR;
        player.getGameplay().trainCakeVisits(next);
        try {
          player.save();
          return Retcode.SUCC;
        } catch (RuntimeException failed) {
          player.getGameplay().trainCakeVisits(previous);
          org.hkrpg.server.GameDiagnostics.saveFailed(player, "train-cake-visit", cat, 0, failed);
          return Retcode.SERVER_INTERNAL_ERROR;
        }
      } catch (RuntimeException invalid) {
        return Retcode.PLAYER_DATA_ERROR;
      }
    }
  }

  private static TrainCakeVisitState checked(Player player, String source) {
    var state = player.getGameplay().getTrainCakeVisits();
    if (state != null && !state.valid(player.getUid(), source))
      throw new IllegalStateException("Invalid saved visitor captures");
    return state;
  }

  public static Map<Integer, Integer> counts(Player player) {
    var state = checked(player, TrainCakeCatch.source());
    return state == null ? Map.of() : state.counts();
  }

  public static Map<Integer, Integer> totalCounts(Player player) {
    var all = new TreeMap<>(TrainCakeCatch.snapshot(player).getCounts());
    counts(player).forEach((cat, count) -> all.merge(cat, count, Integer::sum));
    if (all.values().stream().anyMatch(count -> count > 13))
      throw new IllegalStateException("Invalid combined cat count");
    return Collections.unmodifiableMap(all);
  }

  public static Map<Integer, Set<Integer>> records(Player player, long now) {
    var state = checked(player, TrainCakeCatch.source());
    validateWeek(state, now);
    return state == null || state.refreshAt() <= now ? Map.of() : state.rooms();
  }

  private static void validateWeek(TrainCakeVisitState state, long now) {
    if (state != null && state.refreshAt() > now && state.refreshAt() != TrainCakeCatch.nextRefresh(now))
      throw new IllegalStateException("Invalid visitor week");
  }

  static int weeklyCount(Player player, long now, String source) {
    var state = checked(player, source);
    if (state == null || state.refreshAt() <= now) return 0;
    validateWeek(state, now);
    return state.rooms().values().stream().mapToInt(Set::size).sum();
  }
  private TrainCakeVisits() {}
}
