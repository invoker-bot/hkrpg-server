package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.data.excel.AvatarSkillParameterExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Six scoped source leaves and inferred bindings; not full ability or native interpreter
 * acceptance.
 */
class MazeBuffParameterTest {
  final Gson gson = ResourceLoader.getGson();
  final MazeCasterBuffRemovalTest parser = new MazeCasterBuffRemovalTest();
  List<AvatarSkillParameterExcel> before;
  JsonArray fixture;

  @BeforeEach
  void setup() throws Exception {
    before = new ArrayList<>(GameData.getAvatarSkillParameterExcelMap().values());
    GameData.getAvatarSkillParameterExcelMap().values().clear();
    try (var in = getClass().getResourceAsStream("/fixtures/maze-buff-parameters-4.6.json")) {
      assertNotNull(in);
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    for (var value : fixture) {
      var row = value.getAsJsonObject();
      if ("skill".equals(row.get("kind").getAsString()))
        GameData.getAvatarSkillParameterExcelMap()
            .add(gson.fromJson(row.get("row"), AvatarSkillParameterExcel.class));
    }
  }

  @AfterEach
  void restore() {
    GameData.getAvatarSkillParameterExcelMap().values().clear();
    before.forEach(GameData.getAvatarSkillParameterExcelMap()::add);
  }

  JsonObject record(int id, String kind) {
    for (var value : fixture) {
      var row = value.getAsJsonObject();
      if (row.get("avatarId").getAsInt() == id && kind.equals(row.get("kind").getAsString()))
        return row;
    }
    throw new AssertionError("Missing original record");
  }

  GameAvatar caster(int id) {
    return new GameAvatar(gson.fromJson(record(id, "avatar").get("row"), AvatarExcel.class));
  }

  MazeSkill skill(GameAvatar caster, JsonObject task) {
    return parser.parse(
        caster.getExcel(),
        record(caster.getExcel().getAvatarID(), "task").get("ability").getAsString(),
        task);
  }

  JsonObject task(int id) {
    return record(id, "task").getAsJsonObject("task").deepCopy();
  }

  void cast(MazeSkill skill, GameAvatar caster) {
    skill.onCast(caster, MotionInfo.newInstance());
  }

  void expiry(GameAvatar caster, int buff, long before, double seconds) {
    assertTrue(caster.getBuffs().containsKey(buff), "Expected supported caster effect");
    long value = caster.getBuffs().get(buff), milliseconds = Math.round(seconds * 1000);
    if (seconds == -1) assertEquals(Long.MAX_VALUE, value);
    else
      assertTrue(
          value >= before + milliseconds && value <= System.currentTimeMillis() + milliseconds,
          "Wrong lifetime: " + (value - before));
  }

  void replace(int id, java.util.function.Consumer<JsonObject> change) {
    var row = record(id, "skill").getAsJsonObject("row").deepCopy();
    change.accept(row);
    GameData.getAvatarSkillParameterExcelMap()
        .values()
        .removeIf(r -> r.getSkillID() == id * 100 + 7);
    GameData.getAvatarSkillParameterExcelMap()
        .add(gson.fromJson(row, AvatarSkillParameterExcel.class));
  }

  @ParameterizedTest
  @ValueSource(ints = {1212, 1213, 1306, 1309, 1317, 1406})
  void unchangedSourceCasterLeafUsesItsOwnSkillParameter(int id) {
    var caster = caster(id);
    var task = task(id);
    var skill = skill(caster, task);
    int index = record(id, "task").get("parameterIndex").getAsInt();
    double seconds =
        record(id, "skill")
            .getAsJsonObject("row")
            .getAsJsonArray("ParamList")
            .get(index)
            .getAsJsonObject()
            .get("Value")
            .getAsDouble();
    assertFalse(record(id, "task").get("completeAbilityChainVerified").getAsBoolean());
    long before = System.currentTimeMillis();
    cast(skill, caster);
    expiry(caster, task.get("ID").getAsInt(), before, seconds);
  }

  @ParameterizedTest
  @ValueSource(ints = {1212, 1213, 1306, 1309, 1317, 1406})
  void dynamicParameterIsReadAtExecutionInsteadOfBeingFrozenAtParse(int id) {
    var caster = caster(id);
    var task = task(id);
    var skill = skill(caster, task);
    int index = record(id, "task").get("parameterIndex").getAsInt();
    replace(
        id,
        row ->
            row.getAsJsonArray("ParamList").set(index, JsonParser.parseString("{\"Value\":7.25}")));
    long before = System.currentTimeMillis();
    cast(skill, caster);
    expiry(caster, task.get("ID").getAsInt(), before, 7.25);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void allExplicitlyInvokedCallbacksUseTheActualCasterValue(int callback) {
    var caster = caster(1309);
    var task = task(1309);
    var skill = skill(caster, task);
    var action = skill.getCastActions().getFirst();
    long before = System.currentTimeMillis();
    if (callback == 0) action.onCast(caster, MotionInfo.newInstance());
    else if (callback == 1) action.onAttack(caster, List.of());
    else action.onCastHit(caster, List.of());
    expiry(caster, task.get("ID").getAsInt(), before, 15);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingSkillRowCannotBorrowTheTwentySecondFallback(boolean afterParse) {
    var caster = caster(1309);
    var task = task(1309);
    MazeSkill skill = null;
    if (afterParse) skill = skill(caster, task);
    GameData.getAvatarSkillParameterExcelMap().values().clear();
    if (!afterParse) skill = skill(caster, task);
    cast(skill, caster);
    assertFalse(caster.getBuffs().containsKey(task.get("ID").getAsInt()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "level",
        "maxLevel",
        "trigger",
        "attack",
        "missingParameters",
        "missingIndex",
        "negative",
        "string",
        "extraValueField"
      })
  void unresolvedSkillRowsDoNotCreateAnEffect(String mode) {
    var caster = caster(1212);
    var task = task(1212);
    var skill = skill(caster, task);
    replace(
        1212,
        row -> {
          switch (mode) {
            case "level" -> row.addProperty("Level", 2);
            case "maxLevel" -> row.addProperty("MaxLevel", 2);
            case "trigger" -> row.addProperty("SkillTriggerKey", "Skill01");
            case "attack" -> row.addProperty("AttackType", "Normal");
            case "missingParameters" -> row.remove("ParamList");
            case "missingIndex" -> row.add("ParamList", new JsonArray());
            case "negative" ->
                row.getAsJsonArray("ParamList").set(2, JsonParser.parseString("{\"Value\":-2}"));
            case "string" -> row.getAsJsonArray("ParamList").set(2, new JsonPrimitive("20"));
            case "extraValueField" ->
                row.getAsJsonArray("ParamList")
                    .set(2, JsonParser.parseString("{\"Value\":20,\"Unresolved\":1}"));
          }
        });
    cast(skill, caster);
    assertFalse(caster.getBuffs().containsKey(task.get("ID").getAsInt()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "hash",
        "floatHash",
        "extraHash",
        "opcode",
        "fixedOperands",
        "extraPostfixField",
        "extraDynamicField",
        "wrongDynamicType",
        "unscopedAbility",
        "missingSkillList",
        "wrongCaster",
        "target",
        "extraTargetField"
      })
  void unrecognizedDynamicExpressionsAndBindingsAreUnavailable(String mode) {
    var caster = caster(1309);
    var task = task(1309);
    String name = record(1309, "task").get("ability").getAsString();
    var life = task.getAsJsonObject("LifeTime");
    var expression = life.getAsJsonObject("PostfixExpr");
    switch (mode) {
      case "hash" -> expression.add("DynamicHashes", JsonParser.parseString("[1239462886]"));
      case "floatHash" -> expression.add("DynamicHashes", JsonParser.parseString("[1239462885.5]"));
      case "extraHash" -> expression.getAsJsonArray("DynamicHashes").add(1);
      case "opcode" -> expression.addProperty("OpCodes", "AQAAAAIR");
      case "fixedOperands" -> expression.getAsJsonArray("FixedValues").add(1);
      case "extraPostfixField" -> expression.addProperty("Unknown", 1);
      case "extraDynamicField" -> life.addProperty("Unknown", 1);
      case "wrongDynamicType" -> life.addProperty("IsDynamic", "true");
      case "unscopedAbility" -> name = "Unknown_MazeSkill";
      case "missingSkillList" -> {
        var raw = record(1309, "avatar").getAsJsonObject("row").deepCopy();
        raw.remove("SkillList");
        caster = new GameAvatar(gson.fromJson(raw, AvatarExcel.class));
      }
      case "target" -> task.getAsJsonObject("TargetType").addProperty("Alias", "AdvLineup");
      case "extraTargetField" -> task.getAsJsonObject("TargetType").addProperty("Unknown", 1);
    }
    String resolvedName = name;
    var skill = assertDoesNotThrow(() -> parser.parse(caster(1309).getExcel(), resolvedName, task));
    if (mode.equals("wrongCaster")) caster = caster(1213);
    cast(skill, caster);
    assertFalse(caster.getBuffs().containsKey(task.get("ID").getAsInt()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"unknownField", "missingValue", "stringValue", "mixedExpression", "nullValue"})
  void explicitUnresolvedLiteralsCannotBecomeZeroOrTwentySeconds(String mode) {
    var caster = caster(1309);
    var task = task(1309);
    var life = new JsonObject();
    var fixed = new JsonObject();
    fixed.addProperty("Value", 5);
    life.add("FixedValue", fixed);
    switch (mode) {
      case "unknownField" -> fixed.addProperty("Unknown", 1);
      case "missingValue" -> fixed.remove("Value");
      case "stringValue" -> fixed.addProperty("Value", "5");
      case "mixedExpression" ->
          life.add("PostfixExpr", task.getAsJsonObject("LifeTime").get("PostfixExpr"));
      case "nullValue" -> fixed.add("Value", JsonNull.INSTANCE);
    }
    task.add("LifeTime", life);
    var skill = assertDoesNotThrow(() -> skill(caster, task));
    cast(skill, caster);
    assertFalse(caster.getBuffs().containsKey(task.get("ID").getAsInt()));
  }

  @ParameterizedTest
  @ValueSource(doubles = {1.25, 0, -1})
  void recognizedLiteralLifetimeRemainsAvailable(double seconds) {
    var caster = caster(1309);
    var task = task(1309);
    task.add("LifeTime", JsonParser.parseString("{\"FixedValue\":{\"Value\":" + seconds + "}}"));
    var skill = skill(caster, task);
    long before = System.currentTimeMillis();
    cast(skill, caster);
    expiry(caster, task.get("ID").getAsInt(), before, seconds);
  }
}
