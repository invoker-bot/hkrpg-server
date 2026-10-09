package emu.lunarcore.data.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;

/**
 * Scoped recovery bindings. The two HP bindings are inferred from pinned character DesignData,
 * matching source leaves and skill rows; this is not a general dynamic-expression interpreter.
 */
public final class MazeRecoveryValues {
  private MazeRecoveryValues() {}

  public static Optional<Function<GameAvatar, OptionalDouble>> hpRatio(
      MazeSkill skill, TaskInfo task) {
    if (!"RPG.GameCore.AdventureModifyTeamPlayerHP".equals(task.getType())
        || task.IsClientOnly()
        || task.getSpecifyTargetType() != null
        || task.getMinRatio() != null
        || task.getModifyValue() != null
        || !adds(task.getModifyFunction())) return Optional.empty();
    var literal = literal(task.getAddRatio());
    if (literal.isPresent()) return Optional.of(caster -> literal);
    if (!isSingleHash(task.getAddRatio(), -850066311)) return Optional.empty();
    int skillId;
    if (skill.getId() == 8001 && "LocalPlayer_PlayerBoy_MazeSkill".equals(skill.getAbilityName())) {
      skillId = 800107;
    } else if (skill.getId() == 8002
        && "LocalPlayer_PlayerGirl_MazeSkill".equals(skill.getAbilityName())) {
      skillId = 800207;
    } else return Optional.empty();
    return Optional.of(
        caster -> {
          if (caster == null
              || caster.getExcel() == null
              || caster.getExcel().getAvatarID() != skill.getId()
              || caster.getExcel().getSkillList() == null
              || Arrays.stream(caster.getExcel().getSkillList()).noneMatch(id -> id == skillId)) {
            return OptionalDouble.empty();
          }
          var row = GameData.getAvatarSkillParameterExcelMap().get(skillId, 1);
          if (row == null
              || row.getMaxLevel() != 1
              || !"Maze".equals(row.getAttackType())
              || !"SkillMaze".equals(row.getSkillTriggerKey())) return OptionalDouble.empty();
          return nonnegative(row.getParameter(0));
        });
  }

  private static boolean adds(JsonElement function) {
    // Both pinned native constructors explicitly initialize PropertyModifyFunction.Add (2).
    return function == null
        || (function.isJsonPrimitive()
            && function.getAsJsonPrimitive().isString()
            && "Add".equals(function.getAsString()));
  }

  /** Normal-energy absolute amount to the server's normalized SP bar, using source SPNeed. */
  public static Optional<Function<GameAvatar, OptionalDouble>> spRatio(
      MazeSkill skill, TaskInfo task) {
    if (!"RPG.GameCore.AdventureModifyTeamPlayerSP".equals(task.getType())
        || task.IsClientOnly()
        || !task.hasCasterRecoveryTarget()
        || task.getAddRatio() != null
        || task.getMinRatio() != null
        || !adds(task.getModifyFunction())) return Optional.empty();
    var fixed = literal(task.getModifyValue());
    boolean dynamic = fixed.isEmpty();
    if (dynamic
        && (skill.getId() != 1202
            || !"LocalPlayer_TingYun_MazeSkill".equals(skill.getAbilityName())
            || !isSingleHash(task.getModifyValue(), 457361642))) return Optional.empty();
    return Optional.of(
        caster -> {
          if (caster == null || caster.getExcel() == null) return OptionalDouble.empty();
          var capacity = caster.getExcel().getBaseSpCapacity();
          if (capacity.isEmpty()) return OptionalDouble.empty();
          var value = fixed;
          if (dynamic) {
            if (caster.getExcel().getAvatarID() != 1202
                || caster.getExcel().getSkillList() == null
                || Arrays.stream(caster.getExcel().getSkillList()).noneMatch(id -> id == 120207)) {
              return OptionalDouble.empty();
            }
            var row = GameData.getAvatarSkillParameterExcelMap().get(120207, 1);
            if (row == null
                || row.getMaxLevel() != 1
                || !"Maze".equals(row.getAttackType())
                || !"SkillMaze".equals(row.getSkillTriggerKey())) return OptionalDouble.empty();
            value = nonnegative(row.getParameter(0));
          }
          if (value.isEmpty()) return OptionalDouble.empty();
          return OptionalDouble.of(
              value.getAsDouble() >= capacity.getAsDouble()
                  ? 1
                  : value.getAsDouble() / capacity.getAsDouble());
        });
  }

  private static OptionalDouble nonnegative(OptionalDouble value) {
    return value.isPresent() && Double.isFinite(value.getAsDouble()) && value.getAsDouble() >= 0
        ? value
        : OptionalDouble.empty();
  }

  private static OptionalDouble literal(JsonElement element) {
    if (element == null || !element.isJsonObject()) return OptionalDouble.empty();
    var object = element.getAsJsonObject();
    if (!Set.of("IsDynamic", "FixedValue").containsAll(object.keySet()))
      return OptionalDouble.empty();
    var dynamic = object.get("IsDynamic");
    if (dynamic != null
        && (!dynamic.isJsonPrimitive()
            || !dynamic.getAsJsonPrimitive().isBoolean()
            || dynamic.getAsBoolean())) {
      return OptionalDouble.empty();
    }
    var value = object.get("FixedValue");
    if (value != null && value.isJsonObject()) {
      if (!value.getAsJsonObject().keySet().equals(Set.of("Value"))) return OptionalDouble.empty();
      value = value.getAsJsonObject().get("Value");
    }
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      return OptionalDouble.empty();
    }
    return nonnegative(OptionalDouble.of(value.getAsDouble()));
  }

  static boolean isSingleHash(JsonElement element, int hash) {
    if (element == null || !element.isJsonObject()) return false;
    var object = element.getAsJsonObject();
    if (!object.keySet().equals(Set.of("IsDynamic", "PostfixExpr"))) return false;
    var dynamic = object.get("IsDynamic");
    var expression = object.get("PostfixExpr");
    if (!dynamic.isJsonPrimitive()
        || !dynamic.getAsJsonPrimitive().isBoolean()
        || !dynamic.getAsBoolean()
        || !expression.isJsonObject()) return false;
    JsonObject postfix = expression.getAsJsonObject();
    if (!postfix.keySet().equals(Set.of("OpCodes", "FixedValues", "DynamicHashes"))) return false;
    var opcode = postfix.get("OpCodes");
    var fixed = postfix.get("FixedValues");
    var hashes = postfix.get("DynamicHashes");
    if (!opcode.isJsonPrimitive()
        || !opcode.getAsJsonPrimitive().isString()
        || !"AQAR".equals(opcode.getAsString())
        || !fixed.isJsonArray()
        || !fixed.getAsJsonArray().isEmpty()
        || !hashes.isJsonArray()
        || hashes.getAsJsonArray().size() != 1) return false;
    var key = hashes.getAsJsonArray().get(0);
    if (!key.isJsonPrimitive() || !key.getAsJsonPrimitive().isNumber()) return false;
    try {
      return key.getAsBigDecimal().intValueExact() == hash;
    } catch (ArithmeticException | NumberFormatException invalid) {
      return false;
    }
  }
}
