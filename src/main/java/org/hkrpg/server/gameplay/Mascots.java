package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.SceneGroup;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.protocol.Story46;

/** Only the pinned catch groups may consume the client group-state command. */
public final class Mascots {
  private static MascotSources.Control control(Scene scene, int group) {
    if (scene == null) return null;
    return MascotSources.CONTROLS.values().stream()
        .filter(
            c ->
                c.plane() == scene.getPlaneId()
                    && c.floor() == scene.getFloorId()
                    && c.group() == group)
        .findFirst()
        .orElse(null);
  }

  private static MascotSources.Control reward(Scene scene, int group) {
    if (scene == null) return null;
    return MascotSources.CONTROLS.values().stream()
        .filter(
            c ->
                c.plane() == scene.getPlaneId()
                    && c.floor() == scene.getFloorId()
                    && c.rewardGroup() == group)
        .findFirst()
        .orElse(null);
  }

  public static boolean applies(Scene scene, int group) {
    return control(scene, group) != null;
  }

  private static EntityProp actor(Player p, WorldChestRules rules, MascotSources.Control c) {
    var scene = p.getScene();
    if (scene == null
        || scene.getPlayer() != p
        || p.getBattle() != null
        || p.getInstance() != null
        || p.getGameplay().getStoryLines().isTransitionPending()
        || scene.getWorldId() != 501
        || p.getWorldId() != 501
        || scene.getPlaneId() != c.plane()
        || scene.getFloorId() != c.floor()
        || p.getPlaneId() != scene.getPlaneId()
        || p.getFloorId() != scene.getFloorId()
        || p.getEntryId() != scene.getEntryId()
        || !rules.entry(c.plane(), c.floor(), scene.getEntryId())
        || scene.getStoryLineId() != p.getGameplay().getStoryLines().getCurrentId()
        || scene.getFloorInfo() != GameData.getFloorInfo(c.plane(), c.floor())
        || scene.getPlaneType() != PlaneType.Town && scene.getPlaneType() != PlaneType.Maze
        || !scene.getLoadedGroups().contains(c.group())
        || !Set.of(0, 1).contains(scene.getDimensionId())) return null;
    var source = rules.group(c.plane(), c.floor(), scene.getDimensionId(), c.path());
    if (source == null) return null;
    var authored =
        source.getPropList().stream()
            .filter(a -> a.getID() == 300003 && a.getPropID() == MascotSources.MONSTER)
            .toList();
    var actual =
        scene.getEntitiesByGroup(EntityProp.class, c.group()).stream()
            .filter(a -> a.getInstId() == 300003)
            .toList();
    if (authored.size() != 1 || actual.size() != 1) return null;
    var a = actual.getFirst();
    var original = authored.getFirst();
    return !original.isIsDelete()
            && original.isIsClientOnly()
            && original.isLoadOnInitial()
            && a.getScene() == scene
            && a.getEntityId() > 0
            && scene.getEntityById(a.getEntityId()) == a
            && a.getPropId() == MascotSources.MONSTER
            && a.getPropInfo() != null
            && PropTimelines.source(a.getPropInfo()).equals(PropTimelines.source(original))
        ? a
        : null;
  }

  public static Retcode change(Player p, int group, int target, int entry, int extra) {
    synchronized (p) {
      var c = control(p.getScene(), group);
      if (c == null || entry != p.getEntryId() || extra != 0 || !Set.of(1, 2, 4).contains(target))
        return Retcode.REQ_PARA_INVALID;
      var rules = WorldChestRules.load();
      if (rules == null || !rules.mascot(c.chest()) || !rules.mascotMission(c))
        return Retcode.REWARD_CONFIG_NOT_EXIST;
      var data = p.getGameplay().getWorldChests();
      if (!data.valid(rules, p)) return Retcode.PLAYER_DATA_ERROR;
      if (data.getRecords().values().stream()
              .anyMatch(r -> r.getPhase() == WorldChestState.Phase.PREPARED)
          || data.getUnlocks().values().stream().anyMatch(r -> !r.completed()))
        return Retcode.SYSTEM_BUSY;
      var a = actor(p, rules, c);
      if (a == null) return Retcode.MISSION_SCENE_NOT_MATCH;
      var record = data.getMascots().get(c.chest());
      int current = record == null ? 0 : record.state();
      if (target != current + 1
          || !p.getScene().getGroups().containsKey(group)
          || p.getScene().getGroups().get(group).getState() != current)
        return Retcode.UNSUPPORTED_PROP_STATE;
      if (!MissionStateRecords.mainAccepted(p, c.mission())) return Retcode.MISSION_NOT_DOING;
      var result = commit(p, a, rules, c, target);
      if (result == Retcode.SUCC && target < 3) p.getMissionManager().refresh();
      return result;
    }
  }

  private static Retcode commit(
      Player p, EntityProp a, WorldChestRules rules, MascotSources.Control c, int state) {
    var old = p.getGameplay().getWorldChests();
    var next = old.mascotStep(p, c.chest(), a, rules, state, Instant.now().getEpochSecond());
    var values = p.getGameplay().getStoryValues();
    String key = "floor:" + c.floor() + ":" + c.savedValue();
    var before = values.get(key);
    p.getGameplay().worldChests(next);
    if (state == 4) values.put(key, 3);
    if (!next.valid(rules, p)) {
      p.getGameplay().worldChests(old);
      if (before == null) values.remove(key);
      else values.put(key, before);
      return Retcode.PLAYER_DATA_ERROR;
    }
    try {
      p.save();
    } catch (RuntimeException failed) {
      p.getGameplay().worldChests(old);
      if (before == null) values.remove(key);
      else values.put(key, before);
      return Retcode.SYSTEM_BUSY;
    }
    p.getScene().getGroups().computeIfAbsent(c.group(), SceneGroup::new).setState(state);
    Story46.syncState(p, c.floor(), c.group(), state);
    if (state >= 3) Story46.syncFloor(p, c.plane(), c.floor(), c.savedValue(), state == 4 ? 3 : 1);
    if (state == 4) p.getScene().loadGroup(p.getScene().getResourceGroups().get(c.rewardGroup()));
    return Retcode.SUCC;
  }

  public static void reconcile(Player p) {
    synchronized (p) {
      var scene = p.getScene();
      if (scene == null) return;
      var data = p.getGameplay().getWorldChests();
      if (data.getMascots() == null
          || data.getMascots().values().stream().noneMatch(r -> r != null && r.state() == 2))
        return;
      var rules = WorldChestRules.load();
      if (!data.valid(rules, p)) return;
      if (data.getRecords().values().stream()
              .anyMatch(r -> r.getPhase() == WorldChestState.Phase.PREPARED)
          || data.getUnlocks().values().stream().anyMatch(r -> !r.completed())) return;
      for (var c : MascotSources.CONTROLS.values()) {
        var r = p.getGameplay().getWorldChests().getMascots().get(c.chest());
        if (r == null || r.state() != 2 || !MascotRecord.missionComplete(p, c)) continue;
        var a = actor(p, rules, c);
        if (a == null) continue;
        var sceneGroup = scene.getGroups().get(c.group());
        if (sceneGroup == null
            || sceneGroup.getState() != 2
            || !Objects.equals(
                p.getGameplay().getStoryValues().get("floor:" + c.floor() + ":" + c.savedValue()),
                1)) continue;
        commit(p, a, rules, c, 3);
      }
    }
  }

  public static boolean shouldLoad(Scene scene, GroupInfo group) {
    var c = reward(scene, group.getId());
    if (c == null) return true;
    var p = scene.getPlayer();
    var data = p.getGameplay().getWorldChests();
    var r = data.getMascots() == null ? null : data.getMascots().get(c.chest());
    if (r == null || r.state() != 4) return false;
    var rules = WorldChestRules.load();
    return data.valid(rules, p)
        && rules.group(
                c.plane(),
                c.floor(),
                scene.getDimensionId(),
                MascotSources.PUZZLES.get(c.chest()).path())
            == group;
  }

  public static void restore(Scene scene, GroupInfo group) {
    var c = control(scene, group.getId());
    if (c == null) return;
    var data = scene.getPlayer().getGameplay().getWorldChests();
    var r = data.getMascots() == null ? null : data.getMascots().get(c.chest());
    int value = r != null && data.valid(WorldChestRules.load(), scene.getPlayer()) ? r.state() : 0;
    scene.getGroups().computeIfAbsent(group.getId(), SceneGroup::new).setState(value);
  }

  public static int floorValue(Scene scene, String name, int fallback) {
    var c =
        MascotSources.CONTROLS.values().stream()
            .filter(
                v ->
                    v.plane() == scene.getPlaneId()
                        && v.floor() == scene.getFloorId()
                        && v.savedValue().equals(name))
            .findFirst()
            .orElse(null);
    if (c == null) return fallback;
    var p = scene.getPlayer();
    var data = p.getGameplay().getWorldChests();
    var r = data.getMascots() == null ? null : data.getMascots().get(c.chest());
    if (r == null || !data.valid(WorldChestRules.load(), p)) return 0;
    if (r.state() == 4) return 3;
    if (r.state() == 3) return 1;
    return r.state() == 2 && c.battle() != 0 && MissionStateRecords.subCompleted(p, c.battle())
        ? 2
        : 0;
  }

  private Mascots() {}
}
