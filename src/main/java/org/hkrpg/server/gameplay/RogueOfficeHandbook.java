package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.Arrays;
import java.util.Objects;

/** Derive the office handbook's state from the actual completed tutorial and its next objective. */
public final class RogueOfficeHandbook {
  private static final String GRAPH =
      "Config/Level/Props/Rogue/InitLevelGraph_Prop_RogueLobby_MechanicalArm_01.json";

  private static boolean available(Scene scene) {
    if (scene == null || scene.getPlayer() == null || scene.getPlaneType() != PlaneType.Town
        || scene.getPlaneId() != 80112 || scene.getFloorId() != 80112001
        || scene.getDimensionId() != 0 || scene.getStoryLineId() != 0
        || scene.getPlayer().getInstance() != null
        || scene.getPlayer().getGameplay().getStoryLines().isTransitionPending()) return false;
    var player = scene.getPlayer();
    var goal = GameplayDefinitions.subs.get(403000222);
    return goal != null && goal.getId() == 403000222 && goal.getMainMissionID() == 4030002
        && goal.getLevelPlaneID() == 80112 && goal.getLevelFloorID() == 80112001
        && "AnySequence".equals(goal.getTakeType())
        && Arrays.equals(goal.getTakeParamIntList(), new Integer[] {403000221})
        && "Talk".equals(goal.getFinishType()) && "Equal".equals(goal.getParamType())
        && "Mission_403000222".equals(goal.getParamStr1()) && goal.getProgress() == 1
        && "Config/Level/Mission/4030002/Mission_403000222.json".equals(goal.getMissionJsonPath())
        && MissionStateRecords.mainCompleted(player, 4030001)
        && MissionStateRecords.subCompleted(player, 403000220)
        && MissionStateRecords.subCompleted(player, 403000221)
        && MissionStateRecords.subAccepted(player, 403000222);
  }

  private static boolean original(EntityProp prop) {
    if (prop == null || prop.getScene() == null || prop.getGroupId() != 3
        || prop.getInstId() != 300003 || prop.getPropId() != 1005) return false;
    var scene = prop.getScene();
    var floor = scene.getFloorInfo();
    var group = scene.getResourceGroups().get(3);
    if (floor == null || !floor.isGroupInDimension(3, scene.getDimensionId())
        || group == null || group.getId() != 3 || group.getPropList() == null) return false;
    var matches = group.getPropList().stream().filter(Objects::nonNull)
        .filter(info -> info.getID() == 300003).toList();
    if (matches.size() != 1 || prop.getPropInfo() == null) return false;
    PropInfo info = matches.getFirst();
    return info.getPropID() == 1005 && GRAPH.equals(info.getInitLevelGraph())
        && !info.isIsDelete() && !info.isIsClientOnly() && info.isLoadOnInitial()
        && info.getState() == PropState.Closed
        && info.getPos().getX() == 5720 && info.getPos().getY() == 201 && info.getPos().getZ() == -7553
        && PropTimelines.source(info).equals(PropTimelines.source(prop.getPropInfo()));
  }

  /** Called during scene construction, before the player's scene pointer is swapped. */
  public static void restore(EntityProp prop) {
    if (original(prop) && prop.getState() == PropState.Closed && available(prop.getScene()))
      prop.setState(PropState.Open, false);
  }

  /** A dialogue can accept the objective while this ordinary group is already loaded. */
  public static void reconcile(Scene scene) {
    if (!available(scene) || scene.getPlayer().getScene() != scene
        || !scene.getLoadedGroups().contains(3)) return;
    var matches = scene.getEntitiesByGroup(EntityProp.class, 3).stream()
        .filter(RogueOfficeHandbook::original).toList();
    if (matches.size() == 1 && matches.getFirst().getState() == PropState.Closed)
      matches.getFirst().setState(PropState.Open, scene.isLoaded());
  }

  private RogueOfficeHandbook() {}
}
