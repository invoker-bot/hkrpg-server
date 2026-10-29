package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import java.util.Map;

/** The office prop and the three authored rooms for the second tutorial. */
public final class RogueSecondStoryEntry {
  private static final String OFFICE_GRAPH =
      "Config/Level/Props/Chap00/InitLevelGraph_Prop_Chapter00_RogueEntry_02.json";
  private static final Map<Integer, Integer> ROOMS = Map.of(1, 201, 2, 202, 3, 203);

  public static boolean officeProp(Scene scene, GroupInfo group, PropInfo prop) {
    return scene != null && scene.getPlaneId() == 80112 && scene.getFloorId() == 80112001
        && scene.getDimensionId() == 0 && group != null && group.getId() == 14
        && scene.getFloorInfo() != null && scene.getResourceGroups().get(14) == group
        && prop != null && prop.getID() == 300002 && prop.getPropID() == 1003
        && prop.getMappingInfoID() == 2219 && !prop.isIsDelete() && prop.isLoadOnInitial()
        && OFFICE_GRAPH.equals(prop.getInitLevelGraph()) && prop.getState() == PropState.Open
        && prop.getPos().getX() == 9252 && prop.getPos().getY() == 608
        && prop.getPos().getZ() == 0;
  }

  /** A valid active mission determines which tutorial area 100 actually represents. */
  public static boolean inProgress(Player player) {
    return player != null && MissionStateRecords.mainCompleted(player, 4030001)
        && MissionStateRecords.mainDoing(player, 4030002);
  }

  public static boolean awaiting(Player player) {
    var entry = GameplayDefinitions.subs.get(403000202);
    return entry != null && entry.getId() == 403000202 && entry.getMainMissionID() == 4030002
        && entry.getLevelPlaneID() == 80112 && entry.getLevelFloorID() == 80112001
        && "EnterFloor".equals(entry.getFinishType()) && "Equal".equals(entry.getParamType())
        && entry.getParamInt1() == 80001 && entry.getParamInt2() == 80001002
        && entry.getProgress() == 1 && inProgress(player)
        && !player.getGameplay().getCancelledSubs().contains(403000202)
        && MissionStateRecords.doing(player.getGameplay().getSubMissions().get(403000202), 403000202, 1);
  }

  public static boolean inOffice(Player player) {
    var scene = player.getScene();
    if (scene == null || scene.getPlayer() != player || scene.getPlaneType() != PlaneType.Town
        || scene.getPlaneId() != 80112 || scene.getFloorId() != 80112001
        || scene.getEntryId() != 8011201 || player.getPlaneId() != 80112
        || player.getFloorId() != 80112001 || player.getEntryId() != 8011201
        || scene.getDimensionId() != 0 || scene.getStoryLineId() != 0
        || scene.getFloorInfo() == null) return false;
    var group = scene.getResourceGroups().get(14);
    return group != null && group.getPropList() != null
        && group.getPropList().stream().filter(p -> officeProp(scene, group, p)).count() == 1
        && scene.getEntities(emu.lunarcore.game.scene.entity.EntityProp.class).stream()
            .filter(p -> p.getGroupId() == 14 && p.getInstId() == 300002
                && p.getPropId() == 1003 && p.getState() == PropState.Open
                && officeProp(scene, group, p.getPropInfo())).count() == 1;
  }

  static boolean plan(Map<Integer, Integer> rooms) {
    return ROOMS.equals(rooms);
  }

  static boolean room(GameplayState.RogueRun run, GameplayDefinitions.Room room) {
    return run != null && run.getAreaId() == 100 && run.getMapId() == 2
        && plan(run.getRooms()) && room != null
        && java.util.Objects.equals(ROOMS.get(run.getSiteId()), room.RogueRoomID())
        && run.getRoomId() == room.RogueRoomID() && room.MapEntrance() == 8000102
        && room.GroupID() == switch (room.RogueRoomID()) { case 201 -> 19; case 202 -> 18; case 203 -> 29; default -> 0; };
  }

  static boolean hasAnchor(Player player, GameplayDefinitions.Room room) {
    if (room == null || room.MapEntrance() != 8000102) return false;
    var entry = GameData.getMapEntranceExcelMap().get(8000102);
    var floor = GameData.getFloorInfo(80001, 80001002);
    if (entry == null || entry.getPlaneID() != 80001 || entry.getFloorID() != 80001002
        || floor == null || !floor.isGroupInDimension(room.GroupID(), 0)
        || !player.hasSceneAnchor(8000102, room.GroupID(), 1)) return false;
    var group = floor.getGroups(0).get(room.GroupID());
    if (group == null || group.getAnchorList() == null
        || group.getAnchorList().stream().filter(a -> a != null && a.getID() == 1).count() != 1)
      return false;
    var point = group.getAnchorList().stream().filter(a -> a != null && a.getID() == 1).findFirst().orElseThrow();
    return !point.isIsDelete() && switch (room.RogueRoomID()) {
      case 201 -> point.getPos().getX() == -42980 && point.getPos().getY() == 18610
          && point.getPos().getZ() == -91380 && point.getRot().getY() == 345000;
      case 202 -> point.getPos().getX() == -57010 && point.getPos().getY() == -1055
          && point.getPos().getZ() == -93180 && point.getRot().getY() == 315000;
      case 203 -> point.getPos().getX() == 24735 && point.getPos().getY() == 3443
          && point.getPos().getZ() == -109695 && point.getRot().getY() == 290000;
      default -> false;
    };
  }

  private RogueSecondStoryEntry() {}
}
