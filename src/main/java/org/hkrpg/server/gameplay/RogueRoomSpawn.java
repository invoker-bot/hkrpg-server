package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.AnchorInfo;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.game.player.Player;

/** Restores a saved room stranded at the shared floor origin, without restarting its run. */
final class RogueRoomSpawn {
  private static AnchorInfo anchor(FloorInfo floor, int groupId, int anchorId) {
    if (groupId <= 0 || anchorId <= 0 || !floor.isGroupInDimension(groupId, 0)) return null;
    var group = floor.getGroups(0).get(groupId);
    if (group == null || group.getAnchorList() == null) return null;
    var points = group.getAnchorList().stream()
        .filter(point -> point != null && point.getID() == anchorId).toList();
    return points.size() == 1 && !points.getFirst().isIsDelete() ? points.getFirst() : null;
  }

  static boolean recoverOnLogin(Player player) {
    synchronized (player) {
      var session = player.getSession();
      var run = player.getGameplay().getRogue();
      var room = GameplayDefinitions.rooms.get(run.getRoomId());
      var area = GameplayDefinitions.areas.get(run.getAreaId());
      if (session == null || !session.getProtocol().experimental() || !run.isInside()
          || run.getAreaId() == 100 || area == null || area.mapId() != run.getMapId()
          || player.isInBattle() || player.getPos() == null || player.getRot() == null
          || (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
              && run.getPhase() != GameplayState.RogueRun.Phase.FINISHED
              && run.getPhase() != GameplayState.RogueRun.Phase.FAILED)
          || room == null || room.RogueRoomID() != run.getRoomId()
          || !java.util.Objects.equals(run.getRooms().get(run.getSiteId()), run.getRoomId())
          || GameplayDefinitions.maps.getOrDefault(run.getMapId(), java.util.List.of()).stream()
              .filter(site -> site.SiteID() == run.getSiteId()).count() != 1
          || player.getEntryId() != room.MapEntrance()) return false;
      var entry = GameData.getMapEntranceExcelMap().get(room.MapEntrance());
      if (entry == null || entry.getPlaneID() != player.getPlaneId()
          || entry.getFloorID() != player.getFloorId()) return false;
      var floor = GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID());
      if (floor == null) return false;
      var correct = anchor(floor, room.GroupID(), 1);
      int oldGroup = entry.getStartGroupID(), oldAnchor = entry.getStartAnchorID();
      if (oldAnchor == 0) {
        var groups = floor.getSimpleGroupList();
        int index = floor.getStartGroupIndex();
        if (groups == null || index < 0 || index >= groups.size() || groups.get(index) == null)
          return false;
        oldGroup = groups.get(index).getID(); oldAnchor = floor.getStartAnchorID();
      }
      var wrong = anchor(floor, oldGroup, oldAnchor);
      if (correct == null || wrong == null || !player.getPos().equals(wrong.getPos())
          || correct.getPos().equals(wrong.getPos())) return false;
      var oldPosition = player.getPos().clone();
      var oldRotation = player.getRot().clone();
      player.getPos().set(correct.getPos()); player.getRot().set(correct.getRot());
      try {
        player.save();
      } catch (RuntimeException failed) {
        player.getPos().set(oldPosition); player.getRot().set(oldRotation);
        emu.lunarcore.LunarCore.getLogger().warn(
            "[UID: {}] Unable to restore universe room entrance", player.getUid(), failed);
        return false;
      }
      return true;
    }
  }

  private RogueRoomSpawn() {}
}
