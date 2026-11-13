package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The authored one-way console link in Rivet Town, using isolated player state. */
class RivetTownGate46Test {
  static final String FLOOR = "P20122_F20122001";
  static final String GROUP = "Config/LevelOutput/SharedRuntimeGroup/Groups_" + FLOOR
      + "/LevelGroup_" + FLOOR + "_G12.json";
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, PropExcel> oldProps = new HashMap<>();
  final Map<Integer, InteractExcel> oldInteractions = new HashMap<>();
  JsonObject records;
  FloorInfo floor, oldFloor;
  MazePlaneExcel plane;
  Actor player;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    final List<BasePacket> packets = new ArrayList<>();
    Scene scene;
    boolean failSave;
    int saves;

    Actor() { teams.getCurrentLineup().getAvatars().add(0); }
    public LineupManager getLineupManager() { return teams; }
    public GameAvatar getAvatarById(int id) { return null; }
    public Scene getScene() { return scene; }
    public int getPlaneId() { return 20122; }
    public int getFloorId() { return 20122001; }
    public int getEntryId() { return 2012201; }
    public void save() {
      if (failSave) throw new IllegalStateException("isolated failed save");
      saves++;
    }
    public void sendPacket(BasePacket packet) { packets.add(packet); }
  }

  @BeforeEach void prepare() throws Exception {
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/rivet-town-gate-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MazeProp.json")) {
      var row = raw.getAsJsonObject().deepCopy();
      if (row.has("PropName") && row.get("PropName").isJsonObject())
        row.addProperty("PropName", row.getAsJsonObject("PropName").get("Hash").getAsLong());
      var prop = gson.fromJson(row, PropExcel.class);
      prop.onLoad();
      oldProps.put(prop.getId(), GameData.getPropExcelMap().get(prop.getId()));
      GameData.getPropExcelMap().add(prop);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/InteractConfig.json")) {
      var row = gson.fromJson(raw, InteractExcel.class);
      oldInteractions.put(row.getId(), GameData.getInteractExcelMap().get(row.getId()));
      GameData.getInteractExcelMap().add(row);
    }
    plane = gson.fromJson(records.getAsJsonArray("ExcelOutput/MazePlane.json").get(0), MazePlaneExcel.class);
    freshFloor();
    player = new Actor();
    scene();
  }

  void freshFloor() {
    floor = gson.fromJson(records.get("Config/LevelOutput/RuntimeFloor/" + FLOOR + ".json"), FloorInfo.class);
    var group = gson.fromJson(records.get(GROUP), GroupInfo.class);
    group.setId(12);
    floor.getGroups().put(12, group);
    floor.onLoad();
    GameData.getFloorInfos().put(FLOOR, floor);
  }

  void scene() {
    player.scene = new Scene(player, plane, 20122001);
    player.scene.setEntryId(player.getEntryId());
  }

  void reload() throws Exception {
    var saved = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    player = new Actor();
    DailyMissionAcceptance46Test.set(player, "gameplay", saved);
    freshFloor();
    scene();
  }

  EntityProp prop(int instance) {
    return player.scene.getEntitiesByGroup(EntityProp.class, 12).stream()
        .filter(p -> p.getInstId() == instance).findFirst().orElseThrow();
  }

  ScenePropTransitions.Result open(int instance) {
    return ScenePropTransitions.interact(player, 1010, prop(instance).getEntityId());
  }

  @AfterEach void cleanup() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    oldProps.forEach((id, old) -> {
      GameData.getPropExcelMap().keySet().remove(id);
      if (old != null) GameData.getPropExcelMap().add(old);
    });
    oldInteractions.forEach((id, old) -> {
      GameData.getInteractExcelMap().keySet().remove(id);
      if (old != null) GameData.getInteractExcelMap().add(old);
    });
  }

  @Test void loaderPreservesTheAuthoredEmptyGateBackReference() {
    var raw = records.getAsJsonObject(GROUP).getAsJsonArray("PropList").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(p -> p.get("ID").getAsInt() == 300009)
        .findFirst().orElseThrow();
    assertEquals(raw.get("ValueSource"), gson.toJsonTree(prop(300009).getPropInfo().getValueSource()));
  }

  @Test void nativeRequestOpensOnlyTheAuthoredPairAndSurvivesReload() throws Exception {
    player.scene.toProto();
    player.packets.clear();
    var protocol = ProtocolBridge.forVersion("4.6.0");
    var replies = new ArrayList<DynamicMessage>();
    var session = new GameSession(null) {
      public Player getPlayer() { return player; }
      public ProtocolBridge getProtocol() { return protocol; }
      public void sendClientPacket(String name, byte[] body) {
        try {
          if (name.equals("InteractPropScRsp")) replies.add(protocol.clientSchema().parse(name, body));
        } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
      }
    };
    session.setState(SessionState.ACTIVE);
    var type = protocol.clientSchema().message("InteractPropCsReq");
    var request = DynamicMessage.newBuilder(type)
        .setField(type.findFieldByName("prop_entity_id"), prop(300010).getEntityId())
        .setField(type.findFieldByName("interact_id2"), 1010)
        .setField(type.findFieldByName("interact_id"), -1L).build();
    assertTrue(VersionedPacketHandler.handle(session, protocol.clientCommand("InteractPropCsReq"), request.toByteArray()));
    assertEquals(1, replies.size());
    var reply = replies.getFirst();
    assertEquals(0, reply.getField(reply.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(1, reply.getField(reply.getDescriptorForType().findFieldByName("prop_state")));
    assertEquals(PropState.Open, prop(300010).getState());
    assertEquals(PropState.Open, prop(300009).getState());
    assertEquals(PropState.Closed, prop(300003).getState());
    assertEquals(2, player.getGameplay().getScenePropStates().getRecords().size());
    assertEquals(2, player.packets.stream().filter(p -> p.getCmdId() == CmdId.SceneGroupRefreshScNotify).count());
    assertTrue(player.getGameplay().getSubMissions().isEmpty());
    reload();
    assertEquals(PropState.Open, prop(300010).getState());
    assertEquals(PropState.Open, prop(300009).getState());
    assertEquals(PropState.Closed, prop(300003).getState());
    assertNotEquals(Retcode.SUCC, open(300010).retcode());
    assertEquals(0, player.saves);
  }

  @Test void failedSaveLeavesBothObjectsUnchangedAndCanBeRetried() {
    player.scene.toProto();
    player.packets.clear();
    var before = gson.toJson(player.getGameplay());
    player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, open(300010).retcode());
    assertEquals(PropState.Closed, prop(300010).getState());
    assertEquals(PropState.Closed, prop(300009).getState());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertTrue(player.packets.isEmpty());
    player.failSave = false;
    assertEquals(Retcode.SUCC, open(300010).retcode());
    assertEquals(PropState.Open, prop(300009).getState());
  }

  @Test void directGateRequestCannotBypassTheSwitch() {
    assertNotEquals(Retcode.SUCC, open(300009).retcode());
    assertEquals(PropState.Closed, prop(300009).getState());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @ParameterizedTest @ValueSource(ints = {300009, 300010})
  void aSingleOldReceiptCannotOpenEitherHalf(int instance) throws Exception {
    var info = prop(instance).getPropInfo();
    player.getGameplay().scenePropStates(player.getGameplay().getScenePropStates().with(List.of(
        new ScenePropState.Snapshot(20122, 20122001, 0, 0, 12, instance, info.getPropID(),
            PropState.Open.getVal(), PropTimelines.source(info), ""))));
    reload();
    assertEquals(PropState.Closed, prop(300010).getState());
    assertEquals(PropState.Closed, prop(300009).getState());
    assertEquals(Retcode.SUCC, open(300010).retcode());
    assertEquals(PropState.Open, prop(300009).getState());
  }

  @Test void theOneWayExceptionDoesNotRetainOtherFloorSources() {
    var gate = prop(300009).getPropInfo();
    assertTrue(StoryDoors.retainsSource(floor, gate));
    assertFalse(StoryDoors.retainsSource(new FloorInfo(20123001), gate));
    assertTrue(StoryDoors.retainsSource(new FloorInfo(20123001), prop(300003).getPropInfo()));
  }

  @Test void ambiguousNewSourceDoesNotRestoreAnEarlierPair() {
    assertEquals(Retcode.SUCC, open(300010).retcode());
    prop(300004).getPropInfo().getValueSource().getValues().getFirst()
        .addProperty("Value", "12,300009");
    scene();
    assertEquals(PropState.Closed, prop(300010).getState());
    assertEquals(PropState.Closed, prop(300009).getState());
    assertNotEquals(Retcode.SUCC, open(300010).retcode());
  }

  @Test void repeatedLinkRejectionsAreCountedAndDifferentReasonsRemainVisible() {
    GameDiagnostics.flush(true);
    var logger = (Logger) LunarCore.getLogger();
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    try {
      var console = prop(300010).getPropInfo();
      var original = console.getValueSource();
      console.setValueSource(null);
      assertNotEquals(Retcode.SUCC, open(300010).retcode());
      assertNotEquals(Retcode.SUCC, open(300010).retcode());
      console.setValueSource(original);
      player.scene.getEntities().remove(prop(300009).getEntityId());
      assertNotEquals(Retcode.SUCC, open(300010).retcode());
      GameDiagnostics.flush(true);
      var messages = capture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertEquals(1, messages.stream().filter(m -> m.contains("reason=missing-binding")).count());
      assertEquals(1, messages.stream().filter(m -> m.contains("reason=invalid-source-or-binding")).count());
      assertTrue(messages.stream().anyMatch(m -> m.contains("floor=20122001")
          && m.contains("group=12") && m.contains("instance=300010")));
      assertTrue(messages.stream().anyMatch(m -> m.contains("Diagnostic repeats:")
          && m.contains("missing-binding") && m.contains("repeated=1")));
    } finally {
      logger.detachAppender(capture);
      capture.stop();
      GameDiagnostics.flush(true);
    }
  }

  @ParameterizedTest @ValueSource(strings = {"missing-target", "wrong-link", "duplicate-owner", "changed-gate-source"})
  void alteredOrAmbiguousSourceCannotOpenEitherObject(String variant) {
    var source = prop(300010).getPropInfo();
    var gate = prop(300009).getPropInfo();
    // On the old loader this binding was discarded; restore it so this checks the validator too.
    var raw = records.getAsJsonObject(GROUP).getAsJsonArray("PropList").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(p -> p.get("ID").getAsInt() == 300009)
        .findFirst().orElseThrow();
    gate.setValueSource(gson.fromJson(raw.get("ValueSource"), PropInfo.PropValueSource.class));
    switch (variant) {
      case "missing-target" -> player.scene.getEntities().remove(prop(300009).getEntityId());
      case "wrong-link" -> source.getValueSource().getValues().getFirst().addProperty("Value", "12,300003");
      case "duplicate-owner" -> prop(300004).getPropInfo().getValueSource().getValues()
          .getFirst().addProperty("Value", "12,300009");
      case "changed-gate-source" -> gate.getValueSource().getValues().getFirst().addProperty("Value", "12,300010");
    }
    assertNotEquals(Retcode.SUCC, open(300010).retcode());
    assertEquals(PropState.Closed, prop(300010).getState());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }
}
