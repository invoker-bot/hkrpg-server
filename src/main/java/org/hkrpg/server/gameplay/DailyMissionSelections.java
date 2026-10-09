package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/**
 * A manual acceptance consumes an authored quota once; historical selections stay in the document.
 */
@Entity(useDiscriminator = false)
@Getter
public final class DailyMissionSelections {
  private long latestDay = Long.MIN_VALUE;
  private Map<String, List<Selection>> days = new HashMap<>();

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Selection {
    private int id, type, group;

    protected Selection() {}

    Selection(DailyMissionCatalog.Mission row) {
      id = row.ID();
      type = row.DailyMissionType();
      group = row.GroupID();
    }
  }

  boolean record(long day, DailyMissionCatalog.Mission row) {
    if (row == null || day < latestDay || days == null) return false;
    var list = days.computeIfAbsent(Long.toString(day), ignored -> new ArrayList<>());
    if (list.stream().anyMatch(value -> value.getId() == row.ID())) return false;
    list.add(new Selection(row));
    latestDay = day;
    return true;
  }
}
