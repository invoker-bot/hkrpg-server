package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original catalogue with an explicitly isolated scene fixture; no resonance or game UI proof. */
class UniverseAeon46Test {
  @TempDir Path resources;
  final Universe46LifecycleTest base = new Universe46LifecycleTest();
  JsonObject proof;
  java.lang.reflect.Field catalogue;
  Object previous;
  final Map<Integer, GameplayDefinitions.RogueUnlock> oldUnlocks = new HashMap<>();
  final Map<Integer, GameplayDefinitions.RogueRule> oldRules = new HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    base.setup();
    catalogue = RogueAeonCatalog.class.getDeclaredField("catalogue");
    catalogue.setAccessible(true);
    previous = catalogue.get(null);
    oldUnlocks.putAll(GameplayDefinitions.rogueUnlocks);
    oldRules.putAll(GameplayDefinitions.rogueRules);
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-aeon-catalogue-4.6.json")) {
      assertNotNull(in);
      proof =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    var excel = Files.createDirectories(resources.resolve("ExcelOutput"));
    Files.writeString(excel.resolve("RogueAeon.json"), proof.get("aeons").toString());
    RogueAeonCatalog.load(resources);
    var gson = new Gson();
    for (var row : proof.getAsJsonArray("unlocks")) {
      var unlock = gson.fromJson(row, GameplayDefinitions.RogueUnlock.class);
      GameplayDefinitions.rogueUnlocks.put(unlock.RogueUnlockID(), unlock);
    }
    for (var row : proof.getAsJsonArray("rules")) {
      var rule = gson.fromJson(row, GameplayDefinitions.RogueRule.class);
      GameplayDefinitions.rogueRules.put(rule.ID(), rule);
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    catalogue.set(null, previous);
    GameplayDefinitions.rogueUnlocks.clear();
    GameplayDefinitions.rogueUnlocks.putAll(oldUnlocks);
    GameplayDefinitions.rogueRules.clear();
    GameplayDefinitions.rogueRules.putAll(oldRules);
    base.reset();
  }

  DynamicMessage basic(DynamicMessage response) {
    var game = (DynamicMessage) Gameplay46.value(response, "rogue_game_info");
    var basic = (DynamicMessage) Gameplay46.value(game, "LFLCDNGALME");
    return (DynamicMessage) Gameplay46.value(basic, "AEFMMOHKCHJ");
  }

  int selected(DynamicMessage response) {
    var game = (DynamicMessage) Gameplay46.value(response, "rogue_game_info");
    var current = (DynamicMessage) Gameplay46.value(game, "CFDIKLBINHK");
    var aeon = (DynamicMessage) Gameplay46.value(current, "AEFMMOHKCHJ");
    assertTrue(aeon.getUnknownFields().asMap().isEmpty());
    assertEquals(0, Gameplay46.number(aeon, "unlock_aeon_enhance_num"));
    return Gameplay46.number(aeon, "JPPAGGPJOMO");
  }

  @Test
  void nativeQueryListsOnlyOriginalUnlockedPathsInOriginalSortOrder() throws Exception {
    var session = new Universe46LifecycleTest.ExplorerSession();
    session.request("GetRogueInfoCsReq");
    assertEquals(List.of(1, 2, 7), Gameplay46.value(basic(session.response), "OPAEGCBNACJ"));
    assertEquals(0, Gameplay46.number(basic(session.response), "unlock_aeon_enhance_num"));
    assertEquals(List.of(1, 2, 7), RogueAeonCatalog.available(session.explorer));
    assertTrue(session.explorer.getGameplay().getRewards().isEmpty());
  }

  @Test
  void actualFinishedUncancelledMissionsUnlockPropagationAndEruditionWithoutFakeKillCounters()
      throws Exception {
    var session = new Universe46LifecycleTest.ExplorerSession();
    for (int id : List.of(8013106, 8016105)) {
      var state = new QuestState(id);
      state.advance(1, 1, 1);
      session.explorer.getGameplay().getMainMissions().put(id, state);
    }
    session.explorer.getGameplay().getRogueWins().put(Universe46LifecycleTest.AREA, 1000);
    session.request("GetRogueInfoCsReq");
    assertEquals(List.of(1, 2, 7, 8, 9), Gameplay46.value(basic(session.response), "OPAEGCBNACJ"));
    session.explorer.getGameplay().getCancelledMissions().add(8013106);
    session.request("GetRogueInfoCsReq");
    assertEquals(List.of(1, 2, 7, 9), Gameplay46.value(basic(session.response), "OPAEGCBNACJ"));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 7})
  void nativeStartLeaveResumeAndJsonReloadPreserveOneActualChoice(int id) throws Exception {
    var session = new Universe46LifecycleTest.ExplorerSession();
    var started = session.start(List.of(199961), id);
    assertEquals(0, Gameplay46.number(started, "retcode"), "Unlocked path must start");
    assertEquals(id, selected(started));
    var run = session.explorer.getGameplay().getRogue();
    var identity = run.getRunId();
    assertEquals(0, Gameplay46.number(session.call("LeaveRogueCsReq", null), "retcode"));
    var gson = emu.lunarcore.data.ResourceLoader.getGson();
    var restored = gson.fromJson(gson.toJson(session.explorer.getGameplay()), GameplayState.class);
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(session.explorer, restored);
    assertEquals(id, selected(session.call("EnterRogueCsReq", Universe46LifecycleTest.AREA)));
    assertEquals(identity, restored.getRogue().getRunId());
    assertEquals(0, Gameplay46.number(session.call("LeaveRogueCsReq", null), "retcode"));
    assertEquals(
        0,
        Gameplay46.number(session.call("QuitRogueCsReq", Universe46LifecycleTest.AREA), "retcode"));
    assertEquals(0, Gameplay46.number(session.start(List.of(199961), 0), "retcode"));
    assertEquals(0, selected(session.response), "A new default run cannot inherit the old path");
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 4, 5, 6, 8, 9, 10, -1, Integer.MAX_VALUE})
  void lockedUnknownAndOutOfRangePathsDoNotCreateARun(int id) throws Exception {
    var session = new Universe46LifecycleTest.ExplorerSession();
    var prior = session.explorer.getGameplay().getRogue();
    assertNotEquals(0, Gameplay46.number(session.start(List.of(199961), id), "retcode"));
    assertSame(prior, session.explorer.getGameplay().getRogue());
    assertEquals(11, session.explorer.getEntryId());
    assertTrue(session.explorer.getGameplay().getRewards().isEmpty());
  }

  @Test
  void sceneFailureRollsBackSelectedPathAndRunWithoutChangingTheNormalLineup() throws Exception {
    var session = new Universe46LifecycleTest.ExplorerSession();
    var prior = session.explorer.getGameplay().getRogue();
    var normal = session.explorer.getCurrentLineup();
    var reject = UniverseLifecycleTest.Explorer.class.getDeclaredField("rejectScene");
    reject.setAccessible(true);
    reject.setBoolean(session.explorer, true);
    assertEquals(
        Retcode.ROGUE_AREA_INVALID.getVal(),
        Gameplay46.number(session.start(List.of(199961), 1), "retcode"));
    assertSame(prior, session.explorer.getGameplay().getRogue());
    assertSame(normal, session.explorer.getCurrentLineup());
    assertEquals(11, session.explorer.getEntryId());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "areaOverflow",
        "duplicateArea",
        "duplicateAeon",
        "duplicateAeonZero",
        "unknown",
        "wrongType",
        "truncated"
      })
  void nativeRawWireDoesNotTruncateMergeOrIgnoreTheSelectionRequest(String mode) throws Exception {
    var session = new Universe46LifecycleTest.ExplorerSession();
    var buffer = new ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(buffer);
    wire.writeUInt32(2, 199961);
    wire.writeUInt64(
        10,
        mode.equals("areaOverflow")
            ? (1L << 32) + Universe46LifecycleTest.AREA
            : Universe46LifecycleTest.AREA);
    if (mode.equals("duplicateArea")) wire.writeUInt32(10, Universe46LifecycleTest.AREA);
    if (mode.equals("duplicateAeon")) {
      wire.writeUInt32(5, 1);
      wire.writeUInt32(5, 0);
    }
    if (mode.equals("duplicateAeonZero")) {
      wire.writeUInt32(5, 0);
      wire.writeUInt32(5, 0);
    }
    if (mode.equals("unknown")) wire.writeUInt32(7, 1);
    if (mode.equals("wrongType")) wire.writeString(5, "1");
    if (mode.equals("truncated")) wire.writeTag(5, 0);
    wire.flush();
    var prior = session.explorer.getGameplay().getRogue();
    assertTrue(
        VersionedPacketHandler.handle(
            session, session.getProtocol().clientCommand("StartRogueCsReq"), buffer.toByteArray()));
    assertEquals("StartRogueScRsp", session.responseName);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    assertSame(prior, session.explorer.getGameplay().getRogue());
    assertEquals(11, session.explorer.getEntryId());
  }

  @Test
  void unavailableCatalogueDoesNotManufactureNonzeroSelectionsButKeepsDefaultStartCompatible()
      throws Exception {
    Files.delete(resources.resolve("ExcelOutput/RogueAeon.json"));
    RogueAeonCatalog.load(resources);
    var session = new Universe46LifecycleTest.ExplorerSession();
    session.request("GetRogueInfoCsReq");
    assertEquals(List.of(), Gameplay46.value(basic(session.response), "OPAEGCBNACJ"));
    assertNotEquals(0, Gameplay46.number(session.start(List.of(199961), 1), "retcode"));
    assertEquals(0, Gameplay46.number(session.start(List.of(199961), 0), "retcode"));
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 9})
  void originalMissionUnlockedPathCanBeSelectedAndCannotBeChangedDuringTheRun(int id)
      throws Exception {
    var session = new Universe46LifecycleTest.ExplorerSession();
    int mission = id == 8 ? 8013106 : 8016105;
    var state = new QuestState(mission);
    state.advance(1, 1, 1);
    session.explorer.getGameplay().getMainMissions().put(mission, state);
    assertEquals(0, Gameplay46.number(session.start(List.of(199961), id), "retcode"));
    assertEquals(id, selected(session.response));
    var run = session.explorer.getGameplay().getRogue();
    assertNotEquals(0, Gameplay46.number(session.start(List.of(199961), 1), "retcode"));
    assertSame(run, session.explorer.getGameplay().getRogue());
    session.request("GetRogueInfoCsReq");
    assertEquals(id, selected(session.response));
    assertTrue(session.explorer.getGameplay().getRewards().isEmpty());
  }

  @Test
  void removingCurrentCatalogueDoesNotRewriteAHistoricalPausedSelection() throws Exception {
    var session = new Universe46LifecycleTest.ExplorerSession();
    assertEquals(0, Gameplay46.number(session.start(List.of(199961), 7), "retcode"));
    assertEquals(0, Gameplay46.number(session.call("LeaveRogueCsReq", null), "retcode"));
    Files.delete(resources.resolve("ExcelOutput/RogueAeon.json"));
    RogueAeonCatalog.load(resources);
    var resumed = session.call("EnterRogueCsReq", Universe46LifecycleTest.AREA);
    assertEquals(0, Gameplay46.number(resumed, "retcode"));
    assertEquals(7, selected(resumed));
    assertEquals(List.of(), Gameplay46.value(basic(resumed), "OPAEGCBNACJ"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 7})
  void actualMorphiaTwoReadsPreservePausedSelectionAndRunIdentity(int id) throws Exception {
    var session = new Universe46LifecycleTest.ExplorerSession();
    assertEquals(0, Gameplay46.number(session.start(List.of(199961), id), "retcode"));
    var run = session.explorer.getGameplay().getRogue();
    var identity = run.getRunId();
    assertEquals(0, Gameplay46.number(session.call("LeaveRogueCsReq", null), "retcode"));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-aeon-selection");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123458001);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, session.explorer.getGameplay());
      store.save(document);
      for (int read = 0; read < 2; read++) {
        var loaded =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(identity, loaded.getGameplay().getRogue().getRunId());
        assertEquals(id, loaded.getGameplay().getRogue().getSelectedAeon());
        assertFalse(loaded.getGameplay().getRogue().isInside());
        assertEquals(11, loaded.getGameplay().getRogue().getReturnTo().getEntry());
        assertTrue(loaded.getGameplay().getRewards().isEmpty());
        if (read == 1) {
          gameplay.set(session.explorer, loaded.getGameplay());
          assertEquals(id, selected(session.call("EnterRogueCsReq", Universe46LifecycleTest.AREA)));
        }
      }
    } finally {
      mongo.shutdownNow();
    }
  }
}
