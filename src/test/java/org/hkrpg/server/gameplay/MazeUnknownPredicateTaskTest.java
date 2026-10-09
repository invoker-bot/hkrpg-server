package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class MazeUnknownPredicateTaskTest {
  private final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();

  private JsonObject branch(JsonElement predicate) {
    var task = new JsonObject();
    task.addProperty("$type", "RPG.GameCore.PredicateTaskList");
    if (predicate != null) task.add("Predicate", predicate);
    var success = new JsonArray();
    success.add(fixture.add(130401, -1));
    task.add("SuccessTaskList", success);
    var failure = new JsonArray();
    failure.add(fixture.add(130404, -1));
    task.add("FailedTaskList", failure);
    return task;
  }

  static Stream<Arguments> unknownKinds() {
    return Stream.of(
            "ByInMotion",
            "AdvByCompareDynamicValue",
            "ByCanAutoUseItemToRecoverMP",
            "UnknownPredicate")
        .flatMap(name -> Stream.of(false, true).map(inverse -> Arguments.of(name, inverse)));
  }

  @ParameterizedTest
  @MethodSource("unknownKinds")
  void unsupportedRootAndItsInverseDoNotSelectEitherBranch(String name, boolean inverse) {
    var predicate = new JsonObject();
    predicate.addProperty("$type", "RPG.GameCore." + name);
    predicate.addProperty("Inverse", inverse);
    var caster = new GameAvatar(fixture.excel());
    var skill = fixture.parse(fixture.excel(), "UnknownBranch_MazeSkill", branch(predicate));
    skill.onCast(caster, MotionInfo.newInstance());
    skill.onAttack(caster, List.of());
    skill.onCastHit(caster, List.of());
    assertTrue(caster.getBuffs().isEmpty(), "Unsupported root flattened a success/failure leaf");
    assertTrue(skill.getCastActions().isEmpty());
    assertTrue(skill.getAttackActions().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void sourceBranchesRespectTheirCurrentSupportedPredicateSubset(int index) throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/maze-unknown-predicate-4.6.json")) {
      assertNotNull(input);
      var source =
          JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
              .getAsJsonArray()
              .get(index)
              .getAsJsonObject();
      boolean supported = source.get("originalPredicateSupported").getAsBoolean();
      var skill =
          fixture.parse(
              fixture.excel(), "SourceUnknownBranch_MazeSkill", source.getAsJsonObject("task"));
      assertEquals(!supported, skill.getCastActions().isEmpty());
      assertTrue(skill.getAttackActions().isEmpty());
      assertNull(skill.getAdventureModifiers());
      assertTrue(skill.isTriggerBattle(), "Unknown source branch changed global battle policy");
      if (supported) {
        var fire = new MazeDamagePredicateTest().caster(emu.lunarcore.game.enums.DamageType.Fire);
        var ice = new MazeDamagePredicateTest().caster(emu.lunarcore.game.enums.DamageType.Ice);
        skill.onCast(fire, MotionInfo.newInstance());
        skill.onCast(ice, MotionInfo.newInstance());
        var modifier = "ADV_StageAbility_MazeCommon_ListenEnterBattle_Fire";
        assertTrue(skill.hasAdventureModifier(fire, modifier));
        assertFalse(skill.hasAdventureModifier(ice, modifier));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "null", "[]", "\"ByInMotion\"", "17"})
  void absentOrMalformedPredicateDoesNotBecomeAnUnconditionalBranch(String raw) {
    var predicate = raw.equals("missing") ? null : JsonParser.parseString(raw);
    var caster = new GameAvatar(fixture.excel());
    var skill =
        assertDoesNotThrow(
            () -> fixture.parse(fixture.excel(), "InvalidBranch_MazeSkill", branch(predicate)));
    skill.onCast(caster, MotionInfo.newInstance());
    assertTrue(caster.getBuffs().isEmpty());
    assertTrue(skill.getCastActions().isEmpty());
  }

  @Test
  void unsupportedBranchCannotPopulateAttackCallbacksOrStaticModifiers() {
    var predicate = JsonParser.parseString("{\"$type\":\"RPG.GameCore.ByHaveAbilityTarget\"}");
    var task = branch(predicate);
    task.getAsJsonArray("SuccessTaskList")
        .add(
            JsonParser.parseString(
                """
                {"$type":"RPG.GameCore.AdventureTriggerAttack","TriggerBattle":false,
                 "OnAttack":[{"$type":"RPG.GameCore.AddMazeBuff","ID":130403,
                   "TargetType":{"$type":"RPG.GameCore.TargetAlias","Alias":"Caster"},
                   "LifeTime":{"FixedValue":{"Value":-1}}}]}
                """));
    task.getAsJsonArray("SuccessTaskList")
        .add(
            JsonParser.parseString(
                """
{"$type":"RPG.GameCore.AddAdventureModifier",
 "TargetType":{"$type":"RPG.GameCore.TargetAlias","Alias":"Caster"},"ModifierName":"UnknownGuard"}
"""));
    var skill = fixture.parse(fixture.excel(), "UnknownAttack_MazeSkill", task);
    assertTrue(skill.getAttackActions().isEmpty());
    assertNull(skill.getAdventureModifiers());
    assertTrue(skill.isTriggerBattle());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void supportedCasterBranchAndFollowingIndependentActionStillRun(boolean marked) {
    var predicate =
        JsonParser.parseString(
                """
                {"$type":"RPG.GameCore.ByCompareCarryMazebuff","BuffID":130402,
                         "TargetType":{"$type":"RPG.GameCore.TargetAlias","Alias":"Caster"}}
                """)
            .getAsJsonObject();
    // This explicit predicate is the established boolean caster-effect subset.
    var caster = new GameAvatar(fixture.excel());
    if (marked) caster.addBuff(130402, -1);
    var skill =
        fixture.parse(
            fixture.excel(), "KnownBranch_MazeSkill", branch(predicate), fixture.add(130405, -1));
    skill.onCast(caster, MotionInfo.newInstance());
    assertEquals(marked, caster.getBuffs().containsKey(130401));
    assertEquals(!marked, caster.getBuffs().containsKey(130404));
    assertTrue(caster.getBuffs().containsKey(130405));
  }
}
