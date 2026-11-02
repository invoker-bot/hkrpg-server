package org.hkrpg.server.gameplay;

import com.google.gson.*;
import dev.morphia.annotations.Entity;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import lombok.Getter;

/** Original classic event identities; reward thresholds are an explicit project policy. */
public final class RogueResonance {
  public static final String POLICY = "hkrpg.rogue.resonance-lunarcore-3-6-10-14.v1";
  private static final List<Integer> THRESHOLDS = List.of(3, 6, 10, 14);

  private record Event(
      int id, int buffType, String data, String config, String active, String passive) {}

  private static volatile Map<Integer, Event> events = Map.of();

  @Entity(useDiscriminator = false)
  @Getter
  public static class Plan {
    private String policy = POLICY;
    private int aeon;
    private int buffType;
    private int basic;
    private List<Integer> enhancements = new ArrayList<>();
    private Set<Integer> normals = new HashSet<>();
    private int event;
    private boolean spReserved;
    private List<String> sourceRows = new ArrayList<>();

    protected Plan() {}

    private Plan(
        RogueAeonCatalog.Row path,
        int base,
        List<Integer> enhanced,
        Set<Integer> normal,
        Event event) {
      aeon = path.id();
      buffType = path.buffType();
      basic = base;
      enhancements.addAll(enhanced);
      normals.addAll(normal);
      this.event = event.id();
      spReserved = true;
      sourceRows.addAll(
          List.of(
              path.sourceJson(), event.data(), event.config(), event.active(), event.passive()));
      GameplayDefinitions.blessings.stream()
          .filter(b -> b.MazeBuffID() == base || enhanced.contains(b.MazeBuffID()))
          .forEach(b -> sourceRows.add(new Gson().toJson(b)));
    }

    public boolean valid(int selected) {
      return POLICY.equals(policy)
          && aeon == selected
          && aeon > 0
          && aeon <= 9
          && buffType >= 120
          && buffType <= 128
          && basic > 0
          && enhancements != null
          && enhancements.size() == 3
          && new HashSet<>(enhancements).size() == 3
          && enhancements.stream().allMatch(id -> id != null && id > 0 && id != basic)
          && normals != null
          && !normals.isEmpty()
          && normals.stream()
              .allMatch(id -> id != null && id > 0 && id != basic && !enhancements.contains(id))
          && event >= 41001
          && event <= 41009
          && spReserved
          && sourceRows != null
          && sourceRows.size() == 9
          && frozenSourcesMatch(this);
    }

    public boolean earned(GameplayState.RogueRun run) {
      return valid(run.getSelectedAeon())
          && run.getBlessings().getOrDefault(basic, 0) == 1
          && enhancements.stream()
              .filter(run.getBlessings()::containsKey)
              .allMatch(id -> Objects.equals(run.getBlessings().get(id), 1));
    }
  }

  /** Validate saved identities against their own snapshot, independent of a resource reload. */
  private static boolean frozenSourcesMatch(Plan plan) {
    try {
      var rows = new ArrayList<JsonObject>();
      for (String source : plan.sourceRows) {
        if (source == null || source.length() > 16384) return false;
        rows.add(JsonParser.parseString(source).getAsJsonObject());
      }
      var path = rows.get(0);
      var data = rows.get(1);
      var config = rows.get(2);
      var active = rows.get(3);
      var passive = rows.get(4);
      if (integer(path, "AeonID") != plan.aeon
          || integer(path, "RogueVersion") != 1
          || integer(path, "RogueBuffType") != plan.buffType
          || integer(data, "BattleEventID") != plan.event
          || !stringEquals(
              data,
              "Config",
              "Config/ConfigCharacter/BattleEvent/Avatar_RogueBattleevent"
                  + plan.buffType
                  + "_Config.json")
          || !data.get("IsSPReserved").isJsonPrimitive()
          || !data.get("IsSPReserved").getAsJsonPrimitive().isBoolean()
          || !data.get("IsSPReserved").getAsBoolean()
          || integer(config, "BattleEventID") != plan.event
          || !stringEquals(config, "Team", "TeamNeutral")
          || !stringEquals(config, "EventSubType", "AssistEvent")
          || integer(active, "SkillID") != plan.event * 100 + 1
          || !stringEquals(active, "SkillTriggerKey", "Skill03")
          || integer(active.getAsJsonObject("SPNeed"), "Value") != 100
          || integer(passive, "SkillID") != plan.event * 100 + 2
          || !stringEquals(passive, "SkillTriggerKey", "SkillP01")
          || passive.has("SPNeed")) return false;
      var skills = data.getAsJsonArray("SkillIDList");
      if (skills.size() != 2
          || !skills.get(0).isJsonPrimitive()
          || !skills.get(0).getAsJsonPrimitive().isNumber()
          || !skills.get(1).isJsonPrimitive()
          || !skills.get(1).getAsJsonPrimitive().isNumber()
          || skills.get(0).getAsBigDecimal().intValueExact() != plan.event * 100 + 1
          || skills.get(1).getAsBigDecimal().intValueExact() != plan.event * 100 + 2) return false;
      var expected = new HashSet<>(plan.enhancements);
      expected.add(plan.basic);
      var seen = new HashSet<Integer>();
      for (var row : rows.subList(5, 9)) {
        int id = integer(row, "MazeBuffID");
        if (!expected.contains(id)
            || !seen.add(id)
            || integer(row, "MazeBuffLevel") != 1
            || integer(row, "AeonID") != plan.aeon
            || integer(row, "RogueBuffType") != plan.buffType
            || integer(row, "RogueVersion") != 1
            || integer(row, "RogueBuffTag") <= 0
            || !stringEquals(
                row,
                "BattleEventBuffType",
                id == plan.basic ? "BattleEventBuff" : "BattleEventBuffEnhance")) return false;
      }
      return seen.equals(expected);
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private static boolean stringEquals(JsonObject row, String key, String expected) {
    var value = row.get(key);
    return value != null
        && value.isJsonPrimitive()
        && value.getAsJsonPrimitive().isString()
        && expected.equals(value.getAsString());
  }

  /** Missing optional tables disable planning; a malformed present table is an operator error. */
  public static void load(Path root) {
    events = Map.of();
    var names = List.of("BattleEventData", "BattleEventConfig", "BattleEventSkillConfig");
    if (names.stream()
        .anyMatch(n -> !Files.isRegularFile(root.resolve("ExcelOutput/" + n + ".json")))) return;
    try {
      var data = table(root, names.get(0));
      var config = table(root, names.get(1));
      var skills = table(root, names.get(2));
      var next = new TreeMap<Integer, Event>();
      for (var raw : data) {
        var row = raw.getAsJsonObject();
        int id = integer(row, "BattleEventID");
        if (id < 41001 || id > 41009) continue;
        String path = row.get("Config").getAsString();
        var match =
            java.util.regex.Pattern.compile(
                    "Config/ConfigCharacter/BattleEvent/Avatar_RogueBattleevent(12[0-8])_Config\\.json")
                .matcher(path);
        if (!match.matches()
            || !row.has("IsSPReserved")
            || !row.get("IsSPReserved").isJsonPrimitive()
            || !row.get("IsSPReserved").getAsJsonPrimitive().isBoolean()
            || !row.get("IsSPReserved").getAsBoolean())
          throw new IllegalArgumentException("Unsupported classic event configuration");
        var list = row.getAsJsonArray("SkillIDList");
        if (list.size() != 2
            || list.get(0).getAsBigDecimal().intValueExact() != id * 100 + 1
            || list.get(1).getAsBigDecimal().intValueExact() != id * 100 + 2)
          throw new IllegalArgumentException("Mismatched classic skills");
        var event = unique(config, "BattleEventID", id);
        if (!"TeamNeutral".equals(event.get("Team").getAsString())
            || !"AssistEvent".equals(event.get("EventSubType").getAsString()))
          throw new IllegalArgumentException("Mismatched classic event type");
        var active = unique(skills, "SkillID", id * 100 + 1);
        var passive = unique(skills, "SkillID", id * 100 + 2);
        if (!"Skill03".equals(active.get("SkillTriggerKey").getAsString())
            || decimal(active, "SPNeed") != 100
            || !"SkillP01".equals(passive.get("SkillTriggerKey").getAsString())
            || passive.has("SPNeed"))
          throw new IllegalArgumentException("Mismatched classic skill costs");
        int type = Integer.parseInt(match.group(1));
        if (next.putIfAbsent(
                type,
                new Event(
                    id,
                    type,
                    row.toString(),
                    event.toString(),
                    active.toString(),
                    passive.toString()))
            != null) throw new IllegalArgumentException("Duplicate classic event configuration");
      }
      if (!next.isEmpty() && next.size() != 9)
        throw new IllegalArgumentException("Incomplete classic event catalogue");
      events = Map.copyOf(next);
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid classic rogue battle event tables", ex);
    }
  }

  private static JsonArray table(Path root, String name) throws Exception {
    var path = root.resolve("ExcelOutput/" + name + ".json");
    if (Files.size(path) > 16 * 1024 * 1024)
      throw new IllegalArgumentException("Event table too large");
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      var rows = JsonParser.parseReader(reader).getAsJsonArray();
      if (rows.size() > 10000) throw new IllegalArgumentException("Too many event rows");
      return rows;
    }
  }

  private static int integer(JsonObject row, String key) {
    var value = row.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Invalid event identity");
    int id = value.getAsBigDecimal().intValueExact();
    if (id <= 0) throw new IllegalArgumentException("Invalid event identity");
    return id;
  }

  private static double decimal(JsonObject row, String key) {
    var v = row.getAsJsonObject(key).get("Value");
    if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Invalid event cost");
    return v.getAsDouble();
  }

  private static JsonObject unique(JsonArray rows, String key, int id) {
    var found =
        rows.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> integer(r, key) == id)
            .toList();
    if (found.size() != 1) throw new IllegalArgumentException("Duplicate/missing event row");
    return found.getFirst();
  }

  public static Plan freeze(int aeon) {
    var path = RogueAeonCatalog.find(aeon).orElse(null);
    if (path == null) return null;
    var event = events.get(path.buffType());
    var basic = RogueTalentEffects.group(path.basicGroup());
    var enhancements = RogueTalentEffects.group(path.enhanceGroup());
    if (event == null
        || basic.isEmpty()
        || basic.get().size() != 1
        || enhancements.isEmpty()
        || enhancements.get().size() != 3) return null;
    int base = basic.get().iterator().next();
    var enhanced = enhancements.get().stream().sorted().toList();
    var ids = new HashSet<>(enhanced);
    ids.add(base);
    if (ids.size() != 4) return null;
    for (int id : ids) {
      var rows =
          GameplayDefinitions.blessings.stream()
              .filter(b -> b.MazeBuffID() == id && b.MazeBuffLevel() == 1)
              .toList();
      if (rows.size() != 1) return null;
      var b = rows.getFirst();
      if (b.AeonID() != aeon
          || b.RogueBuffType() != path.buffType()
          || b.RogueVersion() != 1
          || b.RogueBuffTag() <= 0
          || !Objects.equals(
              b.BattleEventBuffType(), id == base ? "BattleEventBuff" : "BattleEventBuffEnhance")
          || emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(id, 1) == null) return null;
    }
    var normal = new HashSet<Integer>();
    GameplayDefinitions.blessings.stream()
        .filter(
            b ->
                b.RogueBuffType() == path.buffType()
                    && b.RogueVersion() == 1
                    && b.MazeBuffLevel() == 1
                    && b.AeonID() == 0
                    && b.BattleEventBuffType() == null
                    && b.MazeBuffID() > 0)
        .forEach(b -> normal.add(b.MazeBuffID()));
    var plan = new Plan(path, base, enhanced, normal, event);
    return plan.valid(aeon) ? plan : null;
  }

  /**
   * Queue after the final current choice; never overwrite blessings or miracles already pending.
   */
  public static boolean queue(GameplayState.RogueRun run) {
    var plan = run.getResonance();
    if (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
        || run.pending()
        || run.getSelectionId() < 0
        || run.getSelectionId() == Integer.MAX_VALUE
        || plan == null
        || !plan.valid(run.getSelectedAeon())) return false;
    if (run.getBlessings().containsKey(plan.basic) && run.getBlessings().get(plan.basic) != 1)
      return false;
    long normal = run.getBlessings().keySet().stream().filter(plan.normals::contains).count();
    int count =
        (plan.earned(run) ? 1 : 0)
            + (int) plan.enhancements.stream().filter(run.getBlessings()::containsKey).count();
    if (count >= 4 || normal < THRESHOLDS.get(count) || count > 0 && !plan.earned(run))
      return false;
    var choices =
        count == 0
            ? List.of(plan.basic)
            : plan.enhancements.stream().filter(id -> !run.getBlessings().containsKey(id)).toList();
    if (choices.isEmpty()
        || choices.stream()
            .anyMatch(id -> emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(id, 1) == null))
      return false;
    var levels = new TreeMap<Integer, Integer>();
    choices.forEach(id -> levels.put(id, 1));
    run.offer(choices, choices, List.of(), levels, 1, levels);
    return true;
  }

  private RogueResonance() {}

  public record Energy(int eventId, int current, int maximum) {
    public boolean valid() {
      return eventId > 0
          && (current == -1 && maximum == -1 || maximum > 0 && current >= 0 && current <= maximum);
    }

    public int normalized() {
      return (int) Math.round((double) current / maximum * 10000);
    }
  }

  public record BattleBinding(
      GameplayState.RogueRun run,
      Plan plan,
      int site,
      int room,
      int entry,
      emu.lunarcore.game.scene.Scene scene,
      int eventId,
      int initialSp) {
    public boolean matches(emu.lunarcore.game.battle.Battle battle) {
      var player = battle.getPlayer();
      return player.getGameplay().getRogue() == run
          && run.getResonance() == plan
          && plan.earned(run)
          && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
          && run.getSiteId() == site
          && run.getRoomId() == room
          && player.getEntryId() == entry
          && player.getScene() == scene
          && (run.getRoster().isEmpty()
              || run.isInside() && battle.getLineup() == player.getUniverseManager().lineup())
          && battle.getBattleEvents().contains(eventId);
    }
  }

  public static void attach(emu.lunarcore.game.battle.Battle battle, GameplayState.RogueRun run) {
    var plan = run.getResonance();
    if (plan == null || !plan.earned(run) || !run.getRoster().isEmpty() && !run.isInside()) return;
    int energy = run.getResonanceSp() == -1 ? 10000 : run.getResonanceSp();
    if (energy < 0 || energy > 10000) return;
    if (!battle.getBattleEvents().contains(plan.event)) battle.getBattleEvents().add(plan.event);
    battle.setRogueResonance(
        new BattleBinding(
            run,
            plan,
            run.getSiteId(),
            run.getRoomId(),
            battle.getPlayer().getEntryId(),
            battle.getPlayer().getScene(),
            plan.event,
            energy));
  }

  public static boolean validReport(emu.lunarcore.game.battle.Battle battle, List<Energy> rows) {
    if (rows == null
        || rows.size() > 64
        || battle.getRogueResonance() != null && !battle.getRogueResonance().matches(battle))
      return false;
    var seen = new HashSet<Integer>();
    return rows.stream()
        .allMatch(
            row ->
                row != null
                    && row.valid()
                    && seen.add(row.eventId())
                    && (battle.getBattleEvents().contains(row.eventId())
                        || battle.getActorBattleEvents().contains(row.eventId())
                        || CocoonBattleEvents.acceptsDormantElation(battle, row)));
  }

  /** Called once, after all battle identity/statistics checks and the settlement guard. */
  public static void saveReport(emu.lunarcore.game.battle.Battle battle, List<Energy> rows) {
    var binding = battle.getRogueResonance();
    if (binding == null) return;
    for (var row : rows)
      if (row.eventId() == binding.eventId() && row.maximum() > 0) {
        binding.run().saveResonanceSp(row.normalized());
        battle.getPlayer().save();
      }
  }
}
