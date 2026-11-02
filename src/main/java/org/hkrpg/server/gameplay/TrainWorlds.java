package org.hkrpg.server.gameplay;

import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;

/** The authored train destination is independent of the player's last ordinary world. */
public final class TrainWorlds {
  public static final String KEY = "train:world";

  public static boolean known(int world) {
    var row = GameplayDefinitions.worlds.get(world);
    return world > 0 && row != null && row.ID() == world;
  }

  public static int current(Player player, int fallback) {
    var saved = player.getGameplay().getStoryValues().get(KEY);
    return saved != null && known(saved) ? saved : fallback;
  }

  public static boolean remember(Player player, int world) {
    if (!known(world)) return false;
    return !Integer.valueOf(1)
        .equals(player.getGameplay().getStoryValues().put("train:visited:" + world, 1));
  }

  public static void declare(Player player, int world) {
    remember(player, current(player, player.getWorldId()));
    remember(player, world);
    player.getGameplay().getStoryValues().put(KEY, world);
  }

  public static Retcode select(Player player, long context, int world) {
    synchronized (player) {
      var scene = player.getScene();
      if (scene == null
          || scene.getPlayer() != player
          // The map selects the train world before sending EnterScene from an ordinary map.
          || (scene.getPlaneType() != PlaneType.Train
              && scene.getPlaneType() != PlaneType.Town
              && scene.getPlaneType() != PlaneType.Maze)
          || context == 0
          || scene.getRuntimeId() != context
          || scene.getPlaneId() != player.getPlaneId()
          || scene.getFloorId() != player.getFloorId()
          || scene.getEntryId() != player.getEntryId()) return Retcode.SCENE_ENTITY_NOT_EXIST;
      var state = player.getGameplay();
      if (player.getBattle() != null
          || state.getStoryLines().isTransitionPending()
          || state.getStoryLines().getCurrentId() != 0
          || state.getTrialRun() != null
          || state.getCurrentMissionRaid() != 0
          || state.getParkourVenue().inside()
          || state.getRogue().isInside()
          || state.getStoryActions().values().stream()
              .anyMatch(GameplayState.ActionCursor::isPending)) return Retcode.SYSTEM_BUSY;
      if (!known(world)
          || (world != current(player, 0)
              && world != player.getWorldId()
              && !Integer.valueOf(1).equals(state.getStoryValues().get("train:visited:" + world))))
        return Retcode.REQ_PARA_INVALID;
      boolean changed = !Integer.valueOf(world).equals(state.getStoryValues().get(KEY));
      boolean recorded = remember(player, current(player, player.getWorldId()));
      recorded |= remember(player, world);
      state.getStoryValues().put(KEY, world);
      if (changed || recorded) player.save();
      if (changed) org.hkrpg.server.protocol.TrainWorld46.sync(player);
      return Retcode.SUCC;
    }
  }

  private TrainWorlds() {}
}
