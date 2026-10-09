package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;

/** Acknowledgements describe the actual active party, including guarded story parties. */
public final class LineupLeaders {
  public static Retcode change(Player player, int slot) {
    synchronized (player) {
      if (player.isInBattle() || player.getGameplay().getStoryLines().isTransitionPending())
        return Retcode.SYSTEM_BUSY;
      var lineup = player.getCurrentLineup();
      if (lineup == null || slot < 0 || slot >= lineup.size()) return Retcode.REQ_PARA_INVALID;
      int id = lineup.getAvatars().get(slot);
      var actor = player.getAvatarById(id);
      if (actor == null || actor.getAvatarId() != id) return Retcode.PLAYER_DATA_ERROR;
      if (slot == lineup.getLeader()) return Retcode.SUCC;
      return player.getLineupManager().changeLeader(slot) ? Retcode.SUCC : Retcode.REQ_PARA_INVALID;
    }
  }

  private LineupLeaders() {}
}
