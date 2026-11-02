package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.scene.Scene;
import java.util.Map;
import java.util.Set;

/** Original MusicRhythmGroup entrance floors and playable EntityGroup placements. */
public final class MusicSceneGroups {
  private static final Map<Integer, Map<Integer, Integer>> ENTRIES = Map.of(
      10001001, Map.of(159, 1),
      10306001, Map.of(19, 2, 206, 4, 208, 7),
      10101001, Map.of(254, 3),
      20004001, Map.of(191, 5),
      20101001, Map.of(102, 6),
      10301001, Map.of(584, 8, 585, 9));

  public static boolean tracks(GroupInfo group, Scene scene) {
    return group != null && scene != null
        && Set.of(PlaneType.Town, PlaneType.Maze).contains(scene.getPlaneType())
        && ENTRIES.getOrDefault(scene.getFloorId(), Map.of()).containsKey(group.getId())
        && ("Config/Level/GroupGraph/F" + scene.getFloorId() + "/Group_F" + scene.getFloorId()
            + "_G" + group.getId() + ".json").equals(group.getLevelGraph());
  }

  public static boolean allowed(GroupInfo group, Scene scene) {
    if (!tracks(group, scene)) return true;
    int musicGroup = ENTRIES.get(scene.getFloorId()).get(group.getId());
    var manager = new MusicManager(scene.getPlayer());
    return GameplayDefinitions.musicLevels.values().stream()
        .anyMatch(level -> level.Group() == musicGroup && manager.unlocked(level.ID()));
  }

  private MusicSceneGroups() {}
}
