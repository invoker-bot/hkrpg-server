package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.List;

/** Creates range entries with explicit child factories and the existing finite task sequence. */
public final class RogueModifierRangeSetup46 {
  private RogueModifierRangeSetup46() {}

  @FunctionalInterface
  public interface ChildFactory {
    RogueTaskSequence46.Task create(ContextSequence parent);
  }

  public static final class Definition {
    public final RogueModifierDynamicRange46.Config range;
    public ChildFactory[] onEnter, onExit, onChange;

    public Definition(RogueModifierDynamicRange46.Config range) {
      this.range = range;
    }
  }

  public static final class ContextSequence
      implements RogueTaskSequence46.Task, RogueModifierSequences46.Sequence {
    public final Object context;
    private RogueTaskSequence46.Sequence sequence;

    private ContextSequence(Object context) {
      this.context = context;
    }

    public List<RogueTaskSequence46.Task> children() {
      return bound().children();
    }

    @Override
    public int state() {
      return sequence == null ? RogueTaskSequence46.READY : sequence.state();
    }

    @Override
    public void state(int value) {
      bound().state(value);
    }

    @Override
    public void begin() {
      bound().begin();
    }

    @Override
    public void tick(float delta) {
      bound().tick(delta);
    }

    @Override
    public int step(float delta) {
      return bound().step(delta);
    }

    @Override
    public void reset() {
      bound().reset();
    }

    @Override
    public void dispose() {
      bound().dispose();
    }

    private RogueTaskSequence46.Sequence bound() {
      if (sequence == null)
        throw new IllegalArgumentException("Incomplete explicit range sequence construction");
      return sequence;
    }
  }

  @FunctionalInterface
  public interface World {
    Object taskContext(RogueModifierDynamicRange46.State state);
  }

  public static void append(
      RogueModifierDynamicRange46.State state,
      Definition[] definitions,
      List<RogueModifierDynamicRange46.Entry> target,
      World world) {
    if (definitions == null)
      throw new IllegalArgumentException("Missing captured range definitions");
    if (definitions.length == 0) return;
    if (state == null || world == null)
      throw new IllegalArgumentException("Missing explicit range setup binding");
    for (int index = 0; index < definitions.length; index++) {
      if (index >= 255) throw new IllegalArgumentException("Excessive finite range definitions");
      Definition definition = definitions[index];
      if (definition == null || definition.range == null)
        throw new IllegalArgumentException("Missing explicit range definition");
      var entry = new RogueModifierDynamicRange46.Entry(definition.range);
      ChildFactory[] enter = definition.onEnter;
      if (enter != null) entry.enterSequence = create(state, enter, world);
      ChildFactory[] exit = definition.onExit;
      if (exit != null) entry.exitSequence = create(state, exit, world);
      ChildFactory[] change = definition.onChange;
      if (change != null) entry.changeSequence = create(state, change, world);
      entry.enterDepth = entry.exitDepth = entry.changeDepth = 0;
      entry.inRange = false;
      if (target == null || target.size() >= 255)
        throw new IllegalArgumentException("Missing or excessive finite target range list");
      target.add(entry);
    }
  }

  private static ContextSequence create(
      RogueModifierDynamicRange46.State state, ChildFactory[] factories, World world) {
    Object context = world.taskContext(state);
    var parent = new ContextSequence(context);
    if (factories.length > 512)
      throw new IllegalArgumentException("Excessive explicit range child factories");
    var children = new ArrayList<RogueTaskSequence46.Task>();
    for (ChildFactory factory : factories) {
      if (factory == null)
        throw new IllegalArgumentException("Missing explicit range child factory");
      RogueTaskSequence46.Task task = factory.create(parent);
      if (task == null) throw new IllegalArgumentException("Missing explicit range child task");
      children.add(task);
    }
    parent.sequence = new RogueTaskSequence46.Sequence(children, false);
    return parent;
  }
}
