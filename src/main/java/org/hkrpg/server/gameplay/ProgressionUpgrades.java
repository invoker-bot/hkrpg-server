package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.game.avatar.*;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.Syncable;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketPlayerSyncScNotify;
import java.util.*;

/** Resource-authoritative promotion and traces; all validation precedes debit under one player lock. */
public final class ProgressionUpgrades {
  static GameAvatar ownedAvatar(Player player, int id) {
    var mapping = GameData.getMultiplePathAvatarExcelMap().get(id);
    var avatar = player.getAvatars().getAvatars().get(mapping == null ? id : mapping.getBaseAvatarID());
    return avatar != null && avatar.getOwner() == player && avatar.getOwnerUid() == player.getUid()
        && avatar.getExcel() != null ? avatar : null;
  }

  public static Retcode promoteAvatar(Player player, int id) {
    synchronized (player) {
      var avatar = ownedAvatar(player, id);
      if (avatar == null) return Retcode.REQ_PARA_INVALID;
      int stage = avatar.getPromotion();
      if (stage < 0 || stage >= avatar.getExcel().getMaxPromotion()) return Retcode.AVATAR_REACH_MAX_PROMOTION;
      // Level and promotion are shared across paths; use the active path's resource row.
      int resourceId = avatar.getExcel().getAvatarID();
      var current = GameData.getAvatarPromotionExcelMap().get(resourceId, stage);
      var next = GameData.getAvatarPromotionExcelMap().get(resourceId, stage + 1);
      if (current == null || next == null || current.getMaxLevel() < 1 || next.getMaxLevel() <= current.getMaxLevel())
        return Retcode.REQ_PARA_INVALID;
      if (avatar.getLevel() != current.getMaxLevel() || player.getLevel() < current.getPlayerLevelRequire()
          || player.getWorldLevel() < current.getWorldLevelRequire()) return Retcode.AVATAR_PROMOTION_NOT_MEET;
      var cost = costs(player, current.getPromotionCostList());
      if (cost == null) return Retcode.ITEM_NOT_ENOUGH;
      player.getInventory().removeItemsByParams(cost);
      avatar.setPromotion(stage + 1);
      avatar.save(); player.save();
      player.sendPacket(new PacketPlayerSyncScNotify(avatar));
      return Retcode.SUCC;
    }
  }

  public static Retcode promoteEquipment(Player player, int id) {
    synchronized (player) {
      var item = player.getInventory().getItemByUid(id);
      if (item == null || item.getOwnerUid() != player.getUid() || item.getCount() != 1
          || item.getExcel() == null || !item.getExcel().isEquipment()) return Retcode.ITEM_NOT_EXIST;
      int stage = item.getPromotion();
      if (stage < 0 || stage >= item.getExcel().getEquipmentExcel().getMaxPromotion())
        return Retcode.EQUIPMENT_PROMOTION_REACH_MAX;
      var current = GameData.getEquipmentPromotionExcelMap().get(item.getItemId(), stage);
      var next = GameData.getEquipmentPromotionExcelMap().get(item.getItemId(), stage + 1);
      if (current == null || next == null || current.getMaxLevel() < 1 || next.getMaxLevel() <= current.getMaxLevel()
          || current.getPromotionCostList() == null || current.getPromotionCostCoin() < 0) return Retcode.REQ_PARA_INVALID;
      if (item.getLevel() != current.getMaxLevel() || player.getLevel() < current.getPlayerLevelRequire()
          || player.getWorldLevel() < current.getWorldLevelRequire()) return Retcode.EQUIPMENT_LEVEL_NOT_MEET;
      var source = new ArrayList<>(current.getPromotionCostList());
      if (current.getPromotionCostCoin() > 0) source.add(new ItemParam(2, current.getPromotionCostCoin()));
      var cost = costs(player, source);
      if (cost == null) return Retcode.ITEM_NOT_ENOUGH;
      player.getInventory().removeItemsByParams(cost);
      item.setPromotion(stage + 1);
      item.save(); player.save();
      player.sendPacket(new PacketPlayerSyncScNotify(item));
      return Retcode.SUCC;
    }
  }

  public static Retcode unlockPoint(Player player, int point, int level) {
    var row = GameData.getAvatarSkillTreeExcelMap().get(point, level);
    if (row == null) return Retcode.SKILLTREE_CONFIG_NOT_EXIST;
    return unlock(player, row.getAvatarID(), row.getAnchorId(), level, point);
  }

  public static Retcode unlock(Player player, int avatarId, int anchor, int targetLevel, int requestedPoint) {
    synchronized (player) {
      var avatar = ownedAvatar(player, avatarId);
      if (avatar == null) return Retcode.REQ_PARA_INVALID;
      BaseAvatar owner = avatar;
      if (GameData.getMultiplePathAvatarExcelMap().containsKey(avatarId)) {
        var path = player.getAvatars().getMultiPathById(avatarId);
        if (path == null || path.getOwner() != player || path.getOwnerUid() != player.getUid()
            || path.getExcel() == null) return Retcode.REQ_PARA_INVALID;
        owner = path;
      }
      var tree = owner.getData().getSkillTree();
      int current = tree.get(anchor);
      if (current < 0 || targetLevel < 1 || targetLevel > 100 || anchor < 1 || anchor > 22)
        return Retcode.REQ_PARA_INVALID;
      if (targetLevel <= current) return Retcode.SKILLTREE_ALREADY_UNLOCK;
      var source = new ArrayList<ItemParam>();
      for (int level = current + 1; level <= targetLevel; level++) {
        var row = GameData.getAvatarSkilltree(avatarId, owner.getEnhanceId(), anchor, level);
        if (row == null || (requestedPoint != 0 && row.getPointID() != requestedPoint)
            || row.getMaterialList() == null || row.getPrePoint() == null) return Retcode.SKILLTREE_CONFIG_NOT_EXIST;
        if (targetLevel > row.getMaxLevel()) return Retcode.SKILLTREE_POINT_LEVEL_REACH_MAX;
        if (avatar.getPromotion() < row.getAvatarPromotionLimit()) return Retcode.SKILLTREE_RANK_NOT_MEET;
        if (avatar.getLevel() < row.getAvatarLevelLimit()) return Retcode.SKILLTREE_LEVEL_NOT_MEET;
        for (int prerequisite : row.getPrePoint()) {
          boolean unlocked = false;
          // PrePoint contains resource point IDs, whereas the save stores anchor IDs. Resolve in the same enhanced tree.
          for (var candidate : AvatarSkillTreeAnchorType.values()) {
            var prior = GameData.getAvatarSkilltree(avatarId, owner.getEnhanceId(), candidate.getValue(), 1);
            if (prior != null && prior.getPointID() == prerequisite && tree.get(candidate.getValue()) > 0) {
              unlocked = true; break;
            }
          }
          if (!unlocked) return Retcode.SKILLTREE_PRE_LOCKED;
        }
        source.addAll(row.getMaterialList());
      }
      var cost = costs(player, source);
      if (cost == null) return Retcode.ITEM_NOT_ENOUGH;
      player.getInventory().removeItemsByParams(cost);
      tree.put(anchor, targetLevel);
      if (owner instanceof AvatarMultiPath path) {
        path.save();
        // An inactive path must update its own tree, without changing the selected path or its equipment.
        player.sendPacket(new PacketPlayerSyncScNotify((Syncable) sync ->
            sync.getMutableAvatarSync().addAvatarPathInfoList(path.toPathInfoProto())));
      } else {
        avatar.save();
        player.sendPacket(new PacketPlayerSyncScNotify(avatar));
      }
      player.save();
      return Retcode.SUCC;
    }
  }

  private static List<ItemParam> costs(Player player, Collection<ItemParam> source) {
    if (source == null || source.size() > 1000) return null;
    var totals = new LinkedHashMap<Integer, Integer>();
    for (var item : source) {
      if (item == null || item.getType() != ItemParamType.PILE || item.getId() <= 0 || item.getCount() <= 0) return null;
      long count = (long) totals.getOrDefault(item.getId(), 0) + item.getCount();
      if (count > Integer.MAX_VALUE) return null;
      totals.put(item.getId(), (int) count);
    }
    var result = new ArrayList<ItemParam>();
    for (var entry : totals.entrySet()) {
      int id = entry.getKey(), count = entry.getValue();
      if (id == 2) {
        if (player.getScoin() < count) return null;
      } else {
        var item = player.getInventory().getMaterialByItemId(id);
        if (item == null || item.getOwnerUid() != player.getUid() || item.getCount() < count
            || item.getExcel() == null || item.getItemMainType() != ItemMainType.Material
            || item.isLocked() || player.getInventory().getItemByUid(item.getInternalUid()) != item) return null;
      }
      result.add(new ItemParam(id, count));
    }
    return result;
  }

  private ProgressionUpgrades() {}
}
