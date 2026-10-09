package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntToLongFunction;

/** Finite standard-number cells in an explicitly selected 4.6 dynamic-value lookup. */
public final class RogueResonanceVariables46 {
  private static final long SOURCE_MINIMUM = -6710886399932891135L;
  private static final long SOURCE_MAXIMUM = 6710886399932891137L;

  public record Value(long raw, long minimum, long maximum) {}

  public record Change(int key, long previous, long current, boolean previousPresent) {
    public Change(int key, long previous, long current) {
      this(key, previous, current, true);
    }
  }

  public record Bounds(long minimum, long maximum) {}

  public static RogueResonanceVariables46 withDynamicCells(
      RogueResonanceVariables46 base, Map<Integer, RogueResonanceDynamicCell46> values) {
    return withProjectedCells(base, values, null);
  }

  /** Mixed standard cells with an explicit alias callback; no live task graph is installed. */
  public static RogueResonanceVariables46 withProjectedCells(
      RogueResonanceVariables46 base,
      Map<Integer, ? extends RogueResonanceCell46> values,
      RogueResonanceTaskCell46.AliasEvaluator evaluator) {
    if (base == null
        || base.aiTagBase != null
        || base.dynamicBase != null
        || values == null
        || values.size() > 255)
      throw new IllegalArgumentException("Invalid finite dynamic-cell projection");
    try {
      var copied = Map.copyOf(values);
      var keys = new HashSet<>(base.cells.keySet());
      keys.addAll(copied.keySet());
      if (keys.size() > 255) throw new IllegalArgumentException("Invalid finite dynamic cells");
      var cells = new HashMap<Integer, RogueResonanceCell46>();
      for (var entry : copied.entrySet()) cells.put(entry.getKey(), entry.getValue().copy());
      return new RogueResonanceVariables46(
          base, new DynamicProjection(Map.copyOf(cells), evaluator));
    } catch (NullPointerException malformed) {
      throw new IllegalArgumentException("Invalid dynamic-cell projection", malformed);
    }
  }

  private static final class Cell {
    private long raw;
    private final long minimum;
    private final long maximum;
    private final boolean sourceDefaults;

    private Cell(Value value) {
      raw = standard(value.raw());
      minimum = standard(value.minimum());
      maximum = standard(value.maximum());
      sourceDefaults = false;
    }

    private Cell(Cell previous, long sourceRaw) {
      raw = standard(sourceRaw);
      minimum = previous.minimum;
      maximum = previous.maximum;
      sourceDefaults = previous.sourceDefaults;
    }

    private Cell(long sourceRaw) {
      raw = standard(sourceRaw);
      minimum = SOURCE_MINIMUM;
      maximum = SOURCE_MAXIMUM;
      sourceDefaults = true;
    }
  }

  private final Map<Integer, Cell> cells;
  private final Consumer<Change> observer;
  private final RogueResonanceObserver46 listeners = new RogueResonanceObserver46();
  private final RogueResonanceVariables46 aiTagBase;
  private final Map<Integer, List<Long>> aiTagValues;
  private final RogueResonanceVariables46 dynamicBase;
  private final Map<Integer, RogueResonanceCell46> dynamicCells;
  // Protected by the fixed base monitor; context reads replace it only within that monitor.
  private RogueResonanceTaskCell46.AliasEvaluator aliasEvaluator;
  private final ThreadLocal<ArrayList<Integer>> dynamicReads = new ThreadLocal<>();

  private record DynamicProjection(
      Map<Integer, RogueResonanceCell46> values,
      RogueResonanceTaskCell46.AliasEvaluator evaluator) {}

  private RogueResonanceVariables46(RogueResonanceVariables46 base, DynamicProjection projection) {
    cells = null;
    observer = null;
    aiTagBase = null;
    aiTagValues = Map.of();
    dynamicBase = base;
    dynamicCells = projection.values();
    aliasEvaluator = projection.evaluator();
  }

  private long readDynamic(int key, IntToLongFunction provider) {
    synchronized (dynamicBase) {
      return readDynamicLocked(key, provider);
    }
  }

  private long readDynamicLocked(int key, IntToLongFunction provider) {
    var stack = dynamicReads.get();
    if (stack == null) {
      stack = new ArrayList<>();
      dynamicReads.set(stack);
    }
    if (stack.size() >= 32 || stack.contains(key))
      throw new IllegalArgumentException("Cyclic or excessive finite dynamic-cell reads");
    stack.add(key);
    try {
      return dynamicCells.get(key).read(provider, aliasEvaluator);
    } finally {
      stack.removeLast();
      if (stack.isEmpty()) dynamicReads.remove();
    }
  }

  /** Immutable, explicit tag-stack read projection over an existing fixed-cell lookup. */
  public static RogueResonanceVariables46 withAiTagProjection(
      RogueResonanceVariables46 base, Map<Integer, List<Long>> stacks) {
    if (base == null || base.aiTagBase != null || stacks == null || stacks.size() > 255) {
      throw new IllegalArgumentException("Invalid finite AITag projection");
    }
    var values = new HashMap<Integer, List<Long>>();
    try {
      for (var entry : Map.copyOf(stacks).entrySet()) {
        if (entry.getValue().size() > 255)
          throw new IllegalArgumentException("Invalid finite AITag stack");
        var copied = List.copyOf(entry.getValue());
        for (long raw : copied) standard(raw);
        values.put(entry.getKey(), copied);
      }
    } catch (NullPointerException malformed) {
      throw new IllegalArgumentException("Invalid AITag projection value", malformed);
    }
    if (values.size() > 255) throw new IllegalArgumentException("Invalid finite AITag projection");
    return new RogueResonanceVariables46(base, Map.copyOf(values));
  }

  private RogueResonanceVariables46(
      RogueResonanceVariables46 base, Map<Integer, List<Long>> stacks) {
    cells = null;
    observer = null;
    aiTagBase = base;
    aiTagValues = stacks;
    dynamicBase = null;
    dynamicCells = Map.of();
    aliasEvaluator = null;
  }

  public static RogueResonanceVariables46 fromSourceDefaults(
      Map<Integer, Long> fixedValues, Consumer<Change> observer) {
    if (fixedValues == null || fixedValues.size() > 255) {
      throw new IllegalArgumentException("Invalid finite source variable declarations");
    }
    Map<Integer, Long> values;
    try {
      values = Map.copyOf(fixedValues);
    } catch (NullPointerException malformed) {
      throw new IllegalArgumentException("Invalid source variable declaration", malformed);
    }
    if (values.size() > 255) {
      throw new IllegalArgumentException("Invalid finite source variable declarations");
    }
    var lookup = new RogueResonanceVariables46(Map.of(), observer);
    for (var entry : values.entrySet())
      lookup.cells.put(entry.getKey(), new Cell(entry.getValue()));
    return lookup;
  }

  /** Standard fixed injection with native source defaults; keeps old bounds without reclamping. */
  public synchronized void injectSourceStandard(int key, long raw) {
    if (aiTagBase != null || dynamicBase != null)
      throw new IllegalArgumentException("Unmatched injection into a projected variable view");
    standard(raw);
    Cell previous = cells.get(key);
    if (previous == null && cells.size() >= 255)
      throw new IllegalArgumentException("Excessive finite injected root cells");
    Cell injected = previous == null ? new Cell(raw) : new Cell(previous, raw);
    cells.put(key, injected);
    notifyObservers(new Change(key, 0L, raw, false));
  }

  public synchronized void combineObserver(RogueResonanceObserver46.Listener listener) {
    requireRootObservers();
    listeners.combine(listener);
  }

  public synchronized boolean removeObserver(RogueResonanceObserver46.Listener listener) {
    requireRootObservers();
    return listeners.remove(listener);
  }

  public synchronized int attachedObserverCount() {
    requireRootObservers();
    return listeners.snapshot().size();
  }

  private void requireRootObservers() {
    if (aiTagBase != null || dynamicBase != null)
      throw new IllegalArgumentException(
          "Unmatched listener membership on a projected variable view");
  }

  private boolean hasObservers() {
    return observer != null || !listeners.snapshot().isEmpty();
  }

  private void notifyObservers(Change change) {
    var snapshot = listeners.snapshot();
    if (observer != null) observer.accept(change);
    RogueResonanceObserver46.dispatch(snapshot, change);
  }

  public Bounds bounds(int key) {
    return boundsWithProvider(key, this::readNativeFixed);
  }

  private Bounds boundsWithProvider(int key, IntToLongFunction provider) {
    if (aiTagBase != null) return aiTagBase.boundsWithProvider(key, provider);
    if (dynamicBase != null) {
      var value = dynamicCells.get(key);
      return value == null ? dynamicBase.boundsWithProvider(key, provider) : value.bounds(provider);
    }
    synchronized (this) {
      var cell = cells.get(key);
      if (cell == null) throw new IllegalArgumentException("Unresolved resonance variable");
      return new Bounds(cell.minimum, cell.maximum);
    }
  }

  public RogueResonanceVariables46(Map<Integer, Value> values, Consumer<Change> observer) {
    if (values == null || values.size() > 255) {
      throw new IllegalArgumentException("Invalid finite resonance lookup");
    }
    Map<Integer, Value> inputs;
    try {
      inputs = Map.copyOf(values);
    } catch (NullPointerException malformed) {
      throw new IllegalArgumentException("Invalid resonance cell", malformed);
    }
    if (inputs.size() > 255) throw new IllegalArgumentException("Invalid finite resonance lookup");
    cells = new HashMap<>();
    for (var entry : inputs.entrySet()) cells.put(entry.getKey(), new Cell(entry.getValue()));
    this.observer = observer;
    aiTagBase = null;
    aiTagValues = Map.of();
    dynamicBase = null;
    dynamicCells = Map.of();
    aliasEvaluator = null;
  }

  public long read(int key) {
    return readWithProvider(key, false, this::readNativeFixed);
  }

  /** Native AITag reads its last stack value; local cell lookup returns zero on a missing key. */
  public long readNativeFixed(int key) {
    return readWithProvider(key, true, this::readNativeFixed);
  }

  long readBound(int key, boolean nativeRead, RogueResonanceTaskCell46.AliasEvaluator evaluator) {
    var binding = aiTagBase == null ? this : aiTagBase.bindingLookup();
    synchronized (monitorBase()) {
      var previous = binding.aliasEvaluator;
      binding.aliasEvaluator = evaluator;
      try {
        return nativeRead ? readNativeFixed(key) : read(key);
      } finally {
        binding.aliasEvaluator = previous;
      }
    }
  }

  private RogueResonanceVariables46 bindingLookup() {
    return aiTagBase == null ? this : aiTagBase.bindingLookup();
  }

  private long readWithProvider(int key, boolean nativeRead, IntToLongFunction provider) {
    if (dynamicBase != null)
      return dynamicCells.containsKey(key)
          ? readDynamic(key, provider)
          : dynamicBase.readWithProvider(key, nativeRead, provider);
    if (aiTagBase != null) {
      var values = aiTagValues.get(key);
      if (nativeRead && values != null && !values.isEmpty()) return values.getLast();
      return aiTagBase.readWithProvider(key, nativeRead, provider);
    }
    synchronized (this) {
      var cell = cells.get(key);
      if (cell == null && !nativeRead)
        throw new IllegalArgumentException("Unresolved resonance variable");
      return cell == null ? 0L : cell.raw;
    }
  }

  public boolean contains(int key) {
    if (aiTagBase != null) return aiTagBase.contains(key);
    if (dynamicBase != null) return dynamicCells.containsKey(key) || dynamicBase.contains(key);
    synchronized (this) {
      return cells.containsKey(key);
    }
  }

  /**
   * Native setter ignores absent keys, clamps an existing cell, then notifies even if unchanged.
   */
  public boolean write(int key, long raw) {
    return writeWithProvider(key, raw, this::readNativeFixed);
  }

  private boolean writeWithProvider(int key, long raw, IntToLongFunction provider) {
    if (aiTagBase != null) return aiTagBase.writeWithProvider(key, raw, provider);
    if (dynamicBase != null) {
      var cell = dynamicCells.get(key);
      if (cell == null) return dynamicBase.writeWithProvider(key, raw, provider);
      standard(raw);
      // Use the fixed base monitor, also used by RoundToInt and observer reentry. A view
      // must not introduce a second monitor between the shared cell and its observer.
      synchronized (dynamicBase) {
        long previous = cell.read(provider, aliasEvaluator);
        long clipped = cell.clip(raw, provider);
        cell.replaceValue(clipped);
        if (dynamicBase.hasObservers())
          dynamicBase.notifyObservers(
              new Change(key, previous, cell.read(provider, aliasEvaluator)));
        return true;
      }
    }
    standard(raw);
    synchronized (this) {
      var cell = cells.get(key);
      if (cell == null) return false;
      long previous = cell.raw;
      // All signed standard words represent [-2^30, 2^30), strictly inside the native
      // absent-range bounds [-99999999999, 99999999999]. Preserve the original raw word.
      // This does not open mixed arithmetic or extended cell values.
      cell.raw = cell.sourceDefaults ? raw : clamp(raw, cell.minimum, cell.maximum);
      notifyObservers(new Change(key, previous, cell.raw));
      return true;
    }
  }

  /** Numeric values only; selecting a lookup in a live TaskContext remains the caller's work. */
  public Map<Integer, Long> snapshot() {
    return snapshotWithProvider(this::readNativeFixed);
  }

  private Map<Integer, Long> snapshotWithProvider(IntToLongFunction provider) {
    if (aiTagBase != null) return aiTagBase.snapshotWithProvider(provider);
    if (dynamicBase != null) {
      var values = new HashMap<>(dynamicBase.snapshotWithProvider(provider));
      for (var key : dynamicCells.keySet()) values.put(key, readDynamic(key, provider));
      return Map.copyOf(values);
    }
    synchronized (this) {
      var result = new HashMap<Integer, Long>();
      for (var entry : cells.entrySet()) result.put(entry.getKey(), entry.getValue().raw);
      return Map.copyOf(result);
    }
  }

  private void roundToInt(int key) {
    // Views share only the base monitor. Observer reentry must not acquire a second lock.
    var base = monitorBase();
    synchronized (base) {
      long rounded = (long) RogueResonanceFunctions46.roundToInt(read(key)) << 33;
      write(key, rounded);
    }
  }

  private void roundNativeFixedToInt(int key) {
    var base = monitorBase();
    synchronized (base) {
      long rounded = (long) RogueResonanceFunctions46.roundToInt(readNativeFixed(key)) << 33;
      write(key, rounded);
    }
  }

  private RogueResonanceVariables46 monitorBase() {
    if (aiTagBase != null) return aiTagBase.monitorBase();
    return dynamicBase == null ? this : dynamicBase;
  }

  private static long standard(long raw) {
    if ((raw & 1) != 0) {
      throw new IllegalArgumentException("Extended resonance cell is unmatched");
    }
    return raw;
  }

  public static long clamp(long raw, long minimum, long maximum) {
    standard(raw);
    standard(minimum);
    standard(maximum);
    // Preserve the native lower-first branch even when the supplied bounds are inverted.
    if (raw < minimum) return minimum;
    return raw > maximum ? maximum : raw;
  }

  public static int nameKey(String name) {
    if (name == null || name.isEmpty() || name.length() > 64) {
      throw new IllegalArgumentException("Invalid finite resonance name");
    }
    int first = 0x1505, second = 0x1505;
    for (int i = 0; i < name.length(); i++) {
      char character = name.charAt(i);
      if (character < 1 || character >= 128) {
        throw new IllegalArgumentException("Unmatched resonance name encoding");
      }
      if ((i & 1) == 0) first = first * 33 ^ character;
      else second = second * 33 ^ character;
    }
    return first + second * 0x5d588b65;
  }

  /** Variant 1221 only; the lookup must already have been selected by the owning task context. */
  public static final class RoundToIntTask {
    private final int key;
    private volatile int state = 0x7777;

    public RoundToIntTask(int variant, String dynamicKey) {
      if (variant != 1221) throw new IllegalArgumentException("Unmatched resonance task variant");
      key = nameKey(dynamicKey);
    }

    public int state() {
      return state;
    }

    public void execute(RogueResonanceVariables46 selectedLookup) {
      if (selectedLookup == null) {
        throw new IllegalArgumentException("Missing resonance task lookup");
      }
      selectedLookup.roundToInt(key);
      state = 0x9999;
    }

    public void executeInContext(RogueResonanceContext46 context, int scope) {
      executeInContext((RogueResonanceLookup46) context, scope);
    }

    public void executeInContext(RogueResonanceLookup46 context, int scope) {
      if (context == null) throw new IllegalArgumentException("Missing resonance task context");
      var selected = context.select(scope, key);
      if (selected == null) throw new IllegalArgumentException("Missing resonance task lookup");
      selected.roundNativeFixedToInt(key);
      state = 0x9999;
    }
  }
}
