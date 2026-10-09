package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** One original no-parameter target, bound when server-earned exploration score is credited. */
final class RogueExploreScoreMission {
  private static final int SUB = 403000601;
  private static final Gson GSON = new Gson();
  private static final JsonElement SCRIPT =
      JsonParser.parseString("{\"OnInitSequece\":[],\"OnStartSequece\":[],\"Type\":\"Mission\"}");
  private static final MissionInfo.SubMissionInfo SOURCE;
  private static final String SIGNATURE;

  static {
    SOURCE =
        GSON.fromJson(
            """
            {"ID":403000601,"MainMissionID":4030006,
            "MissionJsonPath":"Config/Level/Mission/4030006/Mission_403000601.json",
            "LevelPlaneID":80112,"LevelFloorID":80112001,"TakeType":"Auto",
            "FinishType":"RogueExploreScore","ParamType":"NoPara","ParamItemList":[],
            "Progress":1,"IsShow":false,"WayPointFloorID":80112001}
            """,
            MissionInfo.SubMissionInfo.class);
    var task = GSON.fromJson(SCRIPT, MissionTaskInfo.class);
    task.retainSourceDefinition(SCRIPT);
    SOURCE.setTask(task);
    SIGNATURE = GSON.toJson(List.of(SOURCE, SCRIPT));
  }

  private static boolean source(MissionInfo.SubMissionInfo c) {
    try {
      return c != null
          && c.getId() == SUB
          && c.getTask() != null
          && c.getTask().getSourceDefinition() != null
          && c.getTask().getSourceDefinition().length() <= 8192
          && GSON.toJsonTree(c).equals(GSON.toJsonTree(SOURCE))
          && JsonParser.parseString(c.getTask().getSourceDefinition()).equals(SCRIPT);
    } catch (JsonParseException | IllegalArgumentException | IllegalStateException invalid) {
      return false;
    }
  }

  private static boolean active(Player player, MissionInfo.SubMissionInfo c) {
    return source(c)
        && MissionStateRecords.mainDoing(player, c.getMainMissionID())
        && MissionStateRecords.doing(player.getMissionManager().sub(SUB), SUB, 1)
        && player.getMissionManager().canFinishFromAction(SUB);
  }

  static Map<Integer, String> capture(Player player, int points) {
    synchronized (player) {
      var c = GameplayDefinitions.subs.get(SUB);
      return points > 0 && active(player, c) ? Map.of(SUB, SIGNATURE) : Map.of();
    }
  }

  static boolean satisfied(Player player, MissionInfo.SubMissionInfo c) {
    synchronized (player) {
      if (!active(player, c)) return false;
      for (var entry : player.getGameplay().getRogueResults().entrySet()) {
        var result = entry.getValue();
        if (entry.getKey() == null
            || entry.getKey().isBlank()
            || result == null
            || result.getExploreMissionTargets() == null
            || !result.getExploreMissionTargets().equals(Map.of(SUB, SIGNATURE))
            || !result.isCycleReady()
            || !result.isCycleSettled()
            || !result.isEarningsReady()
            || result.getCyclePoints() <= 0
            || result.getCyclePoints() != result.getRoomScore()
            || result.getAreaId() <= 0
            || result.getPassedRooms() <= 0
            || result.getReachedRooms() < result.getPassedRooms()
            || result.getCycleStart() < 0
            || result.getCycleEnd() <= result.getCycleStart()
            || result.getOutcome() == null
            || !Set.of(
                    GameplayState.RogueRun.Phase.FINISHED,
                    GameplayState.RogueRun.Phase.FAILED,
                    GameplayState.RogueRun.Phase.ABANDONED)
                .contains(result.getOutcome())) continue;
        var week =
            player
                .getGameplay()
                .getCycleWeeks()
                .get(
                    new CycleScoreManager.Window(result.getCycleStart(), result.getCycleEnd())
                        .key());
        if (week != null
            && week.getStart() == result.getCycleStart()
            && week.getEnd() == result.getCycleEnd()
            && week.getScore() >= result.getCyclePoints()) return true;
      }
      return false;
    }
  }

  private RogueExploreScoreMission() {}
}
