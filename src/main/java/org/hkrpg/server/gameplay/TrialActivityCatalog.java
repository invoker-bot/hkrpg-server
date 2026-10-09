package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Actual activity membership and declared modules; historical resources never open a window. */
public final class TrialActivityCatalog {
  private record Activity(int panel, Set<Integer> modules, Set<Integer> stages) {}

  private record Catalog(Map<Integer, Activity> activities) {}

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
      for (var row : rows(root, "ActivityConfig")) {
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
      return new Catalog(Map.copyOf(activities));
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

  public static boolean available(Player player, int stage, Instant time) {
    if (stage <= 0) return false;
    var catalog = read();
    if (catalog == null) return false;
    for (var window : GameplayDefinitions.windows)
      if (window.beginTime() <= time.getEpochSecond()
          && time.getEpochSecond() < window.endTime()
          && ActivityAccess.unlocked(player, window.panelId()))
        for (var activity : catalog.activities().values())
          if (matches(activity, window) && activity.stages().contains(stage)) return true;
    return false;
  }

  private TrialActivityCatalog() {}
}
