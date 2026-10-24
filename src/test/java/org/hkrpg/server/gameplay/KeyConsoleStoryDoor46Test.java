package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The original key console has parallel delayed speech, not a prerequisite for enabling it. */
class KeyConsoleStoryDoor46Test {
  final AwakeningStoryDoor46Test base = new AwakeningStoryDoor46Test();
  final StoryDoors46Test f = base.f;
  JsonObject script;

  @BeforeEach
  void prepare() throws Exception {
    base.prepare();
    JsonObject records;
    try (var reader = new InputStreamReader(
        getClass().getResourceAsStream("/fixtures/key-console-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    GameData.getMainMissionExcelMap().add(f.gson.fromJson(
        records.getAsJsonArray("ExcelOutput/MainMission.json").get(0), MainMissionExcel.class));
    for (var raw : records.getAsJsonObject(
        "Config/Level/Mission/1000202/MissionInfo_1000202.partial.json").getAsJsonArray("SubMissionList")) {
      var sub = f.gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      var source = records.get("Config/Level/Mission/1000201/Mission_" + sub.getId() + ".json");
      if (source != null) {
        var task = f.gson.fromJson(source, MissionTaskInfo.class);
        task.retainSourceDefinition(source);
        sub.setTask(task);
      }
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    script = records.getAsJsonObject("Config/Level/Mission/1000201/Mission_100020112.json");
    f.player.getGameplay().getMainMissions().remove(1000201);
    f.player.getGameplay().getMainMissions().put(1000202, new QuestState(1000202));
    addDoor();
  }

  void addDoor() {
    var original = f.gson.fromJson(base.records.get(
        "Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G3.json"),
        GroupInfo.class);
    base.floor.getGroups().get(3).getPropList().add(original.getPropList().stream()
        .filter(p -> p.getID() == 300004).findFirst().orElseThrow());
    base.floor.onLoad();
  }

  void source(JsonObject value) {
    var task = f.gson.fromJson(value, MissionTaskInfo.class);
    task.retainSourceDefinition(value);
    GameplayDefinitions.subs.get(100020112).setTask(task);
  }

  void ready() {
    f.completed(100020111);
    f.accepted(100020112);
    base.scene();
  }

  ScenePropTransitions.Result open() {
    return ScenePropTransitions.interact(f.player, 1010, base.prop(300006).getEntityId());
  }

  void reload() {
    base.reload();
    addDoor();
    base.scene();
  }

  @AfterEach
  void cleanup() { base.cleanup(); }

  @Test
  void keyObjectiveEnablesItsConsoleAndOpensOnlyItsBoundDoor() {
    ready();
    assertEquals(PropState.Closed, base.prop(300006).getState());
    assertEquals(PropState.Closed, base.prop(300004).getState());
    assertEquals(PropState.Locked, base.prop(300002).getState());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100020112).getPhase());
    assertEquals(Retcode.SUCC, open().retcode());
    assertEquals(PropState.Open, base.prop(300006).getState());
    assertEquals(PropState.Open, base.prop(300004).getState());
    assertEquals(PropState.Locked, base.prop(300002).getState());
    assertEquals(PropState.Closed, base.prop(300001).getState());
    assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(100020112).getPhase());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100020113).getPhase());
    assertNull(f.player.getMissionManager().sub(100020108), "Do not skip the next performance");
  }

  @Test
  void keyConsoleAndDoorSurviveReloadWithNoDuplicateInteraction() {
    ready();
    reload();
    assertEquals(PropState.Closed, base.prop(300006).getState());
    assertEquals(PropState.Closed, base.prop(300004).getState());
    assertEquals(Retcode.SUCC, open().retcode());
    var receipts = f.gson.toJson(f.player.getGameplay().getScenePropStates());
    var missions = f.gson.toJson(f.player.getGameplay().getSubMissions());
    reload();
    assertEquals(PropState.Open, base.prop(300006).getState());
    assertEquals(PropState.Open, base.prop(300004).getState());
    assertNotEquals(Retcode.SUCC, open().retcode());
    assertEquals(receipts, f.gson.toJson(f.player.getGameplay().getScenePropStates()));
    assertEquals(missions, f.gson.toJson(f.player.getGameplay().getSubMissions()));
  }

  @Test
  void refreshEnablesNewlyAcceptedGoalWithoutRelogOrOpeningTheDoor() {
    base.scene();
    assertEquals(PropState.Locked, base.prop(300006).getState());
    f.completed(100020111);
    f.player.getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100020112).getPhase());
    assertEquals(PropState.Closed, base.prop(300006).getState());
    assertEquals(PropState.Closed, base.prop(300004).getState());
  }

  @Test
  void failedSaveDoesNotOpenDoorOrFinishObjective() {
    ready();
    var before = f.gson.toJson(f.player.getGameplay());
    f.player.packets.clear();
    f.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, open().retcode());
    assertEquals(before, f.gson.toJson(f.player.getGameplay()));
    assertEquals(PropState.Closed, base.prop(300006).getState());
    assertEquals(PropState.Closed, base.prop(300004).getState());
    assertTrue(f.player.packets.isEmpty());
  }

  @Test
  void pairedReceiptRecoversInterruptedMissionCallback() {
    ready();
    assertEquals(Retcode.SUCC, open().retcode());
    f.accepted(100020112);
    f.player.getGameplay().getSubMissions().remove(100020113);
    reload();
    f.player.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(100020112).getPhase());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100020113).getPhase());
  }

  @ParameterizedTest
  @ValueSource(strings = {"unaccepted", "cancelled", "cancelled-main", "wrong-target",
      "dynamic-wait", "string-wait", "negative-wait", "conditional-enable", "extra-init",
      "extra-effect", "duplicate-enable", "ordered-speech", "dynamic-talk", "wrong-talk-id"})
  void missingAcceptanceOrUnrecognizedScriptCannotBypassLock(String variant) {
    f.completed(100020111);
    f.accepted(100020112);
    var changed = script.deepCopy();
    var sequences = changed.getAsJsonArray("OnStartSequece");
    var tasks = sequences.get(0).getAsJsonObject().getAsJsonArray("TaskList");
    var wait = tasks.get(0).getAsJsonObject().getAsJsonObject("WaitTime");
    switch (variant) {
      case "unaccepted" -> f.player.getGameplay().getSubMissions().remove(100020112);
      case "cancelled" -> f.player.getGameplay().getCancelledSubs().add(100020112);
      case "cancelled-main" -> f.player.getGameplay().getCancelledMissions().add(1000202);
      case "wrong-target" -> GameplayDefinitions.subs.get(100020112).setParamInt2(399999);
      case "dynamic-wait" -> wait.addProperty("IsDynamic", true);
      case "string-wait" -> wait.getAsJsonObject("FixedValue").addProperty("Value", "1");
      case "negative-wait" -> wait.getAsJsonObject("FixedValue").addProperty("Value", -1);
      case "conditional-enable" -> {
        sequences.remove(1);
        tasks.add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.ChangePropState\"}"));
      }
      case "extra-init" -> changed.getAsJsonArray("OnInitSequece").add(sequences.get(1).deepCopy());
      case "extra-effect" -> tasks.add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.CreateProp\"}"));
      case "duplicate-enable" -> sequences.add(sequences.get(1).deepCopy());
      case "ordered-speech" -> sequences.get(0).getAsJsonObject().addProperty("Order", 1);
      case "dynamic-talk" -> tasks.get(1).getAsJsonObject().addProperty("IsDynamic", true);
      case "wrong-talk-id" -> tasks.get(1).getAsJsonObject().getAsJsonArray("SimpleTalkList")
          .get(0).getAsJsonObject().addProperty("TalkSentenceID", "100020328");
    }
    source(changed);
    base.scene();
    assertEquals(PropState.Locked, base.prop(300006).getState());
    assertNotEquals(Retcode.SUCC, open().retcode());
    assertEquals(PropState.Closed, base.prop(300004).getState());
    assertTrue(f.player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }
}
