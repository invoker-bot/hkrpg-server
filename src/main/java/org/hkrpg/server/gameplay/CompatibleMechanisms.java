package org.hkrpg.server.gameplay;

import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify;
import java.util.*;
import org.hkrpg.server.protocol.Story46;

/** Missing server links reconstructed under an explicit, versioned compatibility policy. */
public final class CompatibleMechanisms {
  private record Binding(String id, CompatibleMechanismSources.Flow flow) {}

  private static List<Binding> flows(Scene scene) {
    return CompatibleMechanismSources.FLOWS.entrySet().stream()
        .filter(e -> CompatibleMechanismSources.matches(e.getValue(), scene))
        .map(e -> new Binding(e.getKey(), e.getValue()))
        .toList();
  }

  private static String address(EntityProp p) {
    return p.getGroupId() + ":" + p.getInstId();
  }

  private static Binding binding(EntityProp p) {
    return p == null
        ? null
        : flows(p.getScene()).stream()
            .filter(b -> b.flow.actors().containsKey(address(p)))
            .findFirst()
            .orElse(null);
  }

  public static boolean controller(EntityProp p) {
    return binding(p) != null;
  }

  private static Set<String> events(Player p, Binding b) {
    return p.getGameplay().getCompatibleMechanisms().events(b.id);
  }

  private static boolean ready(Player p, Binding b) {
    return b.flow.actors().entrySet().stream()
        .filter(a -> a.getValue().role().equals("piece"))
        .allMatch(a -> events(p, b).contains("done:" + a.getKey()));
  }

  private static boolean doing(Player p, CompatibleMechanismSources.Flow f, int id) {
    if (id == 0) return true;
    var d = CompatibleMechanismSources.mission(f, id);
    return d != null
        && MissionStateRecords.mainDoing(p, d.getMainMissionID())
        && !p.getGameplay().getCancelledSubs().contains(id)
        && MissionStateRecords.doing(
            p.getGameplay().getSubMissions().get(id), id, Math.max(d.getProgress(), 1));
  }

  private static boolean context(Player p, Binding b, int g) {
    var s = p.getScene();
    return p.getBattle() == null
        && p.getGameplay().getCompatibleMechanisms().valid()
        && CompatibleMechanismSources.valid(b.flow, s)
        && WorldMechanisms.context(p, s, CompatibleMechanismSources.group(b.flow, s, g));
  }

  static Retcode validateControl(Player p, int config, EntityProp prop) {
    var b = binding(prop);
    if (b == null) return Retcode.INTERACT_TYPE_INVALID;
    if (!p.getGameplay().getCompatibleMechanisms().valid()) return Retcode.PLAYER_DATA_ERROR;
    var a = b.flow.actors().get(address(prop));
    if (a.prop() != prop.getPropId() || !a.interactions().contains(config))
      return Retcode.INTERACT_TYPE_INVALID;
    if (!context(p, b, prop.getGroupId())) return Retcode.MISSION_SCENE_NOT_MATCH;
    if (!doing(p, b.flow, b.flow.mission())) return Retcode.MISSION_NOT_EXIST;
    if (events(p, b).contains("done:" + address(prop))) return Retcode.SYSTEM_BUSY;
    if (a.requires() != null && !events(p, b).containsAll(a.requires()))
      return Retcode.UNSUPPORTED_PROP_STATE;
    if (prop.getState() != actorState(p, b, address(prop))) return Retcode.PLAYER_DATA_ERROR;
    if (a.role().equals("piece") && !events(p, b).contains("open:" + b.flow.board()))
      return Retcode.UNSUPPORTED_PROP_STATE;
    if (a.role().equals("board") && (config == 11000 || config == 19100) && !ready(p, b))
      return Retcode.UNSUPPORTED_PROP_STATE;
    if (a.role().equals("board") && config == 1920 && ready(p, b))
      return Retcode.UNSUPPORTED_PROP_STATE;
    return Retcode.SUCC;
  }

  public static ScenePropTransitions.Result interact(Player p, int config, int entity) {
    synchronized (p) {
      var basic = SceneInteractions.validate(p, config, entity);
      if (basic != Retcode.SUCC) return new ScenePropTransitions.Result(basic, null);
      var prop = (EntityProp) p.getScene().getEntityById(entity);
      var code = validateControl(p, config, prop);
      if (code != Retcode.SUCC) return new ScenePropTransitions.Result(code, null);
      var b = binding(prop);
      var a = b.flow.actors().get(address(prop));
      var additions = new HashSet<String>();
      var target = GameData.getInteractExcelMap().get(config).getTargetState();
      String stateActor = null;
      Integer state = null;
      if (a.role().equals("bug")) {
        stateActor = address(prop);
        state = target.getVal();
        if (target == PropState.EventOpen) additions.add("done:" + address(prop));
      } else if (config == 1210) additions.add("open:" + address(prop));
      else if (config == 11000) additions.add("done:" + address(prop));
      var next =
          p.getGameplay().getCompatibleMechanisms().advance(b.id, additions, stateActor, state);
      if (!commit(p, b, next)) return new ScenePropTransitions.Result(Retcode.SYSTEM_BUSY, null);
      p.getMissionManager()
          .onPropInteraction(
              p.getFloorId(), prop.getGroupId(), prop.getInstId(), prop.getState().getVal());
      return new ScenePropTransitions.Result(Retcode.SUCC, prop);
    }
  }

  private static PropState actorState(Player p, Binding b, String key) {
    var a = b.flow.actors().get(key);
    var e = events(p, b);
    if (a.role().equals("bug")) {
      Integer saved = p.getGameplay().getCompatibleMechanisms().state(b.id, key);
      if (saved != null)
        for (var state : PropState.values()) if (state.getVal() == saved) return state;
      return a.initial();
    }
    if (e.contains("done:" + key)) return PropState.Closed;
    if (a.role().equals("piece"))
      return e.contains("open:" + b.flow.board()) ? PropState.TriggerEnable : a.initial();
    if (a.role().equals("board")) {
      if (b.flow.mission() > 0)
        return doing(p, b.flow, b.flow.mission()) ? PropState.TriggerEnable : a.initial();
      if (e.contains("open:" + key)) return ready(p, b) ? PropState.TriggerEnable : PropState.Open;
    }
    return a.initial();
  }

  static Retcode validateChest(Player p, int chest) {
    var b =
        flows(p.getScene()).stream()
            .filter(f -> f.flow.rewards().containsKey(chest))
            .findFirst()
            .orElse(null);
    if (b == null) return Retcode.SUCC;
    if (!p.getGameplay().getCompatibleMechanisms().valid()) return Retcode.PLAYER_DATA_ERROR;
    var loc = MapChestSources.LOCATIONS.get(chest);
    if (!context(p, b, loc.group())) return Retcode.MISSION_SCENE_NOT_MATCH;
    return events(p, b).containsAll(b.flow.rewards().get(chest))
        ? Retcode.SUCC
        : Retcode.UNSUPPORTED_PROP_STATE;
  }

  public static void restore(EntityProp prop) {
    var p = prop.getScene().getPlayer();
    var own = binding(prop);
    if (own != null) {
      var a = own.flow.actors().get(address(prop));
      prop.setState(a.initial(), false);
      if (p.getGameplay().getCompatibleMechanisms().valid()
          && CompatibleMechanismSources.restoration(own.flow, prop.getScene())
          && CompatibleMechanismSources.group(own.flow, prop.getScene(), prop.getGroupId()) != null)
        prop.setState(actorState(p, own, address(prop)), false);
    }
    var id = WorldChests.identity(prop);
    if (id == null) return;
    for (var b : flows(prop.getScene()))
      if (b.flow.rewards().containsKey(id)) {
        if (p.getGameplay().getWorldChests().getRecords() != null
            && p.getGameplay().getWorldChests().getRecords().containsKey(id)) return;
        prop.setState(PropState.Hidden, false);
        if (p.getGameplay().getCompatibleMechanisms().valid()
            && events(p, b).containsAll(b.flow.rewards().get(id))
            && CompatibleMechanismSources.restoration(b.flow, prop.getScene()))
          prop.setState(PropState.ChestClosed, false);
      }
  }

  public static boolean allowExcludedGroup(Scene scene, int group) {
    var f = CompatibleMechanismSources.FLOWS.get("bugs");
    return CompatibleMechanismSources.matches(f, scene)
        && f.groups().contains(group)
        && CompatibleMechanismSources.restoration(f, scene)
        && CompatibleMechanismSources.group(f, scene, group) != null;
  }

  public static boolean tracksGroup(GroupInfo group, Scene scene) {
    var condition = group.getSavedValueCondition();
    return condition != null
        && condition.isJsonObject()
        && condition.getAsJsonObject().has("Conditions")
        && !condition.getAsJsonObject().getAsJsonArray("Conditions").isEmpty()
        && flows(scene).stream().anyMatch(b -> b.flow.groups().contains(group.getId()));
  }

  /** Apply the original floor gates only to groups owned by the compatibility catalogue. */
  public static boolean shouldLoad(Scene scene, GroupInfo group) {
    if (!tracksGroup(group, scene)) return true;
    var b =
        flows(scene).stream()
            .filter(f -> f.flow.groups().contains(group.getId()))
            .findFirst()
            .orElseThrow();
    if (!CompatibleMechanismSources.restoration(b.flow, scene)
        || CompatibleMechanismSources.group(b.flow, scene, group.getId()) == null) return false;
    try {
      var set = group.getSavedValueCondition().getAsJsonObject();
      String operation = set.has("Operation") ? set.get("Operation").getAsString() : "And";
      if (!operation.equals("And") && !operation.equals("Or")) return false;
      boolean result = operation.equals("And");
      for (var element : set.getAsJsonArray("Conditions")) {
        var c = element.getAsJsonObject();
        if (c.has("Operation") && !c.get("Operation").getAsString().equals("Equal")) return false;
        String name = c.get("SavedValueName").getAsString();
        var declaration =
            scene.getFloorInfo().getExtraDatas(scene.getDimensionId()).stream()
                .filter(d -> d.getName().equals(name))
                .findFirst()
                .orElse(null);
        if (declaration == null) return false;
        int actual =
            scene
                .getPlayer()
                .getGameplay()
                .getStoryValues()
                .getOrDefault(
                    "floor:" + scene.getFloorId() + ":" + name, declaration.getDefaultValue());
        boolean match = actual == (c.has("Value") ? c.get("Value").getAsInt() : 0);
        result = operation.equals("And") ? result && match : result || match;
      }
      return result;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  public static void afterDefeat(Scene scene, int group) {
    if (scene.getFloorId() == 20331001 && (group == 98 || group == 147 || group == 150))
      scene.refreshMissionGroups();
  }

  public static boolean ownsProperty(Scene scene, int group) {
    return flows(scene).stream()
        .anyMatch(
            b ->
                b.flow.presentationGroup() == group
                    || b.flow.reports().stream().anyMatch(r -> r.group() == group));
  }

  public static WorldGroupProperties.Result property(
      Player p, int floor, int dimension, int group, String name, int value) {
    synchronized (p) {
      var s = p.getScene();
      if (s == null || s.getFloorId() != floor || s.getDimensionId() != dimension)
        return new WorldGroupProperties.Result(Retcode.MISSION_SCENE_NOT_MATCH, 0, 0);
      var b =
          flows(s).stream()
              .filter(
                  f ->
                      f.flow.presentationGroup() == group
                          || f.flow.reports().stream().anyMatch(r -> r.group() == group))
              .findFirst()
              .orElse(null);
      if (b == null) return new WorldGroupProperties.Result(Retcode.INTERACT_TYPE_INVALID, 0, 0);
      if (!context(p, b, group) || !s.hasGroupProperty(group, name))
        return new WorldGroupProperties.Result(Retcode.MISSION_SCENE_NOT_MATCH, 0, 0);
      int old = s.getGroups().get(group).getProperties().getInt(name);
      var r =
          b.flow.reports().stream()
              .filter(x -> x.group() == group && x.name().equals(name) && x.value() == value)
              .findFirst()
              .orElse(null);
      if (r == null) {
        if (group != b.flow.presentationGroup() || !name.equals("State") || value < 0 || value > 1)
          return new WorldGroupProperties.Result(Retcode.INTERACT_TYPE_INVALID, old, old);
        var next =
            p.getGameplay()
                .getCompatibleMechanisms()
                .advance(b.id, Set.of(), "@" + group + ":State", value);
        return new WorldGroupProperties.Result(
            commit(p, b, next) ? Retcode.SUCC : Retcode.SYSTEM_BUSY, old, value);
      }
      if (events(p, b).contains(r.key()))
        return new WorldGroupProperties.Result(Retcode.SUCC, old, old);
      if (!events(p, b).containsAll(r.requires()) || !doing(p, b.flow, r.mission()))
        return new WorldGroupProperties.Result(Retcode.UNSUPPORTED_PROP_STATE, old, old);
      var next =
          p.getGameplay().getCompatibleMechanisms().advance(b.id, Set.of(r.key()), null, null);
      if (!commit(p, b, next))
        return new WorldGroupProperties.Result(Retcode.SYSTEM_BUSY, old, old);
      p.getMissionManager().refresh();
      return new WorldGroupProperties.Result(Retcode.SUCC, old, value);
    }
  }

  private static Map<String, Integer> effects(Binding b, CompatibleMechanismState state) {
    var values = new LinkedHashMap<String, Integer>();
    var f = b.flow;
    var e = state.events(b.id);
    String prefix = "floor:" + f.floor() + ":";
    for (var r : f.reports())
      if (e.contains(r.key())) {
        values.put("group:" + f.floor() + ":" + r.group() + ":" + r.name(), r.value());
        r.effects().forEach((k, v) -> values.put(prefix + k, v));
        r.groupEffects()
            .forEach((k, v) -> values.put("group:" + f.floor() + ":" + r.group() + ":" + k, v));
      }
    if (f.board() != null) {
      int count =
          f.basePieces()
              + (int)
                  f.actors().entrySet().stream()
                      .filter(
                          a ->
                              a.getValue().role().equals("piece")
                                  && e.contains("done:" + a.getKey()))
                      .count();
      if (f.pieceValue() != null) values.put(prefix + f.pieceValue(), count);
      if (f.boardValue() != null)
        values.put(
            prefix + f.boardValue(),
            e.contains("done:" + f.board())
                ? 3
                : count == 3 ? 2 : e.contains("open:" + f.board()) ? 1 : 0);
    }
    if (b.id.equals("clocks"))
      values.put(
          prefix + "B2ClockBoyPhase",
          e.contains("done:200:300001") ? 2 : e.contains("done:199:300001") ? 1 : 0);
    if (b.id.equals("bugs") && e.containsAll(f.rewards().get(10401680)))
      values.put(prefix + "FSV_BugQuest_Answer", 1);
    if (f.waves() != null && e.contains("started")) {
      values.put(prefix + "B1InFloor", 2);
      int stage = 0;
      for (var wave : f.waves())
        if (e.containsAll(wave)) stage++;
        else break;
      values.put(prefix + "B1MonsterWave", stage == 3 ? 4 : stage);
    }
    if (f.presentationGroup() > 0) {
      for (int i = 1; i <= 3; i++)
        if (e.contains("claimed:" + i)) values.put(prefix + "FSV_SubChestCheck", i);
      var v = state.state(b.id, "@" + f.presentationGroup() + ":State");
      if (v != null) values.put("group:" + f.floor() + ":" + f.presentationGroup() + ":State", v);
    }
    return values;
  }

  /**
   * One save owns the completion and its floor/group effects. Scene mutation follows durability.
   */
  private static boolean commit(Player p, Binding b, CompatibleMechanismState next) {
    var old = p.getGameplay().getCompatibleMechanisms();
    var values = p.getGameplay().getStoryValues();
    var before = new HashMap<>(values);
    var effects = effects(b, next);
    for (var item : effects.entrySet())
      if (item.getKey().startsWith("floor:")) {
        String name = item.getKey().substring(item.getKey().lastIndexOf(':') + 1);
        if (p.getScene().getFloorInfo().getExtraDatas(p.getScene().getDimensionId()).stream()
            .noneMatch(d -> d.getName().equals(name))) return false;
      }
    p.getGameplay().compatibleMechanisms(next);
    values.putAll(effects);
    try {
      p.save();
    } catch (RuntimeException failed) {
      p.getGameplay().compatibleMechanisms(old);
      values.clear();
      values.putAll(before);
      return false;
    }
    for (var item : effects.entrySet())
      if (!Objects.equals(before.get(item.getKey()), item.getValue())) {
        String[] k = item.getKey().split(":");
        if (k[0].equals("floor"))
          Story46.syncFloor(p, b.flow.plane(), b.flow.floor(), k[2], item.getValue());
        else {
          int g = Integer.parseInt(k[2]);
          var actual = p.getScene().getGroups().get(g);
          int previous = actual == null ? 0 : actual.getProperties().getInt(k[3]);
          if (actual != null) actual.getProperties().put(k[3], item.getValue());
          Story46.syncGroup(p, b.flow.floor(), g, k[3], previous, item.getValue());
        }
      }
    publish(p.getScene());
    return true;
  }

  private static void publish(Scene scene) {
    for (var prop : List.copyOf(scene.getEntities(EntityProp.class))) {
      var before = prop.getState();
      restore(prop);
      if (before != prop.getState() && scene.isLoaded())
        scene.getPlayer().sendPacket(new PacketSceneGroupRefreshScNotify(scene, prop, null));
    }
  }

  public static void restoreGroup(Scene scene, int group) {
    var state = scene.getPlayer().getGameplay().getCompatibleMechanisms();
    if (!state.valid()) return;
    if (ownsGroupState(scene, group)) {
      var saved = state.state("waves", "@39:state");
      if (saved != null)
        scene
            .getGroups()
            .computeIfAbsent(group, emu.lunarcore.game.scene.SceneGroup::new)
            .setState(saved);
    }
    var actual = scene.getGroups().get(group);
    if (actual == null) return;
    for (var b : flows(scene))
      if (b.flow.groups().contains(group) && CompatibleMechanismSources.restoration(b.flow, scene))
        for (var e : effects(b, state).entrySet()) {
          String prefix = "group:" + scene.getFloorId() + ":" + group + ":";
          if (e.getKey().startsWith(prefix))
            actual.getProperties().put(e.getKey().substring(prefix.length()), e.getValue());
        }
  }

  /**
   * Durable mission completions and individual OPEN loot receipts are the only automatic inputs.
   */
  static void reconcile(Player p) {
    synchronized (p) {
      var s = p.getScene();
      if (s == null
          || p.getInstance() != null
          || !p.getGameplay().getCompatibleMechanisms().valid()) return;
      for (var b : flows(s)) {
        if (!CompatibleMechanismSources.valid(b.flow, s)) continue;
        var additions = new HashSet<String>();
        for (var m : b.flow.missions().entrySet())
          if (!events(p, b).contains(m.getValue().key())
              && events(p, b).containsAll(m.getValue().requires())
              && CompatibleMechanismSources.mission(b.flow, m.getKey()) != null
              && MissionStateRecords.subCompleted(p, m.getKey())) additions.add(m.getValue().key());
        if (b.flow.presentationGroup() > 0) {
          var chests = p.getGameplay().getWorldChests().getRecords();
          for (int stage = 1; stage <= 3; stage++) {
            final int n = stage;
            if (events(p, b).contains("room:" + stage)
                && b.flow.rewards().entrySet().stream()
                    .filter(e -> e.getValue().contains("room:" + n))
                    .allMatch(
                        e ->
                            chests != null
                                && chests.containsKey(e.getKey())
                                && chests.get(e.getKey()).getPhase() == WorldChestState.Phase.OPEN))
              additions.add("claimed:" + stage);
          }
        }
        additions.removeAll(events(p, b));
        if (!additions.isEmpty())
          commit(
              p, b, p.getGameplay().getCompatibleMechanisms().advance(b.id, additions, null, null));
      }
      publish(s);
    }
  }

  static void claimed(Player p, int chest) {
    if (flows(p.getScene()).stream()
        .noneMatch(b -> b.flow.presentationGroup() > 0 && b.flow.rewards().containsKey(chest)))
      return;
    reconcile(p);
    p.getMissionManager().refresh();
  }

  public static boolean ownsGroupState(Scene s, int group) {
    return s != null && s.getFloorId() == 20331001 && group == 39;
  }

  public static Retcode groupState(Player p, int group, int value, int entry, int dimension) {
    synchronized (p) {
      var s = p.getScene();
      var b = new Binding("waves", CompatibleMechanismSources.FLOWS.get("waves"));
      if (!ownsGroupState(s, group)
          || s.getEntryId() != entry
          || s.getDimensionId() != dimension
          || !context(p, b, group)) return Retcode.MISSION_SCENE_NOT_MATCH;
      if (value != 1 && value != 2) return Retcode.INTERACT_TYPE_INVALID;
      if (Objects.equals(
          p.getGameplay().getCompatibleMechanisms().state("waves", "@39:state"), value))
        return Retcode.SUCC;
      if (value == 1 && !events(p, b).contains("started")) return Retcode.UNSUPPORTED_PROP_STATE;
      if (!commit(
          p,
          b,
          p.getGameplay()
              .getCompatibleMechanisms()
              .advance(b.id, Set.of("started"), "@39:state", value))) return Retcode.SYSTEM_BUSY;
      s.getGroups()
          .computeIfAbsent(group, emu.lunarcore.game.scene.SceneGroup::new)
          .setState(value);
      s.refreshMissionGroups();
      return Retcode.SUCC;
    }
  }

  public static boolean defeat(Scene scene, EntityMonster monster) {
    var b =
        flows(scene).stream()
            .filter(
                x ->
                    x.flow.waves() != null
                        && x.flow.waves().stream()
                            .anyMatch(
                                w ->
                                    w.contains(
                                        "kill:"
                                            + monster.getGroupId()
                                            + ":"
                                            + monster.getInstId())))
            .findFirst()
            .orElse(null);
    if (b == null) return true;
    var p = scene.getPlayer();
    var key = "kill:" + monster.getGroupId() + ":" + monster.getInstId();
    // Battle is still present during victory settlement; do not use the interaction-only context.
    if (!p.getGameplay().getCompatibleMechanisms().valid()
        || !CompatibleMechanismSources.valid(b.flow, scene)
        || !WorldMechanisms.context(
            p, scene, CompatibleMechanismSources.group(b.flow, scene, monster.getGroupId()))
        || !events(p, b).contains("started")) return false;
    var group = CompatibleMechanismSources.group(b.flow, scene, monster.getGroupId());
    if (group.getMonsterList().stream()
        .filter(m -> m.getID() == monster.getInstId())
        .noneMatch(
            m ->
                ResourceLoader.getGson()
                    .toJsonTree(m)
                    .equals(ResourceLoader.getGson().toJsonTree(monster.getMonsterInfo()))))
      return false;
    for (var wave : b.flow.waves()) {
      if (wave.contains(key)) break;
      if (!events(p, b).containsAll(wave)) return false;
    }
    if (events(p, b).contains(key)) return false;
    return commit(
        p, b, p.getGameplay().getCompatibleMechanisms().advance(b.id, Set.of(key), null, null));
  }

  public static boolean spawns(Scene s, GroupInfo group, MonsterInfo monster) {
    for (var b : flows(s))
      if (b.flow.waves() != null
          && s.getPlayer().getGameplay().getCompatibleMechanisms().valid()
          && events(s.getPlayer(), b).contains("kill:" + group.getId() + ":" + monster.getID())
          && CompatibleMechanismSources.valid(b.flow, s)) return false;
    return true;
  }

  private CompatibleMechanisms() {}
}
