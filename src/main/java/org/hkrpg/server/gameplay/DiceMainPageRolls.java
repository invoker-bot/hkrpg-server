package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;

/** Native empty roll report, with the pinned resource's 1500 millisecond cooldown. */
public final class DiceMainPageRolls {
  public static Retcode accept(Player player, DiceCombatCatalog catalog, Instant now) {
    synchronized (player) {
      if (catalog == null || !DiceProgressionRules.available())
        return Retcode.REWARD_CONFIG_NOT_EXIST;
      if (!DiceProgression.valid(player, catalog) || !DicePveMissionReports.valid(player))
        return Retcode.PLAYER_DATA_ERROR;
      var gameplay = player.getGameplay();
      var before = gameplay.getDiceRolls();
      if (!before.valid()) return Retcode.PLAYER_DATA_ERROR;
      long time;
      try {
        time = now.toEpochMilli();
      } catch (RuntimeException invalid) {
        return Retcode.REQ_PARA_INVALID;
      }
      if (time <= 0 || time > 253402300799000L || time < before.getLastMillis())
        return Retcode.REQ_PARA_INVALID;
      if (!catalog.available(player, now)) return Retcode.SHOP_NOT_OPEN;
      if (player.getBattle() != null
          || gameplay.getStoryLines().isTransitionPending()
          || gameplay.getRogue().isInside()
          || gameplay.getTrialRun() != null
          || gameplay.getCurrentMissionRaid() != 0
          || gameplay.getRhythm().getActiveLevel() != 0
          || gameplay.getParkour().getActiveLevel() != 0
          || gameplay.getParkourVenue().inside()
          || DiceProgression.pending(player)
          || DicePveMissionReports.pending(player)
          || gameplay.getDiceCombat().getPurchases().values().stream()
              .anyMatch(r -> r == null || !r.isGranted())
          || gameplay.getOfferings().values().stream()
              .anyMatch(
                  o ->
                      o == null
                          || o.getClaims() == null
                          || o.getSubmissions() == null
                          || o.getClaims().stream().anyMatch(c -> c == null || !c.isGranted())
                          || o.getSubmissions().stream().anyMatch(d -> d == null || !d.isPaid())))
        return Retcode.SYSTEM_BUSY;
      if (before.getCount() > 0 && time - before.getLastMillis() < 1500) return Retcode.SYSTEM_BUSY;
      if (before.getCount() == 0xffffffffL) return Retcode.PLAYER_DATA_ERROR;
      gameplay.diceRolls(new DiceRollState(before.getCount() + 1, time));
      try {
        player.save();
      } catch (RuntimeException failure) {
        gameplay.diceRolls(before);
        throw failure;
      }
      player.getQuestManager().onDiceRollProgress();
      return Retcode.SUCC;
    }
  }

  private DiceMainPageRolls() {}
}
