package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
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
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original snowdrift, enable script and objectives; player history and failed saves are fixtures. */
class SnowdriftInvestigation46Test {
  static final int MAIN = 1010101, TARGET = 101010108;
  static final String FLOOR = "P20101_F20101001";
  static final String SCRIPT = "Config/Level/Mission/1010101/Mission_101010108.json";
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final Map<Integer, PropExcel> oldProps = new HashMap<>();
  final Map<Integer, InteractExcel> oldInteractions = new HashMap<>();
  JsonObject records;
  FloorInfo oldFloor, floor;
  MazePlaneExcel plane;
  MissionInfo oldMission;
  Actor player;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    final List<BasePacket> packets = new ArrayList<>();
    Scene scene;
    boolean failSave;

    Actor() { teams.getCurrentLineup().getAvatars().add(0); }
    public LineupManager getLineupManager() { return teams; }
    public GameAvatar getAvatarById(int id) { return null; }
    public Scene getScene() { return scene; }
    public int getPlaneId() { return 20101; }
    public int getFloorId() { return 20101001; }
    public int getEntryId() { return 2010101; }
    public void save() {
      if (failSave) throw new IllegalStateException("isolated failed save");
    }
    public void sendPacket(BasePacket packet) { packets.add(packet); }
  }

  @BeforeEach void prepare() throws Exception {
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    oldMission = GameData.getMissionInfos().get(MAIN);
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldSubs.putAll(GameplayDefinitions.subs);
    GameData.getMainMissionExcelMap().values().clear();
    GameplayDefinitions.subs.clear();
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/snowdrift-investigation-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MazeProp.json")) {
      var row = raw.getAsJsonObject().deepCopy();
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
    GameData.getMainMissionExcelMap().add(gson.fromJson(
        records.getAsJsonArray("ExcelOutput/MainMission.json").get(0), MainMissionExcel.class));
    GameData.getMissionInfos().put(MAIN, gson.fromJson(records.get(
        "Config/Level/Mission/1010101/MissionInfo_1010101.json"), MissionInfo.class));
    for (var raw : records.getAsJsonObject(
        "Config/Level/Mission/1010101/MissionInfo_1010101.partial.json").getAsJsonArray("SubMissionList")) {
      var sub = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    script(records.getAsJsonObject(SCRIPT));
    plane = gson.fromJson(records.getAsJsonArray("ExcelOutput/MazePlane.json").get(0), MazePlaneExcel.class);
    floor = gson.fromJson(records.get("Config/LevelOutput/RuntimeFloor/" + FLOOR + ".json"), FloorInfo.class);
    for (int id : List.of(23, 25)) {
      var group = gson.fromJson(records.get("Config/LevelOutput/SharedRuntimeGroup/Groups_" + FLOOR
          + "/LevelGroup_" + FLOOR + "_G" + id + ".json"), GroupInfo.class);
      group.setId(id);
      floor.getGroups().put(id, group);
    }
    floor.onLoad();
    GameData.getFloorInfos().put(FLOOR, floor);
    player = new Actor();
    player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    finished(101010102);
    finished(101010107);
    finished(101010120);
    accepted(101010106);
    accepted(TARGET);
  }

  void script(JsonObject source) {
    var task = gson.fromJson(source, MissionTaskInfo.class);
    task.retainSourceDefinition(source);
    GameplayDefinitions.subs.get(TARGET).setTask(task);
  }

  void accepted(int id) { player.getGameplay().getSubMissions().put(id, new QuestState(id)); }
  void finished(int id) { accepted(id); player.getMissionManager().sub(id).advance(1, 1, 100); }

  void scene() {
    player.scene = new Scene(player, plane, 20101001);
    player.scene.setEntryId(player.getEntryId());
  }

  void reload() throws Exception {
    var saved = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    player = new Actor();
    DailyMissionAcceptance46Test.set(player, "gameplay", saved);
    scene();
  }

  EntityProp snow() {
    return player.scene.getEntitiesByGroup(EntityProp.class, 25).stream()
        .filter(p -> p.getInstId() == 300001).findFirst().orElseThrow();
  }

  @AfterEach void cleanup() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    if (oldMission == null) GameData.getMissionInfos().remove(MAIN);
    else GameData.getMissionInfos().put(MAIN, oldMission);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    oldProps.forEach((id, old) -> {
      GameData.getPropExcelMap().keySet().remove(id);
      if (old != null) GameData.getPropExcelMap().add(old);
    });
    oldInteractions.forEach((id, old) -> {
      GameData.getInteractExcelMap().keySet().remove(id);
      if (old != null) GameData.getInteractExcelMap().add(old);
    });
  }

  @Test void acceptedOriginalObjectiveEnablesInvestigationWithoutCompletingIt() {
    scene();
    assertEquals(PropState.Locked, snow().getPropInfo().getState(), "Original resource stays locked");
    assertEquals(PropState.Closed, snow().getState(), "Accepted ChangePropState must enable the snowdrift");
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(TARGET).getPhase());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void realNativeInvestigationSavesOpenStateAndStartsTheOriginalPerformance() throws Exception {
    scene();
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
        .setField(type.findFieldByName("prop_entity_id"), snow().getEntityId())
        .setField(type.findFieldByName("interact_id2"), 1010)
        .setField(type.findFieldByName("interact_id"), -1L).build();
    assertTrue(VersionedPacketHandler.handle(session, protocol.clientCommand("InteractPropCsReq"), request.toByteArray()));
    assertEquals(1, replies.size());
    var reply = replies.getFirst();
    assertEquals(0, reply.getField(reply.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(1, reply.getField(reply.getDescriptorForType().findFieldByName("prop_state")));
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(TARGET).getPhase());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(101010109).getPhase());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(101010106).getPhase());
    assertEquals(1, player.getGameplay().getScenePropStates().getRecords().size());
    reload();
    assertEquals(PropState.Open, snow().getState());
    assertNotEquals(Retcode.SUCC, ScenePropTransitions.interact(player, 1010, snow().getEntityId()).retcode());
  }

  @Test void anAlreadyVisibleSnowdriftEnablesWhenTheObjectiveIsAccepted() {
    player.getGameplay().getSubMissions().remove(TARGET);
    scene();
    assertEquals(PropState.Locked, snow().getState());
    player.scene.toProto();
    player.packets.clear();
    player.getMissionManager().refresh();
    assertEquals(PropState.Closed, snow().getState());
    assertTrue(player.packets.stream().anyMatch(p -> p.getCmdId() == CmdId.SceneGroupRefreshScNotify));
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(TARGET).getPhase());
  }

  @Test void enabledButUninvestigatedSnowdriftSurvivesReload() throws Exception {
    scene();
    reload();
    assertEquals(PropState.Closed, snow().getState());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(TARGET).getPhase());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void failedSaveLeavesInvestigationAndPerformanceUntouched() {
    scene();
    var before = gson.toJson(player.getGameplay());
    player.scene.toProto();
    player.packets.clear();
    player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, ScenePropTransitions.interact(player, 1010, snow().getEntityId()).retcode());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(PropState.Closed, snow().getState());
    assertTrue(player.packets.isEmpty());
  }

  @Test void durableInvestigationRecoversAnInterruptedMissionCallback() throws Exception {
    scene();
    assertEquals(Retcode.SUCC, ScenePropTransitions.interact(player, 1010, snow().getEntityId()).retcode());
    accepted(TARGET); // Persisted interaction, interrupted before the mission completion save.
    player.getGameplay().getSubMissions().remove(101010109);
    reload();
    player.getMissionManager().refresh();
    assertEquals(PropState.Open, snow().getState());
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(TARGET).getPhase());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(101010109).getPhase());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "cancelled", "wrong-target", "missing-enable", "extra-effect", "changed-camera"})
  void absentGatesAndUnrecognizedScriptsDoNotUnlockTheSnowdrift(String variant) {
    switch (variant) {
      case "missing" -> player.getGameplay().getSubMissions().remove(TARGET);
      case "cancelled" -> player.getGameplay().getCancelledSubs().add(TARGET);
      case "wrong-target" -> GameplayDefinitions.subs.get(TARGET).setParamInt2(300002);
      default -> {
        var changed = records.getAsJsonObject(SCRIPT).deepCopy();
        var sequences = changed.getAsJsonArray("OnStartSequece");
        if (variant.equals("missing-enable")) sequences.remove(1);
        if (variant.equals("extra-effect")) sequences.get(1).getAsJsonObject().getAsJsonArray("TaskList")
            .add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.TriggerBattle\"}"));
        if (variant.equals("changed-camera")) sequences.get(0).getAsJsonObject().getAsJsonArray("TaskList")
            .get(0).getAsJsonObject().getAsJsonArray("OnTriggerEnter").get(0).getAsJsonObject()
            .addProperty("LookAtTargetAreaName", "unknown");
        script(changed);
      }
    }
    scene();
    assertEquals(PropState.Locked, snow().getState());
    assertNotEquals(Retcode.SUCC, ScenePropTransitions.interact(player, 1010, snow().getEntityId()).retcode());
    assertNotEquals(Retcode.SUCC, ScenePropTransitions.interact(player, 1200, snow().getEntityId()).retcode());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }
}
