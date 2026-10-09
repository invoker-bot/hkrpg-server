package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueMazeBuffTable46Test {
  private static void uvar(ByteArrayOutputStream output, long value) {
    do {
      int next = (int) (value & 127);
      value >>>= 7;
      output.write(next | (value != 0 ? 128 : 0));
    } while (value != 0);
  }

  private static byte[] table(long count, byte[]... rows) {
    var output = new ByteArrayOutputStream();
    uvar(output, (count << 1) ^ (count >> 63));
    for (var row : rows) output.writeBytes(row);
    return output.toByteArray();
  }

  private static byte[] row(long id, long level, Long[] parameters) {
    var output = new ByteArrayOutputStream();
    uvar(output, 265);
    uvar(output, id);
    uvar(output, level);
    long size = parameters == null ? -1 : parameters.length;
    uvar(output, (size << 1) ^ (size >> 63));
    if (parameters != null)
      for (long value : parameters) uvar(output, (value << 1) ^ (value >> 63));
    return output.toByteArray();
  }

  private static JsonObject fixture(String name) throws Exception {
    try (var input = RogueMazeBuffTable46Test.class.getResourceAsStream("/fixtures/" + name)) {
      assertNotNull(input);
      return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  private static JsonObject profile() throws Exception {
    return fixture("rogue-maze-buff-table-4.6.json");
  }

  private static byte[] raw(JsonObject record) {
    return HexFormat.of().parseHex(record.get("rawHex").getAsString());
  }

  @Test
  void threeCompleteOriginalTablesRetainAll4019RowsAnd8215RawWords() throws Exception {
    int rows = 0, words = 0;
    for (var source : profile().getAsJsonArray("records")) {
      var record = source.getAsJsonObject();
      var table = RogueMazeBuffTable46.read(raw(record));
      assertEquals(record.get("rows").getAsInt(), table.size());
      for (var row : table.values()) {
        if (row.parameters() != null) words += row.parameters().size();
        assertTrue(row.offset() < row.endOffset());
      }
      rows += table.size();
    }
    assertEquals(4019, rows);
    assertEquals(8215, words);
  }

  @Test
  void original41007CoefficientKeepsExactRawWordRatherThanDecimalReconstruction() throws Exception {
    var record = profile().getAsJsonArray("records").get(0).getAsJsonObject();
    var row = RogueMazeBuffTable46.read(raw(record)).get(new RogueMazeBuffTable46.Key(612620, 1));
    assertNotNull(row);
    assertEquals("ADV_StageAbility_612620", row.modifier());
    assertEquals("StageAbility_612620", row.bindingKey());
    assertEquals(4, row.bindingType());
    assertEquals(
        List.of(
            2147483650L,
            25769803776L,
            42949672960L,
            687194768L,
            1717986920L,
            3435973840L,
            429496730L),
        row.parameters());
    assertNotEquals((1L << 33) / 5, row.parameters().get(4));
  }

  @Test
  void originalRawStageParametersFeedExplicit41007TransferWithoutQueryFallback() throws Exception {
    var row =
        RogueMazeBuffTable46.read(raw(profile().getAsJsonArray("records").get(0).getAsJsonObject()))
            .get(new RogueMazeBuffTable46.Key(612620, 1));
    assertNotNull(row);
    var event =
        fixture("rogue-resonance-event-params-4.6.json")
            .getAsJsonArray("classicEventBindings")
            .asList()
            .stream()
            .map(e -> e.getAsJsonObject())
            .filter(e -> e.get("eventId").getAsInt() == 41007)
            .findFirst()
            .orElseThrow();
    var values = new HashMap<Integer, Long>();
    for (var declaration : event.getAsJsonArray("sourceDeclarations")) {
      var d = declaration.getAsJsonObject();
      values.put(d.get("key").getAsInt(), d.get("fixedSignedRaw").getAsLong());
    }
    for (var entry :
        RogueResonanceStageParams46.create(
            row.parameters().stream().mapToLong(Long::longValue).toArray()))
      values.put(entry.key(), entry.raw());
    var bindings = new ArrayList<RogueResonanceEventParameters46.Binding>();
    for (var binding : event.getAsJsonArray("bindings")) {
      var pair = binding.getAsJsonArray();
      var scalar = pair.get(1).getAsJsonObject();
      bindings.add(
          new RogueResonanceEventParameters46.Binding(
              pair.get(0).getAsString(),
              RogueResonanceDynamicCell46.Scalar.fromPostfix(
                  0,
                  HexFormat.of().parseHex(scalar.get("rawPostfixBytes").getAsString()),
                  new long[0],
                  new int[] {scalar.getAsJsonArray("variableKeys").get(0).getAsInt()})));
    }
    var context =
        new RogueResonanceContext46(
            null, List.of(), RogueResonanceVariables46.fromSourceDefaults(values, null), null);
    var result = RogueResonanceEventParameters46.evaluate(bindings, context);
    assertEquals(Long.valueOf(1717986920L), result.get(RogueResonanceVariables46.nameKey("ADF_5")));
    assertEquals(18, result.size());
    // Query declarations remain their source zero. Neither held-buff levels nor the
    // query action's index/fallback rules are inferred by this table reader.
    assertEquals(Long.valueOf(0), result.get(RogueResonanceVariables46.nameKey("ADF_612626_1")));
  }

  @Test
  void all56OriginalClassicRowsMatchOffsetsFieldsAndParameters() throws Exception {
    var record = profile().getAsJsonArray("records").get(0).getAsJsonObject();
    assertEquals(56, record.getAsJsonArray("selected").size());
    var table = RogueMazeBuffTable46.read(raw(record));
    for (var selected : record.getAsJsonArray("selected")) {
      var s = selected.getAsJsonObject();
      var row =
          table.get(new RogueMazeBuffTable46.Key(s.get("ID").getAsInt(), s.get("Lv").getAsInt()));
      assertNotNull(row);
      assertEquals(s.get("offset").getAsInt(), row.offset());
      assertEquals(s.get("endOffset").getAsInt(), row.endOffset());
      assertEquals(s.get("mask").getAsLong(), row.fieldMask());
      assertEquals(s.get("ModifierName").getAsString(), row.modifier());
      assertEquals(s.get("InBattleBindingKey").getAsString(), row.bindingKey());
      assertEquals(
          s.getAsJsonObject("InBattleBindingType").get("raw").getAsInt(), row.bindingType());
      assertEquals(
          s.getAsJsonArray("ParamList").asList().stream().map(e -> e.getAsLong()).toList(),
          row.parameters());
    }
  }

  @Test
  void omittedLevelAndParameterFieldsRemainAbsentWithZeroIdentityLevel() {
    var rows = RogueMazeBuffTable46.read(HexFormat.of().parseHex("020101"));
    var row = rows.get(new RogueMazeBuffTable46.Key(1, 0));
    assertNotNull(row);
    assertNull(row.parameters());
    assertNull(row.bindingKey());
    assertNull(row.modifier());
    assertEquals(1, row.fieldMask());
    assertEquals(1, row.offset());
    assertEquals(3, row.endOffset());
    assertFalse(rows.containsKey(new RogueMazeBuffTable46.Key(1, 1)));
  }

  @Test
  void nullAndEmptyArraysKeepOriginalFieldPresence() {
    var rows = RogueMazeBuffTable46.read(table(2, row(1, 0, null), row(1, 1, new Long[0])));
    assertNull(rows.get(new RogueMazeBuffTable46.Key(1, 0)).parameters());
    assertEquals(List.of(), rows.get(new RogueMazeBuffTable46.Key(1, 1)).parameters());
    assertEquals(265, rows.get(new RogueMazeBuffTable46.Key(1, 0)).fieldMask());
  }

  @Test
  void signedStandardWordsPreserveNegativeZeroAndBothFiniteLimits() {
    var words = new Long[] {Long.MIN_VALUE, -2L, 0L, 2L, Long.MAX_VALUE - 1};
    var row =
        RogueMazeBuffTable46.read(table(1, row(1, 1, words)))
            .get(new RogueMazeBuffTable46.Key(1, 1));
    assertEquals(List.of(words), row.parameters());
  }

  @Test
  void inputMapAndParameterListsAreDetachedAndImmutable() {
    var data = table(1, row(1, 1, new Long[] {2L}));
    var rows = RogueMazeBuffTable46.read(data);
    Arrays.fill(data, (byte) 0);
    var parsed = rows.get(new RogueMazeBuffTable46.Key(1, 1));
    assertEquals(List.of(2L), parsed.parameters());
    assertThrows(UnsupportedOperationException.class, rows::clear);
    assertThrows(UnsupportedOperationException.class, () -> parsed.parameters().set(0, 4L));
  }

  @Test
  void sameBuffDifferentLevelsDoNotMergeOrFallback() {
    var rows =
        RogueMazeBuffTable46.read(table(2, row(1, 0, new Long[] {2L}), row(1, 2, new Long[] {4L})));
    assertEquals(2, rows.size());
    assertEquals(List.of(2L), rows.get(new RogueMazeBuffTable46.Key(1, 0)).parameters());
    assertEquals(List.of(4L), rows.get(new RogueMazeBuffTable46.Key(1, 2)).parameters());
    assertNull(rows.get(new RogueMazeBuffTable46.Key(1, 1)));
  }

  @Test
  void duplicateIdentityFailsWithoutReturningPartialTable() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMazeBuffTable46.read(table(2, row(1, 1, null), row(1, 1, null))));
  }

  @Test
  void parameterBoundAndExtendedWordAreExplicitProjectLimits() {
    Long[] words = new Long[255];
    Arrays.fill(words, 2L);
    assertEquals(
        255,
        RogueMazeBuffTable46.read(table(1, row(1, 1, words)))
            .get(new RogueMazeBuffTable46.Key(1, 1))
            .parameters()
            .size());
    Long[] excessive = java.util.Collections.nCopies(256, 2L).toArray(Long[]::new);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMazeBuffTable46.read(table(1, row(1, 1, excessive))));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMazeBuffTable46.read(table(1, row(1, 1, new Long[] {1L}))));
  }

  @Test
  void nullEmptyAndExcessiveInputAreRejectedBeforeReading() {
    assertThrows(IllegalArgumentException.class, () -> RogueMazeBuffTable46.read(null));
    assertThrows(IllegalArgumentException.class, () -> RogueMazeBuffTable46.read(new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMazeBuffTable46.read(new byte[8 * 1024 * 1024 + 1]));
    assertEquals(Map.of(), RogueMazeBuffTable46.read(new byte[] {0}));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "01",
        "c2b518",
        "020001",
        "020100",
        "020201",
        "02010100",
        "02010180",
        "02810101",
        "0201ffffffffffffffffffff02",
        "0201ffffffff0f",
        "0201ffffffff1f",
        "02890201010404",
        "0289020101040401",
        "028902010103",
        "0281020107"
      })
  void malformedCountsIdentitiesMasksAndParametersAreRejected(String hex) {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMazeBuffTable46.read(HexFormat.of().parseHex(hex)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "02410112",
        "028180040110",
        "028180100108",
        "028180400106",
        "02818080010102",
        "02210101ff"
      })
  void invalidEnumsBooleanAndUtf8AreRejected(String hex) {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMazeBuffTable46.read(HexFormat.of().parseHex(hex)));
  }
}
