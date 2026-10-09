package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Pinned original objects. Reading a graph never executes or completes it. */
public final class ClockParkCatalog {
  private static final Map<String, String> INPUTS =
      Map.of(
          "ExcelOutput/ClockParkScriptConfig.json",
              "6a9792f2e9fc7c6c330f220a553d1bbf3321286ab73b7e8f65704c7e138e025c",
          "ExcelOutput/ClockParkChapterConfig.json",
              "c944fbf3a2276cc6c51e82460124f6cdd72432c6fff1d29cc49239c56eec2140",
          "ExcelOutput/ClockParkRound.json",
              "1256dead3294cff190c259cbd01235eb06d7bc853c1918a8ba873f6defe89ea1",
          "ExcelOutput/ClockParkStory.json",
              "b8e84eb71521a0f61bf45f897427cb72d8f94b2559f1e8024485e7d2e5ac2779",
          "ServerData/ClockParkStories.json",
              "92840d9743b0708323171b6e8389851f8a47fe29d7e6d6c9f235bec1cdcc01c3");

  public record Script(
      int id,
      int module,
      String kind,
      int startChapter,
      String prerequisiteKind,
      int prerequisiteId) {}

  public record Chapter(
      int id,
      List<Integer> next,
      List<Integer> rounds,
      List<Integer> gameplayRounds,
      List<Integer> stories,
      List<Integer> checkpoints,
      int successRound,
      String kind,
      int rewardId,
      int rewardProgress) {
    public boolean ending() {
      return kind.equals("TrueEnding") || kind.equals("FalseEnding");
    }
  }

  public static final class Story {
    private final int id;
    private final JsonObject config, graph;
    private final String fingerprint;

    private Story(int id, JsonObject config, JsonObject graph, String fingerprint) {
      this.id = id;
      this.config = config.deepCopy();
      this.graph = graph.deepCopy();
      this.fingerprint = fingerprint;
    }

    public int id() {
      return id;
    }

    public JsonObject config() {
      return config.deepCopy();
    }

    public JsonObject graph() {
      return graph.deepCopy();
    }

    public String fingerprint() {
      return fingerprint;
    }
  }

  private final Map<Integer, Script> scripts;
  private final Map<Integer, Chapter> chapters;
  private final Map<Integer, JsonObject> rounds;
  private final Map<Integer, Story> stories;

  private ClockParkCatalog(
      Map<Integer, Script> scripts,
      Map<Integer, Chapter> chapters,
      Map<Integer, JsonObject> rounds,
      Map<Integer, Story> stories) {
    this.scripts = Collections.unmodifiableMap(new TreeMap<>(scripts));
    this.chapters = Collections.unmodifiableMap(new TreeMap<>(chapters));
    this.rounds = Collections.unmodifiableMap(new TreeMap<>(rounds));
    this.stories = Collections.unmodifiableMap(new TreeMap<>(stories));
  }

  public Map<Integer, Script> scripts() {
    return scripts;
  }

  public Map<Integer, Chapter> chapters() {
    return chapters;
  }

  public Map<Integer, Story> stories() {
    return stories;
  }

  public int roundCount() {
    return rounds.size();
  }

  public JsonObject round(int id) {
    var value = rounds.get(id);
    return value == null ? null : value.deepCopy();
  }

  private static JsonElement read(Path root, String name) throws Exception {
    Path path = root.resolve(name);
    long size = Files.size(path);
    if (size <= 0 || size > 1024 * 1024) throw new IOException("Clock park resource size");
    byte[] bytes = Files.readAllBytes(path);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    if (!INPUTS.get(name).equals(hash))
      throw new IOException("Clock park resource identity: " + name);
    return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
  }

  private static int number(JsonObject row, String key) {
    var value = row.get(key);
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches("[0-9]+")) throw new IllegalArgumentException(key);
    return Integer.parseInt(value.getAsString());
  }

  private static List<Integer> numbers(JsonObject row, String key) {
    var values = new ArrayList<Integer>();
    for (var value : row.getAsJsonArray(key)) {
      var wrapper = new JsonObject();
      wrapper.add("value", value);
      values.add(number(wrapper, "value"));
    }
    if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException(key);
    return List.copyOf(values);
  }

  /** All five inputs and all references must match before any usable catalog is published. */
  public static ClockParkCatalog load(Path root) {
    try {
      var inputs = new HashMap<String, JsonElement>();
      for (String path : INPUTS.keySet()) inputs.put(path, read(root, path));
      var objects = inputs.get("ServerData/ClockParkStories.json").getAsJsonObject();
      if (!"hkrpg.clock-park.original-objects.v1".equals(objects.get("schema").getAsString()))
        return null;
      var configs = new HashMap<String, JsonObject>();
      var graphs = new HashMap<String, JsonObject>();
      var fingerprints = new HashMap<String, String>();
      for (var value : objects.getAsJsonArray("records")) {
        var row = value.getAsJsonObject();
        String kind = row.get("kind").getAsString(), path = row.get("sourcePath").getAsString();
        if (!row.get("fullyConsumed").getAsBoolean()) return null;
        var original = row.getAsJsonObject("value");
        if (!kind.equals(original.get("$type").getAsString())) return null;
        var map =
            switch (kind) {
              case "ClockParkStoryConfig" -> configs;
              case "LevelGraphConfig" -> graphs;
              default -> throw new IllegalArgumentException("Unknown clock park object");
            };
        if (map.putIfAbsent(path, original) != null
            || fingerprints.putIfAbsent(path, row.get("sha256").getAsString()) != null) return null;
      }
      if (configs.size() != 27 || graphs.size() != 27) return null;
      var stories = new HashMap<Integer, Story>();
      var usedGraphs = new HashSet<String>();
      var usedConfigs = new HashSet<String>();
      for (var value : inputs.get("ExcelOutput/ClockParkStory.json").getAsJsonArray()) {
        var row = value.getAsJsonObject();
        int id = number(row, "StoryID");
        String configPath = row.get("StoryJsonPath").getAsString();
        var config = configs.get(configPath);
        if (id <= 0 || config == null || !usedConfigs.add(configPath)) return null;
        String graphPath = config.get("LevelGraphConfigPath").getAsString();
        var graph = graphs.get(graphPath);
        if (graph == null || !usedGraphs.add(graphPath)) return null;
        var story =
            new Story(
                id,
                config,
                graph,
                fingerprints.get(configPath) + ":" + fingerprints.get(graphPath));
        if (stories.putIfAbsent(id, story) != null) return null;
      }
      if (stories.size() != 27
          || !usedConfigs.equals(configs.keySet())
          || !usedGraphs.equals(graphs.keySet())) return null;
      var rounds = new HashMap<Integer, JsonObject>();
      for (var value : inputs.get("ExcelOutput/ClockParkRound.json").getAsJsonArray()) {
        var row = value.getAsJsonObject();
        int id = number(row, "RoundID");
        if (id <= 0 || rounds.putIfAbsent(id, row.deepCopy()) != null) return null;
      }
      var chapters = new HashMap<Integer, Chapter>();
      for (var value : inputs.get("ExcelOutput/ClockParkChapterConfig.json").getAsJsonArray()) {
        var row = value.getAsJsonObject();
        var chapter =
            new Chapter(
                number(row, "ChapterID"),
                numbers(row, "NextChapterID"),
                numbers(row, "ChapterRoundIDList"),
                numbers(row, "ChapterGamePlayRoundRandomList"),
                numbers(row, "ChapterStoryIDList"),
                numbers(row, "CheckPointList"),
                row.has("SuccessToRoundID") ? number(row, "SuccessToRoundID") : 0,
                row.has("ChapterType") ? row.get("ChapterType").getAsString() : "Normal",
                row.has("RewardID") ? number(row, "RewardID") : 0,
                row.has("RewardProgress") ? number(row, "RewardProgress") : 0);
        if (chapter.id() <= 0
            || chapters.putIfAbsent(chapter.id(), chapter) != null
            || !rounds.keySet().containsAll(chapter.rounds())
            || !rounds.keySet().containsAll(chapter.gameplayRounds())
            || !stories.keySet().containsAll(chapter.stories())
            || chapter.successRound() != 0 && !rounds.containsKey(chapter.successRound()))
          return null;
      }
      for (var chapter : chapters.values())
        if (chapter.next().stream().anyMatch(id -> id != 0 && !chapters.containsKey(id)))
          return null;
      var scripts = new HashMap<Integer, Script>();
      for (var value : inputs.get("ExcelOutput/ClockParkScriptConfig.json").getAsJsonArray()) {
        var row = value.getAsJsonObject();
        var gates = row.getAsJsonArray("ScriptUnlockCondition");
        if (gates.size() != 1) return null;
        var gate = gates.get(0).getAsJsonObject();
        String gateKind = gate.get("Type").getAsString();
        if (!Set.of("FinishSubMission", "FinishMainMission").contains(gateKind)) return null;
        int gateId = Integer.parseInt(gate.get("Param").getAsString());
        var script =
            new Script(
                number(row, "ActivityStudioScriptID"),
                number(row, "ActivityModuleID"),
                row.get("ScriptType").getAsString(),
                number(row, "StartChapterID"),
                gateKind,
                gateId);
        if (script.id() <= 0
            || gateId <= 0
            || script.module() != 5001202
            || !Set.of("Normal", "Endless").contains(script.kind())
            || !chapters.containsKey(script.startChapter())
            || scripts.putIfAbsent(script.id(), script) != null) return null;
      }
      if (scripts.size() != 6 || chapters.size() != 28 || rounds.size() != 169) return null;
      return new ClockParkCatalog(scripts, chapters, rounds, stories);
    } catch (Exception invalid) {
      return null;
    }
  }

  private ClockParkCatalog() {
    throw new AssertionError();
  }
}
