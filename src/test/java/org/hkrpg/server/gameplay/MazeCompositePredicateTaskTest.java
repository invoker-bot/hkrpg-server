package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/**
 * Original caster predicates in an explicitly constructed branch fixture, plus original rejection
 * leaves.
 */
class MazeCompositePredicateTaskTest {
  final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();
  JsonArray effects, unsupported;

  @BeforeEach
  void setup() throws Exception {
    effects = read("/fixtures/maze-buff-condition-4.6.json");
    unsupported = read("/fixtures/maze-composite-predicate-4.6.json");
  }

  JsonArray read(String path) throws Exception {
    try (var input = getClass().getResourceAsStream(path)) {
      assertNotNull(input);
      return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
          .getAsJsonArray();
    }
  }

  JsonObject predicate(int index) {
    return effects
        .get(index)
        .getAsJsonObject()
        .getAsJsonObject("task")
        .getAsJsonObject("Condition")
        .deepCopy();
  }

  JsonObject branch(JsonObject predicate) {
    var result = new JsonObject();
    result.addProperty("$type", "RPG.GameCore.PredicateTaskList");
    result.add("Predicate", predicate);
    var success = new JsonArray();
    success.add(fixture.add(130401, -1));
    result.add("SuccessTaskList", success);
    var failure = new JsonArray();
    failure.add(fixture.add(130404, -1));
    result.add("FailedTaskList", failure);
    return result;
  }

  GameAvatar caster(int mask) {
    var c = new GameAvatar(fixture.excel());
    if ((mask & 1) != 0) c.addBuff(130402, -1);
    if ((mask & 2) != 0) c.addBuff(130403, -1);
    return c;
  }

  void assertBranch(GameAvatar caster, boolean success) {
    assertEquals(success, caster.getBuffs().containsKey(130401));
    assertEquals(!success, caster.getBuffs().containsKey(130404));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void unchangedAndPredicateSelectsExactlyOneRuntimeBranch(int mask) {
    var caster = caster(mask);
    fixture
        .parse(fixture.excel(), "BranchFixture_MazeSkill", branch(predicate(0)))
        .onCast(caster, MotionInfo.newInstance());
    assertBranch(caster, mask == 0);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void unchangedAnyPredicateSelectsExactlyOneRuntimeBranch(int mask) {
    var caster = caster(mask);
    fixture
        .parse(fixture.excel(), "BranchFixture_MazeSkill", branch(predicate(1)))
        .onCast(caster, MotionInfo.newInstance());
    assertBranch(caster, mask != 0);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3})
  void compositeInverseReversesTheCompletedTreeRatherThanEachChild(int mask) {
    var predicate = predicate(0);
    predicate.addProperty("Inverse", true);
    var caster = caster(mask);
    fixture
        .parse(fixture.excel(), "BranchFixture_MazeSkill", branch(predicate))
        .onCast(caster, MotionInfo.newInstance());
    assertBranch(caster, mask != 0);
  }

  @Test
  void expiredMarkerDoesNotSatisfyAnAnyBranch() {
    var caster = caster(0);
    caster.getBuffs().put(130402, System.currentTimeMillis() - 60_000);
    fixture
        .parse(fixture.excel(), "BranchFixture_MazeSkill", branch(predicate(1)))
        .onCast(caster, MotionInfo.newInstance());
    assertBranch(caster, false);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void attackAndHitCallbacksPreserveTheWholePredicate(boolean hit) {
    var caster = caster(3);
    var skill = fixture.parse(fixture.excel(), "BranchFixture_NormalAtk01", branch(predicate(0)));
    if (hit) skill.onCastHit(caster, List.of());
    else skill.onAttack(caster, List.of());
    assertBranch(caster, false);
  }

  @Test
  void compiledBranchesReevaluateAfterActualMarkerRemoval() {
    var caster = caster(1);
    var skill = fixture.parse(fixture.excel(), "BranchFixture_MazeSkill", branch(predicate(0)));
    skill.onCast(caster, MotionInfo.newInstance());
    assertBranch(caster, false);
    caster.getBuffs().remove(130404);
    caster.getBuffs().remove(130402);
    skill.onCast(caster, MotionInfo.newInstance());
    assertBranch(caster, true);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void originalMixedUnknownTreesNeverExecuteTheirSuccessTasks(int index) {
    var task = unsupported.get(index).getAsJsonObject().getAsJsonObject("task").deepCopy();
    var caster = caster(0);
    var skill = fixture.parse(fixture.excel(), "OriginalLeafFixture_MazeSkill", task);
    skill.onCast(caster, MotionInfo.newInstance());
    assertTrue(caster.getBuffs().isEmpty());
    assertTrue(skill.getAdventureModifiers() == null || skill.getAdventureModifiers().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty", "null", "unknown", "fraction", "inverse"})
  void invalidWholeTreeCannotBeFlattenedIntoEitherBranch(String kind) {
    var predicate = predicate(0);
    var list = predicate.getAsJsonArray("PredicateList");
    switch (kind) {
      case "empty" -> predicate.add("PredicateList", new JsonArray());
      case "null" -> predicate.add("PredicateList", JsonNull.INSTANCE);
      case "unknown" ->
          list.get(0).getAsJsonObject().addProperty("$type", "RPG.GameCore.ByUnknown");
      case "fraction" -> list.get(0).getAsJsonObject().addProperty("BuffID", 130402.5);
      case "inverse" -> predicate.addProperty("Inverse", 1);
    }
    var caster = caster(0);
    fixture
        .parse(fixture.excel(), "BranchFixture_MazeSkill", branch(predicate))
        .onCast(caster, MotionInfo.newInstance());
    assertTrue(caster.getBuffs().isEmpty());
  }
}
