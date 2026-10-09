package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Pinned second-season choices. An unselected roster has no stage group or battle record. */
public final class BoxingClubResonanceCatalog {
  public record Challenge(
      int id,
      int previous,
      int module,
      int reward,
      int turnLimit,
      Map<Integer, Integer> resonanceGroups) {
    public Challenge {
      resonanceGroups = Map.copyOf(resonanceGroups);
    }
  }

  private final BoxingClubCatalog original;
  private final Map<Integer, Challenge> challenges;

  private BoxingClubResonanceCatalog(
      BoxingClubCatalog original, Map<Integer, Challenge> challenges) {
    this.original = original;
    this.challenges = Collections.unmodifiableMap(new TreeMap<>(challenges));
  }

  public Map<Integer, Challenge> challenges() {
    return challenges;
  }

  public BoxingClubCatalog.Group group(int id) {
    return original.group(id);
  }

  public BoxingClubCatalog.Stage stage(int event) {
    return original.stage(event);
  }

  public static BoxingClubResonanceCatalog load(Path root) {
    try {
      var original = BoxingClubCatalog.load(root);
      if (original == null) return null;
      var rows =
          JsonParser.parseString(
                  Files.readString(root.resolve("ExcelOutput/BoxingClubChallenge.json")))
              .getAsJsonArray();
      var challenges = new TreeMap<Integer, Challenge>();
      for (var raw : rows) {
        var row = raw.getAsJsonObject();
        if (!"Second".equals(row.get("Type").getAsString())) continue;
        int id = n(row, "ChallengeID");
        var choices = new TreeMap<Integer, Integer>();
        for (var choice : row.getAsJsonObject("StageBuffAndGroupMap").entrySet()) {
          int buff = Integer.parseInt(choice.getKey()),
              group = choice.getValue().getAsBigDecimal().intValueExact();
          if (buff <= 0 || group <= 0 || choices.putIfAbsent(buff, group) != null) return null;
          var g = original.group(group);
          if (g == null || g.weight() != 1 || g.events().size() != 3) return null;
          for (int i = 0; i < 3; i++) {
            var s = original.stage(g.events().get(i));
            if (s == null
                || s.buff() != 0
                || (i == 0
                    ? !s.options().equals(List.of(0))
                    : s.options().size() != 2 || s.options().stream().anyMatch(v -> v <= 0)))
              return null;
          }
        }
        var challenge =
            new Challenge(
                id,
                n(row, "PreChallengeID"),
                n(row, "ActivityModuleID"),
                n(row, "FirstPassRewardID"),
                n(row, "ChallengeTurnLimit"),
                choices);
        if (id < 6
            || id > 12
            || challenge.previous() != (id == 6 ? 0 : id - 1)
            || challenge.module() <= 0
            || challenge.reward() != 100
            || challenge.turnLimit() != 20
            || choices.size() != 1
            || !row.getAsJsonArray("StageGroupList").isEmpty()
            || challenges.putIfAbsent(id, challenge) != null) return null;
      }
      return challenges.size() == 7 ? new BoxingClubResonanceCatalog(original, challenges) : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  private static int n(JsonObject row, String key) {
    return row.has(key) ? row.get(key).getAsBigDecimal().intValueExact() : 0;
  }
}
