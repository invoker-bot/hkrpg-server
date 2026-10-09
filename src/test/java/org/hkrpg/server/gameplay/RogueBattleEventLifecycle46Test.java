package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueBattleEventLifecycle46Test {
  private static RogueBattleEventTable46.Config event(boolean requiresStage) {
    return new RogueBattleEventTable46.Config(
        41007,
        3,
        3,
        0,
        0,
        "event",
        null,
        List.of(),
        List.of(),
        100L << 33,
        requiresStage,
        requiresStage,
        null,
        null,
        List.of(),
        null,
        0,
        0,
        1);
  }

  private static RogueResonanceLookup46 context() {
    return (scope, key) -> {
      throw new IllegalArgumentException("No implicit values");
    };
  }

  private static RogueBattleEventLifecycle46.Configuration config(
      RogueBattleEventLifecycle46.Sequence before, RogueBattleEventLifecycle46.Sequence after) {
    return new RogueBattleEventLifecycle46.Configuration(
        RogueResonanceDynamicCell46.Scalar.fixed(41007L << 33), List.of(), before, after);
  }

  @Test
  void doneStatePrecedesPreparationAndCreation() {
    var task = new RogueBattleEventLifecycle46.Task(config(null, null), context(), null);
    assertEquals(RogueBattleEventLifecycle46.Task.NEW, task.state());
    task.begin(
        Map.of(41007, event(false)),
        0,
        null,
        (id, prepared, parameters, before) -> {
          assertEquals(RogueBattleEventLifecycle46.Task.DONE, task.state());
          return new Object();
        });
  }

  @Test
  void missingRequiredWaveStageDoesNotCallCreation() {
    var calls = new AtomicInteger();
    var task = new RogueBattleEventLifecycle46.Task(config(null, null), context(), null);
    var result =
        task.begin(
            Map.of(41007, event(true)),
            1001,
            id -> null,
            (id, prepared, parameters, before) -> {
              calls.incrementAndGet();
              return new Object();
            });
    assertTrue(result.isEmpty());
    assertEquals(0, calls.get());
  }

  @Test
  void beforeAndAfterCallbacksUseSeparateEntityFramesAndRestoreTheOuterFrame() {
    var entity = new Object();
    var outer = new RogueBattleEventLifecycle46.Frame(new Object());
    var frames = new RogueBattleEventLifecycle46.Frames(List.of(outer));
    var order = new ArrayList<String>();
    var callbackFrames = new ArrayList<RogueBattleEventLifecycle46.Frame>();
    var task =
        new RogueBattleEventLifecycle46.Task(
            config(
                f -> {
                  assertSame(entity, f.current().eventEntity());
                  callbackFrames.add(f.current());
                  order.add("before");
                },
                f -> {
                  assertSame(entity, f.current().eventEntity());
                  callbackFrames.add(f.current());
                  order.add("after");
                }),
            context(),
            frames);
    var result =
        task.begin(
            Map.of(41007, event(false)),
            0,
            null,
            (id, prepared, parameters, before) -> {
              order.add("create");
              before.accept(entity);
              assertSame(outer, frames.current());
              order.add("setup");
              return entity;
            });
    assertSame(entity, result.orElseThrow());
    assertEquals(List.of("create", "before", "setup", "after"), order);
    assertEquals(2, callbackFrames.size());
    assertNotSame(callbackFrames.get(0), callbackFrames.get(1));
    assertSame(outer, frames.current());
    assertEquals(1, frames.depth());
  }

  private static JsonObject fixture(String name) throws Exception {
    try (var stream =
        RogueBattleEventLifecycle46Test.class.getResourceAsStream("/fixtures/" + name)) {
      assertNotNull(stream);
      return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {41001, 41002, 41003, 41004, 41005, 41006, 41007, 41008, 41009})
  void nineOriginalEventsPassExactQueriesParametersAndCurrentWaveToExplicitCreation(int id)
      throws Exception {
    var records = fixture("rogue-battle-event-tables-4.6.json").getAsJsonArray("records");
    var events =
        RogueBattleEventTable46.readConfig(
            HexFormat.of().parseHex(records.get(1).getAsJsonObject().get("rawHex").getAsString()));
    var data =
        RogueBattleEventTable46.readData(
            HexFormat.of().parseHex(records.get(0).getAsJsonObject().get("rawHex").getAsString()));
    var raw =
        fixture("rogue-maze-buff-table-4.6.json")
            .getAsJsonArray("records")
            .get(0)
            .getAsJsonObject()
            .get("rawHex")
            .getAsString();
    var buffs = RogueMazeBuffTable46.read(HexFormat.of().parseHex(raw));
    var definition = RogueResonanceClassicEvents46.find(id).orElseThrow();
    var template = definition.newTemplate();
    int base = Integer.parseInt(definition.sourceAbility().substring("StageAbility_".length()));
    for (var p :
        RogueResonanceStageParams46.create(
            buffs.get(new RogueMazeBuffTable46.Key(base, 1)).parameters().stream()
                .mapToLong(Long::longValue)
                .toArray())) assertTrue(template.write(p.key(), p.raw()));
    var lookup = new RogueResonanceContext46(null, List.of(), template, null);
    var queries =
        RogueResonanceMazeQueries46.execute(
            RogueResonanceMazeQueries46.classicQueries(id),
            0,
            (buff, level) -> buffs.get(new RogueMazeBuffTable46.Key(buff, level)),
            lookup);
    assertTrue(queries.failures().isEmpty());
    var stage = new RogueBattleEventTable46.WaveStage(1001, new Object());
    var frames = new RogueBattleEventLifecycle46.Frames(List.of());
    var task =
        new RogueBattleEventLifecycle46.Task(
            new RogueBattleEventLifecycle46.Configuration(
                RogueResonanceDynamicCell46.Scalar.fixed((long) id << 33),
                definition.parameterBindings(),
                null,
                null),
            lookup,
            frames);
    var created = new Object();
    var result =
        task.begin(
            events,
            1001,
            key -> {
              assertEquals(1001, key);
              return stage;
            },
            (key, prepared, parameters, before) -> {
              assertEquals(id, key);
              assertSame(events.get(id), prepared.event());
              assertSame(stage, prepared.waveStage());
              assertEquals(definition.evaluate(lookup), parameters);
              assertEquals(definition.parameterBindings().size(), parameters.size());
              assertEquals(List.of(id * 100 + 1, id * 100 + 2), data.get(key).skills());
              assertThrows(UnsupportedOperationException.class, () -> parameters.clear());
              before.accept(created);
              assertEquals(0, frames.depth());
              return created;
            });
    assertSame(created, result.orElseThrow());
    assertEquals(RogueBattleEventLifecycle46.Task.DONE, task.state());
    assertNull(frames.current());
  }

  @Test
  void stateIsDoneEvenWhenEventIdEvaluationThrowsBeforePreparation() {
    var holder = new AtomicReference<RogueBattleEventLifecycle46.Task>();
    var problem = new IllegalStateException("dynamic source unavailable");
    var scalar =
        RogueResonanceDynamicCell46.Scalar.fromPostfix(
            0, new byte[] {1, 0, 17}, new long[0], new int[] {1});
    RogueResonanceLookup46 lookup =
        (scope, key) -> {
          assertEquals(RogueBattleEventLifecycle46.Task.DONE, holder.get().state());
          throw problem;
        };
    var task =
        new RogueBattleEventLifecycle46.Task(
            new RogueBattleEventLifecycle46.Configuration(scalar, List.of(), null, null),
            lookup,
            null);
    holder.set(task);
    assertSame(
        problem,
        assertThrows(IllegalStateException.class, () -> task.begin(Map.of(), 0, null, null)));
    assertEquals(RogueBattleEventLifecycle46.Task.DONE, task.state());
  }

  @Test
  void missingEventStopsBeforeParameterReadsCallbacksAndCreation() {
    var scalar =
        RogueResonanceDynamicCell46.Scalar.fromPostfix(
            0, new byte[] {1, 0, 17}, new long[0], new int[] {1});
    var configuration =
        new RogueBattleEventLifecycle46.Configuration(
            RogueResonanceDynamicCell46.Scalar.fixed(41007L << 33),
            List.of(new RogueResonanceEventParameters46.Binding("x", scalar)),
            frames -> fail("No callback for missing event"),
            frames -> fail("No callback for missing event"));
    var task =
        new RogueBattleEventLifecycle46.Task(
            configuration, context(), new RogueBattleEventLifecycle46.Frames(List.of()));
    assertTrue(
        task.begin(
                Map.of(),
                0,
                null,
                (id, p, values, before) -> {
                  fail("No creation for missing event");
                  return null;
                })
            .isEmpty());
    assertEquals(RogueBattleEventLifecycle46.Task.DONE, task.state());
  }

  @Test
  void parameterEvaluationFailureStopsBeforeCreationAndPreservesDoneState() {
    var scalar =
        RogueResonanceDynamicCell46.Scalar.fromPostfix(
            0, new byte[] {1, 0, 17}, new long[0], new int[] {1});
    var configuration =
        new RogueBattleEventLifecycle46.Configuration(
            RogueResonanceDynamicCell46.Scalar.fixed(41007L << 33),
            List.of(new RogueResonanceEventParameters46.Binding("x", scalar)),
            null,
            null);
    var task = new RogueBattleEventLifecycle46.Task(configuration, context(), null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            task.begin(
                Map.of(41007, event(false)),
                0,
                null,
                (id, p, values, before) -> {
                  fail("No creation after source failure");
                  return null;
                }));
    assertEquals(RogueBattleEventLifecycle46.Task.DONE, task.state());
  }

  @Test
  void evaluatedParameterSnapshotDoesNotChangeWhenBeforeCallbackMutatesSource() {
    int key = RogueResonanceVariables46.nameKey("x");
    var cells = RogueResonanceVariables46.fromSourceDefaults(Map.of(key, 1L << 33), null);
    var lookup = new RogueResonanceContext46(null, List.of(), cells, null);
    var scalar =
        RogueResonanceDynamicCell46.Scalar.fromPostfix(
            0, new byte[] {1, 0, 17}, new long[0], new int[] {key});
    var configuration =
        new RogueBattleEventLifecycle46.Configuration(
            RogueResonanceDynamicCell46.Scalar.fixed(41007L << 33),
            List.of(new RogueResonanceEventParameters46.Binding("x", scalar)),
            frames -> cells.write(key, 2L << 33),
            null);
    var task =
        new RogueBattleEventLifecycle46.Task(
            configuration, lookup, new RogueBattleEventLifecycle46.Frames(List.of()));
    task.begin(
        Map.of(41007, event(false)),
        0,
        null,
        (id, p, values, before) -> {
          assertEquals(1L << 33, values.get(key));
          var entity = new Object();
          before.accept(entity);
          assertEquals(2L << 33, cells.read(key));
          assertEquals(1L << 33, values.get(key));
          return entity;
        });
  }

  @Test
  void nullReturnedEntityDoesNotInvokeAfterCallback() {
    var count = new AtomicInteger();
    var task =
        new RogueBattleEventLifecycle46.Task(
            config(null, frames -> count.incrementAndGet()),
            context(),
            new RogueBattleEventLifecycle46.Frames(List.of()));
    assertTrue(
        task.begin(Map.of(41007, event(false)), 0, null, (id, p, values, before) -> null)
            .isEmpty());
    assertEquals(0, count.get());
  }

  @Test
  void nullBeforeEntityAndAbsentRegisterSkipCallbacks() {
    var count = new AtomicInteger();
    var sequence = (RogueBattleEventLifecycle46.Sequence) frames -> count.incrementAndGet();
    var task = new RogueBattleEventLifecycle46.Task(config(sequence, sequence), context(), null);
    task.begin(
        Map.of(41007, event(false)),
        0,
        null,
        (id, p, values, before) -> {
          before.accept(null);
          before.accept(new Object());
          return new Object();
        });
    assertEquals(0, count.get());
    var frames = new RogueBattleEventLifecycle46.Frames(List.of());
    task = new RogueBattleEventLifecycle46.Task(config(sequence, null), context(), frames);
    task.begin(
        Map.of(41007, event(false)),
        0,
        null,
        (id, p, values, before) -> {
          before.accept(null);
          return new Object();
        });
    assertEquals(0, count.get());
    assertNull(frames.current());
  }

  @Test
  void beforeExceptionPropagatesLeavesFrameAndStopsSetupAndAfterCallback() {
    var entity = new Object();
    var frames = new RogueBattleEventLifecycle46.Frames(List.of());
    var problem = new IllegalStateException("before callback failed");
    var order = new ArrayList<String>();
    var task =
        new RogueBattleEventLifecycle46.Task(
            config(
                f -> {
                  order.add("before");
                  throw problem;
                },
                f -> order.add("after")),
            context(),
            frames);
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class,
            () ->
                task.begin(
                    Map.of(41007, event(false)),
                    0,
                    null,
                    (id, p, values, before) -> {
                      before.accept(entity);
                      order.add("setup");
                      return entity;
                    })));
    assertEquals(List.of("before"), order);
    assertEquals(1, frames.depth());
    assertSame(entity, frames.current().eventEntity());
    assertEquals(RogueBattleEventLifecycle46.Task.DONE, task.state());
  }

  @Test
  void afterExceptionPropagatesWithoutUndoingCreationAndLeavesFrame() {
    var frames = new RogueBattleEventLifecycle46.Frames(List.of());
    var entity = new Object();
    var created = new AtomicInteger();
    var problem = new IllegalStateException("after callback failed");
    var task =
        new RogueBattleEventLifecycle46.Task(
            config(
                null,
                f -> {
                  throw problem;
                }),
            context(),
            frames);
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class,
            () ->
                task.begin(
                    Map.of(41007, event(false)),
                    0,
                    null,
                    (id, p, values, before) -> {
                      created.incrementAndGet();
                      return entity;
                    })));
    assertEquals(1, created.get());
    assertEquals(1, frames.depth());
    assertSame(entity, frames.current().eventEntity());
  }

  @Test
  void creationExceptionPropagatesWithoutRunningAfterCallback() {
    var problem = new IllegalStateException("factory unavailable");
    var task =
        new RogueBattleEventLifecycle46.Task(
            config(null, f -> fail("No after callback on factory failure")),
            context(),
            new RogueBattleEventLifecycle46.Frames(List.of()));
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class,
            () ->
                task.begin(
                    Map.of(41007, event(false)),
                    0,
                    null,
                    (id, p, values, before) -> {
                      throw problem;
                    })));
    assertEquals(RogueBattleEventLifecycle46.Task.DONE, task.state());
  }

  @Test
  void nestedCreationRestoresTheOuterCallbackFrameBeforeItsContinuation() {
    var frames = new RogueBattleEventLifecycle46.Frames(List.of());
    var outerEntity = new Object();
    var innerEntity = new Object();
    var order = new ArrayList<String>();
    var inner =
        new RogueBattleEventLifecycle46.Task(
            config(
                f -> {
                  assertEquals(2, f.depth());
                  assertSame(innerEntity, f.current().eventEntity());
                  order.add("inner-before");
                },
                f -> {
                  assertSame(innerEntity, f.current().eventEntity());
                  order.add("inner-after");
                }),
            context(),
            frames);
    var outer =
        new RogueBattleEventLifecycle46.Task(
            config(
                f -> {
                  assertEquals(1, f.depth());
                  var outerFrame = f.current();
                  order.add("outer-before");
                  inner.begin(
                      Map.of(41007, event(false)),
                      0,
                      null,
                      (id, p, values, before) -> {
                        before.accept(innerEntity);
                        return innerEntity;
                      });
                  assertSame(outerFrame, f.current());
                  assertEquals(1, f.depth());
                  order.add("outer-continue");
                },
                f -> {
                  assertSame(outerEntity, f.current().eventEntity());
                  order.add("outer-after");
                }),
            context(),
            frames);
    outer.begin(
        Map.of(41007, event(false)),
        0,
        null,
        (id, p, values, before) -> {
          before.accept(outerEntity);
          return outerEntity;
        });
    assertEquals(
        List.of("outer-before", "inner-before", "inner-after", "outer-continue", "outer-after"),
        order);
    assertEquals(0, frames.depth());
  }

  private static RogueBattleEventLifecycle46.Sequence disposable(
      String name, List<String> order, Runnable disposal) {
    return new RogueBattleEventLifecycle46.Sequence() {
      public void execute(RogueBattleEventLifecycle46.Frames frames) {
        order.add(name);
      }

      public void dispose() {
        order.add("dispose-" + name);
        disposal.run();
      }
    };
  }

  @Test
  void successfulDisposalUsesAfterThenBeforeAndClearsCallbackBridge() {
    var order = new ArrayList<String>();
    var task =
        new RogueBattleEventLifecycle46.Task(
            config(disposable("before", order, () -> {}), disposable("after", order, () -> {})),
            context(),
            new RogueBattleEventLifecycle46.Frames(List.of()));
    task.dispose();
    task.dispose();
    assertEquals(List.of("dispose-after", "dispose-before"), order);
    task.begin(
        Map.of(41007, event(false)),
        0,
        null,
        (id, p, values, before) -> {
          assertNull(before);
          return new Object();
        });
    assertEquals(List.of("dispose-after", "dispose-before"), order);
  }

  @Test
  void failedAfterDisposalDoesNotClearCallbacksOrDisposeBefore() {
    var order = new ArrayList<String>();
    var problem = new IllegalStateException("dispose after");
    var task =
        new RogueBattleEventLifecycle46.Task(
            config(
                disposable("before", order, () -> {}),
                disposable(
                    "after",
                    order,
                    () -> {
                      throw problem;
                    })),
            context(),
            new RogueBattleEventLifecycle46.Frames(List.of()));
    assertSame(problem, assertThrows(IllegalStateException.class, task::dispose));
    assertEquals(List.of("dispose-after"), order);
    task.begin(
        Map.of(41007, event(false)),
        0,
        null,
        (id, p, values, before) -> {
          var entity = new Object();
          before.accept(entity);
          return entity;
        });
    assertEquals(List.of("dispose-after", "before", "after"), order);
  }

  @Test
  void failedBeforeDisposalRetainsBeforeBridgeButAlreadyClearsAfter() {
    var order = new ArrayList<String>();
    var problem = new IllegalStateException("dispose before");
    var task =
        new RogueBattleEventLifecycle46.Task(
            config(
                disposable(
                    "before",
                    order,
                    () -> {
                      throw problem;
                    }),
                disposable("after", order, () -> {})),
            context(),
            new RogueBattleEventLifecycle46.Frames(List.of()));
    assertSame(problem, assertThrows(IllegalStateException.class, task::dispose));
    task.begin(
        Map.of(41007, event(false)),
        0,
        null,
        (id, p, values, before) -> {
          var entity = new Object();
          before.accept(entity);
          return entity;
        });
    assertEquals(List.of("dispose-after", "dispose-before", "before"), order);
  }

  @Test
  void fractionalStandardEventIdentityUsesNativeArithmeticShift() {
    var task =
        new RogueBattleEventLifecycle46.Task(
            new RogueBattleEventLifecycle46.Configuration(
                RogueResonanceDynamicCell46.Scalar.fixed((41007L << 33) + (1L << 32)),
                List.of(),
                null,
                null),
            context(),
            null);
    task.begin(
        Map.of(41007, event(false)),
        0,
        null,
        (id, p, values, before) -> {
          assertEquals(41007, id);
          return new Object();
        });
  }

  @Test
  void nativeBeginHasNoImplicitOnceOnlyGuard() {
    var calls = new AtomicInteger();
    var task = new RogueBattleEventLifecycle46.Task(config(null, null), context(), null);
    RogueBattleEventLifecycle46.Creation creation =
        (id, p, values, before) -> {
          calls.incrementAndGet();
          return new Object();
        };
    task.begin(Map.of(41007, event(false)), 0, null, creation);
    task.begin(Map.of(41007, event(false)), 0, null, creation);
    assertEquals(2, calls.get());
  }

  @Test
  void framesCopyInputAndExposeImmutableSnapshots() {
    var initial = new ArrayList<RogueBattleEventLifecycle46.Frame>();
    var outer = new RogueBattleEventLifecycle46.Frame(new Object());
    initial.add(outer);
    var frames = new RogueBattleEventLifecycle46.Frames(initial);
    initial.clear();
    assertSame(outer, frames.current());
    assertThrows(UnsupportedOperationException.class, () -> frames.snapshot().clear());
  }

  @Test
  void fullFrameStackRejectsAdditionalCallbackBeforeExecutingIt() {
    var initial = new ArrayList<RogueBattleEventLifecycle46.Frame>();
    for (int i = 0; i < 255; i++) initial.add(new RogueBattleEventLifecycle46.Frame(new Object()));
    var frames = new RogueBattleEventLifecycle46.Frames(initial);
    var task =
        new RogueBattleEventLifecycle46.Task(
            config(f -> fail("No callback on stack overflow"), null), context(), frames);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            task.begin(
                Map.of(41007, event(false)),
                0,
                null,
                (id, p, values, before) -> {
                  before.accept(new Object());
                  return new Object();
                }));
    assertEquals(initial, frames.snapshot());
  }

  @Test
  void inputsRejectMissingBindingsUnknownNegativeIdentitiesAndOversizedFrames() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueBattleEventLifecycle46.Task(null, context(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueBattleEventLifecycle46.Task(config(null, null), null, null));
    assertThrows(IllegalArgumentException.class, () -> new RogueBattleEventLifecycle46.Frame(null));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueBattleEventLifecycle46.Frames(null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RogueBattleEventLifecycle46.Frames(
                java.util.Collections.nCopies(
                    256, new RogueBattleEventLifecycle46.Frame(new Object()))));
    var task =
        new RogueBattleEventLifecycle46.Task(
            new RogueBattleEventLifecycle46.Configuration(
                RogueResonanceDynamicCell46.Scalar.fixed(-2), List.of(), null, null),
            context(),
            null);
    assertThrows(IllegalArgumentException.class, () -> task.begin(Map.of(), 0, null, null));
    assertEquals(RogueBattleEventLifecycle46.Task.DONE, task.state());
    var valid = new RogueBattleEventLifecycle46.Task(config(null, null), context(), null);
    assertThrows(
        IllegalArgumentException.class,
        () -> valid.begin(Map.of(41007, event(false)), 0, null, null));
  }
}
