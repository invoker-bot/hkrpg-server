package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueResonanceOnAdd46Test {
  private static RogueBattleEventTable46.Config event(int id) {
    return new RogueBattleEventTable46.Config(
        id,
        3,
        3,
        0,
        0,
        "event",
        null,
        List.of(),
        List.of(),
        100L << 33,
        false,
        false,
        null,
        null,
        List.of(),
        null,
        0,
        0,
        1);
  }

  private static RogueResonanceOnAdd46.Bindings bindings(
      int id, List<String> calls, RogueResonanceOnAdd46.ModifierFactory modifiers) {
    var definition = RogueResonanceClassicEvents46.find(id).orElseThrow();
    var context = new RogueResonanceContext46(null, List.of(), definition.newTemplate(), null);
    return new RogueResonanceOnAdd46.Bindings(
        context,
        (buff, level) -> {
          calls.add("query");
          return null;
        },
        Map.of(id, event(id)),
        0,
        null,
        (request, before) -> {
          assertEquals(id, request.eventId());
          calls.add("create");
          return new Object();
        },
        null,
        modifiers);
  }

  private static class Effect implements RogueTaskSequence46.Task {
    int state = RogueTaskSequence46.READY;
    final Runnable action;

    Effect(Runnable action) {
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
  void sourceQueriesExecuteBeforeCreatingTheEvent() {
    var calls = new ArrayList<String>();
    var compiled =
        RogueResonanceOnAdd46.compile(
            RogueResonanceOnAdd46.classicCommands(41009), bindings(41009, calls, null));
    compiled.sequence().begin();
    assertEquals(List.of("query", "query", "query", "create"), calls);
  }

  @Test
  void originalModifierIsExecutedAfterCreation() {
    var calls = new ArrayList<String>();
    var bound =
        bindings(41001, calls, command -> new Effect(() -> calls.add(command.literalName())));
    var compiled =
        RogueResonanceOnAdd46.compile(RogueResonanceOnAdd46.classicCommands(41001), bound);
    compiled.sequence().begin();
    assertEquals("StageAbility_612020_Modifier", calls.isEmpty() ? null : calls.getLast());
    assertEquals("create", calls.size() < 2 ? null : calls.get(calls.size() - 2));
  }

  @Test
  void missingModifierImplementationIsRejectedBeforeExecution() {
    var calls = new ArrayList<String>();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceOnAdd46.compile(
                RogueResonanceOnAdd46.classicCommands(41008), bindings(41008, calls, null)));
    assertTrue(calls.isEmpty());
  }

  private static com.google.gson.JsonObject fixture(String name) throws Exception {
    try (var in = RogueResonanceOnAdd46Test.class.getResourceAsStream("/fixtures/" + name);
        var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
      return com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      ints = {41001, 41002, 41003, 41004, 41005, 41006, 41007, 41008, 41009})
  void allNineCompleteOriginalOnAddListsRunInSourceOrder(int id) throws Exception {
    var frozen = fixture("rogue-resonance-onadd-4.6.json").getAsJsonArray("sourceCommands");
    var source =
        frozen.asList().stream()
            .map(e -> e.getAsJsonObject())
            .filter(e -> e.get("eventId").getAsInt() == id)
            .findFirst()
            .orElseThrow();
    var original = source.getAsJsonArray("commands");
    var commands = RogueResonanceOnAdd46.classicCommands(id);
    assertEquals(original.size(), commands.size());
    for (int i = 0; i < original.size(); i++) {
      var task = original.get(i).getAsJsonObject();
      assertEquals(task.get("taskId").getAsInt(), commands.get(i).taskId());
      assertFalse(task.has("TaskEnabled"));
      assertTrue(commands.get(i).enabled());
      if (commands.get(i) instanceof RogueResonanceOnAdd46.Modifier m) {
        assertEquals(
            task.getAsJsonObject("TargetType").get("Alias").getAsString(), m.targetAlias());
        assertEquals(
            task.getAsJsonObject("ModifierName").get("Value").getAsString(), m.literalName());
      }
    }
    var buffs =
        RogueMazeBuffTable46.read(
            java.util.HexFormat.of()
                .parseHex(
                    fixture("rogue-maze-buff-table-4.6.json")
                        .getAsJsonArray("records")
                        .get(0)
                        .getAsJsonObject()
                        .get("rawHex")
                        .getAsString()));
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
    var definition = RogueResonanceClassicEvents46.find(id).orElseThrow();
    var template = definition.newTemplate();
    int base = Integer.parseInt(definition.sourceAbility().substring("StageAbility_".length()));
    for (var p :
        RogueResonanceStageParams46.create(
            buffs.get(new RogueMazeBuffTable46.Key(base, 1)).parameters().stream()
                .mapToLong(Long::longValue)
                .toArray())) assertTrue(template.write(p.key(), p.raw()));
    var context = new RogueResonanceContext46(null, List.of(), template, null);
    var calls = new ArrayList<String>();
    var stage = new RogueBattleEventTable46.WaveStage(1001, new Object());
    var frames = new RogueBattleEventLifecycle46.Frames(List.of());
    var bound =
        new RogueResonanceOnAdd46.Bindings(
            context,
            (key, level) -> {
              calls.add("query");
              return buffs.get(new RogueMazeBuffTable46.Key(key, level));
            },
            events,
            1001,
            key -> {
              assertEquals(1001, key);
              return stage;
            },
            (request, before) -> {
              assertEquals(3, request.team());
              assertEquals(1, request.totalDamageTeam());
              assertEquals(id, request.eventId());
              assertSame(stage, request.prepared().waveStage());
              assertEquals(definition.evaluate(context), request.parameters());
              assertEquals(definition.parameterBindings().size(), request.parameters().size());
              assertThrows(UnsupportedOperationException.class, () -> request.parameters().clear());
              calls.add("create");
              var entity = new Object();
              before.accept(entity);
              assertEquals(0, frames.depth());
              return entity;
            },
            frames,
            m -> new Effect(() -> calls.add(m.literalName())));
    var compiled = RogueResonanceOnAdd46.compile(commands, bound);
    assertTrue(calls.isEmpty());
    compiled.sequence().begin();
    var expected = new ArrayList<String>();
    for (var query : RogueResonanceMazeQueries46.classicQueries(id)) expected.add("query");
    expected.add("create");
    for (var command : commands)
      if (command instanceof RogueResonanceOnAdd46.Modifier m) expected.add(m.literalName());
    assertEquals(expected, calls);
    assertEquals(RogueTaskSequence46.SUCCESS, compiled.sequence().state());
    compiled.sequence().begin();
    assertEquals(expected, calls);
    assertNull(frames.current());
  }

  private static RogueResonanceOnAdd46.Modifier modifier(Boolean enabled, String name) {
    return new RogueResonanceOnAdd46.Modifier(enabled, "Caster", name);
  }

  private static RogueResonanceOnAdd46.Bindings empty(
      RogueResonanceOnAdd46.ModifierFactory factory) {
    return new RogueResonanceOnAdd46.Bindings(null, null, null, 0, null, null, null, factory);
  }

  @Test
  void disabledCommandsDoNotResolveBindingsOrAllocateAnyTask() {
    var compiled =
        RogueResonanceOnAdd46.compile(
            List.of(
                new RogueResonanceOnAdd46.Query(false, List.of(), 0),
                new RogueResonanceOnAdd46.Create(false, 41001, 3, 1, List.of()),
                modifier(false, "m")),
            empty(null));
    assertTrue(compiled.enabledCommands().isEmpty());
    assertTrue(compiled.sequence().children().isEmpty());
    compiled.sequence().begin();
    assertEquals(RogueTaskSequence46.SUCCESS, compiled.sequence().state());
  }

  @Test
  void absentAndTrueFlagsAllocateInOrderButFalseFlagsAreSkipped() {
    var allocated = new ArrayList<String>();
    var compiled =
        RogueResonanceOnAdd46.compile(
            List.of(modifier(null, "a"), modifier(false, "b"), modifier(true, "c")),
            empty(
                m -> {
                  allocated.add(m.literalName());
                  return new Effect(() -> {});
                }));
    assertEquals(List.of("a", "c"), allocated);
    assertEquals(2, compiled.sequence().children().size());
  }

  @Test
  void preflightChecksLaterMissingBindingsBeforeEarlierFactoryAllocation() {
    var allocated = new java.util.concurrent.atomic.AtomicInteger();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceOnAdd46.compile(
                List.of(modifier(null, "m"), new RogueResonanceOnAdd46.Query(null, List.of(), 0)),
                empty(
                    m -> {
                      allocated.incrementAndGet();
                      return new Effect(() -> {});
                    })));
    assertEquals(0, allocated.get());
  }

  @Test
  void nullModifierFactoryResultCannotEraseAnOriginalTask() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceOnAdd46.compile(List.of(modifier(null, "m")), empty(m -> null)));
  }

  @Test
  void factoryExceptionsPropagateBeforeAnyExecution() {
    var problem = new IllegalStateException("factory");
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class,
            () ->
                RogueResonanceOnAdd46.compile(
                    List.of(modifier(null, "m")),
                    empty(
                        m -> {
                          throw problem;
                        }))));
  }

  @Test
  void missingEventDoesNotSkipTheFollowingModifierTask() {
    var calls = new ArrayList<String>();
    var base = bindings(41008, calls, m -> new Effect(() -> calls.add("modifier")));
    var bound =
        new RogueResonanceOnAdd46.Bindings(
            base.context(),
            base.rows(),
            Map.of(),
            0,
            null,
            (r, b) -> {
              fail("No event factory for missing row");
              return null;
            },
            null,
            base.modifiers());
    var compiled =
        RogueResonanceOnAdd46.compile(RogueResonanceOnAdd46.classicCommands(41008), bound);
    compiled.sequence().begin();
    assertEquals("modifier", calls.getLast());
    assertFalse(calls.contains("create"));
    assertEquals(RogueTaskSequence46.SUCCESS, compiled.sequence().state());
  }

  @Test
  void nullEventEntityDoesNotSkipTheFollowingModifierTask() {
    var calls = new ArrayList<String>();
    var base = bindings(41001, calls, m -> new Effect(() -> calls.add("modifier")));
    var bound =
        new RogueResonanceOnAdd46.Bindings(
            base.context(),
            base.rows(),
            base.events(),
            0,
            null,
            (r, b) -> {
              calls.add("create");
              return null;
            },
            null,
            base.modifiers());
    var compiled =
        RogueResonanceOnAdd46.compile(RogueResonanceOnAdd46.classicCommands(41001), bound);
    compiled.sequence().begin();
    assertEquals(List.of("create", "modifier"), calls.subList(calls.size() - 2, calls.size()));
  }

  @Test
  void creationExceptionRetainsDoneChildStateAndStopsLaterTasks() {
    var problem = new IllegalStateException("create");
    var calls = new ArrayList<String>();
    var base = bindings(41001, calls, m -> new Effect(() -> calls.add("modifier")));
    var bound =
        new RogueResonanceOnAdd46.Bindings(
            base.context(),
            base.rows(),
            base.events(),
            0,
            null,
            (r, b) -> {
              calls.add("create");
              throw problem;
            },
            null,
            base.modifiers());
    var compiled =
        RogueResonanceOnAdd46.compile(RogueResonanceOnAdd46.classicCommands(41001), bound);
    assertSame(problem, assertThrows(IllegalStateException.class, compiled.sequence()::begin));
    assertEquals(RogueTaskSequence46.SUCCESS, compiled.sequence().children().get(1).state());
    assertEquals(RogueTaskSequence46.READY, compiled.sequence().children().get(2).state());
    assertFalse(calls.contains("modifier"));
    compiled.sequence().begin();
    assertEquals("modifier", calls.getLast());
    assertEquals(1, calls.stream().filter("create"::equals).count());
  }

  @Test
  void modifierTaskCanWaitAndCompleteOnALaterTick() {
    var ticks = new ArrayList<Float>();
    var waiting =
        new Effect(() -> {}) {
          public void begin() {
            state = RogueTaskSequence46.EXECUTING;
          }

          public void tick(float delta) {
            ticks.add(delta);
            if (delta > 0) state = RogueTaskSequence46.SUCCESS;
          }
        };
    var compiled = RogueResonanceOnAdd46.compile(List.of(modifier(null, "m")), empty(m -> waiting));
    compiled.sequence().begin();
    assertEquals(List.of(0f), ticks);
    assertEquals(RogueTaskSequence46.EXECUTING, compiled.sequence().state());
    compiled.sequence().tick(.25f);
    assertEquals(List.of(0f, .25f), ticks);
    assertEquals(RogueTaskSequence46.SUCCESS, compiled.sequence().state());
  }

  @Test
  void modifierFailureStopsLaterCommands() {
    var calls = new ArrayList<String>();
    var compiled =
        RogueResonanceOnAdd46.compile(
            List.of(modifier(null, "fail"), modifier(null, "next")),
            empty(
                m ->
                    new Effect(() -> calls.add(m.literalName())) {
                      public void begin() {
                        super.begin();
                        if (m.literalName().equals("fail")) state = RogueTaskSequence46.FAIL;
                      }
                    }));
    compiled.sequence().begin();
    assertEquals(List.of("fail"), calls);
    assertEquals(RogueTaskSequence46.FAIL, compiled.sequence().state());
  }

  @Test
  void resetReconstructsQueryAndCreationStateAndResetsModifier() {
    var calls = new ArrayList<String>();
    var effect = new Effect(() -> calls.add("modifier"));
    var compiled =
        RogueResonanceOnAdd46.compile(
            RogueResonanceOnAdd46.classicCommands(41001), bindings(41001, calls, m -> effect));
    compiled.sequence().begin();
    int count = calls.size();
    compiled.sequence().reset();
    assertTrue(
        compiled.sequence().children().stream()
            .allMatch(t -> t.state() == RogueTaskSequence46.READY));
    compiled.sequence().begin();
    assertEquals(count * 2, calls.size());
  }

  @Test
  void disposalReleasesExplicitModifierAndRejectsFurtherExecution() {
    var disposed = new java.util.concurrent.atomic.AtomicInteger();
    var compiled =
        RogueResonanceOnAdd46.compile(
            List.of(modifier(null, "m")),
            empty(
                m ->
                    new Effect(() -> {}) {
                      public void dispose() {
                        disposed.incrementAndGet();
                      }
                    }));
    compiled.sequence().dispose();
    compiled.sequence().dispose();
    assertEquals(1, disposed.get());
    assertThrows(IllegalStateException.class, compiled.sequence()::begin);
  }

  @Test
  void sourceAndCompiledListsRemainImmutableAfterCallerMutation() {
    var commands = new ArrayList<RogueResonanceOnAdd46.Command>(List.of(modifier(null, "m")));
    var compiled = RogueResonanceOnAdd46.compile(commands, empty(m -> new Effect(() -> {})));
    commands.clear();
    assertEquals(1, compiled.enabledCommands().size());
    assertThrows(UnsupportedOperationException.class, () -> compiled.enabledCommands().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> RogueResonanceOnAdd46.classicCommands(41001).clear());
  }

  @Test
  void invalidFiniteCommandListIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> RogueResonanceOnAdd46.compile(null, empty(null)));
    assertThrows(
        IllegalArgumentException.class, () -> RogueResonanceOnAdd46.compile(List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceOnAdd46.compile(
                java.util.Arrays.asList((RogueResonanceOnAdd46.Command) null), empty(null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceOnAdd46.compile(
                java.util.Collections.nCopies(513, modifier(false, "m")), empty(null)));
  }

  @Test
  void unknownClassicEventAndUnmatchedModifierTargetAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> RogueResonanceOnAdd46.classicCommands(41010));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueResonanceOnAdd46.Modifier(null, "Enemy", "m"));
    assertThrows(IllegalArgumentException.class, () -> modifier(null, null));
  }

  @Test
  void creationRequestCannotUseAnotherPreparedEventOrInvalidTeams() {
    var prepared = new RogueBattleEventTable46.Prepared(event(41001), null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueResonanceOnAdd46.CreationRequest(41002, 3, 1, prepared, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueResonanceOnAdd46.CreationRequest(41001, 6, 1, prepared, Map.of()));
  }

  @Test
  void bindingsFreezeTheEventMapWithoutInstallingIt() {
    var events = new java.util.HashMap<Integer, RogueBattleEventTable46.Config>();
    events.put(41001, event(41001));
    var bound = new RogueResonanceOnAdd46.Bindings(null, null, events, 0, null, null, null, null);
    events.clear();
    assertEquals(1, bound.events().size());
    assertThrows(UnsupportedOperationException.class, () -> bound.events().clear());
  }
}
