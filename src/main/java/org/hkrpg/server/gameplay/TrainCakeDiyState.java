package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Owner's visual cat arrangement. It cannot grant cats or mission/reward progress. */
@Entity(useDiscriminator = false)
public final class TrainCakeDiyState {
  @Entity(useDiscriminator = false)
  public static final class Ground {
    private int slot, pose;
    private List<Integer> cats = new ArrayList<>();
    public Ground() {}
    public Ground(int slot, int pose, List<Integer> cats) {
      this.slot = slot; this.pose = pose; this.cats = new ArrayList<>(cats);
    }
    public int slot() { return slot; }
    public int pose() { return pose; }
    public List<Integer> cats() { return List.copyOf(cats); }
    @Override public boolean equals(Object other) {
      return other instanceof Ground row && slot == row.slot && pose == row.pose && Objects.equals(cats, row.cats);
    }
    @Override public int hashCode() { return Objects.hash(slot, pose, cats); }
  }
  @Entity(useDiscriminator = false)
  public static final class Frame {
    private int slot, cat;
    public Frame() {}
    public Frame(int slot, int cat) { this.slot = slot; this.cat = cat; }
    public int slot() { return slot; }
    public int cat() { return cat; }
    @Override public boolean equals(Object other) {
      return other instanceof Frame row && slot == row.slot && cat == row.cat;
    }
    @Override public int hashCode() { return Objects.hash(slot, cat); }
  }

  private int version = 1, theme;
  private List<Ground> grounds = new ArrayList<>();
  private List<Frame> frames = new ArrayList<>();
  public TrainCakeDiyState() {}
  public TrainCakeDiyState(int theme, List<Ground> grounds, List<Frame> frames) {
    this.theme = theme; this.grounds = new ArrayList<>(grounds); this.frames = new ArrayList<>(frames);
  }
  public int theme() { return theme; }
  public List<Ground> grounds() { return List.copyOf(grounds); }
  public List<Frame> frames() { return List.copyOf(frames); }

  private boolean valid(Player player) {
    if (version != 1 || theme < 0 || theme > 1 || grounds == null || grounds.size() > 3
        || frames == null || frames.size() > 4) return false;
    var owned = TrainCakeVisits.totalCounts(player);
    var used = new HashMap<Integer, Integer>(); var groundSlots = new HashSet<Integer>();
    var frameSlots = new HashSet<Integer>();
    for (var ground : grounds) {
      if (ground == null || ground.slot < 0 || ground.slot > 1024 || !groundSlots.add(ground.slot)
          || ground.pose < 0 || ground.pose > 2 || ground.cats == null || ground.cats.size() > 13) return false;
      for (var cat : ground.cats) {
        if (cat == null || cat < 0 || cat > 27) return false;
        if (cat != 0) {
          int threshold = ground.pose == 2 ? 6 : ground.pose == 1 ? 3 : 1;
          if (owned.getOrDefault(cat, 0) < threshold) return false;
          used.merge(cat, 1, Integer::sum);
        }
      }
    }
    for (var frame : frames) {
      if (frame == null || frame.slot < 0 || frame.slot > 1024 || !frameSlots.add(frame.slot)
          || frame.cat < 0 || frame.cat > 27) return false;
      if (frame.cat != 0) used.merge(frame.cat, 1, Integer::sum);
    }
    return used.values().stream().mapToInt(Integer::intValue).sum() <= 13
        && used.entrySet().stream().allMatch(entry -> entry.getValue() <= owned.getOrDefault(entry.getKey(), 0));
  }

  public static TrainCakeDiyState current(Player player) {
    var saved = player.getGameplay().getTrainCakeDiy();
    if (saved == null) return new TrainCakeDiyState();
    if (!saved.valid(player)) throw new IllegalStateException("Invalid saved cat arrangement");
    return saved;
  }

  public static Retcode update(Player player, TrainCakeDiyState next) {
    synchronized (player) {
      try {
        if (next == null || !next.valid(player)) return Retcode.REQ_PARA_INVALID;
        var previous = player.getGameplay().getTrainCakeDiy();
        if (previous != null && !previous.valid(player)) return Retcode.PLAYER_DATA_ERROR;
        if (previous != null && previous.theme == next.theme && previous.grounds.equals(next.grounds)
            && previous.frames.equals(next.frames)) return Retcode.SUCC;
        player.getGameplay().trainCakeDiy(next);
        try { player.save(); return Retcode.SUCC; }
        catch (RuntimeException failed) {
          player.getGameplay().trainCakeDiy(previous);
          org.hkrpg.server.GameDiagnostics.saveFailed(player, "train-cake-diy", 0, 0, failed);
          return Retcode.SERVER_INTERNAL_ERROR;
        }
      } catch (RuntimeException invalid) { return Retcode.PLAYER_DATA_ERROR; }
    }
  }
}
