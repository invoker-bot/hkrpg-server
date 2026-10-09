package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Original classic handbook reward 106011 is thirty item-1 jades; other recipes stay closed. */
public final class RogueHandbookRewards {
  public record Result(Retcode code, List<Integer> identities, List<GameItem> items) {}

  public static String key(int id) {
    return "rogue-handbook-miracle:" + id;
  }

  public static boolean validReceipts(Player player) {
    var receipts = player.getGameplay().getRewards();
    return receipts != null
        && receipts.entrySet().stream()
            .noneMatch(
                entry ->
                    entry.getKey() != null
                        && entry.getKey().startsWith("rogue-handbook-miracle:")
                        && entry.getValue() == null);
  }

  public static boolean taken(Player player, int id) {
    var receipts = player.getGameplay().getRewards();
    if (receipts == null) return false;
    var receipt = receipts.get(key(id));
    return receipt != null && receipt.isGranted();
  }

  public static Result claim(Player player, List<Integer> requested) {
    synchronized (player) {
      if (requested == null
          || requested.isEmpty()
          || requested.size() > 100
          || new HashSet<>(requested).size() != requested.size()
          || requested.stream().anyMatch(id -> id == null || id <= 0))
        return error(Retcode.REQ_PARA_INVALID);
      var history = player.getGameplay().getRogueHandbook();
      var receipts = player.getGameplay().getRewards();
      if (history == null || !history.valid() || !validReceipts(player))
        return error(Retcode.PLAYER_DATA_ERROR);
      var pending = new ArrayList<Integer>();
      for (int id : requested) {
        var source = RogueHandbookCatalog.miracle(id);
        if (source == null || source.MiracleHandbookID() != id)
          return error(Retcode.REQ_PARA_INVALID);
        if (!history.getMiracles().contains(id)) return error(Retcode.QUEST_NOT_FINISH);
        var receipt = receipts.get(key(id));
        if (receipt != null) {
          if (!receipt.isGranted()) return error(Retcode.SYSTEM_BUSY);
          continue;
        }
        var reward = GameData.getRewardExcelMap().get(source.MiracleReward());
        if (source.MiracleReward() != 106011
            || reward == null
            || reward.getRewardID() != 106011
            || reward.getRewards() == null
            || reward.getRewards().size() != 1
            || reward.getRewards().get(1) != 30) return error(Retcode.REWARD_CONFIG_NOT_EXIST);
        pending.add(id);
      }
      if (pending.isEmpty()) return error(Retcode.QUEST_REWARD_ALREADY_TAKEN);
      var item = GameData.getItemExcelMap().get(1);
      if (item == null
          || item.getId() != 1
          || item.getItemMainType() != ItemMainType.Virtual
          || item.getPileLimit() <= 0) return error(Retcode.REWARD_CONFIG_NOT_EXIST);
      long total = 30L * pending.size();
      if (player.getHcoin() < 0
          || (long) player.getHcoin() + total
              > Math.min((long) item.getPileLimit(), Integer.MAX_VALUE))
        return error(Retcode.ITEM_REWARD_EXCEED_LIMIT);
      // Validate the whole batch before the first receipt or payment, then freeze authored
      // quantities.
      var granted = new ArrayList<Integer>();
      var items = new ArrayList<GameItem>();
      for (int id : pending) {
        var amounts = new ItemParamMap();
        amounts.add(1, 30);
        RewardLedger.Claim actual;
        try {
          actual = player.getRewardLedger().claimItems(key(id), amounts);
        } catch (RuntimeException interrupted) {
          return new Result(Retcode.SYSTEM_BUSY, List.copyOf(granted), List.copyOf(items));
        }
        items.addAll(actual.items());
        if (actual.retcode() != Retcode.SUCC)
          return new Result(actual.retcode(), List.copyOf(granted), List.copyOf(items));
        granted.add(id);
      }
      return new Result(Retcode.SUCC, List.copyOf(granted), List.copyOf(items));
    }
  }

  private static Result error(Retcode code) {
    return new Result(code, List.of(), List.of());
  }

  private RogueHandbookRewards() {}
}
