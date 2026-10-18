package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.util.Position;

/** Layer reported by an accepted teleport, valid only for that exact saved location. */
@Entity(useDiscriminator = false)
public final class SceneTeleportLocation {
  private int plane, floor, entry, dimension, story, layer;
  private Position pos, rot;

  public SceneTeleportLocation() {}

  /** Called by the existing save lifecycle, after ordinary movement as well as teleports. */
  public static void record(Player player) {
    var scene = player.getScene();
    if (scene == null || scene.getPlayer() != player
        || scene.getPlaneId() != player.getPlaneId() || scene.getFloorId() != player.getFloorId()
        || scene.getEntryId() != player.getEntryId()) return;
    var masks = scene.getFloorInfo() == null ? null : scene.getFloorInfo().getLayerToAreaMask();
    int layer = scene.getTeleportMapLayer();
    if (masks != null && layer > 0 && layer <= masks.length)
      player.setSceneTeleportLocation(new SceneTeleportLocation(scene, layer));
  }

  public SceneTeleportLocation(Scene scene, int layer) {
    plane = scene.getPlaneId();
    floor = scene.getFloorId();
    entry = scene.getEntryId();
    dimension = scene.getDimensionId();
    story = scene.getStoryLineId();
    this.layer = layer;
    pos = new Position(scene.getPlayer().getPos());
    rot = new Position(scene.getPlayer().getRot());
  }

  public int layerFor(Scene scene) {
    var floorInfo = scene.getFloorInfo();
    var layers = floorInfo == null ? null : floorInfo.getLayerToAreaMask();
    return plane == scene.getPlaneId() && floor == scene.getFloorId()
            && entry == scene.getEntryId() && dimension == scene.getDimensionId()
            && story == scene.getStoryLineId() && pos != null && rot != null
            && pos.equals(scene.getPlayer().getPos()) && rot.equals(scene.getPlayer().getRot())
            && layers != null && layer > 0 && layer <= layers.length
        ? layer : 1;
  }
}
