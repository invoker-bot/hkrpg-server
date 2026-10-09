package org.hkrpg.server.gameplay;

import java.util.List;
import java.util.function.Consumer;

/** Explicit child-task sequence; original task factories and global plugins remain external. */
public final class RogueTaskSequence46 {
  private RogueTaskSequence46() {}

  public static final int READY = 0x7777;
  public static final int EXECUTING = 0x8888;
  public static final int SUCCESS = 0x9999;
  public static final int FAIL = 0xaaaa;

  public interface Task {
    int state();

    void begin();

    void tick(float delta);

    void reset();

    default void dispose() {}
  }

  public interface ImmediateTask extends Task {
    void finishImmediately(float delta);
  }

  public interface UnstartedFailureTask extends Task {
    void onUnstartedFailure();
  }

  public static final class Sequence implements Task, RogueModifierSequences46.Sequence {
    private List<Task> children;
    private final boolean finishImmediatelyInPerformance;
    private int state = READY;
    private boolean disposed;

    public Sequence(List<? extends Task> children, boolean finishImmediatelyInPerformance) {
      if (children == null || children.size() > 512)
        throw new IllegalArgumentException("Invalid finite original task sequence");
      try {
        this.children = List.copyOf(children);
      } catch (NullPointerException invalid) {
        throw new IllegalArgumentException("Missing explicit child task", invalid);
      }
      this.finishImmediatelyInPerformance = finishImmediatelyInPerformance;
    }

    public int state() {
      return state;
    }

    public List<Task> children() {
      return children;
    }

    public void stopForChild(int value) {
      if (value != SUCCESS && value != FAIL)
        throw new IllegalArgumentException("Invalid explicit sequence terminal state");
      state = value;
    }

    public void begin() {
      active();
      int result = step(0f);
      state = result == SUCCESS || result == FAIL ? result : EXECUTING;
    }

    public void tick(float delta) {
      active();
      if (state != EXECUTING) return;
      if (!Float.isFinite(delta) || delta < 0)
        throw new IllegalArgumentException("Invalid finite child-task delta");
      state = step(delta);
    }

    public void state(int value) {
      active();
      if (value != READY && value != EXECUTING && value != SUCCESS && value != FAIL)
        throw new IllegalArgumentException("Unmatched modifier sequence state");
      state = value;
    }

    public int step(float delta) {
      active();
      if (!Float.isFinite(delta) || delta < 0)
        throw new IllegalArgumentException("Invalid finite modifier sequence delta");
      return stepChildren(delta);
    }

    private int stepChildren(float delta) {
      // Every pass scans from the first child. Completed children do not run again.
      for (int index = 0; index < children.size(); index++) {
        Task child = children.get(index);
        int childState = child.state();
        if (childState == READY) {
          child.begin();
          childState = child.state();
        }
        if (state == SUCCESS || state == FAIL) return state;
        if (childState == SUCCESS) continue;
        if (childState == EXECUTING) {
          state = EXECUTING;
          if (finishImmediatelyInPerformance && child instanceof ImmediateTask immediate)
            immediate.finishImmediately(delta);
          else child.tick(delta);
          if (child.state() == SUCCESS) continue;
          if (state == FAIL) notifyLaterReady(index);
          return state;
        }
        if (childState == FAIL) {
          state = FAIL;
          notifyLaterReady(index);
          return state;
        }
        throw new IllegalStateException("Unmatched original child-task state: " + childState);
      }
      state = SUCCESS;
      return state;
    }

    private void notifyLaterReady(int current) {
      for (int index = current + 1; index < children.size(); index++) {
        Task child = children.get(index);
        if (child instanceof UnstartedFailureTask skipped && child.state() == READY)
          skipped.onUnstartedFailure();
      }
    }

    public void reset() {
      active();
      for (var child : children) child.reset();
      state = READY;
    }

    public int runOnce(Consumer<Integer> waitingDiagnostic) {
      if (waitingDiagnostic == null)
        throw new IllegalArgumentException("Missing explicit waiting diagnostic");
      begin();
      int result = state;
      if (result == EXECUTING) waitingDiagnostic.accept(result);
      reset();
      return result;
    }

    public void dispose() {
      if (disposed) return;
      for (var child : children) child.dispose();
      children = List.of();
      disposed = true;
    }

    private void active() {
      if (disposed) throw new IllegalStateException("Disposed explicit sequence binding");
    }
  }

  public static RogueBattleEventLifecycle46.Sequence callback(
      Sequence sequence, Consumer<Integer> diagnostic) {
    if (sequence == null || diagnostic == null)
      throw new IllegalArgumentException("Missing explicit callback sequence");
    return new RogueBattleEventLifecycle46.Sequence() {
      public void execute(RogueBattleEventLifecycle46.Frames frames) {
        sequence.runOnce(diagnostic);
      }

      public void dispose() {
        sequence.dispose();
      }
    };
  }
}
