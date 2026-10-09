package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Source OnStage task is invoked explicitly; no modifier lifecycle dispatcher is inferred. */
class MazeMpPredicateTest {
  @TempDir Path root;
  final MazeCasterBuffPredicateTest fixture = new MazeCasterBuffPredicateTest();
  JsonObject source;

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    try (var input = getClass().getResourceAsStream("/fixtures/maze-mp-predicate-4.6.json")) {
      assertNotNull(input);
      source =
          JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
              .getAsJsonObject()
              .getAsJsonObject("task");
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 5})
  void unchangedSourceTaskDestroysOnlyWhenCurrentMpSatisfiesItsComparison(int mp) throws Exception {
    var owner = fixture.fixture.fixture.owner(false);
    var unit = fixture.summon(owner);
    owner.getCurrentLineup().setMp(mp);
    fixture
        .fixture
        .parse(source.deepCopy())
        .onCast(owner.getCurrentLeaderAvatar(), fixture.fixture.motion());
    if (mp == 0) assertNull(owner.getScene().getPlayerSummon());
    else
      assertSame(
          unit,
          owner.getScene().getPlayerSummon(),
          "Source MP branch executed without its comparison");
    assertEquals(mp, owner.getCurrentLineup().getMp());
  }

  JsonObject branch(JsonObject predicate) {
    var task = new JsonObject();
    task.addProperty("$type", "RPG.GameCore.PredicateTaskList");
    task.add("Predicate", predicate);
    var success = new JsonArray();
    success.add(fixture.fixture.fixture.add(130401, -1));
    task.add("SuccessTaskList", success);
    var failure = new JsonArray();
    failure.add(fixture.fixture.fixture.add(130404, -1));
    task.add("FailedTaskList", failure);
    return task;
  }

  JsonObject compare(String kind, JsonElement threshold) {
    var predicate = new JsonObject();
    predicate.addProperty("$type", "RPG.GameCore.AdventureByCompareMP");
    predicate.addProperty("CompareType", kind);
    if (threshold != null) predicate.add("CompareValue", threshold);
    return predicate;
  }

  @ParameterizedTest
  @CsvSource({
    "Greater,2,false",
    "Greater,1,true",
    "GreaterEqual,2,true",
    "GreaterEqual,3,false",
    "NotEqual,2,false",
    "NotEqual,3,true",
    "Equal,2,true",
    "Equal,3,false",
    "LessEqual,2,true",
    "LessEqual,1,false",
    "Less,2,false",
    "Less,3,true"
  })
  void fixedFloatThresholdsSelectTheCorrectBranch(String type, float threshold, boolean success)
      throws Exception {
    var owner = fixture.fixture.fixture.owner(false);
    owner.getCurrentLineup().setMp(2);
    var caster = owner.getCurrentLeaderAvatar();
    fixture
        .fixture
        .parse(branch(compare(type, new JsonPrimitive(threshold))))
        .onCast(caster, MotionInfo.newInstance());
    assertEquals(success, caster.getBuffs().containsKey(130401));
    assertEquals(!success, caster.getBuffs().containsKey(130404));
  }

  @Test
  void reusedSourceBranchRechecksMpRatherThanFreezingItsFirstDecision() throws Exception {
    var owner = fixture.fixture.fixture.owner(false);
    var unit = fixture.summon(owner);
    var skill = fixture.fixture.parse(source.deepCopy());
    skill.onCast(owner.getCurrentLeaderAvatar(), fixture.fixture.motion());
    assertSame(unit, owner.getScene().getPlayerSummon());
    owner.getCurrentLineup().setMp(0);
    skill.onCast(owner.getCurrentLeaderAvatar(), fixture.fixture.motion());
    assertNull(owner.getScene().getPlayerSummon());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void inverseChangesTheResolvedMpComparison(int mp) throws Exception {
    var owner = fixture.fixture.fixture.owner(false);
    var unit = fixture.summon(owner);
    owner.getCurrentLineup().setMp(mp);
    var task = source.deepCopy();
    task.getAsJsonObject("Predicate").addProperty("Inverse", true);
    fixture.fixture.parse(task).onCast(owner.getCurrentLeaderAvatar(), fixture.fixture.motion());
    if (mp == 0) assertSame(unit, owner.getScene().getPlayerSummon());
    else assertNull(owner.getScene().getPlayerSummon());
  }

  @ParameterizedTest
  @CsvSource({"0,5", "3,0"})
  void storyPartyUsesItsOwnSavedMpRatherThanTheOrdinaryParty(int storyMp, int normalMp)
      throws Exception {
    var owner = fixture.fixture.fixture.owner(true);
    var unit = fixture.summon(owner);
    owner.getLineupManager().getLineups()[0].setMp(normalMp);
    owner.getCurrentLineup().setMp(storyMp);
    assertEquals(normalMp, owner.getLineupManager().getMp());
    fixture
        .fixture
        .parse(source.deepCopy())
        .onCast(owner.getCurrentLeaderAvatar(), fixture.fixture.motion());
    if (storyMp == 0) assertNull(owner.getScene().getPlayerSummon());
    else assertSame(unit, owner.getScene().getPlayerSummon());
    assertEquals(storyMp, owner.getCurrentLineup().getMp());
    assertEquals(normalMp, owner.getLineupManager().getMp());
  }

  @Test
  void compareValueUsesNativeFloatPrecisionAndAcceptsFractionalThresholds() throws Exception {
    var owner = fixture.fixture.fixture.owner(false);
    owner.getCurrentLineup().setMp(2);
    var caster = owner.getCurrentLeaderAvatar();
    var predicate = compare("Equal", new JsonPrimitive(2.00000003));
    fixture.fixture.parse(branch(predicate)).onCast(caster, MotionInfo.newInstance());
    assertTrue(caster.getBuffs().containsKey(130401));
    caster.getBuffs().remove(130401);
    predicate = compare("Less", new JsonPrimitive(2.5));
    fixture.fixture.parse(branch(predicate)).onCast(caster, MotionInfo.newInstance());
    assertTrue(caster.getBuffs().containsKey(130401));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void attackAndHitCallbacksAlsoReadCurrentMp(boolean hit) throws Exception {
    var owner = fixture.fixture.fixture.owner(false);
    var unit = fixture.summon(owner);
    owner.getCurrentLineup().setMp(2);
    var skill =
        fixture.fixture.fixture.parse(
            fixture.fixture.fixture.excel(), "MpFixture_NormalAtk01", source.deepCopy());
    if (hit) skill.onCastHit(owner.getCurrentLeaderAvatar(), List.of());
    else skill.onAttack(owner.getCurrentLeaderAvatar(), List.of());
    assertSame(unit, owner.getScene().getPlayerSummon());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"unknown", "missing", "string", "expression", "null", "infinite", "selector"})
  void unavailableOrInvalidComparisonNeverBecomesUnconditionalSuccess(String kind)
      throws Exception {
    var owner = fixture.fixture.fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    var predicate = compare("LessEqual", new JsonPrimitive(0));
    switch (kind) {
      case "unknown" -> predicate.addProperty("CompareType", "Unknow");
      case "missing" -> predicate.remove("CompareType");
      case "string" -> predicate.addProperty("CompareValue", "0");
      case "expression" ->
          predicate.add("CompareValue", JsonParser.parseString("{\"IsDynamic\":true}"));
      case "null" -> predicate.add("CompareValue", JsonNull.INSTANCE);
      case "infinite" -> predicate.add("CompareValue", JsonParser.parseString("1e1000"));
      case "selector" -> predicate.addProperty("TargetType", "Caster");
    }
    fixture.fixture.parse(branch(predicate)).onCast(caster, MotionInfo.newInstance());
    assertFalse(caster.getBuffs().containsKey(130401));
    assertFalse(caster.getBuffs().containsKey(130404));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unavailablePlayerContextDoesNotSelectEitherBranchIncludingInverse(boolean inverse) {
    var caster = new GameAvatar(fixture.fixture.fixture.excel());
    var predicate = compare("LessEqual", null);
    predicate.addProperty("Inverse", inverse);
    fixture.fixture.parse(branch(predicate)).onCast(caster, MotionInfo.newInstance());
    assertTrue(caster.getBuffs().isEmpty());
  }

  @Test
  void mixedBuffAndMpTreePreservesBothConditionsAndAnInvalidMpStateSkipsBothBranches()
      throws Exception {
    var owner = fixture.fixture.fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    var predicate = new JsonObject();
    predicate.addProperty("$type", "RPG.GameCore.ByAnd");
    var list = new JsonArray();
    list.add(compare("LessEqual", null));
    list.add(
        JsonParser.parseString(
            "{\"$type\":\"RPG.GameCore.ByCompareCarryMazebuff\",\"BuffID\":150601,\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"}}"));
    predicate.add("PredicateList", list);
    var skill = fixture.fixture.parse(branch(predicate));
    caster.addBuff(150601, -1);
    owner.getCurrentLineup().setMp(1);
    skill.onCast(caster, MotionInfo.newInstance());
    assertFalse(caster.getBuffs().containsKey(130401));
    assertTrue(caster.getBuffs().containsKey(130404));
    caster.getBuffs().remove(130404);
    owner.getCurrentLineup().setMp(0);
    skill.onCast(caster, MotionInfo.newInstance());
    assertTrue(caster.getBuffs().containsKey(130401));
    caster.getBuffs().remove(130401);
    owner.getCurrentLineup().setMp(-1);
    skill.onCast(caster, MotionInfo.newInstance());
    assertFalse(caster.getBuffs().containsKey(130401));
    assertFalse(caster.getBuffs().containsKey(130404));
  }
}
