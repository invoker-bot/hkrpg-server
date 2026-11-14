package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemSubType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
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
        var state = player.getGameplay().getPlayerRoomDisplay();
        if (state == null) state = new PlayerRoomDisplayState();
        if (!validSaved(catalog, state)) return new View(false, true, null, List.of());
        var owned = catalog.items.keySet().stream().filter(id -> ownsItem(player, id)).sorted().toList();
        return new View(true, false, state, owned);
      } catch (Exception unavailable) {
        return new View(false, true, null, List.of());
      }
    }
  }

  private static boolean validSaved(PlayerRoomCatalog catalog, PlayerRoomDisplayState state) {
    if (!state.valid()) return false;
    for (var item : state.getItems().entrySet())
      if (!catalog.itemFits(item.getKey(), item.getValue())) return false;
    for (var photo : state.getPictures().entrySet()) {
      if (!catalog.pictureFits(photo.getKey(), photo.getValue().getType())) return false;
      if (photo.getValue().getType() == 4
          && !catalog.storyMissions.containsKey(photo.getValue().getId())) return false;
    }
    return true;
  }

  public static Result replace(Player player, Map<Integer, Integer> items,
      Map<Integer, PlayerRoomDisplayState.Picture> pictures) {
    synchronized (player) {
      if (player.getLevel() < 21) return fail(Retcode.PLAYER_LEVEL_NOT_MEET, "level");
      var scene = player.getScene();
      if (scene == null || scene.getPlaneId() != 10000 || scene.getFloorId() != 10000003
          || scene.getDimensionId() != 0 || scene.getStoryLineId() != 0)
        return fail(Retcode.NOT_IN_SCENE, "room-scene");
      if (player.getBattle() != null || player.getInstance() != null)
        return fail(Retcode.SYSTEM_BUSY, "active-instance");
      PlayerRoomCatalog catalog;
      try { catalog = PlayerRoomCatalog.load(); }
      catch (Exception unavailable) { return fail(Retcode.PLAYER_DATA_ERROR, "source-unavailable"); }
      var old = player.getGameplay().getPlayerRoomDisplay();
      if (old != null && !validSaved(catalog, old))
        return fail(Retcode.PLAYER_DATA_ERROR, "saved-display-invalid");
      if (items == null || pictures == null) return fail(Retcode.REQ_PARA_INVALID, "invalid-display");
      var next = new PlayerRoomDisplayState(items, pictures);
      if (!validSaved(catalog, next)) return fail(Retcode.REQ_PARA_INVALID, "invalid-slot-or-item");
      for (var item : next.getItems().entrySet()) {
        if (!ownsItem(player, item.getValue()))
          return fail(Retcode.REQ_PARA_INVALID, "item-not-owned");
      }
      for (var photo : next.getPictures().values()) {
        if (!ownsPicture(player, catalog, photo))
          return fail(Retcode.REQ_PARA_INVALID, "picture-not-unlocked");
      }
      if (old == null && next.getItems().isEmpty() && next.getPictures().isEmpty())
        return new Result(Retcode.SUCC, "unchanged", next);
      if (old != null && old.getItems().equals(next.getItems())
          && old.getPictures().equals(next.getPictures()))
        return new Result(Retcode.SUCC, "unchanged", old);
      player.getGameplay().playerRoomDisplay(next);
      try {
        player.save();
      } catch (RuntimeException failed) {
        player.getGameplay().playerRoomDisplay(old);
        GameDiagnostics.saveFailed(player, "player-room-display", 0, 0, failed);
        return fail(Retcode.SERVER_INTERNAL_ERROR, "save-failed");
      }
      return new Result(Retcode.SUCC, "saved", next);
    }
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
