package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import dev.morphia.Morphia;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.game.GameServer;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.protocol.Gameplay46;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Full opening definitions and client scripts, with an isolated empty test party. */
class OpeningStageWin46Test {
  static final int MAIN = 1000101, SUB = 100010103, NEXT = 100010105, EVENT = 20001001;
  @TempDir Path temp;
  final Gson gson = ResourceLoader.getGson();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final List<StageExcel> oldStages = new ArrayList<>();
  final List<PlaneEventExcel> oldEvents = new ArrayList<>();
  final Map<Integer, emu.lunarcore.data.config.MissionInfo> oldInfos = new HashMap<>();
  final Map<Integer, emu.lunarcore.data.config.MissionInfo.SubMissionInfo> oldSubs =
      new HashMap<>();
  final MissionFarmingBattleTest.Service service = new MissionFarmingBattleTest.Service();
  Actor actor;
  JsonObject script;

  static class Actor extends MissionFarmingBattleTest.Farmer {
    int floor = 20001001, world, ids, saves;

    @Override
    public int getPlaneId() {
      return floor / 1000;
    }

    @Override
    public int getFloorId() {
      return floor;
    }

    @Override
    public int getEntryId() {
      return 2000101;
    }

    @Override
    public int getWorldLevel() {
      return world;
    }

    @Override
    public int getNextBattleId() {
      return ++ids;
    }

    @Override
    public void save() {
      saves++;
    }
  }

  class Session extends GameSession {
    final Player owner;
    final GameServer shell;
    final Map<String, DynamicMessage> responses = new HashMap<>();

    Session(Player owner) throws Exception {
      super(null);
      this.owner = owner;
      setState(SessionState.ACTIVE);
      owner.setSession(this);
      // An unstarted service shell: native packet dispatch needs no socket or global server.
      var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      singleton.setAccessible(true);
      shell =
          (GameServer) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(GameServer.class);
      var field = GameServer.class.getDeclaredField("battleService");
      field.setAccessible(true);
      field.set(shell, service);
    }

    @Override
    public Player getPlayer() {
      return owner;
    }

    @Override
    public GameServer getServer() {
      return shell;
    }

    @Override
    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    @Override
    public void sendClientPacket(String name, byte[] body) {
      try {
        responses.put(name, getProtocol().clientSchema().parse(name, body));
      } catch (java.io.IOException error) {
        throw new AssertionError(error);
      }
    }

    @Override
    public void send(BasePacket packet) {
      if (packet.getCmdId() == CmdId.PVEBattleResultScRsp) {
        var converted = getProtocol().outbound(packet.getCmdId(), packet.getData().toByteArray());
        sendClientPacket("PVEBattleResultScRsp", converted.body());
      }
    }

    DynamicMessage request(String name, Map<String, Object> fields) throws Exception {
      var request = DynamicMessage.newBuilder(getProtocol().clientSchema().message(name));
      fields.forEach(
          (key, value) ->
              request.setField(request.getDescriptorForType().findFieldByName(key), value));
      assertTrue(
          VersionedPacketHandler.handle(
              this, getProtocol().clientCommand(name), request.build().toByteArray()));
      return responses.get(name.replace("CsReq", "ScRsp"));
    }
  }

  static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  void assertSnapshot(DynamicMessage snapshot) {
    assertEquals(0, field(snapshot, "retcode"));
    var missions = (List<?>) field(snapshot, "mission_list");
    for (int id : new int[] {SUB, NEXT}) {
      var mission =
          missions.stream()
              .map(row -> (DynamicMessage) row)
              .filter(row -> field(row, "id").equals(id))
              .findFirst()
              .orElseThrow();
      assertEquals(id == SUB ? 1 : 0, field(mission, "progress"));
      assertEquals(
          id == SUB ? "MissionStatus_MissionFinish" : "MissionStatus_MissionDoing",
          ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(mission, "status"))
              .getName());
    }
  }

  @BeforeEach
  void load() throws Exception {
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldStages.addAll(GameData.getStageExcelMap().values());
    oldEvents.addAll(GameData.getPlaneEventExcelMap().values());
    oldInfos.putAll(GameData.getMissionInfos());
    oldSubs.putAll(GameplayDefinitions.subs);
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getStageExcelMap().values().clear();
    GameData.getPlaneEventExcelMap().values().clear();
    try (var input = getClass().getResourceAsStream("/fixtures/opening-stage-win-4.6.json")) {
      var fixture =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      for (var raw : fixture.getAsJsonArray("records")) {
        var record = raw.getAsJsonObject();
        var row = record.getAsJsonObject("row");
        switch (record.get("kind").getAsString()) {
          case "file" -> {
            var name = record.get("source").getAsString();
            var path = temp.resolve(name);
            Files.createDirectories(path.getParent());
            Files.writeString(path, row.toString());
            if (name.endsWith("Mission_" + SUB + ".json")) script = row.deepCopy();
          }
          case "MainMission" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "PlaneEvent" ->
              GameData.getPlaneEventExcelMap().add(gson.fromJson(row, PlaneEventExcel.class));
          case "StageConfig" -> {
            var stage = gson.fromJson(row, StageExcel.class);
            stage.onLoad();
            GameData.getStageExcelMap().add(stage);
          }
          default -> fail("Unknown fixture record");
        }
      }
    }
    GameplayDefinitions.load(temp);
    actor = new Actor();
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(MAIN));
  }

  @AfterEach
  void restore() {
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getStageExcelMap().values().clear();
    oldStages.forEach(GameData.getStageExcelMap()::add);
    GameData.getPlaneEventExcelMap().values().clear();
    oldEvents.forEach(GameData.getPlaneEventExcelMap()::add);
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldInfos);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
  }

  void finishIntro() {
    for (int id : new int[] {100010102, 100010100, 100010104})
      assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(id, "Mission_" + id));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(SUB).getPhase());
    assertNull(actor.getMissionManager().sub(NEXT));
    assertNotNull(GameplayDefinitions.subs.get(SUB).getTask());
  }

  Battle finish(Battle battle, BattleEndStatus result) {
    return service.finishBattle(
        actor, battle.getId(), battle.getStage().getId(), result, BattleStatistics.newInstance());
  }

  @Test
  void firstVictoryCompletesOriginalBattleGoalAndUnlocksTheDoorObjective() {
    finishIntro();
    var battle = Objects.requireNonNull(service.startEventBattle(actor, EVENT));
    assertEquals(200010010, battle.getStage().getId());
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(
        QuestState.Phase.FINISHED,
        actor.getMissionManager().sub(SUB).getPhase(),
        "Winning the first battle must stop its client mission from restarting it");
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(NEXT).getPhase());
    assertNull(actor.getBattle());
    assertNull(finish(battle, BattleEndStatus.BATTLE_END_WIN));
  }

  @Test
  void losingOrQuittingKeepsTheOpeningBattleRetryableUntilARealWin() {
    finishIntro();
    for (var result : List.of(BattleEndStatus.BATTLE_END_LOSE, BattleEndStatus.BATTLE_END_QUIT)) {
      var battle = Objects.requireNonNull(service.startEventBattle(actor, EVENT));
      assertNotNull(finish(battle, result));
      assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(SUB).getPhase());
      assertNull(actor.getMissionManager().sub(NEXT));
    }
    var battle = Objects.requireNonNull(service.startEventBattle(actor, EVENT));
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(QuestState.Phase.FINISHED, actor.getMissionManager().sub(SUB).getPhase());
  }

  @Test
  void staleBattleIdentityCannotFinishTheMissionButTheCurrentWinCan() {
    finishIntro();
    var battle = Objects.requireNonNull(service.startEventBattle(actor, EVENT));
    assertNull(
        service.finishBattle(
            actor,
            battle.getId() + 1,
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(SUB).getPhase());
    assertNull(actor.getMissionManager().sub(NEXT));
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertNotNull(actor.getMissionManager().sub(NEXT));
  }

  @Test
  void anObjectiveAcceptedAfterTheBattleStartedCannotConsumeItsVictory() {
    var battle = Objects.requireNonNull(service.startEventBattle(actor, EVENT));
    finishIntro();
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(SUB).getPhase());
    assertNull(actor.getMissionManager().sub(NEXT));
  }

  @Test
  void anotherOpeningBattleCannotCompleteTheFirstBattleGoal() {
    finishIntro();
    var battle = Objects.requireNonNull(service.startEventBattle(actor, 20001002));
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(SUB).getPhase());
    assertNull(actor.getMissionManager().sub(NEXT));
  }

  @Test
  void currentWorldUsesItsOwnExactEventMapping() {
    actor.world = 1;
    finishIntro();
    var battle = Objects.requireNonNull(service.startEventBattle(actor, EVENT));
    assertEquals(200010011, battle.getStage().getId());
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(QuestState.Phase.FINISHED, actor.getMissionManager().sub(SUB).getPhase());
  }

  @Test
  void nativeBattleWinPublishesFinishedMissionAndTheNextGoal() throws Exception {
    var session = new Session(actor);
    for (int id : new int[] {100010102, 100010100, 100010104}) {
      var response =
          session.request(
              "FinishTalkMissionCsReq", Map.of("sub_mission_id", id, "talk_str", "Mission_" + id));
      assertEquals(0, field(response, "retcode"));
    }
    var entered = session.request("SceneEnterStageCsReq", Map.of("event_id", EVENT));
    assertEquals(0, field(entered, "retcode"));
    var info = (DynamicMessage) field(entered, "battle_info");
    var end =
        session
            .getProtocol()
            .clientSchema()
            .message("PVEBattleResultCsReq")
            .findFieldByName("end_status");
    var report =
        Map.<String, Object>of(
            "battle_id",
            field(info, "battle_id"),
            "stage_id",
            field(info, "stage_id"),
            "end_status",
            end.getEnumType().findValueByName("BATTLE_END_WIN"));
    assertEquals(0, field(session.request("PVEBattleResultCsReq", report), "retcode"));
    assertSnapshot(session.request("GetMissionDataCsReq", Map.of()));
    assertNotEquals(0, field(session.request("PVEBattleResultCsReq", report), "retcode"));
    assertSnapshot(session.request("GetMissionDataCsReq", Map.of()));
  }

  @Test
  void completedOpeningGoalAndNextObjectiveSurviveActualPlayerPersistence() throws Exception {
    finishIntro();
    var battle = Objects.requireNonNull(service.startEventBattle(actor, EVENT));
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN));
    var server = new MongoServer(new MemoryBackend());
    var address = server.bind();
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = Morphia.createDatastore(client, "opening-stage-win");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var saved = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(saved, 123456782);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(saved, actor.getGameplay());
      store.save(saved);
      for (int round = 0; round < 2; round++) {
        var restored = Objects.requireNonNull(store.find(Player.class).first());
        assertEquals(QuestState.Phase.FINISHED, restored.getMissionManager().sub(SUB).getPhase());
        assertEquals(QuestState.Phase.DOING, restored.getMissionManager().sub(NEXT).getPhase());
        assertSnapshot(Gameplay46.missionSnapshot(new Session(restored), false));
        store.save(restored);
      }
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"floor", "world", "script", "cancel", "event"})
  void changesDuringBattleCannotCompleteItsFrozenMissionTarget(String change) {
    finishIntro();
    var battle = Objects.requireNonNull(service.startEventBattle(actor, EVENT));
    switch (change) {
      case "floor" -> actor.floor++;
      case "world" -> actor.world++;
      case "script" -> {
        var altered = script.deepCopy();
        altered.addProperty("clientPresentationChanged", true);
        var task = gson.fromJson(altered, MissionTaskInfo.class);
        task.retainSourceDefinition(altered);
        GameplayDefinitions.subs.get(SUB).setTask(task);
      }
      case "cancel" -> actor.getGameplay().getCancelledSubs().add(SUB);
      case "event" ->
          GameData.getPlaneEventExcelMap()
              .add(
                  gson.fromJson(
                      "{\"EventID\":20001001,\"StageID\":200010020}", PlaneEventExcel.class));
      default -> fail("Unknown mutation");
    }
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(SUB).getPhase());
    assertNull(actor.getMissionManager().sub(NEXT));
  }
}
