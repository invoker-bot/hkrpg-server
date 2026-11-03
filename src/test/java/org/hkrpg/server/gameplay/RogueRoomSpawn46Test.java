package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Original room and anchor records; only the final scene transport/save boundary is replaced. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RogueRoomSpawn46Test {
  static final int AREA = 19996, MAP = 1999601;
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, GameplayDefinitions.Room> oldRooms = new HashMap<>();
  final Map<Integer, GameplayDefinitions.Area> oldAreas = new HashMap<>();
  final Map<Integer, List<GameplayDefinitions.Site>> oldMaps = new HashMap<>();
  final Map<Integer, Map<Integer, List<Integer>>> oldPools = new HashMap<>();
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  final Map<Integer, MapEntranceExcel> oldEntries = new HashMap<>();
  final Map<Integer, MazePlaneExcel> oldPlanes = new HashMap<>();
  JsonObject fixture;

  static class Actor extends Player {
    final Position position = new Position(120, 30, 400), rotation = new Position(0, 90000, 0);
    final LineupManager teams = new LineupManager(this);
    final GameAvatar avatar = new GameAvatar() {
      @Override public int getAvatarId() { return 199961; }
      @Override public void save() {}
    };
    int entry = 2000101, plane = 20001, floor = 20001001, saves;
    Scene scene;
    emu.lunarcore.server.game.GameSession session;
    boolean rejectScene, failSave;
    @Override public Position getPos() { return position; }
    @Override public Position getRot() { return rotation; }
    @Override public LineupManager getLineupManager() { return teams; }
    @Override public GameAvatar getAvatarById(int id) { return id == 199961 ? avatar : null; }
    @Override public int getEntryId() { return entry; }
    @Override public int getPlaneId() { return plane; }
    @Override public int getFloorId() { return floor; }
    @Override public Scene getScene() { return scene; }
    @Override public emu.lunarcore.server.game.GameSession getSession() { return session; }
    Actor() {
      getAvatars().getAvatars().put(199961, avatar);
      getCurrentLineup().getAvatars().add(199961);
      var town = ResourceLoader.getGson().fromJson(
          "{\"PlaneID\":20001,\"PlaneType\":\"Town\",\"WorldID\":2}", MazePlaneExcel.class);
      GameData.getMazePlaneExcelMap().add(town);
      scene = new Scene(this, town, floor);
      scene.setEntryId(entry);
    }
    @Override public void save() {
      if (failSave) throw new IllegalStateException("fixture save rejected");
      saves++;
    }
    @Override public boolean loadScene(int p, int f, int e, Position pos, Position rot, boolean send) {
      if (rejectScene) return false;
      plane = p; floor = f; entry = e;
      position.set(pos); rotation.set(rot);
      scene = new Scene(this, GameData.getMazePlaneExcelMap().get(p), f);
      scene.setEntryId(e);
      return true;
    }
  }

  static class Session extends emu.lunarcore.server.game.GameSession {
    final org.hkrpg.server.protocol.ProtocolBridge protocol =
        org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    Session() { super(null); }
    @Override public org.hkrpg.server.protocol.ProtocolBridge getProtocol() { return protocol; }
    @Override public void send(emu.lunarcore.server.packet.BasePacket packet) {}
    @Override public void sendClientPacket(String name, byte[] bytes) {}
  }

  @BeforeAll void prepare() throws Exception {
    oldRooms.putAll(GameplayDefinitions.rooms); oldAreas.putAll(GameplayDefinitions.areas);
    oldMaps.putAll(GameplayDefinitions.maps); oldPools.putAll(GameplayDefinitions.roomPoolByMap);
    oldFloors.putAll(GameData.getFloorInfos());
    GameData.getMapEntranceExcelMap().values().forEach(row -> oldEntries.put(row.getId(), row));
    GameData.getMazePlaneExcelMap().values().forEach(row -> oldPlanes.put(row.getId(), row));
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-room-spawns-4.6.json")) {
      fixture = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject();
    }
    for (var row : fixture.getAsJsonArray("rooms")) {
      var room = gson.fromJson(row, GameplayDefinitions.Room.class);
      GameplayDefinitions.rooms.put(room.RogueRoomID(), room);
    }
    for (var item : fixture.getAsJsonObject("floors").entrySet())
      GameData.getFloorInfos().put(item.getKey(), gson.fromJson(item.getValue(), FloorInfo.class));
    for (var item : fixture.getAsJsonArray("groups")) {
      var row = item.getAsJsonObject();
      var group = gson.fromJson(row, GroupInfo.class); group.setId(row.get("id").getAsInt());
      GameData.getFloorInfos().get(row.get("floor").getAsString()).getGroups().put(group.getId(), group);
    }
    for (var item : fixture.getAsJsonArray("entries")) {
      var entry = gson.fromJson(item, MapEntranceExcel.class);
      GameData.getMapEntranceExcelMap().add(entry);
      GameData.getMazePlaneExcelMap().add(gson.fromJson("{\"PlaneID\":" + entry.getPlaneID()
          + ",\"PlaneType\":\"Rogue\",\"WorldID\":8}", MazePlaneExcel.class));
    }
    GameplayDefinitions.areas.put(AREA, new GameplayDefinitions.Area(AREA, 19996, 1, 0, 0));
    GameplayDefinitions.maps.put(MAP, List.of(new GameplayDefinitions.Site(MAP, 1, true, List.of(2), 1),
        new GameplayDefinitions.Site(MAP, 2, false, List.of(), 3)));
    GameplayDefinitions.roomPoolByMap.put(MAP, Map.of(1, List.of(304), 2, List.of(305)));
  }

  @AfterAll void restore() {
    GameplayDefinitions.rooms.clear(); GameplayDefinitions.rooms.putAll(oldRooms);
    GameplayDefinitions.areas.clear(); GameplayDefinitions.areas.putAll(oldAreas);
    GameplayDefinitions.maps.clear(); GameplayDefinitions.maps.putAll(oldMaps);
    GameplayDefinitions.roomPoolByMap.clear(); GameplayDefinitions.roomPoolByMap.putAll(oldPools);
    GameData.getFloorInfos().clear(); GameData.getFloorInfos().putAll(oldFloors);
    GameData.getMapEntranceExcelMap().keySet().clear(); oldEntries.values().forEach(GameData.getMapEntranceExcelMap()::add);
    GameData.getMazePlaneExcelMap().keySet().clear(); oldPlanes.values().forEach(GameData.getMazePlaneExcelMap()::add);
  }

  AnchorInfo point(int roomId) {
    var room = GameplayDefinitions.rooms.get(roomId);
    var entry = GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    return GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID()).getAnchorInfo(room.GroupID(), 1);
  }
  void atRoom(Actor player, int id) {
    assertEquals(point(id).getPos().toProto(), player.getPos().toProto(),
        "Room " + id + " must use its own entrance, not the shared floor origin");
    assertEquals(point(id).getRot().toProto(), player.getRot().toProto());
  }

  @Test void startUsesTheActualFirstWorldRoomEntrance() {
    var player = new Actor();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(AREA));
    atRoom(player, 304);
    assertEquals(76718, player.getPos().getY());
  }

  @Test void nextRoomOnTheSameFloorUsesItsOwnEntrance() {
    var player = new Actor();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(AREA));
    player.getGameplay().getRogue().clear();
    assertEquals(Retcode.SUCC, player.getUniverseManager().enter(2));
    atRoom(player, 305);
  }

  @Test void pausedRunResumesAtItsCurrentRoomEntrance() {
    var player = new Actor();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(AREA));
    assertEquals(Retcode.SUCC, player.getUniverseManager().leave(false));
    assertEquals(Retcode.SUCC, player.getUniverseManager().resume(AREA, false));
    atRoom(player, 304);
  }

  Stream<Integer> originalRooms() {
    return fixture.getAsJsonArray("rooms").asList().stream()
        .map(row -> row.getAsJsonObject().get("RogueRoomID").getAsInt());
  }
  @ParameterizedTest @MethodSource("originalRooms")
  void allAuthoredRoomTypesResumeAtTheirOwnEntrance(int roomId) {
    var player = new Actor();
    var run = new GameplayState.RogueRun(AREA, MAP, Map.of(1, roomId));
    run.setupTeam(player, List.of(199961)); run.enter(1, roomId); run.setInside(false);
    player.getGameplay().setRogue(run);
    assertEquals(Retcode.SUCC, player.getUniverseManager().resume(AREA, false));
    atRoom(player, roomId);
  }

  Actor stranded() {
    var player = new Actor();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(AREA));
    player.session = new Session();
    player.getPos().set(0, 0, 0); player.getRot().set(0, 37000, 0);
    return player;
  }

  @Test void existingLoginHookRepairsOnlyThePositionAndPreservesTheEntireRun() {
    var player = stranded();
    String before = gson.toJson(player.getGameplay());
    int saves = player.saves;
    assertTrue(RogueFirstStorySpawn.recoverOnLogin(player));
    atRoom(player, 304);
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(saves + 1, player.saves);
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(player));
    assertEquals(saves + 1, player.saves, "Repair is naturally idempotent at the correct point");
  }

  @Test void validMovedPositionAndPausedRunAreNotRelocated() {
    var player = stranded();
    player.getPos().set(point(304).getPos()); player.getPos().add(1000, 0, 0);
    var before = player.getPos().clone(); int saves = player.saves;
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(player));
    assertEquals(before, player.getPos()); assertEquals(saves, player.saves);
    player.getPos().set(0, 0, 0); player.getGameplay().getRogue().setInside(false);
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(player));
    assertEquals(new Position(), player.getPos());
  }

  @Test void repairSaveFailureRestoresTheOriginalPositionAndRotation() {
    var player = stranded();
    String before = gson.toJson(player.getGameplay());
    var pos = player.getPos().clone(); var rot = player.getRot().clone();
    player.failSave = true;
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(player));
    assertEquals(pos, player.getPos()); assertEquals(rot, player.getRot());
    assertEquals(before, gson.toJson(player.getGameplay()));
  }

  @Test void mismatchedSavedRoomOrSceneCannotBeRepaired() {
    var player = stranded();
    player.getGameplay().getRogue().enter(2, 304); // The stored assignment at site 2 is 305.
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(player));
    player.getGameplay().getRogue().enter(1, 304); player.entry = 8000102;
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(player));
    assertEquals(new Position(), player.getPos());
  }

  @Test void missingOrDuplicatedAuthoredAnchorDoesNotRepairAnOldRun() {
    var player = stranded();
    var group = GameData.getFloorInfo(80001, 80001003).getGroups().get(18);
    var saved = new ArrayList<>(group.getAnchorList());
    try {
      group.getAnchorList().clear();
      assertFalse(RogueFirstStorySpawn.recoverOnLogin(player));
      group.getAnchorList().addAll(saved); group.getAnchorList().add(saved.getFirst());
      assertFalse(RogueFirstStorySpawn.recoverOnLogin(player));
      assertEquals(new Position(), player.getPos());
    } finally { group.getAnchorList().clear(); group.getAnchorList().addAll(saved); }
  }

  @Test void missingRoomAnchorRejectsStartWithoutPublishingOrMovingTheRun() {
    var player = new Actor(); var prior = player.getGameplay().getRogue();
    var group = GameData.getFloorInfo(80001, 80001003).getGroups().get(18);
    var saved = new ArrayList<>(group.getAnchorList()); var pos = player.getPos().clone();
    try {
      group.getAnchorList().clear();
      assertEquals(Retcode.ROGUE_AREA_INVALID, player.getUniverseManager().start(AREA));
      assertSame(prior, player.getGameplay().getRogue()); assertEquals(pos, player.getPos());
      assertEquals(0, player.saves);
    } finally { group.getAnchorList().addAll(saved); }
  }

  @Test void failedNextRoomKeepsThePriorRoomAndItsPosition() {
    var player = new Actor(); assertEquals(Retcode.SUCC, player.getUniverseManager().start(AREA));
    var run = player.getGameplay().getRogue(); run.clear();
    var group = GameData.getFloorInfo(80001, 80001003).getGroups().get(21);
    var saved = new ArrayList<>(group.getAnchorList());
    var pos = player.getPos().clone(); int saves = player.saves;
    try {
      group.getAnchorList().clear();
      assertEquals(Retcode.ROGUE_AREA_INVALID, player.getUniverseManager().enter(2));
      assertEquals(1, run.getSiteId()); assertEquals(304, run.getRoomId());
      assertEquals(pos, player.getPos()); assertEquals(saves, player.saves);
    } finally { group.getAnchorList().addAll(saved); }
  }

  @Test void ordinaryOriginRecoveryCannotBypassTheFirstTutorialMissionGuards() {
    var player = new Actor(); player.session = new Session();
    var original = GameplayDefinitions.rooms.get(100);
    var map = GameplayDefinitions.maps.get(1);
    try {
      // A changed tutorial binding must not fall through to ordinary-room recovery.
      GameplayDefinitions.rooms.put(100, new GameplayDefinitions.Room(100, 1, 8000101, 10, Map.of(10, 9999)));
      GameplayDefinitions.maps.put(1, List.of(new GameplayDefinitions.Site(1, 1, true, List.of(), 1)));
      var run = new GameplayState.RogueRun(100, 1, Map.of(1, 100));
      run.setupTeam(player, List.of(199961)); run.enter(1, 100);
      player.getGameplay().setRogue(run);
      player.entry = 8000101; player.plane = 80001; player.floor = 80001001;
      player.getPos().set(0, 0, 0);
      assertFalse(RogueFirstStorySpawn.recoverOnLogin(player));
      assertEquals(new Position(), player.getPos()); assertEquals(0, player.saves);
    } finally {
      GameplayDefinitions.rooms.put(100, original);
      if (map == null) GameplayDefinitions.maps.remove(1); else GameplayDefinitions.maps.put(1, map);
    }
  }
}
