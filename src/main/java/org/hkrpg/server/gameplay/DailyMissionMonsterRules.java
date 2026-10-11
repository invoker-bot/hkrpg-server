package org.hkrpg.server.gameplay;

import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.scene.Scene;
import java.util.*;

/** Original owned daily targets use the accepted run's objective, without a global death history. */
public final class DailyMissionMonsterRules {
  static boolean repeatable(MissionInfo.SubMissionInfo sub) {
    if (sub == null
        || sub.getId() <= 0
        || sub.getMainMissionID() <= 0
        || GameplayDefinitions.subs.get(sub.getId()) != sub
        || !"KillMonster".equals(sub.getFinishType())
        || !"Equal".equals(sub.getParamType())
        || sub.getProgress() != 1
        || sub.getParamInt1() <= 0 || sub.getParamInt2() <= 0 || sub.getParamInt3() != 0
        || !"".equals(sub.getParamStr1())
        || sub.getParamIntList() == null || sub.getParamIntList().length != 0
        || sub.getParamItemList() == null || !sub.getParamItemList().isEmpty()
        || sub.getTakeParamStrList() == null || sub.getTakeParamStrList().length != 0
        || sub.getSubRewardID() != 0
        || !sub.isBackTrack()
        || sub.getMazePlaneID() != sub.getLevelPlaneID()
        || sub.getMazeFloorID() != sub.getLevelFloorID()
        || sub.getStoryLineID() != 0
        || sub.getStoryLineIDList() == null || !sub.getStoryLineIDList().isEmpty()
        || sub.getIgnoreVerseParamList() == null || !sub.getIgnoreVerseParamList().isEmpty()
        || sub.getStoryTrialTask() != null) return false;
    var main = GameData.getMainMissionExcelMap().get(sub.getMainMissionID());
    var plane = GameData.getMazePlaneExcelMap().get(sub.getLevelPlaneID());
    var floor = GameData.getFloorInfo(sub.getLevelPlaneID(), sub.getLevelFloorID());
    if (main == null || main.getId() != sub.getMainMissionID()
        || !"Daily".equals(main.getType()) || main.isInRaid()
        || plane == null || plane.getId() != sub.getLevelPlaneID()
        || !(plane.getPlaneType() == PlaneType.Town || plane.getPlaneType() == PlaneType.Maze
            || plane.getPlaneType() == PlaneType.Train)
        || plane.getFloorIDList() == null
        || Collections.frequency(plane.getFloorIDList(), sub.getLevelFloorID()) != 1
        || floor == null || floor.getFloorID() != sub.getLevelFloorID() || !floor.isLoaded())
      return false;
    try {
      var group = floor.getGroups(0).get(sub.getParamInt1());
      if (!floor.isGroupInDimension(sub.getParamInt1(), 0)
          || group == null || group.getId() != sub.getParamInt1()
          || group.getCategory() != GroupInfo.GroupCategory.Mission
          || group.getLoadSide() != GroupInfo.GroupLoadSide.Server
          || group.getOwnerMainMissionID() != sub.getMainMissionID()
          || group.getAtmosphereCondition() != null || group.getSavedValueCondition() != null
          || group.getValueSource() != null
          || group.getLevelGraph() != null && !group.getLevelGraph().isBlank()
          || group.getGroupPropertyMap() != null && !group.getGroupPropertyMap().isEmpty()
          || group.getGroupStateList() != null && !group.getGroupStateList().isEmpty()
          || !loads(group.getLoadCondition(), sub)
          || !single(group.getUnloadCondition(), "And", sub.getMainMissionID(),
              LevelGroupMissionCondition.LevelGroupMissionType.MainMission,
              LevelGroupMissionCondition.LevelGroupMissionPhase.Finish)
          || group.getMonsterList() == null || group.getMonsterList().size() != 1) return false;
      var monster = group.getMonsterList().getFirst();
      if (monster == null || monster.getID() != sub.getParamInt2()
          || monster.isIsDelete() || monster.isIsClientOnly() || !monster.isLoadOnInitial()
          || monster.getNPCMonsterID() <= 0 || monster.getEventID() <= 0) return false;
      var npc = GameData.getNpcMonsterExcelMap().get(monster.getNPCMonsterID());
      if (npc == null || npc.getId() != monster.getNPCMonsterID()) return false;
      for (int world = 0; world < GameConstants.WORLD_LEVEL_UPGRADES.length; world++) {
        var event = GameData.getPlaneEventExcelMap().get(monster.getEventID(), world);
        if (event == null || event.getEventId() != monster.getEventID()
            || event.getWorldLevel() != world || event.getStageId() <= 0) return false;
        var stage = GameData.getStageExcelMap().get(event.getStageId());
        if (stage == null || stage.getId() != event.getStageId()
            || stage.getMonsterWaves() == null || stage.getMonsterWaves().isEmpty()
            || stage.getMonsterWaves().stream().anyMatch(wave -> wave == null || wave.isEmpty()
                || wave.intStream().anyMatch(id -> id <= 0))) return false;
      }
      return true;
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  /** Called while constructing a new Scene, before Player.scene is replaced. */
  public static boolean spawns(Scene scene, GroupInfo group, MonsterInfo monster) {
    if (scene == null || group == null || monster == null) return false;
    var main = GameData.getMainMissionExcelMap().get(group.getOwnerMainMissionID());
    if (main == null || !"Daily".equals(main.getType())) return true;
    var graph = GameData.getMissionInfos().get(main.getId());
    if (graph == null || graph.getSubMissionList() == null) return true;
    var matches = Arrays.stream(graph.getSubMissionList()).filter(sub -> sub != null
        && "KillMonster".equals(sub.getFinishType())
        && sub.getLevelPlaneID() == scene.getPlaneId() && sub.getLevelFloorID() == scene.getFloorId()
        && sub.getParamInt1() == group.getId() && sub.getParamInt2() == monster.getID()).toList();
    if (matches.isEmpty()) return true;
    if (matches.size() != 1) return false;
    var sub = matches.getFirst();
    var player = scene.getPlayer();
    synchronized (player) {
      var now = player.getMissionManager().currentInstant();
      var day = DailyMissionRepeats.selectedDay(player, main.getId());
      var gameplay = player.getGameplay();
      if (gameplay.getDailyMissionPeriods() == null
          || gameplay.getDailyMissionPeriods().getRunDays() == null) return false;
      var run = gameplay.getDailyMissionPeriods().getRunDays().get(main.getId());
      if (!repeatable(sub) || !DailyMissionRepeats.supportedGraph(main.getId())
          || scene.getDimensionId() != 0 || scene.getStoryLineId() != 0
          || gameplay.getStoryLines().getCurrentId() != 0
          || scene.getFloorInfo() != GameData.getFloorInfo(sub.getLevelPlaneID(), sub.getLevelFloorID())
          || scene.getResourceGroups().get(group.getId()) != group
          || group.getMonsterList().getFirst() != monster
          || !MissionStateRecords.mainDoing(player, main.getId())
          || !DailyMissionExpiry.current(player, main.getId(), now)
          || day == null || day != DailyManager.day(now)
          || run != null && !Objects.equals(run, day)
          || gameplay.getCancelledSubs().contains(sub.getId())) return false;
      var state = gameplay.getSubMissions().get(sub.getId());
      // G95's original main Accept condition can spawn before the first talk accepts the kill goal.
      return state == null || MissionStateRecords.doing(state, sub.getId(), 1);
    }
  }

  private static boolean loads(LoadConditionInfo condition, MissionInfo.SubMissionInfo sub) {
    if (single(condition, "And", sub.getMainMissionID(),
        LevelGroupMissionCondition.LevelGroupMissionType.MainMission,
        LevelGroupMissionCondition.LevelGroupMissionPhase.Accept)) return true;
    if (condition == null || !"Or".equals(condition.getOperation())
        || condition.isDelayToLevelReload() || condition.getConditions() == null
        || condition.getConditions().length != 2) return false;
    var ids = new HashSet<Integer>();
    for (var clause : condition.getConditions()) {
      if (clause == null || clause.getMissionType() != LevelGroupMissionCondition.LevelGroupMissionType.SubMission
          || clause.getPhase() != LevelGroupMissionCondition.LevelGroupMissionPhase.Accept
          || !ids.add(clause.getID())) return false;
    }
    if (!ids.remove(sub.getId()) || ids.size() != 1) return false;
    var next = GameplayDefinitions.subs.get(ids.iterator().next());
    return next != null && next.getMainMissionID() == sub.getMainMissionID()
        && "Talk".equals(next.getFinishType()) && "AnySequence".equals(next.getTakeType())
        && next.getTakeParamIntList() != null && next.getTakeParamIntList().length == 1
        && Objects.equals(next.getTakeParamIntList()[0], sub.getId());
  }

  private static boolean single(LoadConditionInfo condition, String operation, int id,
      LevelGroupMissionCondition.LevelGroupMissionType type,
      LevelGroupMissionCondition.LevelGroupMissionPhase phase) {
    if (condition == null || !operation.equals(condition.getOperation())
        || condition.isDelayToLevelReload() || condition.getConditions() == null
        || condition.getConditions().length != 1) return false;
    var clause = condition.getConditions()[0];
    return clause != null && clause.getID() == id
        && clause.getMissionType() == type && clause.getPhase() == phase;
  }

  private DailyMissionMonsterRules() {}
}
