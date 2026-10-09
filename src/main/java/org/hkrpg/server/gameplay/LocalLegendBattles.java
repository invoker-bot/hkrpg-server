package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.List;

/** Validates access and the whole source party before creating or publishing any battle. */
public final class LocalLegendBattles {
  public record Entry(Retcode retcode, LocalLegendBattle battle) {}

  public static Entry start(
      Player player,
      int group,
      int difficulty,
      List<LocalLegendParty.Member> members,
      Instant time) {
    synchronized (player) {
      var g = player.getGameplay();
      if (player.isInBattle()
          || player.getInstance() != null
          || g.getCurrentMissionRaid() != 0
          || g.getTrialRun() != null
          || g.getRogue().isInside()
          || g.getStoryLines().getCurrentId() != 0
          || g.getStoryLines().isTransitionPending()
          || g.getParkourVenue().inside()
          || g.getParkour().getActiveLevel() != 0
          || g.getRhythm().getActiveLevel() != 0) return new Entry(Retcode.SYSTEM_BUSY, null);
      var level = LocalLegendCatalog.level(group, difficulty);
      if (!LocalLegendAccess.available(player, level, time))
        return new Entry(Retcode.REQ_PARA_INVALID, null);
      var stage = level.stage(player.getWorldLevel());
      if (LocalLegendStageBinding.identity(level, stage) == null)
        return new Entry(Retcode.REQ_PARA_INVALID, null);
      var party = LocalLegendParty.prepare(player, level, members);
      if (party == null) return new Entry(Retcode.REQ_PARA_INVALID, null);
      var battle = new LocalLegendBattle(player, level, stage, party);
      player.setBattle(battle);
      return new Entry(Retcode.SUCC, battle);
    }
  }

  private LocalLegendBattles() {}
}
