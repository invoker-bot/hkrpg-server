package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** One bare source task; unrelated or dynamic script instructions remain unsupported. */
public final class StoryTrialReplacement {
  public record Plan(
      int sub, int main, int trial, int story, int plane, int floor, MissionTaskInfo task) {
    boolean matches(MissionInfo.SubMissionInfo config) {
      return condition(config)
          && config.getId() == sub
          && config.getMainMissionID() == main
          && config.getParamInt1() == trial
          && config.getStoryLineIDList().getFirst() == story
          && config.getLevelPlaneID() == plane
          && config.getLevelFloorID() == floor
          && config.getTask() == task;
    }
  }

  public static Plan from(MissionInfo.SubMissionInfo config, JsonElement definition) {
    if (!condition(config) || config.getTask() == null || !literal(definition)) return null;
    return new Plan(
        config.getId(),
        config.getMainMissionID(),
        config.getParamInt1(),
        config.getStoryLineIDList().getFirst(),
        config.getLevelPlaneID(),
        config.getLevelFloorID(),
        config.getTask());
  }

  private static boolean condition(MissionInfo.SubMissionInfo config) {
    return config != null
        && config.getId() > 0
        && config.getMainMissionID() > 0
        && "StoryLineAddTrialAvatar".equals(config.getFinishType())
        && "Equal".equals(config.getParamType())
        && config.getParamInt1() > 0
        && config.getParamInt2() == 0
        && config.getParamInt3() == 0
        && config.getParamStr1().isBlank()
        && config.getParamIntList().length == 0
        && config.getParamItemList().isEmpty()
        && config.getProgress() == 1
        && !config.isBackTrack()
        && config.getLevelPlaneID() > 0
        && config.getLevelFloorID() > 0
        && config.getMazePlaneID() == 0
        && config.getMazeFloorID() == 0
        && config.getIgnoreVerseParamList().isEmpty()
        && config.getFinishActionList().length == 0
        && config.getCustomValueList().length == 0
        && config.getCustomValueReward().length == 0
        && config.getGroupIDList().isEmpty()
        && config.getRequiredNPCSeriesIDList().isEmpty()
        && config.getStoryLineIDList().size() == 1
        && config.getStoryLineIDList().getFirst() > 0
        && (config.getStoryLineID() == 0
            || config.getStoryLineID() == config.getStoryLineIDList().getFirst());
  }

  private static boolean literal(JsonElement definition) {
    try {
      if (definition == null || !definition.isJsonObject()) return false;
      var root = definition.getAsJsonObject();
      if (!root.keySet().equals(Set.of("Type", "OnInitSequece", "OnStartSequece"))
          || !"Mission".equals(root.get("Type").getAsString())
          || !root.get("OnInitSequece").isJsonArray()
          || !root.getAsJsonArray("OnInitSequece").isEmpty()
          || !root.get("OnStartSequece").isJsonArray()
          || root.getAsJsonArray("OnStartSequece").size() != 1) return false;
      var sequence = root.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject();
      if (!Set.of("TaskList", "Order").containsAll(sequence.keySet())
          || !sequence.has("TaskList")
          || sequence.has("Order")
              && (!sequence.get("Order").isJsonPrimitive()
                  || !sequence.getAsJsonPrimitive("Order").isNumber()
                  || sequence.get("Order").getAsBigDecimal().intValueExact() != 0)) return false;
      var tasks = sequence.getAsJsonArray("TaskList");
      if (tasks.size() != 1 || !tasks.get(0).isJsonObject()) return false;
      var task = tasks.get(0).getAsJsonObject();
      return task.keySet().equals(Set.of("$type"))
          && "RPG.GameCore.StoryLineReplaceTrialPlayer".equals(task.get("$type").getAsString());
    } catch (IllegalArgumentException
        | IllegalStateException
        | ArithmeticException
        | NullPointerException invalid) {
      return false;
    }
  }

  static boolean apply(Player player, MissionInfo.SubMissionInfo config) {
    synchronized (player) {
      var source = config.getStoryTrialTask();
      var manager = player.getMissionManager();
      var state = manager.sub(config.getId());
      if (source == null
          || !source.matches(config)
          || state == null
          || state.getQuestId() != source.sub()
          || state.getPhase() != QuestState.Phase.DOING
          || !manager.canFinishFromAction(source.sub())
          || new StoryActions(player).validate(config) != Retcode.SUCC) return false;
      try {
        return player.getStoryLineManager().replaceTrial(source);
      } catch (IllegalArgumentException | IllegalStateException invalid) {
        return false;
      }
    }
  }

  private StoryTrialReplacement() {}
}
