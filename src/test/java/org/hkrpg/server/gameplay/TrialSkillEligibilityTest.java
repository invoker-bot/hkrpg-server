package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.trial.TrialAvatar;
import java.util.*;
import org.junit.jupiter.api.*;

class TrialSkillEligibilityTest {
  static final int ACTOR = 199960;
  final Gson gson = new Gson();
  AvatarExcel actor, oldActor;
  List<AvatarSkillTreeExcel> oldRows;
  Map<Long, AvatarSkillTreeExcel> oldCache;

  @BeforeEach
  void install() {
    oldActor = GameData.getAvatarExcelMap().get(ACTOR);
    oldRows = new ArrayList<>(GameData.getAvatarSkillTreeExcelMap().values());
    oldCache = new HashMap<>(GameDepot.getAvatarSkillTreeExcels());
    GameData.getAvatarSkillTreeExcelMap().values().clear();
    actor = gson.fromJson("{\"AvatarID\":199960}", AvatarExcel.class);
    GameData.getAvatarExcelMap().add(actor);
  }

  @AfterEach
  void restore() {
    GameData.getAvatarSkillTreeExcelMap().values().clear();
    oldRows.forEach(GameData.getAvatarSkillTreeExcelMap()::add);
    GameDepot.getAvatarSkillTreeExcels().clear();
    GameDepot.getAvatarSkillTreeExcels().putAll(oldCache);
    GameData.getAvatarExcelMap().keySet().remove(ACTOR);
    if (oldActor != null) GameData.getAvatarExcelMap().add(oldActor);
  }

  void row(int anchor, int level, int max, int promotion, int minimumLevel, int... parents) {
    var json = new com.google.gson.JsonObject();
    json.addProperty("AvatarID", ACTOR);
    json.addProperty("PointID", ACTOR * 100 + anchor);
    json.addProperty("Level", level);
    json.addProperty("MaxLevel", max);
    json.addProperty("AnchorType", "Point" + String.format("%02d", anchor));
    json.addProperty("AvatarPromotionLimit", promotion);
    json.addProperty("AvatarLevelLimit", minimumLevel);
    json.addProperty("DefaultUnlock", level == 1 && parents.length == 0 && promotion == 0);
    json.add(
        "PrePoint", gson.toJsonTree(Arrays.stream(parents).map(p -> ACTOR * 100 + p).toArray()));
    var row = gson.fromJson(json, AvatarSkillTreeExcel.class);
    GameData.getAvatarSkillTreeExcelMap().add(row);
    row.onLoad();
  }

  TrialAvatar trial(int level, int promotion) {
    var row =
        gson.fromJson(
            "{\"SpecialAvatarID\":1199960,\"AvatarID\":199960,\"Level\":"
                + level
                + ",\"Promotion\":"
                + promotion
                + "}",
            SpecialAvatarExcel.class);
    return new TrialAvatar(new Player(), row, actor);
  }

  @Test
  void lowerLevelTrialCannotEncodeSkillLevelsOrTracesAboveItsResourceLimits() {
    row(1, 1, 6, 0, 0);
    row(1, 2, 6, 2, 40);
    for (int level = 3; level <= 6; level++) row(1, level, 6, 4, 60);
    row(2, 1, 1, 6, 80, 1);
    var trial = trial(40, 2);
    assertEquals(2, trial.getSkillTree().get(1));
    assertFalse(trial.getSkillTree().containsKey(2));
    var battle = trial.toBattleProto(null, 0);
    assertEquals(1, battle.getSkilltreeList().length());
    assertEquals(2, battle.getSkilltreeList().get(0).getLevel());
  }

  @Test
  void missingIntermediateSkillRowsCannotProduceAnInventedOrSkippedLevel() {
    row(1, 1, 6, 0, 0);
    row(1, 5, 6, 0, 0);
    var trial = trial(80, 6);
    assertEquals(1, trial.getSkillTree().get(1));
    assertEquals(1, trial.toBattleProto(null, 0).getSkilltreeList().get(0).getLevel());
  }

  @Test
  void lockedMissingOrCyclicPrerequisitesCannotUnlockDependentTraceNodes() {
    row(1, 1, 1, 6, 80);
    row(2, 1, 1, 0, 0, 1);
    row(3, 1, 1, 0, 0, 4);
    row(4, 1, 1, 0, 0, 3);
    row(5, 1, 1, 0, 0, 99);
    row(6, 1, 1, 0, 0);
    row(7, 1, 1, 0, 0, 6);
    row(8, 1, 1, 0, 0, 7);
    assertEquals(Set.of(6, 7, 8), new HashSet<>(trial(40, 2).getSkillTree().keySet()));
  }

  @Test
  void aFullyEligibleTrialPreservesLegacyRequestedLevelsAndRealBattleRows() {
    for (int level = 1; level <= 6; level++) row(1, level, 6, 0, 0);
    for (int level = 1; level <= 10; level++) row(2, level, 10, 0, 0);
    row(3, 1, 1, 0, 0, 2);
    var trial = trial(80, 6);
    assertEquals(Map.of(1, 5, 2, 8, 3, 1), new HashMap<>(trial.getSkillTree()));
    assertEquals(3, trial.toBattleProto(null, 0).getSkilltreeList().length());
  }
}
