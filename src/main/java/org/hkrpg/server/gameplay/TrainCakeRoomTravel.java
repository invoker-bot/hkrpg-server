package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;

/** A recoverable return point for a transient social scene. */
public final class TrainCakeRoomTravel {
  public static Retcode prepare(Player player) {
    synchronized (player) {
      var scene = player.getScene();
      if (hasPending(player) || scene == null || scene.getDimensionId() != 0
          || scene.getStoryLineId() != 0 || player.getGameplay().getStoryLines().getCurrentId() != 0
          || player.getGameplay().getStoryLines().isTransitionPending()
          || player.getBattle() != null || player.getInstance() != null
          || player.getGameplay().getTrialRun() != null
          || player.getGameplay().getCurrentMissionRaid() != 0
          || player.getGameplay().getRogue().isInside()
          || player.getGameplay().getParkourVenue().inside()
          || scene.getPlaneId() != player.getPlaneId() || scene.getFloorId() != player.getFloorId()
          || scene.getEntryId() != player.getEntryId() || player.getPos() == null || player.getRot() == null)
        return Retcode.SYSTEM_BUSY;
      var location = new TrainCakeReturnLocation(player);
      if (!location.valid(player)) return Retcode.PLAYER_DATA_ERROR;
      player.getGameplay().trainCakeReturnLocation(location);
      try { player.save(); return Retcode.SUCC; }
      catch (RuntimeException failed) {
        player.getGameplay().trainCakeReturnLocation(null);
        org.hkrpg.server.GameDiagnostics.saveFailed(player, "train-room-bookmark", 0, 0, failed);
        return Retcode.SERVER_INTERNAL_ERROR;
      }
    }
  }

  public static boolean hasPending(Player player) {
    return player.getGameplay().getTrainCakeReturnLocation() != null;
  }

  public static Retcode returnToOrigin(Player player) {
    synchronized (player) {
      var location = player.getGameplay().getTrainCakeReturnLocation();
      if (location == null || !location.valid(player)) return Retcode.PLAYER_DATA_ERROR;
      var previous = player.captureSceneLocation();
      var entities = new java.util.IdentityHashMap<emu.lunarcore.game.avatar.GameAvatar, Integer>();
      for (var avatar : player.getAvatars().getAvatars().values()) entities.put(avatar, avatar.getEntityId());
      try {
        player.setSceneTeleportLocation(location.teleport());
        if (!player.loadTrainRoomScene(location.plane(), location.floor(), location.entry(),
            location.pos(), location.rot(), 0)) {
          player.restoreSceneLocation(previous);
          entities.forEach((avatar, id) -> avatar.setEntityId(id));
          return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
        }
        player.restoreTrainRoomReturn(location);
        player.getGameplay().trainCakeReturnLocation(null);
        player.save();
        return Retcode.SUCC;
      } catch (RuntimeException failed) {
        player.restoreSceneLocation(previous);
        player.getGameplay().trainCakeReturnLocation(location);
        entities.forEach((avatar, id) -> avatar.setEntityId(id));
        org.hkrpg.server.GameDiagnostics.saveFailed(player, "train-room-return", 0, 0, failed);
        return Retcode.SERVER_INTERNAL_ERROR;
      }
    }
  }

  /** Before loading the login scene or refreshing missions, recover an interrupted visit. */
  public static void recoverOnLogin(Player player) {
    synchronized (player) {
      var location = player.getGameplay().getTrainCakeReturnLocation();
      if (location == null) return;
      if (player.getScene() != null || !location.valid(player))
        throw new IllegalStateException("Invalid social room return point");
      var previous = player.captureSceneLocation();
      player.restoreTrainRoomReturn(location);
      player.getGameplay().trainCakeReturnLocation(null);
      try { player.save(); }
      catch (RuntimeException failed) {
        player.restoreSceneLocation(previous);
        player.getGameplay().trainCakeReturnLocation(location);
        org.hkrpg.server.GameDiagnostics.saveFailed(player, "train-room-recover", 0, 0, failed);
        throw failed;
      }
    }
  }
  private TrainCakeRoomTravel() {}
}
