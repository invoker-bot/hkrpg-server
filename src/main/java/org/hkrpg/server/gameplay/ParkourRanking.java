package org.hkrpg.server.gameplay;

import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import dev.morphia.query.filters.Filters;
import emu.lunarcore.GameConstants;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.friends.Friendship;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Saved personal and mutually accepted friend records; the client adds its resource NPC rows. */
public final class ParkourRanking {
  public record Entry(int uid, int time, int ball) {}

  public record Board(int level, List<Entry> entries) {}

  public record Snapshot(Board aggregate, List<Board> levels) {}

  private static final Comparator<Entry> ORDER =
      Comparator.comparingInt(Entry::time).thenComparingInt(Entry::uid);

  public static Optional<Snapshot> snapshot(Player player) {
    var database = LunarCore.getGameDatabase();
    return snapshot(player, database == null ? null : database.getDatastore());
  }

  static Optional<Snapshot> snapshot(Player player, Datastore persisted) {
    synchronized (player) {
      var levels = new TreeSet<Integer>();
      for (var row : GameplayDefinitions.parkourRankings) {
        if (row == null
            || row.RailBallID() <= 0
            || row.LevelBestRecordList() == null
            || row.LevelBestRecordList().isEmpty()) return Optional.empty();
        for (var time : row.LevelBestRecordList()) {
          if (time == null
              || time.MLDLOJJBIJD() <= 0
              || time.GAFENHHEBPG() < 0
              || !GameplayDefinitions.parkourLevels.containsKey(time.MLDLOJJBIJD()))
            return Optional.empty();
          levels.add(time.MLDLOJJBIJD());
        }
      }
      if (levels.isEmpty()) return Optional.empty();
      var profiles = new TreeMap<Integer, Player>();
      if (player.getUid() > 0) profiles.put(player.getUid(), player);
      if (persisted != null && player.getUid() > 0) {
        var friends = new TreeSet<Integer>();
        try (var links =
            persisted
                .find(Friendship.class)
                .filter(Filters.eq("ownerUid", player.getUid()), Filters.eq("isFriend", true))
                .iterator(new FindOptions().limit(GameConstants.MAX_FRIENDSHIPS + 1))) {
          int count = 0;
          while (links.hasNext()) {
            if (++count > GameConstants.MAX_FRIENDSHIPS) return Optional.empty();
            var link = links.next();
            int uid = link.getFriendUid();
            if (uid <= 0 || uid == player.getUid() || !canonical(link, player.getUid(), uid))
              continue;
            friends.add(uid);
          }
        }
        for (int uid : friends) {
          var reciprocal =
              persisted
                  .find(Friendship.class)
                  .filter(Filters.eq("_id", Friendship.generateUniqueKey(uid, player.getUid())))
                  .first();
          if (!canonical(reciprocal, uid, player.getUid())) continue;
          // Load a detached persisted document, never lock the other online Player instance.
          var profile = persisted.find(Player.class).filter(Filters.eq("_id", uid)).first();
          if (profile != null && profile.getUid() == uid) profiles.put(uid, profile);
        }
      }
      var boards = new TreeMap<Integer, List<Entry>>();
      for (int level : levels) boards.put(level, new ArrayList<>());
      var aggregates = new ArrayList<Entry>();
      for (var profile : profiles.values()) {
        boolean complete = true;
        int total = 0, commonBall = 0;
        for (int level : levels) {
          var record = profile.getGameplay().getParkour().getRecords().get(level);
          if (!valid(record)) {
            complete = false;
            continue;
          }
          boards
              .get(level)
              .add(new Entry(profile.getUid(), record.getTime(), record.getBestBall()));
          if (commonBall == 0) commonBall = record.getBestBall();
          if (commonBall != record.getBestBall()) complete = false;
          try {
            total = Math.addExact(total, record.getTime());
          } catch (ArithmeticException e) {
            complete = false;
          }
        }
        if (complete) aggregates.add(new Entry(profile.getUid(), total, commonBall));
      }
      var result =
          boards.entrySet().stream()
              .map(e -> new Board(e.getKey(), e.getValue().stream().sorted(ORDER).toList()))
              .toList();
      return Optional.of(
          new Snapshot(new Board(0, aggregates.stream().sorted(ORDER).toList()), result));
    }
  }

  private static boolean canonical(Friendship link, int owner, int friend) {
    return link != null
        && link.isFriend()
        && link.getOwnerUid() == owner
        && link.getFriendUid() == friend
        && link.getId() == Friendship.generateUniqueKey(owner, friend);
  }

  private static boolean valid(GameplayState.ParkourRecord record) {
    return record != null
        && record.getTime() > 0
        && record.getTime() <= 86_400_000
        && record.getRank() > 0
        && record.getRank() <= 32
        && record.getBestBall() > 0
        && GameplayDefinitions.parkourBalls.containsKey(record.getBestBall());
  }

  private ParkourRanking() {}
}
