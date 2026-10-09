package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import java.util.*;

/** Evaluates the separate FinishWayRogue graph. Unknown conditions remain locked. */
public final class RogueUnlocks {
  public static boolean open(Player player, int unlock) {
    synchronized (player) {
      return unlock == 0 || evaluate(player, unlock, new HashSet<>());
    }
  }

  private static boolean evaluate(Player player, int unlock, Set<Integer> path) {
    var definition = GameplayDefinitions.rogueUnlocks.get(unlock);
    var rule =
        definition == null
            ? null
            : GameplayDefinitions.rogueRules.get(definition.UnlockFinishWay());
    if (unlock <= 0 || rule == null || rule.Progress() <= 0 || !path.add(unlock)) return false;
    try {
      if (rule.ParamInt2() != 0
          || rule.ParamInt3() != 0
          || (rule.ParamStr1() != null && !rule.ParamStr1().isEmpty())
          || (rule.ParamItemList() != null && !rule.ParamItemList().isEmpty())) return false;
      return switch (Objects.toString(rule.FinishType(), "")) {
        case "RogueFinishUnlock", "FinishMission" -> {
          var ids = rule.ParamIntList();
          if (!"ListContain".equals(rule.ParamType())
              || ids == null
              || ids.isEmpty()
              || rule.ParamInt1() != 0
              || ids.stream().anyMatch(id -> id == null || id <= 0)
              || new HashSet<>(ids).size() != ids.size()
              || rule.Progress() > ids.size()) yield false;
          long count =
              ids.stream()
                  .filter(
                      id ->
                          "FinishMission".equals(rule.FinishType())
                              ? player.getMissionManager().finished(id)
                                  && !player.getGameplay().getCancelledMissions().contains(id)
                              : evaluate(player, id, path))
                  .count();
          yield count >= rule.Progress();
        }
        case "WorldLevel", "PlayerLevel" ->
            "NoPara".equals(rule.ParamType())
                && rule.ParamInt1() == 0
                && emptyIds(rule)
                && ("WorldLevel".equals(rule.FinishType())
                        ? player.getWorldLevel()
                        : player.getLevel())
                    >= rule.Progress();
        case "RoguePassAreaProgress" ->
            "Equal".equals(rule.ParamType())
                && rule.ParamInt1() >= 0
                && emptyIds(rule)
                && player.getGameplay().getRogueWins().entrySet().stream()
                        .filter(
                            e ->
                                GameplayDefinitions.areas.containsKey(e.getKey())
                                    && GameplayDefinitions.areas.get(e.getKey()).AreaProgress()
                                        == rule.ParamInt1())
                        .mapToLong(e -> Math.max(e.getValue(), 0))
                        .sum()
                    >= rule.Progress();
        case "RogueUnlockBuff" ->
            "Equal".equals(rule.ParamType())
                && rule.ParamInt1() > 0
                && emptyIds(rule)
                && player.getGameplay().getRogueCollectedBuffs().stream()
                        .filter(
                            id ->
                                GameplayDefinitions.blessings.stream()
                                    .anyMatch(
                                        b ->
                                            b.MazeBuffID() == id
                                                && b.RogueVersion() == 1
                                                && b.RogueBuffType() == rule.ParamInt1()))
                        .count()
                    >= rule.Progress();
        case "RogueTalentEnable" -> talentProgress(player, unlock).orElse(-1) >= rule.Progress();
        default -> false;
      };
    } finally {
      path.remove(unlock);
    }
  }

  private static boolean emptyIds(GameplayDefinitions.RogueRule rule) {
    return rule.ParamIntList() == null || rule.ParamIntList().isEmpty();
  }

  /** Only recognized resource conditions may report an actual talent prerequisite count. */
  public static OptionalInt talentProgress(Player player, int unlock) {
    var definition = GameplayDefinitions.rogueUnlocks.get(unlock);
    var rule =
        definition == null
            ? null
            : GameplayDefinitions.rogueRules.get(definition.UnlockFinishWay());
    if (rule == null
        || !"RogueTalentEnable".equals(rule.FinishType())
        || !"ListContain".equals(rule.ParamType())
        || rule.Progress() <= 0
        || rule.ParamInt1() != 0
        || rule.ParamInt2() != 0
        || rule.ParamInt3() != 0
        || (rule.ParamStr1() != null && !rule.ParamStr1().isEmpty())
        || (rule.ParamItemList() != null && !rule.ParamItemList().isEmpty()))
      return OptionalInt.empty();
    var ids = rule.ParamIntList();
    if (ids == null
        || ids.isEmpty()
        || rule.Progress() > ids.size()
        || new HashSet<>(ids).size() != ids.size()
        || ids.stream()
            .anyMatch(
                id -> id == null || id <= 0 || !GameplayDefinitions.rogueTalents.containsKey(id)))
      return OptionalInt.empty();
    return OptionalInt.of(
        (int) ids.stream().filter(player.getGameplay().getRogueTalents()::contains).count());
  }

  private RogueUnlocks() {}
}
