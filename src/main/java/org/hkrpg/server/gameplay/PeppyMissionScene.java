package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import java.util.List;

/** The original base-zone group and the two mission graphs that create its actors. */
public final class PeppyMissionScene {
  private static final int MAIN = 2000211, PLANE = 20001, FLOOR = 20001001, GROUP = 70;
  private static final int BATTLE = 200021114, OLD_FIGHT_MARKER = 200021115, DAN_HENG = 200021116;
  private static final String GROUP_SOURCE =
      "d1992477e3115b3bf8e06e40925f21a26be3d1e8981e3d12daf693af8677db20";

  public static final List<Integer> SCENE_GRAPH_OBJECTIVES = List.of(BATTLE, DAN_HENG);

  static boolean matchesSource(GroupInfo group, JsonObject raw) {
    return group.getOwnerMainMissionID() == MAIN
        && group.getCategory() == GroupInfo.GroupCategory.Mission
        && GROUP_SOURCE.equals(TimelineActivityRules.fingerprint(raw));
  }

  public static boolean clientGroup(Scene scene, int id) {
    if (id != GROUP || !ClientNpcGroups.supports(scene)
        || scene.getPlaneId() != PLANE || scene.getFloorId() != FLOOR) return false;
    var source = scene.getResourceGroups().get(id);
    return source != null && source.getOwnerMainMissionID() == MAIN
        && source.isClientOwnedNpcSource();
  }

  static boolean retiredFightMarker(Player player, MissionInfo.SubMissionInfo source) {
    // The waypoint is on the pre-battle graph's temporary trigger. Keep any real
    // legacy Talk completion, and retire the unfinished marker after its fight.
    if (source.getId() != OLD_FIGHT_MARKER || source.getMainMissionID() != MAIN
        || source.getLevelPlaneID() != PLANE || source.getLevelFloorID() != FLOOR
        || !"Auto".equals(source.getTakeType()) || !"Talk".equals(source.getFinishType())
        || !"Equal".equals(source.getParamType()) || !"Mission_200021115".equals(source.getParamStr1())
        || source.getProgress() != 1 || !source.getMissionJsonPath().isBlank()
        || source.getTask() != null || source.getFinishActionList().length != 0
        || !MissionStateRecords.subCompleted(player, BATTLE)) return false;
    var battle = GameplayDefinitions.subs.get(BATTLE);
    return StoryBattleEvents.worldWin(BATTLE).matches(battle);
  }

  private PeppyMissionScene() {}
}
