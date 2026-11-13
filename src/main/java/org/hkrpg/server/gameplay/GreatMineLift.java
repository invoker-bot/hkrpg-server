package org.hkrpg.server.gameplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify;
import java.util.Arrays;
import java.util.List;
import org.hkrpg.server.GameDiagnostics;

/** Execute the one original automatic lift-state objective; never a general script interpreter. */
final class GreatMineLift {
  private static final int MAIN = 1010501, SUB = 101050116, PREVIOUS = 101050111;
  private static final String SOURCE = "4fe1e410cf67f30ee38fb8997c2ca67130ef161b0502ff1a464af1dbac7661b5";
  private static final JsonElement SCRIPT = JsonParser.parseString("""
      {"OnInitSequece":[],"OnStartSequece":[{"TaskList":[
        {"$type":"RPG.GameCore.ChangePropState"}]}],"Type":"Mission"}
      """);

  private static boolean source(MissionInfo.SubMissionInfo c) {
    try {
      var main = GameData.getMissionInfos().get(MAIN);
      return c != null && c.getId() == SUB && c.getMainMissionID() == MAIN
          && "AnySequence".equals(c.getTakeType())
          && Arrays.equals(c.getTakeParamIntList(), new Integer[] {PREVIOUS})
          && c.getTakeParamStrList().length == 0
          && "PropState".equals(c.getFinishType()) && "Equal".equals(c.getParamType())
          && c.getLevelPlaneID() == 20121 && c.getLevelFloorID() == 20121001
          && c.getMazePlaneID() == 20121 && c.getMazeFloorID() == 20121001
          && c.getParamInt1() == 3 && c.getParamInt2() == 300005 && c.getParamInt3() == 15
          && c.getProgress() == 1 && !c.isBackTrack()
          && c.getParamStr1().isEmpty() && c.getParamIntList().length == 0 && c.getParamItemList().isEmpty()
          && c.getIgnoreVerseParamList().isEmpty() && c.getStoryLineIDList().isEmpty() && c.getStoryLineID() == 0
          && c.getGroupIDList().isEmpty() && c.getRequiredNPCSeriesIDList().isEmpty()
          && c.getSubRewardID() == 0 && c.getCustomValueReward().length == 0
          && c.getFinishActionList().length == 0 && c.getCustomValueList().length == 0
          && "Config/Level/Mission/1010501/Mission_101050116.json".equals(c.getMissionJsonPath())
          && c.getTask() != null && SCRIPT.equals(JsonParser.parseString(c.getTask().getSourceDefinition()))
          && main != null && main.getMainMissionID() == MAIN
          && Arrays.equals(main.getFinishSubMissionList(), new int[] {SUB});
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static EntityProp target(Player player, MissionInfo.SubMissionInfo c) {
    var scene = player.getScene();
    if (!source(c) || !StoryDoors.world(scene) || scene.getPlayer() != player
        || scene.getPlaneId() != 20121 || scene.getFloorId() != 20121001
        || player.getPlaneId() != scene.getPlaneId() || player.getFloorId() != scene.getFloorId()
        || player.getEntryId() != scene.getEntryId() || player.getBattle() != null
        || !scene.getLoadedGroups().contains(3)
        || !MissionStateRecords.mainDoing(player, MAIN)
        || !MissionStateRecords.doing(player.getMissionManager().sub(SUB), SUB, 1)
        || !player.getMissionManager().canFinishFromAction(SUB)
        || !MissionStateRecords.subCompleted(player, PREVIOUS)) return null;
    var group = scene.getResourceGroups().get(3);
    if (group == null || group.getPropList() == null) return null;
    var originals = group.getPropList().stream().filter(p -> p != null && p.getID() == 300005).toList();
    var actors = scene.getEntitiesByGroup(EntityProp.class, 3).stream().filter(p -> p.getInstId() == 300005).toList();
    if (originals.size() != 1 || actors.size() != 1) return null;
    var info = originals.getFirst(); var prop = actors.getFirst();
    return info.getPropID() == 101007 && SOURCE.equals(PropTimelines.source(info))
        && prop.getScene() == scene && prop.getPropInfo() == info && prop.getPropId() == 101007
        && prop.getExcel().getPropStateList().contains(PropState.Elevator2) ? prop : null;
  }

  private static boolean receipt(Player player, EntityProp prop) {
    var state = player.getGameplay().getScenePropStates();
    if (!state.valid()) return false;
    var saved = state.get(PropTimelines.key(prop.getScene(), 3, 300005));
    return saved != null && saved.getProp() == 101007 && saved.getState() == 15
        && SOURCE.equals(saved.getSource()) && saved.getAcceptance().isEmpty();
  }

  /** Persist the authored world effect first. MissionManager then advances its usual completion. */
  static boolean apply(Player player, MissionInfo.SubMissionInfo c) {
    if (c.getId() != SUB || !MissionStateRecords.doing(player.getMissionManager().sub(SUB), SUB, 1)) return false;
    // Login initializes missions before assigning the ordinary scene; this is an expected deferral.
    if (player.getScene() == null) return false;
    var prop = target(player, c);
    if (prop == null) {
      GameDiagnostics.missionPropState(player, SUB, 3, 300005, 15, "source-or-context-rejected");
      return false;
    }
    var before = prop.getState();
    if (!receipt(player, prop)) {
      var old = player.getGameplay().getScenePropStates();
      if (!old.valid()) {
        GameDiagnostics.missionPropState(player, SUB, 3, 300005, 15, "invalid-saved-state");
        return false;
      }
      var scene = prop.getScene();
      var saved = new ScenePropState.Snapshot(20121, 20121001, 0, 0, 3, 300005, 101007, 15, SOURCE, "");
      player.getGameplay().scenePropStates(old.with(List.of(saved)));
      prop.setState(PropState.Elevator2, false);
      try {
        player.save();
      } catch (RuntimeException failed) {
        player.getGameplay().scenePropStates(old);
        prop.setState(before, false);
        GameDiagnostics.saveFailed(player, "mission-prop-state", SUB, 0, failed);
        return false;
      }
      GameDiagnostics.missionPropState(player, SUB, 3, 300005, 15, "applied");
    } else prop.setState(PropState.Elevator2, false);
    if (before != prop.getState() && prop.getScene().isLoaded())
      player.sendPacket(new PacketSceneGroupRefreshScNotify(prop.getScene(), prop, null));
    return before != prop.getState();
  }

  /** A persisted effect can recover the interrupted mission callback, including non-backtrack 116. */
  static boolean satisfied(Player player, MissionInfo.SubMissionInfo c) {
    if (c.getId() != SUB) return false;
    var prop = target(player, c);
    return prop != null && prop.getState() == PropState.Elevator2 && receipt(player, prop);
  }

  private GreatMineLift() {}
}
