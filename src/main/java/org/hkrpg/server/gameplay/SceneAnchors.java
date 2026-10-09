package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.*;

/** Individual anchor progress never changes shared floor definitions. */
public final class SceneAnchors {
  public static PropInfo find(int entryId, int groupId, int instance) {
    var entry = GameData.getMapEntranceExcelMap().get(entryId);
    var plane = entry == null ? null : GameData.getMazePlaneExcelMap().get(entry.getPlaneID());
    if (plane == null
        || !Set.of(
                emu.lunarcore.game.enums.PlaneType.Town,
                emu.lunarcore.game.enums.PlaneType.Maze,
                emu.lunarcore.game.enums.PlaneType.Train)
            .contains(plane.getPlaneType())) return null;
    var floor =
        entry == null ? null : GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID());
    var group = floor == null ? null : floor.getGroups().get(groupId);
    if (group == null) return null;
    var matches = group.getPropList().stream().filter(p -> p.getID() == instance).toList();
    if (matches.size() != 1) return null;
    var prop = matches.getFirst();
    var excel = GameData.getPropExcelMap().get(prop.getPropID());
    if (prop.isIsDelete()
        || prop.isIsClientOnly()
        || prop.getMappingInfoID() <= 0
        || prop.getAnchorID() <= 0
        || prop.getAnchorGroupID() <= 0
        || excel == null
        || !excel.getPropStateList().contains(PropState.CheckPointEnable)
        || floor.getAnchorInfo(prop.getAnchorGroupID(), prop.getAnchorID()) == null
        || floor.getCachedTeleports().get(prop.getMappingInfoID()) != prop) return null;
    return prop;
  }

  private static String key(MapEntranceExcel entry, PropInfo prop) {
    return "prop-state:"
        + entry.getPlaneID()
        + ":"
        + entry.getFloorID()
        + ":"
        + prop.getGroup().getId()
        + ":"
        + prop.getID();
  }

  public static PropState state(Player player, MapEntranceExcel entry, PropInfo prop) {
    if (player == null) return prop.getState();
    Integer saved = player.getGameplay().getStoryValues().get(key(entry, prop));
    if (saved == null) return prop.getState();
    for (var state : GameData.getPropExcelMap().get(prop.getPropID()).getPropStateList())
      if (state.getVal() == saved) return state;
    return prop.getState();
  }

  public static boolean unlocked(Player player, MapEntranceExcel entry, PropInfo prop) {
    return prop != null
        && find(entry.getId(), prop.getGroup().getId(), prop.getID()) == prop
        && state(player, entry, prop) == PropState.CheckPointEnable;
  }

  public static SortedSet<Integer> unlocked(Player player, MapEntranceExcel entry) {
    var ids = new TreeSet<Integer>();
    var floor = GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID());
    if (floor != null)
      for (var prop : floor.getCachedTeleports().values())
        if (unlocked(player, entry, prop)) ids.add(prop.getMappingInfoID());
    return ids;
  }

  public static boolean activate(Player player, int entryId, int groupId, int instance) {
    var prop = find(entryId, groupId, instance);
    if (prop == null) return false;
    var entry = GameData.getMapEntranceExcelMap().get(entryId);
    player
        .getGameplay()
        .getStoryValues()
        .put(key(entry, prop), PropState.CheckPointEnable.getVal());
    var scene = player.getScene();
    if (scene != null
        && scene.getPlaneId() == entry.getPlaneID()
        && scene.getFloorId() == entry.getFloorID())
      for (var entity : scene.getEntitiesByGroup(EntityProp.class, groupId))
        if (entity.getInstId() == instance && entity.getPropId() == prop.getPropID())
          entity.setState(PropState.CheckPointEnable);
    return true;
  }

  private SceneAnchors() {}
}
