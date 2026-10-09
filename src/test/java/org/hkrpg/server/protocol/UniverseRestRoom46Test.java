package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseRestRoom46Test {
  static final int AREA = Universe46LifecycleTest.AREA, MAP = Universe46LifecycleTest.MAP;
  static final int ROOM = Universe46LifecycleTest.ROOM, BUFF = 19996091;
  static final String FLOOR_KEY = "P199962_F199962001";
  final Gson gson = new Gson();
  final Map<Integer, PropExcel> priorProps = new HashMap<>();
  final Map<Integer, MapEntranceExcel> priorEntries = new HashMap<>();
  FloorInfo priorFloor, floor;
  Session session;

  static class Explorer extends UniverseLifecycleTest.Explorer {
    Scene cached;
    int saves;
    boolean reject;
    Session session;
    final List<Set<Integer>> portalNotifications = new ArrayList<>();

    Explorer() {
      getAvatarById(199961).setOwner(this);
    }

    public Scene getScene() {
      if (cached == null || cached.getEntryId() != getEntryId()) {
        cached = super.getScene();
        cached.setEntryId(getEntryId());
      }
      return cached;
    }

    public boolean enterScene(int id, int teleport, boolean send) {
      return !reject && super.enterScene(id, teleport, send);
    }

    public void save() {
      saves++;
    }

    public boolean isOnline() {
      return session != null;
    }

    public emu.lunarcore.server.game.GameSession getSession() {
      return session;
    }

    public void sendPacket(BasePacket packet) {
      if (packet instanceof emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify) {
        assertTrue(saves > 0, "Portal state must be saved before notification");
        portalNotifications.add(Set.copyOf(getGameplay().getRogue().getCleared()));
      }
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Explorer explorer = new Explorer();

    Session() {
      explorer.session = this;
    }

    public Player getPlayer() {
      return explorer;
    }

    DynamicMessage enter(int site, int room) throws Exception {
      var req = Gameplay46.builder(this, "EnterRogueMapRoomCsReq");
      Gameplay46.set(req, "MAJKFIMPOOF", site);
      Gameplay46.set(req, "room_id", room);
      assertTrue(
          VersionedPacketHandler.handle(
              this,
              getProtocol().clientCommand("EnterRogueMapRoomCsReq"),
              req.build().toByteArray()));
      assertEquals("EnterRogueMapRoomScRsp", responseName);
      return response;
    }
  }

  @BeforeEach
  void setup() {
    new Universe46LifecycleTest().setup();
    GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", "300");
    GameplayDefinitions.maps.put(
        MAP,
        List.of(
            new GameplayDefinitions.Site(MAP, 1, true, List.of(2), 5),
            new GameplayDefinitions.Site(MAP, 2, false, List.of(3), 5),
            new GameplayDefinitions.Site(MAP, 3, false, List.of(), 4)));
    for (int i = 0; i < 3; i++) {
      GameplayDefinitions.rooms.put(
          ROOM + i, new GameplayDefinitions.Room(ROOM + i, i == 2 ? 4 : 5, 12 + i, 8 + i));
      GameplayDefinitions.roomPool.put(i + 1, List.of(ROOM + i));
      priorEntries.put(12 + i, GameData.getMapEntranceExcelMap().get(12 + i));
      GameData.getMapEntranceExcelMap()
          .add(
              gson.fromJson(
                  "{\"ID\":" + (12 + i) + ",\"PlaneID\":199962,\"FloorID\":199962001}",
                  MapEntranceExcel.class));
    }
    priorFloor = GameData.getFloorInfos().get(FLOOR_KEY);
    floor = gson.fromJson("{\"FloorID\":199962001}", FloorInfo.class);
    for (int group : List.of(8, 9, 10)) {
      var row =
          gson.fromJson(
              "{\"PropList\":[{\"ID\":300001,\"Name\":\"Door1\",\"PropID\":1000}]}",
              GroupInfo.class);
      row.setId(group);
      floor.getGroups().put(group, row);
    }
    GameData.getFloorInfos().put(FLOOR_KEY, floor);
    for (int id : List.of(1000, 1021, 1023)) {
      priorProps.put(id, GameData.getPropExcelMap().get(id));
      var prop =
          gson.fromJson(
              "{\"ID\":"
                  + id
                  + ",\"PropType\":\"PROP_ROGUE_DOOR\",\"PropStateList\":[\"Closed\",\"Open\",\"Locked\"]}",
              PropExcel.class);
      prop.onLoad();
      GameData.getPropExcelMap().add(prop);
    }
    GameplayDefinitions.blessings.add(
        new GameplayDefinitions.Blessing(BUFF, 1, 100, "Common", 1, List.of()));
    GameData.getMazeBuffExcelMap()
        .add(gson.fromJson("{\"ID\":" + BUFF + ",\"Lv\":1}", MazeBuffExcel.class));
    session = new Session();
  }

  @AfterEach
  void cleanup() {
    new Universe46LifecycleTest().reset();
    for (int i : List.of(1, 2)) {
      GameplayDefinitions.rooms.remove(ROOM + i);
      GameplayDefinitions.roomPool.remove(i + 1);
    }
    priorEntries.forEach(
        (id, row) -> {
          if (row == null) GameData.getMapEntranceExcelMap().keySet().remove(id);
          else GameData.getMapEntranceExcelMap().add(row);
        });
    priorProps.forEach(
        (id, row) -> {
          if (row == null) GameData.getPropExcelMap().keySet().remove(id);
          else GameData.getPropExcelMap().add(row);
        });
    if (priorFloor == null) GameData.getFloorInfos().remove(FLOOR_KEY);
    else GameData.getFloorInfos().put(FLOOR_KEY, priorFloor);
    GameplayDefinitions.blessings.removeIf(b -> b.MazeBuffID() == BUFF);
    GameData.getMazeBuffExcelMap().values().removeIf(b -> b.getID() == BUFF);
  }

  Explorer p() {
    return session.explorer;
  }

  GameplayState.RogueRun run() {
    return p().getGameplay().getRogue();
  }

  void start() {
    assertEquals(Retcode.SUCC, p().getUniverseManager().start(AREA));
  }

  RoguePortal portal() {
    return p().getScene().getEntities().values().stream()
        .filter(RoguePortal.class::isInstance)
        .map(RoguePortal.class::cast)
        .findFirst()
        .orElseThrow();
  }

  void roomType(int type) {
    GameplayDefinitions.rooms.put(ROOM, new GameplayDefinitions.Room(ROOM, type, 12, 8));
  }

  @Test
  void actualNoncombatRestRoomOpensItsBoundExitWithoutPurchaseOrBattleRewards() throws Exception {
    start();
    assertEquals(Set.of(1), run().getCleared());
    assertEquals(PropState.Open, portal().getState());
    var rows = (List<?>) Gameplay46.value(Universe46.mapSnapshot(session), "LLNICHDDIAN");
    assertEquals(
        "RogueRoomStatus_Finish",
        Gameplay46.value((DynamicMessage) rows.getFirst(), "CAGDNHCOCKM").toString());
    assertEquals(
        "RogueRoomStatus_Unlock",
        Gameplay46.value((DynamicMessage) rows.get(1), "CAGDNHCOCKM").toString());
    assertEquals(300, run().getCoins());
    assertFalse(run().pending());
    assertTrue(run().getProcessedBattles().isEmpty());
    assertTrue(p().getGameplay().getRogueResults().isEmpty());
    assertEquals(0, Gameplay46.number(session.enter(2, ROOM + 1), "retcode"));
    assertEquals(Set.of(1, 2), run().getCleared());
    assertEquals(2, run().getSiteId());
    assertEquals(PropState.Open, portal().getState());
  }

  @Test
  void roomStateIsSavedBeforePortalNotificationsAndDoesNotMakeTheTerminalBossComplete()
      throws Exception {
    start();
    assertFalse(p().portalNotifications.isEmpty());
    assertTrue(p().portalNotifications.stream().allMatch(s -> s.contains(1)));
    assertEquals(0, Gameplay46.number(session.enter(2, ROOM + 1), "retcode"));
    assertEquals(0, Gameplay46.number(session.enter(3, ROOM + 2), "retcode"));
    assertEquals(Set.of(1, 2), run().getCleared());
    assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run().getPhase());
    assertTrue(p().getGameplay().getRogueResults().isEmpty());
    assertTrue(p().getGameplay().getRewards().isEmpty());
  }

  @Test
  void pausedAndLegacyRestResumeReconcilesExitWithoutRerollOrInterest() {
    start();
    run().getCleared().clear(); // Old rest save predating the exit fix.
    String id = run().getRunId();
    run().getMiracles().put(15, new GameplayState.RogueMiracle(0, List.of(.1)));
    assertEquals(Retcode.SUCC, p().getUniverseManager().leave(false));
    assertEquals(Retcode.SUCC, p().getUniverseManager().resume(AREA, true));
    assertEquals(Set.of(1), run().getCleared());
    assertEquals(PropState.Open, portal().getState());
    assertEquals(id, run().getRunId());
    assertEquals(300, run().getCoins());
    assertTrue(run().getBlessings().isEmpty());
  }

  @Test
  void pendingInitialChoiceKeepsTheReadyRestPortalClosedUntilSelectionCompletes() {
    start();
    run().offer(List.of(BUFF));
    assertEquals(PropState.Closed, portal().getState());
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, p().getUniverseManager().enter(2));
    assertEquals(
        Retcode.SUCC, p().getUniverseManager().selectBlessing(run().getSelectionId(), BUFF));
    assertEquals(PropState.Open, portal().getState());
    assertEquals(Retcode.SUCC, p().getUniverseManager().enter(2));
  }

  @Test
  void aRestArrivalCannotManufactureAVictoryBlessingOrConsumeVictoryMiracles() {
    start();
    run().getMiracles().put(7, new GameplayState.RogueMiracle(1, List.of(1.0)));
    assertTrue(p().getUniverseManager().offerBlessings().isEmpty());
    assertTrue(run().getOffers().isEmpty());
    assertTrue(run().getSelectionRooms().isEmpty());
    assertEquals(0, run().getMiracles().get(7).getUseCount());
    assertTrue(run().getProcessedBattles().isEmpty());
  }

  @Test
  void combatEventAndTerminalRestDefinitionsAreNotAutomaticallyFinished() {
    for (int type : List.of(1, 2, 3, 4, 6, 7, 8)) {
      roomType(type);
      session = new Session();
      start();
      assertTrue(run().getCleared().isEmpty());
      assertEquals(PropState.Closed, portal().getState());
    }
    roomType(5);
    GameplayDefinitions.maps.put(
        MAP, List.of(new GameplayDefinitions.Site(MAP, 1, true, List.of(), 5)));
    session = new Session();
    start();
    assertTrue(run().getCleared().isEmpty());
    assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run().getPhase());
  }

  @Test
  void missingGroupOrDeclaredCombatCannotTurnResourceFailureIntoAReadyRestExit() {
    floor.getGroups().remove(8);
    start();
    assertTrue(run().getCleared().isEmpty());
    var group =
        gson.fromJson(
            "{\"MonsterList\":[{\"ID\":200001,\"NPCMonsterID\":999999}]}", GroupInfo.class);
    group.setId(8);
    floor.getGroups().put(8, group);
    session = new Session();
    start();
    assertTrue(run().getCleared().isEmpty());
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, p().getUniverseManager().enter(2));
  }

  @Test
  void failedEntryCannotClearItsDestinationOrLoseTheCurrentRestState() {
    start();
    p().reject = true;
    assertEquals(Retcode.ROGUE_AREA_INVALID, p().getUniverseManager().enter(2));
    assertEquals(Set.of(1), run().getCleared());
    assertEquals(1, run().getSiteId());
    assertEquals(ROOM, run().getRoomId());
    assertEquals(12, p().getEntryId());
  }

  @Test
  void wrongEntryResourceCannotAuthorizeRestCompletionOnTheUnrelatedScene() {
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":12,\"PlaneID\":199961,\"FloorID\":199961001}", MapEntranceExcel.class));
    start();
    assertTrue(run().getCleared().isEmpty());
    assertTrue(
        p().getScene().getEntities().values().stream().noneMatch(RoguePortal.class::isInstance));
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, p().getUniverseManager().enter(2));
  }

  @Test
  void deletedOrClientOnlyMonsterPlacementsDoNotInventACombatRequirementInRest() {
    var group =
        gson.fromJson(
            "{\"MonsterList\":[{\"ID\":200001,\"IsDelete\":true},{\"ID\":200002,\"IsClientOnly\":true}],"
                + "\"PropList\":[{\"ID\":300001,\"Name\":\"Door1\",\"PropID\":1000}]}",
            GroupInfo.class);
    group.setId(8);
    floor.getGroups().put(8, group);
    start();
    assertEquals(Set.of(1), run().getCleared());
    assertEquals(PropState.Open, portal().getState());
    assertTrue(run().getProcessedBattles().isEmpty());
  }

  @Test
  void forgedDestinationOrParallelRetriesCannotEnterTheNextRoomTwice() throws Exception {
    start();
    assertNotEquals(0, Gameplay46.number(session.enter(2, ROOM + 2), "retcode"));
    assertEquals(Retcode.ROGUE_AREA_INVALID, p().getUniverseManager().enter(3));
    try (var workers = Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<Callable<Retcode>>();
      for (int i = 0; i < 6; i++) tasks.add(() -> p().getUniverseManager().enter(2));
      int success = 0;
      for (var result : workers.invokeAll(tasks)) if (result.get() == Retcode.SUCC) success++;
      assertEquals(1, success);
    }
    assertEquals(Set.of(1, 2), run().getCleared());
    assertEquals(300, run().getCoins());
  }

  @Test
  void actualMorphiaReloadKeepsOnlyVisitedRestRoomsAndTheBoundDestination() throws Exception {
    start();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rest-room");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456766);
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(document, p().getGameplay());
      store.save(document);
      field.set(p(), store.find(Player.class).first().getGameplay());
      assertEquals(Set.of(1), run().getCleared());
      assertEquals(Retcode.SUCC, p().getUniverseManager().resume(AREA, false));
      assertEquals(PropState.Open, portal().getState());
      assertEquals(Retcode.SUCC, p().getUniverseManager().enter(2));
      field.set(document, p().getGameplay());
      store.save(document);
      var restored = store.find(Player.class).first().getGameplay().getRogue();
      assertEquals(Set.of(1, 2), restored.getCleared());
      assertEquals(2, restored.getSiteId());
      assertEquals(300, restored.getCoins());
      assertFalse(restored.pending());
    } finally {
      mongo.shutdown();
    }
  }

  private void duck(String script) {
    var group =
        gson.fromJson(
            "{\"PropList\":[{\"ID\":300001,\"Name\":\"Duck\",\"PropID\":1000,\"InitLevelGraph\":\""
                + script
                + "\"}]}",
            GroupInfo.class);
    group.setId(8);
    floor.getGroups().put(8, group);
    floor.onLoad();
  }

  @Test
  void declaredDuckMapPortalRemainsVisibleAndDoesNotInventOneBranchDestination() throws Exception {
    duck("Config/Level/Maze/MazeRogue/Rogue140/Maze_Rogue140_Portal.json");
    GameplayDefinitions.maps.put(
        MAP,
        List.of(
            new GameplayDefinitions.Site(MAP, 1, true, List.of(2, 3), 5),
            new GameplayDefinitions.Site(MAP, 2, false, List.of(3), 5),
            new GameplayDefinitions.Site(MAP, 3, false, List.of(), 4)));
    start();
    var prop =
        p().getScene().getEntities().values().stream()
            .filter(
                e ->
                    e instanceof emu.lunarcore.game.scene.entity.EntityProp v
                        && v.getPropId() == 1000)
            .map(emu.lunarcore.game.scene.entity.EntityProp.class::cast)
            .findFirst();
    assertTrue(prop.isPresent(), "The real Duck map portal was discarded by the room loader");
    assertEquals(PropState.Open, prop.orElseThrow().getState());
    assertFalse(
        prop.orElseThrow() instanceof RoguePortal,
        "The map chooser must not be tied to one arbitrary branch");
    var map = Universe46.mapSnapshot(session);
    long unlocked =
        ((List<?>) Gameplay46.value(map, "LLNICHDDIAN"))
            .stream()
                .map(DynamicMessage.class::cast)
                .filter(
                    r ->
                        Gameplay46.value(r, "CAGDNHCOCKM")
                            .toString()
                            .equals("RogueRoomStatus_Unlock"))
                .count();
    assertEquals(2, unlocked);
    run().offer(List.of(BUFF));
    assertEquals(PropState.Closed, prop.orElseThrow().getState());
    assertEquals(
        Retcode.SUCC, p().getUniverseManager().selectBlessing(run().getSelectionId(), BUFF));
    assertEquals(PropState.Open, prop.orElseThrow().getState());
    assertEquals(Retcode.SUCC, p().getUniverseManager().enter(3));
    assertEquals(PropState.Closed, prop.orElseThrow().getState());
    assertFalse(run().getCleared().contains(3));
  }

  @Test
  void anUnrecognizedDuckScriptDoesNotGainAnOpenMapPortalByNameAlone() {
    duck("Config/Level/UnknownPortal.json");
    start();
    assertTrue(
        p().getScene().getEntities().values().stream()
            .noneMatch(
                e ->
                    e instanceof emu.lunarcore.game.scene.entity.EntityProp v
                        && v.getPropId() == 1000));
  }
}
