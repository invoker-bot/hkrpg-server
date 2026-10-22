package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.data.excel.InteractExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The actual fade-out -> config 1200 -> mission 116 chain, with isolated player history. */
class StoryDoorUnlock46Test {
  final StoryDoors46Test f = new StoryDoors46Test();
  JsonObject script;
  InteractExcel previous;

  @BeforeEach void setup() throws Exception {
    f.prepare();
    try (var in = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/story-door-unlock-4.6.json"), java.nio.charset.StandardCharsets.UTF_8)) {
      var records = JsonParser.parseReader(in).getAsJsonObject().getAsJsonObject("records");
      script = records.getAsJsonObject("Config/Level/Mission/1000101/Mission_100010124.json");
      var row = f.gson.fromJson(records.getAsJsonArray("ExcelOutput/InteractConfig.json").get(0), InteractExcel.class);
      previous = GameData.getInteractExcelMap().get(1200);
      GameData.getInteractExcelMap().add(row);
    }
    source(script);
  }

  void source(JsonObject value) {
    var task = f.gson.fromJson(value, MissionTaskInfo.class);
    task.retainSourceDefinition(value);
    GameplayDefinitions.subs.get(100010124).setTask(task);
  }

  @AfterEach void cleanup() {
    GameData.getInteractExcelMap().keySet().remove(1200);
    if (previous != null) GameData.getInteractExcelMap().add(previous);
    f.cleanup();
  }

  void ready() {
    f.completed(100010113);
    f.accepted(100010124);
    f.scene();
  }

  ScenePropTransitions.Result unlock() {
    return ScenePropTransitions.interact(f.player, 1200, f.prop(300029).getEntityId());
  }

  @Test void realNativeUnlockCompletesBlackScreenObjectiveAndAcceptsContinuation() throws Exception {
    ready();
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
        .setField(type.findFieldByName("prop_entity_id"), f.prop(300029).getEntityId())
        .setField(type.findFieldByName("interact_id2"), 1200)
        .setField(type.findFieldByName("interact_id"), -1L).build();
    assertTrue(VersionedPacketHandler.handle(session, 1405, request.toByteArray()));
    assertEquals(1, replies.size());
    var reply = replies.getFirst();
    assertEquals(0, reply.getField(reply.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(0, reply.getField(reply.getDescriptorForType().findFieldByName("prop_state")));
    assertEquals(PropState.Closed, f.prop(300029).getState());
    assertEquals(PropState.Closed, f.prop(300007).getState(), "Unlock is not opening the linked door");
    assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(100010124).getPhase());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100010116).getPhase());
  }

  @Test void unlockAndLaterOpenSurviveReloadWithoutResettingOrPrematurelyOpeningDoor() {
    ready();
    assertEquals(Retcode.SUCC, unlock().retcode());
    f.reload();
    assertEquals(PropState.Closed, f.prop(300029).getState());
    assertEquals(PropState.Closed, f.prop(300007).getState());
    assertEquals(Retcode.SUCC, ScenePropTransitions.interact(f.player, 1010, f.prop(300029).getEntityId()).retcode());
    f.reload();
    assertEquals(PropState.Open, f.prop(300029).getState());
    assertEquals(PropState.Open, f.prop(300007).getState());
    assertNotEquals(Retcode.SUCC, unlock().retcode());
    assertEquals(PropState.Open, f.prop(300007).getState());
  }

  @Test void receiptBeforeMissionCallbackRecoversOnRefresh() {
    ready();
    assertEquals(Retcode.SUCC, unlock().retcode());
    f.accepted(100010124); // Simulate durable prop save before the mission callback saved.
    f.player.getGameplay().getSubMissions().remove(100010116);
    f.reload();
    f.player.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(100010124).getPhase());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100010116).getPhase());
    assertEquals(PropState.Closed, f.prop(300007).getState());
  }

  @Test void failedSaveKeepsLockedStateAndDoesNotCompleteTheFadeOutObjective() {
    ready();
    var before = f.gson.toJson(f.player.getGameplay());
    f.player.packets.clear();
    f.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, unlock().retcode());
    assertEquals(before, f.gson.toJson(f.player.getGameplay()));
    assertEquals(PropState.Locked, f.prop(300029).getState());
    assertTrue(f.player.packets.isEmpty());
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "cancelled", "wrong-target", "dynamic", "other-action"})
  void unprovenUnlockCannotBypassAStoryGate(String variant) {
    ready();
    switch (variant) {
      case "missing" -> f.player.getGameplay().getSubMissions().remove(100010124);
      case "cancelled" -> f.player.getGameplay().getCancelledSubs().add(100010124);
      default -> {
        var changed = script.deepCopy();
        var tasks = changed.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList");
        var action = tasks.get(1).getAsJsonObject();
        var target = action.getAsJsonObject("TargetType").getAsJsonObject("SinglePropID").getAsJsonObject("ID");
        if (variant.equals("wrong-target")) target.getAsJsonObject("FixedValue").addProperty("Value", 300002);
        if (variant.equals("dynamic")) target.addProperty("IsDynamic", true);
        if (variant.equals("other-action")) action.addProperty("$type", "RPG.GameCore.ChangePropState");
        source(changed);
      }
    }
    assertNotEquals(Retcode.SUCC, unlock().retcode());
    assertEquals(PropState.Locked, f.prop(300029).getState());
    assertTrue(f.player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }
}
