package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RogueResonanceAliasContext46Test {
  private static final long ONE = 1L << 33;

  private static RogueResonanceVariables46 lookup(
      RogueResonanceTaskCell46.AliasEvaluator fallback) {
    return RogueResonanceVariables46.withProjectedCells(
        RogueResonanceVariables46.fromSourceDefaults(Map.of(2, ONE), null),
        Map.of(1, new RogueResonanceTaskCell46(new Object(), null, null, null)),
        fallback);
  }

  private static RogueResonanceContext46 context(RogueResonanceVariables46 lookup) {
    return new RogueResonanceContext46(null, List.of(), lookup, null);
  }

  @Test
  void selectedLookupUsesCurrentContextInsteadOfItsDefaultCallback() {
    var lookup = lookup((task, key) -> 5 * ONE);
    var binding = new RogueResonanceAliasContext46(context(lookup), (ctx, task, key) -> 19 * ONE);
    assertEquals(19 * ONE, binding.read(1));
    assertEquals(5 * ONE, lookup.read(1));
  }

  @Test
  void contextCallbackFailureRestoresPreviousEvaluator() {
    var lookup = lookup((task, key) -> 5 * ONE);
    var error = new IllegalStateException("alias task failed");
    var binding =
        new RogueResonanceAliasContext46(
            context(lookup),
            (ctx, task, key) -> {
              throw error;
            });
    assertSame(error, assertThrows(IllegalStateException.class, () -> binding.read(1)));
    assertEquals(5 * ONE, lookup.read(1));
  }

  @Test
  void contextWithoutAliasEvaluatorReturnsCacheInsteadOfBorrowingLookupCallback() {
    var lookup = lookup((task, key) -> 5 * ONE);
    assertEquals(0, context(lookup).read(1));
    assertEquals(5 * ONE, lookup.read(1));
    assertEquals(5 * ONE, context(lookup).read(1));
  }

  @Test
  void callbackReceivesExactTaskIdentityOutputKeyAndOwningContext() {
    var task = new Object();
    var values =
        RogueResonanceVariables46.withProjectedCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(2, 7 * ONE), null),
            Map.of(1, new RogueResonanceTaskCell46(task, null, null, null)),
            null);
    var owner = new AtomicReference<RogueResonanceLookup46>();
    var binding =
        new RogueResonanceAliasContext46(
            context(values),
            (ctx, config, key) -> {
              assertSame(owner.get(), ctx);
              assertSame(task, config);
              assertEquals(RogueResonanceTaskCell46.RESULT_KEY, key);
              return ctx.read(2);
            });
    owner.set(binding);
    assertEquals(7 * ONE, binding.read(1));
    assertEquals(7 * ONE, values.read(1));
  }

  @Test
  void explicitScopeBindsTheLookupSelectedInThatScope() {
    var callback = lookup(null);
    var template = lookup(null);
    var modifier = lookup(null);
    var ctx = new RogueResonanceContext46(callback, List.of(), template, modifier);
    var binding = new RogueResonanceAliasContext46(ctx, (owner, task, key) -> 9 * ONE);
    assertEquals(9 * ONE, binding.readInScope(9, 1));
    assertEquals(0, template.read(1));
    assertEquals(9 * ONE, binding.readInScope(1, 1));
    assertEquals(0, modifier.read(1));
    assertEquals(9 * ONE, binding.readInScope(2, 1));
  }

  @Test
  void nestedContextReadRestoresOuterThenDefaultBinding() {
    var outerTask = new Object();
    var innerTask = new Object();
    var values =
        RogueResonanceVariables46.withProjectedCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null),
            Map.of(
                1,
                new RogueResonanceTaskCell46(outerTask, null, null, null),
                3,
                new RogueResonanceTaskCell46(innerTask, null, null, null)),
            (task, key) -> 5 * ONE);
    var trace = new ArrayList<String>();
    var inner =
        new RogueResonanceAliasContext46(
            context(values),
            (ctx, task, key) -> {
              trace.add("inner");
              return 2 * ONE;
            });
    var outer =
        new RogueResonanceAliasContext46(
            context(values),
            (ctx, task, key) -> {
              if (task == innerTask) {
                trace.add("outer restored");
                return 3 * ONE;
              }
              trace.add("outer");
              return inner.read(3) + values.read(3);
            });
    assertEquals(5 * ONE, outer.read(1));
    assertEquals(List.of("outer", "inner", "outer restored"), trace);
    assertEquals(5 * ONE, values.read(3));
  }

  @Test
  void nestedFailureRestoresOuterBindingWhileOuterCallbackContinues() {
    var task = new Object();
    var values =
        RogueResonanceVariables46.withProjectedCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null),
            Map.of(
                1,
                new RogueResonanceTaskCell46(task, null, null, null),
                3,
                new RogueResonanceTaskCell46(new Object(), null, null, null)),
            (config, key) -> 5 * ONE);
    var inner =
        new RogueResonanceAliasContext46(
            context(values),
            (ctx, config, key) -> {
              throw new IllegalStateException("inner failed");
            });
    var outer =
        new RogueResonanceAliasContext46(
            context(values),
            (ctx, config, key) -> {
              if (config != task) return 3 * ONE;
              assertThrows(IllegalStateException.class, () -> inner.read(3));
              return values.read(3);
            });
    assertEquals(3 * ONE, outer.read(1));
    assertEquals(5 * ONE, values.read(3));
  }

  @Test
  void getterFailureAfterCallbackRestoresDefaultBindingAndDoesNotCacheResult() {
    var reads = new AtomicInteger();
    var values =
        lookup(
            (task, key) -> {
              reads.incrementAndGet();
              return 5 * ONE;
            });
    var binding = new RogueResonanceAliasContext46(context(values), (ctx, task, key) -> 1L);
    assertThrows(IllegalArgumentException.class, () -> binding.read(1));
    assertEquals(0, context(values).read(1));
    assertEquals(0, reads.get());
    assertEquals(5 * ONE, values.read(1));
    assertEquals(1, reads.get());
  }

  @Test
  void cyclicCallbackFailureCleansUpBothBindingAndDynamicReadGuard() {
    var values = lookup((task, key) -> 5 * ONE);
    var binding =
        new RogueResonanceAliasContext46(context(values), (ctx, task, key) -> ctx.read(1));
    assertThrows(IllegalArgumentException.class, () -> binding.read(1));
    assertEquals(5 * ONE, values.read(1));
    assertEquals(5 * ONE, context(values).read(1));
  }

  @Test
  void aiTagFallbackBindsUnderlyingLookupAndRetainsTagReads() {
    var values = lookup((task, key) -> 5 * ONE);
    var tagged = RogueResonanceVariables46.withAiTagProjection(values, Map.of(1, List.of(3 * ONE)));
    var binding = new RogueResonanceAliasContext46(context(tagged), (ctx, task, key) -> 9 * ONE);
    assertEquals(9 * ONE, binding.read(1));
    assertEquals(3 * ONE, binding.readInScope(1, 1));
    assertEquals(5 * ONE, values.read(1));
    assertEquals(3 * ONE, tagged.readNativeFixed(1));
  }

  @Test
  void emptyAiTagStackUsesContextBindingAndThenRestoresDefault() {
    var values = lookup((task, key) -> 5 * ONE);
    var tagged = RogueResonanceVariables46.withAiTagProjection(values, Map.of(1, List.of()));
    var binding = new RogueResonanceAliasContext46(context(tagged), (ctx, task, key) -> 9 * ONE);
    assertEquals(9 * ONE, binding.readInScope(1, 1));
    assertEquals(5 * ONE, tagged.readNativeFixed(1));
  }

  @Test
  void missingSelectionDoesNotInvokeCallbackOrChangeExistingBinding() {
    var values = lookup((task, key) -> 5 * ONE);
    var calls = new AtomicInteger();
    var binding =
        new RogueResonanceAliasContext46(
            context(values),
            (ctx, task, key) -> {
              calls.incrementAndGet();
              return 9 * ONE;
            });
    assertThrows(IllegalArgumentException.class, () -> binding.read(99));
    assertThrows(IllegalArgumentException.class, () -> binding.readInScope(10, 1));
    assertEquals(0, calls.get());
    assertEquals(5 * ONE, values.read(1));
  }

  @Test
  void fixedValuesAndOrdinaryDynamicCellsDoNotExecuteAliasTasks() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(2, 7 * ONE), null);
    var values =
        RogueResonanceVariables46.withDynamicCells(
            base,
            Map.of(
                1,
                new RogueResonanceDynamicCell46(
                    RogueResonanceDynamicCell46.Scalar.fixed(3 * ONE), null, null)));
    var binding =
        new RogueResonanceAliasContext46(
            context(values),
            (ctx, task, key) -> {
              fail("ordinary value invoked alias callback");
              return 0;
            });
    assertEquals(3 * ONE, binding.read(1));
    assertEquals(7 * ONE, binding.read(2));
  }

  @Test
  void entityProjectionUsesBindingWithoutChangingEntityLookupSelection() {
    var values = lookup((task, key) -> 5 * ONE);
    var target = new RogueResonanceScopedLookup46.Entity(new Object(), values);
    var scoped =
        new RogueResonanceScopedLookup46(
            new RogueResonanceContext46(null, List.of(), null, null),
            new RogueResonanceScopedLookup46.Tail(null, null, null, null, null));
    RogueResonanceLookup46 selected = (scope, key) -> scoped.selectForEntity(target, scope, key);
    var binding = new RogueResonanceAliasContext46(selected, (ctx, task, key) -> 9 * ONE);
    assertEquals(9 * ONE, binding.readInScope(6, 1));
    assertEquals(9 * ONE, scoped.readForEntity(target, 6, 1));
    assertEquals(5 * ONE, values.read(1));
    assertEquals(0, binding.readInScope(6, 99));
  }

  @Test
  void writeAndObserverReentryInsideCallbackRetainBinding() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(2, ONE), changes::add);
    var values =
        RogueResonanceVariables46.withProjectedCells(
            base,
            Map.of(1, new RogueResonanceTaskCell46(new Object(), null, null, null)),
            (task, key) -> 5 * ONE);
    var binding =
        new RogueResonanceAliasContext46(
            context(values),
            (ctx, task, key) -> {
              assertTrue(values.write(2, 3 * ONE));
              return ctx.read(2);
            });
    assertEquals(3 * ONE, binding.read(1));
    assertEquals(List.of(new RogueResonanceVariables46.Change(2, ONE, 3 * ONE)), changes);
    assertEquals(5 * ONE, values.read(1));
  }

  @Test
  void callbackErrorAlsoRestoresBindingForErrors() {
    var values = lookup((task, key) -> 5 * ONE);
    var error = new AssertionError("task assertion");
    var binding =
        new RogueResonanceAliasContext46(
            context(values),
            (ctx, task, key) -> {
              throw error;
            });
    assertSame(error, assertThrows(AssertionError.class, () -> binding.read(1)));
    assertEquals(5 * ONE, values.read(1));
  }

  @Test
  void twoConcurrentContextsAndDirectReadCannotBorrowTemporaryEvaluator() throws Exception {
    var values = lookup((task, key) -> 5 * ONE);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var secondStarted = new CountDownLatch(1);
    var directStarted = new CountDownLatch(1);
    var secondEntered = new CountDownLatch(1);
    var first =
        new RogueResonanceAliasContext46(
            context(values),
            (ctx, task, key) -> {
              entered.countDown();
              try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              return 9 * ONE;
            });
    var second =
        new RogueResonanceAliasContext46(
            context(values),
            (ctx, task, key) -> {
              secondEntered.countDown();
              return 11 * ONE;
            });
    try (var pool = Executors.newFixedThreadPool(3)) {
      var a = pool.submit(() -> first.read(1));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var b =
            pool.submit(
                () -> {
                  secondStarted.countDown();
                  return second.read(1);
                });
        var direct =
            pool.submit(
                () -> {
                  directStarted.countDown();
                  return values.read(1);
                });
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
        assertTrue(directStarted.await(5, TimeUnit.SECONDS));
        assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
        assertFalse(direct.isDone());
        release.countDown();
        assertEquals(9 * ONE, a.get(5, TimeUnit.SECONDS));
        assertEquals(11 * ONE, b.get(5, TimeUnit.SECONDS));
        assertEquals(5 * ONE, direct.get(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    }
    assertEquals(5 * ONE, values.read(1));
  }

  @Test
  void invalidExplicitAliasContextIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueResonanceAliasContext46(null, (ctx, task, key) -> 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueResonanceAliasContext46(context(lookup(null)), null));
  }
}
