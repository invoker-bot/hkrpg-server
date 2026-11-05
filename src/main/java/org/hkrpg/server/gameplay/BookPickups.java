package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Standard fixed reading props: authored book rewards, with durable once-per-location delivery. */
public final class BookPickups {
  public static final int INTERACT = 118191;
  private static final String GRAPH =
      "Config/Level/Props/Common/InitLevelGraph_Prop_Common_PickupReadings.json";

  public record Result(Retcode retcode, EntityProp prop) {}

  private record Loot(int reward, List<Integer> books) {}

  private static boolean shape(PropInfo info) {
    return info != null
        && !info.isIsDelete()
        && !info.isIsClientOnly()
        && info.isLoadOnInitial()
        && info.getID() > 0
        && info.getEventID() > 0
        && info.getState() == PropState.EventClose
        && GRAPH.equals(info.getInitLevelGraph());
  }

  private static Loot loot(PropInfo info, int world) {
    if (!shape(info) || world < 0) return null;
    var prop = GameData.getPropExcelMap().get(info.getPropID());
    var event = GameData.getPlaneEventExcelMap().get(info.getEventID(), world);
    if (prop == null
        || prop.getPropType() != PropType.PROP_LIGHT
        || !prop.getPropStateList().containsAll(List.of(PropState.EventClose, PropState.EventOpen))
        || event == null
        || event.getStageId() != 0
        || event.getDropList() == null
        || !event.getDropList().isEmpty()) return null;
    var reward = GameData.getRewardExcelMap().get(event.getReward());
    if (reward == null
        || reward.getRewards() == null
        || reward.getRewards().isEmpty()
        || reward.getRewards().size() > 6) return null;
    for (var entry : reward.getRewards().int2IntEntrySet()) {
      if (entry.getIntValue() != 1
          || !BookItems.storable(GameData.getItemExcelMap().get(entry.getIntKey()))) return null;
    }
    return new Loot(
        reward.getId(), reward.getRewards().keySet().intStream().sorted().boxed().toList());
  }

  /** Some reading atmosphere groups still declare their prerequisites as legacy mission clauses. */
  public static boolean allowsGroup(Scene scene, GroupInfo group) {
    if (!world(scene)
        || group == null
        || group.getCategory() != GroupInfo.GroupCategory.Atmosphere
        || group.getPropList() == null
        || group.getPropList().stream().noneMatch(BookPickups::shape)) return true;
    return (!MissionGroupConditions.declaresCondition(group.getLoadCondition())
            || MissionGroupConditions.matches(scene.getPlayer(), group.getLoadCondition()))
        && (!MissionGroupConditions.declaresCondition(group.getUnloadCondition())
            || MissionGroupConditions.evaluate(scene.getPlayer(), group.getUnloadCondition())
                == MissionGroupConditions.Result.NO_MATCH);
  }

  public static boolean supportedGroup(Scene scene, GroupInfo group) {
    return world(scene)
        && group.getPropList() != null
        && group.getPropList().stream()
            .anyMatch(info -> loot(info, RaidDifficultySettings.world(scene)) != null);
  }

  public static boolean supported(Scene scene, PropInfo info) {
    return world(scene) && loot(info, RaidDifficultySettings.world(scene)) != null;
  }

  private static boolean world(Scene scene) {
    return scene != null
        && scene.getDimensionId() == 0
        && scene.getStoryLineId() == 0
        && (scene.getPlaneType() == PlaneType.Town || scene.getPlaneType() == PlaneType.Maze);
  }

  public static boolean applies(EntityProp prop) {
    return prop != null && supported(prop.getScene(), prop.getPropInfo());
  }

  private static String key(EntityProp prop) {
    var scene = prop.getScene();
    return scene.getPlaneId()
        + ":"
        + scene.getFloorId()
        + ":"
        + prop.getGroupId()
        + ":"
        + prop.getInstId();
  }

  private static PropInfo original(EntityProp prop) {
    var scene = prop.getScene();
    var floor = GameData.getFloorInfo(scene.getPlaneId(), scene.getFloorId());
    if (!world(scene)
        || floor == null
        || floor != scene.getFloorInfo()
        || !floor.isGroupInDimension(prop.getGroupId(), 0)) return null;
    var group = floor.getGroups().get(prop.getGroupId());
    if (group == null
        || group.getId() != prop.getGroupId()
        || group.getLoadSide() != GroupInfo.GroupLoadSide.Server
        || group.getPropList() == null
        || !scene.getLoadedGroups().contains(group.getId())
        || !scene.getPlayer().getMissionManager().shouldLoadGroup(group, scene)) return null;
    var matches = group.getPropList().stream().filter(i -> i.getID() == prop.getInstId()).toList();
    if (matches.size() != 1) return null;
    var info = matches.getFirst();
    return supported(scene, info)
            && prop.getPropId() == info.getPropID()
            && prop.getExcel() == GameData.getPropExcelMap().get(info.getPropID())
            && Objects.equals(PropTimelines.source(info), PropTimelines.source(prop.getPropInfo()))
        ? info
        : null;
  }

  public static Retcode validate(Player player, int config, EntityProp prop) {
    if (config != INTERACT || !applies(prop)) return Retcode.INTERACT_TYPE_INVALID;
    var scene = player.getScene();
    if (scene != prop.getScene()
        || scene.getPlayer() != player
        || scene.getPlaneId() != player.getPlaneId()
        || scene.getFloorId() != player.getFloorId()
        || scene.getEntryId() != player.getEntryId()
        || player.getGameplay().getStoryLines().isTransitionPending()
        || original(prop) == null) return Retcode.MISSION_SCENE_NOT_MATCH;
    if (player.getPos() == null) return Retcode.REQ_PARA_INVALID;
    // Cast before subtraction; malformed extreme coordinates must not overflow into range.
    double dx = (double) player.getPos().getX() - prop.getPos().getX();
    double dy = (double) player.getPos().getY() - prop.getPos().getY();
    double dz = (double) player.getPos().getZ() - prop.getPos().getZ();
    if (dx * dx + dy * dy + dz * dz > 25_000_000) return Retcode.REQ_PARA_INVALID;
    var state = player.getGameplay().getBookPickups();
    if (!state.valid()) return Retcode.PLAYER_DATA_ERROR;
    if (state.getReceipts().values().stream().anyMatch(r -> !r.isGranted()))
      return Retcode.SYSTEM_BUSY;
    if (state.getReceipts().containsKey(key(prop))) return Retcode.QUEST_REWARD_ALREADY_TAKEN;
    var loot = loot(prop.getPropInfo(), RaidDifficultySettings.world(scene));
    var inventory = player.getInventory();
    int newStacks = 0;
    var tab = inventory.getTabByItemType(ItemMainType.Usable);
    if (tab == null) return Retcode.ITEM_EXCEED_LIMIT;
    for (int book : loot.books()) {
      var definition = GameData.getItemExcelMap().get(book);
      var item = inventory.getMaterialByItemId(book);
      if (item != null
          && (item.getOwnerUid() != player.getUid()
              || item.isDiscarded()
              || item.getEquipAvatarId() != null
              || item.getEquipAvatarExcelId() != 0
              || item.getExcel() != definition
              || item.getInternalUid() <= 0
              || inventory.getItemByUid(item.getInternalUid()) != item
              || item.getCount() <= 0
              || item.getCount() > definition.getPileLimit())) return Retcode.PLAYER_DATA_ERROR;
      if (item == null) newStacks++;
      else if (item.getCount() == definition.getPileLimit()) return Retcode.ITEM_EXCEED_LIMIT;
    }
    if ((long) tab.getSize() + newStacks > tab.getMaxCapacity()) return Retcode.ITEM_EXCEED_LIMIT;
    return Retcode.SUCC;
  }

  public static Result pickUp(Player player, int config, int entity) {
    synchronized (player) {
      var code = SceneInteractions.validateNative(player, config, entity);
      if (code != Retcode.SUCC) return new Result(code, null);
      var prop = (EntityProp) player.getScene().getEntityById(entity);
      if (!applies(prop)) return new Result(Retcode.INTERACT_TYPE_INVALID, null);
      var old = player.getGameplay().getBookPickups();
      var loot = loot(prop.getPropInfo(), RaidDifficultySettings.world(prop.getScene()));
      var receipt =
          new BookPickupState.Receipt(
              PropTimelines.source(prop.getPropInfo()), loot.reward(), loot.books(), false);
      var prepared = old.with(key(prop), receipt);
      player.getGameplay().bookPickups(prepared);
      try {
        player.save();
      } catch (RuntimeException failed) {
        player.getGameplay().bookPickups(old);
        return new Result(Retcode.SYSTEM_BUSY, null);
      }
      var items = new ItemParamMap();
      for (int book : loot.books()) items.add(book, 1);
      try {
        var delivered = player.getInventory().grantItems(items, true);
        if (delivered == null || !delivered.complete())
          return new Result(Retcode.PLAYER_DATA_ERROR, null);
      } catch (RuntimeException failed) {
        return new Result(Retcode.SYSTEM_BUSY, null);
      }
      var completed =
          prepared.with(
              key(prop),
              new BookPickupState.Receipt(receipt.getSource(), loot.reward(), loot.books(), true));
      player.getGameplay().bookPickups(completed);
      try {
        player.save();
      } catch (RuntimeException failed) {
        player.getGameplay().bookPickups(prepared);
        return new Result(Retcode.SYSTEM_BUSY, null);
      }
      prop.setState(PropState.EventOpen);
      player
          .getMissionManager()
          .onPropInteraction(
              player.getFloorId(),
              prop.getGroupId(),
              prop.getInstId(),
              PropState.EventOpen.getVal());
      return new Result(Retcode.SUCC, prop);
    }
  }

  public static void restore(EntityProp prop) {
    if (!applies(prop)) return;
    var state = prop.getScene().getPlayer().getGameplay().getBookPickups();
    if (!state.valid()) return;
    var receipt = state.getReceipts().get(key(prop));
    if (receipt == null || !receipt.isGranted()) return;
    var loot = loot(prop.getPropInfo(), RaidDifficultySettings.world(prop.getScene()));
    if (receipt.getSource().equals(PropTimelines.source(prop.getPropInfo()))
        && receipt.getReward() == loot.reward()
        && receipt.books().equals(loot.books())) prop.setState(PropState.EventOpen, false);
  }

  private BookPickups() {}
}
