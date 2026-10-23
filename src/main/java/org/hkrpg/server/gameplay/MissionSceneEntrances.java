package org.hkrpg.server.gameplay;

import com.google.gson.JsonParser;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** An active literal EnterMap may authorize its own arrival before that arrival is completed. */
public final class MissionSceneEntrances {
  public static boolean allows(Player player, MapEntranceExcel entry, int teleport) {
    synchronized (player) {
      var scene = player.getScene();
      var g = player.getGameplay();
      if (entry == null
          || teleport != 0
          || scene == null
          || scene.getPlayer() != player
          || scene.getPlaneId() != player.getPlaneId()
          || scene.getFloorId() != player.getFloorId()
          || scene.getEntryId() != player.getEntryId()
          || scene.getDimensionId() != 0
          || scene.getStoryLineId() != 0
          || player.getBattle() != null
          || player.getInstance() != null
          || g.getCurrentMissionRaid() != 0
          || g.getTrialRun() != null
          || g.getRogue().isInside()
          || g.getParkourVenue().inside()
          || g.getStoryLines().getCurrentId() != 0
          || g.getStoryLines().isTransitionPending()
          || GameData.getMapEntranceExcelMap().get(entry.getId()) != entry) return false;
      for (var state : g.getSubMissions().values()) {
        var sub = state == null ? null : GameplayDefinitions.subs.get(state.getQuestId());
        if (sub == null
            || !MissionStateRecords.doing(state, sub.getId(), 1)
            || sub.getLevelPlaneID() != scene.getPlaneId()
            || sub.getLevelFloorID() != scene.getFloorId()
            || sub.getParamInt1() != entry.getPlaneID()
            || sub.getParamInt2() != entry.getFloorID()
            || !player.getMissionManager().canFinishFromAction(sub.getId())
            || !predecessors(player, sub)
            || !script(sub, entry.getId())) continue;
        var graph = GameData.getMissionInfos().get(sub.getMainMissionID());
        var main = GameData.getMainMissionExcelMap().get(sub.getMainMissionID());
        if (graph == null
            || main == null
            || main.getId() != sub.getMainMissionID()
            || graph.getMainMissionID() != main.getId()
            || graph.getSubMissionById(sub.getId()) != sub) continue;
        // Unrelated entrance prerequisites still apply. Do not complete any task to gain entry.
        if (Arrays.stream(entry.getFinishMainMissionList())
                .allMatch(
                    id ->
                        id == sub.getMainMissionID()
                            || MissionStateRecords.mainCompleted(player, id))
            && Arrays.stream(entry.getFinishSubMissionList())
                .allMatch(id -> id == sub.getId() || StoryLineConditions.finished(player, id)))
          return true;
      }
      return false;
    }
  }

  private static boolean predecessors(Player player, MissionInfo.SubMissionInfo sub) {
    var ids = sub.getTakeParamIntList();
    if (ids == null || sub.getTakeParamStrList() == null || sub.getTakeParamStrList().length != 0)
      return false;
    if ("Auto".equals(sub.getTakeType())) return ids.length == 0;
    if (!Set.of("AnySequence", "MultiSequence").contains(Objects.toString(sub.getTakeType(), ""))
        || ids.length == 0
        || new HashSet<>(Arrays.asList(ids)).size() != ids.length
        || Arrays.stream(ids)
            .anyMatch(
                id ->
                    id == null
                        || id <= 0
                        || GameplayDefinitions.subs.get(id) == null
                        || GameplayDefinitions.subs.get(id).getMainMissionID()
                            != sub.getMainMissionID())) return false;
    return "AnySequence".equals(sub.getTakeType())
        ? Arrays.stream(ids).anyMatch(id -> MissionStateRecords.subCompleted(player, id))
        : Arrays.stream(ids).allMatch(id -> MissionStateRecords.subCompleted(player, id));
  }

  private static boolean script(MissionInfo.SubMissionInfo sub, int entry) {
    if (!DailyMissionTravelRules.enterFloor(sub)) return false;
    var task =
        JsonParser.parseString(sub.getTask().getSourceDefinition())
            .getAsJsonObject()
            .getAsJsonArray("OnStartSequece")
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("TaskList")
            .get(0)
            .getAsJsonObject();
    // Overrides need their own anchor-aware transition. This path uses the original entry anchor.
    return task.keySet().equals(Set.of("$type", "EntranceID"))
        && task.get("EntranceID").getAsInt() == entry;
  }

  private MissionSceneEntrances() {}
}
