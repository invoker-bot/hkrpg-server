package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;

/** Original controller finish reports and their individually bound hidden chests. */
public final class ControllerFinishRewards {
  private static final String CONTROLLER =
      "Config/Level/Maze/MazeExtra/CommandRobotPuzzle/CommandRobotPuzzle_Controller.json";
  private static final String HELPER =
      "Config/Level/Maze/MazeExtra/CommandRobotPuzzle/CommandRobotPuzzle_HideChestControl.json";
  private static final String MOVIE =
      "Config/Level/Maze/MazeExtra/MovieGame/MovieGame_InCinema_Secret.json";
  private static final JsonObject CATALOG =
      PuzzleRewardSources.catalog("controller-finish-rewards-4.6.json");

  private record Binding(
      int chest,
      int plane,
      int floor,
      int group,
      int instance,
      int prop,
      int controller,
      String graph,
      int prerequisite,
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
                      row.get("group").getAsInt(),
                      row.get("chest").getAsInt(),
                      row.get("chestProp").getAsInt(),
                      row.get("controller").getAsInt(),
                      row.get("graph").getAsString(),
                      row.get("prerequisite").getAsInt(),
                      Set.copyOf(dimensions)));
            });
    return Map.copyOf(result);
  }

  private static Binding binding(Scene scene, int group) {
    if (scene == null) return null;
    return BINDINGS.values().stream()
        .filter(
            b ->
                b.plane() == scene.getPlaneId()
                    && b.floor() == scene.getFloorId()
                    && b.group() == group
                    && b.dimensions().contains(scene.getDimensionId()))
        .findFirst()
        .orElse(null);
  }

  static boolean controller(EntityProp prop) {
    if (prop == null || prop.getPropId() != 100) return false;
    var b = binding(prop.getScene(), prop.getGroupId());
    return b != null && b.controller() == prop.getInstId();
  }

  static int chest(EntityProp prop) {
    var b = prop == null ? null : binding(prop.getScene(), prop.getGroupId());
    return b == null ? 0 : b.chest();
  }

  private static WorldMechanismSources.Rule source(Player p, Binding b) {
    if (b == null || p.getScene() == null) return null;
    var rule = WorldMechanismSources.RULES.get(b.chest());
    if (rule == null
        || rule.kind() != WorldMechanismSources.Kind.CLIENT_REPORT
        || !rule.reports().equals(Set.of(120120))
        || !PuzzleRewardSources.row(CATALOG, "InteractConfig", 1010)
        || !PuzzleRewardSources.row(CATALOG, "MazeProp", 100)
        || b.prerequisite() != 0 && !PuzzleRewardSources.row(CATALOG, "InteractConfig", 1200)
        || !rule.files().contains(b.graph().equals(CONTROLLER) ? HELPER : b.graph())
        || PuzzleRewardSources.read(CATALOG, b.graph()) == null
        || b.graph().equals(CONTROLLER) && PuzzleRewardSources.read(CATALOG, HELPER) == null
        || b.prerequisite() != 0 && PuzzleRewardSources.read(CATALOG, MOVIE) == null) return null;
    var loc = MapChestSources.LOCATIONS.get(b.chest());
    if (loc == null
        || loc.group() != b.group()
        || loc.instance() != b.instance()
        || loc.prop() != b.prop()) return null;
    var group = WorldMechanismSources.group(rule, p.getScene());
    return WorldMechanisms.context(p, p.getScene(), group) ? rule : null;
  }

  private static boolean finishedPrerequisite(
      Scene scene, WorldMechanismSources.Rule rule, Binding b) {
    if (b.prerequisite() == 0) return true;
    var actor = WorldMechanisms.boundActor(scene, rule, b.prerequisite(), 100);
    return actor != null
        && actor.getState() == PropState.Closed
        && WorldMechanisms.durableState(actor);
  }

  static Retcode validate(Player player, int config, EntityProp controller) {
    if (config != 1010) return Retcode.INTERACT_TYPE_INVALID;
    var b = binding(controller.getScene(), controller.getGroupId());
    var rule = source(player, b);
    if (rule == null) return Retcode.MISSION_SCENE_NOT_MATCH;
    if (!player.getGameplay().getWorldMechanisms().valid()
        || !player.getGameplay().getScenePropStates().valid()
        || player.getGameplay().getWorldChests().getRecords() == null)
      return Retcode.PLAYER_DATA_ERROR;
    if (WorldMechanisms.boundActor(player.getScene(), rule, b.controller(), 100) != controller
        || WorldMechanisms.boundActor(player.getScene(), rule, b.instance(), b.prop()) == null
        || !finishedPrerequisite(player.getScene(), rule, b))
      return Retcode.MISSION_SCENE_NOT_MATCH;
    return Retcode.SUCC;
  }

  static WorldMechanismState progress(
      Player player, int config, EntityProp prop, WorldMechanismState previous) {
    if (config != 1010 || !controller(prop)) return previous;
    var b = binding(prop.getScene(), prop.getGroupId());
    if (previous.completed(b.chest())
        || player.getGameplay().getWorldChests().getRecords().containsKey(b.chest()))
      return previous;
    return previous.advance(
        WorldMechanismSources.RULES.get(b.chest()),
        prop.getScene(),
        0,
        Instant.now().getEpochSecond());
  }

  static boolean acknowledgesChestReport(Player p, int config, EntityProp prop) {
    if (config != 120120 || prop == null || prop.getState() != PropState.ChestClosed) return false;
    var b = binding(prop.getScene(), prop.getGroupId());
    if (b == null || prop.getInstId() != b.instance() || prop.getPropId() != b.prop()) return false;
    var rule = source(p, b);
    var chests = p.getGameplay().getWorldChests().getRecords();
    return rule != null
        && chests != null
        && !chests.containsKey(b.chest())
        && p.getGameplay().getWorldMechanisms().completed(b.chest())
        && WorldMechanisms.boundActor(p.getScene(), rule, b.instance(), b.prop()) == prop;
  }

  /** Old server versions persisted the controller even when the chest callback was delayed. */
  public static void recover(Player p) {
    synchronized (p) {
      var scene = p.getScene();
      if (scene == null || p.getInstance() != null) return;
      var previous = p.getGameplay().getWorldMechanisms();
      var states = p.getGameplay().getScenePropStates();
      var chests = p.getGameplay().getWorldChests().getRecords();
      if (!previous.valid() || !states.valid() || chests == null) return;
      var next = previous;
      int subject = 0;
      for (var b : BINDINGS.values()) {
        if (b.plane() != scene.getPlaneId()
            || b.floor() != scene.getFloorId()
            || !b.dimensions().contains(scene.getDimensionId())
            || previous.completed(b.chest())
            || chests.containsKey(b.chest())) continue;
        var saved = states.get(PropTimelines.key(scene, b.group(), b.controller()));
        if (saved == null || saved.getState() != PropState.Open.getVal()) continue;
        var rule = source(p, b);
        if (rule == null) {
          GameDiagnostics.outcome(
              p.getSession(),
              "ControllerFinishRecovery",
              b.chest(),
              Retcode.MISSION_SCENE_NOT_MATCH,
              "invalid-source-or-scene",
              "group=" + b.group());
          continue;
        }
        var actor = WorldMechanisms.boundActor(scene, rule, b.controller(), 100);
        if (actor == null
            || actor.getState() != PropState.Open
            || !WorldMechanisms.durableState(actor)
            || WorldMechanisms.boundActor(scene, rule, b.instance(), b.prop()) == null
            || !finishedPrerequisite(scene, rule, b)) {
          GameDiagnostics.outcome(
              p.getSession(),
              "ControllerFinishRecovery",
              b.chest(),
              Retcode.UNSUPPORTED_PROP_STATE,
              "missing-finish-proof",
              "group=" + b.group());
          continue;
        }
        next = next.advance(rule, scene, 0, Instant.now().getEpochSecond());
        subject = b.chest();
      }
      if (next == previous) return;
      p.getGameplay().worldMechanisms(next);
      try {
        p.save();
      } catch (RuntimeException failed) {
        p.getGameplay().worldMechanisms(previous);
        GameDiagnostics.saveFailed(p, "controller-finish-recovery", subject, 0, failed);
        return;
      }
      WorldMechanisms.publish(scene);
    }
  }

  private ControllerFinishRewards() {}
}
