package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Actual operator windows plus resource unlock conditions. No historical activity is auto-opened.
 */
public final class ActivityAccess {
  public static List<GameplayDefinitions.Window> active(Player p, Instant time) {
    return GameplayDefinitions.windows.stream()
        .filter(w -> w.beginTime() <= time.getEpochSecond() && time.getEpochSecond() < w.endTime())
        .filter(w -> unlocked(p, w.panelId()))
        .filter(TrialActivityCatalog::validWindow)
        .sorted(Comparator.comparingInt(GameplayDefinitions.Window::panelId))
        .toList();
  }

  public static boolean unlocked(Player p, int panel) {
    if (p == null) return false;
    synchronized (p) {
      var config = GameData.getActivityPanelExcelMap().get(panel);
      if (config == null || config.getUnlockConditions() == null) return false;
      String text = config.getUnlockConditions();
      if (text.isBlank()) return true;
      var alternatives = parse(text);
      return alternatives != null
          && alternatives.stream().anyMatch(group -> group.stream().allMatch(c -> c.check(p)));
    }
  }

  private static final Pattern CONDITION =
      Pattern.compile(
          "\\[(FinishMainMission|FinishSubMission|QuestClose|PlayerLevel|WorldLevel):(\\d+)\\]");

  private record Condition(String kind, int value) {
    boolean check(Player player) {
      return switch (kind) {
        case "FinishMainMission" -> player.getMissionManager().finished(value);
        case "FinishSubMission" -> MissionStateRecords.subCompleted(player, value);
        case "QuestClose" -> QuestCompletionRecords.closed(player, value);
        case "PlayerLevel" -> player.getLevel() >= value;
        case "WorldLevel" -> player.getWorldLevel() >= value;
        default -> false;
      };
    }
  }

  /** Validate the complete expression before evaluating any successful alternative. */
  private static List<List<Condition>> parse(String text) {
    if (text.length() > 2048) return null;
    var alternatives = new ArrayList<List<Condition>>();
    int count = 0;
    for (var group : text.split("\\|", -1)) {
      var conjunction = new ArrayList<Condition>();
      for (var part : group.split("[,&]", -1)) {
        if (++count > 32) return null;
        var match = CONDITION.matcher(part.trim());
        if (!match.matches()) return null;
        int value;
        try {
          value = Integer.parseInt(match.group(2));
        } catch (NumberFormatException invalid) {
          return null;
        }
        String kind = match.group(1);
        if (value == 0 && !kind.equals("WorldLevel") && !kind.equals("PlayerLevel")) return null;
        conjunction.add(new Condition(kind, value));
      }
      alternatives.add(List.copyOf(conjunction));
    }
    return List.copyOf(alternatives);
  }

  private ActivityAccess() {}
}
