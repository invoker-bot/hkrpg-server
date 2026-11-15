package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;

/** Explicit, player-owned permissions. A missing legacy value grants no room access. */
@Entity(useDiscriminator = false)
public final class TrainCakeSocialSettings {
  private boolean allowStrangers;
  private boolean allowEntry;
  private boolean allowBox;

  public TrainCakeSocialSettings() {}

  public TrainCakeSocialSettings(boolean allowStrangers, boolean allowEntry, boolean allowBox) {
    this.allowStrangers = allowStrangers;
    this.allowEntry = allowEntry;
    this.allowBox = allowBox;
  }

  public boolean allowStrangers() { return allowStrangers; }
  public boolean allowEntry() { return allowEntry; }
  public boolean allowBox() { return allowBox; }

  public static TrainCakeSocialSettings current(Player player) {
    var value = player.getGameplay().getTrainCakeSocialSettings();
    return value == null ? new TrainCakeSocialSettings() : value;
  }

  public static Retcode update(Player player, TrainCakeSocialSettings next) {
    synchronized (player) {
      var gameplay = player.getGameplay();
      var previous = gameplay.getTrainCakeSocialSettings();
      if (previous != null && previous.allowStrangers == next.allowStrangers
          && previous.allowEntry == next.allowEntry && previous.allowBox == next.allowBox)
        return Retcode.SUCC;
      gameplay.trainCakeSocialSettings(next);
      try {
        player.save();
        org.hkrpg.server.protocol.TrainCakeSocial46.invalidateRoomAccess();
        return Retcode.SUCC;
      } catch (RuntimeException failed) {
        gameplay.trainCakeSocialSettings(previous);
        org.hkrpg.server.GameDiagnostics.saveFailed(player, "train-cake-permission", 0, 0, failed);
        return Retcode.SERVER_INTERNAL_ERROR;
      }
    }
  }
}
