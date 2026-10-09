package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.GameData;
import java.util.*;

/** Accepted per-monster deaths; old aggregate totals never reconstruct this history. */
@Entity(useDiscriminator = false)
public final class RogueBattleDeathState {
  private Map<Integer, Integer> counts = new HashMap<>();
  private boolean quarantined;

  private static boolean valid(Map<Integer, Integer> rows) {
    if (rows == null || rows.size() > 4096) return false;
    long total = 0;
    for (var e : rows.entrySet()) {
      if (e.getKey() == null || e.getKey() <= 0 || e.getValue() == null || e.getValue() <= 0)
        return false;
      var source = GameData.getMonsterExcelMap().get(e.getKey());
      if (source == null || source.getId() != e.getKey()) return false;
      total += e.getValue();
      if (total > Integer.MAX_VALUE) return false;
    }
    return true;
  }

  public boolean valid() {
    return !quarantined && valid(counts);
  }

  /** Preserve damaged records for reconciliation instead of using unrelated aggregate deaths. */
  void quarantine() {
    quarantined = true;
  }

  public int total() {
    return valid() ? counts.values().stream().mapToInt(Integer::intValue).sum() : -1;
  }

  public Map<Integer, Integer> snapshot() {
    return valid() ? Map.copyOf(counts) : null;
  }

  public OptionalInt count(Set<Integer> ids) {
    if (!valid() || ids == null || ids.stream().anyMatch(id -> id == null || id <= 0))
      return OptionalInt.empty();
    return OptionalInt.of(ids.stream().mapToInt(id -> counts.getOrDefault(id, 0)).sum());
  }

  public boolean canRecord(Map<Integer, Integer> delta) {
    if (!valid() || !valid(delta) || delta.isEmpty()) return false;
    long sum = (long) total() + delta.values().stream().mapToInt(Integer::intValue).sum();
    if (sum > Integer.MAX_VALUE) return false;
    var keys = new HashSet<>(counts.keySet());
    keys.addAll(delta.keySet());
    return keys.size() <= 4096;
  }

  public boolean record(Map<Integer, Integer> delta) {
    if (!canRecord(delta)) return false;
    delta.forEach((id, n) -> counts.merge(id, n, Math::addExact));
    return true;
  }
}
