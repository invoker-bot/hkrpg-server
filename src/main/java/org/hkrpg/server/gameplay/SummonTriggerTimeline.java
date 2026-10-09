package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.TaskInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Fixed waits and explicit trigger switches from the direct source OnCreate list. */
public final class SummonTriggerTimeline {
  private SummonTriggerTimeline() {}

  private static final Set<String> NON_BLOCKING_TASKS =
      Set.of(
          "RPG.GameCore.TriggerEffect",
          "RPG.GameCore.RemoveEffect",
          "RPG.GameCore.RadialBlurEffect",
          "RPG.GameCore.DisableMazeLockTarget",
          "RPG.GameCore.DebugLog",
          "RPG.GameCore.AdvPlayerEnableIdleShow",
          "RPG.GameCore.AdvMarkAsTauntProxy");

  public record Switch(long elapsedMs, List<String> names, boolean enabled) {
    public Switch {
      names = names == null ? List.of() : List.copyOf(names);
    }
  }

  public static List<Switch> compile(List<TaskInfo> tasks) {
    var switches = new ArrayList<Switch>();
    if (tasks == null) return List.of();
    long elapsed = 0;
    for (var task : tasks) {
      if ("RPG.GameCore.WaitSecond".equals(task.getType())) {
        var wait = task.getFixedWaitSeconds();
        if (wait.isEmpty() || !Double.isFinite(wait.getAsDouble()) || wait.getAsDouble() < 0) break;
        try {
          elapsed = Math.addExact(elapsed, MazeBuffLifetime.milliseconds(wait.getAsDouble()));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
          break;
        }
      } else if ("RPG.GameCore.SetSummonUnitTriggerEnable".equals(task.getType())) {
        if (task.IsClientOnly() || !task.hasSummonSelfTarget()) continue;
        var enabled = task.getTriggerSwitchEnabled();
        var names = task.getTriggerNames();
        if (enabled.isEmpty()
            || names != null && names.stream().anyMatch(n -> n == null || n.isBlank())) continue;
        switches.add(new Switch(elapsed, names, enabled.get()));
      } else if (task.getType() == null || !NON_BLOCKING_TASKS.contains(task.getType())) {
        // Unknown tasks may wait or choose a branch; do not advance past them at a guessed time.
        break;
      }
    }
    return List.copyOf(switches);
  }
}
