package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.excel.AvatarSkillTreeExcel;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.avatar.AvatarSkillTreeAnchorType;
import java.nio.file.*;
import java.util.*;

/** Local resource presets; no reference checkout is read by the running server. */
public final class TrialSkillPresets {
  public record Rule(String mode, int level) {}

  public record Plan(boolean custom, boolean noKey, Map<Integer, Rule> nodes) {
    public Plan {
      if (nodes == null || !custom && (noKey || !nodes.isEmpty()) || noKey && !nodes.isEmpty())
        throw new IllegalArgumentException("Invalid frozen trial skill plan");
      for (var entry : nodes.entrySet())
        if (entry.getKey() == null
            || entry.getKey() <= 0
            || entry.getValue() == null
            || !Set.of("Fixed", "MaxLevel", "MaxWithCharacterLevel")
                .contains(entry.getValue().mode())
            || "Fixed".equals(entry.getValue().mode())
                && (entry.getValue().level() <= 0 || entry.getValue().level() > 100))
          throw new IllegalArgumentException("Invalid frozen trial skill rule");
      nodes = Map.copyOf(nodes);
    }

    public int requested(AvatarSkillTreeExcel row) {
      if (!custom) return row.getMaxLevel() == 6 ? 5 : row.getMaxLevel() == 10 ? 8 : 1;
      if (noKey) return 0;
      var rule = nodes.get(row.getAnchorId());
      if (rule == null) return row.isDefaultUnlock() ? 1 : 0;
      return "Fixed".equals(rule.mode())
          ? Math.min(rule.level(), row.getMaxLevel())
          : row.getMaxLevel();
    }

    public boolean characterLimits(int anchor, int level, boolean defaultUnlock) {
      if (!custom) return true;
      var rule = nodes.get(anchor);
      return rule != null
          && "MaxWithCharacterLevel".equals(rule.mode())
          && !(level == 1 && defaultUnlock);
    }
  }

  private record Node(String Anchor, String LevelType, int Level) {}

  private record Preset(String Name, List<Node> PresetList) {}

  public static Plan legacy() {
    return new Plan(false, false, Map.of());
  }

  public static Plan resolve(SpecialAvatarExcel config) {
    String template = config.getSkillTreeTemplate();
    if (template == null || template.isBlank()) return legacy();
    if (!"TYPE_CUSTOM".equals(template))
      throw new IllegalArgumentException("Unsupported trial skill template");
    String key = config.getCustomSkillTreeKey();
    if (key == null || key.isBlank()) return new Plan(true, true, Map.of());
    Path folder =
        Path.of(
            LunarCore.getConfig().getResourceDir(), "Config/BattleLineupPage/SkillTreePointPreset");
    var presets = new HashMap<String, Plan>();
    try (var paths = Files.list(folder)) {
      for (var path :
          paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
        var preset = new Gson().fromJson(Files.readString(path), Preset.class);
        if (preset == null
            || preset.Name() == null
            || preset.Name().isBlank()
            || preset.PresetList() == null)
          throw new IllegalArgumentException("Invalid trial skill preset");
        var nodes = new HashMap<Integer, Rule>();
        for (var node : preset.PresetList()) {
          if (node == null || node.Anchor() == null)
            throw new IllegalArgumentException("Invalid trial preset node");
          int anchor = AvatarSkillTreeAnchorType.getValue(node.Anchor());
          String mode =
              node.LevelType() == null || node.LevelType().isBlank() ? "Fixed" : node.LevelType();
          if (anchor <= 0
              || !Set.of("Fixed", "MaxLevel", "MaxWithCharacterLevel").contains(mode)
              || "Fixed".equals(mode) && (node.Level() <= 0 || node.Level() > 100)
              || nodes.putIfAbsent(anchor, new Rule(mode, node.Level())) != null)
            throw new IllegalArgumentException("Invalid or duplicate trial preset node");
        }
        if (presets.putIfAbsent(preset.Name(), new Plan(true, false, nodes)) != null)
          throw new IllegalArgumentException("Duplicate trial skill preset name");
      }
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("Cannot load trial skill presets", error);
    }
    var result = presets.get(key);
    if (result == null) throw new IllegalArgumentException("Unknown trial skill preset: " + key);
    return result;
  }

  private TrialSkillPresets() {}
}
