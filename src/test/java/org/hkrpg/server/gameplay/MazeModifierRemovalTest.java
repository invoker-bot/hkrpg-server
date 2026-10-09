package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Ordered removal of the current cast's legacy marker subset, not a modifier lifecycle. */
class MazeModifierRemovalTest {
  private static final String MARKER = "ADV_StageAbility_Maze_IgnoreWeakness_MazeSkillMark";
  private final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();

  private JsonObject modifier(boolean remove, String name) {
    var task = new JsonObject();
    task.addProperty("$type", "RPG.GameCore." + (remove ? "Remove" : "Add") + "AdventureModifier");
    task.addProperty("ModifierName", name);
    task.add(
        "TargetType",
        JsonParser.parseString("{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"}"));
    return task;
  }

  private JsonObject branch(JsonObject child) {
    var task = new JsonObject();
    task.addProperty("$type", "RPG.GameCore.PredicateTaskList");
    task.add(
        "Predicate",
        JsonParser.parseString(
            """
            {"$type":"RPG.GameCore.ByCompareCarryMazebuff","BuffID":130402,
             "TargetType":{"$type":"RPG.GameCore.TargetAlias","Alias":"Caster"}}
            """));
    var list = new JsonArray();
    list.add(child);
    task.add("SuccessTaskList", list);
    return task;
  }

  private MazeSkill parse(int callback, JsonObject... tasks) {
    return fixture.parse(
        fixture.excel(), callback == 0 ? "Marker_MazeSkill" : "Marker_NormalAtk01", tasks);
  }

  private void invoke(MazeSkill skill, GameAvatar caster, int callback) {
    switch (callback) {
      case 0 -> skill.onCast(caster, MotionInfo.newInstance());
      case 1 -> skill.onAttack(caster, List.of());
      case 2 -> skill.onCastHit(caster, List.of());
      default -> throw new AssertionError("Unknown callback");
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void additionThenRemovalDoesNotLeaveTheCurrentCastMarked(int callback) {
    var skill = parse(callback, modifier(false, MARKER), modifier(true, MARKER));
    var caster = new GameAvatar(fixture.excel());
    invoke(skill, caster, callback);
    assertFalse(skill.hasAdventureModifier(caster, MARKER));
    assertTrue(
        skill.hasAdventureModifier(MARKER), "Legacy definition query changed its API meaning");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void removalThenAdditionRetainsTheLaterAuthoredMarker(int callback) {
    var skill = parse(callback, modifier(true, MARKER), modifier(false, MARKER));
    var caster = new GameAvatar(fixture.excel());
    invoke(skill, caster, callback);
    assertTrue(skill.hasAdventureModifier(caster, MARKER));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void namedRemovalPreservesOtherModifiers(int callback) {
    var skill =
        parse(
            callback,
            modifier(false, MARKER),
            modifier(false, "OtherMarker"),
            modifier(true, MARKER));
    var caster = new GameAvatar(fixture.excel());
    invoke(skill, caster, callback);
    assertFalse(skill.hasAdventureModifier(caster, MARKER));
    assertTrue(skill.hasAdventureModifier(caster, "OtherMarker"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void removalAlsoClearsAnExplicitLegacyDefaultForThisCasterOnly(int callback) {
    var skill = parse(callback, modifier(true, MARKER));
    skill.addAdventureModifier(MARKER);
    var caster = new GameAvatar(fixture.excel());
    var other = new GameAvatar(fixture.excel());
    invoke(skill, caster, callback);
    assertFalse(skill.hasAdventureModifier(caster, MARKER));
    assertTrue(skill.hasAdventureModifier(other, MARKER));
    assertTrue(skill.hasAdventureModifier(MARKER));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void conditionalRemovalSelectsOnlyTheActualCastersBranch(boolean selected) {
    var skill = parse(0, modifier(false, MARKER), branch(modifier(true, MARKER)));
    var caster = new GameAvatar(fixture.excel());
    if (selected) caster.addBuff(130402, -1);
    invoke(skill, caster, 0);
    assertEquals(!selected, skill.hasAdventureModifier(caster, MARKER));
    var other = new GameAvatar(fixture.excel());
    invoke(skill, other, 0);
    assertTrue(skill.hasAdventureModifier(other, MARKER));
    assertEquals(!selected, skill.hasAdventureModifier(caster, MARKER));
  }

  @Test
  void aLaterCastRebuildsAuthoredMarkersAfterAnEarlierRemoval() {
    var skill = parse(0, modifier(false, MARKER), branch(modifier(true, MARKER)));
    var caster = new GameAvatar(fixture.excel());
    caster.addBuff(130402, -1);
    invoke(skill, caster, 0);
    assertFalse(skill.hasAdventureModifier(caster, MARKER));
    caster.getBuffs().remove(130402);
    invoke(skill, caster, 0);
    assertTrue(skill.hasAdventureModifier(caster, MARKER));
  }

  @Test
  void futureAuthoredAdditionIsNotVisibleBeforeItsOwnAction() {
    var skill = parse(0, modifier(false, MARKER));
    var observed = new java.util.ArrayList<Boolean>();
    skill
        .getCastActions()
        .add(
            0,
            new emu.lunarcore.game.battle.skills.MazeSkillAction() {
              @Override
              public void onCast(GameAvatar caster, MotionInfo position) {
                observed.add(skill.hasAdventureModifier(caster, MARKER));
              }
            });
    var caster = new GameAvatar(fixture.excel());
    invoke(skill, caster, 0);
    assertEquals(List.of(false), observed);
    assertTrue(skill.hasAdventureModifier(caster, MARKER));
  }

  @Test
  void anExplicitDefaultStillExistsBeforeItsAuthoredAddition() {
    var skill = parse(0, modifier(false, MARKER));
    skill.addAdventureModifier(MARKER);
    var observed = new java.util.ArrayList<Boolean>();
    skill
        .getCastActions()
        .add(
            0,
            new emu.lunarcore.game.battle.skills.MazeSkillAction() {
              @Override
              public void onCast(GameAvatar caster, MotionInfo position) {
                observed.add(skill.hasAdventureModifier(caster, MARKER));
              }
            });
    invoke(skill, new GameAvatar(fixture.excel()), 0);
    assertEquals(List.of(true), observed);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "clientOnly",
        "wrongType",
        "missingTarget",
        "otherTarget",
        "extraTargetField",
        "blankName",
        "missingName"
      })
  void unresolvedRemovalCannotClearAnExistingMarker(String mode) {
    var removal = modifier(true, MARKER);
    switch (mode) {
      case "clientOnly" -> removal.addProperty("IsClientOnly", true);
      case "wrongType" -> removal.addProperty("$type", "Custom.RemoveAdventureModifier");
      case "missingTarget" -> removal.remove("TargetType");
      case "otherTarget" ->
          removal.getAsJsonObject("TargetType").addProperty("Alias", "ParamEntity");
      case "extraTargetField" -> removal.getAsJsonObject("TargetType").addProperty("Index", 1);
      case "blankName" -> removal.addProperty("ModifierName", " ");
      case "missingName" -> removal.remove("ModifierName");
    }
    var skill = parse(0, modifier(false, MARKER), removal);
    var caster = new GameAvatar(fixture.excel());
    invoke(skill, caster, 0);
    assertTrue(skill.hasAdventureModifier(caster, MARKER));
  }

  @Test
  void independentSkillsDoNotShareTheRemovalSnapshot() {
    var first = parse(0, modifier(false, MARKER), modifier(true, MARKER));
    var second = parse(0, modifier(false, MARKER));
    var caster = new GameAvatar(fixture.excel());
    invoke(first, caster, 0);
    assertFalse(first.hasAdventureModifier(caster, MARKER));
    invoke(second, caster, 0);
    assertTrue(second.hasAdventureModifier(caster, MARKER));
  }

  @Test
  void originalAcheronRemovalClearsOnlyItsNamedCasterMarker() throws Exception {
    try (var stream = getClass().getResourceAsStream("/fixtures/maze-modifier-removal-4.6.json")) {
      assertNotNull(stream);
      var row =
          JsonParser.parseReader(
                  new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonObject();
      assertFalse(row.get("completeAbilityChainVerified").getAsBoolean());
      assertFalse(row.get("completeModifierLifecycleVerified").getAsBoolean());
      var skill =
          fixture.parse(
              fixture.excel(), row.get("ability").getAsString(), row.getAsJsonObject("task"));
      skill.addAdventureModifier(MARKER);
      skill.addAdventureModifier("OtherMarker");
      var caster = new GameAvatar(fixture.excel());
      skill.onAttack(caster, List.of());
      assertFalse(skill.hasAdventureModifier(caster, MARKER));
      assertTrue(skill.hasAdventureModifier(caster, "OtherMarker"));
    }
  }
}
