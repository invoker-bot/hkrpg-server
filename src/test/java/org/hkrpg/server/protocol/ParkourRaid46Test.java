package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.SceneInfoOuterClass.SceneInfo;
import emu.lunarcore.util.Position;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class ParkourRaid46Test {
  static final int RAID = 44306001,
      PLANE = 44306,
      HOME = 1999341,
      HOME_PLANE = 199934,
      SUB = 19993401,
      LEVEL = 19993402,
      AVATAR = 19993403;
  final GameplayDefinitions.Window window =
      new GameplayDefinitions.Window(50039, 5003901, 0, 4102444800L);
  final Gson gson = new Gson();

  static class Racer extends Player {
    int entry = HOME, plane = HOME_PLANE, floor = 199934001;
    boolean rejectScene, battle;
    final Position pos = new Position(12, 34, 56), rot = new Position(0, 90, 0);
    final emu.lunarcore.game.player.lineup.LineupManager teams =
        new emu.lunarcore.game.player.lineup.LineupManager(this);

    public emu.lunarcore.game.player.lineup.LineupManager getLineupManager() {
      return teams;
    }

    final GameAvatar avatar =
        new GameAvatar() {
          public int getAvatarId() {
            return AVATAR;
          }

          public int getMaxSp() {
            return 10000;
          }

          public void save() {}
        };

    Racer() {
      getAvatars().getAvatars().put(AVATAR, avatar);
      getCurrentLineup().getAvatars().add(AVATAR);
    }

    public GameAvatar getAvatarById(int id) {
      return id == AVATAR ? avatar : null;
    }

    public int getEntryId() {
      return entry;
    }

    public int getPlaneId() {
      return plane;
    }

    public int getFloorId() {
      return floor;
    }

    public Position getPos() {
      return pos;
    }

    public Position getRot() {
      return rot;
    }

    public boolean isInBattle() {
      return battle;
    }

    public void save() {}

    public void sendPacket(emu.lunarcore.server.packet.BasePacket p) {}

    public Scene getScene() {
      var excel = GameData.getMazePlaneExcelMap().get(plane);
      return new Scene(this, excel, floor) {
        public synchronized SceneInfo toProto() {
          return SceneInfo.newInstance()
              .setPlaneId(plane)
              .setFloorId(floor)
              .setEntryId(entry)
              .setGameModeType(excel.getPlaneType().getVal());
        }
      };
    }

    public boolean enterScene(int id, int teleport, boolean send) {
      if (rejectScene) return false;
      entry = id;
      plane = PLANE;
      floor = RAID;
      pos.set(1, 2, 3);
      return true;
    }

    public boolean loadScene(
        int p, int f, int id, Position position, Position rotation, boolean send) {
      if (rejectScene) return false;
      entry = id;
      plane = p;
      floor = f;
      pos.set(position);
      rot.set(rotation);
      return true;
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Racer racer = new Racer();
    final Map<String, com.google.protobuf.DynamicMessage> packets = new HashMap<>();

    public Player getPlayer() {
      return racer;
    }

    public void send(emu.lunarcore.server.packet.BasePacket packet) {
      var wire =
          getProtocol()
              .outbound(
                  packet.getCmdId(),
                  packet.getData() == null ? new byte[0] : packet.getData().toByteArray());
      if (packet.getCmdId() == emu.lunarcore.server.packet.CmdId.EnterSceneByServerScNotify)
        sendClientPacket("EnterSceneByServerScNotify", wire.body());
    }

    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      packets.put(name, response);
    }
  }

  Session s;

  @BeforeEach
  void setup() {
    GameData.getMazePlaneExcelMap()
        .add(gson.fromJson("{\"PlaneID\":44306,\"PlaneType\":\"Raid\"}", MazePlaneExcel.class));
    GameData.getMazePlaneExcelMap()
        .add(gson.fromJson("{\"PlaneID\":199934,\"PlaneType\":\"Town\"}", MazePlaneExcel.class));
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":44306001,\"PlaneID\":44306,\"FloorID\":44306001}",
                MapEntranceExcel.class));
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":1999341,\"PlaneID\":199934,\"FloorID\":199934001}",
                MapEntranceExcel.class));
    GameData.getActivityPanelExcelMap()
        .add(gson.fromJson("{\"PanelID\":50039}", ActivityPanelExcel.class));
    GameplayDefinitions.windows.add(window);
    GameplayDefinitions.parkourConstants.put("parkour_activity_module_id", 5003901);
    GameplayDefinitions.parkourConstants.put("parkour_gameplay_floor_id", RAID);
    GameplayDefinitions.parkourConstants.put("parkour_gameplay_entrance_id", RAID);
    GameplayDefinitions.parkourConstants.put("parkour_raid_id", RAID);
    var raid =
        new GameplayDefinitions.Raid(
            RAID,
            0,
            "TreasureChallenge",
            "Player",
            "SkipUI",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            true);
    GameplayDefinitions.raids.put(raid.key(), raid);
    GameplayDefinitions.parkourLevels.put(
        LEVEL,
        new GameplayDefinitions.ParkourLevel(
            LEVEL, "FinishSubMission", SUB, 1, 3, 3, "race.asset"));
    s = new Session();
    var q = new QuestState(SUB);
    q.advance(1, 1, 1);
    s.racer.getGameplay().getSubMissions().put(SUB, q);
  }

  @AfterEach
  void clear() {
    GameplayDefinitions.windows.remove(window);
    GameplayDefinitions.parkourConstants.clear();
    GameplayDefinitions.parkourLevels.remove(LEVEL);
    GameplayDefinitions.raids.remove((long) RAID * 100);
    for (int id : List.of(PLANE, HOME_PLANE)) GameData.getMazePlaneExcelMap().keySet().remove(id);
    for (int id : List.of(RAID, HOME)) GameData.getMapEntranceExcelMap().keySet().remove(id);
    GameData.getActivityPanelExcelMap().keySet().remove(50039);
  }

  int start(int id, int world) throws Exception {
    var b = Gameplay46.builder(s, "StartRaidCsReq");
    Gameplay46.set(b, "raid_id", id);
    Gameplay46.set(b, "world_level", world);
    assertTrue(VersionedPacketHandler.handle(s, 2234, b.build().toByteArray()), "原生副本入场不能缺处理入口");
    return Gameplay46.number(s.packets.get("StartRaidScRsp"), "retcode");
  }

  int leave(int id, boolean save) throws Exception {
    var b = Gameplay46.builder(s, "LeaveRaidCsReq");
    Gameplay46.set(b, "raid_id", id);
    Gameplay46.set(b, "is_save", save);
    assertTrue(VersionedPacketHandler.handle(s, 2220, b.build().toByteArray()));
    return Gameplay46.number(s.packets.get("LeaveRaidScRsp"), "retcode");
  }

  @Test
  void pendingStoryReturnCannotBeReplacedByAParkourVenue() throws Exception {
    var location = new StoryLineState.Place(s.racer);
    s.racer.getGameplay().getStoryLines().begin(199931, location, location);
    s.racer.getGameplay().getStoryLines().begin(0, location, location);
    assertNotEquals(0, start(RAID, 0));
    assertFalse(s.racer.getGameplay().getParkourVenue().inside());
    assertEquals(HOME, s.racer.entry);
    assertSame(location, s.racer.getGameplay().getStoryLines().getReturnDestination());
  }

  @Test
  void startCarriesTheRealSceneAndTeamAndLeaveRestoresTheExactHome() throws Exception {
    assertEquals(0, start(RAID, 0));
    assertEquals(RAID, s.racer.getEntryId());
    var wrapper =
        (com.google.protobuf.DynamicMessage)
            Gameplay46.value(s.packets.get("StartRaidScRsp"), "scene");
    assertEquals(RAID, Gameplay46.number(wrapper, "raid_id"));
    var scene = (com.google.protobuf.DynamicMessage) Gameplay46.value(wrapper, "MOPHKIGKDIK");
    assertEquals(RAID, Gameplay46.number(scene, "floor_id"));
    assertTrue(wrapper.hasField(wrapper.getDescriptorForType().findFieldByNumber(9)));
    assertEquals(0, leave(RAID, false));
    assertEquals(HOME, s.racer.entry);
    assertEquals(12, s.racer.pos.getX());
    assertEquals(90, s.racer.rot.getY());
    var returned =
        (com.google.protobuf.DynamicMessage)
            Gameplay46.value(s.packets.get("EnterSceneByServerScNotify"), "scene");
    assertEquals(HOME, Gameplay46.number(returned, "entry_id"));
    assertNotEquals(0, leave(RAID, false));
  }

  @Test
  void noOpenWindowOrUnlockCannotEnterAndUnknownRaidCannotBeFaked() throws Exception {
    GameplayDefinitions.windows.remove(window);
    assertNotEquals(0, start(RAID, 0));
    GameplayDefinitions.windows.add(window);
    s.racer.getGameplay().getCancelledSubs().add(SUB);
    assertNotEquals(0, start(RAID, 0));
    s.racer.getGameplay().getCancelledSubs().clear();
    assertNotEquals(0, start(RAID + 1, 0));
    assertNotEquals(0, start(RAID, 1));
    assertEquals(HOME, s.racer.entry);
  }

  @Test
  void failedSceneAndUnownedTeamNeverReportSuccessfulEntry() throws Exception {
    s.racer.rejectScene = true;
    assertNotEquals(0, start(RAID, 0));
    assertEquals(HOME, s.racer.entry);
    s.racer.rejectScene = false;
    s.racer.getCurrentLineup().getAvatars().clear();
    assertNotEquals(0, start(RAID, 0));
  }

  @Test
  void wrongRaidSavedExitAndFailedReturnDoNotLoseTheActiveVenue() throws Exception {
    assertEquals(0, start(RAID, 0));
    assertNotEquals(0, start(RAID, 0));
    assertNotEquals(0, leave(RAID + 1, false));
    assertNotEquals(0, leave(RAID, true));
    s.racer.rejectScene = true;
    assertNotEquals(0, leave(RAID, false));
    assertEquals(RAID, s.racer.entry);
    s.racer.rejectScene = false;
    GameplayDefinitions.windows.remove(window);
    assertEquals(0, leave(RAID, false));
    assertEquals(HOME, s.racer.entry);
  }

  @Test
  void nativeAvatarIdentifiersMustMatchTheOwnedCurrentFormalTeam() throws Exception {
    var b = Gameplay46.builder(s, "StartRaidCsReq");
    Gameplay46.set(b, "raid_id", RAID);
    var avatar = Gameplay46.builder(s, "AvatarIdentifier");
    Gameplay46.set(avatar, "id", AVATAR);
    var type = avatar.getDescriptorForType().findFieldByName("avatar_type");
    avatar.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
    Gameplay46.add(b, "NIMDEPFJJNI", avatar.build());
    assertTrue(VersionedPacketHandler.handle(s, 2234, b.build().toByteArray()));
    assertEquals(0, Gameplay46.number(s.packets.get("StartRaidScRsp"), "retcode"));
    assertEquals(0, leave(RAID, false));
    Gameplay46.set(avatar, "assist_uid", 10002);
    b.clearField(b.getDescriptorForType().findFieldByName("NIMDEPFJJNI"));
    Gameplay46.add(b, "NIMDEPFJJNI", avatar.build());
    assertTrue(VersionedPacketHandler.handle(s, 2234, b.build().toByteArray()));
    assertNotEquals(0, Gameplay46.number(s.packets.get("StartRaidScRsp"), "retcode"));
    assertEquals(HOME, s.racer.entry);
  }

  @Test
  void overflowAliasesDuplicateIdsAndUnknownFieldsCannotEnter() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = com.google.protobuf.CodedOutputStream.newInstance(bytes);
    out.writeUInt64(9, (1L << 32) + RAID);
    out.flush();
    assertTrue(VersionedPacketHandler.handle(s, 2234, bytes.toByteArray()));
    assertNotEquals(0, Gameplay46.number(s.packets.get("StartRaidScRsp"), "retcode"));
    bytes.reset();
    out.writeUInt32(9, RAID);
    out.writeUInt32(9, RAID);
    out.flush();
    assertTrue(VersionedPacketHandler.handle(s, 2234, bytes.toByteArray()));
    assertNotEquals(0, Gameplay46.number(s.packets.get("StartRaidScRsp"), "retcode"));
    bytes.reset();
    out.writeUInt32(9, RAID);
    out.writeUInt32(16, 1);
    out.flush();
    assertTrue(VersionedPacketHandler.handle(s, 2234, bytes.toByteArray()));
    assertNotEquals(0, Gameplay46.number(s.packets.get("StartRaidScRsp"), "retcode"));
    assertEquals(HOME, s.racer.entry);
  }

  @Test
  void aPersistedVenueRestoresTheReturnPointAndAbandonsAnUnfinishedRace() throws Exception {
    assertEquals(0, start(RAID, 0));
    s.racer.getGameplay().getParkour().start(LEVEL, 1, 3, RAID, 0, 4102444800L, SUB);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "parkourvenue");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var persisted = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(persisted, 19993404);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(persisted, s.racer.getGameplay());
      store.save(persisted);
      var restored = store.find(Player.class).first();
      gameplay.set(s.racer, restored.getGameplay());
      assertEquals(12, s.racer.getGameplay().getParkourVenue().getReturnTo().getPos().getX());
      assertTrue(new ParkourRaid(s.racer).restoreOnLogin());
      assertEquals(0, s.racer.getGameplay().getParkour().getActiveLevel());
      assertTrue(s.racer.getGameplay().getParkour().getRecords().isEmpty());
      assertEquals(0, leave(RAID, false));
      assertEquals(HOME, s.racer.entry);
      var old = new Player();
      assertFalse(old.getGameplay().getParkourVenue().inside());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void concurrentEntryCreatesOnlyOneReturnSnapshot() throws Exception {
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var work =
          new ArrayList<java.util.concurrent.Callable<emu.lunarcore.server.packet.Retcode>>();
      for (int i = 0; i < 12; i++)
        work.add(() -> new ParkourRaid(s.racer).enter(RAID, 0, 0, 0, List.of()));
      int successes = 0;
      for (var f : pool.invokeAll(work, 10, java.util.concurrent.TimeUnit.SECONDS))
        if (f.get() == emu.lunarcore.server.packet.Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(HOME, s.racer.getGameplay().getParkourVenue().getReturnTo().getEntry());
    }
  }

  @Test
  void parkourVenueCannotBeOverlaidByUniverseOrEscapedThroughOrdinaryTeleport() throws Exception {
    assertEquals(0, start(RAID, 0));
    GameplayDefinitions.areas.put(199934, new GameplayDefinitions.Area(199934, 199934, 1, 0, 0));
    GameplayDefinitions.maps.put(
        19993401, List.of(new GameplayDefinitions.Site(19993401, 1, true, List.of(), 1)));
    GameplayDefinitions.rooms.put(19993402, new GameplayDefinitions.Room(19993402, 1, RAID, 0));
    var pool = GameplayDefinitions.roomPool.put(1, List.of(19993402));
    try {
      assertEquals(
          emu.lunarcore.server.packet.Retcode.ROGUE_STATUS_NOT_MATCH,
          s.racer.getUniverseManager().start(199934));
    } finally {
      GameplayDefinitions.areas.remove(199934);
      GameplayDefinitions.maps.remove(19993401);
      GameplayDefinitions.rooms.remove(19993402);
      if (pool == null) GameplayDefinitions.roomPool.remove(1);
      else GameplayDefinitions.roomPool.put(1, pool);
    }
    var request = Gameplay46.builder(s, "EnterSceneCsReq");
    Gameplay46.set(request, "entry_id", HOME);
    assertTrue(
        VersionedPacketHandler.handle(
            s, s.getProtocol().clientCommand("EnterSceneCsReq"), request.build().toByteArray()));
    assertNotEquals(0, Gameplay46.number(s.packets.get("EnterSceneScRsp"), "retcode"));
    assertEquals(RAID, s.racer.entry);
  }

  @Test
  void thisUnsavedVenueDoesNotInventSavedRaidOrFinishedRaidRows() throws Exception {
    var request = Gameplay46.builder(s, "GetSaveRaidCsReq");
    Gameplay46.set(request, "raid_id", RAID);
    assertTrue(VersionedPacketHandler.handle(s, 2247, request.build().toByteArray()));
    assertEquals(0, Gameplay46.number(s.packets.get("GetSaveRaidScRsp"), "retcode"));
    assertEquals(false, Gameplay46.value(s.packets.get("GetSaveRaidScRsp"), "is_save"));
    for (var name : List.of("GetRaidInfoCsReq", "GetAllSaveRaidCsReq")) s.request(name);
    assertTrue(
        ((List<?>) Gameplay46.value(s.packets.get("GetRaidInfoScRsp"), "finished_raid_info_list"))
            .isEmpty());
    assertTrue(
        ((List<?>) Gameplay46.value(s.packets.get("GetAllSaveRaidScRsp"), "raid_data_list"))
            .isEmpty());
    Gameplay46.set(request, "raid_id", RAID + 1);
    assertTrue(VersionedPacketHandler.handle(s, 2247, request.build().toByteArray()));
    assertNotEquals(0, Gameplay46.number(s.packets.get("GetSaveRaidScRsp"), "retcode"));
  }

  @Test
  void anOtherwiseUnlockedMusicGameCannotStartInsideTheVenue() throws Exception {
    int music = 19993405;
    var activity = new GameplayDefinitions.Window(50022, 5002201, 0, 4102444800L);
    GameData.getActivityPanelExcelMap()
        .add(gson.fromJson("{\"PanelID\":50022}", ActivityPanelExcel.class));
    GameplayDefinitions.windows.add(activity);
    GameplayDefinitions.musicGroups.put(
        music, new GameplayDefinitions.MusicGroup(music, 1, 0, List.of()));
    GameplayDefinitions.musicLevels.put(
        music,
        new GameplayDefinitions.MusicLevel(music, music, 1, List.of(1, 2, 3), List.of(0, 0, 0)));
    try {
      assertTrue(new MusicManager(s.racer).unlocked(music));
      assertEquals(0, start(RAID, 0));
      assertEquals(
          emu.lunarcore.server.packet.Retcode.MUSIC_LOCKED, new MusicManager(s.racer).start(music));
      assertEquals(0, s.racer.getGameplay().getRhythm().getActiveLevel());
    } finally {
      GameplayDefinitions.musicGroups.remove(music);
      GameplayDefinitions.musicLevels.remove(music);
      GameplayDefinitions.windows.remove(activity);
      GameData.getActivityPanelExcelMap().keySet().remove(50022);
    }
  }

  @Test
  void lostVenueResourcesOnLoginReturnThroughTheSavedHomeWithoutAwardingARace() throws Exception {
    assertEquals(0, start(RAID, 0));
    GameplayDefinitions.raids.remove((long) RAID * 100);
    assertFalse(new ParkourRaid(s.racer).restoreOnLogin());
    assertEquals(HOME, s.racer.entry);
    assertFalse(s.racer.getGameplay().getParkourVenue().inside());
    assertTrue(s.racer.getGameplay().getParkour().getRecords().isEmpty());
  }
}
