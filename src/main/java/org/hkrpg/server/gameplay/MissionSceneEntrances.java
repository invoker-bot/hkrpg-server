package org.hkrpg.server.gameplay;

import com.google.gson.JsonParser;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** An active literal EnterMap may authorize its own arrival before that arrival is completed. */
public final class MissionSceneEntrances {
  /**
   * Captured before login refresh, so newly accepted objectives cannot skip their start sequence.
   */
  public record LoginOrigin(int plane, int floor, int entry, Set<Integer> accepted) {
    public LoginOrigin {
      accepted = Set.copyOf(accepted);
    }
  }

  public static LoginOrigin captureLogin(Player player) {
    synchronized (player) {
      var pending = new HashSet<Integer>();
      player
          .getGameplay()
          .getSubMissions()
          .forEach(
              (id, state) -> {
                if (MissionStateRecords.doing(state, id, 1)) pending.add(id);
              });
      return new LoginOrigin(
          player.getPlaneId(), player.getFloorId(), player.getEntryId(), pending);
    }
  }

  /** Resume an interrupted, already accepted literal transfer before the first login scene. */
  public static boolean resumeOnLogin(Player player, LoginOrigin origin) {
    synchronized (player) {
      if (origin == null
          || player.isOnline()
          || !ordinaryContext(player)
          || origin.plane() != player.getPlaneId()
          || origin.floor() != player.getFloorId()
          || origin.entry() != player.getEntryId()) return false;
      MapEntranceExcel destination = null;
      MissionInfo.SubMissionInfo accepted = null;
      for (int id : origin.accepted()) {
        var sub = GameplayDefinitions.subs.get(id);
        var entry = sub == null ? null : GameData.getMapEntranceExcelMap().get(literalEntry(sub));
        if (entry == null || !authorizes(player, sub, entry)) continue;
        // Two pending starts require explicit client ordering; never choose one arbitrarily.
        if (destination != null) return false;
        destination = entry;
        accepted = sub;
      }
      // loadScene persists the real location, then completes arrival and accepts the next dialogue.
      // No scene push is sent before login: the first ordinary scene response contains the result.
      return destination != null && transfer(player, accepted, destination, false);
    }
  }

  /** Resolve the same source-bound destination for a live request, even if the map is already open. */
  public static boolean enter(Player player, int entryId, int teleport, boolean sendPacket) {
    synchronized (player) {
      var entry = GameData.getMapEntranceExcelMap().get(entryId);
      MissionInfo.SubMissionInfo accepted = null;
      if (entry != null && teleport == 0 && ordinaryContext(player)) {
        for (var state : player.getGameplay().getSubMissions().values()) {
          var sub = state == null ? null : GameplayDefinitions.subs.get(state.getQuestId());
          if (sub == null || !authorizes(player, sub, entry)) continue;
          if (accepted != null) return false;
          accepted = sub;
        }
      }
      return accepted == null ? player.enterScene(entryId, teleport, sendPacket)
          : transfer(player, accepted, entry, sendPacket);
    }
  }

  private static boolean transfer(Player player, MissionInfo.SubMissionInfo sub,
      MapEntranceExcel entry, boolean sendPacket) {
    var task = literalTask(sub);
    // Anchor identifiers come only from the completely validated original script, never the request.
    return task.has("GroupID")
        ? player.enterSceneAtAnchor(entry.getId(), task.get("GroupID").getAsInt(),
            task.get("AnchorID").getAsInt(), sendPacket)
        : player.enterScene(entry.getId(), 0, sendPacket);
  }

  public static boolean allows(Player player, MapEntranceExcel entry, int teleport) {
    synchronized (player) {
      if (entry == null
          || teleport != 0
          || !ordinaryContext(player)
          || GameData.getMapEntranceExcelMap().get(entry.getId()) != entry) return false;
      for (var state : player.getGameplay().getSubMissions().values()) {
        var sub = state == null ? null : GameplayDefinitions.subs.get(state.getQuestId());
        if (sub != null && authorizes(player, sub, entry)) return true;
      }
      return false;
    }
  }

  private static boolean ordinaryContext(Player player) {
    var scene = player.getScene();
    var g = player.getGameplay();
    return scene != null
        && scene.getPlayer() == player
        && scene.getPlaneId() == player.getPlaneId()
        && scene.getFloorId() == player.getFloorId()
        && scene.getEntryId() == player.getEntryId()
        && scene.getDimensionId() == 0
        && scene.getStoryLineId() == 0
        && player.getBattle() == null
        && player.getInstance() == null
        && g.getCurrentMissionRaid() == 0
        && g.getTrialRun() == null
        && !g.getRogue().isInside()
        && !g.getParkourVenue().inside()
        && g.getStoryLines().getCurrentId() == 0
        && !g.getStoryLines().isTransitionPending();
  }

  private static boolean authorizes(
      Player player, MissionInfo.SubMissionInfo sub, MapEntranceExcel entry) {
    if (!MissionStateRecords.doing(
            player.getGameplay().getSubMissions().get(sub.getId()), sub.getId(), 1)
        || sub.getLevelPlaneID() != player.getPlaneId()
        || sub.getLevelFloorID() != player.getFloorId()
        || sub.getParamInt1() != entry.getPlaneID()
        || sub.getParamInt2() != entry.getFloorID()
        || !player.getMissionManager().canFinishFromAction(sub.getId())
        || !predecessors(player, sub)
        || literalEntry(sub) != entry.getId()) return false;
    var graph = GameData.getMissionInfos().get(sub.getMainMissionID());
    var main = GameData.getMainMissionExcelMap().get(sub.getMainMissionID());
    if (graph == null
        || main == null
        || main.getId() != sub.getMainMissionID()
        || graph.getMainMissionID() != main.getId()
        || graph.getSubMissionById(sub.getId()) != sub) return false;
    // Unrelated entrance prerequisites still apply. Do not complete any task to gain entry.
    return Arrays.stream(entry.getFinishMainMissionList())
            .allMatch(
                id -> id == sub.getMainMissionID() || MissionStateRecords.mainCompleted(player, id))
        && Arrays.stream(entry.getFinishSubMissionList())
            .allMatch(id -> id == sub.getId() || StoryLineConditions.finished(player, id));
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

  private static int literalEntry(MissionInfo.SubMissionInfo sub) {
    if (!DailyMissionTravelRules.enterFloor(sub)) return 0;
    return literalTask(sub).get("EntranceID").getAsInt();
  }

  private static com.google.gson.JsonObject literalTask(MissionInfo.SubMissionInfo sub) {
    return JsonParser.parseString(sub.getTask().getSourceDefinition())
            .getAsJsonObject()
            .getAsJsonArray("OnStartSequece")
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("TaskList")
            .get(0)
            .getAsJsonObject();
  }

  private MissionSceneEntrances() {}
}
