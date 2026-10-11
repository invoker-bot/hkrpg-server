package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.*;
import lombok.Getter;

/**
 * Four ordered source override interactions; old generic prop values are not completion evidence.
 */
@Entity(useDiscriminator = false)
public final class GoldenSpearRecord {
  @Getter private int chest;
  private List<Step> steps = new ArrayList<>();

  private GoldenSpearRecord() {}

  GoldenSpearRecord(int chest) {
    this.chest = chest;
  }

  @Getter
  @Entity(useDiscriminator = false)
  public static final class Step {
    private int config, from, to, plane, floor, entry, dimension, story, entity;
    private long at, runtime;
    private String event, propSource, groupSource, configSource;

    private Step() {}

    Step(Player p, EntityProp actor, WorldChestRules rules, int chest, int index, long now) {
      var scene = p.getScene();
      config = GoldenSpearSources.CONFIGS[index];
      from = GoldenSpearSources.STATES.get(index).getVal();
      to = GoldenSpearSources.STATES.get(index + 1).getVal();
      plane = scene.getPlaneId();
      floor = scene.getFloorId();
      entry = scene.getEntryId();
      dimension = scene.getDimensionId();
      story = scene.getStoryLineId();
      entity = actor.getEntityId();
      runtime = scene.getRuntimeId();
      at = now;
      event = UUID.randomUUID().toString();
      propSource = PropTimelines.source(actor.getPropInfo());
      groupSource = rules.filePins.get(rules.locations.get(chest).path());
      configSource = rules.rowPins.get("InteractConfig:" + config);
    }

    boolean valid(WorldChestRules rules, int chest, int index) {
      try {
        var l = rules.locations.get(chest);
        var original = rules.groupProp(chest, dimension, 300002, GoldenSpearSources.SPEAR);
        return l != null
            && original != null
            && config == GoldenSpearSources.CONFIGS[index]
            && from == GoldenSpearSources.STATES.get(index).getVal()
            && to == GoldenSpearSources.STATES.get(index + 1).getVal()
            && plane == l.plane()
            && floor == l.floor()
            && rules.entry(plane, floor, entry)
            && story >= 0
            && entity > 0
            && runtime > 0
            && at > 0
            && UUID.fromString(event).toString().equals(event)
            && Objects.equals(propSource, PropTimelines.source(original))
            && Objects.equals(groupSource, rules.filePins.get(l.path()))
            && Objects.equals(configSource, rules.rowPins.get("InteractConfig:" + config));
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }

  public List<Step> getSteps() {
    return steps == null ? null : Collections.unmodifiableList(steps);
  }

  public PropState state() {
    return GoldenSpearSources.STATES.get(steps.size());
  }

  boolean valid(WorldChestRules rules) {
    if (rules == null
        || !rules.golden(chest)
        || steps == null
        || steps.isEmpty()
        || steps.size() > GoldenSpearSources.CONFIGS.length) return false;
    long last = 0;
    var keys = new HashSet<String>();
    for (int i = 0; i < steps.size(); i++) {
      var step = steps.get(i);
      if (step == null || !step.valid(rules, chest, i) || step.at < last || !keys.add(step.event))
        return false;
      last = step.at;
    }
    return true;
  }

  void append(Player p, EntityProp actor, WorldChestRules rules, int index, long now) {
    if (steps == null
        || steps.size() != index
        || index < 0
        || index >= GoldenSpearSources.CONFIGS.length)
      throw new IllegalStateException("Spear stage changed");
    steps.add(new Step(p, actor, rules, chest, index, now));
  }
}
