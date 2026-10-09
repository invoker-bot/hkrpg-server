package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

/** Acquisition history belongs to the player document and survives removal of the current run. */
@Entity(useDiscriminator = false)
public final class RogueHandbookState {
  private Set<Integer> miracles = new HashSet<>();

  public boolean valid() {
    return miracles != null
        && miracles.size() <= 4096
        && miracles.stream().allMatch(id -> id != null && id > 0);
  }

  public Set<Integer> getMiracles() {
    return valid() ? Collections.unmodifiableSet(miracles) : Set.of();
  }

  public boolean record(int id) {
    return canRecord(id) && miracles.add(id);
  }

  public boolean canRecord(int id) {
    return id > 0 && valid() && (miracles.contains(id) || miracles.size() < 4096);
  }

  public void merge(RogueHandbookState other) {
    if (!valid() || other == null || !other.valid()) return;
    for (int id : other.miracles) record(id);
  }
}
