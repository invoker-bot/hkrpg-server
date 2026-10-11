package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.*;
import lombok.Getter;

/** Ordered catch reports and a completed authored mission; neither delivers chest loot. */
@Entity(useDiscriminator = false)
public final class MascotRecord {
  @Getter private int chest;
  private List<Step> steps = new ArrayList<>();

  private MascotRecord() {}

  MascotRecord(int chest) {
    this.chest = chest;
  }

  @Getter
  @Entity(useDiscriminator = false)
  public static final class Step {
    private int state, plane, floor, entry, dimension, story, entity;
    private long at, runtime, missionFinish;
    private String event, propSource, groupSource;

    private Step() {}

    Step(
        Player p,
        EntityProp actor,
        WorldChestRules rules,
        MascotSources.Control c,
        int state,
        long now) {
      var scene = p.getScene();
      this.state = state;
      plane = scene.getPlaneId();
      floor = scene.getFloorId();
      entry = scene.getEntryId();
      dimension = scene.getDimensionId();
      story = scene.getStoryLineId();
      runtime = scene.getRuntimeId();
      entity = actor.getEntityId();
      at = now;
      event = UUID.randomUUID().toString();
      propSource = PropTimelines.source(actor.getPropInfo());
      groupSource = rules.filePins.get(c.path());
      if (state == 3)
        missionFinish = p.getGameplay().getSubMissions().get(c.talk()).getFinishTime();
    }
  }

  public List<Step> getSteps() {
    return steps == null ? null : Collections.unmodifiableList(steps);
  }

  public int state() {
    return steps == null ? -1 : steps.size();
  }

  long completedAt() {
    return steps.getLast().at;
  }

  static boolean missionComplete(Player p, MascotSources.Control c) {
    return MissionStateRecords.subCompleted(p, c.capture())
        && MissionStateRecords.subCompleted(p, c.talk())
        && (c.battle() == 0 || MissionStateRecords.subCompleted(p, c.battle()));
  }

  boolean valid(WorldChestRules rules, Player p) {
    try {
      var c = MascotSources.CONTROLS.get(chest);
      if (c == null
          || rules == null
          || !rules.mascot(chest)
          || !rules.mascotMission(c)
          || steps == null
          || steps.isEmpty()
          || steps.size() > 4) return false;
      long last = 0;
      var keys = new HashSet<String>();
      for (int i = 0; i < steps.size(); i++) {
        var s = steps.get(i);
        var group = rules.group(c.plane(), c.floor(), s.dimension, c.path());
        if (group == null) return false;
        var actors =
            group.getPropList().stream()
                .filter(a -> a.getID() == 300003 && a.getPropID() == MascotSources.MONSTER)
                .toList();
        if (actors.size() != 1
            || s.state != i + 1
            || s.plane != c.plane()
            || s.floor != c.floor()
            || !Set.of(0, 1).contains(s.dimension)
            || !rules.entry(s.plane, s.floor, s.entry)
            || s.runtime <= 0
            || s.entity <= 0
            || s.story < 0
            || s.at <= 0
            || s.at < last
            || !UUID.fromString(s.event).toString().equals(s.event)
            || !keys.add(s.event)
            || !Objects.equals(s.propSource, PropTimelines.source(actors.getFirst()))
            || !Objects.equals(s.groupSource, rules.filePins.get(c.path()))) return false;
        if (s.state == 3) {
          if (!missionComplete(p, c)
              || s.missionFinish <= 0
              || s.missionFinish > s.at
              || s.missionFinish != p.getGameplay().getSubMissions().get(c.talk()).getFinishTime()
              || p.getGameplay().getSubMissions().get(c.capture()).getFinishTime() > s.missionFinish
              || (c.battle() != 0
                  && p.getGameplay().getSubMissions().get(c.battle()).getFinishTime()
                      > s.missionFinish)) return false;
        } else if (s.missionFinish != 0) return false;
        last = s.at;
      }
      return true;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  void append(Player p, EntityProp actor, WorldChestRules rules, int state, long now) {
    if (steps == null || state != steps.size() + 1 || state > 4)
      throw new IllegalStateException("Mascot stage changed");
    steps.add(new Step(p, actor, rules, MascotSources.CONTROLS.get(chest), state, now));
  }
}
