package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Only the pinned, explicitly shared StoryLine/Content property conditions are supported. */
public final class StoryGroupConditions {
  public static boolean shared(Player player, MissionInfo.SubMissionInfo config) {
    var ignores = config.getIgnoreVerseParamList();
    if (!"Equal".equals(config.getParamType())
        || config.getProgress() != 1
        || config.getParamInt1() <= 0
        || config.getParamInt3() != 0
        || config.getParamStr1() == null
        || config.getParamStr1().isBlank()
        || config.getParamIntList() == null
        || config.getParamIntList().length != 0
        || config.getParamItemList() == null
        || !config.getParamItemList().isEmpty()
        || config.getStoryLineIDList() == null
        || !config.getStoryLineIDList().isEmpty()
        || ignores == null
        || ignores.size() != 2
        || !new HashSet<>(ignores).equals(Set.of("StoryLine", "Content"))
        || config.getMazePlaneID() <= 0
        || config.getMazeFloorID() <= 0
        || config.getLevelPlaneID() != player.getPlaneId()
        || config.getLevelFloorID() != player.getFloorId()
        || player.getGameplay().getStoryLines().isTransitionPending()) return false;
    var floor = GameData.getFloorInfo(config.getMazePlaneID(), config.getMazeFloorID());
    if (floor == null || floor.getFloorID() != config.getMazeFloorID()) return false;
    var scene = player.getScene();
    var group = floor.getGroups(scene == null ? 0 : scene.getDimensionId()).get(config.getParamInt1());
    if (group == null
        || group.getId() != config.getParamInt1()
        || group.getGroupPropertyMap() == null
        || group.getGroupPropertyMap().values().stream()
                .filter(
                    property ->
                        property != null && config.getParamStr1().equals(property.getName()))
                .count()
            != 1) return false;
    // Scene.setStoryGroupProperty owns this shared saved key; defaults are not proof of an event.
    return Objects.equals(
        player
            .getGameplay()
            .getStoryValues()
            .get(
                "group:"
                    + config.getMazeFloorID()
                    + ":"
                    + config.getParamInt1()
                    + ":"
                    + config.getParamStr1()),
        config.getParamInt2());
  }

  private StoryGroupConditions() {}
}
