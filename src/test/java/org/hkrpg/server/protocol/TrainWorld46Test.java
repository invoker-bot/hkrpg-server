package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.*;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class TrainWorld46Test {
  static final int WORLD = 199967501, OTHER = WORLD + 1;
  final SceneDimension46Test fixture = new SceneDimension46Test();
  final Gson gson = new Gson();
  final List<GameplayState> saved = new ArrayList<>();
  final List<String> notifications = new ArrayList<>();
  DynamicMessage response;
  GameSession session;
  String version = "4.6.0";

  @BeforeEach
  void setup() {
    fixture.prepare();
    GameplayDefinitions.worlds.put(WORLD, new GameplayDefinitions.World(WORLD));
    GameplayDefinitions.worlds.put(OTHER, new GameplayDefinitions.World(OTHER));
    fixture.actor =
        new Player() {
          final PlayerLineup team = new PlayerLineup(this, 0);
          final emu.lunarcore.util.Position pos = new emu.lunarcore.util.Position(),
              rot = new emu.lunarcore.util.Position();

          public emu.lunarcore.util.Position getPos() {
            return pos;
          }

          public emu.lunarcore.util.Position getRot() {
            return rot;
          }

          {
            team.getAvatars().add(1);
          }

          public PlayerLineup getCurrentLineup() {
            return team;
          }

          public Scene getScene() {
            return fixture.current;
          }

          public int getPlaneId() {
            return SceneDimension46Test.PLANE;
          }

          public int getFloorId() {
            return SceneDimension46Test.FLOOR;
          }

          public int getEntryId() {
            return SceneDimension46Test.ENTRY;
          }

          public boolean isOnline() {
            return true;
          }

          public GameSession getSession() {
            return session;
          }

          public void sendPacket(BasePacket packet) {}

          public void save() {
            saved.add(gson.fromJson(gson.toJson(getGameplay()), GameplayState.class));
          }
        };
    session =
        new GameSession(null) {
          {
            setState(SessionState.ACTIVE);
          }

          public Player getPlayer() {
            return fixture.actor;
          }

          public ProtocolBridge getProtocol() {
            return ProtocolBridge.forVersion(version);
          }

          public void send(BasePacket packet) {}

          public void sendClientPacket(String name, byte[] bytes) {
            try {
              var message = getProtocol().clientSchema().parse(name, bytes);
              if (name.equals("SetTrainWorldIdScRsp")) {
                assertEquals(1401, getProtocol().clientCommand(name));
                response = message;
              } else {
                assertEquals("TrainWorldIdChangeScNotify", name);
                int world = Gameplay46.number(message, "CFDCEEMIOCO");
                assertEquals(world, saved.getLast().getStoryValues().get("train:world"));
                notifications.add(name);
              }
            } catch (IOException e) {
              throw new AssertionError(e);
            }
          }
        };
    fixture.actor.getGameplay().getStoryValues().put("train:world", WORLD);
    fixture.actor.getGameplay().getStoryValues().put("train:visited:" + OTHER, 1);
    scene("Train");
  }

  void scene(String type) {
    fixture.current =
        new Scene(
            fixture.actor,
            gson.fromJson(
                "{\"PlaneID\":199936,\"PlaneType\":\"" + type + "\",\"WorldID\":100}",
                MazePlaneExcel.class),
            SceneDimension46Test.FLOOR,
            4);
    fixture.current.setEntryId(SceneDimension46Test.ENTRY);
  }

  @AfterEach
  void cleanup() {
    fixture.restore();
    GameplayDefinitions.worlds.remove(WORLD);
    GameplayDefinitions.worlds.remove(OTHER);
  }

  long context() throws Exception {
    var nativeScene =
        fixture.scene(
            Scene46.enrich(
                fixture.bridge.clientSchema(),
                "GetCurSceneInfoScRsp",
                fixture.nativeBody(),
                fixture.actor));
    return (Long) Gameplay46.value(nativeScene, "interact_id");
  }

  byte[] request(long context, long world) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var output = CodedOutputStream.newInstance(bytes);
    output.writeUInt64(5, context);
    output.writeUInt64(9, world);
    output.flush();
    return bytes.toByteArray();
  }

  int call(byte[] bytes) throws Exception {
    response = null;
    assertTrue(VersionedPacketHandler.handle(session, 1489, bytes));
    assertNotNull(response);
    return Gameplay46.number(response, "retcode");
  }

  @Test
  void nativeSelectionUsesTheCurrentSceneContextSavesThenNotifiesAndDoesNotRepeat()
      throws Exception {
    long context = context();
    assertNotEquals(0, context);
    assertEquals(context, context());
    assertEquals(0, call(request(context, OTHER)));
    assertEquals(OTHER, Gameplay46.number(response, "CFDCEEMIOCO"));
    assertEquals(OTHER, fixture.current.toProto().getWorldId());
    assertEquals(1, saved.size());
    assertEquals(List.of("TrainWorldIdChangeScNotify"), notifications);
    assertEquals(0, call(request(context, OTHER)));
    assertEquals(1, saved.size());
    assertEquals(1, notifications.size());
  }

  @Test
  void unvisitedUnknownAndMismatchedWorldsCannotBeSelectedOrEchoedAsSuccess() throws Exception {
    fixture.actor.getGameplay().getStoryValues().remove("train:visited:" + OTHER);
    long context = context();
    for (int target : new int[] {OTHER, OTHER + 1}) {
      assertNotEquals(0, call(request(context, target)));
      assertEquals(WORLD, Gameplay46.number(response, "CFDCEEMIOCO"));
    }
    fixture.actor.getGameplay().getStoryValues().put("train:visited:" + OTHER, 1);
    GameplayDefinitions.worlds.put(OTHER, new GameplayDefinitions.World(OTHER + 1));
    assertNotEquals(0, call(request(context, OTHER)));
    assertEquals(WORLD, fixture.actor.getGameplay().getStoryValues().get("train:world"));
    assertTrue(saved.isEmpty());
    assertTrue(notifications.isEmpty());
  }

  @Test
  void fullWidthContextDoesNotAliasAndARebuiltSceneRejectsThePreviousContext() throws Exception {
    long old = context();
    assertNotEquals(0, call(request(old ^ (1L << 32), OTHER)));
    scene("Train");
    long current = context();
    assertNotEquals(old, current);
    assertNotEquals(0, call(request(old, OTHER)));
    assertEquals(0, call(request(current, OTHER)));
    assertEquals(1, saved.size());
    scene("Town");
    assertNotEquals(0, call(request(current, WORLD)));
    assertEquals(1, saved.size());
  }

  @Test
  void malformedDuplicateWideOrUnknownFieldsLeaveTheSavedSelectionUntouched() throws Exception {
    long context = context();
    var normal = request(context, OTHER);
    var duplicate = Arrays.copyOf(normal, normal.length + 2);
    duplicate[normal.length] = 40;
    duplicate[normal.length + 1] = 1;
    var unknown = Arrays.copyOf(normal, normal.length + 2);
    unknown[normal.length] = 8;
    unknown[normal.length + 1] = 1;
    for (byte[] invalid :
        List.of(
            new byte[0],
            new byte[] {40, 1},
            request(0, OTHER),
            request(context, 0),
            request(context, 1L << 32),
            request(context, -1),
            duplicate,
            unknown,
            new byte[] {40, (byte) 0x80},
            new byte[129])) {
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), call(invalid));
      assertEquals(WORLD, Gameplay46.number(response, "CFDCEEMIOCO"));
      assertTrue(saved.isEmpty());
      assertTrue(notifications.isEmpty());
    }
  }

  @Test
  void unauthenticatedAndLegacySessionsDoNotChangeWorldOrSendNativeState() throws Exception {
    var request = request(context(), OTHER);
    session.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(session, 1489, request));
    assertNull(response);
    session.setState(SessionState.ACTIVE);
    version = "4.2.0";
    assertFalse(VersionedPacketHandler.handle(session, 1489, request));
    assertNull(response);
    assertTrue(saved.isEmpty());
    assertEquals(WORLD, fixture.actor.getGameplay().getStoryValues().get("train:world"));
  }

  @Test
  void savedRaidOwnershipBlocksTrainSelectionEvenIfTheSceneContextLooksValid() throws Exception {
    long context = context();
    fixture
        .actor
        .getGameplay()
        .beginMissionRaid(
            new GameplayState.MissionRaidRun(49979, 0, 199979, 49979, 49979001, 499791));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), call(request(context, OTHER)));
    assertEquals(WORLD, fixture.actor.getGameplay().getStoryValues().get("train:world"));
    assertTrue(saved.isEmpty());
    assertTrue(notifications.isEmpty());
    fixture.actor.getGameplay().endMissionRaid();
    assertEquals(0, call(request(context, OTHER)));
    assertEquals(1, notifications.size());
  }

  @Test
  void pendingStoryTrialUniverseVenueAndReservedActionsBlockSelection() throws Exception {
    var p = fixture.actor;
    long context = context();
    p.getGameplay().getStoryLines().begin(1, null, null);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), call(request(context, OTHER)));
    p.getGameplay().getStoryLines().begin(0, null, null);
    p.getGameplay().getStoryLines().returned();
    p.getGameplay().setTrialRun(gson.fromJson("{}", TrialRun.class));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), call(request(context, OTHER)));
    p.getGameplay().setTrialRun(null);
    p.getGameplay().getRogue().setInside(true);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), call(request(context, OTHER)));
    p.getGameplay().getRogue().setInside(false);
    p.getGameplay().getParkourVenue().enter(1, 1, 1, 1, 1, p);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), call(request(context, OTHER)));
    p.getGameplay().getParkourVenue().close();
    var cursor = new GameplayState.ActionCursor();
    cursor.reserve();
    p.getGameplay().getStoryActions().put(1, cursor);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), call(request(context, OTHER)));
    assertTrue(saved.isEmpty());
    assertTrue(notifications.isEmpty());
  }

  @Test
  void morphiaReloadKeepsBothSelectionAndActualVisitedHistory() throws Exception {
    assertEquals(0, call(request(context(), OTHER)));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "train-world-selection");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456771);
      var state = Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(document, saved.getLast());
      store.save(document);
      state.set(fixture.actor, store.find(Player.class).first().getGameplay());
      scene("Train");
      assertEquals(OTHER, fixture.current.toProto().getWorldId());
      assertEquals(1, fixture.actor.getGameplay().getStoryValues().get("train:visited:" + WORLD));
      assertEquals(1, fixture.actor.getGameplay().getStoryValues().get("train:visited:" + OTHER));
      assertEquals(0, call(request(context(), WORLD)));
      assertEquals(WORLD, fixture.current.toProto().getWorldId());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void concurrentRetriesSaveAndNotifyOneActualChange() throws Exception {
    long context = context();
    try (var pool = Executors.newFixedThreadPool(6)) {
      var gate = new CountDownLatch(1);
      var results = new ArrayList<Future<Retcode>>();
      for (int i = 0; i < 6; i++)
        results.add(
            pool.submit(
                () -> {
                  assertTrue(gate.await(5, TimeUnit.SECONDS));
                  return TrainWorlds.select(fixture.actor, context, OTHER);
                }));
      gate.countDown();
      for (var result : results) assertEquals(Retcode.SUCC, result.get(5, TimeUnit.SECONDS));
    }
    assertEquals(1, saved.size());
    assertEquals(1, notifications.size());
    assertEquals(OTHER, saved.getFirst().getStoryValues().get("train:world"));
  }

  @Test
  void queuedForeignSceneSnapshotsKeepTheirOwnContextAndWorld() throws Exception {
    var wrapper = fixture.bridge.clientSchema().parse("GetCurSceneInfoScRsp", fixture.nativeBody());
    var field = wrapper.getDescriptorForType().findFieldByName("scene");
    for (String identity : new String[] {"plane_id", "floor_id", "entry_id"}) {
      var scene = ((DynamicMessage) wrapper.getField(field)).toBuilder();
      Gameplay46.set(scene, identity, 1);
      Gameplay46.set(scene, "interact_id", 5L);
      var bytes = wrapper.toBuilder().setField(field, scene.build()).build().toByteArray();
      assertArrayEquals(
          bytes,
          Scene46.enrich(
              fixture.bridge.clientSchema(), "GetCurSceneInfoScRsp", bytes, fixture.actor));
    }
  }
}
