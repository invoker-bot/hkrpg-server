package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import org.junit.jupiter.api.*;

/** A disconnect during a visit must restore the exact previous ordinary-world location. */
class TrainCakeRoomTravelTest {
  static final int PLANE = 199945, FLOOR = 19994501, ENTRY = 19994501;
  final Gson gson = new Gson();
  FloorInfo oldFloor;
  MazePlaneExcel oldPlane;
  MapEntranceExcel oldEntrance, oldDestination;
  MazePlaneExcel plane;

  static class Actor extends Player {
    final PlayerLineup lineup = new PlayerLineup(this, 0);
    int saves, attempts, failAt = -1;
    boolean failSave;
    @Override public int getUid() { return 19994501; }
    @Override public PlayerLineup getCurrentLineup() { return lineup; }
    @Override public void save() {
      attempts++;
      if (failSave || attempts == failAt) throw new IllegalStateException("injected");
      saves++;
    }
  }

  @BeforeEach void setup() {
    oldFloor = GameData.getFloorInfos().get("P" + PLANE + "_F" + FLOOR);
    oldPlane = GameData.getMazePlaneExcelMap().get(PLANE);
    oldEntrance = GameData.getMapEntranceExcelMap().get(ENTRY);
    oldDestination = GameData.getMapEntranceExcelMap().get(ENTRY + 1);
    plane = gson.fromJson("{\"PlaneID\":199945,\"PlaneType\":\"Town\",\"WorldID\":1}", MazePlaneExcel.class);
    GameData.getMazePlaneExcelMap().add(plane);
    var floor = gson.fromJson("{\"FloorID\":19994501,\"DimensionList\":[{\"ID\":0,\"GroupIndexList\":[0]},{\"ID\":11001,\"GroupIndexList\":[0]}],\"GroupInstanceList\":[{\"ID\":8}]}", FloorInfo.class);
    var group = gson.fromJson("{\"GroupName\":\"Origin\",\"LoadSide\":\"Client\",\"PropList\":[],\"AnchorList\":[{\"ID\":1}]}", GroupInfo.class);
    group.setId(8); floor.getGroups().put(8, group);
    GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR, floor);
    GameData.getMapEntranceExcelMap().add(gson.fromJson("{\"ID\":19994501,\"PlaneID\":199945,\"FloorID\":19994501,\"StartGroupID\":8,\"StartAnchorID\":1}", MapEntranceExcel.class));
    GameData.getMapEntranceExcelMap().add(gson.fromJson("{\"ID\":19994502,\"PlaneID\":199945,\"FloorID\":19994501,\"StartGroupID\":8,\"StartAnchorID\":1}", MapEntranceExcel.class));
  }

  @AfterEach void cleanup() {
    if (oldFloor == null) GameData.getFloorInfos().remove("P" + PLANE + "_F" + FLOOR);
    else GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR, oldFloor);
    if (oldPlane == null) GameData.getMazePlaneExcelMap().keySet().remove(PLANE);
    else GameData.getMazePlaneExcelMap().add(oldPlane);
    if (oldEntrance == null) GameData.getMapEntranceExcelMap().keySet().remove(ENTRY);
    else GameData.getMapEntranceExcelMap().add(oldEntrance);
    if (oldDestination == null) GameData.getMapEntranceExcelMap().keySet().remove(ENTRY + 1);
    else GameData.getMapEntranceExcelMap().add(oldDestination);
  }

  static void set(Player player, String field, Object value) throws Exception {
    var f = Player.class.getDeclaredField(field); f.setAccessible(true); f.set(player, value);
  }

  Actor origin() throws Exception {
    var p = new Actor();
    set(p, "planeId", PLANE); set(p, "floorId", FLOOR); set(p, "entryId", ENTRY);
    set(p, "worldId", 1); set(p, "pos", new Position(100, 200, 300));
    set(p, "rot", new Position(0, 1234, 0));
    var scene = new Scene(p, plane, FLOOR); scene.setEntryId(ENTRY); set(p, "scene", scene);
    return p;
  }

  @Test void activeOrPendingGameplayCannotBeInterruptedByARoomVisit() throws Exception {
    var raid = origin();
    raid.getGameplay().beginMissionRaid(new GameplayState.MissionRaidRun(123, 1, 0, PLANE, FLOOR, ENTRY));
    var parkour = origin();
    parkour.getGameplay().getParkourVenue().enter(123, 1, PLANE, FLOOR, ENTRY, parkour);
    var rogue = origin(); rogue.getGameplay().getRogue().setInside(true);
    var trial = origin();
    var field = GameplayState.class.getDeclaredField("trialRun"); field.setAccessible(true);
    field.set(trial.getGameplay(), new TrialRun());
    var story = origin(); story.getGameplay().getStoryLines().begin(0, null, null);
    assertAll(
        () -> assertEquals(Retcode.SYSTEM_BUSY, TrainCakeRoomTravel.prepare(raid)),
        () -> assertEquals(Retcode.SYSTEM_BUSY, TrainCakeRoomTravel.prepare(parkour)),
        () -> assertEquals(Retcode.SYSTEM_BUSY, TrainCakeRoomTravel.prepare(rogue)),
        () -> assertEquals(Retcode.SYSTEM_BUSY, TrainCakeRoomTravel.prepare(trial)),
        () -> assertEquals(Retcode.SYSTEM_BUSY, TrainCakeRoomTravel.prepare(story)));
    for (var player : java.util.List.of(raid, parkour, rogue, trial, story)) {
      assertEquals(0, player.saves);
      assertFalse(TrainCakeRoomTravel.hasPending(player));
    }
  }

  @Test void anInterruptedVisitRestoresTheExactLocationBeforeAnyNewStoryAction() throws Exception {
    var p = origin();
    var missions = gson.toJson(p.getGameplay().getSubMissions());
    assertEquals(Retcode.SUCC, TrainCakeRoomTravel.prepare(p));
    assertTrue(TrainCakeRoomTravel.hasPending(p));
    set(p, "scene", null); set(p, "planeId", 10000); set(p, "floorId", 10000003);
    set(p, "entryId", 100000353); p.getPos().set(1, 2, 3);
    TrainCakeRoomTravel.recoverOnLogin(p);
    assertEquals(PLANE, p.getPlaneId()); assertEquals(FLOOR, p.getFloorId());
    assertEquals(ENTRY, p.getEntryId()); assertEquals(new Position(100, 200, 300), p.getPos());
    assertEquals(new Position(0, 1234, 0), p.getRot());
    assertFalse(TrainCakeRoomTravel.hasPending(p));
    assertEquals(missions, gson.toJson(p.getGameplay().getSubMissions()));
    assertEquals(2, p.saves);
  }

  @Test void theReturnBookmarkSurvivesAnH2RestartBeforeLoginRecovery() throws Exception {
    var actor = origin();
    assertEquals(Retcode.SUCC, TrainCakeRoomTravel.prepare(actor));
    var root = java.nio.file.Files.createTempDirectory("train-room-return-");
    var path = root.resolve("database.mv");
    try {
      var first = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.h2.H2Backend(path.toString()));
      var address = first.bind();
      try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
        var store = dev.morphia.Morphia.createDatastore(client, "return-test");
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
        var saved = new Player();
        set(saved, "uid", actor.getUid());
        set(saved, "gameplay", gson.fromJson(gson.toJson(actor.getGameplay()), GameplayState.class));
        store.save(saved);
      } finally { first.shutdownNow(); }
      var second = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.h2.H2Backend(path.toString()));
      address = second.bind();
      try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
        var store = dev.morphia.Morphia.createDatastore(client, "return-test");
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
        var restored = store.find(Player.class).first();
        assertNotNull(restored);
        set(actor, "gameplay", restored.getGameplay());
        set(actor, "scene", null); set(actor, "planeId", 10000); set(actor, "floorId", 10000003);
        set(actor, "entryId", 100000353); actor.getPos().set(1, 2, 3);
        TrainCakeRoomTravel.recoverOnLogin(actor);
        assertEquals(PLANE, actor.getPlaneId()); assertEquals(FLOOR, actor.getFloorId());
        assertEquals(ENTRY, actor.getEntryId()); assertEquals(new Position(100, 200, 300), actor.getPos());
        assertFalse(TrainCakeRoomTravel.hasPending(actor));
      } finally { second.shutdownNow(); }
    } finally {
      try (var files = java.nio.file.Files.list(root)) {
        for (var file : files.toList()) java.nio.file.Files.deleteIfExists(file);
      }
      java.nio.file.Files.deleteIfExists(root);
    }
  }

  @Test void aFailedBookmarkCannotEnterAndAFailedRecoveryKeepsTheBookmark() throws Exception {
    var p = origin(); p.failSave = true;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR, TrainCakeRoomTravel.prepare(p));
    assertFalse(TrainCakeRoomTravel.hasPending(p));
    p.failSave = false; assertEquals(Retcode.SUCC, TrainCakeRoomTravel.prepare(p));
    set(p, "scene", null); set(p, "planeId", 10000); set(p, "floorId", 10000003);
    set(p, "entryId", 100000353); p.getPos().set(1, 2, 3); p.failSave = true;
    assertThrows(RuntimeException.class, () -> TrainCakeRoomTravel.recoverOnLogin(p));
    assertTrue(TrainCakeRoomTravel.hasPending(p));
    assertEquals(10000, p.getPlaneId()); assertEquals(new Position(1, 2, 3), p.getPos());
  }

  @Test void aTemporaryRoomTransitionDoesNotCompleteAnOrdinaryEntranceObjective() throws Exception {
    int main = 19994511, sub = 199945111;
    var oldInfo = GameData.getMissionInfos().get(main);
    var oldMain = GameData.getMainMissionExcelMap().get(main);
    var oldSub = GameplayDefinitions.subs.get(sub);
    try {
      new GameplayProgressTest().mission(main, "Manual", Integer.toString(sub),
          "[{\"ID\":199945111,\"MainMissionID\":19994511,\"TakeType\":\"Auto\",\"FinishType\":\"EnterMapByEntrance\",\"ParamType\":\"Equal\",\"ParamInt1\":19994502}]");
      var p = origin();
      assertEquals(Retcode.SUCC, p.getMissionManager().accept(main));
      assertEquals(emu.lunarcore.game.quest.QuestState.Phase.DOING, p.getMissionManager().sub(sub).getPhase());
      assertEquals(Retcode.SUCC, TrainCakeRoomTravel.prepare(p));
      assertTrue(p.loadTrainRoomScene(PLANE, FLOOR, ENTRY + 1, new Position(4, 5, 6), new Position(), 11001));
      assertEquals(emu.lunarcore.game.quest.QuestState.Phase.DOING, p.getMissionManager().sub(sub).getPhase());
    } finally {
      if (oldInfo == null) GameData.getMissionInfos().remove(main); else GameData.getMissionInfos().put(main, oldInfo);
      if (oldMain == null) GameData.getMainMissionExcelMap().keySet().remove(main); else GameData.getMainMissionExcelMap().add(oldMain);
      if (oldSub == null) GameplayDefinitions.subs.remove(sub); else GameplayDefinitions.subs.put(sub, oldSub);
    }
  }

  @Test void leavingARoomRestoresTheOriginalSceneAndClearsTheBookmark() throws Exception {
    var p = origin();
    assertEquals(Retcode.SUCC, TrainCakeRoomTravel.prepare(p));
    assertTrue(p.loadTrainRoomScene(PLANE, FLOOR, ENTRY + 1,
        new Position(4, 5, 6), new Position(), 11001));
    assertEquals(Retcode.SUCC, TrainCakeRoomTravel.returnToOrigin(p));
    assertEquals(ENTRY, p.getEntryId());
    assertEquals(0, p.getScene().getDimensionId());
    assertEquals(new Position(100, 200, 300), p.getPos());
    assertEquals(new Position(0, 1234, 0), p.getRot());
    assertFalse(TrainCakeRoomTravel.hasPending(p));
  }

  @Test void aFailedReturnKeepsTheRoomAndCanBeRetried() throws Exception {
    var p = origin();
    assertEquals(Retcode.SUCC, TrainCakeRoomTravel.prepare(p));
    assertTrue(p.loadTrainRoomScene(PLANE, FLOOR, ENTRY + 1,
        new Position(4, 5, 6), new Position(), 11001));
    var room = p.getScene(); p.failSave = true;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR, TrainCakeRoomTravel.returnToOrigin(p));
    assertSame(room, p.getScene()); assertTrue(TrainCakeRoomTravel.hasPending(p));
    assertEquals(new Position(4, 5, 6), p.getPos());
    p.failSave = false;
    assertEquals(Retcode.SUCC, TrainCakeRoomTravel.returnToOrigin(p));
    assertFalse(TrainCakeRoomTravel.hasPending(p));
  }

  @Test void aFailedFinalReturnSaveKeepsTheBookmarkAndCanBeRetried() throws Exception {
    var p = origin();
    assertEquals(Retcode.SUCC, TrainCakeRoomTravel.prepare(p));
    assertTrue(p.loadTrainRoomScene(PLANE, FLOOR, ENTRY + 1,
        new Position(4, 5, 6), new Position(), 11001));
    var room = p.getScene(); p.failAt = p.attempts + 2;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR, TrainCakeRoomTravel.returnToOrigin(p));
    assertSame(room, p.getScene()); assertTrue(TrainCakeRoomTravel.hasPending(p));
    assertEquals(new Position(4, 5, 6), p.getPos());
    assertEquals(Retcode.SUCC, TrainCakeRoomTravel.returnToOrigin(p));
    assertFalse(TrainCakeRoomTravel.hasPending(p));
  }
}
