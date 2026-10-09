package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RogueAddModifierEntry46Test {
  private static RogueResonanceOnAdd46.Modifier command() {
    return new RogueResonanceOnAdd46.Modifier(null, "Caster", "StageAbility_612020_Modifier");
  }

  private static class Bound implements RogueAddModifierEntry46.Context {
    final Object source = new Object();
    final Object target = new Object();
    final List<String> calls = new ArrayList<>();
    Boolean charm = false;

    public RogueAddModifierEntry46.Source source() {
      calls.add("source");
      return new RogueAddModifierEntry46.Source(source, charm);
    }

    public List<Object> targets(String alias, int mask) {
      assertEquals("Caster", alias);
      assertEquals(1, mask);
      calls.add("targets");
      return List.of(target);
    }

    public void apply(RogueAddModifierEntry46.Request request) {
      assertSame(source, request.sourceEntity());
      assertSame(target, request.targetEntity());
      assertEquals("StageAbility_612020_Modifier", request.literalName());
      calls.add("apply");
    }

    public void finished(List<Object> targets) {
      calls.add("finished");
    }
  }

  @Test
  void ordinaryEntryReadsSourceAndAppliesBeforeCompletion() {
    var bound = new Bound();
    var task = new RogueAddModifierEntry46.Task(command(), bound);
    task.begin();
    assertEquals(List.of("source", "targets", "apply", "finished"), bound.calls);
    assertEquals(RogueTaskSequence46.SUCCESS, task.state());
  }

  @Test
  void sourceCharmSkipsTargetsAndEffects() {
    var bound = new Bound();
    bound.charm = true;
    var task = new RogueAddModifierEntry46.Task(command(), bound);
    task.begin();
    assertEquals(List.of("source"), bound.calls);
    assertEquals(RogueTaskSequence46.SUCCESS, task.state());
  }

  @Test
  void effectFailureKeepsTheTaskReadyAndDoesNotFinalize() {
    var problem = new IllegalStateException("effect");
    var bound =
        new Bound() {
          public void apply(RogueAddModifierEntry46.Request request) {
            calls.add("apply");
            throw problem;
          }
        };
    var task = new RogueAddModifierEntry46.Task(command(), bound);
    assertSame(problem, assertThrows(IllegalStateException.class, task::begin));
    assertEquals(RogueTaskSequence46.READY, task.state());
    assertFalse(bound.calls.contains("finished"));
  }

  private static class Targets implements RogueAddModifierEntry46.Context {
    final Object source = new Object();
    final List<Object> input = new ArrayList<>();
    final List<RogueAddModifierEntry46.Request> applied = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    Boolean charm;
    List<Object> completed;

    public RogueAddModifierEntry46.Source source() {
      calls.add("source");
      return new RogueAddModifierEntry46.Source(source, charm);
    }

    public List<Object> targets(String alias, int mask) {
      assertEquals("Caster", alias);
      assertEquals(1, mask);
      calls.add("targets");
      return input;
    }

    public void apply(RogueAddModifierEntry46.Request request) {
      calls.add("apply");
      applied.add(request);
    }

    public void finished(List<Object> targets) {
      calls.add("finished");
      completed = targets;
    }
  }

  @Test
  void missingComponentDoesNotInventCharm() {
    var b = new Targets();
    b.input.add(new Object());
    var t = new RogueAddModifierEntry46.Task(command(), b);
    t.begin();
    assertEquals(List.of("source", "targets", "apply", "finished"), b.calls);
    assertEquals(RogueTaskSequence46.SUCCESS, t.state());
  }

  @Test
  void allBoundWorkObservesReadyUntilCompletionReturns() {
    var holder = new RogueAddModifierEntry46.Task[1];
    var b =
        new Targets() {
          public RogueAddModifierEntry46.Source source() {
            assertEquals(RogueTaskSequence46.READY, holder[0].state());
            return super.source();
          }

          public List<Object> targets(String alias, int mask) {
            assertEquals(RogueTaskSequence46.READY, holder[0].state());
            return super.targets(alias, mask);
          }

          public void apply(RogueAddModifierEntry46.Request r) {
            assertEquals(RogueTaskSequence46.READY, holder[0].state());
            super.apply(r);
          }

          public void finished(List<Object> targets) {
            assertEquals(RogueTaskSequence46.READY, holder[0].state());
            super.finished(targets);
          }
        };
    b.input.add(new Object());
    holder[0] = new RogueAddModifierEntry46.Task(command(), b);
    holder[0].begin();
    assertEquals(RogueTaskSequence46.SUCCESS, holder[0].state());
  }

  @Test
  void emptyTargetsCompleteWithoutNotification() {
    var b = new Targets();
    var t = new RogueAddModifierEntry46.Task(command(), b);
    t.begin();
    assertEquals(List.of("source", "targets"), b.calls);
    assertNull(b.completed);
    assertEquals(RogueTaskSequence46.SUCCESS, t.state());
  }

  @Test
  void sourceExceptionStopsBeforeTargetSelection() {
    var problem = new IllegalStateException("source");
    var b =
        new Targets() {
          public RogueAddModifierEntry46.Source source() {
            throw problem;
          }
        };
    var t = new RogueAddModifierEntry46.Task(command(), b);
    assertSame(problem, assertThrows(IllegalStateException.class, t::begin));
    assertEquals(RogueTaskSequence46.READY, t.state());
    assertTrue(b.calls.isEmpty());
  }

  @Test
  void absentSourceProjectionIsRejected() {
    var b =
        new Targets() {
          public RogueAddModifierEntry46.Source source() {
            return null;
          }
        };
    var t = new RogueAddModifierEntry46.Task(command(), b);
    assertThrows(IllegalArgumentException.class, t::begin);
    assertEquals(RogueTaskSequence46.READY, t.state());
    assertTrue(b.calls.isEmpty());
  }

  @Test
  void targetExceptionDoesNotApplyOrFinish() {
    var problem = new IllegalStateException("targets");
    var b =
        new Targets() {
          public List<Object> targets(String alias, int mask) {
            throw problem;
          }
        };
    var t = new RogueAddModifierEntry46.Task(command(), b);
    assertSame(problem, assertThrows(IllegalStateException.class, t::begin));
    assertEquals(RogueTaskSequence46.READY, t.state());
    assertTrue(b.applied.isEmpty());
    assertNull(b.completed);
  }

  @Test
  void absentTargetsAreRejectedBeforeEffects() {
    var b =
        new Targets() {
          public List<Object> targets(String alias, int mask) {
            return null;
          }
        };
    var t = new RogueAddModifierEntry46.Task(command(), b);
    assertThrows(IllegalArgumentException.class, t::begin);
    assertEquals(RogueTaskSequence46.READY, t.state());
    assertTrue(b.applied.isEmpty());
    assertNull(b.completed);
  }

  @Test
  void missingTargetRejectsEntireSnapshotBeforeEffects() {
    var b = new Targets();
    b.input.add(new Object());
    b.input.add(null);
    var t = new RogueAddModifierEntry46.Task(command(), b);
    assertThrows(IllegalArgumentException.class, t::begin);
    assertTrue(b.applied.isEmpty());
    assertEquals(RogueTaskSequence46.READY, t.state());
    assertNull(b.completed);
  }

  @Test
  void oversizedProjectSnapshotIsRejectedBeforeEffects() {
    var b = new Targets();
    for (int i = 0; i < 256; i++) b.input.add(new Object());
    var t = new RogueAddModifierEntry46.Task(command(), b);
    assertThrows(IllegalArgumentException.class, t::begin);
    assertTrue(b.applied.isEmpty());
    assertEquals(RogueTaskSequence46.READY, t.state());
  }

  @Test
  void maximumProjectSnapshotPreservesAllTargetIdentitiesInOrder() {
    var b = new Targets();
    for (int i = 0; i < 255; i++) b.input.add(new Object());
    var t = new RogueAddModifierEntry46.Task(command(), b);
    t.begin();
    assertEquals(255, b.applied.size());
    assertEquals(b.input, b.completed);
    for (int i = 0; i < 255; i++) {
      var r = b.applied.get(i);
      assertSame(b.input.get(i), r.targetEntity());
      assertSame(b.source, r.sourceEntity());
      assertSame(RogueAddModifierEntry46.DEFAULTS, r.defaults());
    }
  }

  @Test
  void laterTargetFailureKeepsEarlierApplicationsAndSkipsTheRemainder() {
    var problem = new IllegalStateException("second");
    var b =
        new Targets() {
          public void apply(RogueAddModifierEntry46.Request r) {
            if (applied.size() == 1) throw problem;
            super.apply(r);
          }
        };
    b.input.add(new Object());
    b.input.add(new Object());
    b.input.add(new Object());
    var t = new RogueAddModifierEntry46.Task(command(), b);
    assertSame(problem, assertThrows(IllegalStateException.class, t::begin));
    assertEquals(1, b.applied.size());
    assertSame(b.input.getFirst(), b.applied.getFirst().targetEntity());
    assertNull(b.completed);
    assertEquals(RogueTaskSequence46.READY, t.state());
  }

  @Test
  void notificationFailureDoesNotUndoApplicationsOrMarkSuccess() {
    var problem = new IllegalStateException("finished");
    var b =
        new Targets() {
          public void finished(List<Object> targets) {
            throw problem;
          }
        };
    b.input.add(new Object());
    var t = new RogueAddModifierEntry46.Task(command(), b);
    assertSame(problem, assertThrows(IllegalStateException.class, t::begin));
    assertEquals(1, b.applied.size());
    assertEquals(RogueTaskSequence46.READY, t.state());
  }

  @Test
  void targetSnapshotIsImmutableAndIndependentOfProviderMutation() {
    var b =
        new Targets() {
          public void apply(RogueAddModifierEntry46.Request r) {
            input.clear();
            super.apply(r);
          }
        };
    Object first = new Object(), second = new Object();
    b.input.add(first);
    b.input.add(second);
    var t = new RogueAddModifierEntry46.Task(command(), b);
    t.begin();
    assertEquals(List.of(first, second), b.completed);
    assertEquals(2, b.applied.size());
    assertThrows(UnsupportedOperationException.class, () -> b.completed.add(new Object()));
  }

  @Test
  void directRepeatedBeginReappliesUntilCallerSuppliesItsOwnStackingPolicy() {
    var b = new Targets();
    b.input.add(new Object());
    var t = new RogueAddModifierEntry46.Task(command(), b);
    t.begin();
    t.begin();
    assertEquals(2, b.applied.size());
    assertEquals(RogueTaskSequence46.SUCCESS, t.state());
  }

  @Test
  void repeatFailurePreservesPreviouslyCompletedState() {
    var b =
        new Targets() {
          public void apply(RogueAddModifierEntry46.Request r) {
            if (!applied.isEmpty()) throw new IllegalStateException("repeat");
            super.apply(r);
          }
        };
    b.input.add(new Object());
    var t = new RogueAddModifierEntry46.Task(command(), b);
    t.begin();
    assertThrows(IllegalStateException.class, t::begin);
    assertEquals(RogueTaskSequence46.SUCCESS, t.state());
    assertEquals(1, b.applied.size());
  }

  @Test
  void resetMakesSubsequentFailureReadyAgain() {
    var b =
        new Targets() {
          public void apply(RogueAddModifierEntry46.Request r) {
            if (!applied.isEmpty()) throw new IllegalStateException("reset");
            super.apply(r);
          }
        };
    b.input.add(new Object());
    var t = new RogueAddModifierEntry46.Task(command(), b);
    t.begin();
    t.reset();
    assertEquals(RogueTaskSequence46.READY, t.state());
    assertThrows(IllegalStateException.class, t::begin);
    assertEquals(RogueTaskSequence46.READY, t.state());
  }

  @Test
  void tickDoesNotStartAReadyTaskOrReapplyACompletedTask() {
    var b = new Targets();
    b.input.add(new Object());
    var t = new RogueAddModifierEntry46.Task(command(), b);
    t.tick(0);
    assertTrue(b.calls.isEmpty());
    assertEquals(RogueTaskSequence46.READY, t.state());
    t.begin();
    t.tick(1);
    assertEquals(1, b.applied.size());
  }

  @Test
  void explicitEntryBindingsRejectAbsentArguments() {
    assertThrows(IllegalArgumentException.class, () -> RogueAddModifierEntry46.factory(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueAddModifierEntry46.Task(null, new Targets()));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueAddModifierEntry46.Task(command(), null));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueAddModifierEntry46.Source(null, false));
  }

  @Test
  void explicitRequestsRejectMissingEffectsAndIdentities() {
    Object entity = new Object();
    var d = RogueAddModifierEntry46.DEFAULTS;
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueAddModifierEntry46.Request(null, d, entity, entity));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueAddModifierEntry46.Request("", d, entity, entity));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueAddModifierEntry46.Request("m", null, entity, entity));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueAddModifierEntry46.Request("m", d, null, entity));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueAddModifierEntry46.Request("m", d, entity, null));
  }

  private static com.google.gson.JsonObject fixture(String name) throws Exception {
    try (var in = RogueAddModifierEntry46Test.class.getResourceAsStream("/fixtures/" + name);
        var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
      return com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
    }
  }

  @Test
  void constructorDefaultsMatchTheFrozenNativeWritesWithoutReinterpretingMinusOne()
      throws Exception {
    var f = fixture("rogue-modifier-entry-4.6.json").getAsJsonObject("constructorDefaults");
    var d = RogueAddModifierEntry46.DEFAULTS;
    assertEquals(f.get("AliveOnly").getAsBoolean(), d.aliveOnly());
    assertEquals(f.get("ChanceRaw").getAsLong(), d.chanceRaw());
    assertEquals(f.get("MaxTargetCountRaw").getAsLong(), d.maxTargetCountRaw());
    assertEquals(f.get("IsShowUIMessageFromModel").getAsBoolean(), d.showUiFromModel());
    assertEquals(f.get("IsShowUIMessageOnUpdate").getAsBoolean(), d.showUiOnUpdate());
    assertEquals(f.get("ShowUIMessageInternalTime").getAsFloat(), d.uiInternalTime());
    assertEquals(f.get("ShowUIMessageCanMerge").getAsBoolean(), d.mergeUiMessages());
    assertEquals(f.get("UseFixedPosition").getAsBoolean(), d.useFixedPosition());
    assertTrue(f.get("TaskEnabled").getAsBoolean());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {41001, 41002, 41003, 41004, 41005, 41008})
  void allSixOriginalListsReachTheEntryOnlyAfterSourceQueryAndCreation(int id) throws Exception {
    var original =
        fixture("rogue-modifier-entry-4.6.json")
            .getAsJsonArray("sourceModifierCommands")
            .asList()
            .stream()
            .map(e -> e.getAsJsonObject())
            .filter(e -> e.get("eventId").getAsInt() == id)
            .findFirst()
            .orElseThrow()
            .getAsJsonObject("command");
    var events =
        RogueBattleEventTable46.readConfig(
            java.util.HexFormat.of()
                .parseHex(
                    fixture("rogue-battle-event-tables-4.6.json")
                        .getAsJsonArray("records")
                        .get(1)
                        .getAsJsonObject()
                        .get("rawHex")
                        .getAsString()));
    var order = new ArrayList<String>();
    var b =
        new Targets() {
          public RogueAddModifierEntry46.Source source() {
            order.add("entry");
            return super.source();
          }

          public void apply(RogueAddModifierEntry46.Request r) {
            assertEquals(
                original.getAsJsonObject("ModifierName").get("Value").getAsString(),
                r.literalName());
            order.add("effect");
            super.apply(r);
          }
        };
    b.input.add(new Object());
    var definition = RogueResonanceClassicEvents46.find(id).orElseThrow();
    var lookup = new RogueResonanceContext46(null, List.of(), definition.newTemplate(), null);
    var bound =
        new RogueResonanceOnAdd46.Bindings(
            lookup,
            (key, level) -> {
              order.add("query");
              return null;
            },
            events,
            1,
            key -> new RogueBattleEventTable46.WaveStage(1, new Object()),
            (request, before) -> {
              order.add("create");
              return new Object();
            },
            null,
            RogueAddModifierEntry46.factory(b));
    var compiled = RogueResonanceOnAdd46.compile(RogueResonanceOnAdd46.classicCommands(id), bound);
    compiled.sequence().begin();
    assertEquals(RogueTaskSequence46.SUCCESS, compiled.sequence().state());
    assertTrue(order.indexOf("query") < order.indexOf("create"));
    assertEquals(
        List.of("create", "entry", "effect"), order.subList(order.size() - 3, order.size()));
    assertEquals(1, b.applied.size());
    assertEquals(List.of("source", "targets", "apply", "finished"), b.calls);
  }
}
