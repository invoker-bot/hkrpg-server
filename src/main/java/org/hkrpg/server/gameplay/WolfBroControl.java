package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.SceneGroup;
import java.util.*;
import org.hkrpg.server.protocol.Story46;

/** Resource-authored control transitions. This never resets props, monsters, chests or rewards. */
public final class WolfBroControl {
  public static GroupInfo definition(WolfBroCatalog.Level level) {
    if (level == null) return null;
    var floor = GameData.getFloorInfo(level.PlaneID(), level.FloorID());
    var group = floor == null ? null : floor.getGroups().get(level.ControlGroupID());
    if (group == null || group.getId() != level.ControlGroupID()) return null;
    var rows = group.getGroupStateList();
    if (rows == null || rows.isEmpty() || rows.size() > 128) return null;
    var edges = new HashSet<String>();
    boolean start = false;
    for (var row : rows) {
      if (row == null || row.SrcState() < 0 || row.DstState() < 0) return null;
      if (row.SrcState() == 0
          && row.DstState() == 0
          && (row.StateName() == null
              || row.StateName().isJsonNull()
              || row.StateName().isJsonPrimitive()
                  && row.StateName().getAsJsonPrimitive().isString()
                  && row.StateName().getAsString().isEmpty())) continue;
      if (row.SrcState() == row.DstState() || !edges.add(row.SrcState() + ":" + row.DstState()))
        return null;
      start |= row.SrcState() == level.StartState() || row.DstState() == level.StartState();
    }
    return start ? group : null;
  }

  static String signature(WolfBroCatalog.Level level) {
    var group = definition(level);
    if (group == null) return null;
    // Comment is descriptive source data. Keep existing signed edge records byte-compatible.
    var rules =
        group.getGroupStateList().stream()
            .map(
                row ->
                    "StateTransition[SrcState="
                        + row.SrcState()
                        + ", DstState="
                        + row.DstState()
                        + ", StateName="
                        + row.StateName()
                        + "]")
            .toList();
    return group.getId() + ":" + group.getGroupName() + ":" + rules;
  }

  static boolean stateKnown(WolfBroCatalog.Level level, int state) {
    var group = definition(level);
    return group != null
        && (state == level.CloseState()
            || state == level.StartState()
            || group.getGroupStateList().stream()
                .anyMatch(r -> r.SrcState() == state || r.DstState() == state));
  }

  static boolean canTransition(WolfBroCatalog.Level level, int from, int to) {
    var group = definition(level);
    if (group == null || to == level.CloseState()) return false;
    return group.getGroupStateList().stream()
        .anyMatch(
            r ->
                r.SrcState() != r.DstState()
                    && (from == to && (r.SrcState() == to || r.DstState() == to)
                        || r.SrcState() == from && r.DstState() == to));
  }

  static Integer current(Player player, WolfBroCatalog.Level level) {
    if (definition(level) == null || player.getGameplay().getStoryValues() == null) return null;
    var group = player.getScene().getGroups().get(level.ControlGroupID());
    if (group != null && group.getId() != level.ControlGroupID()) return null;
    var key = key(level);
    var values = player.getGameplay().getStoryValues();
    int state = group == null ? 0 : group.getState();
    if (!stateKnown(level, state)
        || values.containsKey(key) && (values.get(key) == null || values.get(key) != state)
        || !values.containsKey(key) && state != 0) return null;
    return state;
  }

  static Update prepare(Player player, WolfBroCatalog.Level level, int target) {
    var state = current(player, level);
    return state == null || !stateKnown(level, target)
        ? null
        : new Update(player, level, target, state);
  }

  private static String key(WolfBroCatalog.Level level) {
    return "group-state:" + level.FloorID() + ":" + level.ControlGroupID();
  }

  static final class Update {
    private final Player player;
    private final WolfBroCatalog.Level level;
    private final int target, oldState;
    private final SceneGroup previous;
    private final boolean valuePresent;
    private final Integer oldValue;
    private SceneGroup applied;

    Update(Player player, WolfBroCatalog.Level level, int target, int oldState) {
      this.player = player;
      this.level = level;
      this.target = target;
      this.oldState = oldState;
      previous = player.getScene().getGroups().get(level.ControlGroupID());
      var values = player.getGameplay().getStoryValues();
      valuePresent = values.containsKey(key(level));
      oldValue = values.get(key(level));
    }

    boolean changed() {
      return target != oldState;
    }

    void apply() {
      if (!changed()) return;
      applied = previous == null ? new SceneGroup(level.ControlGroupID()) : previous;
      player.getScene().getGroups().put(level.ControlGroupID(), applied);
      applied.setState(target);
      player.getGameplay().getStoryValues().put(key(level), target);
    }

    void rollback() {
      if (!changed()) return;
      if (previous == null) player.getScene().getGroups().remove(level.ControlGroupID());
      else previous.setState(oldState);
      var values = player.getGameplay().getStoryValues();
      if (valuePresent) values.put(key(level), oldValue);
      else values.remove(key(level));
    }

    void publish() {
      if (changed()) Story46.syncState(player, level.FloorID(), level.ControlGroupID(), target);
    }
  }

  private WolfBroControl() {}
}
