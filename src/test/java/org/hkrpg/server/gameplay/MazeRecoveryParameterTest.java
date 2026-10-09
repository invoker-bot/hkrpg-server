package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.data.excel.AvatarSkillParameterExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Source recovery leaves are invoked in isolation; this is not full ability or client acceptance.
 */
class MazeRecoveryParameterTest {
  @TempDir Path root;
  final Gson gson = ResourceLoader.getGson();
  final MazeCasterBuffRemovalTest parser = new MazeCasterBuffRemovalTest();
  final MazeCasterBuffRemovalTest.Capture owner = new MazeCasterBuffRemovalTest.Capture();
  List<AvatarSkillParameterExcel> before;
  JsonArray rows;
  JsonArray actions;

  @BeforeEach
  void setup() throws Exception {
    before = new ArrayList<>(GameData.getAvatarSkillParameterExcelMap().values());
    GameData.getAvatarSkillParameterExcelMap().values().clear();
    try (var in = getClass().getResourceAsStream("/fixtures/avatar-skill-parameters-4.6.json")) {
      assertNotNull(in);
      rows =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    try (var in = getClass().getResourceAsStream("/fixtures/maze-recovery-4.6.json")) {
      assertNotNull(in);
      actions =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    for (var element : rows) {
      var row = element.getAsJsonObject().getAsJsonObject("row");
      if (row.has("SkillID"))
        GameData.getAvatarSkillParameterExcelMap()
            .add(gson.fromJson(row, AvatarSkillParameterExcel.class));
    }
  }

  @AfterEach
  void restore() {
    GameData.getAvatarSkillParameterExcelMap().values().clear();
    before.forEach(GameData.getAvatarSkillParameterExcelMap()::add);
  }

  JsonObject avatar(int id) {
    for (var element : rows) {
      var row = element.getAsJsonObject().getAsJsonObject("row");
      if (row.has("AvatarID") && row.get("AvatarID").getAsInt() == id) return row.deepCopy();
    }
    throw new AssertionError("Original avatar row missing");
  }

  GameAvatar caster(JsonObject raw) {
    var excel = gson.fromJson(raw, AvatarExcel.class);
    var caster =
        new GameAvatar(excel) {
          @Override
          public void save() {}
        };
    caster.setOwner(owner);
    owner.getAvatars().getAvatars().put(caster.getAvatarId(), caster);
    owner.getCurrentLineup().getAvatars().add(caster.getAvatarId());
    caster.setCurrentHp(owner.getCurrentLineup(), 2000);
    return caster;
  }

  JsonObject task(int id) {
    return actions.get(id == 8002 ? 1 : 0).getAsJsonObject().getAsJsonObject("task").deepCopy();
  }

  String name(int id) {
    return id == 8002 ? "LocalPlayer_PlayerGirl_MazeSkill" : "LocalPlayer_PlayerBoy_MazeSkill";
  }

  void cast(GameAvatar caster, JsonObject task) {
    var skill = parser.parse(caster.getExcel(), name(caster.getExcel().getAvatarID()), task);
    skill.onCast(caster, MotionInfo.newInstance());
  }

  void replaceParameter(int skillId, String field, JsonElement value) {
    var current = GameData.getAvatarSkillParameterExcelMap().get(skillId, 1);
    var row = gson.toJsonTree(current).getAsJsonObject();
    row.add(field, value);
    GameData.getAvatarSkillParameterExcelMap()
        .add(gson.fromJson(row, AvatarSkillParameterExcel.class));
  }

  @ParameterizedTest
  @ValueSource(ints = {8001, 8002})
  void originalHpRecoveryLeafUsesItsSourceSkillParameter(int id) {
    var caster = caster(avatar(id));
    cast(caster, task(id));
    assertEquals(3500, caster.getCurrentHp(owner.getCurrentLineup()));
  }

  @ParameterizedTest
  @ValueSource(ints = {8001, 8002})
  void aParameterChangeIsReadAtCastWithoutRecompilingTheAbility(int id) {
    var caster = caster(avatar(id));
    var skill = parser.parse(caster.getExcel(), name(id), task(id));
    replaceParameter(id * 100 + 7, "ParamList", JsonParser.parseString("[{\"Value\":0.22}]"));
    skill.onCast(caster, MotionInfo.newInstance());
    assertEquals(
        4200,
        caster.getCurrentHp(owner.getCurrentLineup()),
        "Recovery still uses the hard-coded 15 percent");
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, 0.125, 0.37, 2})
  void anExplicitLiteralRatioUsesTheDeclaredAmountAndCapsAtFullHealth(double ratio) {
    var caster = caster(avatar(8001));
    var task = task(8001);
    task.add("AddRatio", JsonParser.parseString("{\"FixedValue\":{\"Value\":" + ratio + "}}"));
    cast(caster, task);
    assertEquals(
        Math.min(10000, 2000 + (int) (ratio * 10000)),
        caster.getCurrentHp(owner.getCurrentLineup()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missingTable",
        "missingParameter",
        "wrongTrigger",
        "wrongAttackType",
        "notInSkillList",
        "wrongHash",
        "extraHash",
        "wrongOpcode",
        "unexpectedAbility",
        "otherAvatar",
        "otherTarget",
        "unsupportedFunction",
        "negativeRatio",
        "invalidRatio",
        "missingRatio",
        "clientOnly",
        "customTask"
      })
  void anUnresolvedOrUnsupportedRecoveryCannotBecomeTheOldDefault(String mode) {
    var raw = avatar(8001);
    if (mode.equals("notInSkillList")) raw.add("SkillList", new JsonArray());
    if (mode.equals("otherAvatar")) raw.addProperty("AvatarID", 8003);
    var caster = caster(raw);
    var task = task(8001);
    if (mode.equals("missingTable")) GameData.getAvatarSkillParameterExcelMap().values().clear();
    if (mode.equals("missingParameter")) replaceParameter(800107, "ParamList", new JsonArray());
    if (mode.equals("wrongTrigger"))
      replaceParameter(800107, "SkillTriggerKey", new JsonPrimitive("Skill01"));
    if (mode.equals("wrongAttackType"))
      replaceParameter(800107, "AttackType", new JsonPrimitive("Normal"));
    if (mode.equals("wrongHash"))
      task.getAsJsonObject("AddRatio")
          .getAsJsonObject("PostfixExpr")
          .getAsJsonArray("DynamicHashes")
          .set(0, new JsonPrimitive(457361642));
    if (mode.equals("extraHash"))
      task.getAsJsonObject("AddRatio")
          .getAsJsonObject("PostfixExpr")
          .getAsJsonArray("DynamicHashes")
          .add(-850066311);
    if (mode.equals("wrongOpcode"))
      task.getAsJsonObject("AddRatio")
          .getAsJsonObject("PostfixExpr")
          .addProperty("OpCodes", "AQAS");
    if (mode.equals("otherTarget"))
      task.add(
          "SpecifyTargetType",
          JsonParser.parseString("{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"}"));
    if (mode.equals("unsupportedFunction")) task.addProperty("ModifyFunction", "Set");
    if (mode.equals("negativeRatio"))
      task.add("AddRatio", JsonParser.parseString("{\"FixedValue\":{\"Value\":-0.5}}"));
    if (mode.equals("invalidRatio"))
      task.add("AddRatio", JsonParser.parseString("{\"FixedValue\":{\"Value\":\"0.15\"}}"));
    if (mode.equals("missingRatio")) task.remove("AddRatio");
    if (mode.equals("clientOnly")) task.addProperty("IsClientOnly", true);
    if (mode.equals("customTask"))
      task.addProperty("$type", "RPG.GameCore.CustomAdventureModifyTeamPlayerHP");
    var skill =
        parser.parse(
            caster.getExcel(),
            mode.equals("unexpectedAbility") ? "Other_MazeSkill" : name(8001),
            task);
    skill.onCast(caster, MotionInfo.newInstance());
    assertEquals(2000, caster.getCurrentHp(owner.getCurrentLineup()));
    assertTrue(owner.sent.isEmpty());
  }

  @Test
  void aTeamRecoveryDoesNotReviveTheDeadOrTouchSomeoneOutsideTheCurrentLineup() {
    var caster = caster(avatar(8001));
    var dead = caster(avatar(8002));
    dead.setCurrentHp(owner.getCurrentLineup(), 0);
    var reserve = caster(avatar(1202));
    owner.getCurrentLineup().getAvatars().remove(Integer.valueOf(reserve.getAvatarId()));
    cast(caster, task(8001));
    assertEquals(3500, caster.getCurrentHp(owner.getCurrentLineup()));
    assertEquals(0, dead.getCurrentHp(owner.getCurrentLineup()));
    assertEquals(2000, reserve.getCurrentHp(owner.getCurrentLineup()));
    assertEquals(1, owner.sent.size());
  }

  @Test
  void anOriginalRecoveryLeafUpdatesOnlyTheCurrentRogueVitals() {
    var caster = caster(avatar(8001));
    var normal = owner.getCurrentLineup();
    var run = owner.getGameplay().getRogue();
    run.getRoster().add(caster.getAvatarId());
    run.getVitals().put(caster.getAvatarId(), new GameplayState.RogueVitals(2000, 568));
    run.setInside(true);
    var current = owner.getCurrentLineup();
    assertInstanceOf(RogueLineup.class, current);
    cast(caster, task(8001));
    assertEquals(3500, run.getVitals().get(caster.getAvatarId()).getHp());
    assertEquals(568, run.getVitals().get(caster.getAvatarId()).getSp());
    assertEquals(2000, caster.getCurrentHp(normal));
    assertTrue(owner.saves > 0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aConstructedLiteralRecoveryRunsForTheRequestedTrialActorInBothHandlers(boolean nativePath)
      throws Exception {
    var scene = new SceneSkillCasterTest();
    scene.root = root;
    scene.setup();
    try {
      var owner = scene.base.started(false);
      var requested = owner.getAvatarById(StoryLineLifecycleTest.SPECIAL + 1);
      var leader = owner.getCurrentLeaderAvatar();
      var lineup = owner.getCurrentLineup();
      leader.setCurrentHp(lineup, 2000);
      requested.setCurrentHp(lineup, 3300);
      var literal = new JsonObject();
      literal.addProperty("$type", "RPG.GameCore.AdventureModifyTeamPlayerHP");
      literal.add("AddRatio", JsonParser.parseString("{\"FixedValue\":{\"Value\":0.22}}"));
      parser.parse(requested.getExcel(), "RequestedRecovery_MazeSkill", literal);
      var session = scene.cast(nativePath);
      assertEquals(nativePath, session.attackAttempted);
      assertEquals(4, lineup.getMp());
      assertEquals(4200, leader.getCurrentHp(lineup));
      assertEquals(5500, requested.getCurrentHp(lineup));
      assertEquals(0, lineup.getLeader());
    } finally {
      scene.cleanup();
    }
  }
}
