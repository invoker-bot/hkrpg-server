package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BattleTargetReport46Test {
  final BattleResult46Test messages = new BattleResult46Test();

  byte[] numbers(long... values) throws Exception {
    var out = new ByteArrayOutputStream();
    var coded = CodedOutputStream.newInstance(out);
    for (int i = 0; i < values.length; i += 2) coded.writeUInt64((int) values[i], values[i + 1]);
    coded.flush();
    return out.toByteArray();
  }

  byte[] nested(int field, byte[]... rows) throws Exception {
    var out = new ByteArrayOutputStream();
    var coded = CodedOutputStream.newInstance(out);
    for (byte[] row : rows) coded.writeByteArray(field, row);
    coded.flush();
    return out.toByteArray();
  }

  byte[] join(byte[]... rows) throws Exception {
    var out = new ByteArrayOutputStream();
    for (byte[] row : rows) out.write(row);
    return out.toByteArray();
  }

  byte[] entry(long key, byte[]... targets) throws Exception {
    return join(numbers(1, key), nested(2, nested(1, targets)));
  }

  byte[] target() throws Exception {
    return numbers(1, 5001805, 2, 1, 3, 1);
  }

  boolean valid(byte[]... entries) throws Exception {
    var field =
        messages
            .message("BattleStatistics")
            .getDescriptorForType()
            .findFieldByName("battle_target_info");
    assertEquals(28, field.getNumber());
    // Keep the raw statistics bytes: a protobuf parser can normalize duplicate target fields.
    var requestType = messages.message("PVEBattleResultCsReq").getDescriptorForType();
    return BattleResult46.identityValid(
        requestType,
        nested(requestType.findFieldByName("stt").getNumber(), nested(field.getNumber(), entries)),
        0);
  }

  @Test
  void originalKeyFiveAndUnsignedValuesSurviveTheExistingBridge() throws Exception {
    var row = numbers(1, 5001805, 2, 0xffffffffL, 3, 1);
    assertTrue(valid(entry(5, row)));
    var stats = messages.message("BattleStatistics").mergeFrom(nested(28, entry(5, row))).build();
    var core =
        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.parseFrom(
            messages.bridge.translate("BattleStatistics", stats.toByteArray(), false));
    assertEquals(1, core.getBattleTargetInfo().length());
    var target = core.getBattleTargetInfo().get(0);
    assertEquals(5, target.getKey());
    assertEquals(5001805, target.getValue().getBattleTargetList().get(0).getId());
    assertEquals(
        0xffffffffL,
        Integer.toUnsignedLong(target.getValue().getBattleTargetList().get(0).getProgress()));
  }

  @Test
  void duplicateOuterKeyCannotMergeConflictingTargetProgress() throws Exception {
    assertFalse(valid(entry(5, numbers(1, 5001805, 2, 0, 3, 1)), entry(5, target())));
  }

  @Test
  void duplicateTargetIdentityCannotProvideTwoDifferentOutcomes() throws Exception {
    assertFalse(valid(entry(5, target(), numbers(1, 5001805, 2, 0, 3, 1))));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void duplicateTargetScalarsAreRejectedBeforeNormalization(int field) throws Exception {
    assertFalse(valid(entry(5, join(target(), numbers(field, 1)))));
  }

  @ParameterizedTest
  @CsvSource({
    "1,4294967296",
    "2,4294967296",
    "3,4294967296",
    "1,9223372036854775807",
    "2,-1",
    "3,-1"
  })
  void everyNestedUint32RejectsOverflowBeforeTruncation(int field, long value) throws Exception {
    long id = 5001805, progress = 1, total = 1;
    if (field == 1) id = value;
    else if (field == 2) progress = value;
    else total = value;
    assertFalse(valid(entry(5, numbers(1, id, 2, progress, 3, total))));
  }

  @ParameterizedTest
  @ValueSource(longs = {4294967296L, 9223372036854775807L, -1L})
  void targetCategoryRejectsUint32Overflow(long key) throws Exception {
    assertFalse(valid(entry(key, target())));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void unknownFieldsAndWrongNestedWireKindsAreRejected(int variant) throws Exception {
    byte[] value =
        switch (variant) {
          case 0 -> join(entry(5, target()), numbers(1, 6));
          case 1 -> join(entry(5, target()), nested(2, new byte[0]));
          case 2 -> join(entry(5, target()), numbers(9, 1));
          case 3 -> join(numbers(1, 5), numbers(2, 1));
          case 4 -> join(numbers(1, 5), nested(2, numbers(1, 1)));
          case 5 -> entry(5, nested(1, new byte[0]));
          case 6 -> entry(5, join(target(), numbers(9, 1)));
          default -> join(numbers(1, 5), nested(2, nested(9, target())));
        };
    assertFalse(valid(value), "variant=" + variant);
  }

  @Test
  void protoDefaultScalarsRemainCompatibleButDefaultDuplicateKeysAreRejected() throws Exception {
    assertTrue(valid(entry(5, numbers(1, 5001805))));
    assertTrue(valid(entry(5)));
    assertTrue(valid(new byte[0])); // protobuf map defaults: key 0, empty value
    assertFalse(valid(new byte[0], numbers(1, 0)));
  }

  @Test
  void targetCollectionsAreBoundedAfterAcceptingTheMaximumDistinctIdentities() throws Exception {
    var rows = new ArrayList<byte[]>();
    for (int i = 0; i < 1024; i++) rows.add(numbers(1, i + 1));
    assertTrue(valid(entry(5, rows.toArray(byte[][]::new))));
    rows.add(numbers(1, 1025));
    assertFalse(valid(entry(5, rows.toArray(byte[][]::new))));
  }

  @Test
  void distinctCategoryCollectionsAreBoundedAfterAcceptingTheMaximum() throws Exception {
    var groups = new ArrayList<byte[]>();
    for (int i = 0; i < 32; i++) groups.add(entry(i, target()));
    assertTrue(valid(groups.toArray(byte[][]::new)));
    groups.add(entry(32, target()));
    assertFalse(valid(groups.toArray(byte[][]::new)));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void truncatedNestedMessagesCannotReachSettlement(int variant) throws Exception {
    byte[] corrupt =
        switch (variant) {
          case 0 -> new byte[] {8, (byte) 128};
          case 1 -> new byte[] {18, 3, 10};
          default -> new byte[] {8, 5, 18, 4, 10, 2, 8, (byte) 128};
        };
    assertFalse(valid(corrupt));
  }
}
