package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.Objects;

/** Destruction receipts are bound to the actual run, room and acquired curio. */
public final class RoguePropMiracle {
  // The pinned ability reads hash -924445233. Its original spelling is absent from the dump.
  // This ASCII compatibility key has exactly that native hash; it is not a guessed source name.
  static final String COUNT_KEY = "RoguePropCounter_0_7394D591";

  public static Destruction prepare(Player player, EntityProp prop) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      var scene = player.getScene();
      var room = GameplayDefinitions.rooms.get(run.getRoomId());
      if (!validContext(player, run, scene)
          || prop == null
          || !scene.canDestroyProp(prop)
          || prop.getGroupId() <= 0
          || prop.getInstId() <= 0
          || prop.getPropInfo() == null
          || prop.getPropInfo().getID() != prop.getInstId()
          || prop.getPropInfo().getPropID() != prop.getPropId()
          || GameData.getPropExcelMap().get(prop.getPropId()) != prop.getExcel()
          || room.GroupWithContent() == null
          || !room.GroupWithContent().containsKey(prop.getGroupId())) return null;
      return new Destruction(
          player,
          run,
          scene,
          prop,
          prop.getEntityId(),
          identity(run, scene, prop),
          run.getMiracles().get(58).getAcquisitionId());
    }
  }

  private static String identity(GameplayState.RogueRun run, Scene scene, EntityProp prop) {
    return run.getSiteId()
        + ":"
        + run.getRoomId()
        + ":"
        + scene.getPlaneId()
        + ":"
        + scene.getFloorId()
        + ":"
        + prop.getGroupId()
        + ":"
        + prop.getInstId();
  }

  private static boolean validContext(Player player, GameplayState.RogueRun run, Scene scene) {
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    var entry = room == null ? null : GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    return scene != null
        && entry != null
        && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
        && run.isInside()
        && !run.pending()
        && !player.isInBattle()
        && RogueMiracles.active(run, 58)
        && scene.getPlaneType() == PlaneType.Rogue
        && !run.getRoster().isEmpty()
        && player.getEntryId() == room.MapEntrance()
        && scene.getPlaneId() == entry.getPlaneID()
        && scene.getFloorId() == entry.getFloorID()
        && Objects.equals(run.getRooms().get(run.getSiteId()), run.getRoomId());
  }

  public static final class Destruction {
    private final Player player;
    private final GameplayState.RogueRun run;
    private final Scene scene;
    private final EntityProp prop;
    private final int entity;
    private final String key;
    private final String acquisition;
    private boolean completed;

    private Destruction(
        Player player,
        GameplayState.RogueRun run,
        Scene scene,
        EntityProp prop,
        int entity,
        String key,
        String acquisition) {
      this.player = player;
      this.run = run;
      this.scene = scene;
      this.prop = prop;
      this.entity = entity;
      this.key = key;
      this.acquisition = acquisition;
    }

    /** Scene calls this only after the validated entity has actually been removed. */
    public void complete() {
      synchronized (player) {
        if (completed
            || player.getGameplay().getRogue() != run
            || player.getScene() != scene
            || !validContext(player, run, scene)
            || prop.getEntityId() != 0
            || scene.getEntities().get(entity) == prop
            || !Objects.equals(key, identity(run, scene, prop))
            || !Objects.equals(acquisition, run.getMiracles().get(58).getAcquisitionId())) return;
        completed = true;
        if (run.getMiracles().get(58).rememberDestruction(key)) player.save();
      }
    }
  }

  private RoguePropMiracle() {}
}
