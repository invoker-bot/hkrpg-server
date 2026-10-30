package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.*;

/** Source-bound encounter rewards for the two authored tutorial maps. */
public final class RogueTutorialCombat {
  private static final String SCRIPT = "Config/Level/Maze/MazeRogue/Maze_Rogue_Prop_TriggerBuffSelect.json";
  private static final String BARRIER_SCRIPT = "Config/Level/Maze/MazeRogue/Maze_Rogue_Tutorial_BlockLine.json";
  private static final Set<Integer> FIRST_REWARDS = Set.of(3, 4, 5, 11, 12);
  private static final Set<Integer> SECOND_REWARDS = Set.of(3, 4, 6, 7, 18, 19);
  private record Profile(int floor, int entry, int roomGroup, Map<Integer,Integer> encounters, Set<Integer> rewards) {}
  private static final Profile FIRST = new Profile(80001001, 8000101, 10,
      Map.of(3,200001,4,200001,5,200001,10,200001,11,200001,12,200001), FIRST_REWARDS);
  private static final Profile SECOND_A = new Profile(80001002, 8000102, 19,
      Map.of(3,200002,4,200001,19,200005), Set.of(3,4,19));
  private static final Profile SECOND_B = new Profile(80001002, 8000102, 18,
      Map.of(6,200001,7,200001,18,200005), Set.of(6,7,18));
  private record Barrier(int floor, int group, int instance, int encounter, int marker) {}
  private static final List<Barrier> BARRIERS = List.of(
      new Barrier(80001001,10,300005,3,300001), new Barrier(80001001,10,300006,4,300001),
      new Barrier(80001001,10,300007,5,300001), new Barrier(80001002,19,300004,3,300001),
      // This one refers to the download machine, not the blessing marker. Preserve its source
      // but never treat a selected blessing as proof of completing the separate download.
      new Barrier(80001002,19,300005,4,300002), new Barrier(80001002,18,300008,6,300001),
      new Barrier(80001002,18,300009,7,300001));

  private static Profile profile(GameplayState.RogueRun run) {
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    if (RogueFirstStorySpawn.applies(run, room)) return FIRST;
    if (!RogueSecondStoryEntry.room(run, room) || room.RogueRoomType() != 1) return null;
    return switch (room.RogueRoomID()) {
      case 201 -> Map.of(19,2003,3,2001,4,2002).equals(room.GroupWithContent()) ? SECOND_A : null;
      case 202 -> Map.of(18,2006,6,2004,7,2005).equals(room.GroupWithContent()) ? SECOND_B : null;
      default -> null;
    };
  }

  static boolean applies(GameplayState.RogueRun run) { return profile(run) != null; }

  static Map<Integer,Integer> receipts(GameplayState.RogueRun run) {
    return run.getMapId() == 2 ? run.getSecondStoryBlessingReceipts() : run.getFirstStoryBlessingReceipts();
  }

  private static boolean sceneMatches(GameplayState.RogueRun run, Scene scene, Profile p) {
    return p != null && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING && run.isInside()
        && scene != null && scene.getPlaneId() == 80001 && scene.getFloorId() == p.floor()
        && scene.getDimensionId() == 0 && scene.getFloorInfo() != null;
  }

  private static boolean valid(Player player, Scene scene) {
    var run = player.getGameplay().getRogue();
    var p = profile(run);
    var saved = receipts(run);
    var allowed = run.getMapId() == 2 ? SECOND_REWARDS : FIRST_REWARDS;
    return sceneMatches(run, scene, p) && scene.getPlayer() == player
        && scene.getEntryId() == p.entry() && player.getEntryId() == p.entry()
        && run.getDefeatedRogueMonsters() != null && saved != null && saved.size() <= allowed.size()
        && saved.entrySet().stream().allMatch(e -> e.getKey() != null && allowed.contains(e.getKey())
            && e.getValue() != null && e.getValue() >= -1);
  }

  static boolean inContext(Player player) { return valid(player, player.getScene()); }

  private static boolean source(Scene scene, Profile p, int id) {
    var group = scene.getResourceGroups().get(id);
    if (group == null || group.getId() != id || group.getMonsterList() == null
        || !p.encounters().containsKey(id)) return false;
    if (group.getMonsterList().stream().filter(Objects::nonNull)
        .filter(m -> m.getID() == p.encounters().get(id) && m.isLoadOnInitial()
            && !m.isIsDelete() && !m.isIsClientOnly()).count() != 1) return false;
    return !p.rewards().contains(id) || group.getPropList() != null
        && group.getPropList().stream().filter(Objects::nonNull)
            .filter(prop -> prop.getID() == 300001 && prop.getPropID() == 100
                && SCRIPT.equals(prop.getInitLevelGraph()) && prop.isLoadOnInitial()
                && !prop.isIsDelete() && !prop.isIsClientOnly()).count() == 1;
  }

  private static String victory(GameplayState.RogueRun run, Profile p, int group) {
    return run.getSiteId() + ":" + run.getRoomId() + ":80001:" + p.floor() + ":" + group + ":" + p.encounters().get(group);
  }

  static boolean reconcile(Player player) {
    if (!inContext(player)) return false;
    var run = player.getGameplay().getRogue(); var p = profile(run);
    boolean changed = false;
    for (int id : p.rewards().stream().sorted().toList())
      if (run.getDefeatedRogueMonsters().contains(victory(run, p, id)) && source(player.getScene(), p, id)
          && !receipts(run).containsKey(id)) { receipts(run).put(id, 0); changed = true; }
    return changed;
  }

  static OptionalInt next(Player player) {
    if (!inContext(player)) return OptionalInt.empty();
    var run = player.getGameplay().getRogue();
    if (run.pending() || receipts(run).values().stream().anyMatch(n -> n > 0)) return OptionalInt.empty();
    var p = profile(run);
    return receipts(run).entrySet().stream().filter(e -> p.rewards().contains(e.getKey()) && e.getValue() == 0)
        .mapToInt(Map.Entry::getKey).min();
  }

  static OptionalInt current(Player player, int selection) {
    if (!inContext(player) || selection <= 0) return OptionalInt.empty();
    var run = player.getGameplay().getRogue(); var p = profile(run);
    var matches = receipts(run).entrySet().stream()
        .filter(e -> p.rewards().contains(e.getKey()) && e.getValue() == selection).mapToInt(Map.Entry::getKey).toArray();
    return matches.length == 1 ? OptionalInt.of(matches[0]) : OptionalInt.empty();
  }

  static boolean hasOutstanding(Player player) {
    if (!inContext(player)) return false;
    var run = player.getGameplay().getRogue(); var p = profile(run);
    return receipts(run).entrySet().stream().anyMatch(e -> p.rewards().contains(e.getKey()) && e.getValue() >= 0);
  }

  static boolean allEncountersDefeated(Player player) {
    if (!inContext(player)) return false;
    var run = player.getGameplay().getRogue(); var p = profile(run);
    return p.encounters().keySet().stream().allMatch(id -> run.getDefeatedRogueMonsters().contains(victory(run, p, id)));
  }

  static boolean canRecoverClear(Player player) {
    if (player.isInBattle() || !allEncountersDefeated(player)) return false;
    var scene = player.getScene(); var p = profile(player.getGameplay().getRogue());
    return scene.getLoadedGroups().containsAll(p.encounters().keySet())
        && p.encounters().keySet().stream().allMatch(id -> source(scene, p, id))
        && scene.getEntities().values().stream().noneMatch(entity ->
            entity instanceof emu.lunarcore.game.scene.entity.EntityMonster monster
                && UniverseEntityLoader.roomGroups(player).contains(monster.getGroupId()) && monster.isAlive());
  }

  private static boolean selected(GameplayState.RogueRun run, Profile p, int group) {
    return p.rewards().contains(group) && run.getDefeatedRogueMonsters() != null && receipts(run) != null
        && run.getDefeatedRogueMonsters().contains(victory(run, p, group)) && receipts(run).getOrDefault(group, 0) == -1;
  }

  static void syncTrigger(Player player, int group) {
    if (!inContext(player)) return;
    var scene = player.getScene(); var run = player.getGameplay().getRogue(); var p = profile(run);
    if (!selected(run, p, group) || !source(scene, p, group)) return;
    var markers = scene.getEntities(EntityProp.class).stream()
        .filter(prop -> prop.getGroupId() == group && prop.getInstId() == 300001 && prop.getPropId() == 100).toList();
    if (markers.size() != 1) return;
    markers.getFirst().setState(PropState.Open);
    if (markers.getFirst().getState() != PropState.Open || !scene.getLoadedGroups().contains(p.roomGroup())) return;
    for (var link : BARRIERS) {
      if (link.floor() != p.floor() || link.group() != p.roomGroup() || link.encounter() != group) continue;
      var barriers = scene.getEntities(EntityProp.class).stream()
          .filter(prop -> prop.getGroupId() == link.group() && prop.getInstId() == link.instance()
              && prop.getPropId() == 100006 && restoresBarrier(scene, scene.getResourceGroups().get(link.group()), prop.getPropInfo())).toList();
      if (barriers.size() == 1) barriers.getFirst().setState(PropState.Open);
    }
    RogueTutorialDownload.sync(player);
  }

  private static Barrier authoredBarrier(int floor, GroupInfo group, PropInfo info) {
    if (group == null || info == null || info.getPropID() != 100006 || !info.isLoadOnInitial()
        || info.isIsDelete() || info.isIsClientOnly() || !BARRIER_SCRIPT.equals(info.getInitLevelGraph())
        || group.getPropList() == null || group.getPropList().stream().filter(Objects::nonNull)
            .filter(p -> p.getID() == info.getID()).count() != 1) return null;
    var link = BARRIERS.stream().filter(b -> b.floor() == floor && b.group() == group.getId() && b.instance() == info.getID())
        .findFirst().orElse(null);
    if (link == null) return null;
    try {
      var values = info.getValueSource() == null ? null : info.getValueSource().getValues();
      if (values == null || values.size() != 1) return null;
      var marker = values.getFirst();
      return marker != null && marker.keySet().equals(Set.of("$type", "Key", "Value"))
          && "RPG.GameCore.SharedString".equals(marker.get("$type").getAsString())
          && "怪物死亡标记".equals(marker.get("Key").getAsString())
          && (link.encounter() + "," + link.marker()).equals(marker.get("Value").getAsString()) ? link : null;
    } catch (RuntimeException malformed) { return null; }
  }

  public static boolean retainsBarrierSource(FloorInfo floor, GroupInfo group, PropInfo info) {
    return floor != null && group != null && floor.getGroups(0).get(group.getId()) == group
        && authoredBarrier(floor.getFloorID(), group, info) != null;
  }

  static boolean restoresBarrier(Scene scene, GroupInfo group, PropInfo info) {
    if (scene == null || group == null || scene.getResourceGroups().get(group.getId()) != group) return false;
    var run = scene.getPlayer().getGameplay().getRogue(); var p = profile(run);
    if (!sceneMatches(run, scene, p)) return false;
    var link = authoredBarrier(p.floor(), group, info);
    var marker = GameData.getPropExcelMap().get(100); var barrier = GameData.getPropExcelMap().get(100006);
    return link != null && link.group() == p.roomGroup() && link.marker() == 300001
        && marker != null && marker.getPropStateList() != null && marker.getPropStateList().contains(PropState.Open)
        && barrier != null && barrier.getPropStateList() != null && barrier.getPropStateList().contains(PropState.Open)
        && selected(run, p, link.encounter()) && source(scene, p, link.encounter());
  }

  static boolean restoresTrigger(Scene scene, int group, int instance) {
    var run = scene.getPlayer().getGameplay().getRogue(); var p = profile(run);
    return instance == 300001 && sceneMatches(run, scene, p)
        && selected(run, p, group) && source(scene, p, group);
  }

  private RogueTutorialCombat() {}
}
