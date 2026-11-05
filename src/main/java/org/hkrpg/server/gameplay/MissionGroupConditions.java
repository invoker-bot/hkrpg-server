package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.LevelGroupMissionCondition;
import emu.lunarcore.data.config.LoadConditionInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import java.util.Objects;
import java.util.Set;

/** Preserve authored And/Or and the omitted Accept phase; unknown clauses never open a group. */
public final class MissionGroupConditions {
  /** Ordinary world groups can declare mission gates without being categorized as Mission. */
  public static boolean tracks(GroupInfo group, Scene scene) {
    return group != null
        && scene != null
        && Set.of(PlaneType.Town, PlaneType.Maze, PlaneType.Train).contains(scene.getPlaneType())
        && (group.getCategory() == null || group.getCategory() == GroupInfo.GroupCategory.Normal)
        && (declaresCondition(group.getLoadCondition())
            || declaresCondition(group.getUnloadCondition()))
        && scene.getFloorInfo() != null
        && scene.getResourceGroups().get(group.getId()) == group;
  }

  static boolean declaresCondition(LoadConditionInfo set) {
    // Some normal groups serialize unused default sets as {"Conditions":[]}.
    // Invalid/unknown sets still enter evaluation and fail closed.
    return set != null
        && (set.getConditions() == null
            || set.getConditions().length != 0
            || !"And".equals(set.getOperation())
            || set.isDelayToLevelReload());
  }

  enum Result {
    MATCH,
    NO_MATCH,
    UNKNOWN
  }

  static boolean matches(Player player, LoadConditionInfo set) {
    return evaluate(player, set) == Result.MATCH;
  }

  static Result evaluate(Player player, LoadConditionInfo set) {
    if (set == null
        || !Set.of("And", "Or").contains(Objects.toString(set.getOperation(), ""))
        || set.getConditions() == null
        || set.getConditions().length == 0) return Result.UNKNOWN;
    boolean result = "And".equals(set.getOperation());
    for (var clause : set.getConditions()) {
      if (clause == null
          || clause.getID() <= 0
          || clause.getPhase() == null
          || clause.getMissionType() == null
          || clause.getMissionType()
              == LevelGroupMissionCondition.LevelGroupMissionType.EventMission)
        return Result.UNKNOWN;
      boolean main =
          clause.getMissionType() == LevelGroupMissionCondition.LevelGroupMissionType.MainMission;
      var states =
          main ? player.getGameplay().getMainMissions() : player.getGameplay().getSubMissions();
      if (states.containsKey(clause.getID())) {
        var state = states.get(clause.getID());
        var definition = main ? null : GameplayDefinitions.subs.get(clause.getID());
        if (!main && (definition == null || definition.getId() != clause.getID()))
          return Result.UNKNOWN;
        int goal = main ? 1 : Math.max(1, definition.getProgress());
        if (!MissionStateRecords.doing(state, clause.getID(), goal)
            && !MissionStateRecords.completed(state, clause.getID(), goal)) return Result.UNKNOWN;
      }
      boolean value =
          switch (clause.getPhase()) {
            case Accept ->
                main
                    ? MissionStateRecords.mainAccepted(player, clause.getID())
                    : MissionStateRecords.subAccepted(player, clause.getID());
            case Finish ->
                main
                    ? MissionStateRecords.mainCompleted(player, clause.getID())
                    : MissionStateRecords.subCompleted(player, clause.getID());
            case Cancel -> false;
          };
      result = "And".equals(set.getOperation()) ? result && value : result || value;
    }
    return result ? Result.MATCH : Result.NO_MATCH;
  }

  private MissionGroupConditions() {}
}
