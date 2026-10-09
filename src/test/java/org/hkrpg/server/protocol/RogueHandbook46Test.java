package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The original explicit handbook link, rather than a display-ID alias or complete catalogue. */
class RogueHandbook46Test {
  @TempDir Path temp;
  final Gson gson = new Gson();
  final Map<Integer, GameplayDefinitions.Miracle> oldMiracles = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();
  final List<GameplayDefinitions.Blessing> oldBlessings = new ArrayList<>();
  final List<MazeBuffExcel> oldMaze = new ArrayList<>();
  final Gameplay46HandlerTest.Session session = new Gameplay46HandlerTest.Session();

  @BeforeEach
  void load() throws Exception {
    oldMiracles.putAll(GameplayDefinitions.miracles);
    oldEffects.putAll(GameplayDefinitions.miracleEffects);
    oldBlessings.addAll(GameplayDefinitions.blessings);
    oldMaze.addAll(GameData.getMazeBuffExcelMap().values());
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracleEffects.clear();
    GameplayDefinitions.blessings.clear();
    GameData.getMazeBuffExcelMap().values().clear();
    var handbooks = new JsonArray();
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-handbook-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(122, rows.size());
      for (var raw : rows) {
        var file = raw.getAsJsonObject();
        var row = file.getAsJsonObject("row");
        switch (file.get("kind").getAsString()) {
          case "handbook" -> handbooks.add(row);
          case "miracle" -> {
            var value = gson.fromJson(row, GameplayDefinitions.Miracle.class);
            GameplayDefinitions.miracles.put(value.MiracleID(), value);
          }
          case "effect" -> {
            var value = gson.fromJson(row, GameplayDefinitions.MiracleEffect.class);
            GameplayDefinitions.miracleEffects.put(value.MiracleEffectID(), value);
          }
          case "buff" ->
              GameplayDefinitions.blessings.add(
                  gson.fromJson(row, GameplayDefinitions.Blessing.class));
          case "maze" ->
              GameData.getMazeBuffExcelMap().add(gson.fromJson(row, MazeBuffExcel.class));
          default -> fail(file.toString());
        }
      }
    }
    Files.createDirectories(temp.resolve("ExcelOutput"));
    Files.writeString(temp.resolve("ExcelOutput/RogueHandbookMiracle.json"), handbooks.toString());
    // The baseline has no catalogue; its absence does not turn a behavior regression into a compile
    // failure.
    try {
      Class.forName("org.hkrpg.server.gameplay.RogueHandbookCatalog")
          .getMethod("load", Path.class)
          .invoke(null, temp);
    } catch (ClassNotFoundException absentOnBaseline) {
    }
    session.player.getGameplay().setRogue(new GameplayState.RogueRun(100, 1, Map.of()));
  }

  @AfterEach
  void restore() throws Exception {
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracles.putAll(oldMiracles);
    GameplayDefinitions.miracleEffects.clear();
    GameplayDefinitions.miracleEffects.putAll(oldEffects);
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.blessings.addAll(oldBlessings);
    GameData.getMazeBuffExcelMap().values().clear();
    oldMaze.forEach(GameData.getMazeBuffExcelMap()::add);
    try {
      Class.forName("org.hkrpg.server.gameplay.RogueHandbookCatalog")
          .getMethod("load", Path.class)
          .invoke(null, temp.resolve("missing"));
    } catch (ClassNotFoundException absentOnBaseline) {
    }
  }

  private DynamicMessage query() throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 5684, new byte[0]));
    assertEquals("GetRogueHandbookDataScRsp", session.responseName);
    assertEquals(0, InitialData46Test.field(session.response, "retcode"));
    return (DynamicMessage) InitialData46Test.field(session.response, "HAMKPLCBEBG");
  }

  @Test
  void realAcquisitionIsRecordedOutsideTheCurrentExploration() {
    assertTrue(RogueMiracles.acquire(session.player, 4));
    session.player.getGameplay().setRogue(new GameplayState().getRogue());
    var state =
        JsonParser.parseString(JsonUtils.encode(session.player.getGameplay())).getAsJsonObject();
    assertTrue(
        state.has("rogueHandbook"), "Acquisition history is absent from the player document");
    assertEquals("[4]", state.getAsJsonObject("rogueHandbook").get("miracles").toString());
  }

  @Test
  void handbookReportsOnlyActuallyCollectedCanonicalBlessings() throws Exception {
    session.player.getGameplay().getRogueCollectedBuffs().addAll(List.of(600000, 199999999));
    var data = query();
    var rows = (List<?>) InitialData46Test.field(data, "buff_list");
    assertEquals(1, rows.size());
    assertEquals(600000, InitialData46Test.field((DynamicMessage) rows.getFirst(), "maze_buff_id"));
    assertTrue(((List<?>) InitialData46Test.field(data, "MAJGMMCOCIG")).isEmpty());
  }

  @Test
  void newAcquisitionIsReportedByTheNativeMiracleHandbookField() throws Exception {
    assertTrue(RogueMiracles.acquire(session.player, 4));
    var data = query();
    var rows = (List<?>) InitialData46Test.field(data, "MAJGMMCOCIG");
    assertEquals(1, rows.size());
    var row = (DynamicMessage) rows.getFirst();
    assertEquals(4, InitialData46Test.field(row, "NPNMLPNBHDP"));
    assertEquals(false, InitialData46Test.field(row, "ALAJJMKOMML"));
    assertTrue(((List<?>) InitialData46Test.field(data, "PMIFFBHOOOO")).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void nonemptyOrMalformedEmptyQueryIsRejectedBeforeAnyCollectionWrite(int index) throws Exception {
    var bodies =
        List.of(
            new byte[] {8, 0},
            new byte[] {0},
            new byte[] {(byte) 128},
            new byte[] {10, 0},
            new byte[] {8, (byte) 128});
    var before = JsonUtils.encode(session.player.getGameplay());
    assertTrue(VersionedPacketHandler.handle(session, 5684, bodies.get(index)));
    assertEquals(5, InitialData46Test.field(session.response, "retcode"));
    assertEquals(before, JsonUtils.encode(session.player.getGameplay()));
  }

  @Test
  void queryingEmptyHistoryDoesNotCollectCurrentOwnershipOrOffers() throws Exception {
    var run = session.player.getGameplay().getRogue();
    run.getMiracles().put(4, new GameplayState.RogueMiracle(1));
    run.getBlessings().put(600000, 1);
    run.offerMiracles(List.of(4));
    String before = JsonUtils.encode(session.player.getGameplay());
    var data = query();
    assertTrue(((List<?>) InitialData46Test.field(data, "buff_list")).isEmpty());
    assertTrue(((List<?>) InitialData46Test.field(data, "MAJGMMCOCIG")).isEmpty());
    assertEquals(before, JsonUtils.encode(session.player.getGameplay()));
  }

  @Test
  void acquisitionHistorySurvivesGameplayDocumentRoundTripAndRunRemoval() throws Exception {
    assertTrue(RogueMiracles.acquire(session.player, 4));
    session.player.getGameplay().getRogueCollectedBuffs().add(600000);
    var restored =
        JsonUtils.decode(JsonUtils.encode(session.player.getGameplay()), GameplayState.class);
    restored.setRogue(new GameplayState().getRogue());
    assertEquals(Set.of(4), restored.getRogueHandbook().getMiracles());
    assertEquals(Set.of(600000), restored.getRogueCollectedBuffs());
  }

  @Test
  void actualPlayerMorphiaTwoSavesKeepHistoryAfterRunRemoval() throws Exception {
    assertTrue(RogueMiracles.acquire(session.player, 4));
    session.player.getGameplay().getRogueCollectedBuffs().add(600000);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-handbook");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456781);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, session.player.getGameplay());
      store.save(document);
      var restored = store.find(Player.class).first();
      assertEquals(Set.of(4), restored.getGameplay().getRogueHandbook().getMiracles());
      assertEquals(Set.of(600000), restored.getGameplay().getRogueCollectedBuffs());
      restored.getGameplay().setRogue(new GameplayState().getRogue());
      store.save(restored);
      var second = store.find(Player.class).first();
      assertEquals(Set.of(4), second.getGameplay().getRogueHandbook().getMiracles());
      assertEquals(Set.of(600000), second.getGameplay().getRogueCollectedBuffs());
      assertEquals(GameplayState.RogueRun.Phase.IDLE, second.getGameplay().getRogue().getPhase());
    } finally {
      mongo.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {4, 10, 18, 45, 46, 64, 71})
  void onlyTheOriginalExplicitUnlockLinkCreatesAHandbookIdentity(int id) {
    int expected = Set.of(10, 18, 46).contains(id) ? 0 : id;
    assertEquals(expected, RogueHandbookCatalog.unlock(id));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void damagedOrFullHistoryRejectsAcquisitionWithoutChangingTheRun(int kind) throws Exception {
    var field = RogueHandbookState.class.getDeclaredField("miracles");
    field.setAccessible(true);
    Object value =
        switch (kind) {
          case 0 -> null;
          case 1 -> new HashSet<>(List.of(-1));
          case 2 -> new HashSet<>(Arrays.asList((Integer) null));
          case 3, 4 -> {
            var ids = new HashSet<Integer>();
            for (int id = 10000; id < 10000 + (kind == 3 ? 4097 : 4096); id++) ids.add(id);
            yield ids;
          }
          default -> throw new AssertionError();
        };
    field.set(session.player.getGameplay().getRogueHandbook(), value);
    String before = JsonUtils.encode(session.player.getGameplay());
    assertFalse(RogueMiracles.acquire(session.player, 4));
    assertEquals(before, JsonUtils.encode(session.player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void damagedPersistentHistoryReturnsPlayerDataErrorWithoutResettingIt(int kind) throws Exception {
    var field = RogueHandbookState.class.getDeclaredField("miracles");
    field.setAccessible(true);
    field.set(
        session.player.getGameplay().getRogueHandbook(),
        kind == 0 ? null : new HashSet<>(kind == 1 ? List.of(-1) : Arrays.asList((Integer) null)));
    String before = JsonUtils.encode(session.player.getGameplay());
    assertTrue(VersionedPacketHandler.handle(session, 5684, new byte[0]));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR.getVal(),
        InitialData46Test.field(session.response, "retcode"));
    assertEquals(before, JsonUtils.encode(session.player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void queryRequiresAnAuthenticatedActiveSession(int kind) throws Exception {
    session.setState(
        List.of(
                SessionState.WAITING_FOR_TOKEN,
                SessionState.WAITING_FOR_LOGIN,
                SessionState.INACTIVE)
            .get(kind));
    assertTrue(VersionedPacketHandler.handle(session, 5684, new byte[0]));
    assertNull(session.response);
  }

  private void dice(org.junit.jupiter.api.function.Executable assertions) throws Throwable {
    var source = new RogueDiceCurioTest();
    source.setup();
    try {
      session
          .player
          .getGameplay()
          .getRogue()
          .setupTeam(new UniverseLifecycleTest.Explorer(), List.of(199961));
      assertions.execute();
    } finally {
      source.cleanup();
    }
  }

  @Test
  void changingHandbookResourcesBeforeDiceCommitRejectsTheFrozenProjection() throws Throwable {
    dice(
        () -> {
          var plan = RogueMiracleDice.prepare(session.player);
          assertNotNull(plan);
          RogueHandbookCatalog.load(temp.resolve("missing"));
          String before = JsonUtils.encode(session.player.getGameplay());
          assertNull(plan.commit(session.player));
          assertEquals(before, JsonUtils.encode(session.player.getGameplay()));
        });
  }

  @Test
  void diceCommitsTemporaryDiceAndReplacementHistoryTogether() throws Throwable {
    dice(
        () -> {
          String before = JsonUtils.encode(session.player.getGameplay());
          var plan = RogueMiracleDice.prepare(session.player);
          assertNotNull(plan);
          assertEquals(before, JsonUtils.encode(session.player.getGameplay()));
          assertNotNull(plan.commit(session.player));
          assertTrue(session.player.getGameplay().getRogueHandbook().getMiracles().contains(21));
          assertFalse(session.player.getGameplay().getRogue().getMiracles().containsKey(21));
          var history = session.player.getGameplay().getRogueHandbook().getMiracles();
          for (int id : session.player.getGameplay().getRogue().getMiracles().keySet())
            assertTrue(history.contains(RogueHandbookCatalog.unlock(id)));
        });
  }

  @Test
  void dicePublishesActualNativeHandbookNotificationAfterCommittedActions() throws Throwable {
    dice(
        () -> {
          var online = new Universe46Test.OnlineSession();
          online.getPlayer().getGameplay().setRogue(session.player.getGameplay().getRogue());
          var plan = RogueMiracleDice.prepare(online.getPlayer());
          assertNotNull(plan);
          var result = plan.commit(online.getPlayer());
          assertNotNull(result);
          result.syncEffects(online.getPlayer());
          assertTrue(online.names.contains("SyncRogueHandbookDataUpdateScNotify"));
          assertTrue(
              online.names.indexOf("SyncRogueHandbookDataUpdateScNotify")
                  > online.names.indexOf("SyncRogueCommonActionResultScNotify"));
          var update =
              online.notifications.get(online.names.indexOf("SyncRogueHandbookDataUpdateScNotify"));
          var rows = (List<?>) InitialData46Test.field(update, "BFDFABHMCNE");
          assertEquals(
              online.getPlayer().getGameplay().getRogueHandbook().getMiracles(),
              rows.stream()
                  .map(
                      row -> (Integer) InitialData46Test.field((DynamicMessage) row, "NPNMLPNBHDP"))
                  .collect(java.util.stream.Collectors.toSet()));
          var buffRows = (List<?>) InitialData46Test.field(update, "IPNAAGCMNLB");
          assertEquals(
              online.getPlayer().getGameplay().getRogueCollectedBuffs(),
              buffRows.stream()
                  .map(row -> (Integer) InitialData46Test.field((DynamicMessage) row, "maze_buff_id"))
                  .collect(java.util.stream.Collectors.toSet()));
        });
  }
}
