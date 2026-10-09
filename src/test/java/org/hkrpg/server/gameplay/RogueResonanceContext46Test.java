package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RogueResonanceContext46Test {
  private static final long ONE = 1L << 33;

  private static RogueResonanceVariables46 cells(int key, long raw) {
    return RogueResonanceVariables46.fromSourceDefaults(Map.of(key, raw), null);
  }

  @Test
  void callbackTemporaryZeroShadowsEveryLaterSource() {
    var callback = cells(42, 0);
    var context =
        new RogueResonanceContext46(
            callback, List.of(cells(42, ONE)), cells(42, 2 * ONE), cells(42, 3 * ONE));
    Long actual;
    try {
      actual = context.read(42);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(0), actual);
    assertSame(callback, context.select(0, 42));
  }

  @Test
  void missingKeysWalkExtraFramesTopFirstThenTemplateThenModifier() {
    var top = cells(42, ONE);
    var parent = cells(43, 2 * ONE);
    var template = cells(44, 3 * ONE);
    var modifier = cells(45, 4 * ONE);
    var context =
        new RogueResonanceContext46(cells(99, 0), List.of(top, parent), template, modifier);
    Map<Integer, Long> actual;
    try {
      actual =
          Map.of(
              42,
              context.read(42),
              43,
              context.read(43),
              44,
              context.read(44),
              45,
              context.read(45));
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Map.of(42, ONE, 43, 2 * ONE, 44, 3 * ONE, 45, 4 * ONE), actual);
    assertSame(parent, context.select(0, 43));
  }

  @Test
  void explicitTemplateScopeBypassesCallbackExtraAndModifier() {
    var template = cells(42, 3 * ONE);
    var context =
        new RogueResonanceContext46(
            cells(42, ONE), List.of(cells(42, 2 * ONE)), template, cells(42, 4 * ONE));
    Long actual;
    try {
      actual = context.select(1, 42).read(42);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(3 * ONE), actual);
    assertSame(template, context.select(1, 42));
  }

  @Test
  void explicitModifierScopeBypassesAllOtherCandidates() {
    var modifier = cells(42, 4 * ONE);
    var context =
        new RogueResonanceContext46(
            cells(42, ONE), List.of(cells(42, 2 * ONE)), cells(42, 3 * ONE), modifier);
    Long actual;
    try {
      actual = context.select(2, 42).read(42);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(4 * ONE), actual);
    assertSame(modifier, context.select(2, 42));
  }

  @Test
  void callbackScopeNineDoesNotFallBackToTemplate() {
    var callback = cells(42, ONE);
    var context = new RogueResonanceContext46(callback, List.of(), cells(42, 2 * ONE), null);
    Long actual;
    try {
      actual = context.select(9, 42).read(42);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(ONE), actual);
    assertThrows(IllegalArgumentException.class, () -> context.select(9, 43));
  }

  @Test
  void expressionReadsEachNumericReferenceInOrderAndNeverResolvesFunctionKeys() {
    var program =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("01000101010205100111"),
            new long[0],
            new int[] {1776456860, -978103959, 1555818884});
    var context =
        new RogueResonanceContext46(
            null, List.of(cells(-978103959, 10 * ONE)), cells(1555818884, 2 * ONE), null);
    Long actual;
    try {
      actual = program.evaluateWithResolver(context::read);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(5 * ONE), actual);
  }

  @Test
  void explicitMissingKeysNeverBorrowFromAnotherScopeAndUnknownRemainderStaysClosed() {
    var context =
        new RogueResonanceContext46(
            cells(42, ONE), List.of(cells(43, ONE)), cells(44, ONE), cells(45, ONE));
    for (int scope : new int[] {1, 2, 9})
      assertThrows(IllegalArgumentException.class, () -> context.select(scope, 43));
    for (int scope : new int[] {-1, 3, 4, 5, 6, 7, 8, 10, Integer.MAX_VALUE})
      assertThrows(IllegalArgumentException.class, () -> context.select(scope, 42));
    assertThrows(IllegalArgumentException.class, () -> context.read(46));
    assertFalse(context.select(0, 42).contains(46));
  }

  @Test
  void nullFrameLookupIsSkippedAndInputStackIsDetachedButCellsRemainLive() {
    var frame = cells(42, ONE);
    var frames = new ArrayList<RogueResonanceVariables46>();
    frames.add(null);
    frames.add(frame);
    var context = new RogueResonanceContext46(null, frames, null, null);
    frames.clear();
    assertSame(frame, context.select(0, 42));
    frame.write(42, 2 * ONE);
    assertEquals(2 * ONE, context.read(42));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceContext46(null, null, null, null));
    for (int i = 0; i < 256; i++) frames.add(frame);
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueResonanceContext46(null, frames, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueResonanceContext46(null, List.of(), null, null).read(42));
  }

  @Test
  void repeatedNumericReferencesAreReadLazilyInBytecodeOrder() {
    var program =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010001000211"), new long[0], new int[] {42});
    var trace = new ArrayList<Integer>();
    var reads = new AtomicInteger();
    assertEquals(
        3 * ONE,
        program.evaluateWithResolver(
            key -> {
              trace.add(key);
              return reads.incrementAndGet() * ONE;
            }));
    assertEquals(List.of(42, 42), trace);
    var constant =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("000011"), new long[] {ONE}, new int[] {43});
    assertEquals(
        ONE,
        constant.evaluateWithResolver(
            key -> {
              throw new AssertionError("Unused key must not be read");
            }));
  }

  @Test
  void malformedResolverValuesAndMissingInputsFailBeforeLaterReads() {
    var program =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010001010211"), new long[0], new int[] {42, 43});
    var reads = new AtomicInteger();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            program.evaluateWithResolver(
                key -> {
                  reads.incrementAndGet();
                  return 1;
                }));
    assertEquals(1, reads.get());
    assertThrows(IllegalArgumentException.class, () -> program.evaluateWithResolver(null));
    assertThrows(IllegalArgumentException.class, () -> program.evaluate(Map.of(42, ONE)));
    var marker = new IllegalStateException("provider failure");
    assertSame(
        marker,
        assertThrows(
            IllegalStateException.class,
            () ->
                program.evaluateWithResolver(
                    key -> {
                      throw marker;
                    })));
  }

  @Test
  void sourcePropertyExpressionAndRoundTasksUseExplicitPrefixBindings() {
    var locals =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(-978103959, 0L, 1555818884, 0L, -38167523, 0L, -1803181471, 0L), null);
    var context =
        new RogueResonanceContext46(null, List.of(cells(909827949, ONE / 64)), locals, null);
    var property = new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12);
    assertTrue(property.executeInContext(context, 0, List.of(Map.of(12, 10 * ONE))));
    var multiply =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010000000411"), new long[] {100 * ONE}, new int[] {909827949});
    var expression = new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", multiply);
    assertEquals(1, expression.executeInContext(context, List.of(locals), false));
    var round = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    round.executeInContext(context, 1);
    var floor =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("01000101010205100111"),
            new long[0],
            new int[] {1776456860, -978103959, 1555818884});
    assertEquals(5 * ONE, floor.evaluateWithResolver(context::read));
    assertEquals(0x9999, round.state());
    assertEquals(0x9999, property.state());
    assertEquals(0x9999, expression.state());
  }

  @Test
  void contextWritesUseSelectedCellObserverAndDoNotCreateOrModifyLowerPriorityCells() {
    var key = RogueResonanceVariables46.nameKey("_CurrentSP");
    var notifications = new ArrayList<RogueResonanceVariables46.Change>();
    var first = RogueResonanceVariables46.fromSourceDefaults(Map.of(key, 0L), notifications::add);
    var later = cells(key, ONE);
    var context = new RogueResonanceContext46(first, List.of(), later, null);
    var property = new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12);
    assertTrue(property.executeInContext(context, 0, List.of(Map.of(12, 2 * ONE))));
    assertEquals(2 * ONE, first.read(key));
    assertEquals(ONE, later.read(key));
    assertEquals(List.of(new RogueResonanceVariables46.Change(key, 0, 2 * ONE)), notifications);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RogueResonanceWrite46.PropertyTask(2855, "absent", 12)
                .executeInContext(context, 0, List.of(Map.of(12, ONE))));
    assertEquals(1, first.snapshot().size());
  }

  @Test
  void taskLifecycleOrderSurvivesUnmatchedContextsAndCharmSkipsResolution() {
    var context = new RogueResonanceContext46(null, List.of(), null, null);
    var property = new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12);
    assertThrows(
        IllegalArgumentException.class,
        () -> property.executeInContext(context, 0, List.of(Map.of(12, ONE))));
    assertEquals(0x9999, property.state());
    var round = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    assertThrows(IllegalArgumentException.class, () -> round.executeInContext(context, 0));
    assertEquals(0x7777, round.state());
    var value =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010011"), new long[0], new int[] {42});
    var expression = new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", value);
    assertThrows(
        IllegalArgumentException.class,
        () -> expression.executeInContext(context, List.of(), false));
    assertEquals(0x9999, expression.state());
    assertEquals(0, expression.executeInContext(null, null, true));
  }

  @Test
  void expressionContextValueIsEvaluatedOnceBeforeAllSelectedWrites() {
    var source = cells(42, ONE);
    var trace = new ArrayList<Long>();
    int key = RogueResonanceVariables46.nameKey("AttackTime");
    var first =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(key, 0L),
            change -> {
              trace.add(change.current());
              source.write(42, 9 * ONE);
            });
    var second =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(key, 0L), change -> trace.add(change.current()));
    var context = new RogueResonanceContext46(null, List.of(source), null, null);
    var value =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010011"), new long[0], new int[] {42});
    assertEquals(
        2,
        new RogueResonanceWrite46.ExpressionTask(2791, "AttackTime", value)
            .executeInContext(context, List.of(first, second), false));
    assertEquals(List.of(ONE, ONE), trace);
    assertEquals(9 * ONE, context.read(42));
  }
}
