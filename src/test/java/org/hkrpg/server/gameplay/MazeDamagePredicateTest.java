package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.DamageType;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original common-ability task branches explicitly invoked, without modifier listeners. */
class MazeDamagePredicateTest {
  @TempDir Path root;
  final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();
  JsonArray source;

  @BeforeEach
  void setup() throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/maze-damage-predicate-4.6.json")) {
      assertNotNull(input);
      source =
          JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
  }

  JsonObject predicate(DamageType damage) {
    return source
        .get(damage.ordinal())
        .getAsJsonObject()
        .getAsJsonObject("task")
        .getAsJsonObject("Predicate")
        .deepCopy();
  }

  GameAvatar caster(DamageType damage) {
    var row = new JsonObject();
    row.addProperty("AvatarID", 199975);
    row.addProperty("DamageType", damage.name());
    return new GameAvatar(fixture.gson.fromJson(row, AvatarExcel.class));
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

  JsonObject composite(String operation, JsonObject other) {
    var result = new JsonObject();
    result.addProperty("$type", "RPG.GameCore." + operation);
    var children = new JsonArray();
    children.add(predicate(DamageType.Fire));
    children.add(other);
    result.add("PredicateList", children);
    return result;
  }

  @ParameterizedTest
  @EnumSource(DamageType.class)
  void originalSevenBranchesSelectOnlyTheActualCasterDamageType(DamageType damage) {
    var tasks = new JsonObject[source.size()];
    for (int i = 0; i < tasks.length; i++)
      tasks[i] = source.get(i).getAsJsonObject().getAsJsonObject("task");
    var skill = fixture.parse(fixture.excel(), "ExplicitCommonDamage_MazeSkill", tasks);
    var caster = caster(damage);
    skill.onCast(caster, MotionInfo.newInstance());
    for (var expected : DamageType.values()) {
      String modifier = "ADV_StageAbility_MazeCommon_ListenEnterBattle_" + expected.name();
      assertEquals(expected == damage, skill.hasAdventureModifier(caster, modifier));
      assertFalse(skill.hasAdventureModifier(modifier), "Original branch entered shared metadata");
    }
  }

  @ParameterizedTest
  @EnumSource(DamageType.class)
  void inverseSelectsFailureForMatchingDamageAndSuccessForAnotherDamage(DamageType damage) {
    var predicate = predicate(damage);
    predicate.addProperty("Inverse", true);
    var skill = fixture.parse(fixture.excel(), "InverseDamage_MazeSkill", branch(predicate));
    var same = caster(damage);
    var other = caster(DamageType.values()[(damage.ordinal() + 1) % DamageType.values().length]);
    skill.onCast(same, MotionInfo.newInstance());
    assertFalse(same.getBuffs().containsKey(130401));
    assertTrue(same.getBuffs().containsKey(130404));
    skill.onCast(other, MotionInfo.newInstance());
    assertTrue(other.getBuffs().containsKey(130401));
    assertFalse(other.getBuffs().containsKey(130404));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingExcelOrDamageRemainsUnavailableEvenWithInverse(boolean inverse) {
    var predicate = predicate(DamageType.Fire);
    predicate.addProperty("Inverse", inverse);
    var condition = MazeBuffConditions.compileBranch(predicate);
    assertTrue(condition.isPresent());
    assertTrue(condition.get().apply(new GameAvatar()).isEmpty());
    assertTrue(condition.get().apply(new GameAvatar(fixture.excel())).isEmpty());
    assertTrue(condition.get().apply(null).isEmpty());
    var skill = fixture.parse(fixture.excel(), "UnavailableDamage_MazeSkill", branch(predicate));
    var caster = new GameAvatar(fixture.excel());
    skill.onCast(caster, MotionInfo.newInstance());
    assertTrue(caster.getBuffs().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "null",
        "2",
        "\"AllType\"",
        "\"Physical, Fire\"",
        "\"Heal\"",
        "[]",
        "{}",
        "false"
      })
  void unsupportedDamageRepresentationDoesNotCompile(String raw) {
    var predicate = predicate(DamageType.Fire);
    if (raw.equals("missing")) predicate.remove("DamageType");
    else predicate.add("DamageType", JsonParser.parseString(raw));
    assertTrue(MazeBuffConditions.compileBranch(predicate).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ParamEntity", "missing", "extra"})
  void unresolvedOrExtendedTargetDoesNotBecomeTheCaster(String mode) {
    var predicate = predicate(DamageType.Fire);
    if (mode.equals("missing")) predicate.remove("TargetType");
    else if (mode.equals("extra"))
      predicate.getAsJsonObject("TargetType").addProperty("SourceHint", 1);
    else predicate.getAsJsonObject("TargetType").addProperty("Alias", mode);
    assertTrue(MazeBuffConditions.compileBranch(predicate).isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"ByAnd,false", "ByAnd,true", "ByAny,false", "ByAny,true"})
  void compositeCombinesCasterDamageWithTheExistingEffectCondition(
      String operation, boolean marked) {
    var effect =
        JsonParser.parseString(
                "{\"$type\":\"RPG.GameCore.ByCompareCarryMazebuff\",\"BuffID\":130402,\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"}}")
            .getAsJsonObject();
    var condition = composite(operation, effect);
    var skill = fixture.parse(fixture.excel(), "CompositeDamage_MazeSkill", branch(condition));
    var caster = caster(DamageType.Fire);
    if (marked) caster.addBuff(130402, -1);
    skill.onCast(caster, MotionInfo.newInstance());
    boolean expected = operation.equals("ByAny") || marked;
    assertEquals(expected, caster.getBuffs().containsKey(130401));
    assertEquals(!expected, caster.getBuffs().containsKey(130404));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ByAnd", "ByAny"})
  void unavailableDamageCannotBeHiddenByAnotherCompositeResultOrInverse(String operation) {
    var effect =
        JsonParser.parseString(
                "{\"$type\":\"RPG.GameCore.ByCompareCarryMazebuff\",\"BuffID\":130402,\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"}}")
            .getAsJsonObject();
    var condition = composite(operation, effect);
    condition.addProperty("Inverse", true);
    var compiled = MazeBuffConditions.compileBranch(condition);
    assertTrue(compiled.isPresent());
    var caster = new GameAvatar(fixture.excel());
    caster.addBuff(130402, -1);
    assertTrue(compiled.get().apply(caster).isEmpty());
  }

  @Test
  void aKnownDamageChildCannotMakeAnUnknownCompositeChildSupported() {
    var condition =
        composite(
            "ByAny",
            JsonParser.parseString("{\"$type\":\"RPG.GameCore.ByHaveAbilityTarget\"}")
                .getAsJsonObject());
    assertTrue(MazeBuffConditions.compileBranch(condition).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void requestedTrialActorUsesItsOwnDamageInBothPacketHandlers(boolean nativePath)
      throws Exception {
    var scene = new SceneSkillCasterTest();
    scene.root = root;
    scene.setup();
    try {
      for (int id : new int[] {StoryLineLifecycleTest.ACTOR, StoryLineLifecycleTest.ACTOR + 2}) {
        var excel = GameData.getAvatarExcelMap().get(id);
        var field = AvatarExcel.class.getDeclaredField("DamageType");
        field.setAccessible(true);
        field.set(
            excel, id == StoryLineLifecycleTest.ACTOR ? DamageType.Physical : DamageType.Fire);
      }
      var owner = scene.base.started(false);
      var requested = owner.getAvatarById(StoryLineLifecycleTest.SPECIAL + 1);
      var tasks = new JsonObject[source.size()];
      for (int i = 0; i < tasks.length; i++)
        tasks[i] = source.get(i).getAsJsonObject().getAsJsonObject("task");
      var skill = fixture.parse(requested.getExcel(), "RequestedDamage_MazeSkill", tasks);
      assertEquals(DamageType.Physical, owner.getCurrentLeaderAvatar().getExcel().getDamageType());
      assertEquals(DamageType.Fire, requested.getExcel().getDamageType());
      var session = scene.cast(nativePath);
      assertEquals(nativePath, session.attackAttempted);
      assertEquals(4, owner.getCurrentLineup().getMp());
      for (var expected : DamageType.values()) {
        String modifier = "ADV_StageAbility_MazeCommon_ListenEnterBattle_" + expected.name();
        assertEquals(expected == DamageType.Fire, skill.hasAdventureModifier(requested, modifier));
        assertFalse(skill.hasAdventureModifier(owner.getCurrentLeaderAvatar(), modifier));
      }
    } finally {
      scene.cleanup();
    }
  }
}
