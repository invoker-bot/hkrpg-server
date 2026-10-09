package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original first-season identities; a catalog query cannot fabricate a played fight. */
class BoxingClubCatalog46Test {
  @TempDir Path root;
  String previous;
  JsonObject fixture;
  final Gameplay46HandlerTest.Session session = new Gameplay46HandlerTest.Session();

  @BeforeEach
  void setup() throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/boxing-club-catalog-4.6.json")) {
      assertNotNull(input);
      fixture =
          JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    assertEquals(
        "724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
    for (var raw : fixture.getAsJsonArray("inputs")) {
      var entry = raw.getAsJsonObject();
      var path = root.resolve(entry.get("path").getAsString());
      Files.createDirectories(path.getParent());
      Files.write(path, HexFormat.of().parseHex(entry.get("rawHex").getAsString()));
    }
    previous = LunarCore.getConfig().getResourceDir();
    resourceRoot(root.toString());
  }

  void resourceRoot(String value) throws Exception {
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true);
    field.set(LunarCore.getConfig(), value);
  }

  @AfterEach
  void restore() throws Exception {
    resourceRoot(previous);
  }

  void query(byte[] bytes) throws Exception {
    assertTrue(
        VersionedPacketHandler.handle(session, 4234, bytes), "4.6 boxing query remains unhandled");
    assertEquals("GetBoxingClubInfoScRsp", session.responseName);
    assertEquals(4225, session.getProtocol().clientCommand(session.responseName));
  }

  @Test
  void originalFirstSeasonAppearsWithoutEarnedRewardsOrAnActiveFight() throws Exception {
    String before = new Gson().toJson(session.player.getGameplay());
    query(new byte[0]);
    assertEquals(0, InitialData46Test.field(session.response, "retcode"));
    var field = session.response.getDescriptorForType().findFieldByName("challenge_list");
    assertEquals(5, session.response.getRepeatedFieldCount(field));
    for (int i = 0; i < 5; i++) {
      var row = (DynamicMessage) session.response.getRepeatedField(field, i);
      assertEquals(i + 1, InitialData46Test.field(row, "challenge_id"));
      for (String name :
          List.of("stage_index", "EAFICCLCLLA", "IFHHFBIJOFC", "LFFBKJBJJDD", "HODLKBMKMCF"))
        assertEquals(0, InitialData46Test.field(row, name));
      assertEquals(false, InitialData46Test.field(row, "MLOGJKEOCPP"));
      for (String name : List.of("avatar_list", "OOJOMKDMBBM", "EJEFDFEFOMH"))
        assertEquals(
            0, row.getRepeatedFieldCount(row.getDescriptorForType().findFieldByName(name)));
      assertTrue(row.getUnknownFields().asMap().isEmpty());
    }
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
    assertNull(session.player.getBattle());
    assertNull(session.player.getInstance());
  }

  @Test
  void originalWindowsLineEndingsKeepTheSameCatalog() throws Exception {
    for (var raw : fixture.getAsJsonArray("inputs")) {
      var path = root.resolve(raw.getAsJsonObject().get("path").getAsString());
      Files.writeString(path, Files.readString(path).replace("\n", "\r\n"));
    }
    originalFirstSeasonAppearsWithoutEarnedRewardsOrAnActiveFight();
  }

  @ParameterizedTest
  @ValueSource(strings = {"BoxingClubChallenge", "BoxingClubStageGroup", "BoxingClubStage"})
  void aMissingSourceCannotAdvertiseOriginalLevels(String table) throws Exception {
    Files.delete(root.resolve("ExcelOutput/" + table + ".json"));
    rejected(new byte[0]);
  }

  @Test
  void changedGroupEventsCannotReuseThePinnedSourceClaim() throws Exception {
    var path = root.resolve("ExcelOutput/BoxingClubStageGroup.json");
    var rows = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    rows.get(0).getAsJsonObject().getAsJsonArray("EventIDList").set(0, new JsonPrimitive(1));
    Files.writeString(path, rows.toString());
    rejected(new byte[0]);
  }

  @Test
  void aPayloadInTheEmptyQueryIsRejectedWithoutMutation() throws Exception {
    rejected(new byte[] {8, 1});
  }

  void rejected(byte[] bytes) throws Exception {
    String before = new Gson().toJson(session.player.getGameplay());
    query(bytes);
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(session.response, "retcode"));
    var field = session.response.getDescriptorForType().findFieldByName("challenge_list");
    assertEquals(0, session.response.getRepeatedFieldCount(field));
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
  }
}
