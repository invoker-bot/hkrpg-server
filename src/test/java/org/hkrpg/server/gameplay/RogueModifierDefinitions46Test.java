package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueModifierDefinitions46Test {
  @Test
  void localModifierWinsBeforeGlobalLoadingOrMissingDiagnostic() {
    Object local = new Object();
    var calls = new ArrayList<String>();
    Object result =
        RogueModifierDefinitions46.resolve(
            "effect",
            Map.of("effect", local),
            key -> {
              calls.add("global");
              return new Object();
            },
            calls::add);
    assertSame(local, result);
    assertTrue(calls.isEmpty());
  }

  @Test
  void callbackSelectionPreservesTheFirstMatchingOriginalEntry() {
    var first =
        new RogueModifierDefinitions46.Callback(
            172, List.of(new RogueModifierDefinitions46.Add("ParamEntity", "first")));
    var second =
        new RogueModifierDefinitions46.Callback(
            172, List.of(new RogueModifierDefinitions46.Add("ParamEntity", "second")));
    var d =
        new RogueModifierDefinitions46.Definition(
            RogueModifierDefinitions46.DEFAULTS, List.of(first, second));
    assertSame(first, d.callbackInfo(172));
    assertEquals(first.program(), d.callback(172));
  }

  @Test
  void originalBreedingDefinitionsKeepBothMain2Events() {
    var global = RogueModifierDefinitions46.breedingGlobal();
    assertEquals(4, global.size());
    var main = global.get("MAvatar_RogueBattleevent127_AimTarget_Main2");
    assertEquals(
        List.of(95, 172),
        main.callbacks().stream().map(RogueModifierDefinitions46.Callback::event).toList());
  }

  @Test
  void missingLocalNameFallsBackToExplicitGlobalProvider() {
    Object global = new Object();
    var calls = new ArrayList<String>();
    Object result =
        RogueModifierDefinitions46.resolve(
            "m",
            Map.of(),
            key -> {
              calls.add(key);
              return global;
            },
            key -> fail("Unexpected missing diagnostic"));
    assertSame(global, result);
    assertEquals(List.of("m"), calls);
  }

  @Test
  void absentAbilityUsesTheSameGlobalProvider() {
    Object global = new Object();
    assertSame(global, RogueModifierDefinitions46.resolve("m", null, key -> global, key -> fail()));
  }

  @Test
  void nullLocalValueDoesNotHideAGlobalDefinition() {
    Object global = new Object();
    var local = new java.util.HashMap<String, Object>();
    local.put("m", null);
    assertSame(
        global, RogueModifierDefinitions46.resolve("m", local, key -> global, key -> fail()));
  }

  @Test
  void missingDiagnosticRunsOnlyAfterBothLookupsReturnNull() {
    var calls = new ArrayList<String>();
    assertNull(
        RogueModifierDefinitions46.resolve(
            "m",
            Map.of(),
            key -> {
              calls.add("global");
              return null;
            },
            key -> calls.add("missing:" + key)));
    assertEquals(List.of("global", "missing:m"), calls);
  }

  @Test
  void globalLoadingFailurePropagatesWithoutReportingAnOrdinaryMiss() {
    var problem = new IllegalStateException("load");
    var calls = new ArrayList<String>();
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class,
            () ->
                RogueModifierDefinitions46.resolve(
                    "m",
                    null,
                    key -> {
                      throw problem;
                    },
                    calls::add)));
    assertTrue(calls.isEmpty());
  }

  @Test
  void diagnosticFailurePropagatesInsteadOfInventingAResolvedDefinition() {
    var problem = new IllegalStateException("diagnostic");
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class,
            () ->
                RogueModifierDefinitions46.resolve(
                    "m",
                    null,
                    key -> null,
                    key -> {
                      throw problem;
                    })));
  }

  @Test
  void resolverDoesNotReplaceTheBoundGlobalProvidersCachePolicy() {
    var calls = new ArrayList<String>();
    Object global = new Object();
    for (int i = 0; i < 2; i++)
      assertSame(
          global,
          RogueModifierDefinitions46.resolve(
              "m",
              Map.of(),
              key -> {
                calls.add(key);
                return global;
              },
              key -> fail()));
    assertEquals(List.of("m", "m"), calls);
  }

  @Test
  void resolverRejectsMissingProjectBindingsBeforeAnyProviderCall() {
    var calls = new ArrayList<String>();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierDefinitions46.resolve(
                null,
                Map.of(),
                key -> {
                  calls.add(key);
                  return null;
                },
                calls::add));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierDefinitions46.resolve(
                "",
                Map.of(),
                key -> {
                  calls.add(key);
                  return null;
                },
                calls::add));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierDefinitions46.resolve("m", Map.of(), null, calls::add));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierDefinitions46.resolve(
                "m",
                Map.of(),
                key -> {
                  calls.add(key);
                  return null;
                },
                null));
    assertTrue(calls.isEmpty());
  }

  @Test
  void absentCallbackListAndUnmatchedEventHaveNoCallback() {
    var d = new RogueModifierDefinitions46.Definition(RogueModifierDefinitions46.DEFAULTS, null);
    assertNull(d.callbackInfo(57));
    assertNull(d.callback(57));
    assertFalse(d.hasCallback(57));
    assertNull(
        RogueModifierDefinitions46.compileCallback(
            d,
            57,
            action -> {
              fail("Absent callback factory used");
              return null;
            }));
    var other =
        new RogueModifierDefinitions46.Definition(
            RogueModifierDefinitions46.DEFAULTS,
            List.of(new RogueModifierDefinitions46.Callback(95, List.of())));
    assertNull(other.callback(172));
    assertFalse(other.hasCallback(172));
  }

  @Test
  void nullProgramStillHasCallbackInformationButDoesNotCompileATaskList() {
    var first = new RogueModifierDefinitions46.Callback(57, null);
    var d =
        new RogueModifierDefinitions46.Definition(
            RogueModifierDefinitions46.DEFAULTS,
            List.of(first, new RogueModifierDefinitions46.Callback(57, List.of())));
    assertTrue(d.hasCallback(57));
    assertSame(first, d.callbackInfo(57));
    assertNull(d.callback(57));
    assertNull(RogueModifierDefinitions46.compileCallback(d, 57, null));
  }

  @Test
  void explicitlyEmptyProgramCompilesARealEmptySequenceWithoutAFactory() {
    var d =
        new RogueModifierDefinitions46.Definition(
            RogueModifierDefinitions46.DEFAULTS,
            List.of(new RogueModifierDefinitions46.Callback(57, List.of())));
    assertTrue(d.hasCallback(57));
    assertNotNull(d.callback(57));
    assertTrue(d.callback(57).isEmpty());
    var sequence = RogueModifierDefinitions46.compileCallback(d, 57, null);
    assertNotNull(sequence);
    assertEquals(RogueTaskSequence46.READY, sequence.state());
    sequence.begin();
    assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
  }

  private static class Task implements RogueTaskSequence46.Task {
    int state = RogueTaskSequence46.READY;
    final Runnable action;

    Task(Runnable action) {
      this.action = action;
    }

    public int state() {
      return state;
    }

    public void begin() {
      action.run();
      state = RogueTaskSequence46.SUCCESS;
    }

    public void tick(float delta) {}

    public void reset() {
      state = RogueTaskSequence46.READY;
    }
  }

  @Test
  void boundActionsCompileInSourceOrderAndExecuteThroughTheExistingSequence() {
    var first = new RogueModifierDefinitions46.Add("ParamEntity", "first");
    var second = new RogueModifierDefinitions46.Remove("ModifierOwnerEntity", "effect", false);
    var d =
        new RogueModifierDefinitions46.Definition(
            RogueModifierDefinitions46.DEFAULTS,
            List.of(new RogueModifierDefinitions46.Callback(57, List.of(first, second))));
    var allocated = new ArrayList<RogueModifierDefinitions46.Action>();
    var executed = new ArrayList<RogueModifierDefinitions46.Action>();
    var sequence =
        RogueModifierDefinitions46.compileCallback(
            d,
            57,
            action -> {
              allocated.add(action);
              return new Task(() -> executed.add(action));
            });
    assertEquals(List.of(first, second), allocated);
    assertTrue(executed.isEmpty());
    sequence.begin();
    assertEquals(List.of(first, second), executed);
    sequence.begin();
    assertEquals(2, executed.size());
    sequence.reset();
    sequence.begin();
    assertEquals(List.of(first, second, first, second), executed);
  }

  @Test
  void missingActionFactoryIsRejectedInsteadOfOmittingOriginalEffects() {
    var d =
        RogueModifierDefinitions46.breedingGlobal()
            .get("MAvatar_RogueBattleevent127_AimTarget_Main");
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierDefinitions46.compileCallback(d, 172, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierDefinitions46.compileCallback(d, 172, action -> null));
  }

  @Test
  void actionFactoryFailureDoesNotExecuteAnEarlierAllocatedTask() {
    var calls = new ArrayList<String>();
    var problem = new IllegalStateException("factory");
    var d =
        new RogueModifierDefinitions46.Definition(
            RogueModifierDefinitions46.DEFAULTS,
            List.of(
                new RogueModifierDefinitions46.Callback(
                    57,
                    List.of(
                        new RogueModifierDefinitions46.Add("ParamEntity", "first"),
                        new RogueModifierDefinitions46.Add("ParamEntity", "second")))));
    var allocated = new ArrayList<RogueModifierDefinitions46.Action>();
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class,
            () ->
                RogueModifierDefinitions46.compileCallback(
                    d,
                    57,
                    action -> {
                      if (!allocated.isEmpty()) throw problem;
                      allocated.add(action);
                      return new Task(() -> calls.add("effect"));
                    })));
    assertEquals(1, allocated.size());
    assertTrue(calls.isEmpty());
  }

  @Test
  void callbackExecutionFailureKeepsPriorWorkAndStopsLaterTasks() {
    var calls = new ArrayList<String>();
    var problem = new IllegalStateException("second");
    var d =
        new RogueModifierDefinitions46.Definition(
            RogueModifierDefinitions46.DEFAULTS,
            List.of(
                new RogueModifierDefinitions46.Callback(
                    57,
                    List.of(
                        new RogueModifierDefinitions46.Add("ParamEntity", "first"),
                        new RogueModifierDefinitions46.Add("ParamEntity", "second"),
                        new RogueModifierDefinitions46.Add("ParamEntity", "third")))));
    var sequence =
        RogueModifierDefinitions46.compileCallback(
            d,
            57,
            action ->
                new Task(
                    () -> {
                      String name = ((RogueModifierDefinitions46.Add) action).literalName();
                      if (name.equals("second")) throw problem;
                      calls.add(name);
                    }));
    assertSame(problem, assertThrows(IllegalStateException.class, sequence::begin));
    assertEquals(List.of("first"), calls);
    assertEquals(RogueTaskSequence46.SUCCESS, sequence.children().getFirst().state());
    assertEquals(RogueTaskSequence46.READY, sequence.children().get(1).state());
    assertEquals(RogueTaskSequence46.READY, sequence.children().getLast().state());
  }

  @Test
  void callbacksAndBothBranchesTakeIndependentImmutableSnapshots() {
    var actions = new ArrayList<RogueModifierDefinitions46.Action>();
    actions.add(new RogueModifierDefinitions46.Add("ParamEntity", "m"));
    var branch =
        new RogueModifierDefinitions46.Branch(
            new RogueModifierDefinitions46.ByTeam("ParamEntity", 1), actions, List.of());
    var callback = new RogueModifierDefinitions46.Callback(57, List.of(branch));
    var callbacks = new ArrayList<RogueModifierDefinitions46.Callback>();
    callbacks.add(callback);
    var d =
        new RogueModifierDefinitions46.Definition(RogueModifierDefinitions46.DEFAULTS, callbacks);
    actions.clear();
    callbacks.clear();
    assertEquals(1, branch.success().size());
    assertEquals(1, d.callbacks().size());
    assertThrows(UnsupportedOperationException.class, () -> branch.success().clear());
    assertThrows(UnsupportedOperationException.class, () -> d.callbacks().clear());
    assertThrows(UnsupportedOperationException.class, () -> callback.program().clear());
  }

  @Test
  void globalCataloguePreservesSourceOrderAndCannotBeMutated() {
    var d = RogueModifierDefinitions46.breedingGlobal();
    String prefix = "MAvatar_RogueBattleevent127_AimTarget";
    assertEquals(
        List.of(prefix, prefix + "_Main", prefix + "_Main2", prefix + "_Sub"),
        new ArrayList<>(d.keySet()));
    assertThrows(UnsupportedOperationException.class, () -> d.clear());
  }

  @Test
  void sourcePredicatesRemainBoundDescriptorsWithoutInventedEvaluation() {
    var d =
        RogueModifierDefinitions46.breedingGlobal()
            .get("MAvatar_RogueBattleevent127_AimTarget_Sub");
    var actions = new ArrayList<RogueModifierDefinitions46.Action>();
    var sequence =
        RogueModifierDefinitions46.compileCallback(
            d,
            173,
            action -> {
              actions.add(action);
              return new Task(() -> {});
            });
    assertEquals(1, actions.size());
    var branch = (RogueModifierDefinitions46.Branch) actions.getFirst();
    assertTrue(branch.condition() instanceof RogueModifierDefinitions46.CompareTargets);
    assertTrue(branch.success().isEmpty());
    assertEquals(1, branch.failure().size());
    sequence.begin();
    assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
  }

  @Test
  void unsupportedCatalogueIdentityAndInvalidDescriptorsAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDefinitions46.classicLocal(41000));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierDefinitions46.compileCallback(null, 57, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierDefinitions46.Definition(null, null));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueModifierDefinitions46.Callback(-1, null));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueModifierDefinitions46.Add(null, "m"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierDefinitions46.Remove("target", "", false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierDefinitions46.Trigger("target", "effect", false, Float.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierDefinitions46.CompareTargets("target", null));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueModifierDefinitions46.ByTeam("target", 6));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierDefinitions46.Branch(null, null, null));
  }

  @Test
  void oversizedProgramsAndCallbackListsAreRejectedAtTheProjectBoundary() {
    var actions = new ArrayList<RogueModifierDefinitions46.Action>();
    var callbacks = new ArrayList<RogueModifierDefinitions46.Callback>();
    for (int i = 0; i < 256; i++) {
      actions.add(new RogueModifierDefinitions46.Add("ParamEntity", "m"));
      callbacks.add(new RogueModifierDefinitions46.Callback(57, null));
    }
    assertThrows(
        IllegalArgumentException.class, () -> new RogueModifierDefinitions46.Callback(57, actions));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RogueModifierDefinitions46.Branch(
                new RogueModifierDefinitions46.ByTeam("target", 1), actions, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RogueModifierDefinitions46.Definition(
                RogueModifierDefinitions46.DEFAULTS, callbacks));
  }

  private static com.google.gson.JsonObject fixture() throws Exception {
    try (var in =
            RogueModifierDefinitions46Test.class.getResourceAsStream(
                "/fixtures/rogue-modifier-definitions-4.6.json");
        var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
      return com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
    }
  }

  @Test
  void nativeDefinitionDefaultsIncludeZeroLayerIncrementAndUniqueStacking() throws Exception {
    var s = fixture().getAsJsonObject("constructorDefaults");
    var d = RogueModifierDefinitions46.DEFAULTS;
    assertEquals(s.get("Count").getAsInt(), d.count());
    assertEquals(s.get("Stacking").getAsInt(), d.stacking());
    assertEquals(s.get("LifeTime").getAsInt(), d.lifeTime());
    assertEquals(s.get("MaxLayer").getAsInt(), d.maxLayer());
    assertEquals(s.get("LayerAddWhenStackRaw").getAsLong(), d.layerAddWhenStackRaw());
    assertEquals(s.get("WorkingTurn").getAsInt(), d.workingTurn());
    assertEquals(s.get("LifeStepMoment").getAsInt(), d.lifeStepMoment());
    assertEquals(
        s.get("ApplyBehaviorFlagBindEffects").getAsBoolean(), d.applyBehaviorFlagBindEffects());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      ints = {41001, 41002, 41003, 41004, 41005, 41006, 41007, 41008, 41009})
  void allNineOriginalLocalDefinitionListsPreserveAbsentFields(int event) throws Exception {
    var raw =
        fixture().getAsJsonArray("sourceLocalDefinitions").asList().stream()
            .map(e -> e.getAsJsonObject())
            .filter(e -> e.get("eventId").getAsInt() == event)
            .findFirst()
            .orElseThrow();
    var local = RogueModifierDefinitions46.classicLocal(event);
    if (raw.get("Modifiers").isJsonNull()) {
      assertTrue(local.isEmpty());
      return;
    }
    var entries = raw.getAsJsonObject("Modifiers").getAsJsonArray("entries");
    assertEquals(entries.size(), local.size());
    for (var entry : entries) {
      var pair = entry.getAsJsonArray();
      String key = pair.get(0).getAsString();
      var d = local.get(key);
      assertNotNull(d);
      assertEquals("0x0", pair.get(1).getAsJsonObject().get("$mask").getAsString());
      assertSame(RogueModifierDefinitions46.DEFAULTS, d.defaults());
      assertNull(d.callbacks());
      assertFalse(d.hasCallback(57));
    }
  }

  private static void checkProgram(
      com.google.gson.JsonElement raw, List<RogueModifierDefinitions46.Action> program) {
    if (raw == null || raw.isJsonNull()) {
      assertNull(program);
      return;
    }
    var source = raw.getAsJsonArray();
    assertNotNull(program);
    assertEquals(source.size(), program.size());
    for (int i = 0; i < source.size(); i++) {
      var s = source.get(i).getAsJsonObject();
      var action = program.get(i);
      assertEquals(s.get("taskId").getAsInt(), action.taskId());
      assertFalse(s.has("TaskEnabled"));
      if (action instanceof RogueModifierDefinitions46.Add a) {
        assertEquals(s.getAsJsonObject("TargetType").get("Alias").getAsString(), a.alias());
        assertEquals(s.getAsJsonObject("ModifierName").get("Value").getAsString(), a.literalName());
      } else if (action instanceof RogueModifierDefinitions46.Trigger a) {
        assertEquals(s.getAsJsonObject("TargetType").get("Alias").getAsString(), a.alias());
        assertEquals(s.get("EffectPath").getAsString(), a.effectPath());
        assertEquals(
            s.get("ForceSimulateImmediately").getAsBoolean(), a.forceSimulateImmediately());
        assertEquals(
            s.has("MinMutexTime") ? s.get("MinMutexTime").getAsFloat() : null, a.minMutexTime());
      } else if (action instanceof RogueModifierDefinitions46.Remove a) {
        assertEquals(s.getAsJsonObject("TargetType").get("Alias").getAsString(), a.alias());
        assertEquals(s.get("EffectPath").getAsString(), a.effectPath());
        assertEquals(s.get("ForceImmediateFadeOut").getAsBoolean(), a.forceImmediateFadeOut());
      } else if (action instanceof RogueModifierDefinitions46.Branch a) {
        var p = s.getAsJsonObject("Predicate");
        assertEquals(
            p.getAsJsonObject("TargetType").get("Alias").getAsString(),
            a.condition() instanceof RogueModifierDefinitions46.ByTeam team
                ? team.alias()
                : ((RogueModifierDefinitions46.CompareTargets) a.condition()).alias());
        if (a.condition() instanceof RogueModifierDefinitions46.ByTeam team) {
          assertEquals(526, p.get("$variantId").getAsInt());
          assertEquals(p.get("Team").getAsInt(), team.team());
        } else {
          assertEquals(273, p.get("$variantId").getAsInt());
          assertEquals(
              p.getAsJsonObject("CompareType").get("Alias").getAsString(),
              ((RogueModifierDefinitions46.CompareTargets) a.condition()).compareAlias());
        }
        checkProgram(s.get("SuccessTaskList"), a.success());
        checkProgram(s.get("FailedTaskList"), a.failure());
      } else fail("Unmatched finite action");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "MAvatar_RogueBattleevent127_AimTarget",
        "MAvatar_RogueBattleevent127_AimTarget_Main",
        "MAvatar_RogueBattleevent127_AimTarget_Main2",
        "MAvatar_RogueBattleevent127_AimTarget_Sub"
      })
  void allFourOriginalGlobalDefinitionsPreserveEveryCallbackTaskAndAbsentBranch(String name)
      throws Exception {
    var entries = fixture().getAsJsonObject("sourceGlobalDefinitions").getAsJsonArray("entries");
    var raw =
        entries.asList().stream()
            .map(e -> e.getAsJsonArray())
            .filter(e -> e.get(0).getAsString().equals(name))
            .findFirst()
            .orElseThrow()
            .get(1)
            .getAsJsonObject();
    var d = RogueModifierDefinitions46.breedingGlobal().get(name);
    assertNotNull(d);
    assertSame(RogueModifierDefinitions46.DEFAULTS, d.defaults());
    var original = raw.getAsJsonArray("_CallbackList");
    assertEquals(original.size(), d.callbacks().size());
    for (int i = 0; i < original.size(); i++) {
      var c = original.get(i).getAsJsonObject();
      var callback = d.callbacks().get(i);
      int event = c.get("Event").getAsInt();
      assertEquals(event, callback.event());
      assertSame(callback, d.callbackInfo(event));
      assertTrue(d.hasCallback(event));
      checkProgram(c.get("CallbackConfig"), callback.program());
      var allocated = new ArrayList<RogueModifierDefinitions46.Action>();
      var executed = new ArrayList<RogueModifierDefinitions46.Action>();
      var sequence =
          RogueModifierDefinitions46.compileCallback(
              d,
              event,
              action -> {
                allocated.add(action);
                return new Task(() -> executed.add(action));
              });
      assertEquals(callback.program(), allocated);
      assertTrue(executed.isEmpty());
      sequence.begin();
      assertEquals(callback.program(), executed);
    }
  }
}
