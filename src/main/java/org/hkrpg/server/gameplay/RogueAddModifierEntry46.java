package org.hkrpg.server.gameplay;

import java.util.List;

/** Ordinary entry prefix for the six Caster/literal source tasks; target effects stay bound. */
public final class RogueAddModifierEntry46 {
  private RogueAddModifierEntry46() {}

  public record Defaults(
      boolean aliveOnly,
      long chanceRaw,
      long maxTargetCountRaw,
      boolean showUiFromModel,
      boolean showUiOnUpdate,
      float uiInternalTime,
      boolean mergeUiMessages,
      boolean useFixedPosition) {}

  // Exact constructor writes; -1 is a source sentinel, not a probability or target count.
  public static final Defaults DEFAULTS =
      new Defaults(true, -(1L << 33), -(1L << 33), true, true, -1f, true, true);
  public static final int SOURCE_ALIVE_MASK = 1;

  /** A missing native ability component corresponds to an absent charm flag. */
  public record Source(Object entity, Boolean inCharmAction) {
    public Source {
      if (entity == null) throw new IllegalArgumentException("Missing explicit source entity");
    }

    public boolean charmed() {
      return Boolean.TRUE.equals(inCharmAction);
    }
  }

  public record Request(
      String literalName, Defaults defaults, Object sourceEntity, Object targetEntity) {
    public Request {
      if (literalName == null
          || literalName.isEmpty()
          || defaults == null
          || sourceEntity == null
          || targetEntity == null)
        throw new IllegalArgumentException("Missing explicit modifier request");
    }
  }

  public interface Context {
    Source source();

    List<Object> targets(String alias, int aliveMask);

    /**
     * Owns definition resolution, eligibility, caster inheritance and actual effect application.
     */
    void apply(Request request);

    /** Owns the remaining context-owner/target notification; source and owner can differ. */
    void finished(List<Object> targets);
  }

  public static final class Task implements RogueTaskSequence46.Task {
    private final RogueResonanceOnAdd46.Modifier command;
    private final Context context;
    private int state = RogueTaskSequence46.READY;

    public Task(RogueResonanceOnAdd46.Modifier command, Context context) {
      if (command == null || context == null)
        throw new IllegalArgumentException("Missing finite modifier entry binding");
      this.command = command;
      this.context = context;
    }

    public int state() {
      return state;
    }

    public void begin() {
      Source source = context.source();
      if (source == null) throw new IllegalArgumentException("Missing explicit source projection");
      if (source.charmed()) {
        state = RogueTaskSequence46.SUCCESS;
        return;
      }
      var resolved = context.targets(command.targetAlias(), SOURCE_ALIVE_MASK);
      if (resolved == null || resolved.size() > 255)
        throw new IllegalArgumentException("Invalid finite target evaluation");
      List<Object> targets;
      try {
        targets = List.copyOf(resolved);
      } catch (NullPointerException invalid) {
        throw new IllegalArgumentException("Missing explicit target", invalid);
      }
      if (targets.isEmpty()) {
        state = RogueTaskSequence46.SUCCESS;
        return;
      }
      for (Object target : targets)
        context.apply(new Request(command.literalName(), DEFAULTS, source.entity(), target));
      context.finished(targets);
      // Unlike query/create tasks, this ordinary prefix is completed only after the work returns.
      state = RogueTaskSequence46.SUCCESS;
    }

    public void tick(float delta) {}

    public void reset() {
      state = RogueTaskSequence46.READY;
    }
  }

  public static RogueResonanceOnAdd46.ModifierFactory factory(Context context) {
    if (context == null) throw new IllegalArgumentException("Missing explicit modifier context");
    return command -> new Task(command, context);
  }
}
