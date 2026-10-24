package org.hkrpg.server.gameplay;

import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketPlayerSyncScNotify;
import java.util.*;

/** Validate the whole typed batch before changing any protection flag. */
public final class EquipmentProtection {
  public static Retcode lock(Player player, Collection<Integer> ids, boolean locked, ItemMainType kind) {
    synchronized (player) {
      if (ids == null || ids.isEmpty() || ids.size() > 1000
          || (kind != ItemMainType.Equipment && kind != ItemMainType.Relic)) return Retcode.REQ_PARA_INVALID;
      var items = new ArrayList<GameItem>();
      for (var id : new LinkedHashSet<>(ids)) {
        if (id == null || id <= 0) return Retcode.REQ_PARA_INVALID;
        var item = player.getInventory().getItemByUid(id);
        if (item == null || item.getOwnerUid() != player.getUid() || item.getCount() != 1
            || item.getItemMainType() != kind || (locked && item.isDiscarded())) return Retcode.REQ_PARA_INVALID;
        items.add(item);
      }
      for (var item : items) { item.setLocked(locked); item.save(); }
      player.sendPacket(new PacketPlayerSyncScNotify(items));
      return Retcode.SUCC;
    }
  }

  private EquipmentProtection() {}
}
