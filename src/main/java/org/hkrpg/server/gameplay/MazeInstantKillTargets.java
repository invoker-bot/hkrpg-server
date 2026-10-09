package org.hkrpg.server.gameplay;

import emu.lunarcore.game.enums.MonsterRank;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import java.util.Optional;
import java.util.Set;

/**
 * Verified rank, Challenge and placement-tag restrictions; character-config tags remain unmapped.
 */
public final class MazeInstantKillTargets {
  private static final Set<String> TAGS =
      Set.of(
          "Default",
          "Trotter",
          "TrotterLover",
          "ResistInstantKill",
          "FakeChest",
          "TriggerBattleFirstWave");

  private MazeInstantKillTargets() {}

  public static boolean allows(Scene scene, EntityMonster monster) {
    return evaluate(scene, monster).orElse(false);
  }

  public static Optional<Boolean> evaluate(Scene scene, EntityMonster monster) {
    if (scene == null
        || monster == null
        || scene.getPlaneType() == null
        || monster.getScene() != scene
        || !monster.isAlive()
        || scene.getEntityById(monster.getEntityId()) != monster
        || monster.getExcel() == null
        || monster.getMonsterInfo() == null) return Optional.empty();
    var rank = monster.getExcel().getRank();
    if (rank == null || rank == MonsterRank.Unknow) return Optional.empty();
    var tags = monster.getMonsterInfo().getTagList();
    if (tags == null || tags.stream().anyMatch(tag -> tag == null || !TAGS.contains(tag)))
      return Optional.empty();
    return Optional.of(
        scene.getPlaneType() != PlaneType.Challenge
            && (rank == MonsterRank.Minion || rank == MonsterRank.MinionLv2)
            && !tags.contains("ResistInstantKill"));
  }
}
