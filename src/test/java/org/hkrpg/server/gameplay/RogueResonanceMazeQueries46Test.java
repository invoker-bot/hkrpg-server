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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueResonanceMazeQueries46Test {
  private static final long ONE = 1L << 33;

  private static int key(String name) {
    return RogueResonanceVariables46.nameKey(name);
  }

  private static RogueMazeBuffTable46.Row row(int id, int level, List<Long> values) {
    return new RogueMazeBuffTable46.Row(id, level, null, null, 0, values, 0, 1, 0);
  }

  @Test
  void omittedLevelUsesMatchedConstructorOneWhileExplicitZeroStaysZero() {
    assertEquals(
        new RogueResonanceMazeQueries46.Key(612625, 1, 1),
        RogueResonanceMazeQueries46.sourceKey(612625, null, 1));
    assertEquals(
        new RogueResonanceMazeQueries46.Key(612625, 0, 1),
        RogueResonanceMazeQueries46.sourceKey(612625, 0, 1));
  }

  @Test
  void successfulQueryWritesTheExactRawParameterAtOneBasedIndex() {
    var cells = RogueResonanceVariables46.fromSourceDefaults(Map.of(key("talent"), 0L), null);
    var context = new RogueResonanceContext46(null, List.of(), cells, null);
    var result =
        RogueResonanceMazeQueries46.execute(
            List.of(
                new RogueResonanceMazeQueries46.Binding(
                    "talent", new RogueResonanceMazeQueries46.Key(612626, 1, 1))),
            0,
            (id, level) -> row(id, level, List.of(4294967300L, ONE)),
            context);
    assertEquals(4294967300L, cells.read(key("talent")));
    assertEquals(1, result.writeRequests());
    assertTrue(result.failures().isEmpty());
  }

  @Test
  void allNineOriginalQueryListsAreCompiledWithoutInventedEruditionQueries() {
    assertEquals(
        List.of("ADF_612821_1", "ADF_612822_1", "ADF_612823_1"),
        RogueResonanceMazeQueries46.classicQueries(41009).stream()
            .map(RogueResonanceMazeQueries46.Binding::name)
            .toList());
    assertEquals(
        74,
        java.util.stream.IntStream.rangeClosed(41001, 41009)
            .map(e -> RogueResonanceMazeQueries46.classicQueries(e).size())
            .sum());
  }

  private static JsonObject fixture(String name) throws Exception {
    try (var stream =
        RogueResonanceMazeQueries46Test.class.getResourceAsStream("/fixtures/" + name)) {
      assertNotNull(stream);
      return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  private static JsonObject original(int event) throws Exception {
    return fixture("rogue-resonance-maze-query-4.6.json")
        .getAsJsonArray("classicQueries")
        .asList()
        .stream()
        .map(e -> e.getAsJsonObject())
        .filter(e -> e.get("eventId").getAsInt() == event)
        .findFirst()
        .orElseThrow();
  }

  private static Map<RogueMazeBuffTable46.Key, RogueMazeBuffTable46.Row> originalRows()
      throws Exception {
    var record =
        fixture("rogue-maze-buff-table-4.6.json")
            .getAsJsonArray("records")
            .get(0)
            .getAsJsonObject();
    return RogueMazeBuffTable46.read(HexFormat.of().parseHex(record.get("rawHex").getAsString()));
  }

  @ParameterizedTest
  @ValueSource(ints = {41001, 41002, 41003, 41004, 41005, 41006, 41007, 41008, 41009})
  void allOriginalNamesIndicesOmittedLevelsAndOrderingMatchTheCompiledQueries(int event)
      throws Exception {
    var source = original(event);
    assertEquals(0, source.get("scope").getAsInt());
    var values = source.getAsJsonArray("queries");
    var compiled = RogueResonanceMazeQueries46.classicQueries(event);
    assertEquals(values.size(), compiled.size());
    for (int i = 0; i < values.size(); i++) {
      var q = values.get(i).getAsJsonObject();
      assertFalse(q.get("levelPresent").getAsBoolean());
      assertEquals(q.get("name").getAsString(), compiled.get(i).name());
      assertEquals(
          RogueResonanceMazeQueries46.sourceKey(
              q.get("id").getAsInt(), null, q.get("index").getAsInt()),
          compiled.get(i).query());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {41001, 41002, 41003, 41004, 41005, 41006, 41007, 41008, 41009})
  void everyOriginalQueryRunsThroughExactSourceRowTemplateAndEventParameters(int event)
      throws Exception {
    var rows = originalRows();
    var definition = RogueResonanceClassicEvents46.find(event).orElseThrow();
    var template = definition.newTemplate();
    int base = Integer.parseInt(definition.sourceAbility().substring("StageAbility_".length()));
    for (var parameter :
        RogueResonanceStageParams46.create(
            rows.get(new RogueMazeBuffTable46.Key(base, 1)).parameters().stream()
                .mapToLong(Long::longValue)
                .toArray())) assertTrue(template.write(parameter.key(), parameter.raw()));
    var context = new RogueResonanceContext46(null, List.of(), template, null);
    var bindings = RogueResonanceMazeQueries46.classicQueries(event);
    var calls = new ArrayList<RogueMazeBuffTable46.Key>();
    var task = new RogueResonanceMazeQueries46.Task(bindings, 0);
    var result =
        task.execute(
            (id, level) -> {
              assertEquals(0x9999, task.state());
              var identity = new RogueMazeBuffTable46.Key(id, level);
              calls.add(identity);
              return rows.get(identity);
            },
            context);
    assertEquals(bindings.size(), result.writeRequests());
    assertTrue(result.failures().isEmpty());
    assertEquals(
        bindings.stream()
            .map(b -> new RogueMazeBuffTable46.Key(b.query().id(), b.query().level()))
            .toList(),
        calls);
    var values = definition.evaluate(context);
    for (var binding : bindings) {
      var q = binding.query();
      long raw =
          rows.get(new RogueMazeBuffTable46.Key(q.id(), q.level())).parameters().get(q.index() - 1);
      assertEquals(raw, template.read(key(binding.name())));
      assertEquals(Long.valueOf(raw), values.get(key(binding.name())));
    }
    assertEquals(Long.valueOf(ONE), values.get(key("FirstBattle_Flag")));
    if (event == 41008) assertEquals(Long.valueOf(0), values.get(key("Egg_MaxLayer_612720")));
    if (event == 41009) {
      assertEquals(Long.valueOf(0), values.get(key("ADF_612822_2")));
      assertEquals(Long.valueOf(0), values.get(key("ADF_612822_3")));
    }
  }

  private static RogueResonanceMazeQueries46.Binding binding(String name, int id, int index) {
    return new RogueResonanceMazeQueries46.Binding(
        name, new RogueResonanceMazeQueries46.Key(id, 1, index));
  }

  private static RogueResonanceContext46 context(RogueResonanceVariables46 cells) {
    return new RogueResonanceContext46(null, List.of(), cells, null);
  }

  @Test
  void failedReadsLeaveEarlierValuesAndContinueToLaterQueries() {
    var names = List.of("first", "missing", "null", "empty", "zero", "badIndex", "throws", "last");
    var defaults = new java.util.HashMap<Integer, Long>();
    names.forEach(n -> defaults.put(key(n), 2 * ONE));
    var cells = RogueResonanceVariables46.fromSourceDefaults(defaults, null);
    var queries =
        List.of(
            binding("first", 1, 1),
            binding("missing", 2, 1),
            binding("null", 3, 1),
            binding("empty", 4, 1),
            binding("zero", 5, 0),
            binding("badIndex", 6, 2),
            binding("throws", 7, 1),
            binding("last", 8, 1));
    var calls = new ArrayList<Integer>();
    var result =
        RogueResonanceMazeQueries46.execute(
            queries,
            0,
            (id, level) -> {
              calls.add(id);
              if (id == 2) return null;
              if (id == 7) throw new IllegalArgumentException("unavailable");
              return row(id, level, id == 3 ? null : id == 4 ? List.of() : List.of(3 * ONE));
            },
            context(cells));
    assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), calls);
    assertEquals(2, result.writeRequests());
    assertEquals(
        List.of("missing", "null", "empty", "zero", "badIndex", "throws"),
        result.failures().stream().map(RogueResonanceMazeQueries46.Failure::name).toList());
    assertEquals(
        List.of(
            RogueResonanceMazeQueries46.Reason.MISSING_ROW,
            RogueResonanceMazeQueries46.Reason.MISSING_PARAMETERS,
            RogueResonanceMazeQueries46.Reason.INVALID_INDEX,
            RogueResonanceMazeQueries46.Reason.INVALID_INDEX,
            RogueResonanceMazeQueries46.Reason.INVALID_INDEX,
            RogueResonanceMazeQueries46.Reason.SOURCE_READ_FAILED),
        result.failures().stream().map(RogueResonanceMazeQueries46.Failure::reason).toList());
    for (String name : names)
      assertEquals(
          name.equals("first") || name.equals("last") ? 3 * ONE : 2 * ONE, cells.read(key(name)));
    assertThrows(UnsupportedOperationException.class, () -> result.failures().clear());
  }

  @Test
  void destinationFailureAbortsWithoutRollingBackPriorWritesOrReadingLaterRows() {
    var cells =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(key("first"), 0L, key("last"), 0L), null);
    var calls = new ArrayList<Integer>();
    var task =
        new RogueResonanceMazeQueries46.Task(
            List.of(binding("first", 1, 1), binding("missing", 2, 1), binding("last", 3, 1)), 0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            task.execute(
                (id, level) -> {
                  calls.add(id);
                  return row(id, level, List.of(ONE));
                },
                context(cells)));
    assertEquals(List.of(1, 2), calls);
    assertEquals(ONE, cells.read(key("first")));
    assertEquals(0, cells.read(key("last")));
    assertEquals(0x9999, task.state());
  }

  @Test
  void explicitLevelZeroDoesNotFallBackToOneOrHeldUpgradeLevel() {
    var cells = RogueResonanceVariables46.fromSourceDefaults(Map.of(key("x"), 0L), null);
    var calls = new ArrayList<Integer>();
    var result =
        RogueResonanceMazeQueries46.execute(
            List.of(
                new RogueResonanceMazeQueries46.Binding(
                    "x", RogueResonanceMazeQueries46.sourceKey(1, 0, 1))),
            0,
            (id, level) -> {
              calls.add(level);
              return level == 1 ? row(id, level, List.of(ONE)) : null;
            },
            context(cells));
    assertEquals(List.of(0), calls);
    assertEquals(0, cells.read(key("x")));
    assertEquals(1, result.failures().size());
  }

  @Test
  void emptyAndNullQueriesFinishBeforeRequiringSourcesOrDestinations() {
    for (var task :
        List.of(
            new RogueResonanceMazeQueries46.Task(null, 0),
            new RogueResonanceMazeQueries46.Task(List.of(), 0))) {
      assertEquals(0x7777, task.state());
      assertEquals(0, task.execute(null, null).writeRequests());
      assertEquals(0x9999, task.state());
    }
    assertEquals(
        new RogueResonanceMazeQueries46.Result(0, List.of()),
        RogueResonanceMazeQueries46.execute(null, 0, null, null));
  }

  @Test
  void explicitScopeDoesNotWriteIntoHigherPriorityTemporaryFrame() {
    var temporary = RogueResonanceVariables46.fromSourceDefaults(Map.of(key("x"), 0L), null);
    var template = RogueResonanceVariables46.fromSourceDefaults(Map.of(key("x"), 0L), null);
    var selected = new RogueResonanceContext46(temporary, List.of(), template, null);
    var q = List.of(binding("x", 1, 1));
    RogueResonanceMazeQueries46.execute(
        q, 1, (id, level) -> row(id, level, List.of(ONE)), selected);
    assertEquals(0, temporary.read(key("x")));
    assertEquals(ONE, template.read(key("x")));
    RogueResonanceMazeQueries46.execute(
        q, 0, (id, level) -> row(id, level, List.of(2 * ONE)), selected);
    assertEquals(2 * ONE, temporary.read(key("x")));
    assertEquals(ONE, template.read(key("x")));
  }

  @Test
  void writesUseExistingCellBoundsAndNotifyEvenWhenTheClippedValueIsUnchanged() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var cells =
        new RogueResonanceVariables46(
            Map.of(key("x"), new RogueResonanceVariables46.Value(ONE, 0, ONE)), changes::add);
    var result =
        RogueResonanceMazeQueries46.execute(
            List.of(binding("x", 1, 1)),
            0,
            (id, level) -> row(id, level, List.of(2 * ONE)),
            context(cells));
    assertEquals(1, result.writeRequests());
    assertEquals(ONE, cells.read(key("x")));
    assertEquals(List.of(new RogueResonanceVariables46.Change(key("x"), ONE, ONE)), changes);
  }

  @Test
  void exactDuplicateNamesAndFiniteCapacityAreRejectedBeforeSourceReads() {
    var calls = new AtomicInteger();
    RogueResonanceMazeQueries46.Rows rows =
        (id, level) -> {
          calls.incrementAndGet();
          return row(id, level, List.of(ONE));
        };
    var cells = RogueResonanceVariables46.fromSourceDefaults(Map.of(key("x"), 0L), null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceMazeQueries46.execute(
                List.of(binding("x", 1, 1), binding("x", 2, 1)), 0, rows, context(cells)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceMazeQueries46.execute(
                java.util.Collections.nCopies(256, binding("x", 1, 1)), 0, rows, context(cells)));
    assertEquals(0, calls.get());
  }

  @Test
  void mismatchedRowIdentityIsReportedAndDoesNotWriteAnotherBuffsValue() {
    var cells = RogueResonanceVariables46.fromSourceDefaults(Map.of(key("x"), 0L), null);
    var result =
        RogueResonanceMazeQueries46.execute(
            List.of(binding("x", 1, 1)),
            0,
            (id, level) -> row(2, level, List.of(ONE)),
            context(cells));
    assertEquals(0, result.writeRequests());
    assertEquals(0, cells.read(key("x")));
    assertEquals(
        RogueResonanceMazeQueries46.Reason.SOURCE_READ_FAILED,
        result.failures().getFirst().reason());
  }

  @Test
  void nativeQueryReadCatchDoesNotSwallowJavaErrors() {
    var cells = RogueResonanceVariables46.fromSourceDefaults(Map.of(key("x"), 0L), null);
    assertThrows(
        AssertionError.class,
        () ->
            RogueResonanceMazeQueries46.execute(
                List.of(binding("x", 1, 1)),
                0,
                (id, level) -> {
                  throw new AssertionError("caller");
                },
                context(cells)));
  }

  @Test
  void unknownClassicEventsDoNotReceiveInventedQueries() {
    for (int event : new int[] {0, 41000, 41010, 96121, 96123, Integer.MAX_VALUE})
      assertTrue(RogueResonanceMazeQueries46.classicQueries(event).isEmpty());
    assertThrows(
        UnsupportedOperationException.class,
        () -> RogueResonanceMazeQueries46.classicQueries(41007).clear());
  }

  @Test
  void negativeKeysUnsupportedNamesAndScopesAreRejectedAsFiniteProjectInputs() {
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceMazeQueries46.Key(-1, 1, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceMazeQueries46.Key(1, -1, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceMazeQueries46.Key(1, 1, -1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RogueResonanceMazeQueries46.Binding(
                "", new RogueResonanceMazeQueries46.Key(1, 1, 1)));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceMazeQueries46.Task(List.of(), 10));
  }
}
