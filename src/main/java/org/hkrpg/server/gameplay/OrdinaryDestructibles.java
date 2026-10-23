package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.time.*;
import java.util.*;

/**
 * Space-station ordinary boxes. DropList identifiers are drop pools, NOT RewardData identifiers.
 * Until the server pool table is available, use an explicit local 100-credit/1-metal policy.
 * Refresh at UTC+8 04:00 on scene entry; this timing and the rewards are compatibility rules.
 */
public final class OrdinaryDestructibles {
  static Clock clock = Clock.systemUTC();

  static long day() {
    return ZonedDateTime.now(clock)
        .withZoneSameInstant(ZoneOffset.ofHours(8))
        .minusHours(4)
        .toLocalDate()
        .toEpochDay();
  }

  private static boolean world(Scene s) {
    return s != null
        && s.getDimensionId() == 0
        && s.getStoryLineId() == 0
        && s.getWorldId() == 101
        && (s.getPlaneType() == PlaneType.Town || s.getPlaneType() == PlaneType.Maze);
  }

  private static boolean family(PropInfo p) {
    return p != null && (p.getPropID() == 100026 || p.getPropID() == 100027);
  }

  private static PropInfo original(Scene s, int group, int instance) {
    if (!world(s) || s.getFloorInfo() == null) return null;
    var g = s.getFloorInfo().getGroups().get(group);
    if (g == null || g.getPropList() == null) return null;
    var rows = g.getPropList().stream().filter(p -> p.getID() == instance).toList();
    return rows.size() == 1 ? rows.getFirst() : null;
  }

  public static boolean applies(EntityProp p) {
    if (p == null || !world(p.getScene())) return false;
    var original = original(p.getScene(), p.getGroupId(), p.getInstId());
    // The four station areas have different event IDs for the same ordinary drop pools.
    // Route authored reward events through validation; malformed pools must not silently destroy.
    return family(p.getPropInfo()) && p.getPropInfo().getEventID() > 0
        || family(original) && original.getEventID() > 0;
  }

  private static boolean configured(Scene s, PropInfo p) {
    if (!world(s)
        || !family(p)
        || p.getEventID() <= 0
        || p.getID() <= 0
        || p.isIsDelete()
        || p.isIsClientOnly()
        || !p.isLoadOnInitial()
        || p.getState() != PropState.Closed
        || p.getMappingInfoID() != 0
        || p.getInitLevelGraph() != null && !p.getInitLevelGraph().isBlank()) return false;
    var excel = GameData.getPropExcelMap().get(p.getPropID());
    var e = GameData.getPlaneEventExcelMap().get(p.getEventID(), RaidDifficultySettings.world(s));
    if (excel == null
        || excel.getPropType() != PropType.PROP_DESTRUCT
        || excel.isRecoverHp()
        || excel.isRecoverMp()
        || e == null
        || e.getStageId() != 0
        || e.getReward() != 0
        || e.getDropList() == null
        || e.getDropList().size() != 2) return false;
    // Require exact source pool shapes; never substitute the unrelated RewardData 101001 bundle.
    return e.getDropList().toString().equals("[2000102,101001]");
  }

  private static String key(Scene s, int group, int instance) {
    return s.getPlaneId() + ":" + s.getFloorId() + ":" + group + ":" + instance;
  }

  public static boolean removed(Scene s, GroupInfo group, PropInfo info) {
    if (!world(s) || !family(info) || info.getEventID() <= 0) return false;
    var state = s.getPlayer().getGameplay().getDestructibleRewards();
    if (state == null || !state.valid()) return false;
    var r = state.getReceipts().get(key(s, group.getId(), info.getID()));
    return r != null && r.isGranted() && r.getDay() >= day();
  }

  public static boolean collect(EntityProp prop) {
    var s = prop.getScene();
    var player = s.getPlayer();
    synchronized (player) {
      var info = original(s, prop.getGroupId(), prop.getInstId());
      var group =
          s.getFloorInfo() == null ? null : s.getFloorInfo().getGroups().get(prop.getGroupId());
      if (!configured(s, info)
          || prop.getPropId() != info.getPropID()
          || prop.getPropType() != PropType.PROP_DESTRUCT
          || prop.getExcel() != GameData.getPropExcelMap().get(info.getPropID())
          || !Objects.equals(PropTimelines.source(info), PropTimelines.source(prop.getPropInfo()))
          || group == null
          || group.getLoadSide() != GroupInfo.GroupLoadSide.Server
          || GameData.getFloorInfo(s.getPlaneId(), s.getFloorId()) != s.getFloorInfo()
          || !s.getFloorInfo().isGroupInDimension(prop.getGroupId(), 0)
          || !s.getLoadedGroups().contains(prop.getGroupId())
          || !player.getMissionManager().shouldLoadGroup(group, s)
          || player.getScene() != s
          || player.getPlaneId() != s.getPlaneId()
          || player.getFloorId() != s.getFloorId()
          || player.getEntryId() != s.getEntryId()
          || player.getGameplay().getStoryLines().isTransitionPending()
          || player.getBattle() != null
          || !s.canDestroyProp(prop)) return false;
      var old = player.getGameplay().getDestructibleRewards();
      if (old == null
          || !old.valid()
          || old.getReceipts().values().stream().anyMatch(r -> !r.isGranted())) return false;
      String key = key(s, prop.getGroupId(), prop.getInstId());
      long day = day();
      var previous = old.getReceipts().get(key);
      if (previous != null && previous.getDay() >= day) return false;
      var coin = GameData.getItemExcelMap().get(2);
      var metal = GameData.getItemExcelMap().get(180005);
      var inventory = player.getInventory();
      if (coin == null
          || coin.getItemMainType() != ItemMainType.Virtual
          || metal == null
          || metal.getItemMainType() != ItemMainType.Material
          || metal.getItemSubType() != ItemSubType.ComposeMaterial
          || metal.getPileLimit() <= 0
          || player.getScoin() < 0
          || (long) player.getScoin() + 100 > Math.min(coin.getPileLimit(), Integer.MAX_VALUE))
        return false;
      var held = inventory.getMaterialByItemId(180005);
      var tab = inventory.getTabByItemType(metal.getItemMainType());
      if (tab == null
          || held == null
              && (tab.getSize() >= tab.getMaxCapacity() || !inventory.hasItemIdCapacity(1))
          || held != null
              && (held.getCount() <= 0
                  || held.getCount() >= metal.getPileLimit()
                  || held.getOwnerUid() != player.getUid()
                  || held.isDiscarded()
                  || held.getExcel() != metal
                  || held.getEquipAvatarId() != null
                  || held.getEquipAvatarExcelId() != 0
                  || held.getInternalUid() <= 0
                  || inventory.getItemByUid(held.getInternalUid()) != held)) return false;
      String source = PropTimelines.source(info);
      var pending = old.with(key, new DestructibleRewardState.Receipt(day, source, false));
      player.getGameplay().destructibleRewards(pending);
      try {
        player.save();
      } catch (RuntimeException failed) {
        player.getGameplay().destructibleRewards(old);
        return false;
      }
      var rewards = new ItemParamMap();
      rewards.add(2, 100);
      rewards.add(180005, 1);
      try {
        var delivered = inventory.grantItems(rewards, true);
        if (delivered == null || !delivered.complete()) return false;
        player
            .getGameplay()
            .destructibleRewards(
                pending.with(key, new DestructibleRewardState.Receipt(day, source, true)));
        try {
          player.save();
        } catch (RuntimeException failed) {
          player.getGameplay().destructibleRewards(pending);
          return false;
        }
        return true;
      } catch (RuntimeException failed) {
        return false;
      }
    }
  }

  private OrdinaryDestructibles() {}
}
