package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueResonanceAiTag46Test {
  private static final long ONE = 1L << 33;
  private static final int KEY = RogueResonanceVariables46.nameKey("Extra_AttackTime");

  private static RogueResonanceVariables46 project(
      RogueResonanceVariables46 base, Map<Integer, List<Long>> tags) {
    try {
      return (RogueResonanceVariables46)
          RogueResonanceVariables46.class
              .getMethod("withAiTagProjection", RogueResonanceVariables46.class, Map.class)
              .invoke(null, base, tags);
    } catch (NoSuchMethodException absent) {
      return null;
    } catch (InvocationTargetException failed) {
      if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new AssertionError(failed.getCause());
    } catch (ReflectiveOperationException failed) {
      throw new AssertionError(failed);
    }
  }

  private static Long read(RogueResonanceVariables46 projected, int key) {
    return projected == null ? null : projected.readNativeFixed(key);
  }

  @Test
  void tagStackTopOverridesExistingBaseCell() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(KEY, ONE), null);
    var projected = project(base, Map.of(KEY, List.of(2 * ONE, 3 * ONE)));
    assertEquals(Long.valueOf(3 * ONE), read(projected, KEY));
    assertEquals(ONE, projected.read(KEY));
    assertEquals(Map.of(KEY, ONE), projected.snapshot());
  }

  @Test
  void nonemptyTagStackReadsWithoutCreatingMissingBaseKey() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(), null);
    var projected = project(base, Map.of(KEY, List.of(ONE)));
    assertEquals(Long.valueOf(ONE), read(projected, KEY));
    assertFalse(projected.contains(KEY));
    assertFalse(projected.write(KEY, 2 * ONE));
    assertThrows(IllegalArgumentException.class, () -> projected.read(KEY));
    assertTrue(base.snapshot().isEmpty());
    assertEquals(ONE, projected.readNativeFixed(KEY));
  }

  @Test
  void emptyOrAbsentStackFallsBackToBaseIncludingNativeZero() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(KEY, -ONE), null);
    var projected = project(base, Map.of(KEY, List.of(), 77, List.of()));
    assertEquals(Long.valueOf(-ONE), read(projected, KEY));
    assertEquals(0, projected.readNativeFixed(77));
    assertEquals(0, projected.readNativeFixed(88));
    base.write(KEY, 2 * ONE);
    assertEquals(2 * ONE, projected.readNativeFixed(KEY));
  }

  @Test
  void nativeRoundReadsTagTopThenWritesBaseWithoutPoppingOrReplacingTag() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(KEY, 0L), changes::add);
    var projected = project(base, Map.of(KEY, List.of(5 * ONE / 2)));
    var owner = new RogueResonanceScopedLookup46.Entity(new Object(), null, projected);
    var lookup =
        new RogueResonanceScopedLookup46(
            new RogueResonanceContext46(null, List.of(), null, null),
            new RogueResonanceScopedLookup46.Tail(null, null, null, owner, null));
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    try {
      task.executeInContext(lookup, 7);
    } catch (IllegalArgumentException unmatched) {
    }
    assertEquals(2 * ONE, base.read(KEY));
    assertEquals(5 * ONE / 2, projected.readNativeFixed(KEY));
    assertEquals(List.of(new RogueResonanceVariables46.Change(KEY, 0, 2 * ONE)), changes);
    assertEquals(0x9999, task.state());
  }

  @Test
  void suppliedTagListsAndKeysAreCopiedBeforeLaterChanges() {
    var values = new ArrayList<>(List.of(ONE, 2 * ONE));
    var tags = new java.util.HashMap<Integer, List<Long>>();
    tags.put(KEY, values);
    var projected = project(RogueResonanceVariables46.fromSourceDefaults(Map.of(), null), tags);
    values.clear();
    tags.clear();
    assertEquals(Long.valueOf(2 * ONE), read(projected, KEY));
  }

  @Test
  void malformedOrExtendedProjectionsAreRejectedIncludingNestedViews() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(), null);
    assertThrows(IllegalArgumentException.class, () -> project(null, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> project(base, null));
    assertThrows(IllegalArgumentException.class, () -> project(base, Map.of(KEY, List.of(1L))));
    var nullList = new java.util.HashMap<Integer, List<Long>>();
    nullList.put(KEY, null);
    assertThrows(IllegalArgumentException.class, () -> project(base, nullList));
    var nullValue = new ArrayList<Long>();
    nullValue.add(null);
    assertThrows(IllegalArgumentException.class, () -> project(base, Map.of(KEY, nullValue)));
    var nullKey = new java.util.HashMap<Integer, List<Long>>();
    nullKey.put(null, List.of(ONE));
    assertThrows(IllegalArgumentException.class, () -> project(base, nullKey));
    assertThrows(IllegalArgumentException.class, () -> project(project(base, Map.of()), Map.of()));
  }

  @Test
  void finiteKeyAndStackLimitsAreCheckedWithoutTruncatingLastValue() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(), null);
    var tags = new java.util.HashMap<Integer, List<Long>>();
    for (int i = 0; i < 255; i++) tags.put(i, java.util.Collections.nCopies(255, ONE));
    var projected = project(base, tags);
    assertEquals(ONE, projected.readNativeFixed(254));
    tags.put(255, List.of());
    assertThrows(IllegalArgumentException.class, () -> project(base, tags));
    assertThrows(
        IllegalArgumentException.class,
        () -> project(base, Map.of(KEY, java.util.Collections.nCopies(256, ONE))));
  }

  @Test
  void strictRoundUsesBaseWhileNativeReadRetainsTagTop() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(KEY, 3 * ONE / 2), null);
    var projected = project(base, Map.of(KEY, List.of(7 * ONE / 2)));
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    task.execute(projected);
    assertEquals(2 * ONE, base.read(KEY));
    assertEquals(7 * ONE / 2, projected.readNativeFixed(KEY));
    assertEquals(0x9999, task.state());
  }

  @Test
  void observerFailureKeepsBaseWriteAndIndependentTagRead() {
    var base =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(KEY, 0L),
            c -> {
              throw new IllegalStateException("observer");
            });
    var projected = project(base, Map.of(KEY, List.of(3 * ONE)));
    assertThrows(IllegalStateException.class, () -> projected.write(KEY, ONE));
    assertEquals(ONE, base.read(KEY));
    assertEquals(3 * ONE, projected.readNativeFixed(KEY));
  }

  @Test
  void expressionTargetAiTagWritesOnlyBaseAndDoesNotReplaceStackTop() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(KEY, 0L), changes::add);
    var projected = project(base, Map.of(KEY, List.of(3 * ONE)));
    var target = new RogueResonanceScopedLookup46.Entity(new Object(), null, projected);
    var lookup =
        new RogueResonanceScopedLookup46(
            new RogueResonanceContext46(null, List.of(), null, null),
            new RogueResonanceScopedLookup46.Tail(null, null, null, null, null));
    var expression =
        RogueResonanceExpression46.compile(
            java.util.HexFormat.of().parseHex("000011"), new long[] {ONE}, new int[0]);
    assertEquals(
        1,
        new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", expression)
            .executeForEntities(lookup, List.of(target), 7, false));
    assertEquals(List.of(new RogueResonanceVariables46.Change(KEY, 0, ONE)), changes);
    assertEquals(ONE, base.read(KEY));
    assertEquals(3 * ONE, lookup.readForEntity(target, 7, KEY));
  }

  @Test
  void projectedBoundsSnapshotAndContainsRemainThoseOfBaseCells() {
    var base =
        new RogueResonanceVariables46(
            Map.of(KEY, new RogueResonanceVariables46.Value(0, -ONE, ONE)), null);
    var projected = project(base, Map.of(KEY, List.of(3 * ONE), 77, List.of(ONE)));
    assertEquals(new RogueResonanceVariables46.Bounds(-ONE, ONE), projected.bounds(KEY));
    assertTrue(projected.contains(KEY));
    assertFalse(projected.contains(77));
    assertThrows(IllegalArgumentException.class, () -> projected.bounds(77));
    assertTrue(projected.write(KEY, 4 * ONE));
    assertEquals(Map.of(KEY, ONE), projected.snapshot());
    assertEquals(3 * ONE, projected.readNativeFixed(KEY));
  }

  @Test
  void observerCanReadProjectionDuringBaseWriteWithoutAnotherMonitor() {
    var reference = new java.util.concurrent.atomic.AtomicReference<RogueResonanceVariables46>();
    var observed = new ArrayList<Long>();
    var base =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(KEY, 0L),
            c -> {
              var view = reference.get();
              observed.add(view.read(KEY));
              observed.add(view.readNativeFixed(KEY));
              assertEquals(Map.of(KEY, c.current()), view.snapshot());
              assertEquals(baseBounds(), view.bounds(KEY));
            });
    reference.set(project(base, Map.of(KEY, List.of(3 * ONE))));
    assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(2), () -> reference.get().write(KEY, ONE));
    assertEquals(List.of(ONE, 3 * ONE), observed);
  }

  private static RogueResonanceVariables46.Bounds baseBounds() {
    return new RogueResonanceVariables46.Bounds(-6710886399932891135L, 6710886399932891137L);
  }
}
