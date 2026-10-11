package org.hkrpg.server.gameplay;

import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.inventory.tabs.InventoryTabType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;

/**
 * Native world offerings 6 and 10, actual debits, source rewards and repeatable long-tail
 * exchange.
 */
public final class KraterOfferings {
  public record Snapshot(
      Retcode code, boolean open, int level, int exp, int total, Set<Integer> taken) {}

  public record Result(Retcode code, List<GameItem> items) {}

  public static boolean storableChestFinder(emu.lunarcore.data.excel.ItemExcel item) {
    if (item == null
        || item.getId() != 408905
        || item.getItemMainType() != ItemMainType.Usable
        || item.getItemSubType() != ItemSubType.FindChest
        || item.getPileLimit() != 1) return false;
    var rules = KraterOfferingRules.load();
    return rules != null && rules.chestFinder(item);
  }

  private static Result rejected(Retcode code) {
    return new Result(code, List.of());
  }

  private static KraterOfferingState state(Player p, int id) {
    return id == 6 ? p.getGameplay().getKraterOffering() : p.getGameplay().getWorldShopOffering();
  }

  private static void replace(Player p, int id, KraterOfferingState value) {
    if (id == 6) p.getGameplay().kraterOffering(value);
    else p.getGameplay().worldShopOffering(value);
  }

  public static Snapshot snapshot(Player p) {
    return snapshot(p, 6);
  }

  public static Snapshot snapshot(Player p, int id) {
    if (id != 6 && id != 10)
      return new Snapshot(Retcode.REQ_PARA_INVALID, false, 0, 0, 0, Set.of());
    synchronized (p) {
      var rules = KraterOfferingRules.load(id);
      var state = state(p, id);
      var code =
          rules == null
              ? Retcode.REWARD_CONFIG_NOT_EXIST
              : state.valid(rules, p) ? Retcode.SUCC : Retcode.PLAYER_DATA_ERROR;
      return code == Retcode.SUCC
          ? new Snapshot(
              code,
              rules.unlocked(p),
              state.level(rules),
              state.exp(rules),
              state.paid(),
              state.taken())
          : new Snapshot(code, false, 0, 0, 0, Set.of());
    }
  }

  private static Retcode preflight(Player p, int id, KraterOfferingRules rules, int entity) {
    if (rules == null) return Retcode.REWARD_CONFIG_NOT_EXIST;
    if (!state(p, id).valid(rules, p)) return Retcode.PLAYER_DATA_ERROR;
    if (!rules.unlocked(p)) return Retcode.SHOP_NOT_OPEN;
    if (state(p, id).pending()) return Retcode.SYSTEM_BUSY;
    return rules.actor(p, entity) == null ? Retcode.REQ_PARA_INVALID : Retcode.SUCC;
  }

  public static Result submit(Player p, int entity) {
    return submit(p, 6, entity);
  }

  public static Result submit(Player p, int id, int entity) {
    if (id != 6 && id != 10) return rejected(Retcode.REQ_PARA_INVALID);
    synchronized (p) {
      var rules = KraterOfferingRules.load(id);
      var code = preflight(p, id, rules, entity);
      if (code != Retcode.SUCC) return rejected(code);
      var old = state(p, id);
      var item = p.getInventory().getMaterialByItemId(rules.item());
      if (item == null || item.getCount() < 1) return rejected(Retcode.ITEM_NOT_ENOUGH);
      int amount = Math.min(item.getCount(), Integer.MAX_VALUE - old.paid());
      if (amount < 1 || old.getPayments().size() >= 4096)
        return rejected(Retcode.ITEM_REWARD_EXCEED_LIMIT);
      var receipt =
          new KraterOfferingState.Payment(
              p, rules.actor(p, entity), old.paid(), amount, item.getCount());
      var prepared = old.prepared(rules, receipt);
      replace(p, id, prepared);
      if (!prepared.valid(rules, p)) {
        replace(p, id, old);
        return rejected(Retcode.PLAYER_DATA_ERROR);
      }
      try {
        p.save();
      } catch (RuntimeException failed) {
        replace(p, id, old);
        return rejected(Retcode.SYSTEM_BUSY);
      }
      try {
        boolean consumed =
            p.getInventory()
                .consumeForUse(
                    item,
                    amount,
                    () -> {
                      if (item.getCount() != receipt.getStockAfter())
                        throw new IllegalStateException(
                            "Material debit differs from reserved stock");
                      var complete = prepared.paid(receipt.getEvent());
                      complete.refresh(rules, p, Instant.now());
                      replace(p, id, complete);
                      if (!complete.valid(rules, p))
                        throw new IllegalStateException("Invalid paid offering receipt");
                      p.save();
                    });
        if (!consumed) return rejected(Retcode.PLAYER_DATA_ERROR);
      } catch (RuntimeException interrupted) {
        replace(p, id, prepared);
        return rejected(Retcode.SYSTEM_BUSY);
      }
      if (id == 6) p.getQuestManager().onKraterOfferingProgress();
      return rejected(Retcode.SUCC);
    }
  }

  public static Result claim(Player p, int entity, List<Integer> levels) {
    return claim(p, 6, entity, levels);
  }

  public static Result claim(Player p, int id, int entity, List<Integer> levels) {
    if (id != 6 && id != 10) return rejected(Retcode.REQ_PARA_INVALID);
    synchronized (p) {
      var rules = KraterOfferingRules.load(id);
      var code = preflight(p, id, rules, entity);
      if (code != Retcode.SUCC) return rejected(code);
      var old = state(p, id);
      int level = old.level(rules);
      if (levels == null
          || levels.isEmpty()
          || levels.size() > 51
          || new HashSet<>(levels).size() != levels.size()
          || levels.stream().anyMatch(l -> l == null || l < 1 || l > 51 || l <= 50 && l > level))
        return rejected(Retcode.REQ_PARA_INVALID);
      if (levels.stream().anyMatch(old.taken()::contains))
        return rejected(Retcode.QUEST_REWARD_ALREADY_TAKEN);
      int tail = levels.contains(51) ? old.exp(rules) / rules.cost(51) : 0;
      if (levels.contains(51) && (level != 50 || tail < 1))
        return rejected(Retcode.REQ_PARA_INVALID);
      var recipe = rules.recipe(levels, tail, p.getLevel() >= GameConstants.MAX_TRAILBLAZER_LEVEL);
      if (recipe == null) return rejected(Retcode.REWARD_CONFIG_NOT_EXIST);
      if (!capacity(p, recipe) || old.getClaims().size() >= 4096)
        return rejected(Retcode.ITEM_REWARD_EXCEED_LIMIT);
      var receipt =
          new KraterOfferingState.Claim(
              p,
              rules.actor(p, entity),
              old.lastPaid(),
              old.paid(),
              new TreeSet<>(levels).stream().toList(),
              tail,
              recipe);
      var prepared = old.prepared(rules, receipt);
      replace(p, id, prepared);
      if (!prepared.valid(rules, p)) {
        replace(p, id, old);
        return rejected(Retcode.PLAYER_DATA_ERROR);
      }
      try {
        p.save();
      } catch (RuntimeException failed) {
        replace(p, id, old);
        return rejected(Retcode.SYSTEM_BUSY);
      }
      var items = new ItemParamMap();
      recipe.forEach(items::add);
      Inventory.GrantResult granted;
      try {
        granted = p.getInventory().grantItems(items, true);
      } catch (RuntimeException interrupted) {
        return rejected(Retcode.SYSTEM_BUSY);
      }
      if (granted == null || !granted.complete()) return rejected(Retcode.PLAYER_DATA_ERROR);
      var complete = prepared.claimed(receipt.getEvent());
      replace(p, id, complete);
      if (!complete.valid(rules, p)) {
        replace(p, id, prepared);
        return rejected(Retcode.PLAYER_DATA_ERROR);
      }
      try {
        p.save();
      } catch (RuntimeException interrupted) {
        replace(p, id, prepared);
        return rejected(Retcode.SYSTEM_BUSY);
      }
      return new Result(Retcode.SUCC, List.copyOf(granted.items()));
    }
  }

  private static boolean capacity(Player p, Map<Integer, Integer> recipe) {
    var additions = new EnumMap<InventoryTabType, Integer>(InventoryTabType.class);
    for (var entry : recipe.entrySet()) {
      int id = entry.getKey(), count = entry.getValue();
      var excel = GameData.getItemExcelMap().get(id);
      if (excel == null || count < 1 || excel.getPileLimit() < 1) return false;
      long before;
      if (excel.getItemMainType() == ItemMainType.Virtual) {
        before =
            switch (id) {
              case 1 -> p.getHcoin();
              case 2 -> p.getScoin();
              case 3 -> p.getMcoin();
              case 22 -> p.getExp();
              default -> -1;
            };
      } else if (excel.getItemMainType() == ItemMainType.Equipment) {
        additions.merge(InventoryTabType.EQUIPMENT, count, Math::addExact);
        continue;
      } else if (excel.getItemMainType() == ItemMainType.Usable
          && Set.of(ItemSubType.HeadIcon, ItemSubType.PhoneTheme)
              .contains(excel.getItemSubType())) {
        if (count != 1) return false;
        continue;
      } else if (Inventory.isStackType(excel)) {
        var item = p.getInventory().getMaterialByItemId(id);
        before = item == null ? 0 : item.getCount();
        if (item == null) additions.merge(InventoryTabType.MATERIAL, 1, Math::addExact);
      } else return false;
      if (before < 0 || before + count > Math.min((long) excel.getPileLimit(), Integer.MAX_VALUE))
        return false;
    }
    for (var entry : additions.entrySet()) {
      var tab = p.getInventory().getTab(entry.getKey());
      if (tab == null || (long) tab.getSize() + entry.getValue() > tab.getMaxCapacity())
        return false;
    }
    return true;
  }

  private KraterOfferings() {}
}
