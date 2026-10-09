package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import java.util.HashSet;

/** Current owned ordinary book identities; copies and reading state are separate. */
public final class BookCollection {
  public static int count(Player player) {
    synchronized (player) {
      var owned = new HashSet<Integer>();
      var inventory = player.getInventory();
      for (var item : inventory.getItems().values()) {
        if (!valid(player.getUid(), item)
            || item.getExcel() != GameData.getItemExcelMap().get(item.getItemId())
            || item.getInternalUid() <= 0
            || inventory.getItemByUid(item.getInternalUid()) != item
            || inventory.getMaterialByItemId(item.getItemId()) != item) continue;
        owned.add(item.getItemId());
      }
      return owned.size();
    }
  }

  /** Read database rows without attaching them to an offline inventory or saving them. */
  public static int stored(int owner, Iterable<GameItem> rows) {
    if (owner <= 0 || rows == null) return 0;
    var owned = new HashSet<Integer>();
    for (var item : rows) {
      if (valid(owner, item) && item.getId() != null) owned.add(item.getItemId());
    }
    return owned.size();
  }

  private static boolean valid(int owner, GameItem item) {
    if (item == null
        || item.getOwnerUid() != owner
        || item.isDiscarded()
        || item.getEquipAvatarId() != null
        || item.getEquipAvatarExcelId() != 0) return false;
    var definition = GameData.getItemExcelMap().get(item.getItemId());
    return BookItems.storable(definition)
        && item.getCount() > 0
        && item.getCount() <= definition.getPileLimit();
  }

  private BookCollection() {}
}
