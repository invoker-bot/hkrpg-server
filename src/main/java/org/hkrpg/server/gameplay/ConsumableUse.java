package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemUseExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Prepared, serialized item use. Unknown effects cannot consume inventory. */
public final class ConsumableUse {
  public record Result(Retcode retcode, List<GameItem> items, int consumed, int formula) {
    public Result(Retcode retcode, List<GameItem> items, int consumed) {
      this(retcode, items, consumed, 0);
    }
  }

  private record Food(PlayerLineup lineup, List<Vital> targets, int mp, ItemUseExcel buff) {
    void apply(Player player) {
      for (var v : targets) {
        v.avatar.setCurrentHp(lineup, v.hp);
        v.avatar.setCurrentSp(lineup, v.sp);
        v.avatar.save();
      }
      if (mp != lineup.getMp()) lineup.setMp(mp);
      if (buff != null && !player.addFoodBuff(buff.getConsumeType(), buff))
        throw new IllegalStateException("Prepared food buff could not be applied");
      if (!targets.isEmpty()) lineup.refreshLineup();
    }
  }

  private record Vital(GameAvatar avatar, int hp, int sp) {}

  private final Player player;

  public ConsumableUse(Player player) {
    this.player = player;
  }

  public Result use(int id, int count, int avatar) {
    synchronized (player) {
      var previous = player.getGameplay().getLastItemUse();
      if (previous != null && (previous.pending() || previous.getSequence() == Long.MAX_VALUE))
        return error(Retcode.SYSTEM_BUSY);
      if (id <= 0 || count <= 0 || avatar < 0) return error(Retcode.ITEM_COUNT_INVALID);
      var inventory = player.getInventory();
      var item = inventory.getMaterialByItemId(id);
      if (item == null) return error(Retcode.ITEM_NOT_EXIST);
      if (item.getCount() < count) return error(Retcode.ITEM_NOT_ENOUGH);
      var excel = item.getExcel();
      if (excel == null || excel.getUseMethod() == null) return error(Retcode.ITEM_INVALID_USE);
      if (excel.getUseMethod() == ItemUseMethod.Recipe)
        return FormulaLearning.use(player, item, count, avatar);
      int data = excel.getUseDataID() > 0 ? excel.getUseDataID() : id;
      var config = GameData.getItemUseExcelMap().get(data);
      if (config == null) return error(Retcode.ITEM_USE_CONFIG_NOT_EXIST);
      int maximum = config.getUseMultipleMax() > 0 ? config.getUseMultipleMax() : 1;
      if (count > maximum) return error(Retcode.ITEM_COUNT_INVALID);
      if (player.isInBattle()) return error(Retcode.ITEM_INVALID_USE);
      var rewards = new ItemParamMap();
      Food food = null;
      switch (excel.getUseMethod()) {
        case FixedRewardGift -> {
          if (avatar != 0 || !fixedRewards(config, count, rewards))
            return error(Retcode.ITEM_INVALID_USE);
          if (!capacity(rewards)) return error(Retcode.ITEM_REWARD_EXCEED_LIMIT);
        }
        case TeamSpecificFoodBenefit, ExternalSystemFoodBenefit -> {
          food = prepareFood(config, excel.getUseMethod(), count, avatar);
          if (food == null) return error(Retcode.ITEM_USE_TARGET_TYPE_INVALID);
        }
        default -> {
          return error(Retcode.ITEM_INVALID_USE);
        }
      }
      var frozen = new TreeMap<Integer, Integer>();
      for (var r : rewards) frozen.put(r.getIntKey(), r.getIntValue());
      var receipt = player.getGameplay().reserveItemUse(id, count, avatar, frozen);
      player.save();
      var received = new ArrayList<GameItem>();
      boolean[] complete = {true};
      Food effect = food;
      if (!inventory.consumeForUse(
          item,
          count,
          () -> {
            receipt.debited();
            player.save();
            if (effect != null) effect.apply(player);
            else {
              var grant = inventory.grantItems(rewards, false);
              received.addAll(grant.items());
              complete[0] = grant.complete();
            }
            receipt.settle(received, complete[0]);
            player.save();
          })) {
        receipt.cancel();
        player.save();
        return error(Retcode.ITEM_NOT_ENOUGH);
      }
      if (complete[0]) player.getDailyManager().onItemUsed(excel.getItemSubType(), count);
      return new Result(
          complete[0] ? Retcode.SUCC : Retcode.PLAYER_DATA_ERROR, List.copyOf(received), count);
    }
  }

  private Food prepareFood(ItemUseExcel c, ItemUseMethod method, int count, int target) {
    var lineup = player.getCurrentLineup();
    double hp = c.getPreviewHPRecoveryPercent(), sp = c.getPreviewPowerPercent();
    if (count != 1
        || lineup == null
        || lineup.isExtraLineup()
        || lineup.getAvatars().isEmpty()
        || lineup.size() > 4
        || new HashSet<>(lineup.getAvatars()).size() != lineup.size()
        || lineup.getLeader() < 0
        || lineup.getLeader() >= lineup.size()
        || !Double.isFinite(hp)
        || !Double.isFinite(sp)
        || hp < -1
        || hp > 1
        || sp < 0
        || sp > 1
        || c.getPreviewHPRecoveryValue() != 0
        || c.getPreviewSkillPoint() < 0
        || c.getPreviewSkillPoint() > lineup.getMaxMp()
        || c.getMazeBuffID2() != 0) return null;
    if (method == ItemUseMethod.TeamSpecificFoodBenefit && target <= 0) return null;
    if (target != 0 && !lineup.getAvatars().contains(target)) return null;
    ItemUseExcel buff = null;
    if (method == ItemUseMethod.ExternalSystemFoodBenefit
        && (c.getConsumeType() == 1 || c.getConsumeType() == 2)) {
      if (target != 0
          || c.getActivityCount() <= 0
          || player.getScene() == null
          || GameData.getMazeBuffExcelMap().get(c.getMazeBuffID(), 1) == null
          || player.getCurrentLeaderAvatar() == null
          || c.getUseTargetType() != null) return null;
      buff = c;
    }
    var ids = target == 0 ? List.copyOf(lineup.getAvatars()) : List.of(target);
    var targets = new ArrayList<Vital>();
    for (int id : ids) {
      var a = player.getAvatarById(id);
      if (a == null) return null;
      int oldHp = a.getCurrentHp(lineup), oldSp = a.getCurrentSp(lineup);
      if (oldHp < 0 || oldHp > a.getMaxHp() || oldSp < 0 || oldSp > a.getMaxSp()) return null;
      if (c.isIsCheckHP() && oldHp == a.getMaxHp()) {
        if (target != 0) return null;
        else continue;
      }
      boolean eligible = c.getUseTargetType() == ItemFoodTargetType.Dead ? oldHp == 0 : oldHp > 0;
      if (!eligible) {
        if (target != 0) return null;
        else continue;
      }
      int nextHp =
          Math.min(a.getMaxHp(), Math.max((int) (oldHp + hp * a.getMaxHp()), hp < 0 ? 100 : 0));
      int nextSp = Math.min(a.getMaxSp(), (int) (oldSp + sp * a.getMaxSp()));
      if (c.getUseTargetType() == ItemFoodTargetType.Dead && nextHp <= 0) return null;
      if (nextHp != oldHp || nextSp != oldSp) targets.add(new Vital(a, nextHp, nextSp));
    }
    int mp = Math.min(lineup.getMaxMp(), lineup.getMp() + c.getPreviewSkillPoint());
    if (targets.isEmpty() && mp == lineup.getMp() && buff == null) return null;
    return new Food(lineup, List.copyOf(targets), mp, buff);
  }

  public static boolean fixedRewards(ItemUseExcel c, int count, ItemParamMap result) {
    if (c == null || count <= 0 || result == null || c.getUseParam() == null
        || c.getUseParam().isEmpty() || c.getUseParam().size() > 20)
      return false;
    try {
      for (int id : c.getUseParam()) {
        var reward = GameData.getRewardExcelMap().get(id);
        if (id <= 0
            || reward == null
            || reward.getRewards() == null
            || reward.getRewards().isEmpty()) return false;
        for (var r : reward.getRewards()) {
          if (r.getIntKey() <= 0 || r.getIntValue() <= 0) return false;
          result.put(
              r.getIntKey(),
              Math.addExact(result.get(r.getIntKey()), Math.multiplyExact(r.getIntValue(), count)));
        }
      }
    } catch (ArithmeticException e) {
      return false;
    }
    return !result.isEmpty();
  }

  private boolean capacity(ItemParamMap rewards) {
    int newStacks = 0;
    for (var r : rewards) {
      int id = r.getIntKey(), amount = r.getIntValue();
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
      if (before < 0 || before + amount > Math.min((long) excel.getPileLimit(), Integer.MAX_VALUE))
        return false;
    }
    var tab = player.getInventory().getTabByItemType(ItemMainType.Material);
    return tab != null && newStacks <= tab.getAvailableCapacity();
  }

  private static Result error(Retcode code) {
    return new Result(code, List.of(), 0);
  }
}
