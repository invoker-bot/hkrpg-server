package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.inventory.tabs.InventoryTabType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.*;
import java.util.*;

/** Source-gated modern assignments, whole-hour grants and actual set/take daily events. */
public final class ExpeditionManager {
  public record Snapshot(
      Retcode retcode, List<Integer> unlocked, List<Integer> active, int teams, long rewardTime) {}

  public record Result(
      Retcode retcode,
      List<Integer> active,
      List<Integer> taken,
      long rewardTime,
      List<ItemParamMap> rewards) {}

  private final Player player;
  private Clock clock = Clock.systemUTC();

  public ExpeditionManager(Player player) {
    this.player = Objects.requireNonNull(player);
  }

  public void setClock(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  public Snapshot snapshot() {
    synchronized (player) {
      var state = player.getGameplay().getExpeditions();
      int teams = ExpeditionCatalog.teamCount(player);
      var unlocked =
          ExpeditionCatalog.harvests.values().stream()
              .filter(
                  r -> ExpeditionCatalog.unlocked(player, r) && ExpeditionCatalog.rates(r) != null)
              .map(ExpeditionCatalog.Harvest::ExpeditionID)
              .sorted()
              .toList();
      return new Snapshot(
          teams == 0 || ExpeditionCatalog.maxHours() == 0
              ? Retcode.EXPEDITION_CONFIG_NOT_EXIST
              : Retcode.SUCC,
          unlocked,
          state.ids(),
          teams,
          state.getRewardTime());
    }
  }

  public Result set(List<Integer> ids) {
    synchronized (player) {
      var state = player.getGameplay().getExpeditions();
      if (state.pending()) return fail(Retcode.SYSTEM_BUSY);
      if (ids == null || ids.size() > 4 || new HashSet<>(ids).size() != ids.size())
        return fail(Retcode.REQ_PARA_INVALID);
      int limit = ExpeditionCatalog.teamCount(player);
      if (limit == 0 || ExpeditionCatalog.maxHours() == 0)
        return fail(Retcode.EXPEDITION_CONFIG_NOT_EXIST);
      if (ids.size() > limit) return fail(Retcode.EXPEDITION_TEAM_COUNT_LIMIT);
      var next = new ArrayList<ExpeditionState.Assignment>();
      for (var id : ids) {
        if (id == null || id <= 0) return fail(Retcode.REQ_PARA_INVALID);
        var row = ExpeditionCatalog.harvests.get(id);
        if (row == null || row.ExpeditionID() != id)
          return fail(Retcode.EXPEDITION_CONFIG_NOT_EXIST);
        if (!ExpeditionCatalog.unlocked(player, row)) return fail(Retcode.EXPEDITION_NOT_UNLOCKED);
        var rates = ExpeditionCatalog.rates(row);
        if (rates == null) return fail(Retcode.EXPEDITION_REWARD_CONFIG_NOT_EXIST);
        next.add(new ExpeditionState.Assignment(id, rates));
      }
      next.sort(Comparator.comparingInt(ExpeditionState.Assignment::getId));
      if (state.ids().equals(next.stream().map(ExpeditionState.Assignment::getId).toList()))
        return fail(Retcode.SUCC);
      return settle(next, true);
    }
  }

  public Result take() {
    synchronized (player) {
      var state = player.getGameplay().getExpeditions();
      if (state.pending()) return fail(Retcode.SYSTEM_BUSY);
      if (state.getAssignments().isEmpty()) return fail(Retcode.EXPEDITION_NOT_ACCEPTED);
      return settle(state.getAssignments(), false);
    }
  }

  private Result settle(List<ExpeditionState.Assignment> next, boolean setting) {
    var state = player.getGameplay().getExpeditions();
    long now = clock.instant().getEpochSecond();
    long hour = Math.floorDiv(now, 3600) * 3600;
    if (hour <= 0
        || hour < state.getRewardTime()
        || state.getRewardTime() < 0
        || state.getRewardTime() % 3600 != 0) return fail(Retcode.PLAYER_DATA_ERROR);
    int cap = ExpeditionCatalog.maxHours();
    if (cap <= 0) return fail(Retcode.EXPEDITION_CONFIG_NOT_EXIST);
    int hours =
        state.getAssignments().isEmpty()
            ? 0
            : (int) Math.min((hour - state.getRewardTime()) / 3600, cap);
    if (!setting && hours == 0) return fail(Retcode.EXPEDITION_NOT_FINISH);
    var rewards = new ItemParamMap();
    var groups = new ArrayList<ItemParamMap>();
    try {
      var seen = new HashSet<Integer>();
      for (var assignment : state.getAssignments()) {
        if (assignment == null
            || assignment.getId() <= 0
            || !seen.add(assignment.getId())
            || assignment.getRates() == null
            || assignment.getRates().isEmpty()
            || state.getRewardTime() == 0) return fail(Retcode.PLAYER_DATA_ERROR);
        var group = new ItemParamMap();
        for (var rate : assignment.getRates().entrySet()) {
          if (rate.getKey() == null
              || rate.getValue() == null
              || rate.getValue() <= 0
              || !ExpeditionCatalog.validItem(rate.getKey()))
            return fail(Retcode.EXPEDITION_REWARD_CONFIG_NOT_EXIST);
          if (hours > 0) {
            int amount = Math.multiplyExact(rate.getValue(), hours);
            int itemId = rate.getKey();
            rewards.put(itemId, Math.addExact(rewards.get(itemId), amount));
            group.put(itemId, amount);
          }
        }
        if (hours > 0) groups.add(group);
      }
    } catch (RuntimeException invalid) {
      return fail(Retcode.PLAYER_DATA_ERROR);
    }
    var taken = hours > 0 ? List.copyOf(state.ids()) : List.<Integer>of();
    synchronized (player.getInventory()) {
      if (!rewards.isEmpty()) {
        if (!capacity(rewards)) return fail(Retcode.PLAYER_DATA_ERROR);
        var expected = new TreeMap<Integer, Integer>();
        for (var entry : rewards) expected.put(entry.getIntKey(), entry.getIntValue());
        ExpeditionState.Attempt attempt;
        try {
          attempt = state.reserve(hour, hours, next, expected);
        } catch (RuntimeException invalid) {
          return fail(Retcode.PLAYER_DATA_ERROR);
        }
        player.save();
        var granted = player.getRewardLedger().claimItems(attempt.key(), rewards);
        if (granted.retcode() != Retcode.SUCC) return fail(granted.retcode());
      }
      state.commit(next, hour);
      player.save();
    }
    if (hours > 0 || setting && !next.isEmpty())
      player.getDailyManager().onExpeditionSetOrTakeReward();
    return new Result(Retcode.SUCC, state.ids(), taken, state.getRewardTime(), List.copyOf(groups));
  }

  private boolean capacity(ItemParamMap rewards) {
    var inventory = player.getInventory();
    int newPiles = 0;
    for (var reward : rewards) {
      int id = reward.getIntKey(), amount = reward.getIntValue();
      if (amount <= 0 || !ExpeditionCatalog.validItem(id)) return false;
      var excel = GameData.getItemExcelMap().get(id);
      if (id == 2) {
        if (player.getScoin() < 0 || (long) player.getScoin() + amount > excel.getPileLimit())
          return false;
        continue;
      }
      var pile = inventory.getMaterialByItemId(id);
      if (pile != null
          && (pile.getOwnerUid() != player.getUid()
              || pile.getInternalUid() <= 0
              || inventory.getItemByUid(pile.getInternalUid()) != pile
              || pile.getExcel() != excel
              || pile.getCount() < 0)) return false;
      int count = pile == null ? 0 : pile.getCount();
      if ((long) count + amount > excel.getPileLimit()) return false;
      if (pile == null) newPiles++;
    }
    var tab = inventory.getTab(InventoryTabType.MATERIAL);
    return tab != null
        && tab.getAvailableCapacity() >= newPiles
        && (newPiles == 0 || inventory.hasItemIdCapacity(newPiles));
  }

  private Result fail(Retcode code) {
    var state = player.getGameplay().getExpeditions();
    return new Result(code, state.ids(), List.of(), state.getRewardTime(), List.of());
  }
}
