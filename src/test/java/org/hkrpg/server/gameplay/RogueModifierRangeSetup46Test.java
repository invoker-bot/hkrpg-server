package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueModifierRangeSetup46.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RogueModifierRangeSetup46Test {
  @Test
  void finiteChildBoundaryCreatesAll512Tasks() {
    var d = definition();
    var child = new Child();
    d.onEnter = new ChildFactory[512];
    Arrays.fill(d.onEnter, (ChildFactory) p -> child);
    assertEquals(512, sequence(d, null).children().size());
  }

  @Test
  void contextFailurePropagatesBeforeFactoriesAndAppend() {
    var d = definition();
    var marker = new IllegalStateException("context");
    d.onEnter =
        new ChildFactory[] {
          p -> {
            fail("Context must be read first");
            return null;
          }
        };
    assertSame(
        marker,
        assertThrows(
            IllegalStateException.class,
            () ->
                sequenceWithWorld(
                    d,
                    s -> {
                      throw marker;
                    })));
  }

  static class Child implements RogueTaskSequence46.Task {
    int state = RogueTaskSequence46.READY, resets, disposes;
    Consumer<Child> begin = c -> c.state = RogueTaskSequence46.SUCCESS;
    Consumer<Float> tick = delta -> {};

    public int state() {
      return state;
    }

    public void begin() {
      begin.accept(this);
    }

    public void tick(float delta) {
      tick.accept(delta);
    }

    public void reset() {
      resets++;
      state = RogueTaskSequence46.READY;
    }

    public void dispose() {
      disposes++;
    }
  }

  static RogueModifierDynamicRange46.State state() {
    return RogueModifierDynamicRange46Test.state();
  }

  static Definition definition() {
    return new Definition(RogueModifierDynamicRange46Test.entry().config);
  }

  static ContextSequence sequence(Definition d, Object context) {
    var entries = new ArrayList<RogueModifierDynamicRange46.Entry>();
    append(state(), new Definition[] {d}, entries, s -> context);
    return (ContextSequence) entries.getFirst().enterSequence;
  }

  @Test
  void nullDefinitionsRemainClosed() {
    assertThrows(IllegalArgumentException.class, () -> append(null, null, null, null));
  }

  @Test
  void emptyDefinitionsSkipMissingStateWorldAndTarget() {
    append(null, new Definition[0], null, null);
  }

  @Test
  void nonemptyDefinitionsRequireExplicitStateAndWorld() {
    var d = definition();
    assertThrows(
        IllegalArgumentException.class,
        () -> append(null, new Definition[] {d}, new ArrayList<>(), s -> null));
    assertThrows(
        IllegalArgumentException.class,
        () -> append(state(), new Definition[] {d}, new ArrayList<>(), null));
  }

  @Test
  void allNullCallbackArraysCreateNoSequenceAndReadNoContext() {
    var d = definition();
    var out = new ArrayList<RogueModifierDynamicRange46.Entry>();
    append(
        state(),
        new Definition[] {d},
        out,
        s -> {
          fail("No callback array requires no context");
          return null;
        });
    var e = out.getFirst();
    assertSame(d.range, e.config);
    assertNull(e.enterSequence);
    assertNull(e.exitSequence);
    assertNull(e.changeSequence);
    assertFalse(e.inRange);
    assertEquals(0, e.enterDepth);
    assertEquals(0, e.exitDepth);
    assertEquals(0, e.changeDepth);
  }

  @Test
  void threeEmptyArraysStillCreateThreeIndependentReadySequences() {
    var d = definition();
    d.onEnter = d.onExit = d.onChange = new ChildFactory[0];
    var contexts = new ArrayList<Object>();
    var out = new ArrayList<RogueModifierDynamicRange46.Entry>();
    append(
        state(),
        new Definition[] {d},
        out,
        s -> {
          var c = new Object();
          contexts.add(c);
          return c;
        });
    var e = out.getFirst();
    assertEquals(3, contexts.size());
    var a = (ContextSequence) e.enterSequence;
    var b = (ContextSequence) e.exitSequence;
    var c = (ContextSequence) e.changeSequence;
    assertNotSame(a, b);
    assertNotSame(b, c);
    assertNotSame(a, c);
    assertSame(contexts.get(0), a.context);
    assertSame(contexts.get(1), b.context);
    assertSame(contexts.get(2), c.context);
    for (var q : List.of(a, b, c)) {
      assertEquals(RogueTaskSequence46.READY, q.state());
      assertTrue(q.children().isEmpty());
      q.begin();
      assertEquals(RogueTaskSequence46.SUCCESS, q.state());
    }
  }

  @Test
  void nullableCapturedContextIsPreservedForFactory() {
    var d = definition();
    var child = new Child();
    d.onEnter =
        new ChildFactory[] {
          p -> {
            assertNull(p.context);
            assertEquals(RogueTaskSequence46.READY, p.state());
            return child;
          }
        };
    assertSame(child, sequence(d, null).children().getFirst());
  }

  @Test
  void callbacksAreConstructedInEnterExitChangeOrderBeforeAppend() {
    var d = definition();
    var order = new ArrayList<String>();
    var out = new ArrayList<RogueModifierDynamicRange46.Entry>();
    d.onEnter =
        new ChildFactory[] {
          p -> {
            assertTrue(out.isEmpty());
            order.add("enter");
            return new Child();
          }
        };
    d.onExit =
        new ChildFactory[] {
          p -> {
            assertTrue(out.isEmpty());
            order.add("exit");
            return new Child();
          }
        };
    d.onChange =
        new ChildFactory[] {
          p -> {
            assertTrue(out.isEmpty());
            order.add("change");
            return new Child();
          }
        };
    append(
        state(),
        new Definition[] {d},
        out,
        s -> {
          order.add("context");
          return null;
        });
    assertEquals(List.of("context", "enter", "context", "exit", "context", "change"), order);
    assertEquals(1, out.size());
  }

  @Test
  void callbackFieldsAreRereadAfterEarlierFactory() {
    var d = definition();
    var order = new ArrayList<String>();
    d.onEnter =
        new ChildFactory[] {
          p -> {
            d.onExit =
                new ChildFactory[] {
                  x -> {
                    order.add("new-exit");
                    d.onChange = new ChildFactory[0];
                    return new Child();
                  }
                };
            return new Child();
          }
        };
    d.onExit =
        new ChildFactory[] {
          p -> {
            fail("Old exit must not be captured early");
            return null;
          }
        };
    var out = new ArrayList<RogueModifierDynamicRange46.Entry>();
    append(state(), new Definition[] {d}, out, s -> null);
    assertEquals(List.of("new-exit"), order);
    assertNotNull(out.getFirst().changeSequence);
  }

  @Test
  void replacingEnterFieldDoesNotReplaceAlreadyCapturedFactoryArray() {
    var d = definition();
    var child = new Child();
    d.onEnter =
        new ChildFactory[] {
          p -> {
            d.onEnter =
                new ChildFactory[] {
                  x -> {
                    fail("Captured array must remain");
                    return null;
                  }
                };
            return child;
          },
          p -> child
        };
    assertEquals(List.of(child, child), sequence(d, null).children());
  }

  @Test
  void laterCapturedArrayElementsAreReadAfterEarlierFactory() {
    var d = definition();
    var a = new Child();
    var b = new Child();
    d.onEnter =
        new ChildFactory[] {
          p -> {
            d.onEnter[1] = x -> b;
            return a;
          },
          p -> {
            fail("Old array element must not run");
            return null;
          }
        };
    assertEquals(List.of(a, b), sequence(d, null).children());
  }

  @Test
  void contextIsCapturedOnceForEachSequenceBeforeChildren() {
    var d = definition();
    Object a = new Object(), b = new Object();
    Object[] current = {a};
    int[] reads = {0};
    d.onEnter =
        new ChildFactory[] {
          p -> {
            assertSame(a, p.context);
            current[0] = b;
            return new Child();
          },
          p -> {
            assertSame(a, p.context);
            return new Child();
          }
        };
    d.onExit =
        new ChildFactory[] {
          p -> {
            assertSame(b, p.context);
            return new Child();
          }
        };
    append(
        state(),
        new Definition[] {d},
        new ArrayList<>(),
        s -> {
          reads[0]++;
          return current[0];
        });
    assertEquals(2, reads[0]);
  }

  @Test
  void laterDefinitionsAreReadAfterEarlierConstruction() {
    var first = definition();
    var replacement = definition();
    Definition[] captured = {first, null};
    first.onEnter =
        new ChildFactory[] {
          p -> {
            captured[1] = replacement;
            return new Child();
          }
        };
    var out = new ArrayList<RogueModifierDynamicRange46.Entry>();
    append(state(), captured, out, s -> null);
    assertSame(replacement.range, out.get(1).config);
  }

  @Test
  void definitionValidationKeepsEarlierAppendedPrefix() {
    var first = definition();
    var out = new ArrayList<RogueModifierDynamicRange46.Entry>();
    assertThrows(
        IllegalArgumentException.class,
        () -> append(state(), new Definition[] {first, null}, out, s -> null));
    assertEquals(1, out.size());
    assertSame(first.range, out.getFirst().config);
  }

  @Test
  void missingCompiledRangeConfigurationIsClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            append(state(), new Definition[] {new Definition(null)}, new ArrayList<>(), s -> null));
  }

  @Test
  void nullChildFactoryKeepsCompletedDefinitionOnly() {
    var first = definition();
    var bad = definition();
    bad.onEnter = new ChildFactory[] {null};
    var out = new ArrayList<RogueModifierDynamicRange46.Entry>();
    assertThrows(
        IllegalArgumentException.class,
        () -> append(state(), new Definition[] {first, bad}, out, s -> null));
    assertEquals(1, out.size());
  }

  @Test
  void missingChildTaskFailsWithoutAppendingPartialEntry() {
    var d = definition();
    d.onChange = new ChildFactory[] {p -> null};
    var out = new ArrayList<RogueModifierDynamicRange46.Entry>();
    assertThrows(
        IllegalArgumentException.class,
        () -> append(state(), new Definition[] {d}, out, s -> null));
    assertTrue(out.isEmpty());
  }

  @Test
  void factoryFailurePreservesCapturedReadyParentAndPriorPrefix() {
    var first = definition();
    var d = definition();
    ContextSequence[] parent = {null};
    var marker = new IllegalStateException("factory");
    d.onExit =
        new ChildFactory[] {
          p -> {
            parent[0] = p;
            throw marker;
          }
        };
    var out = new ArrayList<RogueModifierDynamicRange46.Entry>();
    assertSame(
        marker,
        assertThrows(
            IllegalStateException.class,
            () -> append(state(), new Definition[] {first, d}, out, s -> null)));
    assertEquals(1, out.size());
    assertEquals(RogueTaskSequence46.READY, parent[0].state());
    assertThrows(IllegalArgumentException.class, () -> parent[0].children());
  }

  @Test
  void targetValidationOccursAfterAllFactories() {
    var d = definition();
    int[] calls = {0};
    ChildFactory f =
        p -> {
          calls[0]++;
          return new Child();
        };
    d.onEnter = d.onExit = d.onChange = new ChildFactory[] {f};
    assertThrows(
        IllegalArgumentException.class,
        () -> append(state(), new Definition[] {d}, null, s -> null));
    assertEquals(3, calls[0]);
  }

  @Test
  void callbackCanFreeTargetCapacityBeforeAppend() {
    var out =
        new ArrayList<RogueModifierDynamicRange46.Entry>(
            Collections.nCopies(255, RogueModifierDynamicRange46Test.entry()));
    var d = definition();
    d.onEnter =
        new ChildFactory[] {
          p -> {
            out.clear();
            return new Child();
          }
        };
    append(state(), new Definition[] {d}, out, s -> null);
    assertEquals(1, out.size());
  }

  @Test
  void fullTargetGuardDoesNotUndoFactories() {
    var out =
        new ArrayList<RogueModifierDynamicRange46.Entry>(
            Collections.nCopies(255, RogueModifierDynamicRange46Test.entry()));
    var d = definition();
    int[] calls = {0};
    d.onEnter =
        new ChildFactory[] {
          p -> {
            calls[0]++;
            return new Child();
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> append(state(), new Definition[] {d}, out, s -> null));
    assertEquals(1, calls[0]);
    assertEquals(255, out.size());
  }

  @Test
  void definitionLimitLeavesFirst255Entries() {
    Definition[] defs = new Definition[256];
    Arrays.fill(defs, definition());
    var out = new ArrayList<RogueModifierDynamicRange46.Entry>();
    assertThrows(IllegalArgumentException.class, () -> append(state(), defs, out, s -> null));
    assertEquals(255, out.size());
  }

  @Test
  void childFactoryLimitFollowsContextReadAndCallsNoFactory() {
    var d = definition();
    d.onEnter = new ChildFactory[513];
    int[] reads = {0};
    assertThrows(
        IllegalArgumentException.class,
        () ->
            sequenceWithWorld(
                d,
                s -> {
                  reads[0]++;
                  return null;
                }));
    assertEquals(1, reads[0]);
  }

  static ContextSequence sequenceWithWorld(Definition d, World w) {
    var out = new ArrayList<RogueModifierDynamicRange46.Entry>();
    append(state(), new Definition[] {d}, out, w);
    return (ContextSequence) out.getFirst().enterSequence;
  }

  @Test
  void createdSequenceRunsWaitingChildAndThenNextChild() {
    var d = definition();
    var a = new Child();
    var b = new Child();
    var deltas = new ArrayList<Float>();
    a.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    a.tick =
        delta -> {
          deltas.add(delta);
          if (delta > 0) a.state = RogueTaskSequence46.SUCCESS;
        };
    d.onEnter = new ChildFactory[] {p -> a, p -> b};
    var q = sequence(d, null);
    q.begin();
    assertEquals(RogueTaskSequence46.EXECUTING, q.state());
    assertEquals(RogueTaskSequence46.READY, b.state());
    q.tick(.25f);
    assertEquals(List.of(0f, .25f), deltas);
    assertEquals(RogueTaskSequence46.SUCCESS, q.state());
    assertEquals(RogueTaskSequence46.SUCCESS, b.state());
  }

  @Test
  void createdChildCanStopParentThroughExplicitStateBinding() {
    var d = definition();
    var later = new Child();
    later.begin = c -> fail("Parent failure must stop later child");
    d.onEnter =
        new ChildFactory[] {
          p -> {
            var c = new Child();
            c.begin =
                x -> {
                  p.state(RogueTaskSequence46.FAIL);
                  x.state = RogueTaskSequence46.EXECUTING;
                };
            return c;
          },
          p -> later
        };
    var q = sequence(d, null);
    q.begin();
    assertEquals(RogueTaskSequence46.FAIL, q.state());
    assertEquals(RogueTaskSequence46.READY, later.state());
  }

  @Test
  void constructionOperationsRemainClosedUntilChildrenAreBound() {
    var d = definition();
    d.onEnter =
        new ChildFactory[] {
          p -> {
            assertEquals(RogueTaskSequence46.READY, p.state());
            assertThrows(IllegalArgumentException.class, () -> p.state(RogueTaskSequence46.FAIL));
            assertThrows(IllegalArgumentException.class, p::begin);
            assertThrows(IllegalArgumentException.class, () -> p.tick(0f));
            assertThrows(IllegalArgumentException.class, () -> p.step(0f));
            assertThrows(IllegalArgumentException.class, p::reset);
            assertThrows(IllegalArgumentException.class, p::dispose);
            return new Child();
          }
        };
    assertEquals(1, sequence(d, null).children().size());
  }

  @Test
  void createdChildrenAreImmutableAndRetainIdentity() {
    var d = definition();
    var child = new Child();
    d.onEnter = new ChildFactory[] {p -> child};
    var q = sequence(d, null);
    assertSame(child, q.children().getFirst());
    assertThrows(UnsupportedOperationException.class, () -> q.children().clear());
  }

  @Test
  void resetAndIdempotentDisposeReachActualChildSequence() {
    var d = definition();
    var child = new Child();
    d.onEnter = new ChildFactory[] {p -> child};
    var q = sequence(d, null);
    q.begin();
    q.reset();
    assertEquals(1, child.resets);
    assertEquals(RogueTaskSequence46.READY, q.state());
    q.dispose();
    q.dispose();
    assertEquals(1, child.disposes);
    assertTrue(q.children().isEmpty());
    assertThrows(IllegalStateException.class, q::begin);
  }

  @Test
  void actualModifierSequenceCompletionResetsCreatedTasksAndFlushesCounter() {
    var d = definition();
    var child = new Child();
    d.onEnter = new ChildFactory[] {p -> child};
    var q = sequence(d, null);
    var range = state();
    var s = new RogueModifierSequences46.State(range.replacement);
    int[] ended = {0};
    var w =
        new RogueModifierSequences46.World() {
          public void sequenceEnded(RogueModifierSequences46.State current) {
            ended[0]++;
            RogueModifierSequences46.endAndFlush(current, this);
          }

          public Object taskContext(RogueModifierSequences46.State current) {
            fail("No deferred ability must skip context");
            return null;
          }

          public void replaceContextAbility(Object c, Object a) {
            fail("No deferred ability");
          }

          public void afterAbilityVirtual(RogueModifierSequences46.State current) {
            fail("No deferred ability");
          }
        };
    RogueModifierSequences46.start(s, q, w);
    assertEquals(1, ended[0]);
    assertEquals(0, s.tickingSequenceCount);
    assertEquals(0, s.replacement.executingSequenceCount);
    assertEquals(1, child.resets);
    assertEquals(RogueTaskSequence46.READY, q.state());
  }

  @Test
  void actualRangeChangeExecutesCreatedEnterSequence() {
    var d = definition();
    var child = new Child();
    d.onEnter = new ChildFactory[] {p -> child};
    var entries = new ArrayList<RogueModifierDynamicRange46.Entry>();
    var s = state();
    append(s, new Definition[] {d}, entries, x -> null);
    long key = RogueModifierRangeLifecycle46.scopedKey(7, 4);
    s.listenerGroups = new LinkedHashMap<>();
    s.listenerGroups.put(key, entries);
    var w =
        new RogueModifierDynamicRange46Test.World() {
          public void executeSequence(Object q) {
            ((ContextSequence) q).begin();
          }
        };
    RogueModifierDynamicRange46.changed(
        s, key, new RogueResonanceVariables46.Change(7, 0L, 2L << 33, false), w);
    assertTrue(entries.getFirst().inRange);
    assertEquals(RogueTaskSequence46.SUCCESS, child.state());
  }

  @Test
  void actualRetirementExecutesCreatedExitWithoutDepthOrRangeMutation() {
    var d = definition();
    var child = new Child();
    d.onExit = new ChildFactory[] {p -> child};
    var entries = new ArrayList<RogueModifierDynamicRange46.Entry>();
    var s = state();
    append(s, new Definition[] {d}, entries, x -> null);
    var e = entries.getFirst();
    e.inRange = true;
    e.exitDepth = 93;
    var groups = new RogueModifierRangeLifecycle46.Groups();
    groups.put(RogueModifierRangeLifecycle46.scopedKey(7, 4), entries);
    s.listenerGroups = groups;
    var life =
        new RogueModifierRangeLifecycle46.State(s, new RogueModifierDynamicRange46Test.World());
    var w =
        new RogueModifierRangeLifecycle46Test.World() {
          public void executeSequence(Object q) {
            ((ContextSequence) q).begin();
          }
        };
    RogueModifierRangeLifecycle46.retire(life, w);
    assertEquals(RogueTaskSequence46.SUCCESS, child.state());
    assertTrue(e.inRange);
    assertEquals(93, e.exitDepth);
    assertNull(s.listenerGroups);
  }
}
