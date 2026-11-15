package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.util.Position;

/** Durable return point written before entering a transient social room. */
@Entity(useDiscriminator = false)
public final class TrainCakeReturnLocation {
  private int version = 1;
  private int uid, plane, floor, entry, world;
  private Position pos, rot;
  private SceneTeleportLocation teleport;

  public TrainCakeReturnLocation() {}

  TrainCakeReturnLocation(Player player) {
    uid = player.getUid(); plane = player.getPlaneId(); floor = player.getFloorId();
    entry = player.getEntryId(); world = player.getWorldId();
    pos = new Position(player.getPos()); rot = new Position(player.getRot());
    teleport = player.getSceneTeleportLocation();
  }

  boolean valid(Player player) {
    var entrance = GameData.getMapEntranceExcelMap().get(entry);
    var originalPlane = GameData.getMazePlaneExcelMap().get(plane);
    if (version != 1 || uid <= 0 || uid != player.getUid() || plane <= 0 || floor <= 0
        || entry <= 0 || world <= 0 || pos == null || rot == null || entrance == null
        || entrance.getPlaneID() != plane || entrance.getFloorID() != floor
        || originalPlane == null || GameData.getFloorInfo(plane, floor) == null) return false;
    return switch (originalPlane.getPlaneType()) {
      case Town, Maze, Train -> true;
      default -> false;
    };
  }

  public int plane() { return plane; }
  public int floor() { return floor; }
  public int entry() { return entry; }
  public int world() { return world; }
  public Position pos() { return new Position(pos); }
  public Position rot() { return new Position(rot); }
  public SceneTeleportLocation teleport() { return teleport; }
}
