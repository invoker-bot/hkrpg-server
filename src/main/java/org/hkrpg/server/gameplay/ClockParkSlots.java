package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Original slot rewards with an explicit project card assignment and weighted ticket rule. */
public final class ClockParkSlots {
  public static final String RULE = "hkrpg.clockpark.slot-51008-card5150-weighted-ticket.v1";
  public static final String CHAPTER_RULE =
      "hkrpg.clockpark.original-chapter-slot-weighted-ticket.v2";

  public record Stage(int chapter, int card, int round, int normals, int checkpoint) {}

  private static final Map<Integer, Stage> STAGES =
      Map.of(
          510, new Stage(510, 5150, 51008, 4, 501),
          520, new Stage(520, 5250, 52008, 4, 502),
          530, new Stage(530, 5350, 53010, 5, 503),
          540, new Stage(540, 5450, 54010, 5, 503));

  public static Stage stage(int chapter) {
    return STAGES.get(chapter);
  }

  public static String rule(int chapter) {
    return chapter == 510 ? RULE : CHAPTER_RULE;
  }

  public static final String LOTTERY_SOURCE =
      "d9ddc0b75e0002ff0cc58e0aaabba5f8d4578d4365131b61058230bd3dff7ee4";

  private record Reward(int id, int gain, int weight) {}

  // Card/action/effect/constant hashes and the lottery bytes remain current-file checks.
  private static volatile ClockParkSlots verifiedDefinitions;
  private final String source;
  private final List<Reward> rewards;

  private ClockParkSlots(String source, List<Reward> rewards) {
    this.source = source;
    this.rewards = List.copyOf(rewards);
  }

  public String fingerprint() {
    return source;
  }

  public static boolean supports(ClockParkCatalog catalog, int script, int chapter) {
    var stage = stage(chapter);
    if (stage == null) return false;
    var row = catalog.chapters().get(chapter);
    var round = catalog.round(stage.round());
    return script == 5
        && row != null
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
        && "SlotMachine".equals(round.get("RoundType").getAsString());
  }

  private static int number(JsonObject row, String name) {
    return row.has(name) ? row.get(name).getAsInt() : 0;
  }

  private static List<Integer> numbers(JsonArray rows) {
    return rows.asList().stream().map(JsonElement::getAsInt).toList();
  }

  private static JsonObject row(Path root, String name, String key, int id) throws Exception {
    var rows =
        JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/" + name + ".json")))
            .getAsJsonArray();
    var matches =
        rows.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(v -> number(v, key) == id)
            .toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  public static ClockParkSlots load(Path root) {
    return load(root, ClockParkCards.load(root));
  }

  // Only a validation of this same root may reuse its already verified card definitions.
  static ClockParkSlots load(Path root, ClockParkCards cards) {
    try {
      if (cards == null) return null;
      var path = root.resolve("ExcelOutput/ClockParkLottery.json");
      if (Files.size(path) <= 0 || Files.size(path) > 65536) return null;
      String text = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(text.getBytes(StandardCharsets.UTF_8)));
      if (!LOTTERY_SOURCE.equals(digest)) return null;
      var cached = verifiedDefinitions;
      if (cached != null) return cached;
      for (var stage : STAGES.values()) {
        int actionId = stage.card() * 100 + 1;
        var card = row(root, "ClockParkCard", "CardID", stage.card());
        var action = row(root, "ClockParkCardAction", "CardActionID", actionId);
        var cost = row(root, "ClockParkEffect", "EffectID", actionId);
        var list = row(root, "ClockParkEffect", "EffectID", actionId + 10);
        if (card == null
            || action == null
            || cost == null
            || list == null
            || !"Gameplay".equals(card.get("CardType").getAsString())
            || number(card, "CardDiceNum") != 1
            || !numbers(card.getAsJsonArray("CardActionList")).equals(List.of(actionId))
            || !numbers(action.getAsJsonArray("DiceList")).equals(List.of(1, 2, 3, 4, 5, 6))
            || !numbers(action.getAsJsonArray("EffectList"))
                .equals(List.of(actionId, actionId + 10))
            || !action.getAsJsonArray("SuccessEffectList").isEmpty()
            || !"LotteryCost".equals(cost.get("EffectType").getAsString())
            || number(cost, "Param1") != 0
            || number(cost, "Param2") != 0
            || number(cost, "Param3") != 0
            || !"LotteryIDList".equals(list.get("EffectType").getAsString())
            || !numbers(list.getAsJsonArray("ParamList")).equals(List.of(42, 32, 22, 12)))
          return null;
      }
      var constants =
          JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/ClockParkConst.json")))
              .getAsJsonArray();
      var playtimes =
          constants.asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(
                  v -> "Maximum_Lottery_Playtimes".equals(v.get("ConstValueName").getAsString()))
              .toList();
      if (playtimes.size() != 1
          || number(playtimes.getFirst().getAsJsonObject("Value"), "IntValue") != 1) return null;
      var all = JsonParser.parseString(text).getAsJsonArray();
      if (all.size() != 12) return null;
      var rewards = new ArrayList<Reward>();
      for (int id : List.of(42, 32, 22, 12)) {
        var values =
            all.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(v -> number(v, "LotteryID") == id)
                .toList();
        if (values.size() != 1) return null;
        var value = values.getFirst();
        var gain = value.getAsJsonObject("LotteryAttributeGain");
        if (gain.size() != 1 || !gain.has("AttributeB")) return null;
        rewards.add(new Reward(id, number(gain, "AttributeB"), number(value, "Weight")));
      }
      if (!rewards.equals(
          List.of(
              new Reward(42, 4, 1),
              new Reward(32, 6, 9),
              new Reward(22, 8, 640),
              new Reward(12, 12, 350)))) return null;
      var verified = new ClockParkSlots(cards.fingerprint() + ":" + digest, rewards);
      verifiedDefinitions = verified;
      return verified;
    } catch (Exception invalid) {
      return null;
    }
  }

  public int lottery(int ticket) {
    if (ticket < 0 || ticket >= 1000)
      throw new IllegalArgumentException("Invalid saved slot ticket");
    int boundary = 0;
    for (var reward : rewards) {
      boundary += reward.weight();
      if (ticket < boundary) return reward.id();
    }
    throw new IllegalStateException();
  }

  public int attributeB(int before, int ticket) {
    int id = lottery(ticket);
    int gain = rewards.stream().filter(v -> v.id() == id).findFirst().orElseThrow().gain();
    return Math.min(100, Math.addExact(before, gain));
  }
}
