package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.game.avatar.*;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.server.game.Syncable;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketPlayerSyncScNotify;
import java.util.*;

/** Formal ownership, requested-path identity and resource-authoritative milestone transactions. */
public final class AvatarMilestones {
  private static GameAvatar formal(Player player, int id) {
    if (id <= 0) return null;
    var avatar = ProgressionUpgrades.ownedAvatar(player, id);
    return avatar != null && avatar.getAvatarType() == AvatarType.AVATAR_FORMAL_TYPE ? avatar : null;
  }

  /** A packet activates exactly the next rank, as in the pinned receive handler. */
  public static Retcode activateRank(Player player, int id, Collection<ItemParam> requested) {
    synchronized (player) {
      var avatar = formal(player, id);
      if (avatar == null) return Retcode.AVATAR_NOT_EXIST;
      BaseAvatar owner = avatar;
      if (GameData.getMultiplePathAvatarExcelMap().containsKey(id)) {
        var path = player.getAvatars().getMultiPathById(id);
        if (path == null || path.getOwner() != player || path.getOwnerUid() != player.getUid()
            || path.getExcelId() != id || path.getExcel() != GameData.getAvatarExcelMap().get(id))
          return Retcode.AVATAR_NOT_EXIST;
        owner = path;
      }
      var source = owner.getExcel();
      if (source == null || source != GameData.getAvatarExcelMap().get(id)) return Retcode.REQ_PARA_INVALID;
      int rank = owner.getData().getRank(), max = source.getMaxRank();
      if (rank < 0 || max < 1 || max > 100 || source.getRankIDList() == null
          || source.getRankIDList().length != max) return Retcode.REQ_PARA_INVALID;
      if (rank >= max) return Retcode.AVATAR_RANK_REACH_MAX;
      var row = GameData.getAvatarRankExcelMap().get(source.getRankIDList()[rank]);
      if (row == null || row.getRank() != rank + 1) return Retcode.REQ_PARA_INVALID;
      var costs = quantities(row.getUnlockCost());
      if (costs == null || (requested != null && !costs.equals(quantities(requested))))
        return Retcode.REQ_PARA_INVALID;
      var previous = player.getGameplay().getAvatarRankChanges().get(id);
      if (previous != null && previous.pending()) return Retcode.SYSTEM_BUSY;
      var before = new TreeMap<Integer,Integer>();
      for (var entry : costs.entrySet()) {
        var item = player.getInventory().getMaterialByItemId(entry.getKey());
        if (!ownedMaterial(player, item) || item.isLocked() || item.getCount() < entry.getValue())
          return Retcode.ITEM_NOT_ENOUGH;
        before.put(entry.getKey(),item.getCount());
      }
      var change = new AvatarRankChange(id,row.getId(),rank,costs,before);
      player.getGameplay().getAvatarRankChanges().put(id,change);
      player.save();
      var debit = new ArrayList<ItemParam>();
      costs.forEach((item,count) -> debit.add(new ItemParam(item,count)));
      player.getInventory().removeItemsByParams(debit);
      for (var entry : costs.entrySet()) {
        var remaining = player.getInventory().getMaterialByItemId(entry.getKey());
        if ((remaining == null ? 0 : remaining.getCount()) != before.get(entry.getKey()) - entry.getValue())
          return Retcode.PLAYER_DATA_ERROR;
      }
      change.debited(); player.save();
      owner.getData().setRank(rank + 1);
      try {
        if (owner instanceof AvatarMultiPath path) path.save(); else avatar.save();
      } catch (RuntimeException failed) {
        owner.getData().setRank(rank);
        throw failed;
      }
      change.complete();
      try { player.save(); }
      catch (RuntimeException failed) { change.debited(); throw failed; }
      if (owner instanceof AvatarMultiPath path && avatar.getMultiPath() != path) {
        player.sendPacket(new PacketPlayerSyncScNotify((Syncable) sync ->
            sync.getMutableAvatarSync().addAvatarPathInfoList(path.toPathInfoProto())));
      } else player.sendPacket(new PacketPlayerSyncScNotify(avatar));
      return Retcode.SUCC;
    }
  }

  private static Map<Integer,Integer> quantities(Collection<ItemParam> source) {
    if (source == null || source.isEmpty() || source.size() > 100) return null;
    var result = new TreeMap<Integer,Integer>();
    try {
      for (var entry : source) {
        if (entry == null || entry.getId() <= 0 || entry.getCount() <= 0 || entry.getType() != ItemParamType.PILE)
          return null;
        result.merge(entry.getId(),entry.getCount(),Math::addExact);
      }
    } catch (ArithmeticException invalid) { return null; }
    return result;
  }

  private static boolean ownedMaterial(Player player, GameItem item) {
    return item != null && item.getOwnerUid() == player.getUid() && item.getCount() >= 0
        && item.getExcel() != null && item.getExcel() == GameData.getItemExcelMap().get(item.getItemId())
        && item.getItemMainType() == ItemMainType.Material
        && player.getInventory().getItemByUid(item.getInternalUid()) == item;
  }

  public static String promotionKey(int baseAvatar, int promotion) {
    return "avatar-promotion:" + baseAvatar + ":" + promotion;
  }

  public static RewardLedger.Claim claimPromotion(Player player, int id, int promotion) {
    synchronized (player) {
      var avatar = formal(player,id);
      if (avatar == null) return error(Retcode.AVATAR_NOT_EXIST);
      var config = GameData.getAvatarPromotionRewardExcelMap().get(promotion);
      if (promotion <= 0 || promotion > avatar.getPromotion() || promotion > avatar.getExcel().getMaxPromotion()
          || config == null || config.getPromotionRewardId() <= 0)
        return error(Retcode.PROMOTION_REWARD_CONFIG_NOT_EXIST);
      if (avatar.hasTakenReward(promotion)) return error(Retcode.PROMOTION_REWARD_ALREADY_TAKEN);
      String key = promotionKey(avatar.getAvatarId(),promotion);
      var receipt = player.getGameplay().getRewards().get(key);
      if (receipt != null) {
        if (!receipt.isGranted()) return error(Retcode.SYSTEM_BUSY);
        markClaimed(player,avatar,promotion);
        return error(Retcode.PROMOTION_REWARD_ALREADY_TAKEN);
      }
      var reward = GameData.getRewardExcelMap().get(config.getPromotionRewardId());
      if (reward == null || reward.getRewards() == null || reward.getRewards().isEmpty())
        return error(Retcode.REWARD_CONFIG_NOT_EXIST);
      int newStacks = 0;
      for (var entry : reward.getRewards()) {
        int itemId = entry.getIntKey(), count = entry.getIntValue();
        var excel = GameData.getItemExcelMap().get(itemId);
        if (count <= 0 || excel == null || excel.getItemMainType() != ItemMainType.Material || excel.getPileLimit() <= 0)
          return error(Retcode.REWARD_CONFIG_NOT_EXIST);
        var existing = player.getInventory().getMaterialByItemId(itemId);
        if (existing != null && !ownedMaterial(player,existing)) return error(Retcode.PLAYER_DATA_ERROR);
        if (existing == null) newStacks++;
        if ((long)(existing == null ? 0 : existing.getCount()) + count > excel.getPileLimit())
          return error(Retcode.ITEM_REWARD_EXCEED_LIMIT);
      }
      var tab = player.getInventory().getTabByItemType(ItemMainType.Material);
      if (tab == null || newStacks > tab.getAvailableCapacity()
          || (newStacks > 0 && !player.getInventory().hasItemIdCapacity(newStacks)))
        return error(Retcode.ITEM_REWARD_EXCEED_LIMIT);
      var result = player.getRewardLedger().claim(key,config.getPromotionRewardId());
      if (result.retcode() != Retcode.SUCC) return result;
      markClaimed(player,avatar,promotion);
      return result;
    }
  }

  private static void markClaimed(Player player, GameAvatar avatar, int promotion) {
    int previous = avatar.getRewards();
    avatar.takeReward(promotion);
    try { avatar.save(); }
    catch (RuntimeException failed) { avatar.setRewards(previous); throw failed; }
    player.sendPacket(new PacketPlayerSyncScNotify(avatar));
  }

  private static RewardLedger.Claim error(Retcode code) { return new RewardLedger.Claim(code,List.of()); }
  private AvatarMilestones() {}
}
