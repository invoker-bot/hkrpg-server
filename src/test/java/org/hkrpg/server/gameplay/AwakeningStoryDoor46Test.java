package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original 100020104 parallel hints must not prevent its independent console enable. */
class AwakeningStoryDoor46Test {
  static final String FLOOR = "P20002_F20002001";
  final StoryDoors46Test f = new StoryDoors46Test();
  JsonObject records, script;
  FloorInfo oldFloor, floor;
  MazePlaneExcel plane;

  @BeforeEach
  void prepare() throws Exception {
    f.prepare();
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/awakening-door-4.6.json"),
            StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    plane =
        f.gson.fromJson(
            records.getAsJsonArray("ExcelOutput/MazePlane.json").get(0), MazePlaneExcel.class);
    GameData.getMainMissionExcelMap()
        .add(
            f.gson.fromJson(
                records.getAsJsonArray("ExcelOutput/MainMission.json").get(0),
                MainMissionExcel.class));
    for (var raw :
        records
            .getAsJsonObject("Config/Level/Mission/1000201/MissionInfo_1000201.partial.json")
            .getAsJsonArray("SubMissionList")) {
      var sub = f.gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    script = records.getAsJsonObject("Config/Level/Mission/1000201/Mission_100020104.json");
    source(script);
    actor();
    f.player.getGameplay().getMainMissions().put(1000201, new QuestState(1000201));
    freshFloor();
  }

  void actor() {
    f.player =
        new StoryDoors46Test.Actor() {
          public int getPlaneId() {
            return 20002;
          }

          public int getFloorId() {
            return 20002001;
          }

          public int getEntryId() {
            return 2000201;
          }
        };
  }

  void source(JsonObject value) {
    var task = f.gson.fromJson(value, MissionTaskInfo.class);
    task.retainSourceDefinition(value);
    GameplayDefinitions.subs.get(100020104).setTask(task);
  }

  void freshFloor() {
    floor =
        f.gson.fromJson(
            records.get("Config/LevelOutput/RuntimeFloor/P20002_F20002001.json"), FloorInfo.class);
    for (int id : new int[] {3, 4}) {
      var group =
          f.gson.fromJson(
              records.get(
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G"
                      + id
                      + ".json"),
              GroupInfo.class);
      group.setId(id);
      // Isolate the actual door, controller, an unrelated later controller, and authored hint
      // trigger.
      group
          .getPropList()
          .removeIf(
              p ->
                  !(id == 3 && Set.of(300001, 300002, 300006).contains(p.getID())
                      || id == 4 && p.getID() == 300012));
      group.getNPCList().clear();
      group.getMonsterList().clear();
      floor.getGroups().put(id, group);
    }
    floor.onLoad();
    GameData.getFloorInfos().put(FLOOR, floor);
  }

  void scene() {
    f.player.scene = new Scene(f.player, plane, 20002001);
    f.player.scene.setEntryId(2000201);
  }

  EntityProp prop(int instance) {
    return f.player.scene.getEntitiesByGroup(EntityProp.class, 3).stream()
        .filter(p -> p.getInstId() == instance)
        .findFirst()
        .orElseThrow();
  }

  void ready() {
    f.accepted(100020104);
    scene();
  }

  ScenePropTransitions.Result open() {
    return ScenePropTransitions.interact(f.player, 1010, prop(300002).getEntityId());
  }

  void reload() {
    var state = f.gson.fromJson(f.gson.toJson(f.player.getGameplay()), GameplayState.class);
    actor();
    f.player.getGameplay().getMainMissions().putAll(state.getMainMissions());
    f.player.getGameplay().getSubMissions().putAll(state.getSubMissions());
    f.player.getGameplay().scenePropStates(state.getScenePropStates());
    freshFloor();
    scene();
  }

  @AfterEach
  void cleanup() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    f.cleanup();
  }

  @Test
  void acceptedGoalEnablesOnlyItsConsoleAndInteractionOpensDoorThenAdvances() {
    ready();
    assertEquals(
        PropState.Closed,
        prop(300002).getState(),
        "The accepted target must offer the open interaction");
    assertEquals(
        PropState.Closed, prop(300001).getState(), "Enabling the console must not open the door");
    assertEquals(PropState.Locked, prop(300006).getState());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100020104).getPhase());
    assertEquals(Retcode.SUCC, open().retcode());
    assertEquals(PropState.Open, prop(300001).getState());
    assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(100020104).getPhase());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100020105).getPhase());
    assertEquals(PropState.Locked, prop(300006).getState());
  }

  @Test
  void enabledAndOpenedStatesSurviveReloadAndCannotRepeatInteraction() {
    ready();
    reload();
    assertEquals(PropState.Closed, prop(300002).getState());
    assertEquals(PropState.Closed, prop(300001).getState());
    assertEquals(Retcode.SUCC, open().retcode());
    var receipts = f.gson.toJson(f.player.getGameplay().getScenePropStates());
    reload();
    assertEquals(PropState.Open, prop(300002).getState());
    assertEquals(PropState.Open, prop(300001).getState());
    assertNotEquals(Retcode.SUCC, open().retcode());
    assertEquals(receipts, f.gson.toJson(f.player.getGameplay().getScenePropStates()));
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100020105).getPhase());
  }

  @Test
  void durableReceiptRecoversInterruptedMissionCallback() {
    ready();
    assertEquals(Retcode.SUCC, open().retcode());
    f.accepted(100020104);
    f.player.getGameplay().getSubMissions().remove(100020105);
    reload();
    f.player.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(100020104).getPhase());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100020105).getPhase());
  }

  @Test
  void failedSaveLeavesBothClosedAndGoalPending() {
    ready();
    var before = f.gson.toJson(f.player.getGameplay());
    f.player.packets.clear();
    f.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, open().retcode());
    assertEquals(before, f.gson.toJson(f.player.getGameplay()));
    assertEquals(PropState.Closed, prop(300002).getState());
    assertEquals(PropState.Closed, prop(300001).getState());
    assertTrue(f.player.packets.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "cancelled",
        "cancelled-main",
        "dynamic-target",
        "missing-target",
        "wrong-signal",
        "extra-effect",
        "conditional-enable",
        "duplicate-enable",
        "extra-init"
      })
  void incompleteOrConditionalScriptCannotUnlockDoor(String variant) {
    f.accepted(100020104);
    var changed = script.deepCopy();
    var sequences = changed.getAsJsonArray("OnStartSequece");
    var trigger =
        sequences.get(0).getAsJsonObject().getAsJsonArray("TaskList").get(0).getAsJsonObject();
    var id =
        trigger.getAsJsonObject("TargetType").getAsJsonObject("SinglePropID").getAsJsonObject("ID");
    switch (variant) {
      case "missing" -> f.player.getGameplay().getSubMissions().remove(100020104);
      case "cancelled" -> f.player.getGameplay().getCancelledSubs().add(100020104);
      case "cancelled-main" -> f.player.getGameplay().getCancelledMissions().add(1000201);
      case "dynamic-target" -> id.addProperty("IsDynamic", true);
      case "missing-target" -> id.getAsJsonObject("FixedValue").addProperty("Value", 399999);
      case "wrong-signal" ->
          sequences
              .get(1)
              .getAsJsonObject()
              .getAsJsonArray("TaskList")
              .get(0)
              .getAsJsonObject()
              .getAsJsonObject("CustomString")
              .addProperty("Value", "unrelated");
      case "extra-effect" ->
          trigger
              .getAsJsonArray("OnTriggerEnter")
              .add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.ChangePropState\"}"));
      case "conditional-enable" ->
          trigger
              .getAsJsonArray("OnTriggerEnter")
              .add(sequences.remove(2).getAsJsonObject().getAsJsonArray("TaskList").get(0));
      case "duplicate-enable" -> sequences.add(sequences.get(2).deepCopy());
      case "extra-init" -> changed.getAsJsonArray("OnInitSequece").add(sequences.get(2).deepCopy());
    }
    source(changed);
    scene();
    assertEquals(PropState.Locked, prop(300002).getState());
    assertNotEquals(Retcode.SUCC, open().retcode());
    assertEquals(PropState.Closed, prop(300001).getState());
    assertTrue(f.player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }
}
