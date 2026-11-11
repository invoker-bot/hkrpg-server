package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.scene.Scene;
import java.util.Set;

/** Native omitted LoadOnInitial is false; GroupIDList binds dormant groups to individual steps. */
public final class NativeMissionGroups {
  static void markSource(GroupInfo group, JsonObject raw) {
    var initial=raw.get("LoadOnInitial");
    var hoyo=raw.get("IsHoyoGroup");
    boolean stepOwned=hoyo!=null && hoyo.isJsonPrimitive()
        && hoyo.getAsJsonPrimitive().isBoolean() && hoyo.getAsBoolean();
    if (group.getCategory()==GroupInfo.GroupCategory.Mission && group.getOwnerMainMissionID()>0
        && (!raw.has("LoadCondition") || stepOwned)
        && (initial==null || initial.isJsonPrimitive() && initial.getAsJsonPrimitive().isBoolean()
            && !initial.getAsBoolean())) group.setNativeDormantMissionSource(true);
  }

  public static boolean supports(Scene scene) {
    return scene!=null && scene.getFloorInfo()!=null && scene.getPlayer().getSession()!=null
        && scene.getPlayer().getSession().getProtocol().experimental()
        && switch(scene.getPlaneType()){case Town,Maze,Train,Raid -> true; default -> false;};
  }

  public static boolean usesBindings(Scene scene, GroupInfo group) {
    return supports(scene) && group!=null && group.isNativeDormantMissionSource()
        && scene.getResourceGroups().get(group.getId())==group;
  }

  static boolean matches(Scene scene, GroupInfo group, MissionInfo.SubMissionInfo sub) {
    if (sub==null || sub.getMainMissionID()!=group.getOwnerMainMissionID()
        || sub.getLevelPlaneID()!=scene.getPlaneId() || sub.getLevelFloorID()!=scene.getFloorId()
        || sub.getGroupIDList()==null || !sub.getGroupIDList().contains(group.getId())) return false;
    if (sub.getStoryLineID()>0 && sub.getStoryLineID()!=scene.getStoryLineId()) return false;
    return sub.getStoryLineIDList()==null || sub.getStoryLineIDList().isEmpty()
        || sub.getStoryLineIDList().contains(scene.getStoryLineId());
  }

  public static boolean boundToCurrentStep(Scene scene, GroupInfo group) {
    var player=scene.getPlayer();
    for (var state:player.getGameplay().getSubMissions().values()) {
      if (state==null) continue;
      var sub=GameplayDefinitions.subs.get(state.getQuestId());
      if (matches(scene,group,sub)
          && MissionStateRecords.subAccepted(player,sub.getId())
          && MissionStateRecords.doing(state,sub.getId(),Math.max(1,sub.getProgress()))) return true;
    }
    return false;
  }

  public static void addSavedBindings(Scene scene, GroupInfo group, Set<Integer> subs) {
    for (var state:scene.getPlayer().getGameplay().getSubMissions().values()) {
      if (state!=null && matches(scene,group,GameplayDefinitions.subs.get(state.getQuestId())))
        subs.add(state.getQuestId());
    }
  }

  private NativeMissionGroups() {}
}
