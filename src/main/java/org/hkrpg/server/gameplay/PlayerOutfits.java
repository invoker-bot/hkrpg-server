package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.enums.ItemSubType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Inventory ownership and disjoint authored slots; default units are omitted by the client. */
public final class PlayerOutfits {
  private static final Set<String> SLOTS =
      Set.of(
          "HeadDecor",
          "Coat",
          "BackDecor",
          "Weapon_Warrior",
          "Weapon_Knight",
          "Weapon_Shaman",
          "Weapon_Memory",
          "Weapon_Elation");

  public static Retcode equip(Player player, List<Integer> ids) {
    if (ids.size() > SLOTS.size() || new HashSet<>(ids).size() != ids.size())
      return Retcode.REQ_PARA_INVALID;
    var occupied = new HashSet<String>();
    for (int id : ids) {
      var row = GameData.getPlayerOutfitBaseExcelMap().get(id);
      if (id <= 0
          || row == null
          || row.getItemID() <= 0
          || row.getSlotTypeList() == null
          || row.getSlotTypeList().length == 0) return Retcode.REQ_PARA_INVALID;
      for (String slot : row.getSlotTypeList())
        if (slot == null || !SLOTS.contains(slot) || !occupied.add(slot))
          return Retcode.REQ_PARA_INVALID;
      var item = GameData.getItemExcelMap().get(row.getItemID());
      if (item == null
          || item.getItemMainType() != ItemMainType.Usable
          || item.getItemSubType() != ItemSubType.PlayerOutfit
          || item.getPileLimit() != 1) return Retcode.REWARD_CONFIG_NOT_EXIST;
      if (!player.getInventory().hasItem(row.getItemID(), 1)) return Retcode.ITEM_NOT_ENOUGH;
    }
    var desired = ids.stream().sorted().toList();
    var state = player.getGameplay();
    var before = List.copyOf(state.getEquippedOutfits());
    if (before.equals(desired)) return Retcode.SUCC;
    state.replaceEquippedOutfits(desired);
    try {
      player.save();
    } catch (RuntimeException failed) {
      state.replaceEquippedOutfits(before);
      throw failed;
    }
    return Retcode.SUCC;
  }

  private PlayerOutfits() {}
}
