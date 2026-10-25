package emu.lunarcore.game.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.GameDepot;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.ItemRarity;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.BaseGameService;
import emu.lunarcore.server.game.GameServer;
import emu.lunarcore.server.packet.send.*;
import us.hebi.quickbuf.RepeatedInt;
import org.hkrpg.server.gameplay.UpgradeMaterials;
import org.hkrpg.server.gameplay.AvatarExperience;
import org.hkrpg.server.gameplay.EquipmentExperience;
import org.hkrpg.server.gameplay.EquipmentSuperimposition;

public class InventoryService extends BaseGameService {

    public InventoryService(GameServer server) {
        super(server);
    }

    // === Avatars ===

    public List<GameItem> levelUpAvatar(Player player, int avatarId, Collection<ItemParam> items) {
        synchronized (player) {
            return levelUpAvatarLocked(player, avatarId, items);
        }
    }

    private List<GameItem> levelUpAvatarLocked(Player player, int avatarId, Collection<ItemParam> items) {
        // Get avatar
        GameAvatar avatar = player.getAvatarById(avatarId);
        if (avatar == null) return null;

        AvatarPromotionExcel promoteData = GameData.getAvatarPromotionExcelMap().get(avatarId, avatar.getPromotion());
        if (promoteData == null) return null;

        if (avatar.getOwnerUid() != player.getUid()
            || player.getAvatars().getAvatarById(avatarId) != avatar || avatar.getExcel() == null) return null;
        int oldLevel = avatar.getLevel();
        int initialRequired = GameData.getAvatarExpRequired(avatar.getExcel().getExpGroup(), oldLevel);
        if (oldLevel < 1 || oldLevel >= promoteData.getMaxLevel() || initialRequired <= 0
            || avatar.getExp() < 0 || avatar.getExp() >= initialRequired) return null;

        var plan = UpgradeMaterials.prepare(player, null, items, UpgradeMaterials.Kind.AVATAR);
        if (plan == null || player.getScoin() < plan.coins()) return null;
        int amount = plan.amount();
        int cost = plan.coins();
        var progress = AvatarExperience.prepare(avatar, amount);
        if (progress == null || progress.applied() == 0) return null;

        // Pay items
        player.getInventory().removeItemsByParams(plan.items());
        player.addSCoin(-cost);

        // Level up
        int level = progress.level();
        int exp = progress.exp();
        amount = progress.remaining();

        // Done
        avatar.setLevel(level);
        avatar.setExp(exp);

        avatar.save();
        player.save();

        // Calculate leftover exp
        var leftoverItems = new ItemParamMap();

        while (GameDepot.getAvatarExpExcels().size() > 0) {
            int oldAmount = amount;
            for (var expExcel : GameDepot.getAvatarExpExcels()) {
                if (amount >= expExcel.getExp()) {
                    leftoverItems.put(expExcel.getItemID(), leftoverItems.get(expExcel.getItemID()) + 1);
                    amount -= expExcel.getExp();
                    break;
                }
            }
            if (oldAmount == amount) break;
        }

        // Create leftover exp items
        List<GameItem> returnItems = player.getInventory().addItems(leftoverItems);

        // Send packets
        player.sendPacket(new PacketPlayerSyncScNotify(avatar));
        if (level > oldLevel) player.getDailyManager().onAvatarLevelUp();
        return returnItems;
    }

    public boolean promoteAvatar(Player player, int avatarId) {
        return org.hkrpg.server.gameplay.ProgressionUpgrades.promoteAvatar(player, avatarId) == emu.lunarcore.server.packet.Retcode.SUCC;
    }

    public boolean unlockSkillTreeAvatar(Player player, int avatarId, int anchorId, int targetLevel) {
        return org.hkrpg.server.gameplay.ProgressionUpgrades.unlock(player, avatarId, anchorId, targetLevel, 0) == emu.lunarcore.server.packet.Retcode.SUCC;
    }

    public boolean rankUpAvatar(Player player, int avatarId) {
        return org.hkrpg.server.gameplay.AvatarMilestones.activateRank(player, avatarId, null)
                == emu.lunarcore.server.packet.Retcode.SUCC;
    }

    public List<GameItem> takePromotionRewardAvatar(Player player, int avatarId, int promotion) {
        var result = org.hkrpg.server.gameplay.AvatarMilestones.claimPromotion(player, avatarId, promotion);
        return result.retcode() == emu.lunarcore.server.packet.Retcode.SUCC ? result.items() : null;
    }

    public GameAvatar markAvatar(Player player, int avatarId, boolean isMarked) {
        // Get avatar
        GameAvatar avatar = player.getAvatarById(avatarId);
        if (avatar == null) return null;

        // Mark avatar
        avatar.setMarked(isMarked);
        avatar.save();

        // Send packets
        player.sendPacket(new PacketPlayerSyncScNotify(avatar));
        return avatar;
    }

    // === Equipment ===

    public List<GameItem> levelUpEquipment(Player player, int equipId, Collection<ItemParam> items) {
        synchronized (player) {
            return levelUpEquipmentLocked(player, equipId, items);
        }
    }

    private List<GameItem> levelUpEquipmentLocked(Player player, int equipId, Collection<ItemParam> items) {
        // Get equipment
        GameItem equip = player.getInventory().getItemByUid(equipId);

        if (equip == null || !equip.getExcel().isEquipment()) {
            return null;
        }

        EquipmentPromotionExcel promoteData = GameData.getEquipmentPromotionExcelMap().get(equip.getItemId(), equip.getPromotion());
        if (promoteData == null) return null;

        if (equip.getOwnerUid() != player.getUid() || equip.getCount() != 1) return null;
        int oldLevel = equip.getLevel();
        int initialRequired = GameData.getEquipmentExpRequired(equip.getExcel().getEquipmentExcel().getExpType(), oldLevel);
        if (oldLevel < 1 || oldLevel >= promoteData.getMaxLevel() || initialRequired <= 0
            || equip.getExp() < 0 || equip.getExp() >= initialRequired) return null;

        var plan = UpgradeMaterials.prepare(player, equip, items, UpgradeMaterials.Kind.EQUIPMENT);
        if (plan == null || player.getScoin() < plan.coins()) return null;
        int amount = plan.amount();
        int cost = plan.coins();
        var progress = EquipmentExperience.prepare(equip, amount);
        if (progress == null || progress.applied() == 0) return null;

        // Pay items
        player.getInventory().removeItemsByParams(plan.items());
        player.addSCoin(-cost);

        // Level up
        int level = progress.level();
        int exp = progress.exp();
        amount = progress.remaining();

        // Done
        equip.setLevel(level);
        equip.setExp(exp);

        equip.save();
        player.save();

        // Calculate leftover exp
        var leftoverItems = new ItemParamMap();

        while (GameDepot.getEquipmentExpExcels().size() > 0) {
            int oldAmount = amount;
            for (var expExcel : GameDepot.getEquipmentExpExcels()) {
                if (amount >= expExcel.getExpProvide()) {
                    leftoverItems.put(expExcel.getItemID(), leftoverItems.get(expExcel.getItemID()) + 1);
                    amount -= expExcel.getExpProvide();
                    break;
                }
            }
            if (oldAmount == amount) break;
        }

        // Create leftover exp items
        List<GameItem> returnItems = player.getInventory().addItems(leftoverItems);

        // Send packets
        player.sendPacket(new PacketPlayerSyncScNotify(equip));
        if (level > oldLevel) player.getDailyManager().onEquipmentLevelUp();
        return returnItems;
    }

    public boolean promoteEquipment(Player player, int equipId) {
        return org.hkrpg.server.gameplay.ProgressionUpgrades.promoteEquipment(player, equipId) == emu.lunarcore.server.packet.Retcode.SUCC;
    }

    public boolean rankUpEquipment(Player player, int equipId, List<ItemParam> items) {
        return EquipmentSuperimposition.apply(player, equipId, items);
    }

    // === Relic ===

    public List<GameItem> levelUpRelic(Player player, int equipId, Collection<ItemParam> items) {
        synchronized (player) {
            return levelUpRelicLocked(player, equipId, items);
        }
    }

    private List<GameItem> levelUpRelicLocked(Player player, int equipId, Collection<ItemParam> items) {
        // Get relic
        GameItem equip = player.getInventory().getItemByUid(equipId);

        if (equip == null || !equip.getExcel().isRelic()) {
            return null;
        }

        if (equip.getOwnerUid() != player.getUid() || equip.getCount() != 1) return null;
        int oldLevel = equip.getLevel();
        int initialRequired = GameData.getRelicExpRequired(equip.getExcel().getRelicExcel().getExpType(), oldLevel);
        if (oldLevel < 0 || oldLevel >= equip.getExcel().getRelicExcel().getMaxLevel() || initialRequired <= 0
            || equip.getExp() < 0 || equip.getExp() >= initialRequired || equip.getTotalExp() < 0) return null;

        var plan = UpgradeMaterials.prepare(player, equip, items, UpgradeMaterials.Kind.RELIC);
        if (plan == null || player.getScoin() < plan.coins()) return null;
        if ((long) equip.getTotalExp() + plan.amount() > Integer.MAX_VALUE) return null;
        int amount = plan.amount();
        int cost = plan.coins();
        var progress = EquipmentExperience.prepare(equip, amount);
        if (progress == null || progress.applied() == 0) return null;

        // Pay items
        player.getInventory().removeItemsByParams(plan.items());
        player.addSCoin(-cost);

        // Level up
        int level = progress.level();
        int exp = progress.exp();
        int totalExp = equip.getTotalExp() + progress.applied();
        int upgrades = level / 3 - oldLevel / 3;
        amount = progress.remaining();

        // Add affixes
        if (upgrades > 0) {
            equip.addRandomSubAffixes(upgrades);
        }

        // Done
        equip.setLevel(level);
        equip.setExp(exp);
        equip.setTotalExp(totalExp);

        equip.save();
        player.save();

        // Calculate leftover exp
        var leftoverItems = new ItemParamMap();

        while (GameDepot.getRelicExpExcels().size() > 0) {
            int oldAmount = amount;
            for (var expExcel : GameDepot.getRelicExpExcels()) {
                if (amount >= expExcel.getExpProvide()) {
                    leftoverItems.put(expExcel.getItemID(), leftoverItems.get(expExcel.getItemID()) + 1);
                    amount -= expExcel.getExpProvide();
                    break;
                }
            }
            if (oldAmount == amount) break;
        }

        // Create leftover exp items
        List<GameItem> returnItems = player.getInventory().addItems(leftoverItems);

        // Send packets
        player.sendPacket(new PacketPlayerSyncScNotify(equip));
        if (level > oldLevel) player.getDailyManager().onRelicLevelUp();
        return returnItems;
    }

    public void reforgeRelic(Player player, int relicUniqueId) {
        // Get relic
        GameItem equip = player.getInventory().getItemByUid(relicUniqueId);

        if (equip == null || !equip.getExcel().isRelic()) {
            return;
        }

        // Sanity checks
        if (equip.getLevel() != 15) {
            return;
        }

        // Verify material cost
        if (player.getInventory().hasItems(GameConstants.RELIC_REFORGE_COST)) {
            player.getInventory().removeItemsByParams(GameConstants.RELIC_REFORGE_COST);
        } else {
            return;
        }

        // Reroll
        equip.reforgeSubAffixes();

        // Send packet
        player.sendPacket(new PacketPlayerSyncScNotify(equip));
    }

    public void confirmReforgeRelic(Player player, int relicUniqueId, boolean keep) {
        // Get relic
        GameItem equip = player.getInventory().getItemByUid(relicUniqueId);

        if (equip == null || !equip.getExcel().isRelic()) {
            return;
        }

        // Sanity checks
        if (equip.getReforgedSubAffixes() == null) {
            return;
        }

        // Reroll
        equip.confirmReforge(keep);
        equip.save();

        // Send packet
        player.sendPacket(new PacketPlayerSyncScNotify(equip));
    }

    // === Etc ===

    public void lockItems(Player player, RepeatedInt list, boolean locked) {
        // List of items to update on the client
        List<GameItem> items = new ArrayList<>();

        // Lock items
        for (int equipId : list) {
            GameItem item = player.getInventory().getItemByUid(equipId);
            if (item == null || !item.getExcel().isEquippable() || item.isDiscarded()) {
                continue;
            }

            item.setLocked(locked);
            item.save();

            items.add(item);
        }

        // Send packet
        if (items.size() > 0) {
            player.sendPacket(new PacketPlayerSyncScNotify(items));
        }
    }

    public void discardRelics(Player player, RepeatedInt list, boolean discarded) {
        // List of items to update on the client
        List<GameItem> items = new ArrayList<>();

        // Discard items
        for (int equipId : list) {
            GameItem item = player.getInventory().getItemByUid(equipId);
            if (item == null || !item.getExcel().isEquippable() || item.isLocked()) {
                continue;
            }

            item.setDiscarded(discarded);
            item.save();

            items.add(item);
        }

        // Send packet
        if (items.size() > 0) {
            player.sendPacket(new PacketPlayerSyncScNotify(items));
        }
    }

    public List<GameItem> sellItems(Player player, boolean toMaterials, List<ItemParam> items) {
        return org.hkrpg.server.gameplay.ItemSale.sell(player, toMaterials, items);
    }

    public boolean destroyItem(Player player, int itemId, int count) {
        // Sanity check
        if (count <= 0) return true;

        // Remove items from inventory
        if (player.getInventory().removePileItem(itemId, count)) {
            return true;
        } else {
            return false;
        }
    }

    public List<GameItem> composeItem(Player player, int composeId, int count, List<ItemParam> costItems) {
        return org.hkrpg.server.gameplay.ItemComposition.compose(player, composeId, count, costItems);
    }

    public List<GameItem> composeRelic(Player player, int composeId, int relicId, int count, int mainAffix, RepeatedInt subAffixList) {
        return org.hkrpg.server.gameplay.SelectedRelicComposition.compose(player, composeId, relicId, count, mainAffix, subAffixList);
    }
}
