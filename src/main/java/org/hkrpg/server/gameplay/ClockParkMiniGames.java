package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Matched original cards with separately versioned project settlement policies. */
public final class ClockParkMiniGames {
  public static final String LEGACY_RULE = "hkrpg.clockpark.original-mini-card-zero-grade.v1";
  public static final String RULE = "hkrpg.clockpark.project-cumulative-mini-grade.v2";

  public record Stage(
      int script,
      int chapter,
      int round,
      int card,
      int action,
      int normals,
      int checkpoint,
      String phase,
      String roundType,
      String effectType,
      int stageA,
      int stageB,
      List<Integer> attributes,
      List<Integer> scores,
      List<Integer> thresholds) {}

  private static final Map<Integer, Stage> STAGES =
      Map.of(
          302,
              new Stage(
                  3,
                  302,
                  30208,
                  3405,
                  341001,
                  4,
                  321,
                  "HANU",
                  "HanuShooting",
                  "ShootingGameStageID",
                  0,
                  99,
                  List.of(1, 0, 0),
                  List.of(0, 2, 2, 2),
                  List.of(1, 2, 3)),
          410,
              new Stage(
                  4,
                  410,
                  41009,
                  4121,
                  415001,
                  8,
                  411,
                  "MOVIE",
                  "MovieGame",
                  "MovieGameStageID",
                  50001,
                  0,
                  List.of(1, 1, 1),
                  List.of(0, 1, 1, 1),
                  List.of(2000, 3000, 4000)),
          420,
              new Stage(
                  4,
                  420,
                  42009,
                  4225,
                  425001,
                  8,
                  421,
                  "MOVIE",
                  "MovieGame",
                  "MovieGameStageID",
                  50002,
                  0,
                  List.of(1, 1, 1),
                  List.of(0, 1, 1, 1),
                  List.of(2000, 4000, 5000)));
  private static volatile ClockParkMiniGames verifiedDefinitions;
  private final String source;

  private ClockParkMiniGames(String source) {
    this.source = source;
  }

  public String fingerprint() {
    return fingerprint(RULE);
  }

  public String fingerprint(String rule) {
    if (!knownRule(rule)) throw new IllegalArgumentException("Unknown mini game policy");
    return source + ":" + rule;
  }

  public static boolean knownRule(String rule) {
    return RULE.equals(rule) || LEGACY_RULE.equals(rule);
  }

  public static Stage stage(int chapter) {
    return STAGES.get(chapter);
  }

  public static boolean supports(ClockParkCatalog catalog, int script, int chapter) {
    var stage = stage(chapter);
    if (stage == null || stage.script() != script) return false;
    var row = catalog.chapters().get(chapter);
    var round = catalog.round(stage.round());
    return row != null
        && row.rounds()
            .equals(
                java.util.stream.IntStream.rangeClosed(
                        chapter * 100 + 1, chapter * 100 + stage.normals())
                    .boxed()
                    .toList())
        && row.gameplayRounds().equals(List.of(stage.round()))
        && row.checkpoints().equals(List.of(stage.checkpoint()))
        && round != null
        && round.has("RoundType")
        && stage.roundType().equals(round.get("RoundType").getAsString());
  }

  public static boolean legalReport(Stage stage, boolean win, int score) {
    return stage != null
        && score >= 0
        && score <= 3
        && (win || score == 0)
        && (!stage.phase().equals("HANU") || win == (score > 0));
  }

  public static boolean settledReport(Stage stage, boolean win, int score) {
    return settledReport(stage, win, score, RULE);
  }

  public static boolean settledReport(Stage stage, boolean win, int score, String rule) {
    return knownRule(rule) && legalReport(stage, win, score) && (RULE.equals(rule) || score == 0);
  }

  /** The project policy sums grade increments; this is not a verified official formula. */
  public int[] apply(int chapter, int[] attributes, boolean win, int grade, String rule) {
    var stage = stage(chapter);
    if (!settledReport(stage, win, grade, rule) || attributes == null || attributes.length != 3)
      throw new IllegalArgumentException("Invalid mini game settlement");
    // ClockParkCards.load verifies the full original Const input and these script caps.
    int cap = stage.script() == 4 ? 60 : 100;
    int[] result = attributes.clone();
    int points = 0;
    if (RULE.equals(rule))
      for (int i = 0; i <= grade; i++) points = Math.addExact(points, stage.scores().get(i));
    for (int i = 0; i < 3; i++) {
      if (result[i] < 0 || result[i] > cap)
        throw new IllegalArgumentException("Invalid mini game attribute");
      result[i] =
          Math.min(
              cap, Math.addExact(result[i], Math.multiplyExact(points, stage.attributes().get(i))));
    }
    return result;
  }

  private static int number(JsonObject row, String key) {
    return row.has(key) ? row.get(key).getAsInt() : 0;
  }

  private static List<Integer> numbers(JsonObject row, String key) {
    return row.getAsJsonArray(key).asList().stream().map(JsonElement::getAsInt).toList();
  }

  private static JsonObject row(JsonArray rows, String key, int id) {
    var matched =
        rows.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> number(r, key) == id)
            .toList();
    return matched.size() == 1 ? matched.getFirst() : null;
  }

  public static ClockParkMiniGames load(Path root) {
    try {
      // All four complete original inputs are hashed on every request, including cache hits.
      var cards = ClockParkCards.load(root);
      if (cards == null) return null;
      var cached = verifiedDefinitions;
      if (cached != null) return cached;
      var cardRows =
          JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/ClockParkCard.json")))
              .getAsJsonArray();
      var actions =
          JsonParser.parseString(
                  Files.readString(root.resolve("ExcelOutput/ClockParkCardAction.json")))
              .getAsJsonArray();
      var effects =
          JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/ClockParkEffect.json")))
              .getAsJsonArray();
      for (var s : STAGES.values()) {
        var card = row(cardRows, "CardID", s.card());
        var action = row(actions, "CardActionID", s.action());
        var stage = row(effects, "EffectID", s.action());
        var score = row(effects, "EffectID", s.action() + 10);
        if (card == null
            || action == null
            || stage == null
            || score == null
            || !"Gameplay".equals(card.get("CardType").getAsString())
            || number(card, "CardDiceNum") != 1
            || !numbers(card, "CardActionList").equals(List.of(s.action()))
            || !numbers(action, "DiceList").equals(List.of(1, 2, 3, 4, 5, 6))
            || !numbers(action, "EffectList").equals(List.of(s.action()))
            || !numbers(action, "SuccessEffectList").equals(List.of(s.action() + 10))
            || !s.effectType().equals(stage.get("EffectType").getAsString())
            || number(stage, "Param1") != s.stageA()
            || number(stage, "Param2") != s.stageB()
            || number(stage, "Param3") != 0
            || !"AttributeChangeByGameScore".equals(score.get("EffectType").getAsString())
            || !List.of(number(score, "Param1"), number(score, "Param2"), number(score, "Param3"))
                .equals(s.attributes())
            || !numbers(score, "ParamList").equals(s.scores())
            || !numbers(score, "PlayCardEffectDescParamList").equals(s.thresholds())) return null;
      }
      var verified = new ClockParkMiniGames(cards.fingerprint());
      verifiedDefinitions = verified;
      return verified;
    } catch (Exception invalid) {
      return null;
    }
  }
}
