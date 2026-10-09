package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses the actual revive handler and clock payment; only the run and scene are fixtures. */
class UniverseRevivalWire46Test {
  final UniverseClockRevival46Test fixture = new UniverseClockRevival46Test();

  @BeforeEach
  void setup() throws Exception {
    fixture.setup();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/rogue-revival-4.6.json"),
            StandardCharsets.UTF_8)) {
      for (var raw : com.google.gson.JsonParser.parseReader(reader).getAsJsonArray()) {
        var row = raw.getAsJsonObject();
        if (row.get("kind").getAsString().equals("RogueMiracle")) {
          var value = new Gson().fromJson(row.get("row"), GameplayDefinitions.Miracle.class);
          GameplayDefinitions.miracles.put(value.MiracleID(), value);
        } else if (row.get("kind").getAsString().equals("RogueMiracleEffect")) {
          var value = new Gson().fromJson(row.get("row"), GameplayDefinitions.MiracleEffect.class);
          GameplayDefinitions.miracleEffects.put(value.MiracleEffectID(), value);
        }
      }
    }
    fixture.counter(490);
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
  }

  byte[] request(String mode) throws Exception {
    var buffer = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    long avatar = UniverseRevive46Test.AVATAR;
    long entity = UniverseRevive46Test.ENTITY;
    if (mode.equals("avatar-alias")) avatar += 1L << 32;
    if (mode.equals("entity-alias")) entity += 1L << 32;
    if (mode.equals("negative-avatar")) avatar = -1;
    if (mode.equals("negative-entity")) entity = -1;
    if (mode.equals("zero-avatar")) avatar = 0;
    if (mode.equals("zero-entity")) entity = 0;
    if (!mode.equals("missing-entity") && !mode.equals("empty")) {
      if (mode.equals("entity-alias64")) over64(out, 13, (int) entity);
      else out.writeUInt64(13, entity);
      if (mode.equals("duplicate-entity")) out.writeUInt64(13, entity);
      if (mode.equals("shadowed-entity")) out.writeUInt64(13, entity + 1);
      if (mode.equals("wrong-entity-wire")) out.writeBytes(13, ByteString.EMPTY);
    }
    if (!mode.equals("missing-avatar") && !mode.equals("empty")) {
      if (mode.equals("wrong-avatar-wire")) out.writeFixed32(10, (int) avatar);
      else if (mode.equals("empty-packed")) out.writeBytes(10, ByteString.EMPTY);
      else if (mode.equals("avatar-alias64")) over64(out, 10, (int) avatar);
      else if (mode.equals("packed") || mode.equals("packed-alias")) {
        var packed = new ByteArrayOutputStream();
        var values = CodedOutputStream.newInstance(packed);
        values.writeUInt64NoTag(avatar + (mode.equals("packed-alias") ? 1L << 32 : 0));
        values.flush();
        out.writeByteArray(10, packed.toByteArray());
      } else if (mode.equals("length-alias")) {
        var packed = new ByteArrayOutputStream();
        var values = CodedOutputStream.newInstance(packed);
        values.writeUInt64NoTag(avatar);
        values.flush();
        out.writeTag(10, 2);
        out.writeUInt64NoTag((1L << 32) + packed.size());
        out.writeRawBytes(packed.toByteArray());
      } else {
        if (mode.equals("mixed-empty-packed")) out.writeBytes(10, ByteString.EMPTY);
        if (mode.equals("tag-alias")) {
          out.writeUInt64NoTag((1L << 32) + 80);
          out.writeUInt64NoTag(avatar);
        } else out.writeUInt64(10, avatar);
      }
      if (mode.equals("duplicate-avatar")) out.writeUInt64(10, avatar);
      if (mode.equals("five-avatars")) for (int i = 1; i < 5; i++) out.writeUInt64(10, avatar + i);
    }
    if (mode.equals("unknown-field")) out.writeUInt32(199, 1);
    out.flush();
    var raw = buffer.toByteArray();
    if (mode.equals("truncated")) return Arrays.copyOf(raw, raw.length - 1);
    if (mode.equals("truncated-packed")) return new byte[] {(byte) 104, 1, 82, 2, (byte) 128};
    if (mode.equals("oversized")) return new byte[16385];
    return raw;
  }

  static void over64(CodedOutputStream out, int field, int value) throws Exception {
    out.writeTag(field, 0);
    for (int i = 0; i < 9; i++) out.writeRawByte((int) (((long) value >>> (i * 7)) & 127) | 128);
    out.writeRawByte(2);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "avatar-alias",
        "entity-alias",
        "packed-alias",
        "avatar-alias64",
        "entity-alias64",
        "tag-alias",
        "length-alias",
        "duplicate-entity",
        "shadowed-entity",
        "wrong-entity-wire",
        "wrong-avatar-wire",
        "negative-avatar",
        "negative-entity",
        "zero-avatar",
        "zero-entity",
        "missing-entity",
        "missing-avatar",
        "empty",
        "empty-packed",
        "duplicate-avatar",
        "five-avatars",
        "unknown-field",
        "truncated",
        "truncated-packed",
        "oversized"
      })
  void invalidNativeRequestsCannotDebitReviveOrBreakTheClock(String mode) throws Exception {
    var session = fixture.fixture.s;
    var before = new Gson().toJson(fixture.run());
    int saves = session.explorer.saves;
    var raw = request(mode);
    assertDoesNotThrow(() -> assertTrue(VersionedPacketHandler.handle(session, 1811, raw)));
    assertEquals("ReviveRogueAvatarScRsp", session.responseName);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), UniverseRevive46Test.code(session.response));
    assertEquals(before, new Gson().toJson(fixture.run()));
    assertEquals(saves, session.explorer.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unpacked", "packed", "mixed-empty-packed"})
  void validWireFormsReviveAndConvertTheClockOnce(String mode) throws Exception {
    var session = fixture.fixture.s;
    int saves = session.explorer.saves;
    assertTrue(VersionedPacketHandler.handle(session, 1811, request(mode)));
    assertEquals(0, UniverseRevive46Test.code(session.response));
    assertEquals(999, fixture.run().getCoins());
    assertFalse(fixture.run().getMiracles().containsKey(70));
    assertNotNull(fixture.run().getMiracles().get(9));
    assertEquals(10000, fixture.run().getVitals().get(UniverseRevive46Test.AVATAR).getHp());
    assertEquals(saves + 1, session.explorer.saves);
    var saved = new Gson().toJson(fixture.run());
    assertTrue(VersionedPacketHandler.handle(session, 1811, request(mode)));
    assertNotEquals(0, UniverseRevive46Test.code(session.response));
    assertEquals(saved, new Gson().toJson(fixture.run()));
    assertEquals(saves + 1, session.explorer.saves);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"INACTIVE", "WAITING_FOR_TOKEN", "WAITING_FOR_LOGIN", "PICKING_CHARACTER"})
  void inactiveSessionHasNoResponseOrGameplayChange(String name) throws Exception {
    var session = fixture.fixture.s;
    session.setState(SessionState.valueOf(name));
    var before = new Gson().toJson(fixture.run());
    int saves = session.explorer.saves;
    var response = session.response;
    assertTrue(VersionedPacketHandler.handle(session, 1811, request("unpacked")));
    assertSame(response, session.response);
    assertEquals(before, new Gson().toJson(fixture.run()));
    assertEquals(saves, session.explorer.saves);
  }
}
