package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original exit-console callback, not an interaction with the nearby space fissure itself. */
class BackwaterExitGate46Test {
  final BackwaterGate46Test f = new BackwaterGate46Test();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  JsonObject script;

  @BeforeEach void prepare() throws Exception {
    oldSubs.putAll(GameplayDefinitions.subs);
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    GameplayDefinitions.subs.clear();
    GameData.getMainMissionExcelMap().values().clear();
    f.prepare();
    JsonObject records;
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/backwater-exit-gate-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MainMission.json"))
      GameData.getMainMissionExcelMap().add(f.gson.fromJson(raw, MainMissionExcel.class));
    for (var raw : records.getAsJsonObject("Config/Level/Mission/1010302/MissionInfo_1010302.partial.json")
        .getAsJsonArray("SubMissionList")) {
      var sub = f.gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    var row = f.gson.fromJson(records.getAsJsonArray("ExcelOutput/InteractConfig.json").get(0), InteractExcel.class);
    f.oldInteractions.put(row.getId(), GameData.getInteractExcelMap().get(row.getId()));
    GameData.getInteractExcelMap().add(row);
    script = records.getAsJsonObject("Config/Level/Mission/1010302/Mission_101030203.json");
    source(script);
    f.player.getGameplay().getMainMissions().put(1010302, new QuestState(1010302));
    f.player.getGameplay().getSubMissions().put(101030203, new QuestState(101030203));
    f.scene();
  }

  void source(JsonObject raw) {
    var task = f.gson.fromJson(raw, MissionTaskInfo.class);
    task.retainSourceDefinition(raw);
    GameplayDefinitions.subs.get(101030203).setTask(task);
  }

  ScenePropTransitions.Result open() {
    return ScenePropTransitions.interact(f.player, 1210, f.prop(300004).getEntityId());
  }

  @AfterEach void cleanup() {
    f.cleanup();
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
  }

  @Test void acceptanceKeepsTheConsoleLockedUntilItsOriginalPerformanceCallback() {
    f.player.getMissionManager().refresh();
    assertEquals(PropState.Locked, f.prop(300004).getState());
    assertEquals(PropState.Closed, f.prop(300005).getState());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(101030203).getPhase());
    assertNull(f.player.getMissionManager().sub(101030204));
    assertTrue(f.player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void originalNativeCallbackOpensOnlyItsBoundGateAndAcceptsTheFissureBattle() throws Exception {
    var protocol = ProtocolBridge.forVersion("4.6.0");
    var replies = new ArrayList<DynamicMessage>();
    var session = new GameSession(null) {
      public Player getPlayer() { return f.player; }
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
        .setField(type.findFieldByName("prop_entity_id"), f.prop(300004).getEntityId())
        .setField(type.findFieldByName("interact_id2"), 1210)
        .setField(type.findFieldByName("interact_id"), -1L).build();
    assertTrue(VersionedPacketHandler.handle(session, protocol.clientCommand("InteractPropCsReq"), request.toByteArray()));
    assertEquals(1, replies.size());
    var reply = replies.getFirst();
    assertEquals(0, reply.getField(reply.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(1, reply.getField(reply.getDescriptorForType().findFieldByName("prop_state")));
    assertEquals(PropState.Open, f.prop(300004).getState());
    assertEquals(PropState.Open, f.prop(300005).getState());
    assertEquals(PropState.Closed, f.prop(300002).getState());
    assertEquals(PropState.Locked, f.prop(300007).getState());
    assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(101030203).getPhase());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(101030204).getPhase());
    assertNull(f.player.getMissionManager().sub(101030205), "Opening the gate is not winning the battle");
    assertEquals(2, f.player.getGameplay().getScenePropStates().getRecords().size());
  }

  @Test void savedPairAndPendingBattleSurviveReload() throws Exception {
    assertEquals(Retcode.SUCC, open().retcode());
    f.reload();
    assertEquals(PropState.Open, f.prop(300004).getState());
    assertEquals(PropState.Open, f.prop(300005).getState());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(101030204).getPhase());
    assertNotEquals(Retcode.SUCC, open().retcode());
    assertEquals(2, f.player.getGameplay().getScenePropStates().getRecords().size());
  }

  @Test void aSavedPairBeforeTheMissionCallbackResumesTheSameObjectiveOnRefresh() throws Exception {
    assertEquals(Retcode.SUCC, open().retcode());
    f.player.getGameplay().getSubMissions().put(101030203, new QuestState(101030203));
    f.player.getGameplay().getSubMissions().remove(101030204);
    f.reload();
    f.player.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(101030203).getPhase());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(101030204).getPhase());
    assertEquals(PropState.Open, f.prop(300005).getState());
  }

  @Test void failedSaveRollsBackBothActorsAndLeavesTheCallbackRetryable() {
    f.player.scene.toProto();
    f.player.packets.clear();
    var before = f.gson.toJson(f.player.getGameplay());
    f.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, open().retcode());
    assertEquals(before, f.gson.toJson(f.player.getGameplay()));
    assertEquals(PropState.Locked, f.prop(300004).getState());
    assertEquals(PropState.Closed, f.prop(300005).getState());
    assertTrue(f.player.packets.isEmpty());
    f.player.failSave = false;
    assertEquals(Retcode.SUCC, open().retcode());
  }

  @ParameterizedTest @ValueSource(strings = {"not-accepted", "cancelled", "cancelled-main", "wrong-parent",
      "wrong-goal", "wrong-target", "dynamic-request", "extra-action", "wrong-performance", "missing-script",
      "wrong-binding", "wrong-reverse-binding", "missing-gate"})
  void unacceptedOrChangedSourceCannotOpenTheExit(String variant) {
    var changed = script.deepCopy();
    var tasks = changed.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList")
        .get(0).getAsJsonObject().getAsJsonArray("ButtonCallback").get(0).getAsJsonObject().getAsJsonArray("Execute");
    switch (variant) {
      case "not-accepted" -> f.player.getGameplay().getSubMissions().remove(101030203);
      case "cancelled" -> f.player.getGameplay().getCancelledSubs().add(101030203);
      case "cancelled-main" -> f.player.getGameplay().getCancelledMissions().add(1010302);
      case "wrong-parent" -> GameplayDefinitions.subs.get(101030203).setMainMissionID(1010301);
      case "wrong-goal" -> GameplayDefinitions.subs.get(101030203).setParamInt3(0);
      case "wrong-target" -> tasks.get(5).getAsJsonObject().getAsJsonObject("TargetType")
          .getAsJsonObject("SinglePropID").getAsJsonObject("ID").getAsJsonObject("FixedValue").addProperty("Value", 300003);
      case "dynamic-request" -> tasks.get(5).getAsJsonObject().getAsJsonObject("InteractID").addProperty("IsDynamic", true);
      case "extra-action" -> tasks.add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.TriggerBattle\"}"));
      case "wrong-performance" -> tasks.get(4).getAsJsonObject().addProperty("PerformanceID", 101030104);
      case "missing-script" -> GameplayDefinitions.subs.get(101030203).setTask(null);
      case "wrong-binding" -> f.prop(300004).getPropInfo().getValueSource().getValues().getFirst().addProperty("Value", "21,300002");
      case "wrong-reverse-binding" -> f.prop(300005).getPropInfo().getValueSource().getValues().getFirst().addProperty("Value", "21,300003");
      case "missing-gate" -> f.player.scene.getEntities().remove(f.prop(300005).getEntityId());
    }
    if (!variant.equals("missing-script")) source(changed);
    assertNotEquals(Retcode.SUCC, open().retcode());
    assertEquals(PropState.Locked, f.prop(300004).getState());
    assertTrue(f.player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void ordinaryOpenUnlockAndDirectGateRequestsCannotBypassTheAuthoredCallback() {
    assertNotEquals(Retcode.SUCC, f.open(300004).retcode());
    assertNotEquals(Retcode.SUCC, ScenePropTransitions.interact(f.player, 1200, f.prop(300004).getEntityId()).retcode());
    assertNotEquals(Retcode.SUCC, f.open(300005).retcode());
    assertEquals(PropState.Locked, f.prop(300004).getState());
    assertTrue(f.player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }
}
