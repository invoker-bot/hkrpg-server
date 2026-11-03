package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;

/** Inventory follower selection; maze-skill summons have a separate lifecycle. */
public final class PetSelection {
  public static boolean configured(int id) {
    var row = GameData.getPetExcelMap().get(id);
    return id > 0 && row != null && row.getPetItemID() > 0 && row.getSummonUnitID() > 0;
  }

  public static Retcode select(Player player, int id) {
    synchronized (player) {
      if (id < 0 || id > 0 && (!configured(id) || player.getUnlocks() == null
          || !player.getUnlocks().getPets().contains(id))) return Retcode.REQ_PARA_INVALID;
      int previous = player.getPetId();
      if (previous == id) return Retcode.SUCC;
      player.setPetId(id);
      try {
        player.save();
      } catch (RuntimeException failed) {
        player.setPetId(previous);
        return Retcode.SERVER_INTERNAL_ERROR;
      }
      return Retcode.SUCC;
    }
  }

  private PetSelection() {}
}
