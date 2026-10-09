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

class RogueResonanceClassicEvents46Test {
  private static JsonObject fixture(String name) throws Exception {
    try (var input =
        RogueResonanceClassicEvents46Test.class.getResourceAsStream("/fixtures/" + name)) {
      assertNotNull(input);
      return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  private static JsonObject source(int event) throws Exception {
    return fixture("rogue-resonance-classic-events-4.6.json")
        .getAsJsonArray("classicEventDefinitions")
        .asList()
        .stream()
        .map(e -> e.getAsJsonObject())
        .filter(e -> e.get("eventId").getAsInt() == event)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void allNineOriginalClassicIdentitiesAreAvailable() {
    assertEquals(
        List.of(41001, 41002, 41003, 41004, 41005, 41006, 41007, 41008, 41009),
        RogueResonanceClassicEvents46.eventIds());
  }

  @Test
  void propagationDefinitionKeepsFixedEggLimitAndFirstBattleSourceDefault() {
    var selected = RogueResonanceClassicEvents46.find(41008);
    assertTrue(selected.isPresent());
    var definition = selected.orElseThrow();
    assertEquals("StageAbility_612720", definition.sourceAbility());
    assertEquals(19, definition.parameterBindings().size());
    var context = new RogueResonanceContext46(null, List.of(), definition.newTemplate(), null);
    var result = definition.evaluate(context);
    assertEquals(
        Long.valueOf(0), result.get(RogueResonanceVariables46.nameKey("Egg_MaxLayer_612720")));
    assertEquals(
        Long.valueOf(1L << 33), result.get(RogueResonanceVariables46.nameKey("FirstBattle_Flag")));
  }

  @Test
  void eruditionDefinitionKeepsAllNineBindingsWithoutInventingUnqueriedValues() {
    var selected = RogueResonanceClassicEvents46.find(41009);
    assertTrue(selected.isPresent());
    var definition = selected.orElseThrow();
    assertEquals("StageAbility_612820", definition.sourceAbility());
    assertEquals(9, definition.parameterBindings().size());
    var context = new RogueResonanceContext46(null, List.of(), definition.newTemplate(), null);
    var result = definition.evaluate(context);
    assertEquals(Long.valueOf(0), result.get(RogueResonanceVariables46.nameKey("ADF_612822_2")));
    assertEquals(Long.valueOf(0), result.get(RogueResonanceVariables46.nameKey("ADF_612822_3")));
    assertEquals(
        Long.valueOf(1L << 33), result.get(RogueResonanceVariables46.nameKey("FirstBattle_Flag")));
  }

  @ParameterizedTest
  @ValueSource(ints = {41001, 41002, 41003, 41004, 41005, 41006, 41007, 41008, 41009})
  void eachCompiledDefinitionMatchesEveryOriginalDeclarationAndBinding(int event) throws Exception {
    var source = source(event);
    var definition = RogueResonanceClassicEvents46.find(event).orElseThrow();
    assertEquals(event, definition.eventId());
    assertEquals(source.get("abilityName").getAsString(), definition.sourceAbility());
    var defaults = new HashMap<Integer, Long>();
    for (var declaration : source.getAsJsonArray("sourceDeclarations")) {
      var value = declaration.getAsJsonObject();
      assertEquals(0, value.get("rawReadType").getAsInt());
      defaults.put(value.get("key").getAsInt(), value.get("fixedSignedRaw").getAsLong());
    }
    assertEquals(defaults, definition.sourceDefaults());
    var calls = new ArrayList<Integer>();
    var counter = new AtomicInteger();
    RogueResonanceLookup46 context =
        new RogueResonanceLookup46() {
          public RogueResonanceVariables46 select(int scope, int key) {
            throw new AssertionError();
          }

          public long read(int key) {
            calls.add(key);
            return counter.incrementAndGet() * (1L << 33);
          }
        };
    var result = definition.evaluate(context);
    var keys = new ArrayList<Integer>();
    var reads = new ArrayList<Integer>();
    var expected = new java.util.LinkedHashMap<Integer, Long>();
    int ordinal = 0;
    for (var binding : source.getAsJsonArray("bindings")) {
      var pair = binding.getAsJsonArray();
      String name = pair.get(0).getAsString();
      int key = RogueResonanceVariables46.nameKey(name);
      var value = pair.get(1).getAsJsonObject();
      long raw;
      if (value.get("mode").getAsInt() == 0) raw = value.get("fixedSignedRaw").getAsLong();
      else {
        assertEquals("010011", value.get("rawPostfixBytes").getAsString());
        reads.add(value.getAsJsonArray("variableKeys").get(0).getAsInt());
        raw = ++ordinal * (1L << 33);
      }
      keys.add(key);
      expected.put(key, raw);
    }
    assertEquals(
        source.getAsJsonArray("bindings").asList().stream()
            .map(e -> e.getAsJsonArray().get(0).getAsString())
            .toList(),
        definition.parameterBindings().stream()
            .map(RogueResonanceEventParameters46.Binding::name)
            .toList());
    assertEquals(expected, result);
    assertEquals(keys, new ArrayList<>(result.keySet()));
    assertEquals(reads, calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {41001, 41002, 41003, 41004, 41005, 41006, 41007, 41008, 41009})
  void eachDefinitionAcceptsExactExplicitOriginalStageParameters(int event) throws Exception {
    var definition = RogueResonanceClassicEvents46.find(event).orElseThrow();
    var record =
        fixture("rogue-maze-buff-table-4.6.json")
            .getAsJsonArray("records")
            .get(0)
            .getAsJsonObject();
    var rows =
        RogueMazeBuffTable46.read(HexFormat.of().parseHex(record.get("rawHex").getAsString()));
    int buff = Integer.parseInt(definition.sourceAbility().substring("StageAbility_".length()));
    var row = rows.get(new RogueMazeBuffTable46.Key(buff, 1));
    assertNotNull(row);
    var template = definition.newTemplate();
    for (var entry :
        RogueResonanceStageParams46.create(
            row.parameters().stream().mapToLong(Long::longValue).toArray()))
      assertTrue(template.write(entry.key(), entry.raw()));
    var result = definition.evaluate(new RogueResonanceContext46(null, List.of(), template, null));
    for (int index = 0; index < row.parameters().size(); index++)
      assertEquals(
          row.parameters().get(index),
          result.get(RogueResonanceVariables46.nameKey("ADF_" + (index + 1))));
  }

  @Test
  void templatesRemainIndependentAndDefinitionsAndResultsAreImmutable() {
    var definition = RogueResonanceClassicEvents46.find(41008).orElseThrow();
    var first = definition.newTemplate();
    var second = definition.newTemplate();
    int flag = RogueResonanceVariables46.nameKey("FirstBattle_Flag");
    assertTrue(first.write(flag, 0));
    assertEquals(1L << 33, second.read(flag));
    assertEquals(Long.valueOf(1L << 33), definition.sourceDefaults().get(flag));
    assertThrows(UnsupportedOperationException.class, () -> definition.sourceDefaults().clear());
    assertThrows(UnsupportedOperationException.class, () -> definition.parameterBindings().clear());
    var result = definition.evaluate(new RogueResonanceContext46(null, List.of(), second, null));
    assertThrows(UnsupportedOperationException.class, result::clear);
    assertThrows(
        UnsupportedOperationException.class,
        () -> RogueResonanceClassicEvents46.eventIds().clear());
  }

  @Test
  void suppliedContextRemainsAuthoritativeAndMissingValuesDoNotFallBackToCompiledDefaults() {
    var definition = RogueResonanceClassicEvents46.find(41009).orElseThrow();
    var empty =
        new RogueResonanceContext46(
            null, List.of(), RogueResonanceVariables46.fromSourceDefaults(Map.of(), null), null);
    assertThrows(IllegalArgumentException.class, () -> definition.evaluate(empty));
    assertThrows(IllegalArgumentException.class, () -> definition.evaluate(null));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 41000, 41010, 96121, 96123, Integer.MAX_VALUE})
  void nonclassicIdsDoNotReuseSharedSourceConfigurations(int event) {
    assertTrue(RogueResonanceClassicEvents46.find(event).isEmpty());
  }
}
