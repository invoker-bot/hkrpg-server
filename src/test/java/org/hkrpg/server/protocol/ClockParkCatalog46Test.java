package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.server.packet.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.ClockParkCatalog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Actual original objects; no script execution, reached chapter or reward is fabricated. */
class ClockParkCatalog46Test {
  @TempDir Path root;
  String oldResources;
  JsonObject fixture;
  final Gameplay46HandlerTest.Session session = new Gameplay46HandlerTest.Session();

  @BeforeEach
  void prepare() throws Exception {
    fixture =
        JsonParser.parseString(
                new String(
                    Objects.requireNonNull(
                            getClass().getResourceAsStream("/fixtures/clock-park-catalog-4.6.json"))
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var entry : fixture.getAsJsonObject("files").entrySet()) {
      var path = root.resolve(entry.getKey());
      Files.createDirectories(path.getParent());
      Files.write(
          path,
          HexFormat.of().parseHex(entry.getValue().getAsJsonObject().get("rawHex").getAsString()));
    }
    oldResources = LunarCore.getConfig().getResourceDir();
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true);
    field.set(LunarCore.getConfig(), root.toString());
  }

  @AfterEach
  void restore() throws Exception {
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true);
    field.set(LunarCore.getConfig(), oldResources);
  }

  void request(byte[] raw) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 7234, raw));
    assertEquals("ClockParkGetInfoScRsp", session.responseName);
    assertEquals(7206, session.getProtocol().clientCommand(session.responseName));
  }

  @Test
  void originalSixScriptsAppearWithNoFabricatedReachedChaptersOrCompletion() throws Exception {
    String before = new Gson().toJson(session.player.getGameplay());
    request(new byte[0]);
    assertEquals(0, InitialData46Test.field(session.response, "retcode"));
    var field = session.response.getDescriptorForType().findFieldByName("DCGDHFJLHCN");
    assertEquals(6, session.response.getRepeatedFieldCount(field));
    for (int i = 0; i < 6; i++) {
      var row = (com.google.protobuf.DynamicMessage) session.response.getRepeatedField(field, i);
      assertEquals(i + 1, InitialData46Test.field(row, "script_id"));
      assertEquals(
          0, row.getRepeatedFieldCount(row.getDescriptorForType().findFieldByName("CFJOGMJIHOE")));
    }
    assertEquals(0, InitialData46Test.field(session.response, "progress"));
    assertEquals(0, InitialData46Test.field(session.response, "CDGICHDIIHH"));
    assertEquals(0, InitialData46Test.field(session.response, "LNKAKGOCAOF"));
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
  }

  @Test
  void missingRecoveredObjectsCannotAdvertiseAUsableCatalog() throws Exception {
    Files.delete(root.resolve("ServerData/ClockParkStories.json"));
    request(new byte[0]);
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(session.response, "retcode"));
    assertEquals(
        0,
        session.response.getRepeatedFieldCount(
            session.response.getDescriptorForType().findFieldByName("DCGDHFJLHCN")));
  }

  @Test
  void unknownQueryFieldsAreRejectedWithoutPlayerMutation() throws Exception {
    String before = new Gson().toJson(session.player.getGameplay());
    request(new byte[] {8, 1});
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(session.response, "retcode"));
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        10101, 10201, 10301, 20101, 20201, 20301, 20401, 20501, 20601, 30101, 30201, 30301, 30401,
        30501, 30601, 40101, 40201, 40301, 40401, 40501, 40601, 50101, 50201, 50301, 50401, 50501,
        50601
      })
  void allOriginalStoriesRetainTheirConfigGraphPairAndByteFingerprints(int id) {
    var catalog = ClockParkCatalog.load(root);
    assertNotNull(catalog);
    var story = catalog.stories().get(id);
    assertNotNull(story);
    assertEquals(id, story.id());
    assertTrue(story.fingerprint().matches("[a-f0-9]{64}:[a-f0-9]{64}"));
    assertEquals("ClockParkStoryConfig", story.config().get("$type").getAsString());
    assertEquals("LevelGraphConfig", story.graph().get("$type").getAsString());
    assertTrue(
        story
            .config()
            .get("LevelGraphConfigPath")
            .getAsString()
            .startsWith("Config/ConfigActivityClockPark/"));
    assertTrue(story.graph().has("OnStartSequece"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ExcelOutput/ClockParkScriptConfig.json",
        "ExcelOutput/ClockParkChapterConfig.json",
        "ExcelOutput/ClockParkRound.json",
        "ExcelOutput/ClockParkStory.json",
        "ServerData/ClockParkStories.json"
      })
  void missingInputsRejectTheWholeCatalog(String path) throws Exception {
    Files.delete(root.resolve(path));
    assertNull(ClockParkCatalog.load(root));
    request(new byte[0]);
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(session.response, "retcode"));
    assertEquals(
        0,
        session.response.getRepeatedFieldCount(
            session.response.getDescriptorForType().findFieldByName("DCGDHFJLHCN")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ExcelOutput/ClockParkScriptConfig.json",
        "ExcelOutput/ClockParkChapterConfig.json",
        "ExcelOutput/ClockParkRound.json",
        "ExcelOutput/ClockParkStory.json",
        "ServerData/ClockParkStories.json"
      })
  void changedInputsRejectTheWholeCatalogAndKeepOperatorBytes(String path) throws Exception {
    var target = root.resolve(path);
    byte[] changed =
        (Files.readString(target) + " ").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(target, changed);
    assertNull(ClockParkCatalog.load(root));
    request(new byte[0]);
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(session.response, "retcode"));
    assertArrayEquals(changed, Files.readAllBytes(target));
  }

  @Test
  void scriptAndChapterCatalogRetainsOriginalGatesTopologyAndUnknownRoundDefaults() {
    var catalog = ClockParkCatalog.load(root);
    assertNotNull(catalog);
    assertEquals(6, catalog.scripts().size());
    assertEquals(28, catalog.chapters().size());
    assertEquals(169, catalog.roundCount());
    assertEquals(27, catalog.stories().size());
    assertEquals(
        new ClockParkCatalog.Script(1, 5001202, "Normal", 101, "FinishSubMission", 802210101),
        catalog.scripts().get(1));
    assertEquals(
        new ClockParkCatalog.Script(6, 5001202, "Endless", 701, "FinishMainMission", 8022104),
        catalog.scripts().get(6));
    var first = catalog.chapters().get(101);
    assertEquals(List.of(102), first.next());
    assertEquals(List.of(10101, 10102, 10103), first.rounds());
    assertEquals(List.of(10101), first.stories());
    assertEquals(10107, first.successRound());
    assertEquals(List.of(0), catalog.chapters().get(103).next());
    assertEquals(List.of(), catalog.chapters().get(701).stories());
    assertFalse(catalog.round(10101).has("RoundType"));
    assertEquals("BranchSelect", catalog.round(10107).get("RoundType").getAsString());
    assertNull(catalog.round(1));
  }

  @Test
  void returnedObjectsCannotChangeOriginalCatalogState() {
    var catalog = ClockParkCatalog.load(root);
    assertNotNull(catalog);
    var story = catalog.stories().get(10101);
    var originalConfig = story.config();
    var originalGraph = story.graph();
    var originalRound = catalog.round(10101);
    story.config().addProperty("LevelGraphConfigPath", "changed");
    story.graph().remove("OnStartSequece");
    catalog.round(10101).addProperty("RoundType", "invented");
    assertEquals(originalConfig, story.config());
    assertEquals(originalGraph, story.graph());
    assertEquals(originalRound, catalog.round(10101));
    assertThrows(UnsupportedOperationException.class, () -> catalog.stories().clear());
    assertThrows(UnsupportedOperationException.class, () -> catalog.scripts().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> catalog.chapters().get(101).next().clear());
  }

  @Test
  void repeatQueryPreservesEveryInputAndEntireGameplayState() throws Exception {
    var bytes = new HashMap<Path, byte[]>();
    for (var entry : fixture.getAsJsonObject("files").entrySet()) {
      var path = root.resolve(entry.getKey());
      bytes.put(path, Files.readAllBytes(path));
    }
    String before = new Gson().toJson(session.player.getGameplay());
    request(new byte[0]);
    byte[] first = session.response.toByteArray();
    request(new byte[0]);
    assertArrayEquals(first, session.response.toByteArray());
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
    for (var entry : bytes.entrySet())
      assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
  }

  @Test
  void legacyProtocolAndOtherCommandsAreNotClaimed() throws Exception {
    var legacy =
        new Gameplay46HandlerTest.Session() {
          @Override
          public ProtocolBridge getProtocol() {
            return ProtocolBridge.forVersion("4.2.0");
          }
        };
    assertFalse(ClockPark46.handle(legacy, 7234, new byte[0]));
    assertFalse(ClockPark46.handle(session, 7229, new byte[0]));
    assertNull(legacy.responseName);
    assertNull(session.responseName);
  }
}
