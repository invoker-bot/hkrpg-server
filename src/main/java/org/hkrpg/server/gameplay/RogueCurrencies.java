package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.player.Player;

/** Persistent shared universe currencies 33/34; only actual inventory grants change balances. */
public final class RogueCurrencies {
  public static int balance(Player player, int id) {
    synchronized (player) {
      return id == 33 || id == 34
          ? player.getGameplay().getRogueCurrencies().getOrDefault(id, 0)
          : 0;
    }
  }

  public static boolean change(Player player, int id, int amount) {
    synchronized (player) {
      var excel = GameData.getItemExcelMap().get(id);
      if ((id != 33 && id != 34)
          || excel == null
          || excel.getItemMainType() != ItemMainType.Virtual
          || excel.getPileLimit() <= 0) return false;
      long next = (long) balance(player, id) + amount;
      if (next < 0 || next > excel.getPileLimit()) return false;
      player.getGameplay().getRogueCurrencies().put(id, (int) next);
      return true;
    }
  }

  private RogueCurrencies() {}
}
