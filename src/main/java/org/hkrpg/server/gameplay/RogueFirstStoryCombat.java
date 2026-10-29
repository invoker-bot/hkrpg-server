package org.hkrpg.server.gameplay;

import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/** First-story encounter receipts use the original placements and accepted victory records. */
public final class RogueFirstStoryCombat {
  private static final Set<Integer> GROUPS = Set.of(3, 4, 5, 11, 12);
  private static final String SCRIPT = "Config/Level/Maze/MazeRogue/Maze_Rogue_Prop_TriggerBuffSelect.json";

  private static boolean valid(Player player, Scene scene) {
    var run = player.getGameplay().getRogue();
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    return RogueFirstStorySpawn.applies(run, room)
        && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING && run.isInside()
        && scene != null && scene.getPlayer() == player && scene.getPlaneId() == 80001
        && scene.getFloorId() == 80001001 && scene.getEntryId() == 8000101
        && player.getEntryId() == 8000101 && scene.getDimensionId() == 0 && scene.getFloorInfo() != null
        && run.getDefeatedRogueMonsters() != null
        && run.getFirstStoryBlessingReceipts() != null
        && run.getFirstStoryBlessingReceipts().size() <= GROUPS.size()
        && run.getFirstStoryBlessingReceipts().entrySet().stream().allMatch(e ->
            e.getKey() != null && GROUPS.contains(e.getKey()) && e.getValue() != null && e.getValue() >= -1);
  }

  static boolean inContext(Player player) { return valid(player, player.getScene()); }

  private static boolean source(Scene scene, int id) {
    var group = scene.getResourceGroups().get(id);
    if (group == null || group.getId() != id || group.getMonsterList() == null
        || group.getPropList() == null) return false;
    return group.getMonsterList().stream().filter(Objects::nonNull)
            .filter(m -> m.getID() == 200001 && m.isLoadOnInitial()
                && !m.isIsDelete() && !m.isIsClientOnly()).count() == 1
        && group.getPropList().stream().filter(Objects::nonNull)
            .filter(p -> p.getID() == 300001 && p.getPropID() == 100
                && SCRIPT.equals(p.getInitLevelGraph()) && p.isLoadOnInitial()
                && !p.isIsDelete() && !p.isIsClientOnly()).count() == 1;
  }

  static boolean reconcile(Player player) {
    var scene = player.getScene();
    if (!valid(player, scene)) return false;
    var run = player.getGameplay().getRogue();
    boolean changed = false;
    for (int id : GROUPS.stream().sorted().toList()) {
      String defeated = "1:100:80001:80001001:" + id + ":200001";
      if (run.getDefeatedRogueMonsters().contains(defeated) && source(scene, id)
          && !run.getFirstStoryBlessingReceipts().containsKey(id)) {
        run.getFirstStoryBlessingReceipts().put(id, 0);
        changed = true;
      }
    }
    return changed;
  }

  static OptionalInt next(Player player) {
    if (!valid(player, player.getScene())) return OptionalInt.empty();
    var run = player.getGameplay().getRogue();
    if (run.pending() || run.getFirstStoryBlessingReceipts().values().stream().anyMatch(n -> n > 0))
      return OptionalInt.empty();
    return run.getFirstStoryBlessingReceipts().entrySet().stream()
        .filter(e -> e.getValue() == 0).mapToInt(Map.Entry::getKey).min();
  }

  static OptionalInt current(Player player, int selection) {
    if (!valid(player, player.getScene()) || selection <= 0) return OptionalInt.empty();
    var matches = player.getGameplay().getRogue().getFirstStoryBlessingReceipts().entrySet().stream()
        .filter(e -> e.getValue() == selection).mapToInt(Map.Entry::getKey).toArray();
    return matches.length == 1 ? OptionalInt.of(matches[0]) : OptionalInt.empty();
  }

  static boolean hasOutstanding(Player player) {
    return valid(player, player.getScene()) && player.getGameplay().getRogue()
        .getFirstStoryBlessingReceipts().values().stream().anyMatch(n -> n >= 0);
  }

  static boolean allEncountersDefeated(Player player) {
    if (!valid(player, player.getScene())) return false;
    var defeated = player.getGameplay().getRogue().getDefeatedRogueMonsters();
    return Set.of(3, 4, 5, 10, 11, 12).stream()
        .allMatch(id -> defeated.contains("1:100:80001:80001001:" + id + ":200001"));
  }

  /** Only reconstruct a missing clear from all accepted victories and the actual loaded room. */
  static boolean canRecoverClear(Player player) {
    if (player.isInBattle() || !allEncountersDefeated(player)) return false;
    var scene = player.getScene();
    if (!scene.getLoadedGroups().containsAll(Set.of(3, 4, 5, 10, 11, 12))
        || GROUPS.stream().anyMatch(id -> !source(scene, id))) return false;
    var boss = scene.getResourceGroups().get(10);
    if (boss == null || boss.getId() != 10 || boss.getMonsterList() == null
        || boss.getMonsterList().stream().filter(Objects::nonNull)
            .filter(m -> m.getID() == 200001 && m.isLoadOnInitial()
                && !m.isIsDelete() && !m.isIsClientOnly()).count() != 1) return false;
    return scene.getEntities().values().stream()
        .noneMatch(entity -> entity instanceof emu.lunarcore.game.scene.entity.EntityMonster monster
            && UniverseEntityLoader.roomGroups(player).contains(monster.getGroupId()) && monster.isAlive());
  }

  static boolean selected(Player player, int group) {
    return valid(player, player.getScene()) && source(player.getScene(), group)
        && player.getGameplay().getRogue().getFirstStoryBlessingReceipts().getOrDefault(group, 0) == -1;
  }

  static void syncTrigger(Player player, int group) {
    if (!selected(player, group)) return;
    var matches = player.getScene().getEntities().values().stream()
        .filter(EntityProp.class::isInstance).map(EntityProp.class::cast)
        .filter(p -> p.getGroupId() == group && p.getInstId() == 300001 && p.getPropId() == 100)
        .toList();
    if (matches.size() == 1) matches.getFirst().setState(PropState.Open);
  }

  static boolean restoresTrigger(Scene scene, int group, int instance) {
    var run = scene.getPlayer().getGameplay().getRogue();
    return GROUPS.contains(group) && instance == 300001
        && RogueFirstStorySpawn.applies(run, GameplayDefinitions.rooms.get(run.getRoomId()))
        && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING && run.isInside()
        && scene.getPlaneId() == 80001 && scene.getFloorId() == 80001001
        && scene.getDimensionId() == 0 && run.getFirstStoryBlessingReceipts() != null
        && run.getFirstStoryBlessingReceipts().getOrDefault(group, 0) == -1
        && source(scene, group);
  }

  private RogueFirstStoryCombat() {}
}
