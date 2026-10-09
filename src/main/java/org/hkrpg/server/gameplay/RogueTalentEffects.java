package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import java.util.*;
import java.util.regex.Pattern;

/** Numeric 4.6 resources plus pinned LunarCore development's 11/21/32 semantics. */
public final class RogueTalentEffects {
  private static final Pattern COST = Pattern.compile("\\[31:([1-9][0-9]*)]");

  public static boolean enabled(Player p, int id) {
    var row = GameplayDefinitions.rogueTalents.get(id);
    if (row == null || !p.getGameplay().getRogueTalents().contains(id)) return false;
    if (id == 11 || id == 21)
      return row.EffectDescParamList() != null && row.EffectDescParamList().isEmpty();
    return id == 32
        && row.EffectDescParamList() != null
        && row.EffectDescParamList().size() == 1
        && row.EffectDescParamList().getFirst() != null
        && Double.isFinite(row.EffectDescParamList().getFirst().Value())
        && row.EffectDescParamList().getFirst().Value() >= 0
        && row.EffectDescParamList().getFirst().Value() <= 1;
  }

  public static OptionalDouble initialSp(Player p) {
    if (enabled(p, 32))
      return OptionalDouble.of(
          GameplayDefinitions.rogueTalents.get(32).EffectDescParamList().getFirst().Value());
    try {
      double ratio =
          Double.parseDouble(GameplayDefinitions.rogueConstants.get("Rogue_Character_Initial_SP"));
      return Double.isFinite(ratio) && ratio >= 0 && ratio <= 1
          ? OptionalDouble.of(ratio)
          : OptionalDouble.empty();
    } catch (RuntimeException ignored) {
      return OptionalDouble.empty();
    }
  }

  public static List<Integer> rerollCosts(Player p) {
    try {
      int limit = Integer.parseInt(GameplayDefinitions.rogueConstants.get("Roll_Buff_Max_Count"));
      if (limit < 0 || limit > 20) return List.of();
      limit += enabled(p, 11) ? 1 : 0;
      if (limit == 0 || limit > 20) return List.of();
      var fields = GameplayDefinitions.rogueConstants.get("Roll_Buff_Cost").split(",", -1);
      if (fields.length > 20 || fields.length < limit) return List.of();
      var costs = new ArrayList<Integer>();
      for (var field : fields) {
        var match = COST.matcher(field.trim());
        if (!match.matches()) return List.of();
        costs.add(Integer.parseInt(match.group(1)));
      }
      return List.copyOf(costs.subList(0, limit));
    } catch (RuntimeException ignored) {
      return List.of();
    }
  }

  public static Optional<Set<Integer>> group(int id) {
    var out = new TreeSet<Integer>();
    return expand(id, out, new HashSet<>(), 0) ? Optional.of(Set.copyOf(out)) : Optional.empty();
  }

  private static boolean expand(int id, Set<Integer> out, Set<Integer> active, int depth) {
    if (id <= 0 || depth > 32 || !active.add(id)) return false;
    try {
      var tags = GameplayDefinitions.blessings.stream().filter(b -> b.RogueBuffTag() == id).toList();
      if (!tags.isEmpty()) {
        if (tags.size() != 1) return false;
        var buff = tags.getFirst();
        if (buff.MazeBuffID() <= 0 || buff.MazeBuffLevel() != 1 || buff.RogueVersion() != 1)
          return false;
        out.add(buff.MazeBuffID());
        return true;
      }
      var row = GameplayDefinitions.rogueBuffGroups.get(id);
      if (row == null) {
        // Keep explicit tagless legacy/test definitions; source rows with tags cannot alias IDs.
        if (GameplayDefinitions.blessings.stream()
            .noneMatch(b -> b.MazeBuffID() == id && b.MazeBuffLevel() == 1 && b.RogueBuffTag() == 0 && b.RogueVersion() == 1)) return false;
        out.add(id);
        return true;
      }
      if (row.HECJCAMDGNO() == null
          || row.HECJCAMDGNO().isEmpty()
          || row.HECJCAMDGNO().size() > 1000) return false;
      for (var child : row.HECJCAMDGNO())
        if (child == null || !expand(child, out, active, depth + 1)) return false;
      return true;
    } finally {
      active.remove(id);
    }
  }

  private RogueTalentEffects() {}
}
