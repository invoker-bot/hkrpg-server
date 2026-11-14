package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonParser;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.data.excel.InteractExcel;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.hkrpg.server.protocol.ProtocolException;
import org.hkrpg.server.GameDiagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The authored mirror performance sends 1010 through an actual 4.6 session and wire bridge. */
class TrainMirrorInteraction46Test {
  private static final String FLOOR = "P10000_F10000000";
  private static final String GROUP = "Config/LevelOutput/SharedRuntimeGroup/Groups_"
      + FLOOR + "/LevelGroup_" + FLOOR + "_G47.json";
  private static final String MISSION = "Config/Level/Mission/4010105/MissionInfo_4010105.partial.json";
  private static final String SAVED = "prop-state:10000:10000000:47:300001";
  private final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  private final Map<Integer, InteractExcel> oldInteractions = new HashMap<>();
  private final Actor player = new Actor();
  private final Session session = new Session("4.6.0");
  private FloorInfo oldFloor;
  private PropExcel oldProp;
  private MazePlaneExcel plane;

  private static final class Actor extends Player {
    final LineupManager lineups = new LineupManager(this);
    Scene scene;
    int saves;
    boolean failSave;
    Actor() { lineups.getCurrentLineup().getAvatars().add(0); }
    @Override public LineupManager getLineupManager() { return lineups; }
    @Override public Scene getScene() { return scene; }
    @Override public int getPlaneId() { return 10000; }
    @Override public int getFloorId() { return 10000000; }
    @Override public int getEntryId() { return 1000001; }
    @Override public boolean isOnline() { return true; }
    @Override public void save() {
      if (failSave) throw new IllegalStateException("private-test-detail");
      saves++;
    }
  }

  private final class Session extends GameSession {
    final ProtocolBridge bridge;
    final List<PacketCodec.Frame> sent = new ArrayList<>();
    Session(String version) { super(null); bridge = ProtocolBridge.forVersion(version); setState(SessionState.ACTIVE); }
    @Override public Player getPlayer() { return player; }
    @Override public ProtocolBridge getProtocol() { return bridge; }
    @Override public void send(BasePacket packet) { if (packet.getCmdId() > 0) send(packet.build()); }
    @Override protected void writeTransport(byte[] bytes) {
      var input = Unpooled.wrappedBuffer(bytes);
      try { while (input.isReadable()) sent.add(PacketCodec.decodeNext(input)); }
      finally { input.release(); }
    }
    void request(int entity, int config) throws Exception {
      var bytes = new ByteArrayOutputStream();
      var wire = CodedOutputStream.newInstance(bytes);
      wire.writeUInt32(4, entity);
      wire.writeUInt32(5, config);
      wire.flush();
      var input = Unpooled.wrappedBuffer(PacketCodec.encode(1405, new byte[] {8, 41}, bytes.toByteArray()));
      try { onMessage(input); } finally { input.release(); }
    }
  }

  @BeforeEach void prepare() throws Exception {
    var gson = ResourceLoader.getGson();
    com.google.gson.JsonObject records;
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/train-mirror-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    var propRow = records.getAsJsonArray("ExcelOutput/MazeProp.json").get(0).getAsJsonObject().deepCopy();
    propRow.addProperty("PropName", propRow.getAsJsonObject("PropName").get("Hash").getAsLong());
    var prop = gson.fromJson(propRow, PropExcel.class);
    prop.onLoad();
    oldProp = GameData.getPropExcelMap().get(prop.getId());
    GameData.getPropExcelMap().add(prop);
    for (var row : records.getAsJsonArray("ExcelOutput/InteractConfig.json")) {
      var interaction = gson.fromJson(row, InteractExcel.class);
      oldInteractions.put(interaction.getId(), GameData.getInteractExcelMap().get(interaction.getId()));
      GameData.getInteractExcelMap().add(interaction);
    }
    plane = gson.fromJson(records.getAsJsonArray("ExcelOutput/MazePlane.json").get(0), MazePlaneExcel.class);
    var group = gson.fromJson(records.get(GROUP), GroupInfo.class);
    group.setId(47);
    var floor = new FloorInfo(10000000);
    floor.getGroups().put(47, group);
    floor.onLoad();
    oldFloor = GameData.getFloorInfos().put(FLOOR, floor);
    for (var row : records.getAsJsonObject(MISSION).getAsJsonArray("SubMissionList")) {
      var sub = gson.fromJson(row, MissionInfo.SubMissionInfo.class);
      oldSubs.put(sub.getId(), GameplayDefinitions.subs.put(sub.getId(), sub));
    }
    player.setSession(session);
    player.getGameplay().getMainMissions().put(4010105, new QuestState(4010105));
    for (int id : new int[] {401010503, 401010505}) {
      var completed = new QuestState(id);
      completed.advance(1, 1, 1);
      player.getGameplay().getSubMissions().put(id, completed);
    }
    player.getGameplay().getSubMissions().put(401010506, new QuestState(401010506));
  }

  @AfterEach void restore() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    if (oldProp == null) GameData.getPropExcelMap().keySet().remove(2004);
    else GameData.getPropExcelMap().add(oldProp);
    oldInteractions.forEach((id, old) -> {
      if (old == null) GameData.getInteractExcelMap().keySet().remove(id);
      else GameData.getInteractExcelMap().add(old);
    });
    oldSubs.forEach((id, old) -> {
      if (old == null) GameplayDefinitions.subs.remove(id);
      else GameplayDefinitions.subs.put(id, old);
    });
  }

  private EntityProp loadMirror() {
    player.scene = new Scene(player, plane, player.getFloorId());
    player.scene.setEntryId(player.getEntryId());
    player.scene.toProto();
    return player.scene.getEntitiesByGroup(EntityProp.class, 47).stream()
        .filter(prop -> prop.getInstId() == 300001).findFirst().orElseThrow();
  }

  private void assertReply(int expected, int entity) throws Exception {
    var reply = session.sent.stream().filter(frame -> frame.cmdId() == 1475).findFirst().orElseThrow();
    assertArrayEquals(new byte[] {8, 41}, reply.header());
    var message = session.bridge.clientSchema().parse("InteractPropScRsp", reply.body());
    assertEquals(expected, message.getField(message.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(entity, message.getField(message.getDescriptorForType().findFieldByName("prop_entity_id")));
  }

  @Test void authoredMirrorRequestKeepsTheConnectionAndRepliesWithoutCompletingTheStory() throws Exception {
    var mirror = loadMirror();
    assertEquals(PropState.Closed, mirror.getState());
    session.request(mirror.getEntityId(), 1010);
    assertEquals(SessionState.ACTIVE, session.getState());
    assertReply(0, mirror.getEntityId());
    assertEquals(PropState.Open, mirror.getState());
    assertEquals(PropState.Open.getVal(), player.getGameplay().getStoryValues().get(SAVED));
    assertEquals(1, player.saves);
    assertEquals(QuestState.Phase.DOING, player.getGameplay().getSubMissions().get(401010506).getPhase());
    assertFalse(player.getGameplay().getSubMissions().containsKey(401010507));
    assertTrue(session.sent.stream().anyMatch(frame -> frame.cmdId() == 1433));
  }

  @Test void anInterruptedPerformanceCanRepeatItsSavedOpenWithoutAdvancingOrResaving() throws Exception {
    player.getGameplay().getStoryValues().put(SAVED, PropState.Open.getVal());
    var mirror = loadMirror();
    assertEquals(PropState.Open, mirror.getState());
    session.request(mirror.getEntityId(), 1010);
    assertEquals(SessionState.ACTIVE, session.getState());
    assertReply(0, mirror.getEntityId());
    assertEquals(0, player.saves);
    assertEquals(QuestState.Phase.DOING, player.getGameplay().getSubMissions().get(401010506).getPhase());
    assertFalse(player.getGameplay().getSubMissions().containsKey(401010507));
  }

  @Test void anUnsavedOpenCannotMasqueradeAsAnInterruptedPerformance() throws Exception {
    var mirror = loadMirror();
    mirror.setState(PropState.Open, false);
    session.request(mirror.getEntityId(), 1010);
    assertReply(Retcode.UNSUPPORTED_PROP_STATE.getVal(), mirror.getEntityId());
    assertEquals(0, player.saves);
  }

  @Test void aSavedOpenDoesNotBypassTheRealMissionPrerequisites() throws Exception {
    player.getGameplay().getStoryValues().put(SAVED, PropState.Open.getVal());
    var mirror = loadMirror();
    player.getGameplay().getSubMissions().remove(401010505);
    session.request(mirror.getEntityId(), 1010);
    assertReply(Retcode.UNSUPPORTED_PROP_STATE.getVal(), mirror.getEntityId());
    assertEquals(0, player.saves);
    assertEquals(QuestState.Phase.DOING, player.getGameplay().getSubMissions().get(401010506).getPhase());
  }

  @Test void aChangedMissionDefinitionDoesNotAuthorizeTheReplay() throws Exception {
    player.getGameplay().getStoryValues().put(SAVED, PropState.Open.getVal());
    var mirror = loadMirror();
    var altered = ResourceLoader.getGson().fromJson("""
        {"ID":401010506,"MainMissionID":4010105,"LevelPlaneID":10000,
         "LevelFloorID":10000000,"FinishType":"Talk","ParamStr1":"other","Progress":1}
        """, MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(401010506, altered);
    session.request(mirror.getEntityId(), 1010);
    assertReply(Retcode.UNSUPPORTED_PROP_STATE.getVal(), mirror.getEntityId());
    assertEquals(0, player.saves);
  }

  @Test void onlyTheActualPerformanceReportAdvancesToTheChallenge() throws Exception {
    var mirror = loadMirror();
    session.request(mirror.getEntityId(), 1010);
    assertReply(0, mirror.getEntityId());
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        player.getMissionManager().finishTalk(401010506, "wrong"));
    assertEquals(Retcode.SUCC,
        player.getMissionManager().finishTalk(401010506, "Mission_401010506"));
    assertEquals(QuestState.Phase.FINISHED, player.getGameplay().getSubMissions().get(401010506).getPhase());
    assertEquals(QuestState.Phase.DOING, player.getGameplay().getSubMissions().get(401010507).getPhase());
    assertTrue(player.getGameplay().getRewards().isEmpty());
  }

  @Test void anotherOrdinaryTrainPropUsesTheSameNativeFallbackWithoutDisconnecting() throws Exception {
    var mirror = loadMirror();
    var other = new EntityProp(player.scene, mirror.getExcel(),
        player.scene.getResourceGroups().get(47),
        ResourceLoader.getGson().fromJson("{\"ID\":300002,\"PropID\":2004}", PropInfo.class));
    player.scene.addEntity(other);
    session.request(other.getEntityId(), 1010);
    assertEquals(SessionState.ACTIVE, session.getState());
    assertReply(0, other.getEntityId());
    assertEquals(PropState.Open, other.getState());
  }

  @Test void anUnmappedOutgoingCommandIsIdentifiedWithoutPacketContents() {
    var error = assertThrows(ProtocolException.class,
        () -> session.bridge.outbound(CmdId.GroupStateChangeScNotify, new byte[0]));
    assertEquals("unmapped-outgoing-command:GroupStateChangeScNotify", error.diagnosticReason());
  }

  @Test void unexpectedInteractionFailuresIdentifyThePropAndDeduplicateWithoutPrivateDetails() throws Exception {
    var mirror = loadMirror();
    var other = new EntityProp(player.scene, mirror.getExcel(),
        player.scene.getResourceGroups().get(47),
        ResourceLoader.getGson().fromJson("{\"ID\":300002,\"PropID\":2004}", PropInfo.class));
    player.scene.addEntity(other);
    player.failSave = true;
    GameDiagnostics.flush(true);
    var logger = (Logger) LunarCore.getLogger();
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    try {
      session.request(mirror.getEntityId(), 1010);
      mirror.setState(PropState.Closed, false);
      player.getGameplay().getStoryValues().remove(SAVED);
      session.request(mirror.getEntityId(), 1010);
      session.request(other.getEntityId(), 1010);
      GameDiagnostics.flush(true);
      var messages = capture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertEquals(2, messages.stream().filter(s -> s.contains("reason=interaction-exception-prop-47-")).count());
      assertTrue(messages.stream().anyMatch(s -> s.contains("reason=interaction-exception-prop-47-300001")));
      assertTrue(messages.stream().anyMatch(s -> s.contains("reason=interaction-exception-prop-47-300002")));
      assertTrue(messages.stream().anyMatch(s -> s.contains("Diagnostic repeats") && s.contains(" x1")));
      assertTrue(messages.stream().noneMatch(s -> s.contains("private-test-detail")));
    } finally {
      logger.detachAppender(capture);
      capture.stop();
    }
  }

  @Test void theLegacySessionStillGetsItsOriginalGroupNotification() throws Exception {
    var mirror = loadMirror();
    var legacy = new Session("4.2.0");
    var field = Player.class.getDeclaredField("session");
    field.setAccessible(true);
    field.set(player, legacy);
    assertSame(mirror, player.interactWithProp(1010, mirror.getEntityId()));
    assertTrue(legacy.sent.stream().anyMatch(frame -> frame.cmdId() == CmdId.GroupStateChangeScNotify));
    assertTrue(legacy.sent.stream().anyMatch(frame -> frame.cmdId() == CmdId.SceneGroupRefreshScNotify));
  }
}
