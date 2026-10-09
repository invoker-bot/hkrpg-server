package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.server.game.*;
import emu.lunarcore.server.packet.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original event families, an unstarted server shell and an empty unit-test lineup. */
class SceneEventBattle46Test {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
  static final int EVENT = 20134221, STAGE = 201342210;
  final Gson gson = new Gson();
  final MissionFarmingBattleTest.Service service = new MissionFarmingBattleTest.Service();
  final List<PlaneEventExcel> oldEvents = new ArrayList<>();
  final List<StageExcel> oldStages = new ArrayList<>();
  JsonArray source;
  GameServer shell;

  static class Actor extends MissionFarmingBattleTest.Farmer {
    int world;
    int saves;
    int ids;

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
    final Actor owner = new Actor();
    DynamicMessage response;
    final List<DynamicMessage> responses = new ArrayList<>();

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
      owner.setSession(this);
    }

    @Override
    public Actor getPlayer() {
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
        assertEquals("SceneEnterStageScRsp", name);
        response = getProtocol().clientSchema().parse(name, body);
        responses.add(response);
      } catch (java.io.IOException error) {
        throw new AssertionError(error);
      }
    }

    @Override
    public void send(BasePacket packet) {
      var converted = getProtocol().outbound(packet.getCmdId(), packet.getData().toByteArray());
      sendClientPacket("SceneEnterStageScRsp", converted.body());
    }
  }

  @BeforeEach
  void load() throws Exception {
    oldEvents.addAll(GameData.getPlaneEventExcelMap().values());
    oldStages.addAll(GameData.getStageExcelMap().values());
    GameData.getPlaneEventExcelMap().values().clear();
    GameData.getStageExcelMap().values().clear();
    try (var input = getClass().getResourceAsStream("/fixtures/scene-event-entry-4.6.json")) {
      source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    assertEquals(30, source.size());
    for (var raw : source) {
      var entry = raw.getAsJsonObject();
      switch (entry.get("kind").getAsString()) {
        case "event" ->
            GameData.getPlaneEventExcelMap()
                .add(gson.fromJson(entry.get("row"), PlaneEventExcel.class));
        case "stage" -> {
          var stage =
              emu.lunarcore.data.ResourceLoader.getGson()
                  .fromJson(entry.get("row"), StageExcel.class);
          stage.onLoad();
          GameData.getStageExcelMap().add(stage);
        }
      }
    }
    // Only the real service getter is needed. No HTTP server, socket, timer or database is started.
    var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    singleton.setAccessible(true);
    shell = (GameServer) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(GameServer.class);
    var field = GameServer.class.getDeclaredField("battleService");
    field.setAccessible(true);
    field.set(shell, service);
  }

  @AfterEach
  void restore() {
    GameData.getPlaneEventExcelMap().values().clear();
    oldEvents.forEach(GameData.getPlaneEventExcelMap()::add);
    GameData.getStageExcelMap().values().clear();
    oldStages.forEach(GameData.getStageExcelMap()::add);
  }

  byte[] request(int event) {
    var type = ProtocolBridge.forVersion("4.6.0").clientSchema().message("SceneEnterStageCsReq");
    return DynamicMessage.newBuilder(type)
        .setField(type.findFieldByName("event_id"), event)
        .build()
        .toByteArray();
  }

  void dispatch(Session session, byte[] bytes) throws Exception {
    if (!VersionedPacketHandler.handle(session, 1404, bytes)) {
      var converted = session.getProtocol().inbound(1404, bytes);
      new emu.lunarcore.server.packet.recv.HandlerSceneEnterStageCsReq()
          .handle(session, converted.body());
    }
  }

  void rejected(Session session, byte[] bytes) throws Exception {
    dispatch(session, bytes);
    assertNotNull(session.response);
    assertNotEquals(
        0,
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
    assertNull(session.owner.getBattle());
    assertEquals(0, session.owner.saves);
    assertEquals(0, session.owner.grants);
    assertEquals(
        1, session.owner.getNextBattleId(), "Rejected entry must not allocate a battle identity");
  }

  @Test
  void actualStageIdCannotStandInForTheMissingNativeEvent() throws Exception {
    rejected(new Session(), request(STAGE));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "eventId",
        "eventWorld",
        "stageId",
        "zeroStage",
        "emptyWaves",
        "emptyWave",
        "negativeMonster"
      })
  void corruptCanonicalResourceCannotStartOrConsumeIdentity(String defect) throws Exception {
    var event = GameData.getPlaneEventExcelMap().get(EVENT, 0);
    var stage = GameData.getStageExcelMap().get(STAGE);
    switch (defect) {
      case "eventId" -> DailyMissionAcceptance46Test.set(event, "EventID", EVENT + 1);
      case "eventWorld" -> DailyMissionAcceptance46Test.set(event, "WorldLevel", 1);
      case "stageId" -> DailyMissionAcceptance46Test.set(stage, "StageID", STAGE + 1);
      case "zeroStage" -> {
        DailyMissionAcceptance46Test.set(event, "StageID", 0);
        DailyMissionAcceptance46Test.set(stage, "StageID", 0);
        GameData.getStageExcelMap().add(stage);
      }
      case "emptyWaves" -> stage.getMonsterWaves().clear();
      case "emptyWave" -> stage.getMonsterWaves().getFirst().clear();
      case "negativeMonster" -> stage.getMonsterWaves().getFirst().set(0, -1);
    }
    rejected(new Session(), request(EVENT));
  }

  @Test
  void nativeEventDoesNotFallBackToWorldZeroForAnUnknownWorld() throws Exception {
    var session = new Session();
    session.owner.world = 7;
    rejected(session, request(STAGE));
  }

  @Test
  void negativePlayerWorldCannotUseADirectStageFallback() throws Exception {
    var session = new Session();
    session.owner.world = -1;
    rejected(session, request(STAGE));
  }

  @Test
  void originalScriptEventMissingFromTableIsRejectedWithoutGuessingItsNeighbour() throws Exception {
    var script =
        source.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(
                r ->
                    r.get("kind").getAsString().equals("script")
                        && r.get("source").getAsString().contains("3011602"))
            .findFirst()
            .orElseThrow();
    var task =
        script
            .getAsJsonObject("row")
            .getAsJsonArray("OnStartSequece")
            .get(1)
            .getAsJsonObject()
            .getAsJsonArray("TaskList")
            .get(1)
            .getAsJsonObject();
    int authored =
        task.getAsJsonObject("EventID").getAsJsonObject("FixedValue").get("Value").getAsInt();
    assertEquals(20134220, authored);
    assertNull(GameData.getPlaneEventExcelMap().get(authored, 0));
    rejected(new Session(), request(authored));
  }

  @ParameterizedTest
  @CsvSource({
    "20134221,0",
    "20134221,1",
    "20134221,2",
    "20134221,3",
    "20134221,4",
    "20134221,5",
    "20134221,6",
    "20134222,0",
    "20134222,1",
    "20134222,2",
    "20134222,3",
    "20134222,4",
    "20134222,5",
    "20134222,6"
  })
  void allOriginalWorldVariantsUseTheirExactStageAndMonsterWaves(int event, int world)
      throws Exception {
    var session = new Session();
    session.owner.world = world;
    dispatch(session, request(event));
    var battle = Objects.requireNonNull(session.owner.getBattle());
    var original = GameData.getStageExcelMap().get(event * 10 + world);
    assertSame(original, battle.getStage());
    assertEquals(1, battle.getId());
    assertEquals(original.getMonsterWaves().size(), battle.getWaves().size());
    for (int i = 0; i < battle.getWaves().size(); i++)
      assertEquals(original.getMonsterWaves().get(i), battle.getWaves().get(i).getMonsters());
    assertEquals(
        0,
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
    var nativeBattle =
        (DynamicMessage)
            session.response.getField(
                session.response.getDescriptorForType().findFieldByName("battle_info"));
    assertEquals(
        original.getId(),
        nativeBattle.getField(nativeBattle.getDescriptorForType().findFieldByName("stage_id")));
    assertEquals(0, session.owner.saves);
    assertEquals(0, session.owner.grants);
  }

  byte[] fields(long... tagsAndValues) throws java.io.IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    var wire = com.google.protobuf.CodedOutputStream.newInstance(bytes);
    for (int i = 0; i < tagsAndValues.length; i += 2) {
      wire.writeUInt32NoTag((int) tagsAndValues[i]);
      wire.writeUInt64NoTag(tagsAndValues[i + 1]);
    }
    wire.flush();
    return bytes.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "empty",
        "zero",
        "negative",
        "uintMax",
        "wideAlias",
        "duplicate",
        "duplicateZeroOption",
        "unknown",
        "truncatedTag",
        "truncatedValue",
        "wrongWire",
        "unknownEnum",
        "otherEnum"
      })
  void malformedNativeBodyCannotAllocateBattleOrChangePlayer(String defect) throws Exception {
    byte[] body =
        switch (defect) {
          case "empty" -> new byte[0];
          case "zero" -> fields(88, 0);
          case "negative" -> fields(88, -1);
          case "uintMax" -> fields(88, 0xffffffffL);
          case "wideAlias" -> fields(88, (1L << 32) + EVENT);
          case "duplicate" -> fields(88, EVENT, 88, EVENT);
          case "duplicateZeroOption" -> fields(88, EVENT, 80, 0, 80, 0);
          case "unknown" -> fields(88, EVENT, 128, 0);
          case "truncatedTag" -> new byte[] {(byte) 128};
          case "truncatedValue" -> new byte[] {88, (byte) 128};
          case "wrongWire" -> new byte[] {90, 0};
          case "unknownEnum" -> fields(88, EVENT, 80, 999);
          default -> fields(88, EVENT, 80, 1);
        };
    rejected(new Session(), body);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missingEvent", "missingStage", "nullWaves", "nullWave"})
  void missingRuntimeResourceIsHeldBeforeCreatingBattle(String defect) throws Exception {
    var stage = GameData.getStageExcelMap().get(STAGE);
    switch (defect) {
      case "missingEvent" ->
          GameData.getPlaneEventExcelMap().values().removeIf(e -> e.getEventId() == EVENT);
      case "missingStage" -> GameData.getStageExcelMap().keySet().remove(STAGE);
      case "nullWaves" -> DailyMissionAcceptance46Test.set(stage, "monsterWaves", null);
      case "nullWave" -> stage.getMonsterWaves().set(0, null);
    }
    rejected(new Session(), request(EVENT));
  }

  @Test
  void repeatedEntryKeepsTheOriginalBattleAndItsIdentity() throws Exception {
    var session = new Session();
    dispatch(session, request(EVENT));
    var original = session.owner.getBattle();
    dispatch(session, request(EVENT + 1));
    assertSame(original, session.owner.getBattle());
    assertNotEquals(
        0,
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(1, session.owner.ids);
    assertEquals(0, session.owner.saves);
  }

  @Test
  void concurrentNativeEntriesCreateOneBattleAndReturnOneSuccess() throws Exception {
    var session = new Session();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var calls = new ArrayList<java.util.concurrent.Callable<Void>>();
      for (int i = 0; i < 12; i++)
        calls.add(
            () -> {
              dispatch(session, request(EVENT));
              return null;
            });
      for (var result : pool.invokeAll(calls)) result.get();
    } finally {
      pool.shutdownNow();
    }
    assertEquals(12, session.responses.size());
    assertEquals(
        1,
        session.responses.stream()
            .filter(r -> r.getField(r.getDescriptorForType().findFieldByName("retcode")).equals(0))
            .count());
    assertEquals(STAGE, session.owner.getBattle().getStage().getId());
    assertEquals(1, session.owner.ids);
    assertEquals(0, session.owner.saves);
    assertEquals(0, session.owner.grants);
  }

  @Test
  void blockedStoryTransitionDoesNotEnterBattle() throws Exception {
    var session = new Session();
    session.owner.getGameplay().getStoryLines().begin(1, null, null);
    rejected(session, request(EVENT));
  }

  @Test
  void unauthenticatedEntryDoesNotParseMalformedBodyOrRespond() throws Exception {
    var session = new Session();
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session, 1404, new byte[] {(byte) 128}));
    assertNull(session.response);
    assertNull(session.owner.getBattle());
    assertEquals(0, session.owner.ids);
  }

  @Test
  void legacyAndUnrelatedCommandsRemainWithTheirOriginalHandlers() throws Exception {
    var session = new Session();
    assertFalse(SceneEventBattle46.handle(session, 1234, new byte[0]));
    var legacy =
        new GameSession(null) {
          @Override
          public ProtocolBridge getProtocol() {
            return ProtocolBridge.forVersion("4.2.0");
          }
        };
    assertFalse(SceneEventBattle46.handle(legacy, CmdId.SceneEnterStageCsReq, new byte[0]));
    var actor = new Actor();
    var direct = service.startBattle(actor, STAGE, 0);
    assertNotNull(direct);
    assertEquals(STAGE, direct.getStage().getId());
  }

  @ParameterizedTest
  @ValueSource(ints = {3011602, 3011607})
  void originalDailyGraphUsesTheAcceptedEventBattleForStageProgressAndItsActualTailReward(int main)
      throws Exception {
    var fixture = new DailyStageWin46Test();
    fixture.temp = temp;
    try {
      fixture.setup();
      fixture.single(main);
      fixture.reachStage(main);
      int event = main == 3011602 ? 20134221 : 20134222;
      var player = fixture.actor();
      var sub = fixture.stageGoal(main);
      assertEquals(0, player.getMissionManager().sub(sub.getId()).getProgress());
      assertNull(service.startEventBattle(player, 20134220));
      var battle = Objects.requireNonNull(service.startEventBattle(player, event));
      assertSame(GameData.getStageExcelMap().get(event * 10), battle.getStage());
      assertEquals(0, player.getMissionManager().sub(sub.getId()).getProgress());
      fixture.win(battle);
      assertEquals(1, player.getMissionManager().sub(sub.getId()).getProgress());
      assertEquals(0, player.getScoin());
      fixture.finishTail(main);
      assertEquals(5000, player.getScoin());
      assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(2100003).retcode());
    } finally {
      fixture.cleanup();
    }
  }

  @Test
  void localClientEvidenceMatchesFrozenEventFieldAndExactTwoPartResourceLookup() throws Exception {
    JsonObject proof;
    try (var input = getClass().getResourceAsStream("/fixtures/scene-event-client-4.6.json")) {
      proof =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    assertFalse(proof.get("clientExecutableStarted").getAsBoolean());
    assertFalse(proof.get("clientModified").getAsBoolean());
    assertFalse(proof.get("clientAccepted").getAsBoolean());
    var type = ProtocolBridge.forVersion("4.6.0").clientSchema().message("SceneEnterStageCsReq");
    assertEquals(11, type.findFieldByName("event_id").getNumber());
    assertEquals(10, type.findFieldByName("NKDAAAGPCDB").getNumber());
    assertEquals(1404, ProtocolBridge.forVersion("4.6.0").clientCommand("SceneEnterStageCsReq"));
    var methods = new HashMap<Integer, JsonObject>();
    for (var row : proof.getAsJsonArray("methods")) {
      var method = row.getAsJsonObject();
      methods.put(method.get("index").getAsInt(), method);
      for (var line : method.getAsJsonArray("instructions"))
        assertTrue(
            Long.parseLong(line.getAsString().split(":")[0], 16)
                < Long.decode(method.get("boundedEndRVA").getAsString()));
    }
    assertEquals("TriggerBattleByEventLegacy", methods.get(552761).get("name").getAsString());
    assertTrue(
        methods.get(552761).getAsJsonArray("instructions").asList().stream()
            .anyMatch(row -> row.getAsString().equals("c60dd13: mov dword ptr [rax + 0x1c], esi")));
    assertTrue(
        methods.get(232690).getAsJsonArray("instructions").asList().stream()
            .anyMatch(row -> row.getAsString().contains("0x58")));
    assertEquals("PlaneEventExcelTable", methods.get(94813).get("typeName").getAsString());
    assertEquals(2, methods.get(94813).getAsJsonArray("parameters").size());
    assertTrue(
        methods.get(94813).getAsJsonArray("instructions").asList().stream()
            .anyMatch(row -> row.getAsString().equals("1d8f1201: shl rsi, 0x20")));
  }
}
