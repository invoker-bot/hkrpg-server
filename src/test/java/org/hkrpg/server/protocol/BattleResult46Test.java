package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.*;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BattleResult46Test {
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  DynamicMessage.Builder message(String name) {
    return DynamicMessage.newBuilder(bridge.clientSchema().message(name));
  }

  void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  DynamicMessage monster(int death) {
    var b = message("MonsterBattleInfo");
    set(b, "entity_id", 5001);
    set(b, "monster_id", 800204001);
    set(b, "stage_id", 3050010);
    var f = b.getDescriptorForType().findFieldByName("death_source");
    b.setField(f, f.getEnumType().findValueByNumberCreatingIfUnknown(death));
    return b.build();
  }

  DynamicMessage statistics(int death) {
    var b = message("BattleStatistics");
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("monster_list"), monster(death));
    return b.build();
  }

  DynamicMessage request(DynamicMessage statistics) {
    var b = message("PVEBattleResultCsReq");
    set(b, "battle_id", 73);
    set(b, "stage_id", 3050010);
    set(b, "stt", statistics);
    return b.build();
  }

  byte[] scalar(byte[] prefix, int field, long value) throws Exception {
    var out = new ByteArrayOutputStream();
    out.write(prefix);
    var coded = CodedOutputStream.newInstance(out);
    coded.writeUInt64(field, value);
    coded.flush();
    return out.toByteArray();
  }

  @Test
  void nativeDeathFieldTwentySurvivesOutsideTheLegacyStatisticsSchema() throws Exception {
    var stats = statistics(1);
    var monster = monster(1);
    assertEquals(20, monster.getDescriptorForType().findFieldByName("death_source").getNumber());
    var rows = BattleResult46.monsters(stats);
    assertNotNull(rows);
    assertEquals(1, rows.size());
    assertEquals(800204001, rows.getFirst().monsterId());
    assertEquals(3050010, rows.getFirst().stageId());
    assertEquals(1, rows.getFirst().deathSource());
    var core =
        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.parseFrom(
            bridge.translate("BattleStatistics", stats.toByteArray(), false));
    assertFalse(java.util.Arrays.equals(stats.toByteArray(), core.toByteArray()));
    assertTrue(
        BattleResult46.identityValid(
            request(stats).getDescriptorForType(), request(stats).toByteArray(), 0));
    assertThrows(UnsupportedOperationException.class, () -> rows.clear());
  }

  @Test
  void selfDeathEscapeAndUnknownArePreservedWithoutInventingKills() {
    for (int death = 0; death <= 3; death++)
      assertEquals(death, BattleResult46.monsters(statistics(death)).getFirst().deathSource());
    assertNull(BattleResult46.monsters(statistics(4)));
    var r = request(statistics(4));
    assertFalse(BattleResult46.identityValid(r.getDescriptorForType(), r.toByteArray(), 0));
  }

  @Test
  void duplicateIdentitiesAndUint32AliasesAreRejectedBeforeDomainUse() throws Exception {
    var r = request(statistics(1));
    assertFalse(
        BattleResult46.identityValid(r.getDescriptorForType(), scalar(r.toByteArray(), 4, 73), 0));
    assertFalse(
        BattleResult46.identityValid(
            r.getDescriptorForType(), scalar(new byte[0], 4, (1L << 32) + 73), 0));
    var type = bridge.clientSchema().message("MonsterBattleInfo");
    assertFalse(
        BattleResult46.identityValid(type, scalar(new byte[0], 2, (1L << 32) + 800204001), 2));
    assertFalse(BattleResult46.identityValid(type, scalar(monster(1).toByteArray(), 20, 3), 2));
    assertFalse(BattleResult46.identityValid(type, scalar(new byte[0], 20, (1L << 32) + 1), 2));
    assertFalse(
        BattleResult46.identityValid(
            r.getDescriptorForType(), scalar(new byte[0], 9, (1L << 32) + 1), 0));
  }

  @Test
  void namedFloatingPointTelemetryRemainsCompatibleWithTheExistingBridge() {
    var row = monster(1).toBuilder();
    set(row, "total_damage", 12.5);
    var stats = message("BattleStatistics");
    stats.addRepeatedField(
        stats.getDescriptorForType().findFieldByName("monster_list"), row.build());
    var r = request(stats.build()).toBuilder();
    set(r, "debug_extra_info", "原生结算");
    var wire = r.build();
    assertTrue(BattleResult46.identityValid(wire.getDescriptorForType(), wire.toByteArray(), 0));
    assertEquals(1, BattleResult46.monsters(stats.build()).size());
    assertNotNull(bridge.inbound(bridge.clientCommand("PVEBattleResultCsReq"), wire.toByteArray()));
  }

  @Test
  void unknownFieldsMalformedWireAndOversizedMonsterCollectionsFailClosed() throws Exception {
    var stats = message("BattleStatistics");
    for (int i = 0; i < 1025; i++)
      stats.addRepeatedField(
          stats.getDescriptorForType().findFieldByName("monster_list"), monster(1));
    assertNull(BattleResult46.monsters(stats.build()));
    var r = request(stats.build());
    assertFalse(BattleResult46.identityValid(r.getDescriptorForType(), r.toByteArray(), 0));
    assertFalse(
        BattleResult46.identityValid(r.getDescriptorForType(), scalar(new byte[0], 9999, 1), 0));
    assertFalse(
        BattleResult46.identityValid(r.getDescriptorForType(), new byte[] {32, (byte) 0x80}, 0));
  }

  @Test
  void challengeRoundCountsRejectRawOverflowBeforeTruncation() throws Exception {
    var type = bridge.clientSchema().message("BattleStatistics");
    for (long rounds : new long[] {0, 8, Integer.MAX_VALUE})
      assertTrue(BattleResult46.identityValid(type, scalar(new byte[0], 9, rounds), 1));
    for (long rounds :
        new long[] {-1, (long) Integer.MAX_VALUE + 1, 1L << 32, (1L << 32) + 8, Long.MAX_VALUE})
      assertFalse(
          BattleResult46.identityValid(type, scalar(new byte[0], 9, rounds), 1),
          Long.toString(rounds));
  }

  byte[] nested(int field, byte[] payload) throws Exception {
    var out = new ByteArrayOutputStream();
    var c = CodedOutputStream.newInstance(out);
    c.writeByteArray(field, payload);
    c.flush();
    return out.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "duplicate-id",
        "overflow-id",
        "duplicate-status",
        "duplicate-bar",
        "duplicate-cur",
        "duplicate-max",
        "overflow-cur",
        "overflow-max",
        "wrong-cur-wire",
        "unknown-event",
        "unknown-status",
        "unknown-bar"
      })
  void nativeEventEnergyMustValidateRawNestedIdentityBeforeLegacyBridge(String mode)
      throws Exception {
    byte[] sp = scalar(scalar(new byte[0], 1, 25), 2, 100);
    if (mode.equals("duplicate-cur")) sp = scalar(sp, 1, 25);
    if (mode.equals("duplicate-max")) sp = scalar(sp, 2, 100);
    if (mode.equals("overflow-cur")) sp = scalar(new byte[0], 1, (1L << 32) + 25);
    if (mode.equals("overflow-max")) sp = scalar(new byte[0], 2, (1L << 32) + 100);
    if (mode.equals("wrong-cur-wire")) sp = nested(1, new byte[] {25});
    if (mode.equals("unknown-bar")) sp = scalar(sp, 3, 1);
    byte[] status = nested(2, sp);
    if (mode.equals("duplicate-bar")) {
      var out = new ByteArrayOutputStream();
      out.write(status);
      out.write(status);
      status = out.toByteArray();
    }
    if (mode.equals("unknown-status")) status = scalar(status, 3, 1);
    byte[] event = scalar(nested(2, status), 1, 41001);
    if (mode.equals("duplicate-id")) event = scalar(event, 1, 41001);
    if (mode.equals("overflow-id")) event = scalar(nested(2, status), 1, (1L << 32) + 41001);
    if (mode.equals("duplicate-status")) {
      var out = new ByteArrayOutputStream();
      out.write(event);
      out.write(nested(2, status));
      event = out.toByteArray();
    }
    if (mode.equals("unknown-event")) event = scalar(event, 10, 1);
    var type = bridge.clientSchema().message("BattleStatistics");
    assertFalse(BattleResult46.identityValid(type, nested(16, event), 1), mode);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 25, 100})
  void nativeEventStatusRetainsEnergyThatLegacyStatisticsDrops(int energy) throws Exception {
    var bar = message("SpBarInfo");
    set(bar, "cur_sp", energy);
    set(bar, "max_sp", 100);
    var status = message("BattleEventProperty");
    set(status, "sp_bar", bar.build());
    var event = message("BattleEventBattleInfo");
    set(event, "battle_event_id", 41001);
    set(event, "status", status.build());
    set(event, "total_damage", 12.5);
    var statistics = message("BattleStatistics");
    statistics.addRepeatedField(
        statistics.getDescriptorForType().findFieldByName("BDCHEJKPOAF"), event.build());
    var stats = statistics.build();
    assertTrue(BattleResult46.identityValid(stats.getDescriptorForType(), stats.toByteArray(), 1));
    var rows = BattleResult46.energies(stats);
    assertNotNull(rows);
    assertEquals(1, rows.size());
    assertEquals(energy, rows.getFirst().current());
    assertEquals(100, rows.getFirst().maximum());
    assertEquals(41001, rows.getFirst().eventId());
    var old =
        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.parseFrom(
            bridge.translate("BattleStatistics", stats.toByteArray(), false));
    assertFalse(java.util.Arrays.equals(old.toByteArray(), stats.toByteArray()));
    assertThrows(UnsupportedOperationException.class, rows::clear);
    var quit = message("QuitBattleCsReq");
    set(quit, "stt", stats);
    assertTrue(
        BattleResult46.identityValid(quit.getDescriptorForType(), quit.build().toByteArray(), 0));
  }

  @ParameterizedTest
  @ValueSource(strings = {"duplicate", "zero-max", "over-max", "negative", "no-id", "nan"})
  void invalidParsedEventReportIsRejectedBeforeDomainSettlement(String mode) {
    var bar = message("SpBarInfo");
    set(bar, "cur_sp", mode.equals("negative") ? -1 : mode.equals("over-max") ? 101 : 25);
    set(bar, "max_sp", mode.equals("zero-max") ? 0 : 100);
    var status = message("BattleEventProperty");
    set(status, "sp_bar", bar.build());
    var event = message("BattleEventBattleInfo");
    set(event, "battle_event_id", mode.equals("no-id") ? 0 : 41001);
    set(event, "status", status.build());
    if (mode.equals("nan")) set(event, "total_damage", Double.NaN);
    var stats = message("BattleStatistics");
    var field = stats.getDescriptorForType().findFieldByName("BDCHEJKPOAF");
    stats.addRepeatedField(field, event.build());
    if (mode.equals("duplicate")) stats.addRepeatedField(field, event.build());
    assertNull(BattleResult46.energies(stats.build()));
  }
}
