package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RogueBattleEventTable46Test {
  private static JsonObject fixture() throws Exception {
    try (var stream =
        RogueBattleEventTable46Test.class.getResourceAsStream(
            "/fixtures/rogue-battle-event-tables-4.6.json")) {
      assertNotNull(stream);
      return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  private static byte[] original(int index) throws Exception {
    return HexFormat.of()
        .parseHex(
            fixture()
                .getAsJsonArray("records")
                .get(index)
                .getAsJsonObject()
                .get("rawHex")
                .getAsString());
  }

  @Test
  void originalConfigIncludesNineClassicTeamsRawSpeedAndStageRequirements() throws Exception {
    var rows = RogueBattleEventTable46.readConfig(original(1));
    assertEquals(498, rows.size());
    var row = rows.get(41003);
    assertNotNull(row);
    assertEquals(3, row.team());
    assertEquals(3, row.subType());
    assertEquals(60L << 33, row.speed());
    assertTrue(row.hardLevel());
    assertTrue(row.eliteGroup());
  }

  @Test
  void originalDataKeepsExactCharacterPathSkillsAndReservedEnergy() throws Exception {
    var rows = RogueBattleEventTable46.readData(original(0));
    assertEquals(458, rows.size());
    var row = rows.get(41009);
    assertNotNull(row);
    assertEquals(
        "Config/ConfigCharacter/BattleEvent/Avatar_RogueBattleevent128_Config.json", row.config());
    assertEquals(List.of(4100901, 4100902), row.skills());
    assertTrue(row.spReserved());
  }

  @Test
  void stageRequiredEventCannotBePreparedWithMissingCurrentWaveStage() {
    var row =
        new RogueBattleEventTable46.Config(
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
            true,
            true,
            null,
            null,
            List.of(),
            null,
            0,
            0,
            1);
    assertTrue(
        RogueBattleEventTable46.prepare(41007, Map.of(41007, row), 1001, id -> null).isEmpty());
  }

  private static RogueBattleEventTable46.Config config(boolean hard, boolean elite) {
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
        hard,
        elite,
        null,
        null,
        List.of(),
        null,
        0,
        0,
        1);
  }

  private static void unsigned(ByteArrayOutputStream out, long value) {
    do {
      int next = (int) (value & 127);
      value >>>= 7;
      out.write(value == 0 ? next : next | 128);
    } while (value != 0);
  }

  private static void signed(ByteArrayOutputStream out, long value) {
    unsigned(out, (value << 1) ^ (value >> 63));
  }

  private static byte[] configBytes(long mask, long... fields) {
    var out = new ByteArrayOutputStream();
    signed(out, 1);
    unsigned(out, mask);
    for (long field : fields) unsigned(out, field);
    return out.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(ints = {41001, 41002, 41003, 41004, 41005, 41006, 41007, 41008, 41009})
  void originalClassicRowsRequireExactCurrentWaveStageAndKeepRawProperties(int id)
      throws Exception {
    var row = RogueBattleEventTable46.readConfig(original(1)).get(id);
    var data = RogueBattleEventTable46.readData(original(0)).get(id);
    assertEquals(id, data.id());
    assertEquals(List.of(id * 100 + 1, id * 100 + 2), data.skills());
    assertEquals(61415, row.mask());
    assertEquals((id == 41003 ? 60L : 100L) << 33, row.speed());
    assertEquals(1, row.properties().size());
    assertEquals(2, row.properties().getFirst().type());
    assertEquals(90L << 33, row.properties().getFirst().raw());
    assertNull(row.actionBarDescription());
    assertEquals(0, row.actionBarType());
    var calls = new AtomicInteger();
    var stage = new RogueBattleEventTable46.WaveStage(1001, new Object());
    var prepared =
        RogueBattleEventTable46.prepare(
                id,
                Map.of(id, row),
                1001,
                key -> {
                  assertEquals(1001, key);
                  calls.incrementAndGet();
                  return stage;
                })
            .orElseThrow();
    assertSame(row, prepared.event());
    assertSame(stage, prepared.waveStage());
    assertEquals(1, calls.get());
  }

  @Test
  void tablesRetainOriginalOrderBoundsAndAreImmutable() throws Exception {
    var configs = RogueBattleEventTable46.readConfig(original(1));
    int position = configs.values().iterator().next().offset();
    for (var row : configs.values()) {
      assertEquals(position, row.offset());
      position = row.endOffset();
    }
    assertEquals(original(1).length, position);
    assertThrows(UnsupportedOperationException.class, () -> configs.clear());
    assertThrows(
        UnsupportedOperationException.class, () -> configs.get(41001).properties().clear());
    var data = RogueBattleEventTable46.readData(original(0));
    position = data.values().iterator().next().offset();
    for (var row : data.values()) {
      assertEquals(position, row.offset());
      position = row.endOffset();
    }
    assertEquals(original(0).length, position);
    assertThrows(UnsupportedOperationException.class, () -> data.clear());
    assertThrows(UnsupportedOperationException.class, () -> data.get(41001).skills().clear());
  }

  @Test
  void absentNullAndEmptyArraysRemainDistinct() {
    var absent = RogueBattleEventTable46.readConfig(configBytes(1, 41007)).get(41007);
    assertNull(absent.abilities());
    assertNull(absent.properties());
    assertNull(absent.parameters());
    var explicitNull =
        RogueBattleEventTable46.readConfig(configBytes(1 | 128 | 256 | 16384, 41007, 1, 1, 1))
            .get(41007);
    assertNull(explicitNull.abilities());
    assertNull(explicitNull.properties());
    assertNull(explicitNull.parameters());
    var empty =
        RogueBattleEventTable46.readConfig(configBytes(1 | 128 | 256 | 16384, 41007, 0, 0, 0))
            .get(41007);
    assertEquals(List.of(), empty.abilities());
    assertEquals(List.of(), empty.properties());
    assertEquals(List.of(), empty.parameters());
  }

  @Test
  void unsignedTextHashKeepsAllSixtyFourBitsAndNegativeCacheIndex() {
    var row = RogueBattleEventTable46.readConfig(configBytes(1 | 4096, 41007, 1, -1L)).get(41007);
    assertEquals(-1, row.actionBarDescription().cacheIndex());
    assertEquals(-1L, row.actionBarDescription().hash());
  }

  @Test
  void noStageFlagsDoNotReadOrRequireStageProvider() {
    var row = config(false, false);
    assertNull(
        RogueBattleEventTable46.prepare(41007, Map.of(41007, row), 0, null)
            .orElseThrow()
            .waveStage());
    assertNull(
        RogueBattleEventTable46.prepare(
                41007,
                Map.of(41007, row),
                1001,
                key -> {
                  fail("No current-wave access without stage flags");
                  return null;
                })
            .orElseThrow()
            .waveStage());
  }

  @ParameterizedTest
  @CsvSource({"true,false", "false,true", "true,true"})
  void eitherStageFlagRequiresTheExactSuppliedWaveStage(boolean hard, boolean elite) {
    var row = config(hard, elite);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueBattleEventTable46.prepare(41007, Map.of(41007, row), 1001, null));
    assertTrue(
        RogueBattleEventTable46.prepare(41007, Map.of(41007, row), 1001, key -> null).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueBattleEventTable46.prepare(
                41007,
                Map.of(41007, row),
                1001,
                key -> new RogueBattleEventTable46.WaveStage(1002, new Object())));
  }

  @Test
  void missingEventDoesNotReadStageAndWrongEventIdentityIsRejected() {
    assertTrue(
        RogueBattleEventTable46.prepare(
                41007,
                Map.of(),
                1001,
                key -> {
                  fail("Missing event must not query stage");
                  return null;
                })
            .isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueBattleEventTable46.prepare(41008, Map.of(41008, config(true, true)), 1001, null));
  }

  @Test
  void stageProviderFailurePropagatesWithoutFallback() {
    var problem = new IllegalStateException("source unavailable");
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class,
            () ->
                RogueBattleEventTable46.prepare(
                    41007,
                    Map.of(41007, config(true, true)),
                    1001,
                    key -> {
                      throw problem;
                    })));
  }

  @Test
  void explicitRowsCopyArraysAndRejectUnknownNumericRepresentations() {
    var skills = new ArrayList<>(List.of(4100701));
    var row =
        new RogueBattleEventTable46.Data(
            41007, "config", null, null, null, null, skills, true, 1, 0, 1);
    skills.clear();
    assertEquals(List.of(4100701), row.skills());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueBattleEventTable46.Property(227, 0, 3, 0, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueBattleEventTable46.Property(2, 1, 3, 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueBattleEventTable46.WaveStage(0, new Object()));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueBattleEventTable46.WaveStage(1001, null));
  }

  @Test
  void duplicateIdentityTruncationAndTrailingBytesAreRejected() {
    var row = configBytes(1, 41007);
    var repeated = new ByteArrayOutputStream();
    signed(repeated, 2);
    repeated.writeBytes(java.util.Arrays.copyOfRange(row, 1, row.length));
    repeated.writeBytes(java.util.Arrays.copyOfRange(row, 1, row.length));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueBattleEventTable46.readConfig(repeated.toByteArray()));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueBattleEventTable46.readConfig(java.util.Arrays.copyOf(row, row.length - 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueBattleEventTable46.readConfig(java.util.Arrays.copyOf(row, row.length + 1)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "80",
        "8000",
        "ff01",
        "02",
        "0200",
        "02808004",
        "02818004",
        "02018080808008",
        "0203030c",
        "0205032e",
        "02810403060802",
        "0281080303",
        "02818001038408",
        "02210301ff",
        "02010300"
      })
  void malformedOrUnsupportedConfigBytesAreRejected(String hex) {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueBattleEventTable46.readConfig(HexFormat.of().parseHex(hex)));
  }

  @Test
  void dataMaskBooleanAndInputSizeAreBounded() {
    assertThrows(IllegalArgumentException.class, () -> RogueBattleEventTable46.readData(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueBattleEventTable46.readData(new byte[8 * 1024 * 1024 + 1]));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueBattleEventTable46.readData(configBytes(257, 41007)));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueBattleEventTable46.readData(configBytes(129, 41007, 2)));
    assertEquals(Map.of(), RogueBattleEventTable46.readData(new byte[] {0}));
  }

  @Test
  void preparationRejectsFiniteDomainViolations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueBattleEventTable46.prepare(-1, Map.of(), 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueBattleEventTable46.prepare(41007, Map.of(), -1, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueBattleEventTable46.prepare(41007, null, 0, null));
  }
}
