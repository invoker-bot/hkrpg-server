package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

@Entity(useDiscriminator = false)
public final class WolfBroState {
  private Map<Integer, Run> runs = new HashMap<>();
  private int activeId;

  public Map<Integer, Run> getRuns() {
    return runs == null ? null : Collections.unmodifiableMap(runs);
  }

  public int getActiveId() {
    return activeId;
  }

  boolean valid() {
    if (runs == null || runs.size() > 128 || activeId < 0) return false;
    int activeCount = 0;
    for (var entry : runs.entrySet()) {
      var id = entry.getKey();
      var run = entry.getValue();
      var level = id == null ? null : WolfBroCatalog.level(id);
      if (level == null || run == null || !run.valid(level)) return false;
      if (run.active) {
        if (activeId != id) return false;
        activeCount++;
      }
    }
    return activeId == 0 ? activeCount == 0 : activeCount == 1;
  }

  Run run(int id) {
    return runs.get(id);
  }

  void replace(int id, Run run) {
    if (run == null) runs.remove(id);
    else runs.put(id, run);
  }

  void active(int id) {
    activeId = id;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Point {
    private int x, y, z;

    private Point() {}

    public Point(int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
    }

    boolean bounded(int bound) {
      return Math.abs((long) x) <= bound
          && Math.abs((long) y) <= bound
          && Math.abs((long) z) <= bound;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Motion {
    private Point pos, rot;

    private Motion() {}

    public Motion(Point pos, Point rot) {
      this.pos = pos;
      this.rot = rot;
    }

    public boolean valid() {
      return pos != null && rot != null && pos.bounded(10_000_000) && rot.bounded(360_000);
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Run {
    private int id;
    private String sourceSignature;
    private String uuid;
    private boolean active;
    private Motion archive;
    private Integer archiveControlState;
    private String controlSignature;

    private Run() {}

    Run(WolfBroCatalog.Level level, String uuid, boolean active, Motion archive) {
      this.id = level.WolfBroID();
      this.sourceSignature = level.signature();
      this.uuid = uuid;
      this.active = active;
      this.archive = archive;
    }

    Run(WolfBroCatalog.Level level, String uuid, boolean active, Motion archive, int controlState) {
      this(level, uuid, active, archive);
      this.archiveControlState = controlState;
      this.controlSignature = WolfBroControl.signature(level);
    }

    boolean valid(WolfBroCatalog.Level level) {
      if (id != level.WolfBroID()
          || !level.signature().equals(sourceSignature)
          || uuid == null
          || uuid.length() != 36
          || archive == null
          || !archive.valid()) return false;
      if ((archiveControlState == null) != (controlSignature == null)) return false;
      if (archiveControlState != null
          && (!controlSignature.equals(WolfBroControl.signature(level))
              || !WolfBroControl.stateKnown(level, archiveControlState))) return false;
      try {
        return UUID.fromString(uuid).toString().equals(uuid);
      } catch (IllegalArgumentException invalid) {
        return false;
      }
    }
  }
}
