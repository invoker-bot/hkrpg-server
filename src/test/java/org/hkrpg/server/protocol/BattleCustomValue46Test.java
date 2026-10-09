package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.*;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class BattleCustomValue46Test {
  final BattleResult46Test messages = new BattleResult46Test();

  DynamicMessage entry(String key, float value) {
    var field =
        messages
            .message("BattleStatistics")
            .getDescriptorForType()
            .findFieldByName("custom_values");
    var row = DynamicMessage.newBuilder(field.getMessageType());
    messages.set(row, "key", key);
    messages.set(row, "value", value);
    return row.build();
  }

  @Test
  void duplicateMapKeyCannotSelectAWinningValueAfterNormalization() {
    var stats = messages.message("BattleStatistics");
    var field = stats.getDescriptorForType().findFieldByName("custom_values");
    stats.addRepeatedField(field, entry("_PlayerWin", 0));
    stats.addRepeatedField(field, entry("_PlayerWin", 1));
    var request = messages.request(stats.build());
    assertFalse(
        BattleResult46.identityValid(request.getDescriptorForType(), request.toByteArray(), 0));
  }

  boolean valid(byte[] entry) throws Exception {
    var out = new ByteArrayOutputStream();
    var coded = CodedOutputStream.newInstance(out);
    coded.writeByteArray(13, entry);
    coded.flush();
    return BattleResult46.identityValid(
        messages.message("BattleStatistics").getDescriptorForType(), out.toByteArray(), 1);
  }

  @Test
  void sourceMapFieldThirteenPreservesFloatAndDefaultZeroThroughTheBridge() throws Exception {
    for (float value : new float[] {0, 1, 0.5f, -1}) {
      var row = entry("_PlayerWin", value);
      assertTrue(valid(row.toByteArray()));
      var stats = messages.message("BattleStatistics");
      stats.addRepeatedField(stats.getDescriptorForType().findFieldByName("custom_values"), row);
      var core =
          emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.parseFrom(
              messages.bridge.translate("BattleStatistics", stats.build().toByteArray(), false));
      assertEquals(1, core.getCustomValues().length());
      assertEquals("_PlayerWin", core.getCustomValues().get(0).getKey());
      assertEquals(value, core.getCustomValues().get(0).getValue());
    }
  }

  @Test
  void nonFiniteMapValuesAreRejectedBeforeBattleSettlement() throws Exception {
    for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
      assertFalse(valid(entry("_PlayerWin", value).toByteArray()));
  }

  @Test
  void duplicateEntryFieldsWrongWireAndUnknownNestedValuesDoNotNormalizeIntoAWin()
      throws Exception {
    for (int variant = 0; variant < 5; variant++) {
      var out = new ByteArrayOutputStream();
      out.write(entry("_PlayerWin", 1).toByteArray());
      var coded = CodedOutputStream.newInstance(out);
      switch (variant) {
        case 0 -> coded.writeString(1, "other");
        case 1 -> coded.writeFloat(2, 0);
        case 2 -> coded.writeUInt32(2, 1);
        case 3 -> coded.writeDouble(2, 1);
        case 4 -> coded.writeUInt32(99, 1);
      }
      coded.flush();
      assertFalse(valid(out.toByteArray()), "variant=" + variant);
    }
    assertFalse(valid(new byte[] {10, 2, (byte) 0xc0, (byte) 0xaf}));
    assertFalse(valid(new byte[] {10, 10, 65}));
  }

  @Test
  void keyLimitUsesUtf8BytesAndDistinctNamedKeysRemainCompatible() throws Exception {
    assertTrue(valid(entry("x".repeat(128), 1).toByteArray()));
    assertTrue(valid(entry("界".repeat(42), 1).toByteArray()));
    assertFalse(valid(entry("x".repeat(129), 1).toByteArray()));
    assertFalse(valid(entry("界".repeat(43), 1).toByteArray()));
    assertFalse(valid(entry("", 1).toByteArray()));
    var stats = messages.message("BattleStatistics");
    var field = stats.getDescriptorForType().findFieldByName("custom_values");
    for (int i = 0; i < 128; i++) stats.addRepeatedField(field, entry("key" + i, 1));
    var request = messages.request(stats.build());
    assertTrue(
        BattleResult46.identityValid(request.getDescriptorForType(), request.toByteArray(), 0));
    stats.addRepeatedField(field, entry("overflow", 1));
    request = messages.request(stats.build());
    assertFalse(
        BattleResult46.identityValid(request.getDescriptorForType(), request.toByteArray(), 0));
  }
}
