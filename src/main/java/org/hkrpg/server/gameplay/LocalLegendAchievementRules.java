package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Original BattleChallenge declarations; sort and reward tabs are not completion conditions. */
public final class LocalLegendAchievementRules {
  private static final Set<Integer> SOURCE_IDS =
      Set.of(
          6072147, 6072148, 6072150, 6072151, 6072153, 6072154, 6072156, 6072157, 6072159, 6072160);

  // Pinned Config/ConfigAbility/Level/Level_LocalLegend_Ability.json at 724b139d.
  // These exact OnLeaveBattle/ByLocalWin leaves emit the achievement; no tab/sort inference.
  private record ScopedAbility(String name, int group, int difficulty) {}

  private static final Map<Integer, ScopedAbility> ABILITIES =
      Map.ofEntries(
          Map.entry(30213, new ScopedAbility("BattleTarget_LocalLegend_S1_1", 1, 2)),
          Map.entry(30214, new ScopedAbility("BattleTarget_LocalLegend_S1_5", 1, 3)),
          Map.entry(30216, new ScopedAbility("BattleTarget_LocalLegend_S2_1", 2, 2)),
          Map.entry(30217, new ScopedAbility("BattleTarget_LocalLegend_S2_5", 2, 3)),
          Map.entry(30219, new ScopedAbility("BattleTarget_LocalLegend_S3_1", 3, 2)),
          Map.entry(30220, new ScopedAbility("BattleTarget_LocalLegend_S3_5", 3, 3)),
          Map.entry(30222, new ScopedAbility("BattleTarget_LocalLegend_S4_1", 4, 2)),
          Map.entry(30223, new ScopedAbility("BattleTarget_LocalLegend_S4_5", 4, 3)),
          Map.entry(30225, new ScopedAbility("BattleTarget_LocalLegend_S5_1", 5, 2)),
          Map.entry(30226, new ScopedAbility("BattleTarget_LocalLegend_S5_5", 5, 3)));

  public record Rule(
      int quest,
      int achievement,
      int reward,
      int group,
      int difficulty,
      String ability,
      String resourceIdentity,
      String questSignature,
      String finishSignature,
      String rewardSignature,
      Map<Integer, Integer> amounts) {
    public Rule {
      amounts = Map.copyOf(amounts);
    }
  }

  private static volatile Map<Integer, Rule> current = Map.of();

  static void clear() {
    current = Map.of();
  }

  static void load(Path root) throws Exception {
    clear();
    var tables = List.of("QuestData", "FinishWay", "RewardData", "BattleAchievement");
    long present =
        tables.stream()
            .filter(n -> Files.isRegularFile(root.resolve("ExcelOutput/" + n + ".json")))
            .count();
    if (present == 0 || LocalLegendCatalog.rewards().isEmpty()) return;
    if (present != tables.size()) invalid();
    var quests = table(root, "QuestData", "QuestID", 16384);
    var finishes = table(root, "FinishWay", "ID", 16384);
    var rewards = table(root, "RewardData", "RewardID", 16384);
    var achievements = table(root, "BattleAchievement", "BattleAchievementID", 2048);
    var rules = new HashMap<Integer, Rule>();
    var identities = new HashSet<Integer>();
    for (var task : LocalLegendCatalog.rewards()) {
      if (!SOURCE_IDS.contains(task.quest())) invalid();
      var q = quests.get(task.quest());
      var f = finishes.get(task.quest());
      if (q == null || f == null) invalid();
      fields(
          q,
          Set.of(
              "QuestID",
              "QuestType",
              "QuestTitle",
              "ImagePath",
              "UnlockType",
              "UnlockParamList",
              "RewardID",
              "FinishWayID"));
      fields(
          f,
          Set.of(
              "ID",
              "FinishType",
              "ParamType",
              "ParamInt1",
              "ParamInt2",
              "ParamInt3",
              "ParamStr1",
              "ParamIntList",
              "ParamItemList",
              "Progress",
              "IsBackTrack"));
      if (LocalLegendCatalog.positive(q, "QuestType") != 1
          || LocalLegendCatalog.positive(q, "FinishWayID") != task.quest()
          || !LocalLegendCatalog.text(q, "UnlockType").equals("AutoUnlock")
          || !LocalLegendCatalog.integers(q, "UnlockParamList", 32, true).isEmpty()
          || !LocalLegendCatalog.text(f, "FinishType").equals("BattleChallenge")
          || !LocalLegendCatalog.text(f, "ParamType").equals("ListContain")
          || LocalLegendCatalog.positive(f, "Progress") != 1
          || optionalInt(f, "ParamInt2") != 0
          || optionalInt(f, "ParamInt3") != 0
          || !emptyString(f, "ParamStr1")
          || f.getAsJsonArray("ParamItemList") == null
          || !f.getAsJsonArray("ParamItemList").isEmpty()
          || f.has("IsBackTrack")
              && (!f.get("IsBackTrack").isJsonPrimitive()
                  || !f.get("IsBackTrack").getAsJsonPrimitive().isBoolean()
                  || f.get("IsBackTrack").getAsBoolean())) invalid();
      int achievement = LocalLegendCatalog.positive(f, "ParamInt1");
      var binding = ABILITIES.get(achievement);
      if (binding == null) invalid();
      String ability = binding.name();
      var sourceLevel = LocalLegendCatalog.level(binding.group(), binding.difficulty());
      if (sourceLevel == null
          || sourceLevel.goals().stream().noneMatch(g -> ability.equals(g.ability()))) invalid();
      if (!LocalLegendCatalog.integers(f, "ParamIntList", 32, false).equals(List.of(achievement))
          || !identities.add(achievement)) invalid();
      var a = achievements.get(achievement);
      int reward = LocalLegendCatalog.positive(q, "RewardID");
      var r = rewards.get(reward);
      if (a != null)
        fields(
            a,
            Set.of(
                "BattleAchievementID",
                "AbilityName",
                "NeedTagList",
                "ExcludeTagList",
                "GameModeGroup"));
      if (r != null)
        fields(
            r,
            Set.of(
                "RewardID",
                "Hcoin",
                "ItemID_1",
                "Count_1",
                "ItemID_2",
                "Count_2",
                "ItemID_3",
                "Count_3",
                "ItemID_4",
                "Count_4",
                "ItemID_5",
                "Count_5"));
      if (a == null
          || r == null
          || !emptyString(a, "AbilityName")
          || a.getAsJsonArray("NeedTagList") == null
          || !a.getAsJsonArray("NeedTagList").isEmpty()
          || a.getAsJsonArray("ExcludeTagList") == null
          || !a.getAsJsonArray("ExcludeTagList").isEmpty()
          || optionalInt(a, "GameModeGroup") != 0) invalid();
      var expected = ResourceLoader.getGson().fromJson(r, RewardExcel.class);
      var amounts = rewardAmounts(r);
      var identity = task + ":" + binding + ":" + q + ":" + f + ":" + r + ":" + a;
      var digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(identity.getBytes(StandardCharsets.UTF_8)));
      rules.put(
          task.quest(),
          new Rule(
              task.quest(),
              achievement,
              reward,
              binding.group(),
              binding.difficulty(),
              ability,
              digest,
              signature(ResourceLoader.getGson().fromJson(q, QuestExcel.class)),
              signature(ResourceLoader.getGson().fromJson(f, FinishWayExcel.class)),
              signature(expected),
              amounts));
    }
    if (!rules.keySet().equals(SOURCE_IDS)) invalid();
    current = Map.copyOf(rules);
  }

  private static Map<Integer, JsonObject> table(Path root, String name, String key, int limit)
      throws Exception {
    var result = new HashMap<Integer, JsonObject>();
    for (var row : LocalLegendCatalog.rows(root, name, limit))
      if (result.putIfAbsent(LocalLegendCatalog.positive(row, key), row) != null) invalid();
    return result;
  }

  private static int optionalInt(JsonObject row, String name) {
    return row.has(name) ? (int) LocalLegendCatalog.integer(row, name, true, Integer.MAX_VALUE) : 0;
  }

  private static void fields(JsonObject row, Set<String> supported) {
    if (!supported.containsAll(row.keySet())) invalid();
  }

  private static boolean emptyString(JsonObject row, String name) {
    var value = row.get(name);
    return value != null
        && value.isJsonPrimitive()
        && value.getAsJsonPrimitive().isString()
        && value.getAsString().isEmpty();
  }

  private static Map<Integer, Integer> rewardAmounts(JsonObject row) {
    var amounts = new TreeMap<Integer, Integer>();
    int currency = optionalInt(row, "Hcoin");
    if (currency > 0) amounts.put(GameConstants.MATERIAL_HCOIN_ID, currency);
    for (int i = 1; i <= 5; i++) {
      int id = optionalInt(row, "ItemID_" + i);
      int count = optionalInt(row, "Count_" + i);
      if ((id == 0) != (count == 0)) invalid();
      if (id > 0) amounts.merge(id, count, Math::addExact);
    }
    if (amounts.isEmpty()) invalid();
    return Map.copyOf(amounts);
  }

  private static String signature(Object value) {
    return ResourceLoader.getGson().toJson(value);
  }

  public static boolean belongs(QuestExcel definition) {
    return definition != null && SOURCE_IDS.contains(definition.getId());
  }

  public static Rule rule(int quest) {
    var rule = current.get(quest);
    if (rule == null) return null;
    var q = GameData.getQuestExcelMap().get(quest);
    var f = GameData.getFinishWayExcelMap().get(quest);
    var r = GameData.getRewardExcelMap().get(rule.reward());
    return q != null
            && f != null
            && r != null
            && rule.questSignature().equals(signature(q))
            && rule.finishSignature().equals(signature(f))
            && rule.rewardSignature().equals(signature(r))
            && r.getRewards() != null
            && rule.amounts().equals(r.getRewards())
        ? rule
        : null;
  }

  public static List<Rule> rules() {
    return current.values().stream().sorted(Comparator.comparingInt(Rule::quest)).toList();
  }

  public static boolean allowed(Rule rule, LocalLegendCatalog.Level level) {
    return rule != null
        && current.get(rule.quest()) == rule
        && level != null
        && LocalLegendCatalog.level(level.group(), level.difficulty()) == level
        && rule.group() == level.group()
        && rule.difficulty() == level.difficulty()
        && level.goals().stream().anyMatch(g -> rule.ability().equals(g.ability()));
  }

  /** Exact pinned OnLeaveBattle/ByLocalWin leaves, independent of an optional statistics echo. */
  public static Optional<Set<Rule>> winningRules(LocalLegendCatalog.Level level) {
    if (level == null || LocalLegendCatalog.level(level.group(), level.difficulty()) != level)
      return Optional.empty();
    var result = new HashSet<Rule>();
    for (var declared : current.values()) {
      if (declared.group() != level.group() || declared.difficulty() != level.difficulty())
        continue;
      var rule = rule(declared.quest());
      if (rule == null || !allowed(rule, level)) return Optional.empty();
      result.add(rule);
    }
    return Optional.of(Set.copyOf(result));
  }

  /** Only actual native achievement categories 2 and 6, never scoring/raid/target categories. */
  public static Optional<Set<Rule>> reported(
      LocalLegendCatalog.Level level, BattleStatistics statistics) {
    if (statistics == null) return Optional.empty();
    var result = new HashSet<Rule>();
    for (var category : statistics.getBattleTargetInfo()) {
      if (category.getKey() != 2 && category.getKey() != 6) continue;
      for (var target : category.getValue().getBattleTargetList()) {
        var declared =
            current.values().stream()
                .filter(r -> r.achievement() == target.getId())
                .findFirst()
                .orElse(null);
        if (declared == null) continue;
        var rule = rule(declared.quest());
        if (rule == null || !allowed(rule, level)) return Optional.empty();
        if (Integer.toUnsignedLong(target.getProgress()) >= 1) result.add(rule);
      }
    }
    return Optional.of(Set.copyOf(result));
  }

  private static void invalid() {
    throw new IllegalArgumentException("Invalid Local Legend achievement declarations");
  }

  private LocalLegendAchievementRules() {}
}
