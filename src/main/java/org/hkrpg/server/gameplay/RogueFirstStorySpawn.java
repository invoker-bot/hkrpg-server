package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.AnchorInfo;
import emu.lunarcore.game.player.Player;
import java.util.Map;

/** The authored first tutorial room begins at group 10's anchor, not the floor origin. */
public final class RogueFirstStorySpawn {
  private static final int ENTRY = 8000101;
  private static final int PLANE = 80001;
  private static final int FLOOR = 80001001;
  private static final Map<Integer, Integer> CONTENT =
      Map.of(10, 1006, 3, 1001, 4, 1002, 5, 1003, 11, 1004, 12, 1005);

  static boolean applies(GameplayState.RogueRun run, GameplayDefinitions.Room room) {
    return run.getAreaId() == 100 && run.getMapId() == 1 && run.getSiteId() == 1
        && run.getRoomId() == 100 && run.getRooms().equals(Map.of(1, 100))
        && room != null && room.RogueRoomID() == 100 && room.RogueRoomType() == 1
        && room.MapEntrance() == ENTRY && room.GroupID() == 10
        && CONTENT.equals(room.GroupWithContent());
  }

  private static AnchorInfo anchor(Player player, GameplayDefinitions.Room room) {
    var entry = GameData.getMapEntranceExcelMap().get(ENTRY);
    var floor = GameData.getFloorInfo(PLANE, FLOOR);
    if (entry == null || entry.getPlaneID() != PLANE || entry.getFloorID() != FLOOR
        || floor == null || !floor.isGroupInDimension(10, 0)
        || !player.hasSceneAnchor(ENTRY, 10, 1)) return null;
    var group = floor.getGroups(0).get(10);
    if (group == null || group.getId() != 10 || group.getAnchorList() == null
        || group.getAnchorList().stream().filter(a -> a != null && a.getID() == 1).count() != 1)
      return null;
    var point = group.getAnchorList().stream().filter(a -> a != null && a.getID() == 1).findFirst().orElseThrow();
    return !point.isIsDelete() && point.getPos().getX() == -23854
        && point.getPos().getY() == 5 && point.getPos().getZ() == 236150
        && point.getRot().getY() == 180000 ? point : null;
  }

  static boolean hasAuthoredAnchor(Player player, GameplayDefinitions.Room room) {
    return anchor(player, room) != null;
  }

  /** Recover only an untouched old run stranded beyond the authored final door. */
  public static boolean recoverOnLogin(Player player) {
    synchronized (player) {
      var session = player.getSession();
      var run = player.getGameplay().getRogue();
      var room = GameplayDefinitions.rooms.get(run.getRoomId());
      var pos = player.getPos();
      if (session == null || !session.getProtocol().experimental()
          || !applies(run, room) || run.isFirstStoryEntrancePlaced()
          || run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING || !run.isInside()
          || player.getPlaneId() != PLANE || player.getFloorId() != FLOOR
          || player.getEntryId() != ENTRY || pos == null || player.getRot() == null
          // The player may have walked back from the old exit without finding an enemy.
          // Leave positions in the vicinity of the authored first encounter untouched.
          || pos.getZ() < -10000 || pos.getZ() >= 220000 || Math.abs((long) pos.getX()) > 30000
          || Math.abs((long) pos.getY()) > 5000 || player.isInBattle()
          || !MissionStateRecords.mainDoing(player, 4030001)
          || !MissionStateRecords.subCompleted(player, 403000102)
          || !MissionStateRecords.doing(player.getGameplay().getSubMissions().get(403000104), 403000104, 1)
          || !run.getCleared().isEmpty() || !run.getProcessedBattles().isEmpty()
          || !run.getRecordedBattleKills().isEmpty() || !run.getDefeatedRogueMonsters().isEmpty()
          || !run.getBlessings().isEmpty() || run.pending()) return false;
      var point = anchor(player, room);
      if (point == null) return false;
      var oldPosition = pos.clone();
      var oldRotation = player.getRot().clone();
      pos.set(point.getPos());
      player.getRot().set(point.getRot());
      run.markFirstStoryEntrancePlaced();
      try {
        player.save();
      } catch (RuntimeException failed) {
        pos.set(oldPosition);
        player.getRot().set(oldRotation);
        run.restoreFirstStoryEntrancePlaced(false);
        emu.lunarcore.LunarCore.getLogger().warn(
            "[UID: {}] Unable to restore first story universe entry", player.getUid(), failed);
        return false;
      }
      return true;
    }
  }

  private RogueFirstStorySpawn() {}
}
