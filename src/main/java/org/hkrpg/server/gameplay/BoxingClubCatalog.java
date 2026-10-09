package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Original first-season definitions. Catalog presence never implies a completed challenge. */
public final class BoxingClubCatalog {
  private static final Map<String, String> INPUTS =
      Map.of(
          "BoxingClubChallenge", "94633346b7f5bfa986797cb3676791a96117f67372ad96e36843df536e793771",
          "BoxingClubStageGroup",
              "c352258891adf431b2f6270ec0cff29fb3250b5af728fe1d1fd46d39fce69077",
          "BoxingClubStage", "473368f57f05ff4b87a959683fa2afe7334deb342af84e192e8fc4a777de4322");

  public record Challenge(
      int id, int previous, int module, int reward, int turnLimit, int buff, List<Integer> groups) {
    public Challenge {
      groups = List.copyOf(groups);
    }
  }

  public record Group(int id, int weight, List<Integer> events) {
    public Group {
      events = List.copyOf(events);
    }
  }

  public record Stage(int event, int buff, List<Integer> options) {
    public Stage {
      options = List.copyOf(options);
    }
  }

  private final Map<Integer, Challenge> challenges;
  private final Map<Integer, Group> groups;
  private final Map<Integer, Stage> stages;

  private BoxingClubCatalog(
      Map<Integer, Challenge> challenges, Map<Integer, Group> groups, Map<Integer, Stage> stages) {
    this.challenges = Collections.unmodifiableMap(new TreeMap<>(challenges));
    this.groups = Map.copyOf(groups);
    this.stages = Map.copyOf(stages);
  }

  public Map<Integer, Challenge> challenges() {
    return challenges;
  }

  public Group group(int id) {
    return groups.get(id);
  }

  public Stage stage(int event) {
    return stages.get(event);
  }

  public static BoxingClubCatalog load(Path root) {
    try {
      var tables = new HashMap<String, JsonArray>();
      for (var entry : INPUTS.entrySet()) {
        var path = root.resolve("ExcelOutput/" + entry.getKey() + ".json");
        if (!Files.isRegularFile(path) || Files.size(path) > 128 * 1024) return null;
        // The fixed Git blobs use LF; an ordinary Windows checkout may use CRLF.
        // No other content normalization or replacement of operator resources is performed.
        var text = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        var digest =
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        if (!entry.getValue().equals(HexFormat.of().formatHex(digest))) return null;
        tables.put(entry.getKey(), JsonParser.parseString(text).getAsJsonArray());
      }
      var groups = new HashMap<Integer, Group>();
      for (var raw : tables.get("BoxingClubStageGroup")) {
        var row = raw.getAsJsonObject();
        var group =
            new Group(number(row, "StageGroupID"), number(row, "Weight"), list(row, "EventIDList"));
        if (group.id() <= 0 || groups.putIfAbsent(group.id(), group) != null) return null;
      }
      var stages = new HashMap<Integer, Stage>();
      for (var raw : tables.get("BoxingClubStage")) {
        var row = raw.getAsJsonObject();
        var stage =
            new Stage(number(row, "EventID"), number(row, "BuffID"), list(row, "BuffOptionalList"));
        if (stage.event() <= 0 || stages.putIfAbsent(stage.event(), stage) != null) return null;
      }
      var challenges = new TreeMap<Integer, Challenge>();
      for (var raw : tables.get("BoxingClubChallenge")) {
        var row = raw.getAsJsonObject();
        if (!"First".equals(row.get("Type").getAsString())) continue;
        var challenge =
            new Challenge(
                number(row, "ChallengeID"),
                number(row, "PreChallengeID"),
                number(row, "ActivityModuleID"),
                number(row, "FirstPassRewardID"),
                number(row, "ChallengeTurnLimit"),
                number(row, "ChallengeBuff"),
                list(row, "StageGroupList"));
        if (challenge.id() < 1
            || challenge.id() > 5
            || challenge.previous() != challenge.id() - 1
            || challenge.module() <= 0
            || challenge.reward() <= 0
            || challenge.turnLimit() <= 0
            || challenge.buff() <= 0
            || challenge.groups().isEmpty()
            || challenges.putIfAbsent(challenge.id(), challenge) != null) return null;
        for (int id : challenge.groups()) {
          var group = groups.get(id);
          if (group == null || group.events().size() != 4) return null;
          for (int round = 0; round < group.events().size(); round++) {
            var stage = stages.get(group.events().get(round));
            if (stage == null
                || stage.buff() < 0
                || round < 3 && stage.buff() == 0
                || !stage.options().isEmpty()) return null;
          }
        }
      }
      return challenges.size() == 5 ? new BoxingClubCatalog(challenges, groups, stages) : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  private static int number(JsonObject row, String key) {
    return row.has(key) ? row.get(key).getAsBigDecimal().intValueExact() : 0;
  }

  private static List<Integer> list(JsonObject row, String key) {
    var result = new ArrayList<Integer>();
    for (var item : row.getAsJsonArray(key)) result.add(item.getAsBigDecimal().intValueExact());
    if (!"BuffOptionalList".equals(key) && result.stream().anyMatch(x -> x <= 0)
        || new HashSet<>(result).size() != result.size())
      throw new IllegalArgumentException("Invalid original IDs");
    return result;
  }
}
