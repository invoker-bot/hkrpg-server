package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;
import org.hkrpg.server.protocol.Story46;

/** The source puzzle's group-property report is an explicit completion callback. */
public final class OraclePuzzleRewards {
  private static final String PROPERTY = "SGP_PuzzleState";
  private static final String TREASURE_GRAPH =
      "Config/Level/Maze/MazeExtra/OraclePuzzle/OraclePuzzle_Treasure_GroupGraph.json";
  private static final String CONTROL_GRAPH =
      "Config/Level/Maze/MazeExtra/OraclePuzzle/OraclePuzzle_GroupGraph.json";
  private static final String PUZZLE_GRAPH =
      "Config/Level/Maze/MazeExtra/OraclePuzzle/OraclePuzzle.json";
  private static final JsonObject CATALOG = PuzzleRewardSources.catalog("oracle-puzzles-4.6.json");

  private record Binding(
      int chest,
      int plane,
      int floor,
      int chestGroup,
      int chestInstance,
      int controlGroup,
      String floorValue,
      String controlPath,
      String floorPath,
      Set<Integer> dimensions) {}

  private static final Map<Integer, Binding> BINDINGS = bindings();

  private static Map<Integer, Binding> bindings() {
    var result = new TreeMap<Integer, Binding>();
    CATALOG
        .getAsJsonObject("bindings")
        .entrySet()
        .forEach(
            e -> {
              int chest = Integer.parseInt(e.getKey());
              var row = e.getValue().getAsJsonObject();
              var dimensions = new HashSet<Integer>();
              row.getAsJsonArray("dimensions").forEach(d -> dimensions.add(d.getAsInt()));
              result.put(
                  chest,
                  new Binding(
                      chest,
                      row.get("plane").getAsInt(),
                      row.get("floor").getAsInt(),
                      row.get("chestGroup").getAsInt(),
                      row.get("chestInstance").getAsInt(),
                      row.get("controlGroup").getAsInt(),
                      row.get("floorValue").getAsString(),
                      row.get("controlPath").getAsString(),
                      row.get("floorPath").getAsString(),
                      Set.copyOf(dimensions)));
            });
    return Map.copyOf(result);
  }

  private static Binding control(Scene scene, int group) {
    if (scene == null) return null;
    return BINDINGS.values().stream()
        .filter(
            b ->
                b.plane() == scene.getPlaneId()
                    && b.floor() == scene.getFloorId()
                    && b.controlGroup() == group
                    && b.dimensions().contains(scene.getDimensionId()))
        .findFirst()
        .orElse(null);
  }

  static boolean owns(Scene scene, int group) {
    return control(scene, group) != null;
  }

  private static WorldMechanismSources.Rule source(Player p, Binding b) {
    var scene = p.getScene();
    if (scene == null
        || b == null
        || scene.getPlaneId() != b.plane()
        || scene.getFloorId() != b.floor()
        || !b.dimensions().contains(scene.getDimensionId())) return null;
    var rule = WorldMechanismSources.RULES.get(b.chest());
    var loc = MapChestSources.LOCATIONS.get(b.chest());
    if (rule == null
        || loc == null
        || rule.kind() != WorldMechanismSources.Kind.CLIENT_REPORT
        || !rule.reports().equals(Set.of(120120))
        || !rule.files().contains(TREASURE_GRAPH)
        || loc.group() != b.chestGroup()
        || loc.instance() != b.chestInstance()
        || loc.prop() != 60402) return null;
    if (PuzzleRewardSources.read(CATALOG, CONTROL_GRAPH) == null
        || PuzzleRewardSources.read(CATALOG, PUZZLE_GRAPH) == null
        || PuzzleRewardSources.read(CATALOG, TREASURE_GRAPH) == null
        || !PuzzleRewardSources.floor(CATALOG, b.floorPath(), scene)) return null;
    var chestGroup = WorldMechanismSources.group(rule, scene);
    var controlGroup = PuzzleRewardSources.group(CATALOG, b.controlPath(), b.controlGroup(), scene);
    if (!WorldMechanisms.context(p, scene, chestGroup)
        || !WorldMechanisms.context(p, scene, controlGroup)
        || !scene.hasGroupProperty(b.controlGroup(), PROPERTY)
        || WorldMechanisms.boundActor(scene, rule, b.chestInstance(), 60402) == null) return null;
    var declarations =
        scene.getFloorInfo().getExtraDatas(scene.getDimensionId()).stream()
            .filter(row -> b.floorValue().equals(row.getName()))
            .toList();
    return declarations.size() == 1
            && declarations.getFirst().getDefaultValue() == 0
            && declarations.getFirst().getMaxValue() >= 1
        ? rule
        : null;
  }

  private static WorldGroupProperties.Result reject(
      Player p, Binding b, Retcode code, String reason) {
    GameDiagnostics.outcome(
        p.getSession(),
        "OraclePuzzleCompletion",
        b == null ? 0 : b.chest(),
        code,
        reason,
        b == null ? "" : "group=" + b.controlGroup());
    return new WorldGroupProperties.Result(code, 0, 0);
  }

  static WorldGroupProperties.Result change(
      Player p, int floor, int dimension, int group, String name, int value) {
    synchronized (p) {
      var b = control(p.getScene(), group);
      if (b == null || floor != b.floor() || p.getScene().getDimensionId() != dimension)
        return reject(p, b, Retcode.MISSION_SCENE_NOT_MATCH, "wrong-scene");
      if (!PROPERTY.equals(name) || value != 1)
        return reject(p, b, Retcode.INTERACT_TYPE_INVALID, "invalid-property");
      return finish(p, b);
    }
  }

  private static WorldGroupProperties.Result finish(Player p, Binding b) {
    var scene = p.getScene();
    if (p.getBattle() != null) return reject(p, b, Retcode.SYSTEM_BUSY, "battle-active");
    var rule = source(p, b);
    if (rule == null)
      return reject(p, b, Retcode.MISSION_SCENE_NOT_MATCH, "invalid-source-or-scene");
    var previous = p.getGameplay().getWorldMechanisms();
    var records = p.getGameplay().getWorldChests().getRecords();
    if (!previous.valid() || records == null)
      return reject(p, b, Retcode.PLAYER_DATA_ERROR, "invalid-save");
    var sceneGroup = scene.getGroups().get(b.controlGroup());
    if (sceneGroup == null) return reject(p, b, Retcode.MISSION_SCENE_NOT_MATCH, "missing-control");
    var values = p.getGameplay().getStoryValues();
    String groupKey = "group:" + b.floor() + ":" + b.controlGroup() + ":" + PROPERTY;
    String floorKey = "floor:" + b.floor() + ":" + b.floorValue();
    Integer oldGroup = values.get(groupKey), oldFloor = values.get(floorKey);
    int before = sceneGroup.getProperties().getInt(PROPERTY);
    int savedGroup = oldGroup == null ? 0 : oldGroup;
    int savedFloor = oldFloor == null ? 0 : oldFloor;
    if (before != savedGroup
        || before < 0
        || before > 1
        || savedFloor < 0
        || savedFloor > 1
        || !previous.completed(b.chest()) && (before != 0 || savedFloor != 0))
      return reject(p, b, Retcode.UNSUPPORTED_PROP_STATE, "inconsistent-state");
    if (previous.completed(b.chest()) && before == 1 && savedFloor == 1)
      return new WorldGroupProperties.Result(Retcode.SUCC, 1, 1);
    var next =
        previous.completed(b.chest())
            ? previous
            : previous.advance(rule, scene, 0, Instant.now().getEpochSecond());
    values.put(groupKey, 1);
    values.put(floorKey, 1);
    sceneGroup.getProperties().put(PROPERTY, 1);
    p.getGameplay().worldMechanisms(next);
    try {
      p.save();
    } catch (RuntimeException failed) {
      restore(values, groupKey, oldGroup);
      restore(values, floorKey, oldFloor);
      sceneGroup.getProperties().put(PROPERTY, before);
      p.getGameplay().worldMechanisms(previous);
      GameDiagnostics.saveFailed(p, "oracle-puzzle-finish", b.chest(), 0, failed);
      return new WorldGroupProperties.Result(Retcode.SYSTEM_BUSY, before, before);
    }
    if (scene.isLoaded()) {
      Story46.syncGroup(p, b.floor(), b.controlGroup(), PROPERTY, before, 1);
      Story46.syncFloor(p, b.plane(), b.floor(), b.floorValue(), 1);
    }
    WorldMechanisms.publish(scene);
    if (scene.isLoaded()) p.getMissionManager().refresh();
    return new WorldGroupProperties.Result(Retcode.SUCC, before, 1);
  }

  /** Preserve the original direct chest callback while saving its matching control state. */
  static WorldChests.Result report(Player p, int config, EntityProp prop) {
    if (config != 120120 || prop == null) return null;
    var id = WorldChests.identity(prop);
    var b = id == null ? null : BINDINGS.get(id);
    if (b == null) return null;
    // A previously supported chest-only scene must remain usable if its control group was not
    // loaded.
    if (p.getScene().getResourceGroups().get(b.controlGroup()) == null) {
      GameDiagnostics.outcome(
          p.getSession(),
          "OraclePuzzleCompletion",
          b.chest(),
          Retcode.MISSION_SCENE_NOT_MATCH,
          "control-definition-unavailable",
          "group=" + b.controlGroup());
      return null;
    }
    var result = finish(p, b);
    return new WorldChests.Result(result.retcode(), result.retcode() == Retcode.SUCC ? prop : null);
  }

  static boolean acknowledgesChestReport(Player p, int config, EntityProp prop) {
    if (config != 120120
        || prop == null
        || prop.getState() != emu.lunarcore.game.enums.PropState.ChestClosed) return false;
    var id = WorldChests.identity(prop);
    var b = id == null ? null : BINDINGS.get(id);
    if (b == null || source(p, b) == null) return false;
    var records = p.getGameplay().getWorldChests().getRecords();
    return records != null
        && !records.containsKey(id)
        && p.getGameplay().getWorldMechanisms().completed(id);
  }

  /** Repair old legitimate chest completions that predate the control-state binding. */
  public static void recover(Player p) {
    synchronized (p) {
      var scene = p.getScene();
      if (scene == null || p.getInstance() != null) return;
      var state = p.getGameplay().getWorldMechanisms();
      if (!state.valid()) return;
      for (var b : BINDINGS.values()) {
        if (b.plane() != scene.getPlaneId()
            || b.floor() != scene.getFloorId()
            || !b.dimensions().contains(scene.getDimensionId())
            || !state.completed(b.chest())) continue;
        finish(p, b);
      }
    }
  }

  private static void restore(Map<String, Integer> values, String key, Integer value) {
    if (value == null) values.remove(key);
    else values.put(key, value);
  }

  private OraclePuzzleRewards() {}
}
