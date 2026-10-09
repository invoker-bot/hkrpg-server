package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.inventory.tabs.InventoryTabType;
import emu.lunarcore.game.mail.Mail;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.JsonUtils;
import java.util.*;

/** Validates the whole attachment list before reserving and verifies actual delivery afterwards. */
public final class MailAttachments {
  public record Claim(Retcode retcode, List<GameItem> items) {}

  public record Batch(Retcode retcode, List<Mail> mails, List<GameItem> items) {}

  private record Plan(
      List<GameItem> items,
      Map<Integer, Integer> expected,
      Map<Integer, Integer> before,
      Set<Integer> oldUids) {}

  public static Claim claim(Player player, Mail mail) {
    synchronized (player) {
      if (mail == null || mail.getOwnerUid() != player.getUid())
        return fail(Retcode.MAIL_NOT_EXIST);
      var receipt = mail.getAttachmentClaim();
      if (receipt != null && receipt.getPhase() == null) return fail(Retcode.PLAYER_DATA_ERROR);
      if (receipt != null && receipt.getPhase() == MailAttachmentClaim.Phase.PENDING)
        return fail(Retcode.SYSTEM_BUSY);
      if (mail.hasClaimedAttachments()
          || mail.getAttachments() == null
          || mail.getAttachments().isEmpty()) return fail(Retcode.MAIL_NO_MAIL_TAKE_ATTACHMENT);
      if (mail.getExpiry() <= System.currentTimeMillis() / 1000)
        return fail(Retcode.MAIL_NOT_EXIST);
      var plan = prepare(player, mail);
      if (plan == null) return fail(Retcode.MAIL_ATTACHEMENT_INVALID);
      mail.reserveAttachments(plan.expected(), plan.before());
      List<GameItem> delivered;
      boolean failed = false;
      try {
        delivered = player.getInventory().addItems(plan.items(), false);
      } catch (RuntimeException interrupted) {
        delivered = List.of();
        failed = true;
      }
      var returned = new TreeMap<Integer, Integer>();
      if (delivered == null) {
        delivered = List.of();
        failed = true;
      }
      for (var item : delivered) {
        if (item == null || item.getCount() <= 0) {
          failed = true;
          continue;
        }
        try {
          returned.merge(item.getItemId(), item.getCount(), Math::addExact);
        } catch (ArithmeticException invalid) {
          failed = true;
        }
      }
      var observed = new TreeMap<Integer, Integer>();
      var uniqueIds = new ArrayList<Integer>();
      for (var entry : plan.expected().entrySet()) {
        int id = entry.getKey();
        var excel = GameData.getItemExcelMap().get(id);
        if (excel == null) {
          failed = true;
          continue;
        }
        long amount;
        if (excel.isEquippable()) {
          amount = 0;
          for (var item : player.getInventory().getItems().values()) {
            if (!plan.oldUids().contains(item.getInternalUid())
                && item.getItemId() == id
                && owned(player, item)
                && item.getExcel() == excel
                && item.getCount() == 1) {
              amount++;
              uniqueIds.add(item.getInternalUid());
            }
          }
        } else if (excel.getItemMainType() == ItemMainType.AvatarCard) {
          var avatar = player.getAvatarById(id);
          amount = avatar != null && avatar.getOwnerUid() == player.getUid() ? 1 : 0;
        } else {
          int current = balance(player, id);
          amount = current < 0 ? -1 : (long) current - plan.before().get(id);
        }
        if (amount < 0 || amount > Integer.MAX_VALUE) {
          failed = true;
          continue;
        }
        if (amount > 0) observed.put(id, (int) amount);
      }
      boolean complete =
          !failed && returned.equals(plan.expected()) && observed.equals(plan.expected());
      // Virtual currencies live in Player, while inventory rows and the mail have separate
      // documents.
      // Persist the observed wallet before publishing a claimed mail; an interrupted save stays
      // pending.
      player.save();
      mail.recordAttachmentDelivery(observed, uniqueIds, complete);
      return complete
          ? new Claim(Retcode.SUCC, List.copyOf(delivered))
          : fail(Retcode.PLAYER_DATA_ERROR);
    }
  }

  private static Plan prepare(Player player, Mail mail) {
    var inventory = player.getInventory();
    var expected = new TreeMap<Integer, Integer>();
    var before = new TreeMap<Integer, Integer>();
    var items = new ArrayList<GameItem>();
    var identities = Collections.newSetFromMap(new IdentityHashMap<GameItem, Boolean>());
    var tabs = new EnumMap<InventoryTabType, Integer>(InventoryTabType.class);
    int allocations = 0;
    if (mail.getId() == null || mail.getAttachments().size() > 500) return null;
    for (var item : mail.getAttachments()) {
      if (item == null
          || !identities.add(item)
          || item.getCount() <= 0
          || item.getOwnerUid() != 0
          || item.getInternalUid() != 0
          || item.isEquipped()) return null;
      var excel = GameData.getItemExcelMap().get(item.getItemId());
      if (excel == null
          || excel.getItemMainType() == null
          || excel.getPileLimit() <= 0
          || item.getExcel() != null && item.getExcel() != excel) return null;
      int id = item.getItemId(), amount = item.getCount();
      try {
        expected.merge(id, amount, Math::addExact);
      } catch (ArithmeticException invalid) {
        return null;
      }
      if (excel.isEquippable()) {
        if (amount != 1) return null;
        tabs.merge(excel.getItemMainType().getTabType(), 1, Math::addExact);
        allocations++;
      } else if (excel.getItemMainType() == ItemMainType.AvatarCard) {
        if (amount != 1
            || expected.get(id) != 1
            || player.getAvatars().hasAvatar(id)
            || GameData.getAvatarExcelMap().get(id) == null) return null;
      } else {
        if (!Inventory.isStackType(excel)
            && !(excel.getItemMainType() == ItemMainType.Virtual && Set.of(1, 2, 3).contains(id)))
          return null;
        int current = balance(player, id);
        if (current < 0 || (long) current + expected.get(id) > excel.getPileLimit()) return null;
        before.put(id, current);
        if (Inventory.isStackType(excel)
            && inventory.getMaterialByItemId(id) == null
            && expected.get(id) == amount) {
          tabs.merge(InventoryTabType.MATERIAL, 1, Math::addExact);
          allocations++;
        }
      }
      // Preserve supplied gear attributes; detach database identity and never mutate the mail's
      // copy.
      var raw = JsonUtils.getGsonFactory().toJsonTree(item, GameItem.class).getAsJsonObject();
      raw.remove("id");
      var copy = JsonUtils.getGsonFactory().fromJson(raw, GameItem.class);
      copy.setExcel(excel);
      items.add(copy);
    }
    for (var entry : tabs.entrySet()) {
      var tab = inventory.getTab(entry.getKey());
      if (tab == null || tab.getAvailableCapacity() < entry.getValue()) return null;
    }
    if (allocations > 0 && !inventory.hasItemIdCapacity(allocations)) return null;
    return new Plan(
        List.copyOf(items), expected, before, Set.copyOf(inventory.getItems().keySet()));
  }

  private static int balance(Player player, int id) {
    var excel = GameData.getItemExcelMap().get(id);
    if (excel == null) return -1;
    if (excel.getItemMainType() == ItemMainType.Virtual)
      return switch (id) {
        case 1 -> player.getHcoin();
        case 2 -> player.getScoin();
        case 3 -> player.getMcoin();
        default -> -1;
      };
    var item = player.getInventory().getMaterialByItemId(id);
    return item == null
        ? 0
        : owned(player, item)
                && item.getItemId() == id
                && item.getExcel() == excel
                && item.getCount() >= 0
            ? item.getCount()
            : -1;
  }

  private static boolean owned(Player player, GameItem item) {
    return item != null
        && item.getOwnerUid() == player.getUid()
        && item.getInternalUid() > 0
        && player.getInventory().getItemByUid(item.getInternalUid()) == item;
  }

  private static Claim fail(Retcode code) {
    return new Claim(code, List.of());
  }

  private MailAttachments() {}
}
