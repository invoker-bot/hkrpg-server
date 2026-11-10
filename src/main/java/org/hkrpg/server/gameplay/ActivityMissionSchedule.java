package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mission schedules tied to windowed activity modules with a known panel. */
final class ActivityMissionSchedule {
  private record MissionRow(int MainMissionID, int ActivityModuleID, boolean IsNotDelete) {}

  private record ActivityRow(int ActivityPanelID, List<Integer> ActivityModuleIDList,
      List<Integer> ResidentModuleList) {}

  private record Binding(int module, Set<Integer> panels, boolean keepAccepted) {
    boolean open(Player player, Instant now) {
      long time = now.getEpochSecond();
      for (var window : GameplayDefinitions.windows) {
        if (window.moduleId() == module && panels.contains(window.panelId())
            && window.beginTime() <= time && time < window.endTime()
            && ActivityAccess.unlocked(player, window.panelId())) return true;
      }
      return false;
    }
  }

  private static volatile Map<Integer, Binding> bindings = Map.of();

  static void load(Path root) {
    var resident = new HashSet<Integer>();
    var panels = new HashMap<Integer, Set<Integer>>();
    GameplayDefinitions.rows(root, "ActivityConfig", ActivityRow.class, row -> {
      if (row.ResidentModuleList() != null) resident.addAll(row.ResidentModuleList());
      if (row.ActivityPanelID() > 0 && row.ActivityModuleIDList() != null) {
        for (int module : row.ActivityModuleIDList())
          panels.computeIfAbsent(module, ignored -> new HashSet<>()).add(row.ActivityPanelID());
      }
    });
    var next = new HashMap<Integer, Binding>();
    GameplayDefinitions.rows(root, "MainMissionSchedule", MissionRow.class, row -> {
      int module = row.ActivityModuleID();
      var owners = panels.get(module);
      if (row.MainMissionID() > 0 && module > 0 && owners != null && !resident.contains(module)) {
        var binding = new Binding(module, Set.copyOf(owners), row.IsNotDelete());
        var previous = next.putIfAbsent(row.MainMissionID(), binding);
        if (previous != null && !previous.equals(binding))
          throw new IllegalStateException("Conflicting activity mission schedule " + row.MainMissionID());
      }
    });
    bindings = Map.copyOf(next);
  }

  static boolean mayAccept(Player player, int id, Instant now) {
    var binding = bindings.get(id);
    return binding == null || binding.open(player, now);
  }

  static boolean paused(Player player, int id) {
    var binding = bindings.get(id);
    return binding != null && !binding.keepAccepted()
        && !binding.open(player, player.getMissionManager().currentInstant());
  }

  private ActivityMissionSchedule() {}
}
