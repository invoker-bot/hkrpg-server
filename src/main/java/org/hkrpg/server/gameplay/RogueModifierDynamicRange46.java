package org.hkrpg.server.gameplay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Explicit range groups, callback frames and shared parameters; native world factories stay bound.
 */
public final class RogueModifierDynamicRange46 {
  private RogueModifierDynamicRange46() {}

  public static final class State {
    public final RogueModifierReplace46.State replacement;
    public Map<Long, List<Entry>> listenerGroups;
    public GameMode gameMode;

    public State(RogueModifierReplace46.State replacement) {
      if (replacement == null)
        throw new IllegalArgumentException("Missing range replacement state");
      this.replacement = replacement;
    }
  }

  public record Frame(Object modifier, int key, int callbackType) {}

  public static final class GameMode {
    public List<Frame> callbackFrames;

    public GameMode(List<Frame> callbackFrames) {
      this.callbackFrames = callbackFrames;
    }
  }

  public static final class Config {
    public RogueResonanceDynamicCell46.Scalar minimum, maximum;
    public boolean maximumInclusive, triggerClampedChange;

    public Config(
        RogueResonanceDynamicCell46.Scalar minimum, RogueResonanceDynamicCell46.Scalar maximum) {
      this.minimum = minimum;
      this.maximum = maximum;
    }
  }

  public static final class Entry {
    public Config config;
    public Object enterSequence, exitSequence, changeSequence;
    public byte enterDepth, exitDepth, changeDepth;
    public boolean inRange;

    public Entry(Config config) {
      this.config = config;
    }
  }

  public record Limits(int recursionLimit) {}

  public record Extra(Object additionalContext, int extraHitCount) {}

  public static final class CallbackParameters {
    public long[] values = new long[2];
    public Object additionalContext;
    public int extraHitCount;
  }

  /** Finite explicit parameter pool; the original context/factory lookup is not synthesized. */
  public static final class ParameterPool {
    public final List<CallbackParameters> active = new ArrayList<>();
    public final ArrayDeque<CallbackParameters> free = new ArrayDeque<>();

    public CallbackParameters acquire() {
      CallbackParameters parameters = free.pollLast();
      return parameters == null ? new CallbackParameters() : parameters;
    }

    public void register(CallbackParameters parameters) {
      if (parameters == null || active.size() >= 255)
        throw new IllegalArgumentException("Invalid finite active range parameters");
      active.add(parameters);
    }

    public CallbackParameters removeLast() {
      if (active.isEmpty()) throw new IllegalArgumentException("Missing current range parameters");
      return active.removeLast();
    }

    public void recycle(CallbackParameters parameters) {
      if (parameters == null || free.size() >= 255)
        throw new IllegalArgumentException("Invalid finite free range parameters");
      free.addLast(parameters);
    }
  }

  public interface World {
    Limits limits(State state);

    Object taskContext(State state);

    RogueResonanceLookup46 lookup(Object context);

    ParameterPool parameterPool(Object context);

    void executeSequence(Object sequence);
  }

  public static void changed(
      State state, long scopedKey, RogueResonanceVariables46.Change change, World world) {
    if (state == null || change == null || world == null)
      throw new IllegalArgumentException("Missing explicit dynamic-range notification binding");
    Map<Long, List<Entry>> groups = state.listenerGroups;
    if (groups == null || !groups.containsKey(scopedKey)) return;
    List<Entry> entries = groups.get(scopedKey);
    List<Frame> frames = frames(state);
    if (frames.size() >= 255) throw new IllegalArgumentException("Excessive finite range frames");
    frames.add(new Frame(state.replacement, (int) (scopedKey >>> 32), 3));
    long delta =
        change.previousPresent()
            ? RogueResonanceExpression46.subtractStandard(change.current(), change.previous())
            : 0;
    groupChanged(state, entries, change, delta, new Extra(null, 0), world);
    List<Frame> current = frames(state);
    if (current.isEmpty()) throw new IllegalArgumentException("Missing current range frame");
    current.removeLast();
  }

  private static List<Frame> frames(State state) {
    if (state.gameMode == null || state.gameMode.callbackFrames == null)
      throw new IllegalArgumentException("Missing current range game-mode frames");
    return state.gameMode.callbackFrames;
  }

  private static Config config(Entry entry) {
    if (entry.config == null) throw new IllegalArgumentException("Missing current range config");
    return entry.config;
  }

  private static Object context(State state, World world) {
    Object context = world.taskContext(state);
    if (context == null) throw new IllegalArgumentException("Missing current range task context");
    return context;
  }

  private static ParameterPool pool(State state, World world) {
    ParameterPool pool = world.parameterPool(context(state, world));
    if (pool == null) throw new IllegalArgumentException("Missing current range parameter pool");
    return pool;
  }

  private static long evaluate(
      State state, RogueResonanceDynamicCell46.Scalar scalar, World world) {
    Object context = context(state, world);
    if (scalar == null) throw new IllegalArgumentException("Missing current range scalar");
    if (scalar.postfix() == null) return scalar.fixedRaw();
    RogueResonanceLookup46 lookup = world.lookup(context);
    if (lookup == null) throw new IllegalArgumentException("Missing explicit range lookup");
    return scalar.read(lookup::read);
  }

  private static boolean allowed(int limit, byte depth) {
    return (byte) limit < 0 || (byte) limit >= depth;
  }

  public static void groupChanged(
      State state,
      List<Entry> entries,
      RogueResonanceVariables46.Change change,
      long delta,
      Extra extra,
      World world) {
    if (state == null || change == null || world == null)
      throw new IllegalArgumentException("Missing explicit range group binding");
    Limits limits = world.limits(state);
    if (limits == null)
      throw new IllegalArgumentException("Missing native range recursion limit binding");
    if (entries == null) throw new IllegalArgumentException("Missing captured range group");
    int limit = limits.recursionLimit();
    CallbackParameters parameters = null;
    for (int index = 0; index < entries.size(); index++) {
      if (index >= 255) throw new IllegalArgumentException("Excessive finite range group");
      Entry entry = entries.get(index);
      if (entry == null) throw new IllegalArgumentException("Missing current range entry");
      long minimum = evaluate(state, config(entry).minimum, world);
      int lower = RogueModifierInjection46.compareRaw(change.current(), minimum);
      long maximum = evaluate(state, config(entry).maximum, world);
      int upper = RogueModifierInjection46.compareRaw(change.current(), maximum);
      Config current = config(entry);
      boolean inRange = lower >= 0 && (current.maximumInclusive ? upper <= 0 : upper < 0);
      if (entry.inRange != inRange) {
        entry.inRange = inRange;
        if (inRange) {
          Object sequence = entry.enterSequence;
          if (sequence != null && allowed(limit, entry.enterDepth)) {
            entry.enterDepth++;
            world.executeSequence(sequence);
            entry.enterDepth--;
          }
        } else {
          Object sequence = entry.exitSequence;
          if (sequence != null && allowed(limit, entry.exitDepth)) {
            entry.exitDepth++;
            world.executeSequence(sequence);
            entry.exitDepth--;
          }
        }
        continue;
      }
      if (!inRange || !change.previousPresent()) continue;
      if (RogueModifierInjection46.compareRaw(change.previous(), change.current()) == 0
          && (!current.triggerClampedChange || RogueModifierInjection46.compareRaw(delta, 0) == 0))
        continue;
      if (entry.changeSequence == null) continue;
      if (parameters == null) {
        ParameterPool selected = pool(state, world);
        parameters = selected.acquire();
        selected.register(parameters);
        long actual =
            RogueResonanceExpression46.subtractStandard(change.current(), change.previous());
        if (parameters.values == null || parameters.values.length < 1)
          throw new IllegalArgumentException("Missing first range callback parameter");
        parameters.values[0] = actual;
        if (parameters.values.length < 2)
          throw new IllegalArgumentException("Missing second range callback parameter");
        parameters.values[1] = delta;
        parameters.additionalContext = extra == null ? null : extra.additionalContext();
        if (extra == null)
          throw new IllegalArgumentException("Missing explicit range callback extra data");
        parameters.extraHitCount = extra.extraHitCount();
      }
      if (allowed(limit, entry.changeDepth)) {
        entry.changeDepth++;
        world.executeSequence(entry.changeSequence);
        entry.changeDepth--;
      }
    }
    if (parameters != null) {
      ParameterPool current = pool(state, world);
      current.recycle(current.removeLast());
    }
  }
}
