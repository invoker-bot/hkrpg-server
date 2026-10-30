package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Restore only the second tutorial's authored room entries and recorded encounter wins. */
final class RogueSecondStoryProgress {
  private static final Map<Integer, Integer> SECOND_CONTENT = Map.of(6, 2004, 7, 2005, 18, 2006);
  private static final Map<Integer, Integer> SECOND_INSTANCES = Map.of(6, 200001, 7, 200001, 18, 200005);

  private static boolean source(Player player) {
    var run = player.getGameplay().getRogue();
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    var second = GameplayDefinitions.rooms.get(202);
    var scene = player.getScene();
    var entry = GameData.getMapEntranceExcelMap().get(8000102);
    return run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING && run.isInside()
        && run.getRunId() != null && !run.getRunId().isBlank()
        && run.getCleared() != null && run.getCleared().contains(1)
        && (run.getSiteId() == 2 || run.getSiteId() == 3 && run.getCleared().contains(2))
        && RogueSecondStoryEntry.room(run, room)
        && second != null && second.RogueRoomType() == 1 && second.MapEntrance() == 8000102
        && second.GroupID() == 18 && Map.of(18, 2006, 6, 2004, 7, 2005).equals(second.GroupWithContent())
        && scene != null && scene.getPlayer() == player && scene.getDimensionId() == 0
        && scene.getPlaneId() == 80001 && scene.getFloorId() == 80001002
        && scene.getEntryId() == 8000102 && player.getEntryId() == 8000102
        && scene.getFloorInfo() != null && entry != null
        && entry.getPlaneID() == 80001 && entry.getFloorID() == 80001002;
  }

  private static boolean defeated(Player player, int group) {
    var run = player.getGameplay().getRogue();
    Integer instance = SECOND_INSTANCES.get(group), content = SECOND_CONTENT.get(group);
    if (instance == null || content == null
        || !RoguePropDestruction.validReceipts(run.getDefeatedRogueMonsters())
        || !RogueMonsterSelection.validChoices(run.getMonsterSelections())) return false;
    var scene = player.getScene();
    var authored = scene.getFloorInfo().getGroups(0).get(group);
    var selected = run.getMonsterSelections().get("2:202:80001:80001002:" + group);
    var source = GameplayDefinitions.rogueMonsterGroups.get(content);
    if (authored == null || authored.getId() != group || authored.getMonsterList() == null
        || authored.getMonsterList().stream().filter(Objects::nonNull)
            .filter(m -> m.getID() == instance && m.isLoadOnInitial() && !m.isIsDelete() && !m.isIsClientOnly())
            .count() != 1
        || selected == null || selected.getContent() != content || source == null
        || source.RogueMonsterGroupID() != content
        || !Objects.equals(source.RogueMonsterListAndWeight(), selected.getWeights())
        || source.RogueMonsterListAndWeight().getOrDefault(selected.getMonster(), 0) <= 0)
      return false;
    var mapping = GameplayDefinitions.rogueMonsters.get(selected.getMonster());
    return mapping != null && mapping.NpcMonsterID() == selected.getNpc()
        && mapping.EventID() == selected.getStage() && RogueMonsterSelection.complete(mapping)
        && run.getDefeatedRogueMonsters().contains("2:202:80001:80001002:" + group + ":" + instance);
  }

  static boolean satisfied(Player player, MissionInfo.SubMissionInfo c) {
    if (player == null || c == null || c.getMainMissionID() != 4030002
        || c.getLevelPlaneID() != 80001 || c.getLevelFloorID() != 80001002
        || !"Equal".equals(c.getParamType()) || c.getProgress() != 1
        || c.getParamInt3() != 0 || !c.getParamStr1().isBlank()
        || c.getParamIntList().length != 0 || !c.getParamItemList().isEmpty()
        || !source(player)) return false;
    var run = player.getGameplay().getRogue();
    return switch (c.getId()) {
      case 403000211 -> "RogueEnterRoomWithLevel".equals(c.getFinishType())
          && c.getParamInt1() == 2 && c.getParamInt2() == 2;
      case 403000218 -> "RogueEnterRoomWithLevel".equals(c.getFinishType())
          && c.getParamInt1() == 2 && c.getParamInt2() == 3 && run.getSiteId() == 3
          && SECOND_INSTANCES.keySet().stream().allMatch(g -> defeated(player, g));
      case 403000212, 403000214, 403000217 -> {
        int group = switch (c.getId()) { case 403000212 -> 6; case 403000214 -> 7; default -> 18; };
        yield "KillMonster".equals(c.getFinishType()) && c.isBackTrack()
            && c.getMazePlaneID() == 80001 && c.getMazeFloorID() == 80001002
            && c.getParamInt1() == group && c.getParamInt2() == SECOND_INSTANCES.get(group)
            && defeated(player, group);
      }
      default -> false;
    };
  }

  /** Both tutorials share area 100: the earlier win must not settle this run's objective. */
  static boolean finished(Player player) {
    var gameplay = player.getGameplay();
    var run = gameplay.getRogue();
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    if (run.getPhase() != GameplayState.RogueRun.Phase.FINISHED
        || !RogueSecondStoryEntry.room(run, room) || run.getSiteId() != 3
        || run.getRunId() == null || run.getRunId().isBlank()
        || run.getCleared() == null || !run.getCleared().equals(Set.of(1, 2, 3))
        || !RoguePropDestruction.validReceipts(run.getDefeatedRogueMonsters())
        || !run.getDefeatedRogueMonsters().contains("3:203:80001:80001002:29:200001")) return false;
    var result = gameplay.getRogueResults().get(run.getRunId());
    return result != null && result.getAreaId() == 100
        && result.getOutcome() == GameplayState.RogueRun.Phase.FINISHED
        && result.getPassedRooms() == 3;
  }

  private RogueSecondStoryProgress() {}
}
