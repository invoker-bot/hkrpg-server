package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Original effects with a small explicit project pool; official round draws remain unverified. */
public final class ClockParkCards {
  public static final String POOL_RULE = "hkrpg.clockpark.cards-1001-1002-1003.v1";
  public static final List<Integer> POOL = List.of(1001, 1002, 1003);
  public static final String BALANCED_RULE = "hkrpg.clockpark.cards-1001-1007-1008.v2";
  public static final List<Integer> BALANCED_POOL = List.of(1001, 1007, 1008);
  private static final Map<String, List<Integer>> POOLS =
      Map.of(POOL_RULE, POOL, BALANCED_RULE, BALANCED_POOL);
  private static final Set<Integer> POOL_CARDS = Set.of(1001, 1002, 1003, 1007, 1008);
  public static final String BRANCH_RULE = "hkrpg.clockpark.single-next-chapter.v1";
  public static final String CONDITIONAL_BRANCH_RULE = "hkrpg.clockpark.original-face-branch.v2";
  private static final Map<Integer, List<Integer>> BRANCH_TARGETS =
      Map.ofEntries(
          Map.entry(1101, Collections.nCopies(6, 102)),
          Map.entry(1102, Collections.nCopies(6, 103)),
          Map.entry(2101, Collections.nCopies(6, 202)),
          Map.entry(2102, List.of(204, 204, 203, 203, 203, 203)),
          Map.entry(2103, Collections.nCopies(6, 205)),
          Map.entry(2104, Collections.nCopies(6, 206)),
          Map.entry(3101, Collections.nCopies(6, 302)),
          Map.entry(3102, List.of(304, 304, 303, 303, 303, 303)),
          Map.entry(3103, Collections.nCopies(6, 305)),
          Map.entry(3104, Collections.nCopies(6, 306)),
          Map.entry(4122, Collections.nCopies(6, 420)),
          Map.entry(4226, List.of(440, 440, 430, 430, 430, 430)),
          Map.entry(4322, Collections.nCopies(6, 450)),
          Map.entry(4422, Collections.nCopies(6, 460)),
          Map.entry(5119, Collections.nCopies(6, 520)),
          Map.entry(5221, List.of(540, 540, 530, 530, 530, 530)),
          Map.entry(5327, Collections.nCopies(6, 550)),
          Map.entry(5427, Collections.nCopies(6, 560)));
  private static final Map<String, String> INPUTS =
      Map.of(
          "ClockParkCard", "e06a1c81803e75f20c0a10ea32ae4893fa425b4319ef20bfb74ae393a2796c4b",
          "ClockParkCardAction", "e2f43b84a5463778acf460a21f3f71050daa4ab4ff304f228ceda9fda62035f7",
          "ClockParkEffect", "881c2572bae82f99f110ebcbeb89510f8b099d673a2eaf61ebd397b3512f3444",
          "ClockParkConst", "aae6c729b32f66c28c220b9d35c4c23ead2550d2ecd306edb876a566a052d00e");
  // Only immutable definitions are shared; all four current files are verified on every load.
  private static volatile ClockParkCards verifiedDefinitions;
  private final Map<Integer, List<Action>> actions;
  private final List<Integer> caps;
  private final Map<Integer, List<Integer>> branches;

  public record Effect(int a, int b, int c, int diceParam) {}

  public record Action(Set<Integer> faces, List<Effect> effects) {}

  private ClockParkCards(
      Map<Integer, List<Action>> actions,
      List<Integer> caps,
      Map<Integer, List<Integer>> branches) {
    this.actions = Map.copyOf(actions);
    this.caps = List.copyOf(caps);
    this.branches = Map.copyOf(branches);
  }

  public int branchCard(int chapter) {
    return switch (chapter) {
      case 101 -> 1101;
      case 102 -> 1102;
      case 201 -> 2101;
      case 202 -> 2102;
      case 203 -> 2103;
      case 204 -> 2104;
      case 301 -> 3101;
      case 302 -> 3102;
      case 303 -> 3103;
      case 304 -> 3104;
      case 410 -> 4122;
      case 420 -> 4226;
      case 430 -> 4322;
      case 440 -> 4422;
      case 510 -> 5119;
      case 520 -> 5221;
      case 530 -> 5327;
      case 540 -> 5427;
      default -> 0;
    };
  }

  public int branchTarget(int card) {
    var targets = branchTargets(card);
    return targets.size() == 1 ? targets.iterator().next() : 0;
  }

  public int branchTarget(int card, int face) {
    var targets = branches.get(card);
    return targets == null || face < 1 || face > 6 ? 0 : targets.get(face - 1);
  }

  public Set<Integer> branchTargets(int card) {
    return branches.containsKey(card) ? Set.copyOf(branches.get(card)) : Set.of();
  }

  public String branchRule(int card) {
    return branchTargets(card).size() > 1 ? CONDITIONAL_BRANCH_RULE : BRANCH_RULE;
  }

  public static List<Integer> pool(String rule) {
    return rule == null ? null : POOLS.get(rule);
  }

  public String fingerprint() {
    return INPUTS.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> e.getValue())
        .reduce((a, b) -> a + ":" + b)
        .orElseThrow();
  }

  private static int number(JsonObject row, String field) {
    return row.has(field) ? row.get(field).getAsInt() : 0;
  }

  public static ClockParkCards load(Path root) {
    try {
      var originals = new HashMap<String, byte[]>();
      for (var e : INPUTS.entrySet()) {
        var path = root.resolve("ExcelOutput/" + e.getKey() + ".json");
        long size = Files.size(path);
        if (size <= 0 || size > 1024 * 1024) return null;
        var raw = Files.readAllBytes(path);
        if (!e.getValue()
            .equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))))
          return null;
        originals.put(e.getKey(), raw);
      }
      var cached = verifiedDefinitions;
      if (cached != null) return cached;
      var tables = new HashMap<String, JsonArray>();
      for (var e : originals.entrySet())
        tables.put(
            e.getKey(),
            JsonParser.parseString(
                    new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonArray());
      var effectRows = new HashMap<Integer, JsonObject>();
      for (var v : tables.get("ClockParkEffect"))
        effectRows.put(number(v.getAsJsonObject(), "EffectID"), v.getAsJsonObject());
      var actionRows = new HashMap<Integer, JsonObject>();
      for (var v : tables.get("ClockParkCardAction"))
        actionRows.put(number(v.getAsJsonObject(), "CardActionID"), v.getAsJsonObject());
      var actions = new HashMap<Integer, List<Action>>();
      var branches = new HashMap<Integer, List<Integer>>();
      for (var v : tables.get("ClockParkCard")) {
        var card = v.getAsJsonObject();
        int id = number(card, "CardID");
        if (BRANCH_TARGETS.containsKey(id)) {
          if (number(card, "CardDiceNum") != 1
              || !"ToChapter".equals(card.get("CardType").getAsString())) return null;
          var targets = new Integer[6];
          for (var actionId : card.getAsJsonArray("CardActionList")) {
            var action = actionRows.get(actionId.getAsInt());
            if (action == null
                || !action.getAsJsonArray("SuccessEffectList").isEmpty()
                || action.getAsJsonArray("EffectList").size()
                    != (Set.of(4122, 4322, 4422).contains(id) ? 4 : 1)
                || action.getAsJsonArray("DiceList").isEmpty()) return null;
            var effect = effectRows.get(action.getAsJsonArray("EffectList").get(0).getAsInt());
            if (effect == null || !"ToChapter".equals(effect.get("EffectType").getAsString()))
              return null;
            // Original fourth-script branches also carry three zero attribute deltas.
            // Verify all of them; never interpret a delta of zero as resetting an attribute.
            for (int i = 1; i < action.getAsJsonArray("EffectList").size(); i++) {
              var extra = effectRows.get(action.getAsJsonArray("EffectList").get(i).getAsInt());
              if (extra == null
                  || !"AttributeChange".equals(extra.get("EffectType").getAsString())
                  || number(extra, "Param1") != 0
                  || number(extra, "Param2") != 0
                  || number(extra, "Param3") != 0) return null;
            }
            for (var value : action.getAsJsonArray("DiceList")) {
              int face = value.getAsInt();
              if (face < 1 || face > 6 || targets[face - 1] != null) return null;
              targets[face - 1] = number(effect, "Param1");
            }
          }
          if (!Arrays.asList(targets).equals(BRANCH_TARGETS.get(id))
              || branches.putIfAbsent(id, List.of(targets)) != null) return null;
        }
        if (!POOL_CARDS.contains(id)) continue;
        if (number(card, "CardDiceNum") != 1
            || !"AttributeChange".equals(card.get("CardType").getAsString())) return null;
        var values = new ArrayList<Action>();
        var covered = new HashSet<Integer>();
        for (var actionId : card.getAsJsonArray("CardActionList")) {
          var row = actionRows.get(actionId.getAsInt());
          if (row == null || !row.getAsJsonArray("SuccessEffectList").isEmpty()) return null;
          var faces = new HashSet<Integer>();
          for (var n : row.getAsJsonArray("DiceList")) {
            int f = n.getAsInt();
            if (f < 1 || f > 6 || !faces.add(f) || !covered.add(f)) return null;
          }
          var effects = new ArrayList<Effect>();
          for (var effectId : row.getAsJsonArray("EffectList")) {
            var e = effectRows.get(effectId.getAsInt());
            if (e == null || !"AttributeChangeByDice".equals(e.get("EffectType").getAsString()))
              return null;
            effects.add(
                new Effect(
                    number(e, "Param1"),
                    number(e, "Param2"),
                    number(e, "Param3"),
                    number(e, "DiceParam")));
          }
          if (faces.isEmpty() || effects.isEmpty()) return null;
          values.add(new Action(Set.copyOf(faces), List.copyOf(effects)));
        }
        if (!covered.equals(Set.of(1, 2, 3, 4, 5, 6))
            || actions.putIfAbsent(id, List.copyOf(values)) != null) return null;
      }
      var caps = new ArrayList<Integer>();
      for (var v : tables.get("ClockParkConst")) {
        var row = v.getAsJsonObject();
        if (!"Max_Attribute_Script".equals(row.get("ConstValueName").getAsString())) continue;
        for (var n : row.getAsJsonObject("Value").getAsJsonArray("ArrayValue"))
          caps.add(n.getAsJsonObject().get("IntValue").getAsInt());
      }
      if (actions.size() != POOL_CARDS.size()
          || !branches.equals(BRANCH_TARGETS)
          || !caps.equals(List.of(50, 100, 100, 60, 100, 100))) return null;
      var verified = new ClockParkCards(actions, caps, branches);
      verifiedDefinitions = verified;
      return verified;
    } catch (Exception invalid) {
      return null;
    }
  }

  public static boolean facesValid(List<Integer> faces) {
    return faces != null
        && faces.size() == 3
        && faces.stream().allMatch(n -> n != null && n >= 1 && n <= 6);
  }

  public static boolean indicesValid(List<Integer> indices) {
    return indices != null && indices.size() == 3 && new HashSet<>(indices).equals(Set.of(0, 1, 2));
  }

  /** Native slots carry faces. Match their multiplicities to distinct, already saved dice. */
  public static List<Integer> assignFaces(List<Integer> saved, List<Integer> placed) {
    if (!facesValid(saved) || !facesValid(placed)) return null;
    var indices = new ArrayList<Integer>();
    boolean[] used = new boolean[3];
    for (int face : placed) {
      int match = -1;
      for (int i = 0; i < saved.size(); i++)
        if (!used[i] && saved.get(i) == face) {
          match = i;
          break;
        }
      if (match < 0) return null;
      used[match] = true;
      indices.add(match);
    }
    return List.copyOf(indices);
  }

  /** Server faces, not client values, drive the verified native coefficient formula. */
  public int[] apply(int script, int[] before, List<Integer> faces, List<Integer> indices) {
    return apply(script, before, faces, indices, POOL_RULE);
  }

  public int[] apply(
      int script, int[] before, List<Integer> faces, List<Integer> indices, String rule) {
    var pool = pool(rule);
    if (script < 1
        || script > 5
        || !facesValid(faces)
        || !indicesValid(indices)
        || before.length != 3
        || pool == null) throw new IllegalArgumentException();
    int[] out = before.clone();
    int cap = caps.get(script - 1);
    for (int i = 0; i < pool.size(); i++) {
      int face = faces.get(indices.get(i));
      var action =
          actions.get(pool.get(i)).stream()
              .filter(a -> a.faces().contains(face))
              .findFirst()
              .orElseThrow();
      for (var e : action.effects()) {
        int factor = Math.multiplyExact(face, e.diceParam());
        out[0] = Math.clamp(Math.addExact(out[0], Math.multiplyExact(factor, e.a())), 0, cap);
        out[1] = Math.clamp(Math.addExact(out[1], Math.multiplyExact(factor, e.b())), 0, cap);
        out[2] = Math.clamp(Math.addExact(out[2], Math.multiplyExact(factor, e.c())), 0, cap);
      }
    }
    return out;
  }
}
