package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.avatar.GameAvatar;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original creation leaves under a constructed caster-effect branch, not the original ability. */
class MazeConditionalSummonPolicyTest {
  @TempDir Path root;
  final MazeSummonActionTest fixture = new MazeSummonActionTest();

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    fixture.install(fixture.excel(0).getId(), fixture.excel(0));
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
  }

  JsonObject branch(boolean failed) {
    var task = new JsonObject();
    task.addProperty("$type", "RPG.GameCore.PredicateTaskList");
    task.add(
        "Predicate",
        JsonParser.parseString(
            "{\"$type\":\"RPG.GameCore.ByCompareCarryMazebuff\",\"BuffID\":130402,\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"}}"));
    var children = new JsonArray();
    children.add(fixture.task(0));
    task.add(failed ? "FailedTaskList" : "SuccessTaskList", children);
    return task;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void onlyASelectedSupportedCreationBranchChangesThisCastPolicy(boolean marked) throws Exception {
    var owner = fixture.fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    if (marked) caster.addBuff(130402, -1);
    var skill = fixture.parse(branch(false));
    assertTrue(skill.isTriggerBattle(), "Conditional creation changed shared skill metadata");
    skill.onCast(caster, fixture.motion());
    assertEquals(!marked, skill.isTriggerBattle(caster));
    assertEquals(marked, fixture.current(owner) != null);
    if (marked) assertSame(caster, fixture.current(owner).getCaster());
  }

  @Test
  void anotherCasterCannotInheritTheEarlierCreationDecision() throws Exception {
    var owner = fixture.fixture.owner(false);
    var first = owner.getCurrentLeaderAvatar();
    var second = new GameAvatar(first.getExcel());
    second.setOwner(owner);
    first.addBuff(130402, -1);
    var skill = fixture.parse(branch(false));
    skill.onCast(first, fixture.motion());
    var summon = fixture.current(owner);
    assertNotNull(summon);
    skill.onCast(second, fixture.motion());
    assertFalse(skill.isTriggerBattle(first));
    assertTrue(skill.isTriggerBattle(second));
    assertSame(summon, fixture.current(owner));
    assertSame(first, summon.getCaster());
  }

  @Test
  void aLaterUnselectedCastRestoresTheDefaultWithoutDestroyingTheEarlierSummon() throws Exception {
    var owner = fixture.fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    caster.addBuff(130402, -1);
    var skill = fixture.parse(branch(false));
    skill.onCast(caster, fixture.motion());
    var summon = fixture.current(owner);
    assertNotNull(summon);
    caster.getBuffs().remove(130402);
    skill.onCast(caster, fixture.motion());
    assertTrue(skill.isTriggerBattle(caster));
    assertSame(summon, fixture.current(owner));
  }

  @Test
  void creationInTheFailureListDoesNotChangeTheOtherBranchPolicy() throws Exception {
    var owner = fixture.fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    var skill = fixture.parse(branch(true));
    skill.onCast(caster, fixture.motion());
    assertFalse(skill.isTriggerBattle(caster));
    assertNotNull(fixture.current(owner));
    caster.addBuff(130402, -1);
    skill.onCast(caster, fixture.motion());
    assertTrue(skill.isTriggerBattle(caster));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missingDuration",
        "dynamicDuration",
        "otherTarget",
        "missingRow",
        "clientDefinition",
        "missingDefinition",
        "clientOnlyTask",
        "unknownTaskType"
      })
  void aSkippedCreationTaskCannotSuppressBattleOrRegisterAnAction(String mode) {
    var task = fixture.task(0);
    if (mode.equals("missingDuration")) task.remove("Duration");
    if (mode.equals("dynamicDuration"))
      task.add(
          "Duration", JsonParser.parseString("{\"IsDynamic\":true,\"PostfixExpr\":\"_Duration\"}"));
    if (mode.equals("otherTarget"))
      task.getAsJsonObject("PosTarget").addProperty("Alias", "ParamEntity");
    if (mode.equals("missingRow")) fixture.install(fixture.excel(0).getId(), null);
    if (mode.equals("clientDefinition")) {
      fixture.install(fixture.excel(2).getId(), fixture.excel(2));
      task.addProperty("SummonUnitID", fixture.excel(2).getId());
    }
    if (mode.equals("missingDefinition")) {
      var excel =
          fixture.fixture.gson.fromJson(
              fixture.rows.get(0).getAsJsonObject().get("row"),
              emu.lunarcore.data.excel.SummonUnitExcel.class);
      assertNull(excel.getInfo());
      fixture.install(excel.getId(), excel);
    }
    if (mode.equals("clientOnlyTask")) task.addProperty("IsClientOnly", true);
    if (mode.equals("unknownTaskType"))
      task.addProperty("$type", "RPG.GameCore.CustomCreateSummonUnit");
    var skill = fixture.parse(task);
    assertTrue(skill.isTriggerBattle(), "Skipped creation task changed the skill's battle policy");
    assertTrue(skill.getCastActions().isEmpty());
  }
}
