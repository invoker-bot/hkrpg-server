package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.gacha.GachaService;
import emu.lunarcore.game.gacha.GachaType;
import emu.lunarcore.game.enums.ItemRarity;
import emu.lunarcore.game.player.Player;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Resource-checked trial memberships and windows associated with active character banners. */
public final class TrialActivityCatalog {
  private record Activity(int panel, Set<Integer> modules, Set<Integer> stages) {}

  private record Catalog(Map<Integer, Activity> activities, Map<Integer, Set<Integer>> stageModules,
      Set<Integer> normalStages) {}
  private record Binding(Activity activity, Set<Integer> stages, Set<Integer> modules, long begin, long end) {}

  private static int positive(JsonObject row, String field) {
    var value = row.get(field);
    if (value == null || !value.isJsonPrimitive() || !value.getAsString().matches("[1-9][0-9]*"))
      throw new IllegalArgumentException("Invalid trial activity resource integer");
    return Integer.parseInt(value.getAsString());
  }

  private static List<JsonObject> rows(Path root, String name) throws java.io.IOException {
    var data = JsonParser.parseString(Files.readString(root.resolve(name + ".json")));
    var values =
        data.isJsonArray()
            ? data.getAsJsonArray().asList()
            : data.getAsJsonObject().asMap().values();
    return values.stream().map(JsonElement::getAsJsonObject).toList();
  }

  private static Catalog read() {
    Path root = Path.of(LunarCore.getConfig().getResourceDir(), "ExcelOutput");
    var panels = new HashMap<Integer, Set<Integer>>();
    try {
      for (var row : rows(root, "ActivityAvatarDemo")) {
        int id = positive(row, "ActivityID");
        var stages = new LinkedHashSet<Integer>();
        for (var stage : row.getAsJsonArray("TypeParam")) {
          var wrapper = new JsonObject();
          wrapper.add("stage", stage);
          if (!stages.add(positive(wrapper, "stage")))
            throw new IllegalArgumentException("Duplicate trial activity stage");
        }
        if (stages.isEmpty() || panels.putIfAbsent(id, Set.copyOf(stages)) != null)
          throw new IllegalArgumentException("Invalid trial activity panel");
      }
      var activities = new HashMap<Integer, Activity>();
      var related = new HashSet<Integer>();
      var activityRows = rows(root, "ActivityConfig");
      var residentModules = new HashSet<Integer>();
      for (var row : activityRows) {
        if (row.has("ResidentModuleList") && row.get("ResidentModuleList").isJsonArray())
          for (var module : row.getAsJsonArray("ResidentModuleList")) {
            var wrapper = new JsonObject(); wrapper.add("module", module);
            try { residentModules.add(positive(wrapper, "module")); }
            catch (IllegalArgumentException invalid) { /* Ignore malformed historical module. */ }
          }
        int id = positive(row, "ActivityID");
        if (!panels.containsKey(id)) continue;
        if (!related.add(id))
          throw new IllegalArgumentException("Duplicate trial activity relation");
        // Zero panel IDs are omitted in the real resource table. Such historical rows cannot open.
        if (!row.has("ActivityPanelID") || "0".equals(row.get("ActivityPanelID").getAsString()))
          continue;
        int panel = positive(row, "ActivityPanelID");
        var modules = new HashSet<Integer>();
        for (var module : row.getAsJsonArray("ActivityModuleIDList")) {
          var wrapper = new JsonObject();
          wrapper.add("module", module);
          if (!modules.add(positive(wrapper, "module")))
            throw new IllegalArgumentException("Duplicate trial activity module");
        }
        if (modules.isEmpty()
            || activities.putIfAbsent(id, new Activity(panel, Set.copyOf(modules), panels.get(id)))
                != null) throw new IllegalArgumentException("Invalid trial activity relation");
      }
      if (!related.equals(panels.keySet()))
        throw new IllegalArgumentException("Missing trial activity relation");
      var stageModules = new HashMap<Integer, Set<Integer>>();
      var normalCounts = new HashMap<Integer, Integer>();
      if (Files.isRegularFile(root.resolve("ActivityModuleDemo.json")))
        for (var row : rows(root, "ActivityModuleDemo")) {
          try {
            int stage = positive(row, "AvatarDemoStageID"), module = positive(row, "ActivityModuleID");
            stageModules.computeIfAbsent(stage, ignored -> new HashSet<>()).add(module);
            if (row.has("AvatarDemoType") && "Normal".equals(row.get("AvatarDemoType").getAsString())
                && residentModules.contains(module)) normalCounts.merge(stage, 1, Integer::sum);
          } catch (IllegalArgumentException invalid) {
            // An invalid historical row cannot establish a safe module mapping.
          }
        }
      var normalStages = new HashSet<Integer>();
      normalCounts.forEach((stage, count) -> { if (count == 1) normalStages.add(stage); });
      return new Catalog(Map.copyOf(activities), Map.copyOf(stageModules), Set.copyOf(normalStages));
    } catch (java.io.IOException | RuntimeException invalid) {
      return null;
    }
  }

  private static boolean matches(Activity activity, GameplayDefinitions.Window window) {
    return activity.panel() == window.panelId()
        && activity.modules().contains(window.moduleId())
        && activity.stages().stream().allMatch(GameData.getAvatarDemoExcelMap()::containsKey);
  }

  public static boolean validWindow(GameplayDefinitions.Window window) {
    var panel = GameData.getActivityPanelExcelMap().get(window.panelId());
    if (panel == null) return false;
    if (!"UI/Quest/Widget/RoleTrialPanel.prefab".equals(panel.getUIPrefab())) return true;
    var catalog = read();
    return catalog != null
        && catalog.activities().values().stream().anyMatch(a -> matches(a, window));
  }

  static List<GameplayDefinitions.Window> validWindows(List<GameplayDefinitions.Window> windows) {
    Catalog catalog = null;
    boolean loaded = false;
    var valid = new ArrayList<GameplayDefinitions.Window>();
    for (var window : windows) {
      var panel = GameData.getActivityPanelExcelMap().get(window.panelId());
      if (panel == null) continue;
      if (!"UI/Quest/Widget/RoleTrialPanel.prefab".equals(panel.getUIPrefab())) { valid.add(window); continue; }
      if (!loaded) { catalog = read(); loaded = true; }
      if (catalog != null && catalog.activities().values().stream().anyMatch(a -> matches(a, window))) valid.add(window);
    }
    return List.copyOf(valid);
  }

  private static GachaService service(Player player) {
    if (player == null) return null;
    try {
      var server = player.getServer();
      return server == null ? null : server.getGachaService();
    } catch (NullPointerException noSession) { return null; }
  }

  private static List<Binding> bindings(Player player, Instant time, Catalog catalog) {
    var service = service(player);
    if (service == null) return List.of();
    var result = new ArrayList<Binding>();
      for (var banner : service.getBannerSnapshot()) {
        if (banner.getGachaType() != GachaType.AvatarUp || !banner.isActive(time.getEpochSecond())) continue;
        var pool = service.getPool(banner);
        if (!pool.canPull() || pool.up5().isEmpty()) continue;
        var matches = catalog.activities().values().stream().filter(activity -> {
          var panel = GameData.getActivityPanelExcelMap().get(activity.panel());
          if (panel == null || !"UI/Quest/Widget/RoleTrialPanel.prefab".equals(panel.getUIPrefab())) return false;
          var five = new HashSet<Integer>();
          var four = new HashSet<Integer>();
          for (int stage : activity.stages()) {
            var demo = GameData.getAvatarDemoExcelMap().get(stage);
            var item = demo == null ? null : GameData.getItemExcelMap().get(demo.getAvatarID());
            if (item == null || item.getRarity() == null) return false;
            if (item.getRarity() == ItemRarity.SuperRare) {
              if (!five.add(demo.getAvatarID())) return false;
            } else if (item.getRarity() == ItemRarity.VeryRare) {
              if (!four.add(demo.getAvatarID())) return false;
            } else return false;
          }
          // Concurrent character banners may share one activity with both five-stars.
          return five.containsAll(pool.up5()) && four.equals(new HashSet<>(pool.up4()))
              && !modulesFor(activity, catalog, activity.stages()).isEmpty();
        }).toList();
        var featured = new HashSet<Integer>(pool.up5()); featured.addAll(pool.up4());
        var candidates = new ArrayList<Binding>();
        for (var activity : matches) {
          var stages = new HashSet<Integer>();
          for (int stage : activity.stages())
            if (featured.contains(GameData.getAvatarDemoExcelMap().get(stage).getAvatarID())) stages.add(stage);
          var modules = modulesFor(activity, catalog, stages);
          if (!modules.isEmpty()) candidates.add(new Binding(activity, Set.copyOf(stages), modules,
              banner.getBeginTime(), banner.getEndTime()));
        }
        // A shared new character can occur in both original phase panels. Preserve both actual
        // locations only when they grant exactly the same stage identities for this UP roster.
        // Different trial variants remain ambiguous; do not pick one by panel/order/actor ID.
        if (!candidates.isEmpty()
            && candidates.stream().allMatch(c -> c.stages().equals(candidates.getFirst().stages())))
          result.addAll(candidates);
      }
    return List.copyOf(result);
  }

  static List<GameplayDefinitions.Window> bannerWindows(Player player, Instant time) {
    var catalog = read();
    if (catalog == null) return List.of();
    var result = new ArrayList<GameplayDefinitions.Window>();
    for (var binding : bindings(player, time, catalog)) {
      boolean grouped = binding.activity().stages().stream().anyMatch(stage ->
          Collections.disjoint(catalog.stageModules().getOrDefault(stage, Set.of()),
              binding.activity().modules()));
      // A group is one resource-defined unit. An operator override must not leave sibling
      // derived windows advertising a group whose missing members no longer have a safe window.
      if (grouped && binding.modules().stream().anyMatch(module -> GameplayDefinitions.windows.stream()
          .anyMatch(w -> w.panelId() == binding.activity().panel() && w.moduleId() == module))) continue;
      for (int module : binding.modules())
        result.add(new GameplayDefinitions.Window(binding.activity().panel(), module, binding.begin(), binding.end()));
    }
    return List.copyOf(result);
  }

  private static Set<Integer> modulesFor(Activity activity, Catalog catalog, Set<Integer> stages) {
    if (stages.isEmpty() || !activity.stages().containsAll(stages)) return Set.of();
    // The earliest client activities reused one module even where later rows now point elsewhere.
    if (activity.modules().size() == 1) return activity.modules();
    boolean grouped = false;
    for (int stage : activity.stages()) {
      long count = catalog.stageModules().getOrDefault(stage, Set.of()).stream()
          .filter(activity.modules()::contains).count();
      if (count > 1) return Set.of();
      if (count == 0) grouped = true;
    }
    // Current composite activities declare their complete stage list separately from their
    // module group. The old per-character table cannot assign every group member individually.
    // Keep that original group intact instead of guessing a module for the missing members.
    if (grouped) return activity.modules();
    var result = new HashSet<Integer>();
    for (int stage : stages) {
      var matches = catalog.stageModules().getOrDefault(stage, Set.of()).stream()
          .filter(activity.modules()::contains).toList();
      if (matches.size() != 1) return Set.of();
      result.add(matches.getFirst());
    }
    return result;
  }

  public static boolean available(Player player, int stage, Instant time) {
    if (stage <= 0) return false;
    var catalog = read();
    if (catalog == null) return false;
    // Pinned 4.6 RoleTrialUtils enumerates AvatarDemoType.Normal resident modules
    // for the permanent banner's own trial page, independently of event windows.
    var service = service(player);
    if (catalog.normalStages().contains(stage) && GameData.getAvatarDemoExcelMap().containsKey(stage)
        && service != null && service.getBannerSnapshot().stream().anyMatch(b ->
            b.getGachaType() == GachaType.Normal && b.isActive(time.getEpochSecond()) && service.getPool(b).canPull()))
      return true;
    // Explicit operator windows preserve their original resource-defined activity membership.
    for (var window : GameplayDefinitions.windows)
      if (window.beginTime() <= time.getEpochSecond() && time.getEpochSecond() < window.endTime()
          && ActivityAccess.unlocked(player, window.panelId()))
        for (var activity : catalog.activities().values())
          if (matches(activity, window) && activity.stages().contains(stage)) return true;
    for (var binding : bindings(player, time, catalog)) {
      if (!binding.stages().contains(stage) || !ActivityAccess.unlocked(player, binding.activity().panel())) continue;
      var required = modulesFor(binding.activity(), catalog, Set.of(stage));
      if (!required.isEmpty() && binding.modules().containsAll(required)
          && required.stream().allMatch(module -> GameplayDefinitions.windows.stream().noneMatch(w ->
              w.panelId() == binding.activity().panel() && w.moduleId() == module))) return true;
    }
    return false;
  }

  private TrialActivityCatalog() {}
}
