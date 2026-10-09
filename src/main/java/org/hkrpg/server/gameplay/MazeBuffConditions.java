package org.hkrpg.server.gameplay;

import com.google.gson.JsonElement;
import emu.lunarcore.game.avatar.GameAvatar;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** Supported caster conditions; unavailable context cannot select either branch. */
public final class MazeBuffConditions {
  private MazeBuffConditions() {}

  private interface TimedPredicate {
    Optional<Boolean> test(GameAvatar caster, long timestamp, Integer mp, Integer hp);
  }

  private record Compiled(TimedPredicate predicate, boolean requiresMp, boolean requiresHp) {}

  public static Optional<Predicate<GameAvatar>> compile(JsonElement expression) {
    return compileBranch(expression)
        .map(condition -> caster -> condition.apply(caster).orElse(false));
  }

  public static Optional<Function<GameAvatar, Optional<Boolean>>> compileBranch(
      JsonElement expression) {
    return compile(expression, 0)
        .map(
            compiled ->
                caster -> {
                  if (caster == null) return Optional.empty();
                  if (compiled.requiresMp() || compiled.requiresHp()) {
                    var owner = caster.getOwner();
                    if (owner == null) return Optional.empty();
                    synchronized (owner) {
                      var lineup = owner.getCurrentLineup();
                      if (lineup == null
                          || !lineup.getAvatars().contains(caster.getAvatarId())
                          || owner.getAvatarById(caster.getAvatarId()) != caster)
                        return Optional.empty();
                      Integer mp = compiled.requiresMp() ? lineup.getMp() : null;
                      Integer hp = compiled.requiresHp() ? caster.getCurrentHp(lineup) : null;
                      if (mp != null && (mp < 0 || mp > lineup.getMaxMp())) return Optional.empty();
                      if (hp != null
                          && (caster.getMaxHp() <= 0 || hp < 0 || hp > caster.getMaxHp()))
                        return Optional.empty();
                      return evaluate(compiled, caster, mp, hp);
                    }
                  }
                  return evaluate(compiled, caster, null, null);
                });
  }

  private static Optional<Boolean> evaluate(
      Compiled compiled, GameAvatar caster, Integer mp, Integer hp) {
    synchronized (caster.getBuffs()) {
      return compiled.predicate().test(caster, System.currentTimeMillis(), mp, hp);
    }
  }

  private static Optional<Compiled> compile(JsonElement expression, int depth) {
    if (expression == null || !expression.isJsonObject() || depth > 32) return Optional.empty();
    var object = expression.getAsJsonObject();
    var type = object.get("$type");
    var inverse = object.get("Inverse");
    if (type == null
        || !type.isJsonPrimitive()
        || !type.getAsJsonPrimitive().isString()
        || (inverse != null
            && (!inverse.isJsonPrimitive() || !inverse.getAsJsonPrimitive().isBoolean()))) {
      return Optional.empty();
    }
    TimedPredicate predicate;
    boolean requiresMp = false;
    boolean requiresHp = false;
    switch (type.getAsString()) {
      case "RPG.GameCore.ByCharacterDamageType" -> {
        if (!java.util.Set.of("$type", "Inverse", "TargetType", "DamageType")
            .containsAll(object.keySet())) return Optional.empty();
        var target = object.get("TargetType");
        var damage = object.get("DamageType");
        if (target == null
            || !target.isJsonObject()
            || damage == null
            || !damage.isJsonPrimitive()
            || !damage.getAsJsonPrimitive().isString()) return Optional.empty();
        var selector = target.getAsJsonObject();
        var selectorType = selector.get("$type");
        var alias = selector.get("Alias");
        if (!java.util.Set.of("$type", "Alias").containsAll(selector.keySet())
            || selectorType == null
            || !selectorType.isJsonPrimitive()
            || !selectorType.getAsJsonPrimitive().isString()
            || !"RPG.GameCore.TargetAlias".equals(selectorType.getAsString())
            || alias == null
            || !alias.isJsonPrimitive()
            || !alias.getAsJsonPrimitive().isString()
            || !"Caster".equals(alias.getAsString())) return Optional.empty();
        final emu.lunarcore.game.enums.DamageType expected;
        try {
          expected = emu.lunarcore.game.enums.DamageType.valueOf(damage.getAsString());
        } catch (IllegalArgumentException invalid) {
          return Optional.empty();
        }
        predicate =
            (caster, timestamp, mp, hp) -> {
              var excel = caster.getExcel();
              if (excel == null || excel.getDamageType() == null) return Optional.empty();
              return Optional.of(excel.getDamageType() == expected);
            };
      }
      case "RPG.GameCore.ByCompareCarryMazebuff" -> {
        var target = object.get("TargetType");
        var id = object.get("BuffID");
        if (target == null
            || !target.isJsonObject()
            || id == null
            || !id.isJsonPrimitive()
            || !id.getAsJsonPrimitive().isNumber()) return Optional.empty();
        var selector = target.getAsJsonObject();
        var selectorType = selector.get("$type");
        var alias = selector.get("Alias");
        if (selectorType == null
            || !selectorType.isJsonPrimitive()
            || !selectorType.getAsJsonPrimitive().isString()
            || !"RPG.GameCore.TargetAlias".equals(selectorType.getAsString())
            || alias == null
            || !alias.isJsonPrimitive()
            || !alias.getAsJsonPrimitive().isString()
            || !"Caster".equals(alias.getAsString())) return Optional.empty();
        final int buffId;
        try {
          buffId = id.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
          return Optional.empty();
        }
        if (buffId <= 0) return Optional.empty();
        predicate =
            (caster, timestamp, mp, hp) ->
                Optional.of(
                    caster.getBuffs().containsKey(buffId)
                        && caster.getBuffs().get(buffId) >= timestamp);
      }
      case "RPG.GameCore.AdventureByCompareMP", "RPG.GameCore.AdventureByCompareHPRatio" -> {
        // Native CompareValue is R4 (not DynamicFloat); the verified constructor leaves it zero.
        boolean hpComparison = "RPG.GameCore.AdventureByCompareHPRatio".equals(type.getAsString());
        var allowed =
            hpComparison
                ? java.util.Set.of("$type", "Inverse", "TargetType", "CompareType", "CompareValue")
                : java.util.Set.of("$type", "Inverse", "CompareType", "CompareValue");
        if (!allowed.containsAll(object.keySet())) return Optional.empty();
        if (hpComparison) {
          var target = object.get("TargetType");
          if (target == null || !target.isJsonObject()) return Optional.empty();
          var selector = target.getAsJsonObject();
          var selectorType = selector.get("$type");
          var alias = selector.get("Alias");
          if (!selector.keySet().equals(java.util.Set.of("$type", "Alias"))
              || selectorType == null
              || !selectorType.isJsonPrimitive()
              || !selectorType.getAsJsonPrimitive().isString()
              || !"RPG.GameCore.TargetAlias".equals(selectorType.getAsString())
              || alias == null
              || !alias.isJsonPrimitive()
              || !alias.getAsJsonPrimitive().isString()
              || !"Caster".equals(alias.getAsString())) return Optional.empty();
        }
        var compare = object.get("CompareType");
        var value = object.get("CompareValue");
        if (compare == null
            || !compare.isJsonPrimitive()
            || !compare.getAsJsonPrimitive().isString()
            || !java.util.Set.of(
                    "Greater", "GreaterEqual", "NotEqual", "Equal", "LessEqual", "Less")
                .contains(compare.getAsString())
            || (value != null
                && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())))
          return Optional.empty();
        final float threshold;
        try {
          threshold = value == null ? 0 : value.getAsFloat();
        } catch (NumberFormatException invalid) {
          return Optional.empty();
        }
        if (!Float.isFinite(threshold)) return Optional.empty();
        String operation = compare.getAsString();
        requiresMp = !hpComparison;
        requiresHp = hpComparison;
        predicate =
            (caster, timestamp, mp, hp) -> {
              float actual = hpComparison ? hp / (float) caster.getMaxHp() : mp;
              return Optional.of(
                  switch (operation) {
                    case "Greater" -> actual > threshold;
                    case "GreaterEqual" -> actual >= threshold;
                    case "NotEqual" -> actual != threshold;
                    case "Equal" -> actual == threshold;
                    case "LessEqual" -> actual <= threshold;
                    case "Less" -> actual < threshold;
                    default -> throw new IllegalStateException("Uncompiled adventure comparator");
                  });
            };
      }
      case "RPG.GameCore.ByAnd", "RPG.GameCore.ByAny" -> {
        var list = object.get("PredicateList");
        if (list == null || !list.isJsonArray() || list.getAsJsonArray().isEmpty())
          return Optional.empty();
        var children = new ArrayList<TimedPredicate>();
        for (var child : list.getAsJsonArray()) {
          var compiled = compile(child, depth + 1);
          if (compiled.isEmpty()) return Optional.empty();
          requiresMp |= compiled.get().requiresMp();
          requiresHp |= compiled.get().requiresHp();
          children.add(compiled.get().predicate());
        }
        boolean all = "RPG.GameCore.ByAnd".equals(type.getAsString());
        predicate =
            (caster, timestamp, mp, hp) -> {
              boolean result = all;
              for (var child : children) {
                var value = child.test(caster, timestamp, mp, hp);
                if (value.isEmpty()) return Optional.empty();
                result = all ? result && value.get() : result || value.get();
              }
              return Optional.of(result);
            };
      }
      default -> {
        return Optional.empty();
      }
    }
    return Optional.of(
        new Compiled(
            inverse != null && inverse.getAsBoolean()
                ? (caster, timestamp, mp, hp) ->
                    predicate.test(caster, timestamp, mp, hp).map(value -> !value)
                : predicate,
            requiresMp,
            requiresHp));
  }
}
