package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unknown task containers cannot grant their server-side children without their own semantics. */
class MazeTaskDispatchTest {
  private static final int BUFF = MazeCasterBuffRemovalTest.BUFF;
  private final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();

  private MazeSkill parse(JsonObject... tasks) {
    return fixture.parse(fixture.excel(), "Dispatch_MazeSkill", tasks);
  }

  private GameAvatar caster() {
    var caster = new GameAvatar(fixture.excel());
    caster.addBuff(BUFF, -1);
    return caster;
  }

  private JsonObject task(String kind) {
    var task = new JsonObject();
    task.addProperty("$type", kind);
    return task;
  }

  private JsonArray children(JsonObject... tasks) {
    var array = new JsonArray();
    for (var task : tasks) array.add(task);
    return array;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "RPG.GameCore.SetComponentAssetLoadStateWithAnimState",
        "RPG.GameCore.UnknownWait",
        "RPG.GameCore.CustomPredicateTaskList",
        "RPG.GameCore.LoopExecuteTaskList",
        "RPG.GameCore.Retarget",
        "RPG.GameCore.RandomConfig",
        "RPG.GameCore.AdvFilterNearbyMonsters"
      })
  void unknownContainersDoNotExecuteSuccessOrFailureChildren(String kind) {
    var root = task(kind);
    root.add("SuccessTaskList", children(fixture.remove(BUFF)));
    root.add("FailedTaskList", children(fixture.remove(BUFF)));
    var skill = parse(root);
    var caster = caster();
    skill.onCast(caster, MotionInfo.newInstance());
    skill.onAttack(caster, List.of());
    skill.onCastHit(caster, List.of());
    assertTrue(caster.getBuffs().containsKey(BUFF), "Unknown container flattened its children");
    assertTrue(skill.getCastActions().isEmpty());
    assertTrue(skill.getAttackActions().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Custom.AddMazeBuff", "RPG.GameCore.AddMazeBuffExtension",
        "Custom.AddAdventureModifier", "Custom.AdventureTriggerAttack",
        "Custom.AdventureFireProjectile", "Custom.AdventureSetAttackTargetMonsterDie"
      })
  void taskNamesContainingKnownWordsAreNotKnownActions(String kind) {
    var root = fixture.add(BUFF + 1, 20);
    root.addProperty("$type", kind);
    root.addProperty("ModifierName", "DispatchMarker");
    root.addProperty("TriggerBattle", false);
    root.add("OnAttack", children(fixture.remove(BUFF)));
    root.add("OnProjectileHit", children(fixture.remove(BUFF)));
    var skill = parse(root);
    var caster = caster();
    skill.onCast(caster, MotionInfo.newInstance());
    skill.onAttack(caster, List.of());
    assertTrue(caster.getBuffs().containsKey(BUFF));
    assertFalse(caster.getBuffs().containsKey(BUFF + 1));
    assertFalse(skill.hasAdventureModifier("DispatchMarker"));
    assertTrue(skill.isTriggerBattle());
    assertTrue(skill.getCastActions().isEmpty());
    assertTrue(skill.getAttackActions().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "AddMazeBuff",
        "RemoveMazeBuff",
        "PredicateTaskList",
        "AddAdventureModifier",
        "AdventureTriggerAttack",
        "AdventureFireProjectile",
        "AdventureSetAttackTargetMonsterDie",
        "DestroySummonUnit",
        "CreateSummonUnit"
      })
  void explicitlyClientOnlyTasksDoNotRegisterServerActions(String kind) {
    var root = fixture.add(BUFF + 1, 20);
    root.addProperty("$type", "RPG.GameCore." + kind);
    root.addProperty("IsClientOnly", true);
    root.addProperty("ModifierName", "DispatchMarker");
    root.addProperty("TriggerBattle", false);
    root.add(
        "Predicate",
        JsonParser.parseString(
            """
            {"$type":"RPG.GameCore.ByCompareCarryMazebuff","BuffID":122001,
             "TargetType":{"$type":"RPG.GameCore.TargetAlias","Alias":"Caster"}}
            """));
    root.add("SuccessTaskList", children(fixture.remove(BUFF)));
    root.add("OnAttack", children(fixture.remove(BUFF)));
    root.add("OnProjectileHit", children(fixture.remove(BUFF)));
    root.add(
        "SummonUnit",
        JsonParser.parseString(
            """
            {"$type":"RPG.GameCore.TargetFetchSummonUnit","SummonUnitID":123,
             "Summoner":{"$type":"RPG.GameCore.TargetAlias","Alias":"Caster"}}
            """));
    var skill = parse(root);
    var caster = caster();
    skill.onCast(caster, MotionInfo.newInstance());
    skill.onAttack(caster, List.of());
    skill.onCastHit(caster, List.of());
    assertTrue(caster.getBuffs().containsKey(BUFF));
    assertFalse(caster.getBuffs().containsKey(BUFF + 1));
    assertFalse(skill.hasAdventureModifier("DispatchMarker"));
    assertTrue(skill.isTriggerBattle());
    assertTrue(skill.getCastActions().isEmpty());
    assertTrue(skill.getAttackActions().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"AdventureTriggerAttack", "AdventureFireProjectile"})
  void knownCallbackTaskUsesItsOwnCallbackEvenWhenUnrelatedSuccessListIsPresent(String kind) {
    var root = task("RPG.GameCore." + kind);
    root.add("SuccessTaskList", children(fixture.add(BUFF + 1, 20)));
    root.add(
        kind.equals("AdventureTriggerAttack") ? "OnAttack" : "OnProjectileHit",
        children(fixture.remove(BUFF)));
    var skill = parse(root);
    var caster = caster();
    skill.onCast(caster, MotionInfo.newInstance());
    assertFalse(caster.getBuffs().containsKey(BUFF + 1));
    assertTrue(caster.getBuffs().containsKey(BUFF));
    skill.onAttack(caster, List.of());
    assertFalse(
        caster.getBuffs().containsKey(BUFF), "Authored callback was shadowed by SuccessTaskList");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ParamEntity", "AdvLineup", "TaskActionTarget"})
  void explicitNonCasterModifierTargetsCannotBecomeSharedCasterMetadata(String alias) {
    var root = task("RPG.GameCore.AddAdventureModifier");
    root.addProperty("ModifierName", "DispatchMarker");
    root.add(
        "TargetType",
        JsonParser.parseString(
            "{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"" + alias + "\"}"));
    var skill = parse(root);
    var caster = caster();
    skill.onCast(caster, MotionInfo.newInstance());
    assertFalse(skill.hasAdventureModifier("DispatchMarker"));
    assertFalse(skill.hasAdventureModifier(caster, "DispatchMarker"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void absentOrNullTaskTypeDoesNotDisableIndependentValidSiblings(int variation) {
    var root = variation == 0 ? null : new JsonObject();
    if (variation == 2) root.add("$type", JsonNull.INSTANCE);
    var skill = assertDoesNotThrow(() -> parse(root, fixture.remove(BUFF)));
    var caster = caster();
    skill.onCast(caster, MotionInfo.newInstance());
    assertFalse(caster.getBuffs().containsKey(BUFF));
  }

  @Test
  void independentKnownActionsRemainOrderedAroundAnUnknownContainer() {
    var root = task("RPG.GameCore.UnknownWait");
    root.add("SuccessTaskList", children(fixture.remove(BUFF + 1)));
    var skill = parse(fixture.add(BUFF + 1, 20), root, fixture.remove(BUFF));
    var caster = caster();
    skill.onCast(caster, MotionInfo.newInstance());
    assertTrue(caster.getBuffs().containsKey(BUFF + 1));
    assertFalse(caster.getBuffs().containsKey(BUFF));
  }

  @Test
  void legacyUnconditionalModifierWithoutSelectorStillRetainsItsExistingDefault() {
    var root = task("RPG.GameCore.AddAdventureModifier");
    root.addProperty("ModifierName", "LegacyMarker");
    var skill = parse(root);
    assertTrue(skill.hasAdventureModifier("LegacyMarker"));
  }

  @Test
  void explicitCasterModifierRemainsAvailableToSupportedConditionalBranches() {
    var root = task("RPG.GameCore.AddAdventureModifier");
    root.addProperty("ModifierName", "CasterMarker");
    root.add(
        "TargetType",
        JsonParser.parseString("{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"}"));
    assertTrue(parse(root).hasAdventureModifier("CasterMarker"));
  }

  @Test
  void originalAnimationLoadWrapperRemainsUnimplementedInsteadOfBeingASuccessContainer()
      throws Exception {
    try (var stream = getClass().getResourceAsStream("/fixtures/maze-task-dispatch-4.6.json")) {
      assertNotNull(stream);
      var row =
          JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
              .getAsJsonObject();
      assertFalse(row.get("completeAbilityChainVerified").getAsBoolean());
      var skill = parse(row.getAsJsonObject("task"));
      assertTrue(skill.getCastActions().isEmpty());
      assertTrue(skill.getAttackActions().isEmpty());
      assertTrue(skill.isTriggerBattle());
    }
  }
}
