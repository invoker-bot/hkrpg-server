package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemSubType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.time.Instant;
import org.hkrpg.server.GameDiagnostics;

/** Personal display wall; the Train Party board, construction and rewards are separate. */
public final class PlayerRoomDisplay {
  public record Result(Retcode code, String reason, PlayerRoomDisplayState state) {}
  public record View(boolean available, boolean damaged, PlayerRoomDisplayState state,
      List<Integer> ownedItems) {}

  public static View view(Player player) {
    synchronized (player) {
      if (player.getLevel() < 21) return new View(false, false, null, List.of());
      try {
        var catalog = PlayerRoomCatalog.load();
        var stored = player.getGameplay().getPlayerRoomDisplay();
        var state = stored == null ? new PlayerRoomDisplayState() : stored;
        BadgeCatalog badges = needsBadgeCatalog(state) ? BadgeCatalog.load() : null;
        if (!validSaved(catalog, badges, state)) return new View(false, true, null, List.of());
        if (badges != null) {
          var owned = badges.owned(player);
          if (!state.isBadgeAutoFillClosed()) {
            var filled = autoFill(state, owned, false);
            var result = persist(player, stored, filled);
            if (result.code() == Retcode.SUCC) state = result.state();
          }
          state = visibleBadges(state, owned);
        }
        var ownedItems = catalog.items.keySet().stream().filter(id -> ownsItem(player, id)).sorted().toList();
        return new View(true, false, state, ownedItems);
      } catch (Exception unavailable) {
        return new View(false, true, null, List.of());
      }
    }
  }

  private static boolean needsBadgeCatalog(PlayerRoomDisplayState state) {
    return state.valid() && (!state.getBadges().isEmpty() || !state.getAutoSeenBadges().isEmpty()
        || !state.isBadgeAutoFillClosed());
  }

  private static boolean validSaved(PlayerRoomCatalog catalog, BadgeCatalog badges,
      PlayerRoomDisplayState state) {
    if (!state.valid()) return false;
    for (var item : state.getItems().entrySet())
      if (!catalog.itemFits(item.getKey(), item.getValue())) return false;
    for (var photo : state.getPictures().entrySet()) {
      if (!catalog.pictureFits(photo.getKey(), photo.getValue().getType())) return false;
      if (photo.getValue().getType() == 4
          && !catalog.storyMissions.containsKey(photo.getValue().getId())) return false;
    }
    if (needsBadgeCatalog(state) && badges == null) return false;
    for (var entry : state.getBadges().entrySet()) {
      int slot = entry.getKey();
      var tags = catalog.slots.get(slot);
      if (slot < 1600106 || slot > 1600114 || tags == null || !tags.contains(7)
          || !badges.contains(entry.getValue().getId())) return false;
    }
    for (int id : state.getAutoSeenBadges().keySet()) if (!badges.contains(id)) return false;
    return true;
  }

  public static Result replace(Player player, Map<Integer, Integer> items,
      Map<Integer, PlayerRoomDisplayState.Picture> pictures,
      Map<Integer, PlayerRoomDisplayState.Badge> badgeItems) {
    synchronized (player) {
      var gate = editingGate(player);
      if (gate != null) return gate;
      if (items == null || pictures == null || badgeItems == null)
        return fail(Retcode.REQ_PARA_INVALID, "invalid-display");
      var old = player.getGameplay().getPlayerRoomDisplay();
      var state = old == null ? new PlayerRoomDisplayState() : old;
      PlayerRoomCatalog catalog;
      BadgeCatalog badges;
      try {
        catalog = PlayerRoomCatalog.load();
        badges = needsBadgeCatalog(state) || !badgeItems.isEmpty() ? BadgeCatalog.load() : null;
      } catch (Exception unavailable) { return fail(Retcode.PLAYER_DATA_ERROR, "source-unavailable"); }
      if (!validSaved(catalog, badges, state)) return fail(Retcode.PLAYER_DATA_ERROR, "saved-display-invalid");
      var next = new PlayerRoomDisplayState(items, pictures, badgeItems,
          state.isBadgeAutoFillClosed(), state.getAutoSeenBadges());
      if (!validSaved(catalog, badges, next)) return fail(Retcode.REQ_PARA_INVALID, "invalid-slot-or-item");
      for (var item : next.getItems().entrySet())
        if (!ownsItem(player, item.getValue())) return fail(Retcode.REQ_PARA_INVALID, "item-not-owned");
      for (var photo : next.getPictures().values())
        if (!ownsPicture(player, catalog, photo)) return fail(Retcode.REQ_PARA_INVALID, "picture-not-unlocked");
      if (badges != null) {
        var owned = badges.owned(player);
        for (var badge : next.getBadges().values())
          if (!Objects.equals(owned.get(badge.getId()), badge.getExpireTime()))
            return fail(Retcode.REQ_PARA_INVALID, "badge-not-owned-or-expired");
      }
      return persist(player, old, next);
    }
  }

  public static Result setBadgeAutoFillClosed(Player player, boolean closed) {
    synchronized (player) {
      var gate = editingGate(player);
      if (gate != null) return gate;
      PlayerRoomCatalog catalog;
      BadgeCatalog badges;
      try { catalog = PlayerRoomCatalog.load(); badges = BadgeCatalog.load(); }
      catch (Exception unavailable) { return fail(Retcode.PLAYER_DATA_ERROR, "source-unavailable"); }
      var old = player.getGameplay().getPlayerRoomDisplay();
      var state = old == null ? new PlayerRoomDisplayState() : old;
      if (!validSaved(catalog, badges, state)) return fail(Retcode.PLAYER_DATA_ERROR, "saved-display-invalid");
      var next = new PlayerRoomDisplayState(state.getItems(), state.getPictures(), state.getBadges(),
          closed, state.getAutoSeenBadges());
      if (!closed) next = autoFill(next, badges.owned(player), state.isBadgeAutoFillClosed());
      return persist(player, old, next);
    }
  }

  /** Called only after an actual badge item was saved; a failed display save never revokes the item. */
  public static Result badgeAcquired(Player player) {
    synchronized (player) {
      var old = player.getGameplay().getPlayerRoomDisplay();
      if (player.getLevel() < 21 || old == null) return new Result(Retcode.SUCC, "unchanged", old);
      if (!old.valid()) return fail(Retcode.PLAYER_DATA_ERROR, "saved-display-invalid");
      if (old.isBadgeAutoFillClosed()) return new Result(Retcode.SUCC, "unchanged", old);
      try {
        var catalog = PlayerRoomCatalog.load();
        var badges = BadgeCatalog.load();
        if (!validSaved(catalog, badges, old)) return fail(Retcode.PLAYER_DATA_ERROR, "saved-display-invalid");
        return persist(player, old, autoFill(old, badges.owned(player), false));
      } catch (Exception unavailable) {
        return fail(Retcode.PLAYER_DATA_ERROR, "source-unavailable");
      }
    }
  }

  private static PlayerRoomDisplayState autoFill(PlayerRoomDisplayState state,
      Map<Integer, Long> owned, boolean includeExisting) {
    var placed = new TreeMap<>(state.getBadges());
    var seen = new TreeMap<>(state.getAutoSeenBadges());
    var present = new HashSet<Integer>();
    long now = Instant.now().getEpochSecond();
    placed.entrySet().removeIf(e -> e.getValue().getExpireTime() < BadgeCatalog.PERMANENT
        && e.getValue().getExpireTime() <= now);
    for (var badge : placed.values()) present.add(badge.getId());
    for (var entry : owned.entrySet()) {
      int id = entry.getKey();
      boolean newlyOwned = !Objects.equals(seen.get(id), entry.getValue());
      if (newlyOwned && present.contains(id)) {
        for (var placement : placed.entrySet()) {
          if (placement.getValue().getId() == id)
            placement.setValue(new PlayerRoomDisplayState.Badge(id, entry.getValue()));
        }
      }
      if ((includeExisting || newlyOwned) && !present.contains(id)) {
        for (int slot = 1600106; slot <= 1600114; slot++) {
          if (!placed.containsKey(slot)) {
            placed.put(slot, new PlayerRoomDisplayState.Badge(id, entry.getValue()));
            present.add(id);
            break;
          }
        }
      }
      seen.put(id, entry.getValue());
    }
    return new PlayerRoomDisplayState(state.getItems(), state.getPictures(), placed,
        state.isBadgeAutoFillClosed(), seen);
  }

  private static PlayerRoomDisplayState visibleBadges(PlayerRoomDisplayState state, Map<Integer, Long> owned) {
    var visible = new TreeMap<Integer, PlayerRoomDisplayState.Badge>();
    state.getBadges().forEach((slot, badge) -> {
      if (Objects.equals(owned.get(badge.getId()), badge.getExpireTime())) visible.put(slot, badge);
    });
    return new PlayerRoomDisplayState(state.getItems(), state.getPictures(), visible,
        state.isBadgeAutoFillClosed(), state.getAutoSeenBadges());
  }

  private static Result editingGate(Player player) {
    if (player.getLevel() < 21) return fail(Retcode.PLAYER_LEVEL_NOT_MEET, "level");
    var scene = player.getScene();
    if (scene == null || scene.getPlaneId() != 10000 || scene.getFloorId() != 10000003
        || scene.getDimensionId() != 0 || scene.getStoryLineId() != 0)
      return fail(Retcode.NOT_IN_SCENE, "room-scene");
    if (player.getBattle() != null || player.getInstance() != null)
      return fail(Retcode.SYSTEM_BUSY, "active-instance");
    return null;
  }

  private static Result persist(Player player, PlayerRoomDisplayState old, PlayerRoomDisplayState next) {
    if (old == null && next.getItems().isEmpty() && next.getPictures().isEmpty()
        && next.getBadges().isEmpty() && next.isBadgeAutoFillClosed() && next.getAutoSeenBadges().isEmpty())
      return new Result(Retcode.SUCC, "unchanged", next);
    if (old != null && old.getItems().equals(next.getItems()) && old.getPictures().equals(next.getPictures())
        && old.getBadges().equals(next.getBadges()) && old.getAutoSeenBadges().equals(next.getAutoSeenBadges())
        && old.isBadgeAutoFillClosed() == next.isBadgeAutoFillClosed())
      return new Result(Retcode.SUCC, "unchanged", old);
    player.getGameplay().playerRoomDisplay(next);
    try { player.save(); }
    catch (RuntimeException failed) {
      player.getGameplay().playerRoomDisplay(old);
      GameDiagnostics.saveFailed(player, "player-room-display", 0, 0, failed);
      return fail(Retcode.SERVER_INTERNAL_ERROR, "save-failed");
    }
    return new Result(Retcode.SUCC, "saved", next);
  }

  private static boolean ownsItem(Player player, int id) {
    var inventory = player.getInventory();
    var item = inventory.getMaterialByItemId(id);
    var definition = GameData.getItemExcelMap().get(id);
    return item != null && definition != null && item.getExcel() == definition
        && definition.getItemSubType() == ItemSubType.TrainPartyDiyMaterial
        && item.getOwnerUid() == player.getUid() && player.getUid() > 0
        && item.getInternalUid() > 0 && inventory.getItemByUid(item.getInternalUid()) == item
        && item.getCount() > 0 && item.getCount() <= definition.getPileLimit();
  }

  private static boolean ownsPicture(Player player, PlayerRoomCatalog catalog,
      PlayerRoomDisplayState.Picture picture) {
    int id = picture.getId();
    if (picture.getType() == 1) {
      var avatar = player.getAvatars().getAvatars().get(id);
      return avatar != null && avatar.getAvatarId() == id && avatar.getOwner() == player
          && player.getUid() > 0 && avatar.getOwnerUid() == player.getUid();
    }
    if (picture.getType() == 3) {
      for (var item : player.getInventory().getItems().values()) {
        if (item == null || item.getItemId() != id || item.getOwnerUid() != player.getUid()
            || player.getUid() <= 0 || item.getCount() != 1 || item.getInternalUid() <= 0
            || player.getInventory().getItemByUid(item.getInternalUid()) != item) continue;
        var definition = GameData.getItemExcelMap().get(id);
        if (definition != null && item.getExcel() == definition && definition.isEquipment()
            && definition.getEquipmentExcel().getId() == id) return true;
      }
      return false;
    }
    Integer mission = catalog.storyMissions.get(id);
    return picture.getType() == 4 && mission != null && MissionStateRecords.mainCompleted(player, mission);
  }

  private static Result fail(Retcode code, String reason) { return new Result(code, reason, null); }
  private PlayerRoomDisplay() {}
}
