package org.hkrpg.server.gameplay;

import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.send.PacketPlayerSyncScNotify;
import java.util.*;

/** Resource-whitelisted superimposition; validate the entire selection under the player's lock. */
public final class EquipmentSuperimposition {
  public static boolean apply(Player player, int targetId, List<ItemParam> requested) {
    synchronized (player) {
      var inventory = player.getInventory();
      var target = inventory.getItemByUid(targetId);
      if (target == null || target.getOwnerUid() != player.getUid() || target.getCount() != 1
          || target.getExcel() == null || !target.getExcel().isEquipment()
          || requested == null || requested.isEmpty() || requested.size() > 1000) return false;
      var config = target.getExcel().getEquipmentExcel();
      int maximum = config.getMaxRank(), rank = target.getRank();
      if (rank < 1 || rank >= maximum || maximum > 1000) return false;
      var quantities = new LinkedHashMap<GameItem, Integer>();
      var kinds = new IdentityHashMap<GameItem, ItemParamType>();
      int gain = 0;
      for (var cost : requested) {
        if (cost == null || cost.getCount() <= 0 || cost.getId() <= 0) return false;
        var material = inventory.getItemByParam(cost);
        if (material == null || material == target || material.getOwnerUid() != player.getUid()
            || material.getExcel() == null || material.isLocked() || material.isEquipped()
            || inventory.getItemByUid(material.getInternalUid()) != material) return false;
        long increase;
        if (cost.getType() == ItemParamType.UNIQUE) {
          if (cost.getCount() != 1 || material.getCount() != 1 || quantities.containsKey(material)
              || !material.getExcel().isEquipment() || material.getItemId() != target.getItemId()
              || material.getRank() < 1 || material.getRank() > maximum) return false;
          increase = material.getRank();
        } else if (cost.getType() == ItemParamType.PILE) {
          if (material.getItemMainType() != ItemMainType.Material || config.getRankUpCostList() == null
              || !config.getRankUpCostList().contains(material.getItemId())) return false;
          // A configured stackable superimposer contributes one rank per consumed unit.
          increase = cost.getCount();
        } else return false;
        long quantity = (long) quantities.getOrDefault(material, 0) + cost.getCount();
        if (quantity > material.getCount() || increase > maximum - rank - gain) return false;
        quantities.put(material, (int) quantity);
        kinds.put(material, cost.getType());
        gain += (int) increase;
      }
      if (gain == 0) return false;
      var debit = new ArrayList<ItemParam>();
      quantities.forEach((material, quantity) -> debit.add(new ItemParam(kinds.get(material),
          kinds.get(material) == ItemParamType.UNIQUE ? material.getInternalUid() : material.getItemId(), quantity)));
      inventory.removeItemsByParams(debit);
      target.setRank(rank + gain);
      target.save();
      player.sendPacket(new PacketPlayerSyncScNotify(target));
      return true;
    }
  }

  private EquipmentSuperimposition() {}
}
