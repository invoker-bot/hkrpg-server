package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.trial.TrialAvatar;
import java.util.*;

/**
 * Observe an already provided current trial; this does not create actors or run unknown scripts.
 */
final class TrialAvatarMissionConditions {
  private static final Set<String> TASKS =
      Set.of(
          "RPG.GameCore.CreateTrialPlayer", "RPG.GameCore.AddTrialPlayer",
          "RPG.GameCore.ReplaceTrialPlayer", "RPG.GameCore.ReplaceVirtualTeam");

  private static String bareTask(MissionInfo.SubMissionInfo c) {
    if (c.getId() == MissionTrialCompanions.SOURCE) return MissionTrialCompanions.task(c);
    var task = c.getTask();
    if (task == null
        || task.getSourceDefinition() == null
        || task.getSourceDefinition().isBlank()
        || task.getSourceDefinition().length() > 8192) return null;
    var definition = JsonParser.parseString(task.getSourceDefinition());
    if (!definition.isJsonObject()) return null;
    var root = definition.getAsJsonObject();
    if (!root.keySet().equals(Set.of("Type", "OnInitSequece", "OnStartSequece"))
        || !root.get("Type").isJsonPrimitive()
        || !root.getAsJsonPrimitive("Type").isString()
        || !"Mission".equals(root.get("Type").getAsString())
        || !root.get("OnInitSequece").isJsonArray()
        || !root.getAsJsonArray("OnInitSequece").isEmpty()
        || !root.get("OnStartSequece").isJsonArray()
        || root.getAsJsonArray("OnStartSequece").size() != 1
        || !root.getAsJsonArray("OnStartSequece").get(0).isJsonObject()) return null;
    var sequence = root.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject();
    if (!sequence.keySet().equals(Set.of("TaskList"))
        || !sequence.get("TaskList").isJsonArray()
        || sequence.getAsJsonArray("TaskList").size() != 1
        || !sequence.getAsJsonArray("TaskList").get(0).isJsonObject()) return null;
    var instruction = sequence.getAsJsonArray("TaskList").get(0).getAsJsonObject();
    return instruction.keySet().equals(Set.of("$type"))
            && instruction.get("$type").isJsonPrimitive()
            && instruction.getAsJsonPrimitive("$type").isString()
            && TASKS.contains(instruction.get("$type").getAsString())
        ? instruction.get("$type").getAsString()
        : null;
  }

  record Source(
      int sub,
      int main,
      int plane,
      int floor,
      String task,
      List<Integer> targets,
      String signature) {}

  static Source source(MissionInfo.SubMissionInfo c) {
    try {
      if (c == null
          || !("GetTrialAvatar".equals(c.getFinishType())
              || "GetTrialAvatarList".equals(c.getFinishType()))) return null;
      String taskName = c == null ? null : bareTask(c);
      var targets = targets(c, taskName);
      if (c == null
          || targets == null
          || c.getId() <= 0
          || c.getMainMissionID() <= 0
          || c.getParamInt2() != 0
          || c.getParamInt3() != 0
          || !c.getParamStr1().isBlank()
          || !c.getParamItemList().isEmpty()
          || c.getProgress() != 1
          || c.isBackTrack()
          || c.getLevelPlaneID() <= 0
          || c.getLevelFloorID() <= 0
          || c.getMazePlaneID() != 0
          || c.getMazeFloorID() != 0
          || c.getStoryLineID() != 0
          || !c.getStoryLineIDList().isEmpty()
          || !c.getIgnoreVerseParamList().isEmpty()
          || c.getFinishActionList().length != 0
          || c.getCustomValueList().length != 0
          || c.getCustomValueReward().length != 0
          || !c.getGroupIDList().isEmpty()
          || !c.getRequiredNPCSeriesIDList().isEmpty()
          || taskName == null) return null;
      var definition = new Gson().toJsonTree(c).getAsJsonObject();
      definition.add("originalTask", JsonParser.parseString(c.getTask().getSourceDefinition()));
      if (c.getId() == MissionTrialCompanions.SOURCE)
        definition.add("companionTarget", MissionTrialCompanions.definition(c));
      return new Source(
          c.getId(),
          c.getMainMissionID(),
          c.getLevelPlaneID(),
          c.getLevelFloorID(),
          taskName,
          List.copyOf(targets),
          definition.toString());
    } catch (JsonParseException
        | IllegalArgumentException
        | IllegalStateException
        | NullPointerException invalid) {
      return null;
    }
  }

  static boolean eligible(Player player, MissionInfo.SubMissionInfo c) {
    var source = source(c);
    return source != null
        && MissionTrialCompanions.owned(player,c)
        && source.plane() == player.getPlaneId()
        && source.floor() == player.getFloorId()
        && MissionStateRecords.mainDoing(player, source.main())
        && MissionStateRecords.doing(player.getMissionManager().sub(source.sub()), source.sub(), 1)
        && !player.getGameplay().getCancelledSubs().contains(source.sub());
  }

  static boolean satisfied(Player player, MissionInfo.SubMissionInfo c) {
    synchronized (player) {
      try {
        var source = source(c);
        return source != null
            && eligible(player, c)
            && MissionTrialCompanions.finished(player,c)
            && source.targets().stream()
                .anyMatch(id -> provided(player, id, source.task(), source.targets()));
      } catch (IllegalArgumentException | IllegalStateException | NullPointerException invalid) {
        return false;
      }
    }
  }

  private static List<Integer> targets(MissionInfo.SubMissionInfo c, String task) {
    if (c == null) return null;
    if ("GetTrialAvatar".equals(c.getFinishType())) {
      return "Equal".equals(c.getParamType())
              && c.getParamInt1() > 0
              && c.getParamIntList().length == 0
          ? List.of(c.getParamInt1())
          : null;
    }
    if (!"GetTrialAvatarList".equals(c.getFinishType())
        || !"ListContain".equals(c.getParamType())
        || c.getParamInt1() != 0) return null;
    var ids = c.getParamIntList();
    if (c.getId() != 201141225 && ids.length == 1 && ids[0] > 0) return List.of(ids[0]);
    // The original two entries are alternate protagonist genders; not an arbitrary OR list.
    if (c.getId() == 201141225
        && c.getMainMissionID() == 2011412
        && "RPG.GameCore.CreateTrialPlayer".equals(task)
        && Arrays.equals(ids, new Integer[] {1108001, 1108002})) return List.of(1108001, 1108002);
    return null;
  }

  private static boolean provided(Player player, int id, String taskName, List<Integer> targets) {
    if (targets.size() == 2) {
      for (int candidate : targets) {
        var source = player.getTrialManager().specialRow(candidate);
        if (source == null
            || source.getId() != candidate
            || source.getAvatarID() != candidate - 1100000
            || GameData.getActorForTrial(source.getAvatarID()) == null) return false;
      }
    }
    var provided = player.getAvatarById(id);
    var current = player.getCurrentLineup();
    var row = player.getTrialManager().specialRow(id);
    if (!(provided instanceof TrialAvatar trial)
        || current == null
        || row == null
        || current.getOwner() != player
        || current.getAvatars().isEmpty()
        || current.getAvatars().size() > 4
        || current.getLeader() < 0
        || current.getLeader() >= current.size()
        || "RPG.GameCore.ReplaceVirtualTeam".equals(taskName) && !current.isExtraLineup()
        || Set.of("RPG.GameCore.ReplaceTrialPlayer", "RPG.GameCore.ReplaceVirtualTeam")
                .contains(taskName)
            && !current.getAvatars().equals(List.of(id))
        || new HashSet<>(current.getAvatars()).size() != current.getAvatars().size()
        || !current.isActiveSlot(current.getAvatars().indexOf(id))
        || trial.getOwner() != player
        || trial.getOwnerUid() != player.getUid()
        || trial.getAvatarId() != id
        || trial.getSpecialAvatarExcel() == null
        || trial.getSpecialAvatarExcel().getId() != id
        || row.getId() != id
        || row.getAvatarID() != trial.getActorId()
        || trial.getExcel() == null
        || trial.getExcel().getAvatarID() != trial.getActorId()
        || GameData.getActorForTrial(trial.getActorId()) == null
        || !player.getTrialManager().matchesGender(trial.getActorId())
        || player.getAvatars().getAvatars().containsKey(id)) return false;
    for (int member : current.getAvatars()) {
      var actor = member > 0 ? player.getAvatarById(member) : null;
      if (actor == null
          || targets.contains(member)
              && actor instanceof TrialAvatar
              && !player.getTrialManager().matchesGender(((TrialAvatar) actor).getActorId())
          || actor.getAvatarId() != member
          || actor.getOwner() != player
          || actor.getOwnerUid() != player.getUid()) return false;
    }
    return player.getAvatars().getTempAvatars().get(id) == trial
        || player.getStoryLineManager().avatar(id) == trial;
  }

  private TrialAvatarMissionConditions() {}
}
