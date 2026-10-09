package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import dev.morphia.annotations.Entity;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.inventory.tabs.InventoryTabType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import lombok.Getter;

/** An authored raid payout, frozen at entry and reserved before any inventory grant. */
@Entity(useDiscriminator = false)
@Getter
public final class MissionRaidPayout {
  private static final Gson GSON = new Gson();
  private String source;
  private List<Integer> rewards = new ArrayList<>();
  private Map<Integer, Integer> items = new TreeMap<>();
  private Map<Integer, String> rewardSources = new TreeMap<>();
  private Map<Integer, String> itemSources = new TreeMap<>();
  private boolean reserved, paid;

  protected MissionRaidPayout() {}

  static boolean required(GameplayDefinitions.Raid row) {
    return row != null && !row.SkipRewardOnFinish();
  }

  static boolean supported(GameplayDefinitions.Raid row) {
    if (row == null || row.RewardList() == null) return false;
    if (!required(row)) return row.RewardList().isEmpty();
    try {
      var values = quantities(row);
      return !values.isEmpty();
    } catch (IllegalArgumentException | ArithmeticException ex) {
      return false;
    }
  }

  private static Map<Integer, Integer> quantities(GameplayDefinitions.Raid row) {
    if (row.RewardList() == null
        || row.RewardList().isEmpty()
        || row.RewardList().size() > 64
        || row.RewardList().stream().anyMatch(id -> id == null || id <= 0)
        || new HashSet<>(row.RewardList()).size() != row.RewardList().size())
      throw new IllegalArgumentException("Invalid raid reward list");
    var values = new TreeMap<Integer, Integer>();
    for (int id : row.RewardList()) {
      var reward = GameData.getRewardExcelMap().get(id);
      if (reward == null
          || reward.getId() != id
          || reward.getRewards() == null
          || reward.getRewards().isEmpty())
        throw new IllegalArgumentException("Missing authored raid reward");
      for (var item : reward.getRewards()) {
        int key = item.getIntKey(), count = item.getIntValue();
        var excel = GameData.getItemExcelMap().get(key);
        if (key <= 0
            || count <= 0
            || excel == null
            || excel.getId() != key
            || excel.getPileLimit() <= 0
            || !(key == 2 && excel.getItemMainType() == ItemMainType.Virtual
                || excel.getItemMainType() == ItemMainType.Material))
          throw new IllegalArgumentException("Missing raid reward item");
        values.merge(key, count, Math::addExact);
      }
    }
    return values;
  }

  MissionRaidPayout(GameplayDefinitions.Raid row) {
    if (!required(row) || !supported(row))
      throw new IllegalArgumentException("Unsupported raid payout");
    source = MissionRaidSequence.signature(row);
    rewards.addAll(row.RewardList());
    items.putAll(quantities(row));
    for (int id : rewards) rewardSources.put(id, GSON.toJson(GameData.getRewardExcelMap().get(id)));
    for (int id : items.keySet())
      itemSources.put(id, GSON.toJson(GameData.getItemExcelMap().get(id)));
  }

  boolean matches(GameplayDefinitions.Raid row) {
    try {
      if (!required(row)
          || !supported(row)
          || source == null
          || rewards == null
          || items == null
          || rewardSources == null
          || itemSources == null
          || paid && !reserved) return false;
      return rewards.equals(row.RewardList())
          && items.equals(quantities(row))
          && source.equals(MissionRaidSequence.signature(row))
          && rewardSources.keySet().equals(new HashSet<>(rewards))
          && itemSources.keySet().equals(items.keySet())
          && rewardSources.entrySet().stream()
              .allMatch(
                  e ->
                      e.getValue()
                          .equals(GSON.toJson(GameData.getRewardExcelMap().get(e.getKey()))))
          && itemSources.entrySet().stream()
              .allMatch(
                  e ->
                      e.getValue().equals(GSON.toJson(GameData.getItemExcelMap().get(e.getKey()))));
    } catch (IllegalArgumentException
        | IllegalStateException
        | ArithmeticException
        | NullPointerException invalid) {
      return false;
    }
  }

  private boolean capacity(Player player) {
    int newStacks = 0;
    for (var value : items.entrySet()) {
      int id = value.getKey();
      var excel = GameData.getItemExcelMap().get(id);
      int current;
      if (id == 2) current = player.getScoin();
      else {
        var stored = player.getInventory().getMaterialByItemId(id);
        if (stored == null) {
          current = 0;
          newStacks++;
        } else {
          if (stored.getItemId() != id
              || stored.getOwnerUid() != player.getUid()
              || stored.getExcel() != excel) return false;
          current = stored.getCount();
        }
      }
      if (current < 0
          || (long) current + value.getValue() > Math.min(Integer.MAX_VALUE, excel.getPileLimit()))
        return false;
    }
    var tab = player.getInventory().getTab(InventoryTabType.MATERIAL);
    return newStacks == 0
        || tab != null
            && newStacks <= tab.getAvailableCapacity()
            && player.getInventory().hasItemIdCapacity(newStacks);
  }

  private static String key(GameplayState.MissionRaidRun run) {
    return "raid:" + run.getRaidId() + ":" + run.getWorldLevel();
  }

  boolean settled(Player player, GameplayState.MissionRaidRun run, GameplayDefinitions.Raid row) {
    var receipt = player.getGameplay().getRewards().get(key(run));
    return matches(row)
        && reserved
        && paid
        && receipt != null
        && receipt.getRewardId() == 0
        && receipt.isGranted();
  }

  boolean settle(Player player, GameplayState.MissionRaidRun run, GameplayDefinitions.Raid row) {
    if (!matches(row)) return false;
    var receipt = player.getGameplay().getRewards().get(key(run));
    if (receipt != null && (!reserved || receipt.getRewardId() != 0 || !receipt.isGranted()))
      return false;
    if (paid) return settled(player, run, row);
    if (receipt == null) {
      if (!capacity(player)) return false;
      reserved = true;
      player.save();
      var quantities = new ItemParamMap();
      items.forEach(quantities::add);
      var result = player.getRewardLedger().claimItems(key(run), quantities);
      if (result.retcode() != Retcode.SUCC || !player.getRewardLedger().granted(key(run)))
        return false;
    }
    paid = true;
    player.save();
    return settled(player, run, row);
  }
}
