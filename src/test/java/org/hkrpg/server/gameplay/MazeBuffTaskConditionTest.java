package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.NpcMonsterExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Two original effect leaves, not their outer random-value predicate or full technique. */
class MazeBuffTaskConditionTest {
  @TempDir Path root;
  final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();
  JsonArray rows;

  @BeforeEach
  void setup() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/maze-buff-condition-4.6.json")) {
      assertNotNull(in);
      rows =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
  }

  JsonObject task(int index) {
    return rows.get(index).getAsJsonObject().getAsJsonObject("task").deepCopy();
  }

  GameAvatar caster(int mask) {
    var caster = new GameAvatar(fixture.excel());
    if ((mask & 1) != 0) caster.addBuff(130402, -1);
    if ((mask & 2) != 0) caster.addBuff(130403, -1);
    return caster;
  }

  void effects(GameAvatar caster, boolean first, boolean second) {
    assertEquals(
        first,
        caster.getBuffs().containsKey(130401),
        "ByAnd effect violated its declared condition");
    assertEquals(
        second,
        caster.getBuffs().containsKey(130404),
        "ByAny effect violated its declared condition");
    if (first) assertEquals(Long.MAX_VALUE, caster.getBuffs().get(130401));
    if (second) assertEquals(Long.MAX_VALUE, caster.getBuffs().get(130404));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void unchangedSourceAndAnyLeavesSelectOnlyTheirApplicablePermanentEffect(int mask) {
    var caster = caster(mask);
    fixture
        .parse(fixture.excel(), "LeafFixture_MazeSkill", task(0), task(1))
        .onCast(caster, MotionInfo.newInstance());
    effects(caster, mask == 0, mask != 0);
    assertEquals((mask & 1) != 0, caster.getBuffs().containsKey(130402));
    assertEquals((mask & 2) != 0, caster.getBuffs().containsKey(130403));
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknownType", "unknownTarget"})
  void unsupportedConditionDoesNotBecomeAnUnconditionalEffect(String kind) {
    var task = task(0);
    var condition = task.getAsJsonObject("Condition");
    if (kind.equals("unknownType"))
      condition.addProperty("$type", "RPG.GameCore.ByCompareDynamicValue");
    else
      condition
          .getAsJsonArray("PredicateList")
          .get(0)
          .getAsJsonObject()
          .getAsJsonObject("TargetType")
          .addProperty("Alias", "ParamEntity");
    var caster = caster(0);
    fixture
        .parse(fixture.excel(), "LeafFixture_MazeSkill", task)
        .onCast(caster, MotionInfo.newInstance());
    effects(caster, false, false);
  }

  @Test
  void existingCompiledTasksReevaluateAfterCasterMarkersChange() {
    var caster = caster(0);
    var skill = fixture.parse(fixture.excel(), "LeafFixture_MazeSkill", task(0), task(1));
    skill.onCast(caster, MotionInfo.newInstance());
    effects(caster, true, false);
    caster.removeBuff(130401);
    caster.removeBuff(130404);
    caster.addBuff(130402, -1);
    skill.onCast(caster, MotionInfo.newInstance());
    effects(caster, false, true);
  }

  @Test
  void expiredMarkersDoNotSatisfySourceAnyCondition() {
    var caster = caster(0);
    caster.getBuffs().put(130402, System.currentTimeMillis() - 60_000);
    fixture
        .parse(fixture.excel(), "LeafFixture_MazeSkill", task(0), task(1))
        .onCast(caster, MotionInfo.newInstance());
    effects(caster, true, false);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void explicitCompositeInverseReversesTheWholeExpression(boolean active) {
    var caster = caster(active ? 1 : 0);
    var task = task(1);
    task.getAsJsonObject("Condition").addProperty("Inverse", true);
    fixture
        .parse(fixture.excel(), "LeafFixture_MazeSkill", task)
        .onCast(caster, MotionInfo.newInstance());
    effects(caster, false, !active);
  }

  EntityMonster monster() {
    var group = new GroupInfo();
    group.setId(199969);
    return new EntityMonster(
        null, new NpcMonsterExcel(), group, fixture.gson.fromJson("{\"ID\":1}", MonsterInfo.class));
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void sourceCasterTargetRemainsTheCasterInAttackAndHitCallbacks(boolean hit, boolean active) {
    var caster = caster(active ? 1 : 0);
    var monster = monster();
    var skill = fixture.parse(fixture.excel(), "LeafFixture_NormalAtk01", task(0), task(1));
    if (hit) skill.onCastHit(caster, List.of(monster));
    else skill.onAttack(caster, List.of(monster));
    effects(caster, !active, active);
    assertTrue(monster.getBuffs() == null || monster.getBuffs().isEmpty());
    assertTrue(monster.getTempBuffs() == null || monster.getTempBuffs().isEmpty());
  }

  @Test
  void anEmptyCompositeDefinitionIsRejectedUntilItsSourceSemanticsAreVerified() {
    var caster = caster(0);
    var task = task(0);
    task.getAsJsonObject("Condition").add("PredicateList", new JsonArray());
    fixture
        .parse(fixture.excel(), "LeafFixture_MazeSkill", task)
        .onCast(caster, MotionInfo.newInstance());
    effects(caster, false, false);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aSupportedCasterConditionAlsoGuardsTaskActionTargetSummonEffects(boolean active)
      throws Exception {
    var summons = new SummonTriggerBuffLifecycleTest();
    summons.root = root;
    summons.setup();
    try {
      var owner = summons.fixture.fixture.owner(false);
      if (active) owner.getCurrentLeaderAvatar().addBuff(130402, -1);
      var row = summons.fixture.rows.get(0).getAsJsonObject();
      var raw = row.getAsJsonObject("config").deepCopy();
      var add =
          raw.getAsJsonObject("TriggerConfig")
              .getAsJsonArray("CustomTriggers")
              .get(0)
              .getAsJsonObject()
              .getAsJsonArray("OnTriggerEnter")
              .get(0)
              .getAsJsonObject();
      // The source condition is grafted onto a separate source effect as a synthetic integration
      // case.
      add.add("Condition", task(1).getAsJsonObject("Condition"));
      var config = fixture.gson.fromJson(raw, SummonUnitInfo.class);
      config.buildMazeSkillActions();
      var excel =
          fixture.gson.fromJson(row.get("row"), emu.lunarcore.data.excel.SummonUnitExcel.class);
      excel.setInfo(config);
      owner
          .getScene()
          .summonUnit(
              owner.getCurrentLeaderAvatar(),
              excel,
              new emu.lunarcore.util.Position(),
              new emu.lunarcore.util.Position(),
              20);
      var unit = owner.getScene().getPlayerSummon();
      var monster = summons.monster(owner, 1);
      summons.trigger(owner, unit, monster);
      assertEquals(active, monster.getBuffs() != null && monster.getBuffs().containsKey(140202));
      owner.getScene().removeSummonUnit();
      assertTrue(monster.getBuffs() == null || !monster.getBuffs().containsKey(140202));
    } finally {
      summons.cleanup();
    }
  }
}
