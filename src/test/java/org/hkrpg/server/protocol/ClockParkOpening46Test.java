package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original five opening chapters; prerequisite completion and windows are explicit fixtures. */
class ClockParkOpening46Test {
  @TempDir Path root;
  final Gson gson = new Gson();
  final ClockParkCatalog46Test inputs = new ClockParkCatalog46Test();
  ActivityPanelExcel oldPanel;
  Map<Integer, MissionInfo.SubMissionInfo> oldSubs;
  List<GameplayDefinitions.Window> oldWindows;
  int level = 21, saves;
  int failSaveAt;
  boolean failSave;
  final Player player =
      new Player() {
        public int getLevel() {
          return level;
        }

        public void save() {
          if (failSave || failSaveAt > 0 && saves + 1 == failSaveAt) {
            failSaveAt = 0;
            throw new IllegalStateException("fixture save failure");
          }
          saves++;
        }
      };
  final Gameplay46HandlerTest.Session session =
      new Gameplay46HandlerTest.Session() {
        public Player getPlayer() {
          return ClockParkOpening46Test.this.player;
        }
      };

  @BeforeEach
  void prepare() throws Exception {
    // Historical tests explicitly pin the legacy project pool; new-default tests replace it.
    var policy = ClockParkState.class.getDeclaredField("nextCardPool");
    policy.setAccessible(true);
    policy.set(player.getGameplay().getClockPark(), ClockParkCards.POOL_RULE);
    inputs.root = root;
    inputs.prepare();
    var fixture =
        JsonParser.parseString(
                new String(
                    Objects.requireNonNull(
                            getClass().getResourceAsStream("/fixtures/clock-park-opening-4.6.json"))
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
    oldPanel = GameData.getActivityPanelExcelMap().get(50012);
    GameData.getActivityPanelExcelMap()
        .add(
            gson.fromJson(
                fixture
                    .getAsJsonObject("records")
                    .getAsJsonArray("ExcelOutput/ActivityPanel.json")
                    .get(0),
                ActivityPanelExcel.class));
    oldSubs = new HashMap<>(GameplayDefinitions.subs);
    for (var entry : fixture.getAsJsonObject("records").entrySet())
      if (entry.getKey().contains("MissionInfo")) {
        var info = gson.fromJson(entry.getValue().getAsJsonArray().get(0), MissionInfo.class);
        for (var sub : info.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
      }
    oldWindows = new ArrayList<>(GameplayDefinitions.windows);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(50012, 5001202, 0, Long.MAX_VALUE));
  }

  @AfterEach
  void restore() throws Exception {
    inputs.restore();
    GameData.getActivityPanelExcelMap().keySet().remove(50012);
    if (oldPanel != null) GameData.getActivityPanelExcelMap().add(oldPanel);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(oldWindows);
  }

  static int gate(int script) {
    return new int[] {802210101, 802210801, 802212004, 802212404, 802210311}[script - 1];
  }

  void unlock(int script) {
    int sub = gate(script), main = GameplayDefinitions.subs.get(sub).getMainMissionID();
    player.getGameplay().getMainMissions().put(main, new QuestState(main));
    var done = new QuestState(sub);
    done.advance(1, 1, 123);
    player.getGameplay().getSubMissions().put(sub, done);
  }

  DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(session.getProtocol().clientSchema().message(name));
  }

  void handle(String name, byte[] body) throws Exception {
    session.responseName = null;
    session.response = null;
    assertTrue(
        VersionedPacketHandler.handle(session, session.getProtocol().clientCommand(name), body));
    assertEquals(name.replace("CsReq", "ScRsp"), session.responseName);
    assertTrue(session.response.getUnknownFields().asMap().isEmpty());
  }

  void start(int script) throws Exception {
    var req = request("ClockParkStartScriptCsReq");
    req.setField(req.getDescriptorForType().findFieldByName("script_id"), script);
    handle("ClockParkStartScriptCsReq", req.build().toByteArray());
  }

  void quit(int script, boolean manual) throws Exception {
    var req = request("ClockParkQuitScriptCsReq");
    req.setField(req.getDescriptorForType().findFieldByName("CNHLAAHDPGA"), script);
    req.setField(req.getDescriptorForType().findFieldByName("MHICFGAPPNB"), manual);
    handle("ClockParkQuitScriptCsReq", req.build().toByteArray());
  }

  int code() {
    return (Integer) InitialData46Test.field(session.response, "retcode");
  }

  JsonObject state() {
    return JsonParser.parseString(gson.toJson(player.getGameplay()))
        .getAsJsonObject()
        .getAsJsonObject("clockPark");
  }

  void replaceGameplay(GameplayState value) throws Exception {
    var f = Player.class.getDeclaredField("gameplay");
    f.setAccessible(true);
    f.set(player, value);
  }

  @Test
  void inconsistentSequenceCannotValidateMultipleReachedReceipts() throws Exception {
    unlock(1);
    unlock(2);
    start(1);
    quit(1, true);
    start(2);
    quit(2, true);
    var json = JsonParser.parseString(gson.toJson(player.getGameplay())).getAsJsonObject();
    json.getAsJsonObject("clockPark").addProperty("sequence", 1);
    replaceGameplay(gson.fromJson(json, GameplayState.class));
    String before = gson.toJson(player.getGameplay());
    handle("ClockParkGetInfoCsReq", new byte[0]);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), code());
    assertEquals(before, gson.toJson(player.getGameplay()));
  }

  @ParameterizedTest
  @CsvSource({
    "1,false", "2,false", "3,false", "4,false", "5,false", "1,true", "2,true", "3,true", "4,true",
    "5,true"
  })
  void actualPlayerMorphiaReloadPreservesOpeningOrQuitExactly(int script, boolean quit)
      throws Exception {
    unlock(script);
    start(script);
    assertEquals(0, code());
    if (quit) quit(script, true);
    handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    var expected = session.response;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-opening-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.set(document, 199986);
      var f = Player.class.getDeclaredField("gameplay");
      f.setAccessible(true);
      f.set(document, player.getGameplay());
      store.save(document);
      String gameplay = gson.toJson(document.getGameplay());
      for (int attempt = 0; attempt < 2; attempt++) {
        var saved = Objects.requireNonNull(store.find(Player.class).first());
        assertEquals(gameplay, gson.toJson(saved.getGameplay()));
        assertTrue(saved.getGameplay().getClockPark().valid(ClockParkManager.catalog()));
        replaceGameplay(saved.getGameplay());
        handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
        assertEquals(expected, session.response);
        assertEquals(gameplay, gson.toJson(saved.getGameplay()));
      }
    } finally {
      server.shutdownNow();
    }
    replaceGameplay(gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(expected, session.response);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "round",
        "story",
        "chapter",
        "source",
        "transaction",
        "openingRule",
        "attributeA",
        "runSequence",
        "stateSequence",
        "reached",
        "nullState"
      })
  void corruptSavedStateDoesNotManufactureOrRepairAnOpening(String mode) throws Exception {
    unlock(1);
    start(1);
    var json = JsonParser.parseString(gson.toJson(player.getGameplay())).getAsJsonObject();
    var state = json.getAsJsonObject("clockPark");
    var run = state.getAsJsonObject("active");
    switch (mode) {
      case "round", "story", "chapter", "attributeA" -> run.addProperty(mode, 1);
      case "source", "transaction", "openingRule" -> run.addProperty(mode, "changed");
      case "runSequence" -> run.addProperty("sequence", 2);
      case "stateSequence" -> state.addProperty("sequence", 0);
      case "reached" -> state.add("reached", JsonNull.INSTANCE);
      case "nullState" -> json.add("clockPark", JsonNull.INSTANCE);
    }
    replaceGameplay(gson.fromJson(json, GameplayState.class));
    String before = gson.toJson(player.getGameplay());
    handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), code());
    start(1);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), code());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(1, saves);
  }

  static void field(Object target, String name, Object value) throws Exception {
    var f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void originalFirstStoryIsSavedBeforeStartAndRepeatQueryIsStable(int script) throws Exception {
    unlock(script);
    start(script);
    assertEquals(0, code());
    assertEquals(1, saves);
    assertEquals(script, InitialData46Test.field(session.response, "script_id"));
    String before = gson.toJson(player.getGameplay());
    handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(0, code());
    int chapter = new int[] {101, 201, 301, 410, 510}[script - 1];
    assertEquals(chapter, InitialData46Test.field(session.response, "DCAHJELNJNP"));
    assertEquals(script, InitialData46Test.field(session.response, "script_id"));
    assertEquals(List.of(chapter), InitialData46Test.field(session.response, "FKNBOIIMFLD"));
    var waiting = (DynamicMessage) InitialData46Test.field(session.response, "HGDLJDAFHLI");
    assertEquals(chapter * 100 + 1, InitialData46Test.field(waiting, "JGPHDBDHKKO"));
    assertEquals(
        5,
        waiting
            .getOneofFieldDescriptor(waiting.getDescriptorForType().getOneofs().get(0))
            .getNumber());
    assertNotNull(InitialData46Test.field(session.response, "JOFJEIFLGBP"));
    assertFalse(((String) InitialData46Test.field(session.response, "IMJFLCKFJGF")).isBlank());
    byte[] snapshot = session.response.toByteArray();
    handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertArrayEquals(snapshot, session.response.toByteArray());
    assertEquals(1, saves);
    assertEquals(before, gson.toJson(player.getGameplay()));
    handle("ClockParkGetInfoCsReq", new byte[0]);
    assertEquals(script, InitialData46Test.field(session.response, "LNKAKGOCAOF"));
    for (Object raw : (List<?>) InitialData46Test.field(session.response, "DCGDHFJLHCN")) {
      var row = (DynamicMessage) raw;
      int id = (Integer) InitialData46Test.field(row, "script_id");
      assertEquals(
          id == script ? List.of(chapter) : List.of(), InitialData46Test.field(row, "CFJOGMJIHOE"));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void unrelatedOrUnfinishedPrerequisiteCannotStart(int script) throws Exception {
    unlock(script == 5 ? 1 : script + 1);
    String before = gson.toJson(player.getGameplay());
    start(script);
    assertEquals(Retcode.FUNC_CHECK_FAILED.getVal(), code());
    assertEquals(0, saves);
    assertEquals(before, gson.toJson(player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "parentMissing",
        "parentCancelled",
        "subCancelled",
        "wrongProgress",
        "finishTimeMissing",
        "wrongIdentity",
        "definitionMissing",
        "definitionParentChanged"
      })
  void invalidPrerequisiteRecordsNeverUnlock(String mode) throws Exception {
    unlock(1);
    var sub = player.getGameplay().getSubMissions().get(gate(1));
    switch (mode) {
      case "parentMissing" -> player.getGameplay().getMainMissions().clear();
      case "parentCancelled" -> player.getGameplay().getCancelledMissions().add(8022101);
      case "subCancelled" -> player.getGameplay().getCancelledSubs().add(gate(1));
      case "wrongProgress" -> field(sub, "progress", 2);
      case "finishTimeMissing" -> field(sub, "finishTime", 0L);
      case "wrongIdentity" -> field(sub, "questId", 802210102);
      case "definitionMissing" -> GameplayDefinitions.subs.remove(gate(1));
      case "definitionParentChanged" -> {
        var copy =
            gson.fromJson(
                gson.toJson(GameplayDefinitions.subs.get(gate(1))),
                MissionInfo.SubMissionInfo.class);
        copy.setMainMissionID(8022108);
        GameplayDefinitions.subs.put(gate(1), copy);
      }
    }
    start(1);
    assertNotEquals(0, code());
    assertEquals(0, saves);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missingWindow", "expired", "future", "foreignModule", "foreignPanel", "level20"})
  void operatorWindowAndOriginalLevelGateAreRequired(String mode) throws Exception {
    unlock(1);
    GameplayDefinitions.windows.clear();
    switch (mode) {
      case "expired" ->
          GameplayDefinitions.windows.add(new GameplayDefinitions.Window(50012, 5001202, 0, 1));
      case "future" ->
          GameplayDefinitions.windows.add(
              new GameplayDefinitions.Window(50012, 5001202, Long.MAX_VALUE - 1, Long.MAX_VALUE));
      case "foreignModule" ->
          GameplayDefinitions.windows.add(
              new GameplayDefinitions.Window(50012, 5001201, 0, Long.MAX_VALUE));
      case "foreignPanel" ->
          GameplayDefinitions.windows.add(
              new GameplayDefinitions.Window(50022, 5001202, 0, Long.MAX_VALUE));
      case "level20" -> {
        level = 20;
        GameplayDefinitions.windows.add(
            new GameplayDefinitions.Window(50012, 5001202, 0, Long.MAX_VALUE));
      }
    }
    start(1);
    assertEquals(Retcode.FUNC_CHECK_FAILED.getVal(), code());
    assertEquals(0, saves);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void quitOnlyMatchingRunWithoutManufacturingCompletionAndCanStartAgain(boolean manual)
      throws Exception {
    unlock(1);
    start(1);
    assertEquals(0, code());
    String transaction = state().getAsJsonObject("active").get("transaction").getAsString();
    quit(2, manual);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code());
    assertEquals(1, saves);
    quit(1, manual);
    assertEquals(0, code());
    assertEquals(2, saves);
    handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(0, code());
    assertEquals(0, InitialData46Test.field(session.response, "script_id"));
    quit(1, manual);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code());
    assertEquals(2, saves);
    start(1);
    assertEquals(0, code());
    assertEquals(3, saves);
    assertNotEquals(
        transaction, state().getAsJsonObject("active").get("transaction").getAsString());
    assertEquals(0, player.getGameplay().getDailyPoints());
    assertTrue(player.getGameplay().getRewards().isEmpty());
  }

  @Test
  void repeatedStartAndOtherScriptCannotOverwriteActiveRun() throws Exception {
    unlock(1);
    unlock(2);
    start(1);
    assertEquals(0, code());
    String before = gson.toJson(player.getGameplay());
    start(1);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    start(2);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(1, saves);
  }

  @Test
  void concurrentStartsSaveExactlyOneRunAndReachedReceipt() throws Exception {
    unlock(1);
    unlock(2);
    var ready = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () -> {
                ready.await();
                return new ClockParkManager(player).start(1);
              });
      var second =
          pool.submit(
              () -> {
                ready.await();
                return new ClockParkManager(player).start(2);
              });
      ready.countDown();
      var a = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
      var b = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(Set.of(Retcode.SUCC, Retcode.SYSTEM_BUSY), Set.of(a.code(), b.code()));
      var actual = a.code() == Retcode.SUCC ? a.run() : b.run();
      assertEquals(actual, player.getGameplay().getClockPark().getActive());
      assertEquals(
          List.of(actual.getChapter()),
          player.getGameplay().getClockPark().reached(actual.getScript()));
      assertTrue(
          player.getGameplay().getClockPark().reached(actual.getScript() == 1 ? 2 : 1).isEmpty());
      assertEquals(1, saves);
    }
  }

  @Test
  void failedSaveRollsBackAndDoesNotAcknowledgeAnUnsavedRun() throws Exception {
    unlock(1);
    String before = gson.toJson(player.getGameplay());
    failSave = true;
    start(1);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    assertEquals(before, gson.toJson(player.getGameplay()));
    failSave = false;
    start(1);
    assertEquals(0, code());
    String active = gson.toJson(player.getGameplay());
    failSave = true;
    quit(1, true);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    assertEquals(active, gson.toJson(player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 6, 7, -1})
  void unsupportedOrUnknownScriptsAreRejectedWithoutState(int script) throws Exception {
    start(script);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code());
    assertEquals(0, saves);
  }

  @Test
  void unsupportedCheckpointOperationCannotCompleteAnOpening() throws Exception {
    unlock(1);
    start(1);
    assertEquals(0, code());
    String before = gson.toJson(player.getGameplay());
    var req = request("ClockParkHandleWaitOperationCsReq");
    req.setField(req.getDescriptorForType().findFieldByName("script_id"), 1);
    req.setField(req.getDescriptorForType().findFieldByName("JGPHDBDHKKO"), 10101);
    req.setField(
        req.getDescriptorForType().findFieldByName("MLHNHAFPLKD"), request("MOEMKBLCKKO").build());
    handle("ClockParkHandleWaitOperationCsReq", req.build().toByteArray());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(1, saves);
  }

  @Test
  void unknownDuplicateAndOverflowWireFieldsCannotAliasScriptOne() throws Exception {
    unlock(1);
    for (byte[] bytes :
        new byte[][] {
          {64, 1, 64, 1},
          {64, 1, 8, 1},
          {64, (byte) 0x81, (byte) 0x80, (byte) 0x80, (byte) 0x80, 16},
          {64, 1, (byte) 0x78, 1}
        }) {
      handle("ClockParkStartScriptCsReq", bytes);
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code());
    }
    assertEquals(0, saves);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ExcelOutput/ActivityPanel.json",
        "ExcelOutput/ActivityConfig.json",
        "Config/Level/Mission/8022101/MissionInfo_8022101.partial.json",
        "ServerData/ClockParkStories.json"
      })
  void changedSourcesRejectStartWithoutOverwritingOperatorData(String name) throws Exception {
    unlock(1);
    var path = root.resolve(name);
    byte[] changed =
        (Files.readString(path) + " ").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(path, changed);
    start(1);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code());
    assertEquals(0, saves);
    assertArrayEquals(changed, Files.readAllBytes(path));
  }
}
