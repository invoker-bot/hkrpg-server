package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Explicit ordinary creation-task lifecycle; entity construction remains a caller binding. */
public final class RogueBattleEventLifecycle46 {
  private RogueBattleEventLifecycle46() {}

  public record Frame(Object eventEntity) {
    public Frame {
      if (eventEntity == null) throw new IllegalArgumentException("Missing callback event entity");
    }
  }

  /** The explicit event-entity projection of the world's parameter register. */
  public static final class Frames {
    private final ArrayList<Frame> frames = new ArrayList<>();

    public Frames(List<Frame> initial) {
      if (initial == null || initial.size() > 255)
        throw new IllegalArgumentException("Invalid finite callback frame stack");
      frames.addAll(List.copyOf(initial));
    }

    public int depth() {
      return frames.size();
    }

    public Frame current() {
      return frames.isEmpty() ? null : frames.getLast();
    }

    public List<Frame> snapshot() {
      return List.copyOf(frames);
    }

    private void push(Frame frame) {
      if (frames.size() == 255)
        throw new IllegalArgumentException("Finite callback stack exhausted");
      frames.add(frame);
    }

    private void pop(Frame expected) {
      if (current() != expected)
        throw new IllegalStateException("Nested callback did not restore its parameter frame");
      frames.removeLast();
    }
  }

  @FunctionalInterface
  public interface Sequence {
    void execute(Frames frames);

    default void dispose() {}
  }

  public record Configuration(
      RogueResonanceDynamicCell46.Scalar eventId,
      List<RogueResonanceEventParameters46.Binding> parameters,
      Sequence beforeCreation,
      Sequence afterCreation) {
    public Configuration {
      if (eventId == null || (parameters != null && parameters.size() > 255))
        throw new IllegalArgumentException("Invalid finite event-task configuration");
      if (parameters != null) parameters = List.copyOf(parameters);
    }
  }

  @FunctionalInterface
  public interface Creation {
    /** Caller owns the entity factory and its before-creation notification point. */
    Object create(
        int eventId,
        RogueBattleEventTable46.Prepared prepared,
        Map<Integer, Long> evaluatedParameters,
        Consumer<Object> beforeCreation);
  }

  public static final class Task {
    public static final int NEW = 0x7777;
    public static final int DONE = 0x9999;
    private int state = NEW;
    private final Configuration config;
    private final RogueResonanceLookup46 context;
    private final Frames frames;
    private Sequence before;
    private Sequence after;
    private Consumer<Object> beforeBridge;

    public Task(Configuration config, RogueResonanceLookup46 context, Frames frames) {
      if (config == null || context == null)
        throw new IllegalArgumentException("Missing explicit event-task context");
      this.config = config;
      this.context = context;
      this.frames = frames;
      before = config.beforeCreation();
      after = config.afterCreation();
      beforeBridge = entity -> callback(before, entity);
    }

    public int state() {
      return state;
    }

    public Optional<Object> begin(
        Map<Integer, RogueBattleEventTable46.Config> events,
        int currentWaveStageId,
        IntFunction<RogueBattleEventTable46.WaveStage> stages,
        Creation creation) {
      state = DONE;
      long raw = config.eventId().read(context::read);
      if ((raw & 1) != 0 || raw < 0)
        throw new IllegalArgumentException("Unmatched finite event-task identity");
      int eventId = (int) (raw >> 33);
      var prepared = RogueBattleEventTable46.prepare(eventId, events, currentWaveStageId, stages);
      if (prepared.isEmpty()) return Optional.empty();
      if (creation == null) throw new IllegalArgumentException("Missing explicit event creation");
      var parameters = RogueResonanceEventParameters46.evaluate(config.parameters(), context);
      Object entity = creation.create(eventId, prepared.get(), parameters, beforeBridge);
      if (entity != null) callback(after, entity);
      return Optional.ofNullable(entity);
    }

    private void callback(Sequence sequence, Object entity) {
      if (entity == null || sequence == null || frames == null) return;
      var frame = new Frame(entity);
      frames.push(frame);
      // The matched ordinary callback helpers have no exception/termination handler.
      // They pop the frame only after the child sequence returns normally.
      sequence.execute(frames);
      frames.pop(frame);
    }

    public void dispose() {
      if (after != null) after.dispose();
      after = null;
      if (before != null) before.dispose();
      before = null;
      beforeBridge = null;
    }
  }
}
