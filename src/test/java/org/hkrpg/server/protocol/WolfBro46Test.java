package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original level 3; group scripts and bullet placement are deliberately not synthesized. */
class WolfBro46Test {
  final Gson gson = emu.lunarcore.data.ResourceLoader.getGson();
  Session session;
  FloorInfo prior;
  MazePlaneExcel priorPlane;
  JsonArray fixture;
  Scene current;
  int saves;
  boolean failSave;
  Runnable beforeSave = () -> {};
  Map<Integer, org.hkrpg.server.gameplay.WolfBroCatalog.Level> priorLevels;

  class Session extends Gameplay46HandlerTest.Session {
    final Player actor =
        new Player() {
          final emu.lunarcore.game.player.lineup.PlayerLineup lineup =
              new emu.lunarcore.game.player.lineup.PlayerLineup(this, 0);

          public emu.lunarcore.game.player.lineup.PlayerLineup getCurrentLineup() {
            return lineup;
          }

          public Scene getScene() {
            return current;
          }

          public void save() {
            beforeSave.run();
            if (failSave) throw new IllegalStateException("isolated save failure");
            saves++;
          }
        };

    public Player getPlayer() {
      return actor;
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    fixture =
        JsonParser.parseString(
                Files.readString(Path.of("src/test/resources/fixtures/wolfbro-lifecycle-4.6.json")))
            .getAsJsonArray();
    session = new Session();
    session.actor.resetPosition();
    priorLevels = new HashMap<>(org.hkrpg.server.gameplay.WolfBroCatalog.levels);
    org.hkrpg.server.gameplay.WolfBroCatalog.levels.clear();
    for (var element : fixture) {
      var r = element.getAsJsonObject();
      if (!r.get("kind").getAsString().equals("level")) continue;
      var level = gson.fromJson(r.get("row"), org.hkrpg.server.gameplay.WolfBroCatalog.Level.class);
      assertTrue(level.valid());
      assertNull(org.hkrpg.server.gameplay.WolfBroCatalog.levels.put(level.WolfBroID(), level));
    }
    assertEquals(17, org.hkrpg.server.gameplay.WolfBroCatalog.levels.size());
    var floor = gson.fromJson(row("floor"), FloorInfo.class);
    for (var element : fixture) {
      var r = element.getAsJsonObject();
      if (!r.get("kind").getAsString().equals("group")) continue;
      var group = gson.fromJson(r.get("row"), GroupInfo.class);
      var path = r.get("source").getAsString();
      group.setId(Integer.parseInt(path.substring(path.lastIndexOf("_G") + 2, path.length() - 5)));
      floor.getGroups().put(group.getId(), group);
    }
    prior = GameData.getFloorInfos().put("P10301_F10301001", floor);
    var plane = gson.fromJson(row("plane"), MazePlaneExcel.class);
    priorPlane = GameData.getMazePlaneExcelMap().get(10301);
    GameData.getMazePlaneExcelMap().add(plane);
    current = new Scene(session.actor, plane, 10301001);
    current.setEntryId(1030102);
    for (var group : floor.getGroups().values()) current.loadGroup(group);
  }

  @AfterEach
  void restore() {
    if (priorPlane == null) GameData.getMazePlaneExcelMap().keySet().remove(10301);
    else GameData.getMazePlaneExcelMap().add(priorPlane);
    org.hkrpg.server.gameplay.WolfBroCatalog.levels.clear();
    org.hkrpg.server.gameplay.WolfBroCatalog.levels.putAll(priorLevels);
    if (prior == null) GameData.getFloorInfos().remove("P10301_F10301001");
    else GameData.getFloorInfos().put("P10301_F10301001", prior);
  }

  JsonElement row(String kind) {
    for (var element : fixture) {
      var r = element.getAsJsonObject();
      if (r.get("kind").getAsString().equals(kind)) return r.get("row");
    }
    throw new AssertionError(kind);
  }

  DynamicMessage motion(int x) {
    var b = Gameplay46.builder(session, "MotionInfo");
    for (String field : List.of("pos", "rot")) {
      var v = Gameplay46.builder(session, "Vector");
      Gameplay46.set(v, "x", field.equals("pos") ? x : -90000);
      Gameplay46.set(v, "y", field.equals("pos") ? -30000 : 0);
      Gameplay46.set(v, "z", field.equals("pos") ? 150000 : 0);
      Gameplay46.set(b, field, v.build());
    }
    return b.build();
  }

  DynamicMessage startRequest(int group, int state) {
    var b = Gameplay46.builder(session, "StartWolfBroGameCsReq");
    Gameplay46.set(b, "id", 3);
    Gameplay46.set(b, "motion", motion(127500));
    var g = Gameplay46.builder(session, "GroupStateInfo");
    Gameplay46.set(g, "group_id", group);
    Gameplay46.set(g, "group_state", state);
    Gameplay46.set(b, "IDANCEPOOEI", g.build());
    return b.build();
  }

  DynamicMessage request(String name, DynamicMessage req) throws Exception {
    session.response = null;
    assertTrue(
        VersionedPacketHandler.handle(
            session, session.getProtocol().clientCommand(name), req.toByteArray()),
        name);
    assertEquals(name.replace("CsReq", "ScRsp"), session.responseName);
    assertNotNull(session.response);
    assertTrue(session.response.getUnknownFields().asMap().isEmpty());
    return session.response;
  }

  DynamicMessage request(String name, int id) throws Exception {
    var b = Gameplay46.builder(session, name);
    Gameplay46.set(b, "id", id);
    return request(name, b.build());
  }

  DynamicMessage data(DynamicMessage response) {
    return (DynamicMessage) Gameplay46.value(response, "game_data");
  }

  int code(DynamicMessage response) {
    return Gameplay46.number(response, "retcode");
  }

  DynamicMessage start() throws Exception {
    var rsp = request("StartWolfBroGameCsReq", startRequest(156, 1));
    assertEquals(0, code(rsp));
    return data(rsp);
  }

  DynamicMessage archive(int x) throws Exception {
    var b = Gameplay46.builder(session, "ArchiveWolfBroGameCsReq");
    Gameplay46.set(b, "id", 3);
    Gameplay46.set(b, "motion", motion(x));
    return request("ArchiveWolfBroGameCsReq", b.build());
  }

  @Test
  void originalResourcesHaveRecordedHashesAndExactRows() throws Exception {
    assertEquals(23, fixture.size());
    for (var e : fixture) {
      var r = e.getAsJsonObject();
      assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", r.get("sourceCommit").getAsString());
      assertTrue(r.get("sha256").getAsString().matches("[0-9a-f]{64}"));
      assertTrue(r.get("sourceBlobSHA256").getAsString().matches("[0-9a-f]{64}"));
      assertNotNull(r.get("row"));
      var source = Path.of(r.get("source").getAsString());
      // The source-package verifier checks external originals; exported tests use bundled rows.
      if (!Files.isRegularFile(source)) continue;
      var raw = Files.readAllBytes(source);
      assertEquals(
          r.get("sha256").getAsString(),
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
      JsonElement v =
          JsonParser.parseString(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
      for (var segment : r.getAsJsonArray("path")) v = v.getAsJsonArray().get(segment.getAsInt());
      assertEquals(r.get("row"), v);
    }
  }

  @Test
  void queryDoesNotStartOrSaveAnUnplayedOriginalLevel() throws Exception {
    var d = data(request("GetWolfBroGameDataCsReq", 3));
    assertEquals(3, Gameplay46.number(d, "id"));
    assertEquals("", Gameplay46.value(d, "uuid"));
    assertEquals(0, saves);
  }

  @Test
  void originalStartReturnsUniqueGameIdentityAndInitialMotionWithoutFreeBullets() throws Exception {
    var d = start();
    assertFalse(((String) Gameplay46.value(d, "uuid")).isBlank());
    assertEquals(3, Gameplay46.number(d, "id"));
    assertEquals(0, Gameplay46.number(d, "CHCOPKAKBNO"));
    assertEquals(false, Gameplay46.value(d, "is_bullet_activated"));
    assertEquals(
        motion(127500),
        Gameplay46.value((DynamicMessage) Gameplay46.value(d, "BBEMIGELGJC"), "motion"));
    assertEquals(1, saves);
  }

  @Test
  void repeatedStartDoesNotResetTheActiveGameOrItsCheckpoint() throws Exception {
    var d = start();
    assertEquals(0, code(archive(140000)));
    int count = saves;
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", startRequest(156, 1))));
    assertEquals(count, saves);
    var q = data(request("GetWolfBroGameDataCsReq", 3));
    assertEquals(Gameplay46.value(d, "uuid"), Gameplay46.value(q, "uuid"));
    assertEquals(
        motion(140000),
        Gameplay46.value((DynamicMessage) Gameplay46.value(q, "BBEMIGELGJC"), "motion"));
  }

  @Test
  void originalArchiveAndRestoreRetainTheActualSignedMotion() throws Exception {
    var d = start();
    var a = archive(140000);
    assertEquals(0, code(a));
    assertEquals(
        motion(140000),
        Gameplay46.value((DynamicMessage) Gameplay46.value(data(a), "BBEMIGELGJC"), "motion"));
    var r = request("RestoreWolfBroGameArchiveCsReq", 3);
    assertEquals(0, code(r));
    assertEquals(data(a), data(r));
    assertEquals(Gameplay46.value(d, "uuid"), Gameplay46.value(data(r), "uuid"));
  }

  @Test
  void quitRetainsTheCheckpointAndRestartCreatesANewIdentity() throws Exception {
    var d = start();
    archive(140000);
    var q = request("QuitWolfBroGameCsReq", 3);
    assertEquals(0, code(q));
    assertEquals("", Gameplay46.value(data(q), "uuid"));
    assertNotEquals(0, code(request("RestoreWolfBroGameArchiveCsReq", 3)));
    assertEquals("", Gameplay46.value(data(request("GetWolfBroGameDataCsReq", 3)), "uuid"));
    assertNotEquals(Gameplay46.value(d, "uuid"), Gameplay46.value(start(), "uuid"));
    assertEquals(0, session.actor.getHcoin());
  }

  @Test
  void wrongControlGroupCannotStartTheOriginalLevel() throws Exception {
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", startRequest(163, 1))));
    assertEquals(0, saves);
  }

  @Test
  void anUnknownLevelCannotBeQueriedAsAnEmptySuccessfulGame() throws Exception {
    assertNotEquals(0, code(request("GetWolfBroGameDataCsReq", 999999)));
    assertEquals(0, saves);
  }

  @Test
  void archiveBeforeStartDoesNotCreateAPlayableRecord() throws Exception {
    assertNotEquals(0, code(archive(140000)));
    assertEquals(0, saves);
  }

  @Test
  void failedStartSavePublishesNoGameAndCanBeRetried() throws Exception {
    failSave = true;
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", startRequest(156, 1))));
    failSave = false;
    assertEquals("", Gameplay46.value(data(request("GetWolfBroGameDataCsReq", 3)), "uuid"));
    assertEquals(0, saves);
    start();
  }

  @Test
  void failedArchiveSaveLeavesThePreviousCheckpointIntact() throws Exception {
    var d = start();
    failSave = true;
    assertNotEquals(0, code(archive(140000)));
    failSave = false;
    assertEquals(d, data(request("GetWolfBroGameDataCsReq", 3)));
    assertEquals(1, saves);
  }

  @Test
  void failedQuitSaveLeavesTheActualGameActive() throws Exception {
    var d = start();
    failSave = true;
    assertNotEquals(0, code(request("QuitWolfBroGameCsReq", 3)));
    failSave = false;
    assertEquals(d, data(request("GetWolfBroGameDataCsReq", 3)));
    assertEquals(0, code(archive(140000)));
  }

  @Test
  void aDetachedOriginalPlaneCannotAuthorizeAStart() throws Exception {
    GameData.getMazePlaneExcelMap().add(gson.fromJson(row("plane"), MazePlaneExcel.class));
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", startRequest(156, 1))));
    assertEquals(0, saves);
  }

  @Test
  void aPlaneLookupAliasCannotAuthorizeAStart() throws Exception {
    var f = MazePlaneExcel.class.getDeclaredField("PlaneID");
    f.setAccessible(true);
    f.setInt(current.getExcel(), 199995);
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", startRequest(156, 1))));
    assertEquals(0, saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, -1})
  void onlyTheOriginalStartStateIsAccepted(int state) throws Exception {
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", startRequest(156, state))));
    assertEquals(0, saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE, 1, 2, 5})
  void originalIdsFromOtherFloorsAndUnsignedAliasesCannotCreateRecords(int id) throws Exception {
    assertNotEquals(0, code(request("GetWolfBroGameDataCsReq", id)));
    assertEquals(0, saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pos", "rot"})
  void incompleteCheckpointMotionIsRejected(String field) throws Exception {
    var m = motion(127500).toBuilder();
    m.clearField(m.getDescriptorForType().findFieldByName(field));
    var r = startRequest(156, 1).toBuilder();
    Gameplay46.set(r, "motion", m.build());
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", r.build())));
    assertEquals(0, saves);
  }

  @Test
  void missingMotionCannotStartOrOverwriteARealCheckpoint() throws Exception {
    var r = startRequest(156, 1).toBuilder();
    r.clearField(r.getDescriptorForType().findFieldByName("motion"));
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", r.build())));
    var d = start();
    assertNotEquals(0, code(request("ArchiveWolfBroGameCsReq", 3)));
    assertEquals(d, data(request("GetWolfBroGameDataCsReq", 3)));
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, 10_000_001})
  void unboundedCoordinatesCannotStartTheOriginalGame(int x) throws Exception {
    var r = startRequest(156, 1).toBuilder();
    Gameplay46.set(r, "motion", motion(x));
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", r.build())));
    assertEquals(0, saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"entry_id", "GMEHAMCHJBK"})
  void unrelatedGroupStateFieldsCannotChangeTheAuthoredControl(String field) throws Exception {
    var r = startRequest(156, 1).toBuilder();
    var g = ((DynamicMessage) Gameplay46.value(r.build(), "IDANCEPOOEI")).toBuilder();
    Gameplay46.set(g, field, 1030101);
    Gameplay46.set(r, "IDANCEPOOEI", g.build());
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", r.build())));
    assertEquals(0, saves);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unknownNestedAndTopLevelWireFieldsAreRejected(boolean nested) throws Exception {
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    var r = startRequest(156, 1).toBuilder();
    if (nested) {
      var m = motion(127500).toBuilder();
      m.setUnknownFields(unknown);
      Gameplay46.set(r, "motion", m.build());
    } else r.setUnknownFields(unknown);
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", r.build())));
    assertEquals(0, saves);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void duplicateAndLargeVarintIdsCannotAliasTheOriginalLevel(boolean large) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    out.writeUInt64(14, large ? 0x1_0000_0003L : 3);
    if (!large) out.writeUInt32(14, 3);
    out.flush();
    assertTrue(VersionedPacketHandler.handle(session, 6526, bytes.toByteArray()));
    assertNotEquals(0, code(session.response));
    assertEquals(0, saves);
  }

  @Test
  void aMissingCurrentSceneCannotExposeAPreviouslySavedCheckpoint() throws Exception {
    start();
    current = null;
    assertNotEquals(0, code(request("GetWolfBroGameDataCsReq", 3)));
    assertEquals(1, saves);
  }

  @Test
  void aReplacedFloorCacheCannotAuthorizeAnArchive() throws Exception {
    var d = start();
    GameData.getFloorInfos().put("P10301_F10301001", gson.fromJson(row("floor"), FloorInfo.class));
    assertNotEquals(0, code(archive(140000)));
    assertEquals(1, saves);
    assertEquals(
        d.getField(d.getDescriptorForType().findFieldByName("uuid")),
        session.actor.getGameplay().getWolfBro().getRuns().get(3).getUuid());
  }

  @Test
  void missingAuthoredGroupsCannotAuthorizeAStart() throws Exception {
    current.getFloorInfo().getGroups().remove(164);
    assertNotEquals(0, code(request("StartWolfBroGameCsReq", startRequest(156, 1))));
    assertEquals(0, saves);
  }

  @Test
  void aChangedOriginalLevelDoesNotSilentlyResetAPreviousCheckpoint() throws Exception {
    start();
    var prior = org.hkrpg.server.gameplay.WolfBroCatalog.levels.get(3);
    org.hkrpg.server.gameplay.WolfBroCatalog.levels.put(
        3,
        new org.hkrpg.server.gameplay.WolfBroCatalog.Level(
            3, 10301, 10301001, List.of(164, 163), List.of(), 156, 1, 0));
    assertNotEquals(0, code(request("GetWolfBroGameDataCsReq", 3)));
    assertNotEquals(0, code(archive(140000)));
    assertEquals(1, saves);
    org.hkrpg.server.gameplay.WolfBroCatalog.levels.put(3, prior);
    assertEquals(0, code(request("GetWolfBroGameDataCsReq", 3)));
  }

  static void set(Object object, String name, Object value) throws Exception {
    var f = object.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(object, value);
  }

  @ParameterizedTest
  @ValueSource(strings = {"uuid", "signature", "motion", "runs", "active"})
  void damagedSavedValuesAreHeldWithoutRepairOrRewards(String damage) throws Exception {
    start();
    var state = session.actor.getGameplay().getWolfBro();
    var run = state.getRuns().get(3);
    switch (damage) {
      case "uuid" -> set(run, "uuid", "damaged-value");
      case "signature" -> set(run, "sourceSignature", "damaged-value");
      case "motion" -> set(run, "archive", null);
      case "runs" -> set(state, "runs", null);
      case "active" -> set(state, "activeId", 0);
    }
    assertNotEquals(0, code(request("GetWolfBroGameDataCsReq", 3)));
    assertNotEquals(0, code(archive(140000)));
    assertNotEquals(0, code(request("QuitWolfBroGameCsReq", 3)));
    assertEquals(1, saves);
    assertEquals(0, session.actor.getHcoin());
    if (damage.equals("uuid")) assertEquals("damaged-value", run.getUuid());
  }

  @Test
  void twentyFourConcurrentStartsCreateOnlyOneActualIdentity() throws Exception {
    var manager = new org.hkrpg.server.gameplay.WolfBroManager(session.actor);
    var m =
        new org.hkrpg.server.gameplay.WolfBroState.Motion(
            new org.hkrpg.server.gameplay.WolfBroState.Point(127500, -30000, 150000),
            new org.hkrpg.server.gameplay.WolfBroState.Point(0, 0, 0));
    var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      var futures =
          new ArrayList<
              java.util.concurrent.Future<org.hkrpg.server.gameplay.WolfBroManager.Result>>();
      for (int i = 0; i < 24; i++)
        futures.add(executor.submit(() -> manager.start(3, 156, 1, 0, 0, m)));
      int successful = 0;
      for (var f : futures) if (f.get().code() == Retcode.SUCC) successful++;
      assertEquals(1, successful);
      assertEquals(1, saves);
      assertEquals(3, session.actor.getGameplay().getWolfBro().getActiveId());
    } finally {
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoActualPlayerMorphiaReadsPreserveActiveAndQuitCheckpoints(boolean quit) throws Exception {
    start();
    archive(140000);
    if (quit) request("QuitWolfBroGameCsReq", 3);
    var expected = data(request("GetWolfBroGameDataCsReq", 3));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "wolfbro-checkpoints");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456788);
      var name = Player.class.getDeclaredField("name");
      name.setAccessible(true);
      name.set(document, "checkpoint-fixture");
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, session.actor.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored = store.find(Player.class).first();
        assertNotNull(restored);
        assertEquals(quit ? 0 : 3, restored.getGameplay().getWolfBro().getActiveId());
        gameplay.set(session.actor, restored.getGameplay());
        assertEquals(expected, data(request("GetWolfBroGameDataCsReq", 3)));
        store.save(restored);
      }
      assertEquals(0, session.actor.getHcoin());
    } finally {
      mongo.shutdownNow();
    }
  }
}
