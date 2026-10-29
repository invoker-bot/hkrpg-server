package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;

/** Original first-story trigger; the previously completed dialogue is an explicit fixture. */
class RogueFirstStoryEntry46Test {
  static final long NOW = 1_800_000_000_000L;
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, MissionInfo.SubMissionInfo> savedSubs = new HashMap<>();
  final Map<Integer, GameplayDefinitions.Area> savedAreas = new HashMap<>();
  final Map<Integer, List<GameplayDefinitions.Site>> savedMaps = new HashMap<>();
  final Map<Integer, GameplayDefinitions.Room> savedRooms = new HashMap<>();
  final Map<Integer, Map<Integer, List<Integer>>> savedPools = new HashMap<>();
  final Map<Integer, Integer> savedBosses = new HashMap<>();
  final Map<String, String> savedConstants = new HashMap<>();
  FloorInfo oldFloor;
  FloorInfo oldTutorialFloor;
  MapEntranceExcel oldTutorialEntry;
  Actor actor;
  Session session;

  static class Session extends GameSession {
    Actor actor;
    final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
    Session() { super(null); setState(SessionState.ACTIVE); }
    @Override public Player getPlayer() { return actor; }
    @Override public ProtocolBridge getProtocol() { return protocol; }
    @Override public void send(emu.lunarcore.server.packet.BasePacket packet) {}
    @Override public void sendClientPacket(String name, byte[] bytes) {}
  }

  static class Actor extends UniverseLifecycleTest.Explorer {
    Scene actual;
    Session session;
    int starts;
    int saves;
    boolean failSave;
    @Override public int getPlaneId() { return entry == 11 ? 80112 : 80001; }
    @Override public int getFloorId() { return entry == 11 ? 80112001 : 80001001; }
    @Override public int getEntryId() { return entry == 11 ? 8011201 : entry; }
    @Override public Scene getScene() { return actual; }
    @Override public GameSession getSession() { return session; }
    @Override public boolean isOnline() { return session != null; }
    @Override public void save() {
      if (failSave) throw new IllegalStateException("fixture save failed");
      saves++;
    }
    @Override public boolean enterScene(int id, int teleport, boolean send) {
      starts++;
      return super.enterScene(id, teleport, send);
    }
    @Override public boolean enterSceneAtAnchor(int id, int group, int anchor, boolean send) {
      starts++;
      return super.enterSceneAtAnchor(id, group, anchor, send);
    }
  }

  @BeforeEach void prepare() throws Exception {
    savedSubs.putAll(GameplayDefinitions.subs);
    savedAreas.putAll(GameplayDefinitions.areas);
    savedMaps.putAll(GameplayDefinitions.maps);
    savedRooms.putAll(GameplayDefinitions.rooms);
    savedPools.putAll(GameplayDefinitions.roomPoolByMap);
    savedBosses.putAll(GameplayDefinitions.bossContentByArea);
    savedConstants.putAll(GameplayDefinitions.rogueConstants);
    GameplayDefinitions.areas.put(100, new GameplayDefinitions.Area(100, 0, 1, 0, 0));
    GameplayDefinitions.maps.put(1, List.of(new GameplayDefinitions.Site(1, 1, true, List.of(), 0)));
    GameplayDefinitions.rooms.put(100, new GameplayDefinitions.Room(100, 1, 8000101, 10,
        Map.of(10, 1006, 3, 1001, 4, 1002, 5, 1003, 11, 1004, 12, 1005)));
    GameplayDefinitions.roomPoolByMap.remove(1);
    GameplayDefinitions.bossContentByArea.remove(100);
    GameplayDefinitions.rogueConstants.remove("Start_Rogue_Coin_Base");
    oldFloor = GameData.getFloorInfos().get("P80112_F80112001");
    oldTutorialFloor = GameData.getFloorInfos().get("P80001_F80001001");
    oldTutorialEntry = GameData.getMapEntranceExcelMap().get(8000101);
    var tutorialFloor = new FloorInfo();
    var tutorialGroup = gson.fromJson(
        "{\"AnchorList\":[{\"ID\":1,\"PosX\":-23.854,\"PosY\":0.005451,"
            + "\"PosZ\":236.15,\"RotY\":180}]}", GroupInfo.class);
    tutorialGroup.setId(10);
    tutorialFloor.getGroups().put(10, tutorialGroup);
    GameData.getFloorInfos().put("P80001_F80001001", tutorialFloor);
    GameData.getMapEntranceExcelMap().add(gson.fromJson(
        "{\"ID\":8000101,\"PlaneID\":80001,\"FloorID\":80001001}", MapEntranceExcel.class));
    var input = Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/rogue-first-story-entry-4.6.json"));
    var records = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
        .getAsJsonObject().getAsJsonObject("records");
    var graph = gson.fromJson(records.get("Config/Level/Mission/4030001/MissionInfo_4030001.partial.json"), MissionInfo.class);
    for (var child : graph.getSubMissionList()) GameplayDefinitions.subs.put(child.getId(), child);
    var floor = new FloorInfo();
    var group = gson.fromJson(records.get("Config/LevelOutput/SharedRuntimeGroup/Groups_P80112_F80112001/LevelGroup_P80112_F80112001_G18.json"), GroupInfo.class);
    group.setId(18);
    floor.getGroups().put(18, group);
    GameData.getFloorInfos().put("P80112_F80112001", floor);
    actor = new Actor();
    session = new Session(); session.actor = actor; actor.session = session;
    var plane = gson.fromJson("{\"PlaneID\":80112,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    actor.actual = new Scene(actor, plane, 80112001);
    actor.actual.setEntryId(8011201);
    var loaded = Scene.class.getDeclaredField("loaded"); loaded.setAccessible(true); loaded.set(actor.actual, true);
    var stamina = Player.class.getDeclaredField("nextStaminaRecover"); stamina.setAccessible(true); stamina.set(actor, Long.MAX_VALUE);
    actor.position.set(9250, 608, -90);
    actor.getGameplay().getMainMissions().put(4030001, new QuestState(4030001));
    var predecessor = new QuestState(403000101); predecessor.advance(1, 1, 100);
    actor.getGameplay().getSubMissions().put(403000101, predecessor);
    actor.getGameplay().getSubMissions().put(403000102, new QuestState(403000102));
  }

  @AfterEach void restore() {
    GameplayDefinitions.subs.clear(); GameplayDefinitions.subs.putAll(savedSubs);
    GameplayDefinitions.areas.clear(); GameplayDefinitions.areas.putAll(savedAreas);
    GameplayDefinitions.maps.clear(); GameplayDefinitions.maps.putAll(savedMaps);
    GameplayDefinitions.rooms.clear(); GameplayDefinitions.rooms.putAll(savedRooms);
    GameplayDefinitions.roomPoolByMap.clear(); GameplayDefinitions.roomPoolByMap.putAll(savedPools);
    GameplayDefinitions.bossContentByArea.clear(); GameplayDefinitions.bossContentByArea.putAll(savedBosses);
    GameplayDefinitions.rogueConstants.clear(); GameplayDefinitions.rogueConstants.putAll(savedConstants);
    if (oldFloor == null) GameData.getFloorInfos().remove("P80112_F80112001");
    else GameData.getFloorInfos().put("P80112_F80112001", oldFloor);
    if (oldTutorialFloor == null) GameData.getFloorInfos().remove("P80001_F80001001");
    else GameData.getFloorInfos().put("P80001_F80001001", oldTutorialFloor);
    if (oldTutorialEntry == null) GameData.getMapEntranceExcelMap().keySet().remove(8000101);
    else GameData.getMapEntranceExcelMap().add(oldTutorialEntry);
  }

  @Test void waitingInTheAuthoredTriggerStartsTheFirstStoryRunOnce() {
    actor.onTick(NOW, 1000);
    actor.onTick(NOW + 14_000, 1000);
    assertEquals(0, actor.starts);
    actor.onTick(NOW + 16_000, 1000);
    assertEquals(1, actor.starts);
    assertEquals(-23854, actor.getPos().getX(), "The tutorial begins at its authored room anchor");
    assertEquals(236150, actor.getPos().getZ(), "The first encounter is ahead of the player");
    actor.onTick(NOW + 40_000, 1000);
    assertEquals(1, actor.starts);
  }

  @Test void leavingTheTriggerRestartsTheWait() {
    actor.onTick(NOW, 1000);
    actor.position.set(20_000, 608, -90);
    actor.onTick(NOW + 10_000, 1000);
    actor.position.set(9250, 608, -90);
    actor.onTick(NOW + 20_000, 1000);
    actor.onTick(NOW + 34_000, 1000);
    assertEquals(0, actor.starts);
    actor.onTick(NOW + 36_000, 1000);
    assertEquals(1, actor.starts);
  }

  @Test void anUnfinishedOrCancelledDialogueDoesNotStartTheRun() {
    actor.getGameplay().getSubMissions().put(403000101, new QuestState(403000101));
    actor.onTick(NOW, 1000); actor.onTick(NOW + 30_000, 1000);
    assertEquals(0, actor.starts);
    var done = new QuestState(403000101); done.advance(1, 1, 100);
    actor.getGameplay().getSubMissions().put(403000101, done);
    actor.getGameplay().getCancelledSubs().add(403000101);
    actor.onTick(NOW + 40_000, 1000); actor.onTick(NOW + 60_000, 1000);
    assertEquals(0, actor.starts);
  }

  @Test void aDisconnectedSessionCannotCarryTheOldWaitIntoAReconnection() {
    actor.onTick(NOW, 1000);
    session.setState(SessionState.INACTIVE);
    actor.onTick(NOW + 10_000, 1000);
    session.setState(SessionState.ACTIVE);
    actor.onTick(NOW + 20_000, 1000);
    actor.onTick(NOW + 34_000, 1000);
    assertEquals(0, actor.starts);
    actor.onTick(NOW + 36_000, 1000);
    assertEquals(1, actor.starts);
  }

  @Test void aNativeStartBeforeTheWaitIsNotStartedAgain() {
    actor.onTick(NOW, 1000);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, actor.getUniverseManager().start(100));
    assertEquals(1, actor.starts);
    actor.onTick(NOW + 30_000, 1000);
    assertEquals(1, actor.starts);
  }

  @Test void resumingTheTutorialUsesTheAuthoredStartAndKeepsTheSameRun() {
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, actor.getUniverseManager().start(100));
    var run = actor.getGameplay().getRogue();
    var id = run.getRunId();
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, actor.getUniverseManager().leave(false));
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, actor.getUniverseManager().resume(100, false));
    assertEquals(id, run.getRunId());
    assertEquals(-23854, actor.position.getX());
    assertEquals(236150, actor.position.getZ());
  }

  @Test void aDifferentTargetOrMissingAuthoredTriggerCannotStartTheRun() {
    var original = GameplayDefinitions.subs.get(403000102);
    var altered = gson.fromJson("{\"ID\":403000102,\"MainMissionID\":4030001,"
        + "\"LevelPlaneID\":80112,\"LevelFloorID\":80112001,\"FinishType\":\"EnterFloor\","
        + "\"ParamType\":\"Equal\",\"ParamInt1\":80001,\"ParamInt2\":80001002,\"Progress\":1}",
        MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(403000102, altered);
    actor.onTick(NOW, 1000); actor.onTick(NOW + 30_000, 1000);
    assertEquals(0, actor.starts);
    GameplayDefinitions.subs.put(403000102, original);
    actor.getScene().getResourceGroups().get(18).getPropList().clear();
    actor.onTick(NOW + 40_000, 1000); actor.onTick(NOW + 60_000, 1000);
    assertEquals(0, actor.starts);
  }

  @Test void aDifferentOperatorMapOrMissingTutorialRoomCannotBeEnteredAutomatically() {
    GameplayDefinitions.rooms.remove(100);
    actor.onTick(NOW, 1000); actor.onTick(NOW + 30_000, 1000);
    assertEquals(0, actor.starts);
    GameplayDefinitions.rooms.put(100, new GameplayDefinitions.Room(100, 1, 8000101, 10,
        Map.of(10, 1006, 3, 1001, 4, 1002, 5, 1003, 11, 1004, 12, 1005)));
    GameplayDefinitions.rooms.put(304, new GameplayDefinitions.Room(304, 1, 8000103, 18));
    GameplayDefinitions.roomPoolByMap.put(1, Map.of(1, List.of(304)));
    actor.onTick(NOW + 40_000, 1000); actor.onTick(NOW + 60_000, 1000);
    assertEquals(0, actor.starts);
  }

  private GameplayState.RogueRun strandedLegacyRun() {
    actor.entry = 8000101;
    actor.position.set(1285, 122, 42242);
    var run = new GameplayState.RogueRun(100, 1, Map.of(1, 100));
    run.enter(1, 100);
    run.setInside(true);
    actor.getGameplay().setRogue(run);
    var entered = new QuestState(403000102);
    assertTrue(entered.advance(1, 1, 100));
    actor.getGameplay().getSubMissions().put(403000102, entered);
    actor.getGameplay().getSubMissions().put(403000104, new QuestState(403000104));
    return run;
  }

  @Test void untouchedLegacyRunAtWrongEndRecoversOnceWithoutResettingProgress() {
    var run = strandedLegacyRun();
    var id = run.getRunId();
    assertTrue(RogueFirstStorySpawn.recoverOnLogin(actor));
    assertEquals(-23854, actor.position.getX());
    assertEquals(236150, actor.position.getZ());
    assertEquals(180000, actor.rotation.getY());
    assertTrue(run.isFirstStoryEntrancePlaced());
    assertEquals(id, run.getRunId());
    assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run.getPhase());
    assertEquals(QuestState.Phase.DOING, actor.getGameplay().getSubMissions().get(403000104).getPhase());
    assertEquals(1, actor.saves);
    var restored = gson.fromJson(gson.toJson(run), GameplayState.RogueRun.class);
    assertTrue(restored.isFirstStoryEntrancePlaced(), "The one-time receipt survives a reload");
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(actor));
    assertEquals(1, actor.saves);
  }

  @Test void progressedOrCorrectlyPositionedRunsAreNeverMoved() {
    var run = strandedLegacyRun();
    run.getProcessedBattles().add("1:123");
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(actor));
    assertEquals(42242, actor.position.getZ());
    run.getProcessedBattles().clear();
    run.getDefeatedRogueMonsters().add("1:100:80001:80001001:3:200001");
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(actor));
    run.getDefeatedRogueMonsters().clear();
    actor.position.set(-23854, 5, 236150);
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(actor));
    assertFalse(run.isFirstStoryEntrancePlaced());
  }

  @Test void untouchedLegacyRunStillRecoversAfterWalkingBackThroughEmptyCorridor() {
    strandedLegacyRun();
    actor.position.set(-12000, 122, 150000);
    assertTrue(RogueFirstStorySpawn.recoverOnLogin(actor));
    assertEquals(-23854, actor.position.getX());
    assertEquals(236150, actor.position.getZ());
  }

  @Test void legacyRecoveryRequiresItsMissionAndUnmodifiedSourceAnchor() {
    var run = strandedLegacyRun();
    actor.getGameplay().getSubMissions().remove(403000104);
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(actor));
    actor.getGameplay().getSubMissions().put(403000104, new QuestState(403000104));
    GameData.getFloorInfo(80001, 80001001).getGroups().get(10).getAnchorList().clear();
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(actor));
    assertEquals(42242, actor.position.getZ());
    assertFalse(run.isFirstStoryEntrancePlaced());
  }

  @Test void recoverySaveFailureLeavesTheOldPositionAndReceiptIntact() {
    var run = strandedLegacyRun();
    actor.failSave = true;
    assertFalse(RogueFirstStorySpawn.recoverOnLogin(actor));
    assertEquals(1285, actor.position.getX());
    assertEquals(42242, actor.position.getZ());
    assertFalse(run.isFirstStoryEntrancePlaced());
  }
}
