package org.hkrpg.server.gameplay;

import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.enums.ItemSubType;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;

/** Real inventory debits and reserved reward batches, with no inferred historical progress. */
public final class Offerings {
  public record Result(Retcode code, List<GameItem> items) {}

  private static Result rejected(Retcode code) {
    return new Result(code, List.of());
  }

  public static boolean valid(GameplayState.Offering state) {
    if (state == null) return true;
    if ((state.getId() != 5 && state.getId() != 12)
        || state.getItem() != (state.getId() == 5 ? 270003 : 264999)
        || state.getCosts() == null
        || state.getCosts().isEmpty()
        || state.getCosts().size() > 100
        || state.getRewardIds() == null
        || state.getCosts().size() != state.getRewardIds().size()
        || state.getCosts().stream().anyMatch(c -> c == null || c <= 0)
        || state.getRewardIds().stream().anyMatch(r -> r == null || r <= 0)
        || state.getSubmissions() == null
        || state.getClaims() == null) return false;
    if (state.getId() == 5) {
      if (state.getCosts().size() != 16) return false;
      for (int i = 0; i < 16; i++)
        if (state.getCosts().get(i) != 3 || state.getRewardIds().get(i) != 260001 + i) return false;
    }
    long cap = state.getCosts().stream().mapToLong(Integer::longValue).sum();
    if (cap > Integer.MAX_VALUE || state.getTotalExp() < 0 || state.getTotalExp() > cap)
      return false;
    long paid = 0;
    boolean pending = false;
    for (var debit : state.getSubmissions()) {
      if (debit == null
          || pending
          || debit.getBefore() != paid
          || debit.getAmount() <= 0
          || paid + debit.getAmount() > cap) return false;
      if (debit.isPaid()) paid += debit.getAmount();
      else pending = true;
    }
    if (paid != state.getTotalExp()) return false;
    int level = 0, exp = state.getTotalExp();
    while (level < state.getCosts().size() && exp >= state.getCosts().get(level))
      exp -= state.getCosts().get(level++);
    if (state.getLevel() != level || state.getLevelExp() != exp) return false;
    var claimed = new HashSet<Integer>();
    for (var claim : state.getClaims()) {
      if (claim == null
          || claim.getLevels() == null
          || claim.getLevels().isEmpty()
          || claim.getItems() == null
          || claim.getItems().isEmpty()
          || claim.getLevels().stream()
              .anyMatch(l -> l == null || l <= 0 || l > state.getLevel() || !claimed.add(l))
          || claim.getItems().entrySet().stream()
              .anyMatch(
                  e ->
                      e.getKey() == null
                          || e.getKey() <= 0
                          || e.getValue() == null
                          || e.getValue() <= 0)
          || claim.getAvatars() == null
          || claim.getDice() == null) return false;
      if (state.getId() == 5 && (!claim.getAvatars().isEmpty() || !claim.getDice().isEmpty())) return false;
      for (var entry : claim.getAvatars().entrySet()) {
        var avatar = entry.getValue();
        if (entry.getKey() == null
            || avatar == null
            || avatar.getId() != entry.getKey()
            || !claim.getItems().containsKey(entry.getKey())
            || avatar.getTime() <= 0
            || avatar.getLevel() != 1
            || avatar.getDice() == null
            || avatar.getDice().isEmpty()
            || avatar.getDice().size() > 32
            || avatar.getDice().stream().anyMatch(d -> d == null || d <= 0)
            || claim.getDice().containsKey(entry.getKey())) return false;
      }
      for (var entry : claim.getDice().entrySet())
        if (entry.getKey() == null
            || entry.getKey() <= 0
            || entry.getValue() == null
            || entry.getValue() <= 0
            || !claim.getItems().containsKey(entry.getKey())) return false;
    }
    return true;
  }

  public static Set<Integer> taken(GameplayState.Offering state) {
    var result = new TreeSet<Integer>();
    if (state != null)
      state.getClaims().stream()
          .filter(GameplayState.OfferingClaim::isGranted)
          .forEach(c -> result.addAll(c.getLevels()));
    return Collections.unmodifiableSet(result);
  }

  public static boolean valid(Player player) {
    return valid(player, 12);
  }

  public static boolean valid(Player player, int id) {
    if (id != 5 && id != 12) return false;
    var state = player.getGameplay().getOfferings().get(id);
    if (!valid(state) || state != null && state.getId() != id) return false;
    if (state == null) return true;
    if (id == 5) return true;
    var owned = player.getGameplay().getDiceCombat();
    for (var claim : state.getClaims()) {
      if (!claim.isGranted()) continue;
      for (var entry : claim.getAvatars().entrySet()) {
        var avatar = owned.getAvatars().get(entry.getKey());
        if (avatar == null
            || avatar.getId() != entry.getKey()
            || avatar.getTime() != entry.getValue().getTime()) return false;
      }
      for (var entry : claim.getDice().entrySet())
        if (!Objects.equals(owned.getDice().get(entry.getKey()), entry.getValue())) return false;
    }
    return true;
  }

  public static boolean unlocked(Player player, int offering, int level) {
    if (offering != 12 || level < 1) return false;
    var state = player.getGameplay().getOfferings().get(offering);
    return state != null && valid(player) && !pending(state) && state.getLevel() >= level;
  }

  private static boolean pending(GameplayState.Offering state) {
    return state != null
        && (state.getSubmissions().stream().anyMatch(s -> !s.isPaid())
            || state.getClaims().stream().anyMatch(c -> !c.isGranted()));
  }

  private static Retcode preflight(Player player, OfferingCatalog catalog, int id, int entity) {
    if ((id != 5 && id != 12) || entity != 0) return Retcode.REQ_PARA_INVALID;
    if (catalog == null || catalog.id() != id) return Retcode.REWARD_CONFIG_NOT_EXIST;
    if (id == 5) {
      var source = OfferingQueryCatalog.read();
      if (source == null) return Retcode.REWARD_CONFIG_NOT_EXIST;
      if (!source.unlocked(player, 5, Instant.now())) return Retcode.SHOP_NOT_OPEN;
      if (player.getGameplay().getRhythm().getActiveLevel() != 0) return Retcode.SYSTEM_BUSY;
    } else {
      var dice = DiceCombatCatalog.read();
      if (dice == null) return Retcode.REWARD_CONFIG_NOT_EXIST;
      if (!dice.available(player, Instant.now())) return Retcode.SHOP_NOT_OPEN;
    }
    if (player.getBattle() != null) return Retcode.SYSTEM_BUSY;
    var state = player.getGameplay().getOfferings().get(id);
    if (!valid(player, id)) return Retcode.PLAYER_DATA_ERROR;
    if (pending(state) || id == 12 && (DiceProgression.pending(player) || DicePveMissionReports.pending(player)))
      return Retcode.SYSTEM_BUSY;
    return Retcode.SUCC;
  }

  public static Result submit(Player player, OfferingCatalog catalog, int id, int entity) {
    synchronized (player) {
      var code = preflight(player, catalog, id, entity);
      if (code != Retcode.SUCC) return rejected(code);
      var saved = player.getGameplay().getOfferings().get(id);
      var gson = emu.lunarcore.data.ResourceLoader.getGson();
      var state = saved == null ? new GameplayState.Offering(catalog)
          : id == 5 ? gson.fromJson(gson.toJson(saved), GameplayState.Offering.class) : saved;
      int cap = Math.toIntExact(state.getCosts().stream().mapToLong(Integer::longValue).sum());
      if (state.getTotalExp() == cap) return rejected(Retcode.REQ_PARA_INVALID);
      var excel = GameData.getItemExcelMap().get(state.getItem());
      if (excel == null
          || excel.getItemMainType() != ItemMainType.Material
          || excel.getPileLimit() < 1) return rejected(Retcode.REWARD_CONFIG_NOT_EXIST);
      var item = player.getInventory().getMaterialByItemId(state.getItem());
      if (item == null || item.getCount() <= 0) return rejected(Retcode.ITEM_NOT_ENOUGH);
      int amount = Math.min(item.getCount(), cap - state.getTotalExp());
      var receipt = new GameplayState.OfferingSubmission(state.getTotalExp(), amount);
      state.getSubmissions().add(receipt);
      player.getGameplay().getOfferings().put(id, state);
      try {
        player.save();
      } catch (RuntimeException failed) {
        if (id != 5) throw failed;
        if (saved == null) player.getGameplay().getOfferings().remove(id);
        else player.getGameplay().getOfferings().put(id, saved);
        return rejected(Retcode.SYSTEM_BUSY);
      }
      var prepared = id == 5 ? gson.fromJson(gson.toJson(state), GameplayState.Offering.class) : null;
      boolean consumed;
      try {
        consumed =
          player
              .getInventory()
              .consumeForUse(
                  item,
                  amount,
                  () -> {
                    state.apply(receipt);
                    player.save();
                  });
      } catch (RuntimeException interrupted) {
        if (id != 5) throw interrupted;
        player.getGameplay().getOfferings().put(id, prepared);
        return rejected(Retcode.SYSTEM_BUSY);
      }
      return rejected(consumed ? Retcode.SUCC : Retcode.PLAYER_DATA_ERROR);
    }
  }

  public static Result claim(
      Player player, OfferingCatalog catalog, int id, int entity, List<Integer> levels) {
    synchronized (player) {
      var code = preflight(player, catalog, id, entity);
      if (code != Retcode.SUCC) return rejected(code);
      var state = player.getGameplay().getOfferings().get(id);
      if (state == null
          || levels.isEmpty()
          || levels.size() > 100
          || new HashSet<>(levels).size() != levels.size()
          || levels.stream().anyMatch(l -> l == null || l <= 0 || l > state.getLevel()))
        return rejected(Retcode.REQ_PARA_INVALID);
      if (levels.stream().anyMatch(taken(state)::contains))
        return rejected(Retcode.QUEST_REWARD_ALREADY_TAKEN);
      var rewards = new ItemParamMap();
      for (int level : levels) {
        var row = GameData.getRewardExcelMap().get(state.getRewardIds().get(level - 1));
        if (row == null || row.getRewards() == null || row.getRewards().isEmpty())
          return rejected(Retcode.REWARD_CONFIG_NOT_EXIST);
        for (var reward : row.getRewards()) {
          int item = reward.getIntKey(), count = reward.getIntValue();
          if (count <= 0
              || GameData.getItemExcelMap().get(item) == null
              || (long) rewards.get(item) + count > Integer.MAX_VALUE)
            return rejected(Retcode.REWARD_CONFIG_NOT_EXIST);
          rewards.add(item, count);
        }
      }
      if (player.getLevel() >= GameConstants.MAX_TRAILBLAZER_LEVEL && rewards.get(22) > 0) {
        long credits = (long) rewards.get(22) * 10 + rewards.get(2);
        if (credits > Integer.MAX_VALUE) return rejected(Retcode.ITEM_REWARD_EXCEED_LIMIT);
        rewards.remove(22);
        rewards.put(2, (int) credits);
      }
      if (!capacity(player, rewards)) return rejected(Retcode.ITEM_REWARD_EXCEED_LIMIT);
      var diceCatalog = id == 12 ? DiceCombatCatalog.read() : null;
      if (id == 12 && !DiceCombatShop.valid(player, diceCatalog)) return rejected(Retcode.PLAYER_DATA_ERROR);
      var owned = player.getGameplay().getDiceCombat();
      var avatars = new TreeMap<Integer, GameplayState.DiceAvatar>();
      var dice = new TreeMap<Integer, Long>();
      long time = Instant.now().getEpochSecond();
      for (var reward : rewards) {
        int item = reward.getIntKey();
        var avatar = diceCatalog == null ? null : diceCatalog.avatars().get(item);
        if (avatar != null && !owned.getAvatars().containsKey(item))
          avatars.put(item, new GameplayState.DiceAvatar(item, time, avatar.initialDice()));
        else if (diceCatalog != null && diceCatalog.dice().contains(item) && !owned.getDice().containsKey(item))
          dice.put(item, time);
      }
      var plan = new TreeMap<Integer, Integer>();
      rewards.forEach(e -> plan.put(e.getIntKey(), e.getIntValue()));
      var receipt =
          new GameplayState.OfferingClaim(
              new TreeSet<>(levels).stream().toList(), plan, avatars, dice);
      state.getClaims().add(receipt);
      try {
        player.save();
      } catch (RuntimeException failed) {
        if (id != 5) throw failed;
        state.getClaims().remove(receipt);
        return rejected(Retcode.SYSTEM_BUSY);
      }
      var gson = emu.lunarcore.data.ResourceLoader.getGson();
      var prepared = id == 5 ? gson.fromJson(gson.toJson(state), GameplayState.Offering.class) : null;
      emu.lunarcore.game.inventory.Inventory.GrantResult granted;
      try {
        granted = player.getInventory().grantItems(rewards, true);
      } catch (RuntimeException interrupted) {
        if (id != 5) throw interrupted;
        return rejected(Retcode.SYSTEM_BUSY);
      }
      if (granted == null) return rejected(Retcode.PLAYER_DATA_ERROR);
      if (!granted.complete())
        return new Result(Retcode.PLAYER_DATA_ERROR, List.copyOf(granted.items()));
      receipt
          .getAvatars()
          .forEach(
              (item, avatar) ->
                  owned
                      .getAvatars()
                      .put(
                          item,
                          new GameplayState.DiceAvatar(
                              avatar.getId(), avatar.getTime(), avatar.getDice())));
      owned.getDice().putAll(receipt.getDice());
      receipt.complete();
      try {
        player.save();
      } catch (RuntimeException interrupted) {
        if (id != 5) throw interrupted;
        player.getGameplay().getOfferings().put(id, prepared);
        return rejected(Retcode.SYSTEM_BUSY);
      }
      return new Result(Retcode.SUCC, List.copyOf(granted.items()));
    }
  }

  private static boolean capacity(Player player, ItemParamMap rewards) {
    int newStacks = 0;
    for (var entry : rewards) {
      int id = entry.getIntKey();
      var excel = GameData.getItemExcelMap().get(id);
      if (excel == null || excel.getPileLimit() <= 0) return false;
      long before;
      if (excel.getItemMainType() == ItemMainType.Virtual)
        before =
            switch (id) {
              case 1 -> player.getHcoin();
              case 2 -> player.getScoin();
              case 3 -> player.getMcoin();
              case 11 -> player.getStamina();
              case 22 -> player.getExp();
              case 32 -> player.getTalentPoints();
              case 33, 34 -> RogueCurrencies.balance(player, id);
              default -> -1;
            };
      else if (excel.getItemMainType() == ItemMainType.Usable
          && excel.getItemSubType() == ItemSubType.ChatBubble && entry.getIntValue() == 1) continue;
      else if (Inventory.isStackType(excel)) {
        var item = player.getInventory().getMaterialByItemId(id);
        before = item == null ? 0 : item.getCount();
        if (item == null) newStacks++;
      } else return false;
      if (before < 0
          || before + entry.getIntValue()
              > Math.min((long) excel.getPileLimit(), Integer.MAX_VALUE)) return false;
    }
    var tab = player.getInventory().getTabByItemType(ItemMainType.Material);
    return tab != null && newStacks <= tab.getAvailableCapacity();
  }

  private Offerings() {}
}
