package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.recv.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TutorialProgress46Test {
  List<TutorialExcel> oldTutorials;
  List<TutorialGuideExcel> oldGuides;

  static class Actor extends Player {
    int saves;
    boolean failSave;

    @Override
    public String getName() {
      return "Tutorial fixture";
    }

    @Override
    public void save() {
      if (failSave) throw new IllegalStateException("fixture save failure");
      saves++;
    }
  }

  static class Session extends GameSession {
    final Actor actor = new Actor();
    BasePacket packet;
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    DynamicMessage response;
    final List<String> sent = new ArrayList<>();
    final List<DynamicMessage> responses = new ArrayList<>();

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    @Override
    public Player getPlayer() {
      return actor;
    }

    @Override
    public void send(BasePacket value) {
      packet = value;
    }

    @Override
    public ProtocolBridge getProtocol() {
      return bridge;
    }

    @Override
    public void sendClientPacket(String name, byte[] body) {
      try {
        sent.add(name);
        response = bridge.clientSchema().parse(name, body);
        responses.add(response);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }

  @BeforeEach
  void prepare() {
    oldTutorials = new ArrayList<>(GameData.getTutorialExcelMap().values());
    oldGuides = new ArrayList<>(GameData.getTutorialGuideExcelMap().values());
    GameData.getTutorialExcelMap().values().clear();
    GameData.getTutorialGuideExcelMap().values().clear();
    GameData.getTutorialExcelMap()
        .add(new Gson().fromJson("{\"TutorialID\":1001}", TutorialExcel.class));
    GameData.getTutorialGuideExcelMap()
        .add(new Gson().fromJson("{\"GroupID\":1101}", TutorialGuideExcel.class));
  }

  @AfterEach
  void restore() {
    GameData.getTutorialExcelMap().values().clear();
    GameData.getTutorialGuideExcelMap().values().clear();
    oldTutorials.forEach(GameData.getTutorialExcelMap()::add);
    oldGuides.forEach(GameData.getTutorialGuideExcelMap()::add);
  }

  @Test
  void freshAccountHasNoCompletedTutorialsOrGuides() throws Exception {
    var session = new Session();
    new HandlerGetTutorialCsReq().handle(session, new byte[0]);
    var tutorials =
        (emu.lunarcore.proto.GetTutorialScRspOuterClass.GetTutorialScRsp) session.packet.getData();
    assertEquals(
        0, tutorials.getTutorialList().length(), "Catalog presence is not player completion");
    new HandlerGetTutorialGuideCsReq().handle(session, new byte[0]);
    var guides =
        (emu.lunarcore.proto.GetTutorialGuideScRspOuterClass.GetTutorialGuideScRsp)
            session.packet.getData();
    assertEquals(0, guides.getTutorialGuideList().length());
    assertEquals(0, session.actor.saves, "Queries must not fabricate progress");
  }

  DynamicMessage call(Session session, String name, int id, int type) throws Exception {
    var request = DynamicMessage.newBuilder(session.bridge.clientSchema().message(name + "CsReq"));
    String idName =
        name.startsWith("Get")
            ? null
            : name.endsWith("Guide")
                ? "group_id"
                : name.startsWith("Unlock") ? "HPIMDINCCNN" : "tutorial_id";
    if (idName != null)
      request.setField(request.getDescriptorForType().findFieldByName(idName), id);
    if (name.endsWith("Guide") && !name.startsWith("Get")) {
      var field = request.getDescriptorForType().findFieldByName("type");
      request.setField(field, field.getEnumType().findValueByNumber(type));
    }
    assertTrue(
        VersionedPacketHandler.handle(
            session, session.bridge.clientCommand(name + "CsReq"), request.build().toByteArray()));
    return session.response;
  }

  int ret(DynamicMessage message) {
    return (int) message.getField(message.getDescriptorForType().findFieldByName("retcode"));
  }

  List<?> entries(DynamicMessage message, boolean guide) {
    return (List<?>)
        message.getField(
            message
                .getDescriptorForType()
                .findFieldByName(guide ? "tutorial_guide_list" : "tutorial_list"));
  }

  int status(DynamicMessage message, boolean guide) {
    var field =
        message.getDescriptorForType().findFieldByName(guide ? "tutorial_guide" : "tutorial");
    var item = (DynamicMessage) message.getField(field);
    return ((Descriptors.EnumValueDescriptor)
            item.getField(item.getDescriptorForType().findFieldByName("status")))
        .getNumber();
  }

  void installMissionBoundTutorial() {
    GameData.getTutorialExcelMap()
        .add(
            new Gson()
                .fromJson(
                    """
{"TutorialID":4006,"RestoreType":"AutoFinish",
 "FinishTriggerParams":[{"TriggerType":"FinishMainMission","TriggerParam":"1000302"}]}
""",
                    TutorialExcel.class));
  }

  void finishTeachingMainMission(Session session) {
    var main = new emu.lunarcore.game.quest.QuestState(1000302);
    main.advance(1, 1, 1792939357L);
    main.reserveReward();
    main.closeReward();
    session.actor.getGameplay().getMainMissions().put(1000302, main);
  }

  @Test
  void lateUnlockCannotRestartTeachingWhoseMainMissionHasAlreadyEnded() throws Exception {
    installMissionBoundTutorial();
    var session = new Session();
    finishTeachingMainMission(session);
    var response = call(session, "UnlockTutorial", 4006, 0);
    assertEquals(0, ret(response));
    assertEquals(
        2, status(response, false), "A stale unlock must not start a control-locking graph");
    assertEquals(Map.of(4006, 2), session.actor.getGameplay().getTutorials().snapshot(false));
  }

  @Test
  void reconnectRetiresOnlyPreviouslyStartedTeachingPastItsOriginalEndCondition() throws Exception {
    installMissionBoundTutorial();
    var session = new Session();
    assertEquals(1, status(call(session, "UnlockTutorial", 4006, 0), false));
    assertEquals(1, status(call(session, "UnlockTutorial", 1001, 0), false));
    finishTeachingMainMission(session);
    session.sent.clear();
    session.responses.clear();
    var response = call(session, "GetTutorial", 0, 0);
    assertEquals(0, ret(response));
    assertEquals(List.of("GetTutorialScRsp", "FinishTutorialScRsp"), session.sent);
    assertEquals(
        2,
        entries(session.responses.getFirst(), false).stream()
            .map(DynamicMessage.class::cast)
            .filter(
                row -> (int) row.getField(row.getDescriptorForType().findFieldByName("id")) == 4006)
            .mapToInt(
                row ->
                    ((Descriptors.EnumValueDescriptor)
                            row.getField(row.getDescriptorForType().findFieldByName("status")))
                        .getNumber())
            .findFirst()
            .orElseThrow());
    assertEquals(
        Map.of(1001, 1, 4006, 2),
        session.actor.getGameplay().getTutorials().snapshot(false),
        "The completed story must retire its stale teaching without skipping unrelated teaching");
    var restored =
        new Gson()
            .fromJson(
                new Gson().toJson(session.actor.getGameplay()),
                org.hkrpg.server.gameplay.GameplayState.class);
    assertEquals(Map.of(1001, 1, 4006, 2), restored.getTutorials().snapshot(false));
  }

  @Test
  void originalClientCanAcknowledgeMissionExpiredTeachingBeforeUnlock() throws Exception {
    installMissionBoundTutorial();
    var session = new Session();
    finishTeachingMainMission(session);
    var response = call(session, "FinishTutorial", 4006, 0);
    assertEquals(
        0, ret(response), "The original client checks finish triggers before unlock triggers");
    assertEquals(2, status(response, false));
  }

  @Test
  void activeSessionReceivesOneNativeFinishAfterDurableRetirement() throws Exception {
    installMissionBoundTutorial();
    var session = new Session();
    session.actor.setSession(session);
    assertEquals(1, status(call(session, "UnlockTutorial", 4006, 0), false));
    finishTeachingMainMission(session);
    session.sent.clear();
    int saves = session.actor.saves;
    int credits = session.actor.getScoin();
    int jade = session.actor.getHcoin();
    Tutorial46.refresh(session.actor);
    assertEquals(List.of("FinishTutorialScRsp"), session.sent);
    assertEquals(2, status(session.response, false));
    assertEquals(saves + 1, session.actor.saves);
    Tutorial46.refresh(session.actor);
    assertEquals(1, session.sent.size(), "Do not repeat completion notifications every tick");
    assertEquals(saves + 1, session.actor.saves);
    assertEquals(credits, session.actor.getScoin());
    assertEquals(jade, session.actor.getHcoin());
  }

  @Test
  void retirementRollbackDoesNotPublishFinishAndCanBeRetried() throws Exception {
    installMissionBoundTutorial();
    var session = new Session();
    session.actor.setSession(session);
    call(session, "UnlockTutorial", 4006, 0);
    finishTeachingMainMission(session);
    session.sent.clear();
    session.actor.failSave = true;
    assertThrows(IllegalStateException.class, () -> Tutorial46.refresh(session.actor));
    assertEquals(Map.of(4006, 1), session.actor.getGameplay().getTutorials().snapshot(false));
    assertTrue(session.sent.isEmpty(), "Never release the client using unsaved completion");
    session.actor.failSave = false;
    Tutorial46.refresh(session.actor);
    assertEquals(Map.of(4006, 2), session.actor.getGameplay().getTutorials().snapshot(false));
    assertEquals(List.of("FinishTutorialScRsp"), session.sent);
  }

  @Test
  void missingCancelledOrDamagedMissionRecordsCannotExpireTeaching() throws Exception {
    installMissionBoundTutorial();
    for (String record :
        List.of(
            "{\"questId\":1000302,\"progress\":0,\"phase\":\"DOING\",\"finishTime\":0}",
            "{\"questId\":1000303,\"progress\":1,\"phase\":\"CLOSED\",\"finishTime\":10}",
            "{\"questId\":1000302,\"progress\":0,\"phase\":\"CLOSED\",\"finishTime\":10}",
            "{\"questId\":1000302,\"progress\":1,\"phase\":\"CLOSED\",\"finishTime\":0}")) {
      var session = new Session();
      session
          .actor
          .getGameplay()
          .getMainMissions()
          .put(1000302, new Gson().fromJson(record, emu.lunarcore.game.quest.QuestState.class));
      assertEquals(1, status(call(session, "UnlockTutorial", 4006, 0), false));
      assertTrue(
          session
              .actor
              .getGameplay()
              .getTutorials()
              .expireFinishedMissions(session.actor)
              .isEmpty());
    }
    var cancelled = new Session();
    finishTeachingMainMission(cancelled);
    cancelled.actor.getGameplay().getCancelledMissions().add(1000302);
    assertEquals(1, status(call(cancelled, "UnlockTutorial", 4006, 0), false));
    assertTrue(
        cancelled
            .actor
            .getGameplay()
            .getTutorials()
            .expireFinishedMissions(cancelled.actor)
            .isEmpty());
  }

  @Test
  void completedMissionDoesNotPopulateUnstartedCatalogAndUnknownFinishRulesStayPending()
      throws Exception {
    installMissionBoundTutorial();
    var session = new Session();
    finishTeachingMainMission(session);
    assertTrue(entries(call(session, "GetTutorial", 0, 0), false).isEmpty());
    assertEquals(0, session.actor.saves);
    for (String triggers :
        List.of(
            "null",
            "[]",
            "[null]",
            "[{\"TriggerType\":\"EnterBattle\",\"TriggerParam\":\"1000302\"}]",
            "[{\"TriggerType\":\"FinishMainMission\",\"TriggerParam\":\"2147483648\"}]",
            "[{\"TriggerType\":\"FinishMainMission\",\"TriggerParam\":\"1000302\"},{}]")) {
      GameData.getTutorialExcelMap()
          .add(
              new Gson()
                  .fromJson(
                      "{\"TutorialID\":4006,\"FinishTriggerParams\":" + triggers + "}",
                      TutorialExcel.class));
      assertEquals(1, status(call(session, "UnlockTutorial", 4006, 0), false));
      assertTrue(
          session
              .actor
              .getGameplay()
              .getTutorials()
              .expireFinishedMissions(session.actor)
              .isEmpty());
    }
  }

  @Test
  void nativeUnlockFinishAndQueriesRetainOnlyRealPlayerAcknowledgements() throws Exception {
    var session = new Session();
    assertTrue(entries(call(session, "GetTutorial", 0, 0), false).isEmpty());
    assertTrue(entries(call(session, "GetTutorialGuide", 0, 0), true).isEmpty());
    assertEquals(
        Retcode.TUTORIAL_NOT_UNLOCK.getVal(), ret(call(session, "FinishTutorial", 1001, 0)));
    assertEquals(1, status(call(session, "UnlockTutorial", 1001, 0), false));
    assertEquals(2, status(call(session, "FinishTutorial", 1001, 0), false));
    int saves = session.actor.saves;
    assertEquals(2, status(call(session, "UnlockTutorial", 1001, 0), false));
    assertEquals(2, status(call(session, "FinishTutorial", 1001, 0), false));
    assertEquals(saves, session.actor.saves);
    assertEquals(1, status(call(session, "UnlockTutorialGuide", 1101, 0), true));
    assertEquals(2, status(call(session, "FinishTutorialGuide", 1101, 0), true));
    assertEquals(1, entries(call(session, "GetTutorialGuide", 0, 0), true).size());
    assertEquals(1, entries(call(session, "GetTutorial", 0, 0), false).size());
    var state =
        new Gson()
            .fromJson(
                new Gson().toJson(session.actor.getGameplay()),
                org.hkrpg.server.gameplay.GameplayState.class);
    assertEquals(Map.of(1001, 2), state.getTutorials().snapshot(false));
    assertEquals(Map.of(1101, 2), state.getTutorials().snapshot(true));
  }

  @Test
  void invalidIdsUnsupportedSubguidesAndMalformedWireDoNotAdvanceProgress() throws Exception {
    var session = new Session();
    assertEquals(
        Retcode.TUTORIAL_TUTORIAL_NOT_FOUND.getVal(),
        ret(call(session, "UnlockTutorial", 999999, 0)));
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), ret(call(session, "UnlockTutorialGuide", 1101, 1)));
    for (byte[] body :
        List.of(
            new byte[] {96, 1, 96, 2},
            new byte[] {8, 1},
            new byte[] {96, -1},
            new byte[] {96, -23, -121, -128, -128, 16})) {
      assertTrue(VersionedPacketHandler.handle(session, 1660, body));
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), ret(session.response));
    }
    assertEquals(0, session.actor.saves);
    assertTrue(session.actor.getGameplay().getTutorials().snapshot(false).isEmpty());
    assertTrue(session.actor.getGameplay().getTutorials().snapshot(true).isEmpty());
  }

  @Test
  void failedPersistenceRollsBackAndLoginStateCannotMutateTutorials() throws Exception {
    var session = new Session();
    session.actor.failSave = true;
    assertThrows(IllegalStateException.class, () -> call(session, "UnlockTutorial", 1001, 0));
    assertTrue(session.actor.getGameplay().getTutorials().snapshot(false).isEmpty());
    session.actor.failSave = false;
    session.setState(SessionState.WAITING_FOR_LOGIN);
    call(session, "UnlockTutorial", 1001, 0);
    assertEquals(0, session.actor.saves);
  }

  @Test
  void originalGuideRewardIsGrantedOnceAndCompletionSurvivesRepeatedAcknowledgements()
      throws Exception {
    var oldReward = GameData.getRewardExcelMap().get(201);
    var oldItem = GameData.getItemExcelMap().get(1);
    try (var input = getClass().getResourceAsStream("/fixtures/opening-player-4.6.json")) {
      var records =
          com.google.gson.JsonParser.parseString(
                  new String(
                      Objects.requireNonNull(input).readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonObject()
              .getAsJsonObject("records");
      var reward =
          new Gson()
              .fromJson(
                  records.getAsJsonArray("ExcelOutput/RewardData.json").get(0), RewardExcel.class);
      reward.onLoad();
      GameData.getRewardExcelMap().add(reward);
      GameData.getItemExcelMap()
          .add(
              emu.lunarcore.data.ResourceLoader.getGson()
                  .fromJson(
                      records.getAsJsonArray("ExcelOutput/ItemConfig.json").get(0),
                      ItemExcel.class));
      GameData.getTutorialGuideExcelMap()
          .add(
              new Gson()
                  .fromJson(
                      records.getAsJsonArray("ExcelOutput/TutorialGuideGroup.json").get(0),
                      TutorialGuideExcel.class));
      var session = new Session();
      int before = session.actor.getHcoin();
      assertEquals(0, ret(call(session, "FinishTutorialGuide", 1101, 0)));
      assertEquals(before + 1, session.actor.getHcoin());
      for (int i = 0; i < 3; i++)
        assertEquals(2, status(call(session, "FinishTutorialGuide", 1101, 0), true));
      assertEquals(before + 1, session.actor.getHcoin());
      assertTrue(
          new org.hkrpg.server.gameplay.RewardLedger(session.actor).granted("tutorial-guide:1101"));
    } finally {
      GameData.getRewardExcelMap().keySet().remove(201);
      GameData.getItemExcelMap().keySet().remove(1);
      if (oldReward != null) GameData.getRewardExcelMap().add(oldReward);
      if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
    }
  }
}
