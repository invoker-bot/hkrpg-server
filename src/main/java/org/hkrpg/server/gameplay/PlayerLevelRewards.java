package org.hkrpg.server.gameplay;

import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.enums.ItemSubType;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.inventory.tabs.InventoryTabType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Resource-authoritative Trailblaze rewards, with a receipt saved before delivery. */
public final class PlayerLevelRewards {
  private static final String PREFIX = "player-level:";

  public static String key(int level) { return PREFIX + level; }

  public static List<Integer> taken(Player player) {
    synchronized (player) {
      var result = new ArrayList<Integer>();
      for (int level = 2; level <= GameConstants.MAX_TRAILBLAZER_LEVEL; level++) {
        var receipt = player.getGameplay().getRewards().get(key(level));
        if (receipt != null && receipt.isGranted()) result.add(level);
      }
      return List.copyOf(result);
    }
  }

  public static RewardLedger.Claim claim(Player player, int level) {
    synchronized (player) {
      var row = GameData.getPlayerLevelExcelMap().get(level);
      if (level < 2 || level > GameConstants.MAX_TRAILBLAZER_LEVEL || level > player.getLevel()
          || row == null || row.getLevelRewardID() <= 0) return error(Retcode.LEVEL_REWARD_LEVEL_ERROR);
      var receipts = player.getGameplay().getRewards();
      var previous = receipts.get(key(level));
      if (previous != null)
        return error(previous.isGranted() ? Retcode.LEVEL_REWARD_HAS_TAKEN : Retcode.SYSTEM_BUSY);
      var reward = GameData.getRewardExcelMap().get(row.getLevelRewardID());
      if (reward == null || reward.getRewards() == null || reward.getRewards().isEmpty())
        return error(Retcode.REWARD_CONFIG_NOT_EXIST);
      var amounts = new ItemParamMap();
      amounts.add(reward.getRewards());
      var checked = capacity(player, amounts);
      if (checked != Retcode.SUCC) return error(checked);
      var receipt = new GameplayState.Receipt(reward.getId());
      receipts.put(key(level), receipt);
      try { player.save(); }
      catch (RuntimeException failed) {
        // No item has been delivered, so a new attempt is safe.
        receipts.remove(key(level));
        throw failed;
      }
      var delivered = player.getInventory().grantItems(amounts, true);
      if (!delivered.complete()) return error(Retcode.PLAYER_DATA_ERROR);
      receipt.complete();
      try { player.save(); }
      catch (RuntimeException failed) {
        // Delivery may already be durable. Keep it pending and block automatic re-grants.
        receipts.put(key(level), new GameplayState.Receipt(reward.getId()));
        throw failed;
      }
      return new RewardLedger.Claim(Retcode.SUCC, List.copyOf(delivered.items()));
    }
  }

  private static Retcode capacity(Player player, ItemParamMap amounts) {
    var inventory = player.getInventory();
    var allocations = new EnumMap<InventoryTabType,Integer>(InventoryTabType.class);
    long ids = 0;
    for (var amount : amounts) {
      int id = amount.getIntKey(), count = amount.getIntValue();
      var excel = GameData.getItemExcelMap().get(id);
      if (count <= 0 || excel == null) return Retcode.REWARD_CONFIG_NOT_EXIST;
      var type = excel.getItemMainType();
      if (type == ItemMainType.Virtual) {
        if (id != 1 && id != 2) return Retcode.REWARD_CONFIG_NOT_EXIST;
        int before = id == 1 ? player.getHcoin() : player.getScoin();
        if (before < 0 || excel.getPileLimit() <= 0
            || (long) before + count > Math.min(Integer.MAX_VALUE, excel.getPileLimit()))
          return Retcode.ITEM_REWARD_EXCEED_LIMIT;
      } else if (type == ItemMainType.Equipment || type == ItemMainType.Relic) {
        if (type == ItemMainType.Equipment && GameData.getEquipExcelMap().get(id) == null
            || type == ItemMainType.Relic && GameData.getRelicExcelMap().get(id) == null)
          return Retcode.REWARD_CONFIG_NOT_EXIST;
        allocations.merge(type.getTabType(), count, Math::addExact);
        ids += count;
      } else if (type == ItemMainType.Material
          || type == ItemMainType.Usable && excel.getItemSubType() == ItemSubType.Food) {
        var stored = inventory.getMaterialByItemId(id);
        if (stored != null && (stored.getOwnerUid() != player.getUid() || stored.getExcel() != excel
            || stored.getItemId() != id || inventory.getItemByUid(stored.getInternalUid()) != stored
            || stored.getCount() < 0)) return Retcode.PLAYER_DATA_ERROR;
        if (excel.getPileLimit() <= 0
            || (long) (stored == null ? 0 : stored.getCount()) + count > excel.getPileLimit())
          return Retcode.ITEM_REWARD_EXCEED_LIMIT;
        if (stored == null) {
          allocations.merge(InventoryTabType.MATERIAL, 1, Math::addExact);
          ids++;
        }
      } else return Retcode.REWARD_CONFIG_NOT_EXIST;
    }
    for (var allocation : allocations.entrySet()) {
      var tab = inventory.getTab(allocation.getKey());
      if (tab == null || allocation.getValue() > tab.getAvailableCapacity())
        return Retcode.ITEM_REWARD_EXCEED_LIMIT;
    }
    if (ids > Integer.MAX_VALUE || ids > 0 && !inventory.hasItemIdCapacity((int) ids))
      return Retcode.ITEM_REWARD_EXCEED_LIMIT;
    return Retcode.SUCC;
  }

  private static RewardLedger.Claim error(Retcode code) { return new RewardLedger.Claim(code,List.of()); }
  private PlayerLevelRewards() {}
}
