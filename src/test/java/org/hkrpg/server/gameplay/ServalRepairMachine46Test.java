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

/** Original repair machine, enable script and objectives; player history and failed saves are fixtures. */
class ServalRepairMachine46Test {
  static final int MAIN = 1010206, TARGET = 101020109;
  static final String FLOOR = "P10101_F10101007";
  static final String SCRIPT = "Config/Level/Mission/1010201/Mission_101020109.json";
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
    public int getPlaneId() { return 10101; }
    public int getFloorId() { return 10101007; }
    public int getEntryId() { return 1010107; }
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
        "/fixtures/serval-repair-machine-4.6.json"), StandardCharsets.UTF_8)) {
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
        "Config/Level/Mission/1010206/MissionInfo_1010206.json"), MissionInfo.class));
    for (var raw : records.getAsJsonObject(
        "Config/Level/Mission/1010206/MissionInfo_1010206.partial.json").getAsJsonArray("SubMissionList")) {
      var sub = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    script(records.getAsJsonObject(SCRIPT));
    plane = gson.fromJson(records.getAsJsonArray("ExcelOutput/MazePlane.json").get(0), MazePlaneExcel.class);
    floor = gson.fromJson(records.get("Config/LevelOutput/RuntimeFloor/" + FLOOR + ".json"), FloorInfo.class);
    for (int id : List.of(12)) {
      var group = gson.fromJson(records.get("Config/LevelOutput/SharedRuntimeGroup/Groups_" + FLOOR
          + "/LevelGroup_" + FLOOR + "_G" + id + ".json"), GroupInfo.class);
      group.setId(id);
      floor.getGroups().put(id, group);
    }
    floor.onLoad();
    GameData.getFloorInfos().put(FLOOR, floor);
    player = new Actor();
    player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    finished(101020107);
    finished(101020108);
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
    player.scene = new Scene(player, plane, 10101007);
    player.scene.setEntryId(player.getEntryId());
  }

  void reload() throws Exception {
    var saved = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    player = new Actor();
    DailyMissionAcceptance46Test.set(player, "gameplay", saved);
    scene();
  }

  EntityProp machine() {
    return player.scene.getEntitiesByGroup(EntityProp.class, 12).stream()
        .filter(p -> p.getInstId() == 300002).findFirst().orElseThrow();
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

  @Test void acceptedOriginalObjectiveEnablesTheMachineWithoutCompletingIt() {
    scene();
    assertEquals(PropState.Locked, machine().getPropInfo().getState(), "Original resource stays locked");
    assertEquals(PropState.Closed, machine().getState(), "Accepted ChangePropState must enable the repair machine");
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(TARGET).getPhase());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void originalPuzzleBindingsSurviveFloorLoading() {
    var original = records.getAsJsonObject("Config/LevelOutput/SharedRuntimeGroup/Groups_" + FLOOR
        + "/LevelGroup_" + FLOOR + "_G12.json").getAsJsonArray("PropList").get(0)
        .getAsJsonObject().get("ValueSource");
    assertEquals(original, gson.toJsonTree(floor.getGroups().get(12).getPropList().getFirst().getValueSource()));
  }

  @Test void nativePuzzleCompletionSavesOpenStateAndStartsTheOriginalDialogue() throws Exception {
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
        .setField(type.findFieldByName("prop_entity_id"), machine().getEntityId())
        .setField(type.findFieldByName("interact_id2"), 1010)
        .setField(type.findFieldByName("interact_id"), -1L).build();
    assertTrue(VersionedPacketHandler.handle(session, protocol.clientCommand("InteractPropCsReq"), request.toByteArray()));
    assertEquals(1, replies.size());
    var reply = replies.getFirst();
    assertEquals(0, reply.getField(reply.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(1, reply.getField(reply.getDescriptorForType().findFieldByName("prop_state")));
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(TARGET).getPhase());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(101020110).getPhase());
    assertEquals(1, player.getGameplay().getScenePropStates().getRecords().size());
    reload();
    assertEquals(PropState.Open, machine().getState());
    assertNotEquals(Retcode.SUCC, ScenePropTransitions.interact(player, 1010, machine().getEntityId()).retcode());
  }

  @Test void alreadyLoadedMachineEnablesWhenTheObjectiveIsAccepted() {
    player.getGameplay().getSubMissions().remove(TARGET);
    scene();
    assertEquals(PropState.Locked, machine().getState());
    player.scene.toProto();
    player.packets.clear();
    player.getMissionManager().refresh();
    assertEquals(PropState.Closed, machine().getState());
    assertTrue(player.packets.stream().anyMatch(p -> p.getCmdId() == CmdId.SceneGroupRefreshScNotify));
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(TARGET).getPhase());
  }

  @Test void enabledUnsolvedMachineSurvivesReload() throws Exception {
    scene();
    reload();
    assertEquals(PropState.Closed, machine().getState());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(TARGET).getPhase());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void failedSaveLeavesMachineAndDialogueUntouched() {
    scene();
    var before = gson.toJson(player.getGameplay());
    player.scene.toProto();
    player.packets.clear();
    player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, ScenePropTransitions.interact(player, 1010, machine().getEntityId()).retcode());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(PropState.Closed, machine().getState());
    assertTrue(player.packets.isEmpty());
  }

  @Test void durableRepairRecoversAnInterruptedMissionCallback() throws Exception {
    scene();
    assertEquals(Retcode.SUCC, ScenePropTransitions.interact(player, 1010, machine().getEntityId()).retcode());
    accepted(TARGET); // Persisted interaction, interrupted before the mission completion save.
    player.getGameplay().getSubMissions().remove(101020110);
    reload();
    player.getMissionManager().refresh();
    assertEquals(PropState.Open, machine().getState());
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(TARGET).getPhase());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(101020110).getPhase());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "cancelled", "cancelled-main", "wrong-target", "wrong-parent",
      "wrong-predecessor", "missing-enable", "extra-effect", "changed-binding", "changed-board"})
  void absentGatesAndUnrecognizedScriptsDoNotEnableTheMachine(String variant) {
    var target = GameplayDefinitions.subs.get(TARGET);
    switch (variant) {
      case "missing" -> player.getGameplay().getSubMissions().remove(TARGET);
      case "cancelled" -> player.getGameplay().getCancelledSubs().add(TARGET);
      case "cancelled-main" -> player.getGameplay().getCancelledMissions().add(MAIN);
      case "wrong-target" -> target.setParamInt2(300003);
      case "wrong-parent" -> target.setMainMissionID(1010201);
      case "wrong-predecessor" -> target.setTakeParamIntList(new Integer[] {101020107});
      case "changed-binding", "changed-board" -> {
        var prop = floor.getGroups().get(12).getPropList().getFirst();
        var original = records.getAsJsonObject("Config/LevelOutput/SharedRuntimeGroup/Groups_" + FLOOR
            + "/LevelGroup_" + FLOOR + "_G12.json").getAsJsonArray("PropList").get(0)
            .getAsJsonObject().get("ValueSource");
        var binding = gson.fromJson(original, PropInfo.PropValueSource.class);
        prop.setValueSource(binding);
        binding.getValues().get(variant.equals("changed-binding") ? 2 : 1)
            .addProperty("Value", variant.equals("changed-binding") ? "12,300003" : "unknown.prefab");
      }
      default -> {
        var changed = records.getAsJsonObject(SCRIPT).deepCopy();
        var tasks = changed.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList");
        if (variant.equals("missing-enable")) tasks.remove(0);
        else tasks.add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.TriggerBattle\"}"));
        script(changed);
      }
    }
    scene();
    assertEquals(PropState.Locked, machine().getState());
    assertNotEquals(Retcode.SUCC, ScenePropTransitions.interact(player, 1010, machine().getEntityId()).retcode());
    assertNotEquals(Retcode.SUCC, ScenePropTransitions.interact(player, 1200, machine().getEntityId()).retcode());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }
}
