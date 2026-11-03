package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.FinishWayExcel;
import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.server.packet.recv.HandlerGetDailyActiveInfoCsReq;
import io.netty.buffer.Unpooled;
import java.time.*;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real GameSession framing must deliver changes without a compensating client query. */
class DailyActiveNotify46Test {
  static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
  static final int AUTO = 2100132, PROPS = 2100144, STAMINA = 2100139;
  final DailyPointRewardTest source = new DailyPointRewardTest();
  final Map<Integer, QuestExcel> oldQuests = new HashMap<>();
  final Map<Integer, FinishWayExcel> oldRules = new HashMap<>();
  Actor actor;
  Session session;

  static class Actor extends Player {
    boolean online, failSave;
    String durable;

    @Override public boolean isOnline() { return online && getSession() != null; }
    @Override public void save() {
      if (failSave) throw new IllegalStateException("Rejected fixture save");
      durable = emu.lunarcore.data.ResourceLoader.getGson().toJson(getGameplay());
    }
  }

  record Sent(PacketCodec.Frame frame, String durable) {}

  class Session extends GameSession {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    final List<Sent> sent = new ArrayList<>();
    final Actor owner;
    Session(Actor value) {
      super(null);
      owner = value;
      value.setSession(this);
      setState(SessionState.ACTIVE);
    }
    @Override public Player getPlayer() { return owner; }
    @Override public ProtocolBridge getProtocol() { return bridge; }
    @Override protected void writeTransport(byte[] bytes) {
      var input = Unpooled.wrappedBuffer(bytes);
      try {
        while (input.isReadable()) sent.add(new Sent(PacketCodec.decodeNext(input), actor.durable));
      } finally { input.release(); }
    }
  }

  @BeforeEach void setup() throws Exception {
    source.setup();
    try (var input = getClass().getResourceAsStream("/fixtures/daily-active-notify-4.6.json")) {
      var extra = com.google.gson.JsonParser.parseString(new String(
          Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      source.fixture.addAll(extra.getAsJsonObject().getAsJsonArray("sourceRows"));
    }
    for (int id : List.of(PROPS, STAMINA)) {
      oldQuests.put(id, GameData.getQuestExcelMap().get(id));
      oldRules.put(id, GameData.getFinishWayExcelMap().get(id));
      GameData.getQuestExcelMap().add(source.gson.fromJson(source.row("quest", id), QuestExcel.class));
      GameData.getFinishWayExcelMap().add(source.gson.fromJson(source.row("rule", id), FinishWayExcel.class));
      GameplayDefinitions.dailyPool.add(
          source.gson.fromJson(source.row("pool", id), GameplayDefinitions.DailyPool.class));
    }
    actor = new Actor();
    actor.getDailyManager().setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    actor.getDailyManager().ensureDay();
    session = new Session(actor);
    actor.online = true;
  }

  @AfterEach void restore() {
    for (int id : List.of(PROPS, STAMINA)) {
      GameData.getQuestExcelMap().keySet().remove(id);
      GameData.getFinishWayExcelMap().keySet().remove(id);
      if (oldQuests.get(id) != null) GameData.getQuestExcelMap().add(oldQuests.get(id));
      if (oldRules.get(id) != null) GameData.getFinishWayExcelMap().add(oldRules.get(id));
    }
    source.restore();
  }

  List<Sent> frames(String name) {
    return session.sent.stream()
        .filter(sent -> sent.frame().cmdId() == session.bridge.clientCommand(name)).toList();
  }

  DynamicMessage body(String name, Sent sent) {
    return assertDoesNotThrow(() -> session.bridge.clientSchema().parse(name, sent.frame().body()));
  }

  int number(DynamicMessage message, String field) {
    return (int) message.getField(message.getDescriptorForType().findFieldByName(field));
  }

  void assertPointNotification(int expected) {
    var notifications = frames("DailyActiveInfoNotify");
    assertEquals(1, notifications.size(), "The real send path must deliver native command 3360");
    var sent = notifications.getFirst();
    var message = body("DailyActiveInfoNotify", sent);
    assertEquals(expected, number(message, "daily_active_point"));
    // The pinned native reader consumes tag 0x78 into +0x28, then HandbookModule.ActivePoint.
    var raw = assertDoesNotThrow(() -> com.google.protobuf.UnknownFieldSet.parseFrom(sent.frame().body()));
    assertEquals(expected == 0 ? List.of() : List.of((long) expected),
        raw.getField(15).getVarintList());
    assertEquals(0, sent.frame().header().length, "A push must not borrow the request identity");
    assertEquals(expected, source.gson.fromJson(sent.durable(), GameplayState.class).getDailyPoints());
  }

  DynamicMessage quest(int id) {
    var updates = frames("GetQuestDataScRsp");
    assertFalse(updates.isEmpty(), "Changed task progress must reach the client without a query");
    var message = body("GetQuestDataScRsp", updates.getLast());
    @SuppressWarnings("unchecked")
    var quests = (List<DynamicMessage>) message.getField(
        message.getDescriptorForType().findFieldByName("quest_list"));
    return quests.stream().filter(row -> number(row, "id") == id).findFirst().orElseThrow();
  }

  @Test void actualRewardRequestPushesSavedHundredBeforeTheCorrelatedReply() throws Exception {
    var type = session.bridge.clientSchema().message("TakeQuestRewardCsReq");
    var request = DynamicMessage.newBuilder(type)
        .addRepeatedField(type.findFieldByName("ODJIJFJCHHB"), AUTO).build().toByteArray();
    byte[] identity = {8, 37};
    try (var scope = session.getReplyCorrelation().enter(
        session.bridge, session.bridge.clientCommand("TakeQuestRewardCsReq"), identity)) {
      assertTrue(VersionedPacketHandler.handle(session,
          session.bridge.clientCommand("TakeQuestRewardCsReq"), request));
    }
    assertPointNotification(100);
    var reply = frames("TakeQuestRewardScRsp").getFirst();
    assertArrayEquals(identity, reply.frame().header());
    assertEquals(0, number(body("TakeQuestRewardScRsp", reply), "retcode"));
    assertTrue(session.sent.indexOf(frames("DailyActiveInfoNotify").getFirst()) < session.sent.indexOf(reply));
    assertEquals(3, ((com.google.protobuf.Descriptors.EnumValueDescriptor)
        quest(AUTO).getField(quest(AUTO).getDescriptorForType().findFieldByName("status"))).getNumber());
    session.sent.clear();
    assertTrue(VersionedPacketHandler.handle(session,
        session.bridge.clientCommand("TakeQuestRewardCsReq"), request));
    assertTrue(frames("DailyActiveInfoNotify").isEmpty());
    assertEquals(100, actor.getGameplay().getDailyPoints());
  }

  @Test void acceptedDestructionsPushEachProgressStepAndTheFinishedObjective() throws Exception {
    for (int progress = 1; progress <= 3; progress++) {
      session.sent.clear();
      actor.getDailyManager().onPropDestruct();
      assertPointNotification(0);
      assertEquals(progress, number(quest(PROPS), "progress"));
      assertEquals(progress, source.gson.fromJson(session.sent.getLast().durable(), GameplayState.class)
          .getDailyQuests().get(PROPS).getProgress());
    }
    session.sent.clear();
    assertEquals(Retcode.SUCC, actor.getDailyManager().claimQuest(PROPS).retcode());
    assertPointNotification(200);
    session.sent.clear();
    actor.getDailyManager().onPropDestruct();
    assertTrue(session.sent.isEmpty(), "A completed objective must not be advanced or rewarded again");
  }

  @Test void actualStaminaDebitPublishesProgressAndItsClaimedPoints() {
    actor.getDailyManager().onStaminaConsumed(120);
    assertPointNotification(0);
    assertEquals(120, number(quest(STAMINA), "progress"));
    session.sent.clear();
    assertEquals(Retcode.SUCC, actor.getDailyManager().claimQuest(STAMINA).retcode());
    assertPointNotification(200);
  }

  @Test void nextResetPushesZeroAndFreshObjectives() {
    assertEquals(Retcode.SUCC, actor.getDailyManager().claimQuest(AUTO).retcode());
    var previous = actor.getGameplay().getDailyQuests().get(AUTO);
    session.sent.clear();
    actor.getDailyManager().setClock(Clock.fixed(NOW.plusSeconds(86400), ZoneOffset.UTC));
    actor.getDailyManager().ensureDay();
    assertPointNotification(0);
    assertNotSame(previous, actor.getGameplay().getDailyQuests().get(AUTO));
    assertEquals(QuestState.Phase.FINISHED, actor.getGameplay().getDailyQuests().get(AUTO).getPhase());
    assertEquals(1, number(quest(AUTO), "progress"));
  }

  @Test void restoredBalanceIsReturnedByTheRealQueryWithoutGrantingAgain() throws Exception {
    assertEquals(Retcode.SUCC, actor.getDailyManager().claimQuest(AUTO).retcode());
    var saved = actor.durable;
    actor = new Actor();
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(actor, source.gson.fromJson(saved, GameplayState.class));
    actor.durable = saved;
    actor.getDailyManager().setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    session = new Session(actor);
    actor.online = true;
    new HandlerGetDailyActiveInfoCsReq().handle(session, new byte[0]);
    var reply = frames("GetDailyActiveInfoScRsp").getFirst();
    assertEquals(100, number(body("GetDailyActiveInfoScRsp", reply), "daily_active_point"));
    assertEquals(List.of(100L), com.google.protobuf.UnknownFieldSet.parseFrom(reply.frame().body())
        .getField(4).getVarintList());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getDailyManager().claimQuest(AUTO).retcode());
    assertEquals(saved, actor.durable);
  }

  @Test void rejectedSaveDoesNotPublishUncommittedRewardOrProgress() {
    var saved = actor.durable;
    actor.failSave = true;
    assertThrows(IllegalStateException.class, () -> actor.getDailyManager().claimQuest(AUTO));
    assertTrue(session.sent.isEmpty());
    assertEquals(saved, actor.durable);
  }

  @ParameterizedTest
  @EnumSource(value = SessionState.class, names = {"WAITING_FOR_TOKEN", "INACTIVE"})
  void inactiveSessionDoesNotReceiveNativePushes(SessionState state) {
    session.setState(state);
    assertEquals(Retcode.SUCC, actor.getDailyManager().claimQuest(AUTO).retcode());
    assertTrue(session.sent.isEmpty());
  }
}
