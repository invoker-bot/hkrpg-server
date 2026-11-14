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
import emu.lunarcore.game.quest.QuestState;
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

/** Two later story controllers and their original, distinct gate bindings. */
class FollowupStoryGates46Test {
  record Gate(int plane, int floor, int entry, int group, int console, int gate, int main, int sub) {
    String floorName() { return "P" + plane + "_F" + floor; }
    String groupPath() {
      return "Config/LevelOutput/SharedRuntimeGroup/Groups_" + floorName()
          + "/LevelGroup_" + floorName() + "_G" + group + ".json";
    }
  }
  static final Gate SVAROG = new Gate(20123, 20123001, 2012301, 8, 300005, 300004, 1010902, 101090232);
  static final Gate HILL = new Gate(20131, 20131001, 2013101, 6, 300004, 300013, 1011103, 101110301);
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, PropExcel> oldProps = new HashMap<>();
  final Map<Integer, InteractExcel> oldInteractions = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final Map<Integer, MissionInfo> oldMissions = new HashMap<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  JsonObject records;
  Actor player;
  Gate current;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    final List<BasePacket> packets = new ArrayList<>();
    final Gate gate;
    Scene scene;
    boolean failSave;
    Actor(Gate gate) { this.gate = gate; teams.getCurrentLineup().getAvatars().add(0); }
    public LineupManager getLineupManager() { return teams; }
    public GameAvatar getAvatarById(int id) { return null; }
    public Scene getScene() { return scene; }
    public int getPlaneId() { return gate.plane(); }
    public int getFloorId() { return gate.floor(); }
    public int getEntryId() { return gate.entry(); }
    public void save() { if (failSave) throw new IllegalStateException("isolated failed save"); }
    public void sendPacket(BasePacket packet) { packets.add(packet); }
  }

  @BeforeEach void prepare() throws Exception {
    oldSubs.putAll(GameplayDefinitions.subs);
    GameplayDefinitions.subs.clear();
    oldMissions.putAll(GameData.getMissionInfos());
    GameData.getMissionInfos().clear();
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    GameData.getMainMissionExcelMap().values().clear();
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/followup-story-gates-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MazeProp.json")) {
      var row = raw.getAsJsonObject().deepCopy();
      row.addProperty("PropName", row.getAsJsonObject("PropName").get("Hash").getAsLong());
      var prop = gson.fromJson(row, PropExcel.class); prop.onLoad();
      oldProps.put(prop.getId(), GameData.getPropExcelMap().get(prop.getId()));
      GameData.getPropExcelMap().add(prop);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/InteractConfig.json")) {
      var row = gson.fromJson(raw, InteractExcel.class);
      oldInteractions.put(row.getId(), GameData.getInteractExcelMap().get(row.getId()));
      GameData.getInteractExcelMap().add(row);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MainMission.json"))
      GameData.getMainMissionExcelMap().add(gson.fromJson(raw, MainMissionExcel.class));
    for (var gate : List.of(SVAROG, HILL)) {
      oldFloors.put(gate.floorName(), GameData.getFloorInfos().get(gate.floorName()));
      var prefix = "Config/Level/Mission/" + gate.main() + "/";
      var main = gson.fromJson(records.get(prefix + "MissionInfo_" + gate.main() + ".json"), MissionInfo.class);
      GameData.getMissionInfos().put(gate.main(), main);
      for (var raw : records.getAsJsonObject(prefix + "MissionInfo_" + gate.main() + ".partial.json")
          .getAsJsonArray("SubMissionList")) {
        var sub = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
        GameplayDefinitions.subs.put(sub.getId(), sub);
      }
      script(gate, records.get(prefix + "Mission_" + gate.sub() + ".json"));
      freshFloor(gate);
    }
  }

  void script(Gate gate, JsonElement source) {
    var task = gson.fromJson(source, MissionTaskInfo.class);
    task.retainSourceDefinition(source);
    GameplayDefinitions.subs.get(gate.sub()).setTask(task);
  }

  void freshFloor(Gate gate) {
    var floor = gson.fromJson(records.get("Config/LevelOutput/RuntimeFloor/" + gate.floorName() + ".json"), FloorInfo.class);
    var group = gson.fromJson(records.get(gate.groupPath()), GroupInfo.class);
    group.setId(gate.group()); floor.getGroups().put(gate.group(), group); floor.onLoad();
    GameData.getFloorInfos().put(gate.floorName(), floor);
  }

  Gate gate(int plane) { return plane == 20123 ? SVAROG : HILL; }
  void start(Gate gate, boolean accepted) {
    current = gate; player = new Actor(gate);
    if (accepted) {
      player.getGameplay().getMainMissions().put(gate.main(), new QuestState(gate.main()));
      player.getGameplay().getSubMissions().put(gate.sub(), new QuestState(gate.sub()));
      if (gate == SVAROG) {
        var previous = new QuestState(101090227); previous.advance(1, 1, 100);
        player.getGameplay().getSubMissions().put(101090227, previous);
      }
    }
    scene();
  }

  void scene() {
    var planeRow = records.getAsJsonArray("ExcelOutput/MazePlane.json").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(p -> p.get("PlaneID").getAsInt() == current.plane())
        .findFirst().orElseThrow();
    player.scene = new Scene(player, gson.fromJson(planeRow, MazePlaneExcel.class), current.floor());
    player.scene.setEntryId(current.entry());
  }

  void reload() throws Exception {
    var state = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    player = new Actor(current);
    DailyMissionAcceptance46Test.set(player, "gameplay", state);
    freshFloor(current); scene();
  }

  EntityProp prop(int id) {
    return player.scene.getEntitiesByGroup(EntityProp.class, current.group()).stream()
        .filter(p -> p.getInstId() == id).findFirst().orElseThrow();
  }
  ScenePropTransitions.Result open(int id) {
    return ScenePropTransitions.interact(player, 1010, prop(id).getEntityId());
  }

  @AfterEach void cleanup() {
    oldFloors.forEach((key, floor) -> {
      if (floor == null) GameData.getFloorInfos().remove(key); else GameData.getFloorInfos().put(key, floor);
    });
    oldProps.forEach((id, old) -> {
      GameData.getPropExcelMap().keySet().remove(id);
      if (old != null) GameData.getPropExcelMap().add(old);
    });
    oldInteractions.forEach((id, old) -> {
      GameData.getInteractExcelMap().keySet().remove(id);
      if (old != null) GameData.getInteractExcelMap().add(old);
    });
    GameplayDefinitions.subs.clear(); GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMissionInfos().clear(); GameData.getMissionInfos().putAll(oldMissions);
    GameData.getMainMissionExcelMap().values().clear(); oldMains.forEach(GameData.getMainMissionExcelMap()::add);
  }

  @ParameterizedTest @ValueSource(ints = {20123, 20131})
  void acceptedOriginalObjectiveEnablesAndOpensOnlyItsGate(int plane) throws Exception {
    var g = gate(plane); start(g, true);
    assertEquals(PropState.Locked, prop(g.console()).getPropInfo().getState());
    assertEquals(PropState.Closed, prop(g.console()).getState());
    assertEquals(PropState.Closed, prop(g.gate()).getState());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(g.sub()).getPhase());
    assertEquals(Retcode.SUCC, open(g.console()).retcode());
    assertEquals(PropState.Open, prop(g.console()).getState());
    assertEquals(PropState.Open, prop(g.gate()).getState());
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(g.sub()).getPhase());
    assertEquals(2, player.getGameplay().getScenePropStates().getRecords().size());
    reload();
    assertEquals(PropState.Open, prop(g.console()).getState());
    assertEquals(PropState.Open, prop(g.gate()).getState());
    assertNotEquals(Retcode.SUCC, open(g.console()).retcode());
  }

  @ParameterizedTest @ValueSource(ints = {20123, 20131})
  void absentOrCancelledObjectiveCannotUnlockOrOpenTheGate(int plane) {
    var g = gate(plane); start(g, false);
    assertEquals(PropState.Locked, prop(g.console()).getState());
    assertNotEquals(Retcode.SUCC, open(g.console()).retcode());
    start(g, true); player.getGameplay().getCancelledSubs().add(g.sub()); scene();
    assertEquals(PropState.Locked, prop(g.console()).getState());
    assertNotEquals(Retcode.SUCC, open(g.console()).retcode());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @ParameterizedTest @ValueSource(ints = {20123, 20131})
  void directGateRequestCannotBypassTheController(int plane) {
    var g = gate(plane); start(g, true);
    assertNotEquals(Retcode.SUCC, open(g.gate()).retcode());
    assertEquals(PropState.Closed, prop(g.gate()).getState());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @ParameterizedTest @ValueSource(ints = {20123, 20131})
  void failedSaveRollsBackThePairAndLeavesTheObjectiveDoing(int plane) {
    var g = gate(plane); start(g, true); player.scene.toProto(); player.packets.clear();
    var before = gson.toJson(player.getGameplay()); player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, open(g.console()).retcode());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(PropState.Closed, prop(g.console()).getState());
    assertEquals(PropState.Closed, prop(g.gate()).getState());
    assertTrue(player.packets.isEmpty());
    player.failSave = false;
    assertEquals(Retcode.SUCC, open(g.console()).retcode());
    assertEquals(PropState.Open, prop(g.gate()).getState());
  }

  @ParameterizedTest @ValueSource(ints = {20123, 20131})
  void changedAuthoredScriptDoesNotEnableTheConsole(int plane) {
    var g = gate(plane); start(g, true);
    var path = "Config/Level/Mission/" + g.main() + "/Mission_" + g.sub() + ".json";
    var changed = records.getAsJsonObject(path).deepCopy();
    changed.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList")
        .add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.TriggerBattle\"}"));
    script(g, changed); scene();
    assertEquals(PropState.Locked, prop(g.console()).getState());
    assertNotEquals(Retcode.SUCC, open(g.console()).retcode());
  }

  @ParameterizedTest @ValueSource(ints = {20123, 20131})
  void changedAcceptedMissionLeavesALocalizableDiagnostic(int plane) {
    var g = gate(plane); start(g, true); GameDiagnostics.flush(true);
    var logger = (Logger) LunarCore.getLogger();
    var capture = new ListAppender<ILoggingEvent>(); capture.start(); logger.addAppender(capture);
    try {
      var path = "Config/Level/Mission/" + g.main() + "/Mission_" + g.sub() + ".json";
      var changed = records.getAsJsonObject(path).deepCopy();
      changed.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList")
          .add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.TriggerBattle\"}"));
      script(g, changed); scene();
      assertTrue(capture.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(m ->
          m.contains("command=FollowupStoryGate") && m.contains("reason=mission-source-mismatch")
              && m.contains("floor=" + g.floor()) && m.contains("instance=" + g.console())));
    } finally {
      logger.detachAppender(capture); capture.stop(); GameDiagnostics.flush(true);
    }
  }

  @ParameterizedTest @ValueSource(ints = {20123, 20131})
  void nativeRequestRepliesOpenAndPublishesBothProps(int plane) throws Exception {
    var g = gate(plane); start(g, true); player.scene.toProto(); player.packets.clear();
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
        .setField(type.findFieldByName("prop_entity_id"), prop(g.console()).getEntityId())
        .setField(type.findFieldByName("interact_id2"), 1010)
        .setField(type.findFieldByName("interact_id"), -1L).build();
    assertTrue(VersionedPacketHandler.handle(session, protocol.clientCommand("InteractPropCsReq"), request.toByteArray()));
    assertEquals(1, replies.size());
    var reply = replies.getFirst();
    assertEquals(0, reply.getField(reply.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(1, reply.getField(reply.getDescriptorForType().findFieldByName("prop_state")));
    assertEquals(PropState.Open, prop(g.gate()).getState());
    assertEquals(2, player.packets.stream().filter(p -> p.getCmdId() == CmdId.SceneGroupRefreshScNotify).count());
  }

  @ParameterizedTest @ValueSource(ints = {20123, 20131})
  void originalGateBindingSurvivesLoadingAndAOneSidedReceiptDoesNotOpenIt(int plane) throws Exception {
    var g = gate(plane); start(g, true);
    var raw = records.getAsJsonObject(g.groupPath()).getAsJsonArray("PropList").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(p -> p.get("ID").getAsInt() == g.gate())
        .findFirst().orElseThrow();
    assertEquals(raw.get("ValueSource"), gson.toJsonTree(prop(g.gate()).getPropInfo().getValueSource()));
    var info = prop(g.gate()).getPropInfo();
    player.getGameplay().scenePropStates(player.getGameplay().getScenePropStates().with(List.of(
        new ScenePropState.Snapshot(g.plane(), g.floor(), 0, 0, g.group(), g.gate(), info.getPropID(),
            PropState.Open.getVal(), PropTimelines.source(info), ""))));
    reload();
    assertEquals(PropState.Closed, prop(g.console()).getState());
    assertEquals(PropState.Closed, prop(g.gate()).getState());
    assertEquals(Retcode.SUCC, open(g.console()).retcode());
  }

  @ParameterizedTest @ValueSource(ints = {20123, 20131})
  void savedPairRecoversAnInterruptedMissionCallback(int plane) throws Exception {
    var g = gate(plane); start(g, true);
    assertEquals(Retcode.SUCC, open(g.console()).retcode());
    player.getGameplay().getMainMissions().put(g.main(), new QuestState(g.main()));
    player.getGameplay().getSubMissions().put(g.sub(), new QuestState(g.sub()));
    reload();
    player.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(g.sub()).getPhase());
    assertEquals(PropState.Open, prop(g.console()).getState());
    assertEquals(PropState.Open, prop(g.gate()).getState());
  }

  @ParameterizedTest @ValueSource(ints = {20123, 20131})
  void changedGateSourceDoesNotRestoreAnEarlierCompletedPair(int plane) {
    var g = gate(plane); start(g, true);
    assertEquals(Retcode.SUCC, open(g.console()).retcode());
    prop(g.gate()).getPropInfo().getValueSource().getValues().getFirst()
        .addProperty("Value", "9,399999");
    scene();
    assertEquals(PropState.Locked, prop(g.console()).getState());
    assertEquals(PropState.Closed, prop(g.gate()).getState());
  }

  @ParameterizedTest @ValueSource(ints = {20123, 20131})
  void changedOrAmbiguousBindingsDoNotEnableOrOpenAnything(int plane) {
    var g = gate(plane);
    for (var variant : List.of("missing-console-binding", "changed-gate-binding", "duplicate-owner")) {
      freshFloor(g); start(g, true);
      var console = prop(g.console()).getPropInfo();
      var target = prop(g.gate()).getPropInfo();
      switch (variant) {
        case "missing-console-binding" -> console.setValueSource(null);
        case "changed-gate-binding" -> target.getValueSource().getValues().getFirst()
            .addProperty("Value", "9,399999");
        case "duplicate-owner" -> {
          var clone = gson.fromJson(gson.toJson(console), PropInfo.class);
          clone.ID = 390000; clone.setGroup(console.getGroup());
          console.getGroup().getPropList().add(clone);
        }
      }
      scene();
      assertEquals(PropState.Locked, prop(g.console()).getState(), variant);
      assertEquals(PropState.Closed, prop(g.gate()).getState(), variant);
      assertNotEquals(Retcode.SUCC, open(g.console()).retcode(), variant);
      assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty(), variant);
    }
  }

  @Test void repeatedSourceFailuresAreCountedSeparatelyForBothFloors() {
    GameDiagnostics.flush(true);
    var logger = (Logger) LunarCore.getLogger();
    var capture = new ListAppender<ILoggingEvent>(); capture.start(); logger.addAppender(capture);
    try {
      for (var g : List.of(SVAROG, HILL)) {
        freshFloor(g); start(g, true);
        var session = new GameSession(null) { public Player getPlayer() { return player; } };
        session.setState(SessionState.ACTIVE); player.setSession(session);
        prop(g.gate()).getPropInfo().setValueSource(null);
        scene(); scene();
      }
      GameDiagnostics.flush(true);
      var messages = capture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertEquals(1, messages.stream().filter(m -> m.contains("command=FollowupStoryGate")
          && m.contains("floor=20123001")).count());
      assertEquals(1, messages.stream().filter(m -> m.contains("command=FollowupStoryGate")
          && m.contains("floor=20131001")).count());
      assertTrue(messages.stream().anyMatch(m -> m.contains("Diagnostic repeats:")
          && m.contains("FollowupStoryGate") && m.contains("repeated=2")));
    } finally {
      logger.detachAppender(capture); capture.stop(); GameDiagnostics.flush(true);
    }
  }
}
