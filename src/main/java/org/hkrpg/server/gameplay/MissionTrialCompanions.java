package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/**
 * Whole original actor/ClientFinishMission graph; the second action uses the ordinary action
 * ledger.
 */
final class MissionTrialCompanions {
  static final int SOURCE = 103420827;
  private static final int MAIN = 1034208, TARGET = 103420807, RAID = 43315, FLOOR = 43315001;
  private static final Gson GSON = new Gson();
  private static final JsonElement SCRIPT =
      JsonParser.parseString(
          """
          {"OnInitSequece":[],"OnStartSequece":[{"TaskList":[
            {"$type":"RPG.GameCore.ReplaceVirtualTeam"},
            {"$type":"RPG.GameCore.ClientFinishMission","SubmissionID":103420807}
          ]}],"Type":"Mission"}
          """);

  private static MissionInfo.SubMissionInfo target(MissionInfo.SubMissionInfo c) {
    if (c == null
        || c.getId() != SOURCE
        || c.getMainMissionID() != MAIN
        || c.getLevelPlaneID() != RAID
        || c.getLevelFloorID() != FLOOR
        || !"GetTrialAvatar".equals(c.getFinishType())
        || !"Equal".equals(c.getParamType())
        || c.getParamInt1() != 1021317
        || c.getTask() == null
        || c.getTask().getSourceDefinition() == null
        || c.getTask().getSourceDefinition().length() > 8192
        || !SCRIPT.equals(JsonParser.parseString(c.getTask().getSourceDefinition()))) return null;
    var target = GameplayDefinitions.subs.get(TARGET);
    if (target == null
        || target.getId() != TARGET
        || target.getMainMissionID() != MAIN
        || target.getLevelPlaneID() != RAID
        || target.getLevelFloorID() != FLOOR
        || !"Talk".equals(target.getFinishType())
        || !"Equal".equals(target.getParamType())
        || !"Mission_103420807".equals(target.getParamStr1())
        || target.getProgress() != 1
        || target.getParamInt1() != 0
        || target.getParamInt2() != 0
        || target.getParamInt3() != 0
        || target.getParamIntList().length != 0
        || !target.getParamItemList().isEmpty()
        || target.getTask() != null
        || target.getCustomValueList().length != 0
        || target.getFinishActionList().length != 1) return null;
    var action = target.getFinishActionList()[0];
    return action != null
            && "MoveToAnchor".equals(action.getType())
            && Arrays.equals(action.getParams(), new int[] {FLOOR, 28, 1})
            && action.getStrings().length == 0
        ? target
        : null;
  }

  static JsonElement definition(MissionInfo.SubMissionInfo c) {
    try {
      var target = target(c);
      return target == null ? null : GSON.toJsonTree(target);
    } catch (IllegalArgumentException | IllegalStateException | NullPointerException invalid) {
      return null;
    }
  }

  static String task(MissionInfo.SubMissionInfo c) {
    return definition(c) == null ? null : "RPG.GameCore.ReplaceVirtualTeam";
  }

  static boolean owned(Player player, MissionInfo.SubMissionInfo c) {
    if (c.getId() != SOURCE) return true;
    return definition(c) != null
        && player.getGameplay().getCurrentMissionRaid() == RAID
        && new MissionRaid(player).owns(MAIN)
        && MissionStateRecords.mainDoing(player, MAIN)
        && MissionStateRecords.subAccepted(player, TARGET);
  }

  static boolean ready(Player player, MissionInfo.SubMissionInfo c) {
    if (c.getId() != SOURCE) return true;
    return owned(player, c)
        && (MissionStateRecords.subCompleted(player, TARGET)
            || new StoryActions(player).validate(GameplayDefinitions.subs.get(TARGET))
                == Retcode.SUCC);
  }

  static boolean finished(Player player, MissionInfo.SubMissionInfo c) {
    return c.getId() != SOURCE
        || owned(player, c) && MissionStateRecords.subCompleted(player, TARGET);
  }

  static boolean complete(Player player, MissionInfo.SubMissionInfo c) {
    if (c.getId() != SOURCE || finished(player, c)) return true;
    if (!ready(player, c)) return false;
    var run = new MissionRaid(player).current();
    var source = TrialAvatarMissionConditions.source(c);
    var party = run == null ? null : run.getMissionTrials().getParty();
    if (source == null
        || party == null
        || party.getRaid() != RAID
        || party.getMain() != MAIN
        || !source.signature().equals(party.getSources().get(SOURCE))
        || !source.signature().equals(run.getMissionTrials().getExecuted().get(SOURCE))
        || !player.getCurrentLineup().getAvatars().equals(List.of(1021317))) return false;
    return player.getMissionManager().finishTalk(TARGET, "Mission_103420807") == Retcode.SUCC;
  }

  private MissionTrialCompanions() {}
}
