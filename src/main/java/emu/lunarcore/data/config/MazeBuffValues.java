package emu.lunarcore.data.config;

import com.google.gson.JsonElement;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import org.hkrpg.server.gameplay.MazeBuffLifetime;

/** Inferred DesignData bindings, scoped to their source abilities; not an expression engine. */
public final class MazeBuffValues {
  private record Binding(int avatar, String ability, int hash, int parameter) {}

  private static final Map<Integer, Binding> BINDINGS =
      Map.ofEntries(
          Map.entry(1212, new Binding(1212, "LocalPlayer_Jingliu_MazeSkill", -1733965089, 2)),
          Map.entry(1213, new Binding(1213, "LocalPlayer_DanHengIL_MazeSkill", 188860000, 1)),
          Map.entry(1306, new Binding(1306, "LocalPlayer_Sparkle_MazeSkill", 188860000, 1)),
          Map.entry(1309, new Binding(1309, "LocalPlayer_Robin_MazeSkill", 1239462885, 0)),
          Map.entry(1317, new Binding(1317, "LocalPlayer_Rappa_00_MazeSkill", 1239462885, 0)),
          Map.entry(1406, new Binding(1406, "LocalPlayer_Cipher_00_MazeSkill", 1239462885, 0)),
          Map.entry(1407, new Binding(1407, "LocalPlayer_Castorice_MazeSkill", -1733965089, 2)),
          Map.entry(1208, new Binding(1208, "LocalPlayer_FuXuan_MazeSkill", 692437857, 0)),
          Map.entry(1510, new Binding(1510, "LocalPlayer_HimekoNova_MazeSkill", 692437857, 0)),
          Map.entry(1223, new Binding(1223, "LocalPlayer_Moze_MazeSkill", 692437857, 0)),
          Map.entry(1102, new Binding(1102, "LocalPlayer_Seele_MazeSkill", 692437857, 0)),
          Map.entry(1207, new Binding(1207, "LocalPlayer_Yukong_00_MazeSkill", 692437857, 0)),
          Map.entry(1221, new Binding(1221, "LocalPlayer_Yunli_00_MazeSkill", 188860000, 1)));

  private MazeBuffValues() {}

  public static Optional<Function<GameAvatar, OptionalDouble>> lifetime(
      MazeSkill skill, TaskInfo task) {
    var raw = task.getLifeTimeExpression();
    // Preserve only the old omitted-field default until native invocation is established.
    if (raw == null) return Optional.of(caster -> OptionalDouble.of(20));
    var fixed = literalLifetimeSeconds(raw);
    if (fixed.isPresent()) return Optional.of(caster -> fixed);
    var binding = BINDINGS.get(skill.getId());
    if (binding == null
        || !binding.ability().equals(skill.getAbilityName())
        || !task.hasExactCasterTarget()
        || !MazeRecoveryValues.isSingleHash(raw, binding.hash())) return Optional.empty();
    return Optional.of(
        caster -> {
          int skillId = binding.avatar() * 100 + 7;
          if (caster == null
              || caster.getExcel() == null
              || caster.getExcel().getAvatarID() != binding.avatar()
              || caster.getExcel().getSkillList() == null
              || Arrays.stream(caster.getExcel().getSkillList()).noneMatch(id -> id == skillId))
            return OptionalDouble.empty();
          var row = GameData.getAvatarSkillParameterExcelMap().get(skillId, 1);
          if (row == null
              || row.getLevel() != 1
              || row.getMaxLevel() != 1
              || !"Maze".equals(row.getAttackType())
              || !"SkillMaze".equals(row.getSkillTriggerKey())
              || row.getParamList() == null
              || row.getParamList().size() <= binding.parameter()) return OptionalDouble.empty();
          var value = row.getParamList().get(binding.parameter());
          if (value == null
              || !value.isJsonObject()
              || !value.getAsJsonObject().keySet().equals(Set.of("Value")))
            return OptionalDouble.empty();
          return valid(row.getParameter(binding.parameter()));
        });
  }

  public static OptionalDouble literalLifetimeSeconds(JsonElement element) {
    if (element == null || !element.isJsonObject()) return OptionalDouble.empty();
    var raw = element.getAsJsonObject();
    if (!Set.of("IsDynamic", "FixedValue").containsAll(raw.keySet())) return OptionalDouble.empty();
    var dynamic = raw.get("IsDynamic");
    if (dynamic != null
        && (!dynamic.isJsonPrimitive()
            || !dynamic.getAsJsonPrimitive().isBoolean()
            || dynamic.getAsBoolean())) return OptionalDouble.empty();
    var value = raw.get("FixedValue");
    if (value != null && value.isJsonObject()) {
      if (!value.getAsJsonObject().keySet().equals(Set.of("Value"))) return OptionalDouble.empty();
      value = value.getAsJsonObject().get("Value");
    }
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      return OptionalDouble.empty();
    return valid(OptionalDouble.of(value.getAsDouble()));
  }

  private static OptionalDouble valid(OptionalDouble value) {
    if (value.isEmpty()) return value;
    try {
      MazeBuffLifetime.milliseconds(value.getAsDouble());
      return value;
    } catch (IllegalArgumentException invalid) {
      return OptionalDouble.empty();
    }
  }
}
