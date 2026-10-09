package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.scene.Scene;
import java.util.Set;

/** A deferred unload applies when rebuilding a normal scene, while its existing group stays. */
public final class DeferredMissionGroups {
  public static boolean tracks(GroupInfo group, Scene scene) {
    return group != null
        && scene != null
        && Set.of(PlaneType.Town, PlaneType.Maze, PlaneType.Train).contains(scene.getPlaneType())
        && (group.getCategory() == null
            || group.getCategory() == GroupInfo.GroupCategory.Normal
            || group.getCategory() == GroupInfo.GroupCategory.Mission)
        && group.getUnloadCondition() != null
        && group.getUnloadCondition().isDelayToLevelReload()
        && scene.getFloorInfo() != null
        && scene.getFloorInfo().getGroups().get(group.getId()) == group;
  }

  static boolean retains(GroupInfo group, Scene scene) {
    return tracks(group, scene) && scene.getLoadedGroups().contains(group.getId());
  }

  private DeferredMissionGroups() {}
}
