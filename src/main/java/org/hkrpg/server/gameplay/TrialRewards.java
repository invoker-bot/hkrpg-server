package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.List;
import java.util.TreeMap;

/** Freeze authored quantities before play; only the validated trial NPC callback confirms them. */
public final class TrialRewards {
  public static GameplayState.TrialCompletion snapshot(int rewardId) {
    var amounts = new TreeMap<Integer, Integer>();
    var reward = GameData.getRewardExcelMap().get(rewardId);
    boolean ready = rewardId > 0 && reward != null && reward.getRewards() != null;
    if (ready) {
      for (var item : reward.getRewards()) {
        if (item.getIntKey() <= 0
            || item.getIntValue() <= 0
            || GameData.getItemExcelMap().get(item.getIntKey()) == null) ready = false;
        else amounts.put(item.getIntKey(), item.getIntValue());
      }
    }
    return new GameplayState.TrialCompletion(rewardId, amounts, ready);
  }

  public static void complete(Player player, int stage, GameplayState.TrialCompletion plan) {
    synchronized (player) {
      if (player.getGameplay().getTrialCompletions().putIfAbsent(stage, plan) == null)
        player.save();
    }
  }

  public static String key(int stage) {
    return "trial:" + stage;
  }

  public static RewardLedger.Claim claim(Player player, int stage) {
    synchronized (player) {
      var plan = player.getGameplay().getTrialCompletions().get(stage);
      if (plan == null) return error(Retcode.QUEST_NOT_FINISH);
      var previous = player.getGameplay().getRewards().get(key(stage));
      if (previous != null)
        return error(
            previous.isGranted() ? Retcode.QUEST_REWARD_ALREADY_TAKEN : Retcode.SYSTEM_BUSY);
      if (!plan.isReady() || plan.getRewardId() <= 0 || plan.getItems().isEmpty())
        return error(Retcode.REWARD_CONFIG_NOT_EXIST);
      var amounts = new ItemParamMap();
      for (var item : plan.getItems().entrySet()) {
        if (item.getKey() <= 0 || item.getValue() <= 0) return error(Retcode.PLAYER_DATA_ERROR);
        amounts.add(item.getKey(), item.getValue());
      }
      if (!capacity(player, amounts)) return error(Retcode.ITEM_REWARD_EXCEED_LIMIT);
      var result = player.getRewardLedger().claimItems(key(stage), amounts);
      if (!result.items().isEmpty()) {
        plan.received(result.items());
        player.save();
      }
      return result;
    }
  }

  private static boolean capacity(Player player, ItemParamMap amounts) {
    int newStacks = 0;
    for (var item : amounts) {
      int id = item.getIntKey();
      var excel = GameData.getItemExcelMap().get(id);
      if (excel == null || excel.getPileLimit() <= 0) return false;
      long before;
      if (excel.getItemMainType() == ItemMainType.Virtual) {
        before =
            switch (id) {
              case 1 -> player.getHcoin();
              case 2 -> player.getScoin();
              case 3 -> player.getMcoin();
              case 11 -> player.getStamina();
              case 32 -> player.getTalentPoints();
              case 33, 34 -> RogueCurrencies.balance(player, id);
              default -> -1;
            };
      } else if (Inventory.isStackType(excel)) {
        var existing = player.getInventory().getMaterialByItemId(id);
        before = existing == null ? 0 : existing.getCount();
        if (existing == null) newStacks++;
      } else return false;
      if (before < 0
          || before + item.getIntValue() > Math.min((long) excel.getPileLimit(), Integer.MAX_VALUE))
        return false;
    }
    var tab = player.getInventory().getTabByItemType(ItemMainType.Material);
    return tab != null && newStacks <= tab.getAvailableCapacity();
  }

  private static RewardLedger.Claim error(Retcode code) {
    return new RewardLedger.Claim(code, List.of());
  }

  private TrialRewards() {}
}
