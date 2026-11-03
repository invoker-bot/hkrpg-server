package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The pictured console first plays mission 104, then its original puzzle reports mission 105. */
class BackwaterStoryConsole46Test {
  final BackwaterGate46Test f = new BackwaterGate46Test();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  JsonObject records, script;

  @BeforeEach void prepare() throws Exception {
    oldSubs.putAll(GameplayDefinitions.subs);
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    GameplayDefinitions.subs.clear();
    GameData.getMainMissionExcelMap().values().clear();
    f.prepare();
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/backwater-story-console-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    GameData.getMainMissionExcelMap().add(f.gson.fromJson(
        records.getAsJsonArray("ExcelOutput/MainMission.json").get(0), MainMissionExcel.class));
    for (var raw : records.getAsJsonObject("Config/Level/Mission/1010301/MissionInfo_1010301.partial.json")
        .getAsJsonArray("SubMissionList")) {
      var sub = f.gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    script = records.getAsJsonObject("Config/Level/Mission/1010301/Mission_101030104.json");
    script(script);
    f.player.getGameplay().getMainMissions().put(1010301, new QuestState(1010301));
    accepted(101030102);
    f.player.getMissionManager().sub(101030102).advance(1, 1, 100);
    accepted(101030104);
    f.scene();
  }

  void script(JsonElement source) {
    var task = f.gson.fromJson(source, MissionTaskInfo.class);
    task.retainSourceDefinition(source);
    GameplayDefinitions.subs.get(101030104).setTask(task);
  }
  void accepted(int id) { f.player.getGameplay().getSubMissions().put(id, new QuestState(id)); }
  void introduction() {
    assertEquals(Retcode.SUCC, f.player.getMissionManager().finishTalk(101030104, "Mission_101030104"));
  }

  @AfterEach void cleanup() {
    f.cleanup();
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
  }

  @Test void acceptedIntroductionEnablesThePicturedConsoleWithoutCompletingAnything() {
    assertEquals(PropState.Locked, f.prop(300007).getPropInfo().getState());
    assertEquals(PropState.Closed, f.prop(300007).getState(), "Mission 104 independently enables its UI callback");
    assertEquals(PropState.Closed, f.prop(300006).getState());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(101030104).getPhase());
    assertNull(f.player.getMissionManager().sub(101030105));
    assertTrue(f.player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void originalCameraBoardAndGateBindingsSurviveLoading() {
    var raw = f.records.getAsJsonObject(BackwaterGate46Test.GROUP).getAsJsonArray("PropList").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(p -> p.get("ID").getAsInt() == 300007).findFirst().orElseThrow();
    assertEquals(raw.get("ValueSource"), f.gson.toJsonTree(f.prop(300007).getPropInfo().getValueSource()));
  }

  @Test void introductionThenPuzzleCompletionOpensOnlyTheBoundGateAndAcceptsTheNextPerformance() {
    assertEquals(PropState.Closed, f.prop(300007).getState());
    assertNotEquals(Retcode.SUCC, f.open(300007).retcode(), "The introductory performance is still pending");
    introduction();
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(101030105).getPhase());
    assertEquals(PropState.Closed, f.prop(300007).getState());
    assertEquals(PropState.Closed, f.prop(300006).getState());
    assertEquals(Retcode.SUCC, f.open(300007).retcode());
    assertEquals(PropState.Open, f.prop(300007).getState());
    assertEquals(PropState.Open, f.prop(300006).getState());
    assertEquals(PropState.Closed, f.prop(300002).getState());
    assertEquals(PropState.Locked, f.prop(300004).getState());
    assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(101030105).getPhase());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(101030201).getPhase());
    assertEquals(2, f.player.getGameplay().getScenePropStates().getRecords().size());
  }

  @Test void enabledIntroductionAndCompletedPairSurviveReload() throws Exception {
    f.reload();
    assertEquals(PropState.Closed, f.prop(300007).getState());
    introduction();
    f.reload();
    assertEquals(PropState.Closed, f.prop(300007).getState());
    assertEquals(Retcode.SUCC, f.open(300007).retcode());
    f.reload();
    assertEquals(PropState.Open, f.prop(300007).getState());
    assertEquals(PropState.Open, f.prop(300006).getState());
    assertNotEquals(Retcode.SUCC, f.open(300007).retcode());
  }

  @Test void failedSaveDoesNotFinishThePuzzleOrOpenItsGate() {
    introduction();
    f.player.scene.toProto();
    f.player.packets.clear();
    var before = f.gson.toJson(f.player.getGameplay());
    f.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, f.open(300007).retcode());
    assertEquals(before, f.gson.toJson(f.player.getGameplay()));
    assertEquals(PropState.Closed, f.prop(300007).getState());
    assertEquals(PropState.Closed, f.prop(300006).getState());
    assertTrue(f.player.packets.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-accepted", "cancelled", "cancelled-main", "wrong-parent", "missing-enable", "extra-action", "wrong-target"})
  void absentAcceptanceOrChangedIntroductionCannotEnableTheConsole(String variant) {
    var changed = script.deepCopy();
    var sequences = changed.getAsJsonArray("OnStartSequece");
    switch (variant) {
      case "not-accepted" -> f.player.getGameplay().getSubMissions().remove(101030104);
      case "cancelled" -> f.player.getGameplay().getCancelledSubs().add(101030104);
      case "cancelled-main" -> f.player.getGameplay().getCancelledMissions().add(1010301);
      case "wrong-parent" -> GameplayDefinitions.subs.get(101030104).setMainMissionID(1010303);
      case "missing-enable" -> sequences.remove(1);
      case "extra-action" -> sequences.get(1).getAsJsonObject().getAsJsonArray("TaskList")
          .add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.TriggerBattle\"}"));
      case "wrong-target" -> sequences.get(0).getAsJsonObject().getAsJsonArray("TaskList").get(0)
          .getAsJsonObject().getAsJsonObject("TargetType").getAsJsonObject("SinglePropID")
          .getAsJsonObject("ID").getAsJsonObject("FixedValue").addProperty("Value", 300003);
    }
    script(changed);
    f.scene();
    assertEquals(PropState.Locked, f.prop(300007).getState());
    assertNotEquals(Retcode.SUCC, f.open(300007).retcode());
    assertEquals(PropState.Closed, f.prop(300006).getState());
  }
}
