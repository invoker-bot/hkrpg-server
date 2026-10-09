package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/**
 * Persist-before-grant prevents automatic re-grants after interruption; reconciliation is explicit.
 */
public final class RewardLedger {
  public record Claim(Retcode retcode, List<GameItem> items) {}

  private final Player player;

  public RewardLedger(Player player) {
    this.player = player;
  }

  public Claim claim(String key, int rewardId) {
    synchronized (player) {
      var receipts = player.getGameplay().getRewards();
      var previous = receipts.get(key);
      if (previous != null)
        return new Claim(
            previous.isGranted() ? Retcode.QUEST_REWARD_ALREADY_TAKEN : Retcode.SYSTEM_BUSY,
            List.of());
      var config = GameData.getRewardExcelMap().get(rewardId);
      if (rewardId != 0 && (config == null || config.getRewards() == null))
        return new Claim(Retcode.REWARD_CONFIG_NOT_EXIST, List.of());
      ItemParamMap rewards = new ItemParamMap();
      if (config != null) rewards.add(config.getRewards());
      return grant(key, rewardId, rewards);
    }
  }

  public Claim claimItems(String key, ItemParamMap rewards) {
    synchronized (player) {
      var copy = new ItemParamMap();
      copy.add(rewards);
      return grant(key, 0, copy);
    }
  }

  private Claim grant(String key, int rewardId, ItemParamMap rewards) {
    var receipts = player.getGameplay().getRewards();
    var previous = receipts.get(key);
    if (previous != null)
      return new Claim(
          previous.isGranted() ? Retcode.QUEST_REWARD_ALREADY_TAKEN : Retcode.SYSTEM_BUSY,
          List.of());
    for (var item : rewards)
      if (item.getIntValue() <= 0 || GameData.getItemExcelMap().get(item.getIntKey()) == null)
        return new Claim(Retcode.PLAYER_DATA_ERROR, List.of());
    var receipt = new GameplayState.Receipt(rewardId);
    receipts.put(key, receipt);
    player.save();
    var grant = player.getInventory().grantItems(rewards, true);
    if (!grant.complete()) return new Claim(Retcode.PLAYER_DATA_ERROR, grant.items());
    receipt.complete();
    player.save();
    return new Claim(Retcode.SUCC, List.copyOf(grant.items()));
  }

  public boolean granted(String key) {
    var r = player.getGameplay().getRewards().get(key);
    return r != null && r.isGranted();
  }
}
