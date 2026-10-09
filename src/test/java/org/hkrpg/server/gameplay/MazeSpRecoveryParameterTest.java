package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Scoped source SP leaf; absolute-to-normalized conversion is not complete client acceptance. */
class MazeSpRecoveryParameterTest {
  final MazeRecoveryParameterTest fixture = new MazeRecoveryParameterTest();

  @BeforeEach
  void setup() throws Exception {
    fixture.setup();
  }

  @AfterEach
  void restore() {
    fixture.restore();
  }

  JsonObject task() {
    return fixture.actions.get(2).getAsJsonObject().getAsJsonObject("task").deepCopy();
  }

  GameAvatar caster(JsonObject row) {
    var caster = fixture.caster(row);
    caster.setCurrentSp(fixture.owner.getCurrentLineup(), 1000);
    return caster;
  }

  void cast(GameAvatar caster, JsonObject task) {
    var skill = fixture.parser.parse(caster.getExcel(), "LocalPlayer_TingYun_MazeSkill", task);
    skill.onCast(caster, MotionInfo.newInstance());
  }

  @Test
  void unchangedTingyunLeafRestoresFiftyAbsoluteEnergyAgainstItsSourceCapacityOf130() {
    var caster = caster(fixture.avatar(1202));
    var other = caster(fixture.avatar(8001));
    cast(caster, task());
    var lineup = fixture.owner.getCurrentLineup();
    assertEquals(
        4846, caster.getCurrentSp(lineup), "50 absolute energy was mistaken for 50 percent");
    assertEquals(1000, other.getCurrentSp(lineup));
    assertEquals(2000, caster.getCurrentHp(lineup));
    assertEquals(1, fixture.owner.sent.size());
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, 30, 50, 130, 1000000})
  void changedSkillParametersAreReadAtCastAndCappedBeforeIntegerConversion(double amount) {
    var caster = caster(fixture.avatar(1202));
    var skill = fixture.parser.parse(caster.getExcel(), "LocalPlayer_TingYun_MazeSkill", task());
    fixture.replaceParameter(
        120207, "ParamList", JsonParser.parseString("[{\"Value\":" + amount + "}]"));
    skill.onCast(caster, MotionInfo.newInstance());
    assertEquals(
        Math.min(10000, 1000 + (int) (Math.min(1, amount / 130) * 10000)),
        caster.getCurrentSp(fixture.owner.getCurrentLineup()));
    assertEquals(amount > 0 ? 1 : 0, fixture.owner.sent.size());
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, 26, 50, 130})
  void anExplicitLiteralUsesTheSameAbsoluteEnergyUnits(double amount) {
    var caster = caster(fixture.avatar(1202));
    var task = task();
    task.add("ModifyValue", JsonParser.parseString("{\"FixedValue\":{\"Value\":" + amount + "}}"));
    cast(caster, task);
    assertEquals(
        Math.min(10000, 1000 + (int) (Math.min(1, amount / 130) * 10000)),
        caster.getCurrentSp(fixture.owner.getCurrentLineup()));
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
        "otherAvatar",
        "wrongAbility",
        "missingTarget",
        "otherTarget",
        "extraTargetField",
        "unsupportedFunction",
        "negativeValue",
        "invalidValue",
        "missingValue",
        "additionalRatio",
        "missingCapacity",
        "zeroCapacity",
        "negativeCapacity",
        "stringCapacity",
        "infiniteCapacity",
        "clientOnly",
        "customTask"
      })
  void anUnresolvedEnergyOrTargetCannotBecomeTheOldCasterDefault(String mode) {
    var raw = fixture.avatar(1202);
    if (mode.equals("notInSkillList")) raw.add("SkillList", new JsonArray());
    if (mode.equals("otherAvatar")) raw.addProperty("AvatarID", 1203);
    if (mode.equals("missingCapacity")) raw.remove("SPNeed");
    if (mode.equals("zeroCapacity")) raw.add("SPNeed", JsonParser.parseString("{\"Value\":0}"));
    if (mode.equals("negativeCapacity"))
      raw.add("SPNeed", JsonParser.parseString("{\"Value\":-130}"));
    if (mode.equals("stringCapacity"))
      raw.add("SPNeed", JsonParser.parseString("{\"Value\":\"130\"}"));
    if (mode.equals("infiniteCapacity"))
      raw.add("SPNeed", JsonParser.parseString("{\"Value\":1e400}"));
    var caster = caster(raw);
    var task = task();
    if (mode.equals("missingTable")) GameData.getAvatarSkillParameterExcelMap().values().clear();
    if (mode.equals("missingParameter"))
      fixture.replaceParameter(120207, "ParamList", new JsonArray());
    if (mode.equals("wrongTrigger"))
      fixture.replaceParameter(120207, "SkillTriggerKey", new JsonPrimitive("Skill01"));
    if (mode.equals("wrongAttackType"))
      fixture.replaceParameter(120207, "AttackType", new JsonPrimitive("Normal"));
    if (mode.equals("wrongHash"))
      task.getAsJsonObject("ModifyValue")
          .getAsJsonObject("PostfixExpr")
          .getAsJsonArray("DynamicHashes")
          .set(0, new JsonPrimitive(-850066311));
    if (mode.equals("extraHash"))
      task.getAsJsonObject("ModifyValue")
          .getAsJsonObject("PostfixExpr")
          .getAsJsonArray("DynamicHashes")
          .add(457361642);
    if (mode.equals("wrongOpcode"))
      task.getAsJsonObject("ModifyValue")
          .getAsJsonObject("PostfixExpr")
          .addProperty("OpCodes", "AQAS");
    if (mode.equals("missingTarget")) task.remove("SpecifyTargetType");
    if (mode.equals("otherTarget"))
      task.getAsJsonObject("SpecifyTargetType").addProperty("Alias", "AdvLineup");
    if (mode.equals("extraTargetField"))
      task.getAsJsonObject("SpecifyTargetType").addProperty("Other", 1);
    if (mode.equals("unsupportedFunction")) task.addProperty("ModifyFunction", "Set");
    if (mode.equals("negativeValue"))
      task.add("ModifyValue", JsonParser.parseString("{\"FixedValue\":{\"Value\":-50}}"));
    if (mode.equals("invalidValue"))
      task.add("ModifyValue", JsonParser.parseString("{\"FixedValue\":{\"Value\":\"50\"}}"));
    if (mode.equals("missingValue")) task.remove("ModifyValue");
    if (mode.equals("additionalRatio"))
      task.add("AddRatio", JsonParser.parseString("{\"FixedValue\":{\"Value\":0.5}}"));
    if (mode.equals("clientOnly")) task.addProperty("IsClientOnly", true);
    if (mode.equals("customTask"))
      task.addProperty("$type", "RPG.GameCore.CustomAdventureModifyTeamPlayerSP");
    var skill =
        fixture.parser.parse(
            caster.getExcel(),
            mode.equals("wrongAbility") ? "Other_MazeSkill" : "LocalPlayer_TingYun_MazeSkill",
            task);
    skill.onCast(caster, MotionInfo.newInstance());
    assertEquals(1000, caster.getCurrentSp(fixture.owner.getCurrentLineup()));
    assertTrue(fixture.owner.sent.isEmpty());
  }

  @Test
  void aFullEnergyBarDoesNotProduceAnotherWriteOrNotification() {
    var caster = caster(fixture.avatar(1202));
    caster.setCurrentSp(fixture.owner.getCurrentLineup(), 10000);
    cast(caster, task());
    assertEquals(10000, caster.getCurrentSp(fixture.owner.getCurrentLineup()));
    assertTrue(fixture.owner.sent.isEmpty());
  }

  @Test
  void recoveryWritesOnlyTheCurrentRogueEnergyAndLeavesNormalEnergyAndHpAlone() {
    var caster = caster(fixture.avatar(1202));
    var normal = fixture.owner.getCurrentLineup();
    caster.setCurrentSp(normal, 17);
    var run = fixture.owner.getGameplay().getRogue();
    run.getRoster().add(caster.getAvatarId());
    run.getVitals().put(caster.getAvatarId(), new GameplayState.RogueVitals(3456, 1000));
    run.setInside(true);
    cast(caster, task());
    assertEquals(4846, run.getVitals().get(caster.getAvatarId()).getSp());
    assertEquals(3456, run.getVitals().get(caster.getAvatarId()).getHp());
    assertEquals(17, caster.getCurrentSp(normal));
    assertTrue(fixture.owner.saves > 0);
    assertEquals(1, fixture.owner.sent.size());
  }
}
