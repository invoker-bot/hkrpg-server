package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import java.util.*;
import java.util.regex.Pattern;

/** Resource-authored story intervals; no client input is evaluated as code. */
public final class StoryLineConditions {
  private record Interval(int start, int end) {}

  private static final Pattern INTERVAL =
      Pattern.compile("\\[BetweenSubMission:([1-9][0-9]{0,9}),([1-9][0-9]{0,9})\\]");

  public static boolean matches(Player player, String expression) {
    var intervals = parse(expression);
    if (player == null || intervals == null) return false;
    synchronized (player) {
      return intervals.stream()
          .anyMatch(
              interval ->
                  known(player, interval.start)
                      && known(player, interval.end)
                      && taken(player, interval.start)
                      && !finished(player, interval.end));
    }
  }

  static boolean supported(String expression) {
    return parse(expression) != null;
  }

  public static boolean taken(Player player, int id) {
    if (player == null) return false;
    synchronized (player) {
      var definition = GameplayDefinitions.subs.get(id);
      return known(player, id)
          && definition != null
          && MissionStateRecords.subAccepted(player, id);
    }
  }

  public static boolean finished(Player player, int id) {
    if (player == null) return false;
    synchronized (player) {
      return taken(player, id) && MissionStateRecords.subCompleted(player, id);
    }
  }

  static boolean known(Player player, int id) {
    if (player == null) return false;
    var definition = GameplayDefinitions.subs.get(id);
    var state = player.getGameplay().getSubMissions().get(id);
    var parent =
        definition == null
            ? null
            : player.getGameplay().getMainMissions().get(definition.getMainMissionID());
    return definition != null
        && definition.getId() == id
        && definition.getMainMissionID() > 0
        && definition.getProgress() >= 0
        && !player.getGameplay().getCancelledSubs().contains(id)
        && !player.getGameplay().getCancelledMissions().contains(definition.getMainMissionID())
        && (parent == null || MissionStateRecords.mainAccepted(player, definition.getMainMissionID()))
        && (state == null || MissionStateRecords.subAccepted(player, id));
  }

  private static List<Interval> parse(String expression) {
    if (expression == null || expression.isEmpty() || expression.length() > 8192) return null;
    String[] branches = expression.split("\\|", -1);
    if (branches.length > 32) return null;
    var intervals = new ArrayList<Interval>();
    for (String branch : branches) {
      var matcher = INTERVAL.matcher(branch);
      if (!matcher.matches()) return null;
      try {
        intervals.add(
            new Interval(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
      } catch (NumberFormatException invalid) {
        return null;
      }
    }
    return List.copyOf(intervals);
  }

  private StoryLineConditions() {}
}
