package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.SkillAbilityInfo;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/**
 * Original ParamEntity branch and explicit surrounding fixtures; full Phainon skill is not
 * asserted.
 */
class MazeInstantKillBranchTest {
  @TempDir Path root;
  final MazeInstantKillPreflightTest base = new MazeInstantKillPreflightTest();
  JsonObject proof;
  static final int SUCCESS = 122001, FAILURE = 122002;

  @BeforeEach
  void prepare() throws Exception {
    base.root = root;
    base.prepare();
    try (var in = getClass().getResourceAsStream("/fixtures/maze-instant-kill-branch-4.6.json")) {
      assertNotNull(in);
      proof =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
  }

  @AfterEach
  void restore() throws Exception {
    base.restore();
  }

  JsonObject originalBranch() {
    return proof
        .getAsJsonObject("ability")
        .getAsJsonArray("OnStart")
        .get(11)
        .getAsJsonObject()
        .getAsJsonArray("OnAttack")
        .get(1)
        .getAsJsonObject()
        .deepCopy();
  }

  MazeSkill parse(JsonObject task) {
    return base.base.fixture.parse(base.base.fixture.excel(), "Fixture_NormalAtk", task);
  }

  MazeSkill markers(boolean inverse) {
    var branch = originalBranch();
    branch.getAsJsonObject("Predicate").addProperty("Inverse", inverse);
    var success = new JsonArray();
    success.add(base.base.fixture.add(SUCCESS, 20));
    var failure = new JsonArray();
    failure.add(base.base.fixture.add(FAILURE, 20));
    branch.add("SuccessTaskList", success);
    branch.add("FailedTaskList", failure);
    return parse(branch);
  }

  void attack(MazeSkill skill, EntityMonster... targets) {
    skill.onAttack(base.base.player.getCurrentLeaderAvatar(), Arrays.asList(targets));
  }

  void assertMarkers(boolean success, boolean failure) {
    var buffs = base.base.player.getCurrentLeaderAvatar().getBuffs();
    assertEquals(success, buffs.containsKey(SUCCESS));
    assertEquals(failure, buffs.containsKey(FAILURE));
  }

  @Test
  void unchangedOriginalBranchActuallyDefeatsEligibleTargetAndCreditsMissionOnce() {
    base.base.killGoals();
    var target = base.assembled("MinionLv2", "[]");
    var skill = parse(originalBranch());
    attack(skill, target, target);
    attack(skill, target);
    assertFalse(target.isAlive());
    assertEquals(1, base.base.drops);
    assertEquals(1, base.base.deaths);
    for (int id : List.of(199998711, 199998712, 199998713))
      assertEquals(1, base.base.player.getMissionManager().sub(id).getProgress());
  }

  @Test
  void completeOriginalAbilityLoadsItsOriginalAttackBranchWithoutRewritingOtherTasks() {
    var config = new JsonObject();
    var abilities = new JsonArray();
    abilities.add(proof.getAsJsonObject("ability"));
    config.add("AbilityList", abilities);
    var excel = base.base.fixture.excel();
    assertTrue(base.base.fixture.gson.fromJson(config, SkillAbilityInfo.class).parse(excel));
    var target = base.assembled("Minion", "[]");
    attack(excel.getMazeSkill(), target);
    assertFalse(
        target.isAlive(), "The original OnAttack[1] must no longer disappear during parsing");
    assertEquals(1, base.base.drops);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualBattleServiceDispatchesOriginalBranchInBattleAndNonBattleHitPaths(boolean battle) {
    var target = base.assembled("Minion", "[]");
    var skill = parse(originalBranch());
    skill.setTriggerBattle(battle); // Explicit callback fixture, not source summon lifecycle proof.
    var caster = base.base.player.getCurrentLeaderAvatar();
    new emu.lunarcore.game.battle.BattleService(null)
        .attack(
            base.base.player,
            caster.getEntityId(),
            0,
            skill,
            new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet(new int[] {target.getEntityId()}),
            new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet());
    assertFalse(target.isAlive());
    assertEquals(1, base.base.drops);
    assertEquals(1, base.base.deaths);
    assertNull(base.base.player.getBattle());
    assertEquals(
        1,
        base.base.player.sent.stream()
            .filter(p -> p instanceof emu.lunarcore.server.packet.send.PacketSceneCastSkillScRsp)
            .count());
  }

  @Test
  void successAndFailureLeavesReceiveOnlyTheirOwnSingleTarget() {
    var task = originalBranch();
    var yes = base.base.fixture.add(SUCCESS, 20);
    var no = base.base.fixture.add(FAILURE, 20);
    yes.getAsJsonObject("TargetType").addProperty("Alias", "ParamEntity");
    no.getAsJsonObject("TargetType").addProperty("Alias", "ParamEntity");
    var success = new JsonArray();
    success.add(yes);
    var failure = new JsonArray();
    failure.add(no);
    task.add("SuccessTaskList", success);
    task.add("FailedTaskList", failure);
    var eligible = base.assembled("Minion", "[]");
    var resistant = base.assembled("Minion", "[\"ResistInstantKill\"]");
    attack(parse(task), eligible, resistant);
    assertEquals(
        List.of(SUCCESS),
        eligible.getTempBuffs().stream()
            .map(emu.lunarcore.game.scene.SceneBuff::getBuffId)
            .toList());
    assertEquals(
        List.of(FAILURE),
        resistant.getTempBuffs().stream()
            .map(emu.lunarcore.game.scene.SceneBuff::getBuffId)
            .toList());
    assertMarkers(false, false);
  }

  @Test
  void anImpostorCasterCannotEvaluateAnInverseBranchOnOwnedTargets() {
    var actual = base.base.player.getCurrentLeaderAvatar();
    var impostor = new emu.lunarcore.game.avatar.GameAvatar(actual.getExcel());
    impostor.setOwner(base.base.player);
    impostor.setEntityId(actual.getEntityId());
    markers(true).onAttack(impostor, List.of(base.assembled("Minion", "[\"ResistInstantKill\"]")));
    assertMarkers(false, false);
  }

  @Test
  void aDeadCasterCannotEnterEitherTargetBranch() {
    var actor = base.base.player.getCurrentLeaderAvatar();
    actor.setCurrentHp(base.base.player.getCurrentLineup(), 0);
    attack(markers(true), base.assembled("Minion", "[\"ResistInstantKill\"]"));
    assertMarkers(false, false);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void predicateSelectsSeparateListsForEveryActualTarget(boolean inverse) {
    var eligible = base.assembled("Minion", "[]");
    var resistant = base.assembled("MinionLv2", "[\"ResistInstantKill\"]");
    attack(markers(inverse), eligible, resistant);
    assertMarkers(true, true);
    base.base.noEffects();
  }

  @ParameterizedTest
  @CsvSource({
    "Minion,[],true",
    "MinionLv2,[],true",
    "Elite,[],false",
    "LittleBoss,[],false",
    "BigBoss,[],false",
    "Minion,[ResistInstantKill],false"
  })
  void singleTargetUsesItsOwnEligibility(String rank, String tags, boolean eligible) {
    var target = base.assembled(rank, tags.replace("ResistInstantKill", "\"ResistInstantKill\""));
    attack(markers(false), target);
    assertMarkers(eligible, !eligible);
    assertTrue(target.isAlive());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void challengeResultIsKnownFalseAndInverseOnlyFlipsThatResult(boolean inverse) throws Exception {
    base.plane("Challenge");
    var target = base.assembled("Minion", "[]");
    attack(markers(inverse), target);
    assertMarkers(inverse, !inverse);
    base.base.noEffects();
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "[null]", "[\"UnknownTag\"]"})
  void unavailableTagsDoNotSelectEitherListEvenWhenInverseIsTrue(String tags) {
    attack(markers(true), base.assembled("Minion", tags));
    assertMarkers(false, false);
    base.base.noEffects();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Unknow", "missing"})
  void missingOrUnknownRankCannotTurnIntoAnInverseSuccess(String rank) {
    var target =
        base.base.monster(base.base.player.getScene(), rank.equals("missing") ? null : rank, true);
    attack(markers(true), target);
    assertMarkers(false, false);
  }

  @Test
  void missingTargetsAndCastCallbacksDoNotPretendToHaveParamEntity() {
    var skill = markers(true);
    var caster = base.base.player.getCurrentLeaderAvatar();
    skill.onAttack(caster, List.of());
    skill.onAttack(caster, null);
    skill.onCast(caster, MotionInfo.newInstance());
    assertMarkers(false, false);
  }

  @Test
  void unregisteredOrStaleIdentityDoesNotSelectAnInverseBranch() {
    var target = base.base.monster(base.base.player.getScene(), "Minion", false);
    attack(markers(true), target);
    assertMarkers(false, false);
    var registered = base.assembled("Minion", "[]");
    target.setEntityId(registered.getEntityId());
    attack(markers(true), target);
    assertMarkers(false, false);
    assertTrue(registered.isAlive());
  }

  @Test
  void reusedBranchReadsActualUpdatedTargetTagsAndCurrentScene() throws Exception {
    var skill = markers(false);
    attack(skill, base.assembled("Minion", "[]"));
    assertMarkers(true, false);
    base.base.player.getCurrentLeaderAvatar().getBuffs().clear();
    attack(skill, base.assembled("Minion", "[\"ResistInstantKill\"]"));
    assertMarkers(false, true);
    base.base.player.getCurrentLeaderAvatar().getBuffs().clear();
    base.plane("Challenge");
    attack(skill, base.assembled("Minion", "[]"));
    assertMarkers(false, true);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"Caster", "LocalPlayer", "ParamEntityList", "missing", "extra", "stringInverse"})
  void unverifiedSelectorsAndShapesStillSkipWholeBranch(String mode) {
    var task = originalBranch();
    var predicate = task.getAsJsonObject("Predicate");
    if (mode.equals("missing")) predicate.remove("TargetType");
    else if (mode.equals("extra")) predicate.addProperty("UnknownField", 1);
    else if (mode.equals("stringInverse")) predicate.addProperty("Inverse", "true");
    else predicate.getAsJsonObject("TargetType").addProperty("Alias", mode);
    var target = base.assembled("Minion", "[]");
    attack(parse(task), target);
    assertTrue(target.isAlive());
    base.base.noEffects();
  }
}
