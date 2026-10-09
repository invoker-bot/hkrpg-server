package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;

/** Real upgrade payments; interrupted cross-document writes require reconciliation. */
public final class DiceProgression {
  public static boolean pending(Player player) {
    return player.getGameplay().getDiceCombat().getUpgrades().stream()
        .anyMatch(r -> r == null || !r.isPaid());
  }

  public static boolean valid(Player player, DiceCombatCatalog catalog) {
    if (!DiceCombatShop.valid(player, catalog) || !Offerings.valid(player)) return false;
    var state = player.getGameplay().getDiceCombat();
    var levels = new HashMap<Integer, Integer>();
    boolean pending = false;
    for (var receipt : state.getUpgrades()) {
      if (receipt == null || pending) return false;
      var owned = state.getAvatars().get(receipt.getAvatar());
      var row = catalog.avatars().get(receipt.getAvatar());
      if (owned == null
          || row == null
          || receipt.getRarity() != row.rarity()
          || receipt.getAcquisitionTime() != owned.getTime()
          || receipt.getBefore() != levels.getOrDefault(receipt.getAvatar(), 1)
          || receipt.getAfter() != receipt.getBefore() + 1
          || receipt.getItem() != DiceProgressionRules.DUST
          || receipt.getAmount() <= 0
          || receipt.getAmount()
              != DiceProgressionRules.cost(receipt.getRarity(), receipt.getBefore())) return false;
      if (receipt.isPaid()) levels.put(receipt.getAvatar(), receipt.getAfter());
      else pending = true;
    }
    for (var owned : state.getAvatars().values())
      if (owned.getLevel() != levels.getOrDefault(owned.getId(), 1)) return false;
    return true;
  }

  private static Retcode preflight(Player player, DiceCombatCatalog catalog, Instant now) {
    if (catalog == null || !DiceProgressionRules.available())
      return Retcode.REWARD_CONFIG_NOT_EXIST;
    if (!valid(player, catalog)) return Retcode.PLAYER_DATA_ERROR;
    if (!catalog.available(player, now)) return Retcode.SHOP_NOT_OPEN;
    if (player.getBattle() != null
        || DicePveMissionReports.pending(player)
        || pending(player)
        || player.getGameplay().getDiceCombat().getPurchases().values().stream()
            .anyMatch(r -> !r.isGranted())
        || player.getGameplay().getOfferings().values().stream()
            .anyMatch(
                o ->
                    o == null
                        || o.getClaims() == null
                        || o.getSubmissions() == null
                        || o.getClaims().stream().anyMatch(c -> c == null || !c.isGranted())
                        || o.getSubmissions().stream().anyMatch(d -> d == null || !d.isPaid())))
      return Retcode.SYSTEM_BUSY;
    return Retcode.SUCC;
  }

  public static Retcode upgrade(Player player, DiceCombatCatalog catalog, int id, Instant now) {
    synchronized (player) {
      var code = preflight(player, catalog, now);
      if (code != Retcode.SUCC) return code;
      var state = player.getGameplay().getDiceCombat();
      var owned = state.getAvatars().get(id);
      var row = catalog.avatars().get(id);
      if (owned == null || row == null || !player.getInventory().hasItem(id, 1))
        return Retcode.REQ_PARA_INVALID;
      int amount = DiceProgressionRules.cost(row.rarity(), owned.getLevel());
      if (amount <= 0) return Retcode.REQ_PARA_INVALID;
      var excel = GameData.getItemExcelMap().get(DiceProgressionRules.DUST);
      if (excel == null
          || excel.getItemMainType() != ItemMainType.Material
          || excel.getPileLimit() < 1) return Retcode.REWARD_CONFIG_NOT_EXIST;
      var item = player.getInventory().getMaterialByItemId(DiceProgressionRules.DUST);
      if (item == null || item.getCount() < amount) return Retcode.ITEM_NOT_ENOUGH;
      var receipt =
          new GameplayState.DiceUpgrade(
              id,
              row.rarity(),
              owned.getLevel(),
              owned.getTime(),
              DiceProgressionRules.DUST,
              amount);
      state.reserve(receipt);
      player.save();
      boolean consumed =
          player
              .getInventory()
              .consumeForUse(
                  item,
                  amount,
                  () -> {
                    owned.setLevel(receipt.getAfter());
                    receipt.paid(true);
                    try {
                      player.save();
                    } catch (RuntimeException interrupted) {
                      owned.setLevel(receipt.getBefore());
                      receipt.paid(false);
                      throw interrupted;
                    }
                  });
      return consumed ? Retcode.SUCC : Retcode.PLAYER_DATA_ERROR;
    }
  }

  public static Retcode modify(
      Player player, DiceCombatCatalog catalog, int id, int die, Instant now) {
    synchronized (player) {
      var code = preflight(player, catalog, now);
      if (code != Retcode.SUCC) return code;
      var state = player.getGameplay().getDiceCombat();
      var owned = state.getAvatars().get(id);
      var row = catalog.avatars().get(id);
      if (owned == null
          || row == null
          || !player.getInventory().hasItem(id, 1)
          || row.counts().size() != 4
          || row.counts().get(3) <= 0
          || !"Colorful".equals(catalog.colors().get(die))) return Retcode.REQ_PARA_INVALID;
      var permission = GameData.getItemExcelMap().get(DiceProgressionRules.CHANGE_PERMISSION);
      var excel = GameData.getItemExcelMap().get(die);
      if (permission == null
          || permission.getItemMainType() != ItemMainType.Mission
          || excel == null
          || excel.getItemMainType() != ItemMainType.Material
          || excel.getItemSubType() != emu.lunarcore.game.enums.ItemSubType.DiceCombatDice)
        return Retcode.REWARD_CONFIG_NOT_EXIST;
      if (!player.getInventory().hasItem(DiceProgressionRules.CHANGE_PERMISSION, 1))
        return Retcode.ITEM_NOT_ENOUGH;
      if (die != row.initialSlots().get(3)
          && (!state.getDice().containsKey(die) || !player.getInventory().hasItem(die, 1)))
        return Retcode.ITEM_NOT_ENOUGH;
      var before = List.copyOf(owned.getDice());
      var replacement = new ArrayList<>(before);
      int copies = row.counts().get(3);
      for (int i = replacement.size() - copies; i < replacement.size(); i++)
        replacement.set(i, die);
      if (replacement.equals(before)) return Retcode.SUCC;
      owned.replaceDice(replacement);
      try {
        player.save();
      } catch (RuntimeException interrupted) {
        owned.replaceDice(before);
        throw interrupted;
      }
      return Retcode.SUCC;
    }
  }

  private DiceProgression() {}
}
