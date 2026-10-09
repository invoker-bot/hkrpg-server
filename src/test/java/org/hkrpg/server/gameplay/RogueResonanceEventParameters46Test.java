package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueResonanceEventParameters46Test {
  private static final long ONE = 1L << 33;

  private static RogueResonanceEventParameters46.Binding reference(String name, int key) {
    return new RogueResonanceEventParameters46.Binding(
        name,
        RogueResonanceDynamicCell46.Scalar.fromPostfix(
            0, HexFormat.of().parseHex("010011"), new long[0], new int[] {key}));
  }

  private static RogueResonanceContext46 context(Map<Integer, Long> source) {
    return new RogueResonanceContext46(
        null, List.of(), RogueResonanceVariables46.fromSourceDefaults(source, null), null);
  }

  private static JsonObject profile() throws Exception {
    try (var input =
        RogueResonanceEventParameters46Test.class.getResourceAsStream(
            "/fixtures/rogue-resonance-event-params-4.6.json")) {
      assertNotNull(input);
      return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  private static List<RogueResonanceEventParameters46.Binding> bindings(JsonObject source) {
    var result = new ArrayList<RogueResonanceEventParameters46.Binding>();
    for (var row : source.getAsJsonArray("bindings")) {
      var pair = row.getAsJsonArray();
      var value = pair.get(1).getAsJsonObject();
      assertEquals(1, value.get("mode").getAsInt());
      assertEquals("010011", value.get("rawPostfixBytes").getAsString());
      assertEquals(1, value.getAsJsonArray("variableKeys").size());
      result.add(
          reference(
              pair.get(0).getAsString(), value.getAsJsonArray("variableKeys").get(0).getAsInt()));
    }
    return result;
  }

  @Test
  void explicitBindingReadsPrefixedSourceAndKeepsOriginalDestinationName() {
    var entries =
        RogueResonanceStageParams46.create(new long[] {ONE, 2 * ONE, 3 * ONE, 4 * ONE, ONE / 5});
    var source = new HashMap<Integer, Long>();
    for (var entry : entries) source.put(entry.key(), entry.raw());
    int output = RogueResonanceVariables46.nameKey("ADF_5");
    var result =
        RogueResonanceEventParameters46.evaluate(
            List.of(reference("ADF_5", entries[4].key())), context(source));
    assertEquals(Long.valueOf(ONE / 5), result.get(output));
    assertFalse(result.containsKey(entries[4].key()));
    assertEquals(5, source.size());
  }

  @Test
  void expressionsReadSourceLazilyOncePerBindingInInputOrder() {
    var reads = new ArrayList<Integer>();
    var count = new AtomicInteger();
    RogueResonanceLookup46 source =
        new RogueResonanceLookup46() {
          public RogueResonanceVariables46 select(int scope, int key) {
            throw new AssertionError();
          }

          public long read(int key) {
            reads.add(key);
            return count.incrementAndGet() * ONE;
          }
        };
    var result =
        RogueResonanceEventParameters46.evaluate(
            List.of(reference("First", 42), reference("Second", 42), reference("Third", 43)),
            source);
    assertEquals(List.of(42, 42, 43), reads);
    assertEquals(
        Map.of(
            RogueResonanceVariables46.nameKey("First"),
            ONE,
            RogueResonanceVariables46.nameKey("Second"),
            2 * ONE,
            RogueResonanceVariables46.nameKey("Third"),
            3 * ONE),
        result);
  }

  @Test
  void original41007TransferFeedsPropertyExpressionRoundAndFloorChain() throws Exception {
    var selected =
        profile().getAsJsonArray("classicEventBindings").asList().stream()
            .map(e -> e.getAsJsonObject())
            .filter(e -> e.get("eventId").getAsInt() == 41007)
            .findFirst()
            .orElseThrow();
    assertEquals("StageAbility_612620", selected.get("abilityName").getAsString());
    assertEquals(1122, selected.get("taskId").getAsInt());
    var values = new HashMap<Integer, Long>();
    for (var declaration : selected.getAsJsonArray("sourceDeclarations")) {
      var d = declaration.getAsJsonObject();
      assertEquals(0, d.get("rawReadType").getAsInt());
      values.put(d.get("key").getAsInt(), d.get("fixedSignedRaw").getAsLong());
    }
    // Explicit raw inputs only. This test does not recover real MazeBuff query values.
    long coefficient = ONE / 5;
    for (var entry :
        RogueResonanceStageParams46.create(
            new long[] {ONE / 4, 3 * ONE, 5 * ONE, 2 * ONE, coefficient, 4 * ONE, ONE / 2}))
      values.put(entry.key(), entry.raw());
    var parameters = RogueResonanceEventParameters46.evaluate(bindings(selected), context(values));
    assertEquals(Long.valueOf(coefficient), parameters.get(909827949));
    assertEquals(18, parameters.size());
    var locals =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(-978103959, 0L, 1555818884, 0L, -38167523, 0L, -1803181471, 0L), null);
    var eventValues = RogueResonanceVariables46.fromSourceDefaults(parameters, null);
    var eventContext = new RogueResonanceContext46(null, List.of(eventValues), locals, null);
    assertTrue(
        new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12)
            .executeInContext(eventContext, 0, List.of(Map.of(12, 100 * ONE))));
    var product =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010000000411"), new long[] {100 * ONE}, new int[] {909827949});
    assertEquals(
        1,
        new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", product)
            .executeInContext(eventContext, List.of(locals), false));
    new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime")
        .executeInContext(eventContext, 0);
    assertEquals(20 * ONE, locals.read(1555818884));
    var quotient =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("01000101010205100111"),
            new long[0],
            new int[] {1776456860, -978103959, 1555818884});
    assertEquals(
        1,
        new RogueResonanceWrite46.ExpressionTask(2791, "AttackTime", quotient)
            .executeInContext(eventContext, List.of(locals), false));
    assertEquals(5 * ONE, locals.read(-38167523));
  }

  @ParameterizedTest
  @ValueSource(ints = {41001, 41006, 41002, 41003, 41004, 41005, 41007})
  void everyClassicSourceDictionaryPreservesEachOriginalNameAndReference(int eventId)
      throws Exception {
    var record =
        profile().getAsJsonArray("classicEventBindings").asList().stream()
            .map(e -> e.getAsJsonObject())
            .filter(e -> e.get("eventId").getAsInt() == eventId)
            .findFirst()
            .orElseThrow();
    var inputs = new HashMap<Integer, Long>();
    var expected = new HashMap<Integer, Long>();
    var keys = new ArrayList<Integer>();
    var supplied = bindings(record);
    for (int i = 0; i < supplied.size(); i++) {
      var pair = record.getAsJsonArray("bindings").get(i).getAsJsonArray();
      int inputKey = pair.get(1).getAsJsonObject().getAsJsonArray("variableKeys").get(0).getAsInt();
      long raw = i % 2 == 0 ? (i + 1) * ONE + 2 : -(i + 1) * ONE - 2;
      assertNull(inputs.put(inputKey, raw));
      assertNull(expected.put(supplied.get(i).key(), raw));
      keys.add(supplied.get(i).key());
    }
    var actual = RogueResonanceEventParameters46.evaluate(supplied, context(inputs));
    assertEquals(expected, actual);
    assertEquals(keys, new ArrayList<>(actual.keySet()));
    assertEquals(1122, record.get("taskId").getAsInt());
  }

  @Test
  void absentAndEmptyDictionariesDoNotReadOrRequireSourceContext() {
    assertEquals(Map.of(), RogueResonanceEventParameters46.evaluate(null, null));
    assertEquals(Map.of(), RogueResonanceEventParameters46.evaluate(List.of(), null));
  }

  @Test
  void nonemptyEvenFixedDictionaryRequiresSourceContext() {
    var value =
        new RogueResonanceEventParameters46.Binding(
            "Fixed", RogueResonanceDynamicCell46.Scalar.fixed(ONE));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceEventParameters46.evaluate(List.of(value), null));
  }

  @Test
  void fixedValuesDoNotReadNumericSourceAndPreserveSignedStandardWords() {
    var bindings = new ArrayList<RogueResonanceEventParameters46.Binding>();
    long[] raw = {0, ONE, -ONE, Long.MIN_VALUE, Long.MAX_VALUE & ~1L};
    for (int i = 0; i < raw.length; i++)
      bindings.add(
          new RogueResonanceEventParameters46.Binding(
              "Fixed" + i, RogueResonanceDynamicCell46.Scalar.fixed(raw[i])));
    RogueResonanceLookup46 source =
        (scope, key) -> {
          throw new AssertionError("Unused numeric source");
        };
    var actual = RogueResonanceEventParameters46.evaluate(bindings, source);
    for (int i = 0; i < raw.length; i++) assertEquals(raw[i], actual.get(bindings.get(i).key()));
  }

  @Test
  void sourceLookupIsNotReplacedByEarlierOutputParameters() {
    int first = RogueResonanceVariables46.nameKey("First");
    var actual =
        RogueResonanceEventParameters46.evaluate(
            List.of(
                new RogueResonanceEventParameters46.Binding(
                    "First", RogueResonanceDynamicCell46.Scalar.fixed(ONE)),
                reference("Second", first)),
            context(Map.of(first, 9 * ONE)));
    assertEquals(ONE, actual.get(first));
    assertEquals(9 * ONE, actual.get(RogueResonanceVariables46.nameKey("Second")));
  }

  @Test
  void missingPrefixedSourceIsNotSatisfiedByDestinationName() {
    int source = RogueResonanceVariables46.nameKey("#ADF_5");
    int output = RogueResonanceVariables46.nameKey("ADF_5");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceEventParameters46.evaluate(
                List.of(reference("ADF_5", source)), context(Map.of(output, ONE))));
  }

  @Test
  void failureKeepsEarlierSourceSideEffectsButStopsLaterReads() {
    var reads = new ArrayList<Integer>();
    var marker = new IllegalStateException("source failure");
    RogueResonanceLookup46 source =
        new RogueResonanceLookup46() {
          public RogueResonanceVariables46 select(int scope, int key) {
            throw new AssertionError();
          }

          public long read(int key) {
            reads.add(key);
            if (key == 43) throw marker;
            return ONE;
          }
        };
    assertSame(
        marker,
        assertThrows(
            IllegalStateException.class,
            () ->
                RogueResonanceEventParameters46.evaluate(
                    List.of(
                        reference("First", 42), reference("Second", 43), reference("Third", 44)),
                    source)));
    assertEquals(List.of(42, 43), reads);
  }

  @Test
  void extendedSourceResultStopsBeforeLaterBinding() {
    var reads = new ArrayList<Integer>();
    RogueResonanceLookup46 source =
        new RogueResonanceLookup46() {
          public RogueResonanceVariables46 select(int scope, int key) {
            throw new AssertionError();
          }

          public long read(int key) {
            reads.add(key);
            return 1;
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceEventParameters46.evaluate(
                List.of(reference("First", 42), reference("Second", 43)), source));
    assertEquals(List.of(42), reads);
  }

  @Test
  void completedParametersDetachFromSourceAndBindingListAndCannotBeModified() {
    var cells = RogueResonanceVariables46.fromSourceDefaults(Map.of(42, ONE), null);
    var supplied = new ArrayList<>(List.of(reference("First", 42)));
    var result =
        RogueResonanceEventParameters46.evaluate(
            supplied, new RogueResonanceContext46(null, List.of(), cells, null));
    cells.write(42, 2 * ONE);
    supplied.clear();
    assertEquals(Map.of(RogueResonanceVariables46.nameKey("First"), ONE), result);
    assertThrows(UnsupportedOperationException.class, () -> result.put(43, ONE));
  }

  @Test
  void malformedBindingNamesScalarsAndNullEntriesAreRejected() {
    var fixed = RogueResonanceDynamicCell46.Scalar.fixed(0);
    for (String name : new String[] {"", "中文", "a".repeat(65), "a\0b"})
      assertThrows(
          IllegalArgumentException.class,
          () -> new RogueResonanceEventParameters46.Binding(name, fixed));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueResonanceEventParameters46.Binding(null, fixed));
    assertThrows(
        NullPointerException.class, () -> new RogueResonanceEventParameters46.Binding("A", null));
    var supplied = new ArrayList<RogueResonanceEventParameters46.Binding>();
    supplied.add(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceEventParameters46.evaluate(supplied, context(Map.of())));
  }

  @Test
  void repeatedOutputHashIsOutsideFiniteOriginalDictionaryBoundaryBeforeReads() {
    var reads = new AtomicInteger();
    RogueResonanceLookup46 source =
        (scope, key) -> {
          reads.incrementAndGet();
          throw new AssertionError();
        };
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceEventParameters46.evaluate(
                List.of(reference("A", 42), reference("A", 43)), source));
    assertEquals(0, reads.get());
  }

  @Test
  void finiteCapacityAccepts255BindingsAndRejects256BeforeReads() {
    var supplied = new ArrayList<RogueResonanceEventParameters46.Binding>();
    for (int i = 0; i < 255; i++)
      supplied.add(
          new RogueResonanceEventParameters46.Binding(
              "A" + i, RogueResonanceDynamicCell46.Scalar.fixed(i * ONE)));
    var source = context(Map.of());
    assertEquals(255, RogueResonanceEventParameters46.evaluate(supplied, source).size());
    supplied.add(reference("TooMany", 42));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceEventParameters46.evaluate(supplied, source));
  }

  @Test
  void sourceScopeAndAliasCallbackRemainOwnedBySourceContext() {
    var identity = new Object();
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(43, 7 * ONE), null);
    var dynamic =
        RogueResonanceVariables46.withProjectedCells(
            base, Map.of(42, new RogueResonanceTaskCell46(identity, null, null, null)), null);
    var selected = new RogueResonanceContext46(null, List.of(), dynamic, null);
    var calls = new AtomicInteger();
    var source =
        new RogueResonanceAliasContext46(
            selected,
            (current, configuration, output) -> {
              assertSame(identity, configuration);
              assertEquals(RogueResonanceTaskCell46.RESULT_KEY, output);
              calls.incrementAndGet();
              return current.read(43);
            });
    var result = RogueResonanceEventParameters46.evaluate(List.of(reference("A", 42)), source);
    assertEquals(7 * ONE, result.get(RogueResonanceVariables46.nameKey("A")));
    assertEquals(1, calls.get());
  }

  @Test
  void postfixValueDoesNotFallBackToItsUnusedFixedField() {
    var scalar =
        RogueResonanceDynamicCell46.Scalar.fromPostfix(
            1, HexFormat.of().parseHex("010011"), new long[0], new int[] {42});
    var result =
        RogueResonanceEventParameters46.evaluate(
            List.of(new RogueResonanceEventParameters46.Binding("A", scalar)),
            context(Map.of(42, 3 * ONE)));
    assertEquals(3 * ONE, result.get(RogueResonanceVariables46.nameKey("A")));
  }
}
