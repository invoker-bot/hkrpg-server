package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual five original chapter transfers; prerequisites are fixtures, scenes and wire entry are real. */
class StorySceneTransfer46Test {
  final OpeningSceneTransfer46Test f = new OpeningSceneTransfer46Test();
  JsonObject records;
  JsonArray cases;
  MissionInfo.SubMissionInfo sub;
  int entry, group, anchor;

  @BeforeEach void prepare() throws Exception {
    f.prepare();
    try (var input = getClass().getResourceAsStream("/fixtures/story-transfers-4.6.json")) {
      var fixture = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
      records = fixture.getAsJsonObject("records"); cases = fixture.getAsJsonArray("cases");
    }
  }

  @AfterEach void cleanup() { f.restore(); }

  void setup(int id) throws Exception {
    var selected = cases.asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row -> row.get("sub").getAsInt() == id).findFirst().orElseThrow();
    int main = selected.get("main").getAsInt();
    entry = selected.get("entry").getAsInt(); group = selected.get("group").getAsInt(); anchor = selected.get("anchor").getAsInt();
    GameplayDefinitions.subs.clear(); GameData.getMissionInfos().clear();
    f.actor.getGameplay().getMainMissions().clear(); f.actor.getGameplay().getSubMissions().clear();
    GameData.getMainMissionExcelMap().values().clear();
    for (var row : records.getAsJsonArray("ExcelOutput/MapEntrance.json"))
      GameData.getMapEntranceExcelMap().add(f.gson.fromJson(row, MapEntranceExcel.class));
    for (var row : records.getAsJsonArray("ExcelOutput/MazePlane.json"))
      GameData.getMazePlaneExcelMap().add(f.gson.fromJson(row, MazePlaneExcel.class));
    GameData.getFloorInfos().clear();
    for (var e : records.entrySet()) if (e.getKey().contains("/RuntimeFloor/")) {
      var floor = f.gson.fromJson(e.getValue(), FloorInfo.class);
      String key = e.getKey().substring(e.getKey().lastIndexOf('/') + 1).replace(".json", "");
      for (var g : records.entrySet()) if (g.getKey().contains("/Groups_" + key + "/")) {
        var definition = f.gson.fromJson(g.getValue(), GroupInfo.class);
        definition.setId(Integer.parseInt(g.getKey().replaceAll(".*_G([0-9]+)\\.json", "$1")));
        definition.getPropList().clear(); definition.getNPCList().clear(); definition.getMonsterList().clear();
        floor.getGroups().put(definition.getId(), definition);
      }
      OpeningSceneTransfer46Test.field(floor, "loaded", true);
      GameData.getFloorInfos().put(key, floor);
    }
    String base = "Config/Level/Mission/" + main + "/MissionInfo_" + main;
    var graphJson = records.getAsJsonObject(base + ".json").deepCopy();
    graphJson.add("SubMissionList", records.getAsJsonObject(base + ".partial.json").get("SubMissionList"));
    var graph = f.gson.fromJson(graphJson, MissionInfo.class);
    sub = Arrays.stream(graph.getSubMissionList()).filter(s -> s.getId() == id).findFirst().orElseThrow();
    assertTrue(f.actor.loadScene(sub.getLevelPlaneID(), sub.getLevelFloorID(), sub.getLevelPlaneID() * 100 + 1, new Position(), new Position(), false));
    GameData.getMissionInfos().put(main, graph);
    for (var row : records.getAsJsonArray("ExcelOutput/MainMission.json")) {
      var config = f.gson.fromJson(row, MainMissionExcel.class);
      if (config.getId() == main) GameData.getMainMissionExcelMap().add(config);
    }
    // Entrance prerequisites can belong to an earlier chapter. Retain their original identity,
    // rather than treating an unconfigured saved id as proof that a prerequisite is complete.
    for (var record : records.entrySet()) if (record.getKey().endsWith(".partial.json")) {
      var prerequisites = f.gson.fromJson(record.getValue(), MissionInfo.class);
      for (var child : prerequisites.getSubMissionList()) GameplayDefinitions.subs.put(child.getId(), child);
    }
    for (var child : graph.getSubMissionList()) GameplayDefinitions.subs.put(child.getId(), child);
    var originalSub = records.getAsJsonObject(base + ".partial.json").getAsJsonArray("SubMissionList").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(s -> s.get("ID").getAsInt() == id).findFirst().orElseThrow();
    var source = records.get(originalSub.get("MissionJsonPath").getAsString());
    var task = f.gson.fromJson(source, MissionTaskInfo.class); task.retainSourceDefinition(source); sub.setTask(task);
    f.actor.getGameplay().getMainMissions().put(main, new QuestState(main));
    for (int required : GameData.getMapEntranceExcelMap().get(entry).getFinishMainMissionList())
      if (required != main) f.actor.getGameplay().getMainMissions().put(required, done(required));
    for (int required : GameData.getMapEntranceExcelMap().get(entry).getFinishSubMissionList())
      if (required != id) f.actor.getGameplay().getSubMissions().put(required, done(required));
    for (int previous : sub.getTakeParamIntList()) f.actor.getGameplay().getSubMissions().put(previous, done(previous));
    f.actor.getGameplay().getSubMissions().put(id, new QuestState(id));
    f.bind();
  }

  static QuestState done(int id) { var state = new QuestState(id); state.advance(1, 1, 100); return state; }
  void arrived() {
    var destination = GameData.getMapEntranceExcelMap().get(entry);
    assertEquals(destination.getFloorID(), f.actor.getFloorId());
    assertEquals(GameData.getFloorInfo(destination.getPlaneID(), destination.getFloorID()).getAnchorInfo(group, anchor).getPos(),
        f.actor.getPos(), "Original story anchor must replace the generic entrance anchor");
    assertEquals(QuestState.Phase.FINISHED, f.actor.getMissionManager().sub(sub.getId()).getPhase());
  }

  @ParameterizedTest @ValueSource(ints = {100010121, 100020118, 100030204, 100040113, 100050102})
  void originalChapterTransferUsesItsAuthoredAnchor(int id) throws Exception {
    setup(id); f.enter(entry, 0, Retcode.SUCC); arrived();
  }

  @ParameterizedTest @ValueSource(ints = {100010121, 100020118, 100030204, 100040113, 100050102})
  void interruptedChapterTransferResumesAtItsAuthoredAnchor(int id) throws Exception {
    setup(id);
    assertTrue(MissionSceneEntrances.resumeOnLogin(f.actor, MissionSceneEntrances.captureLogin(f.actor)));
    arrived();
    int saves = f.actor.saves;
    assertFalse(MissionSceneEntrances.resumeOnLogin(f.actor, MissionSceneEntrances.captureLogin(f.actor)));
    assertEquals(saves, f.actor.saves);
  }

  @ParameterizedTest @ValueSource(ints = {100040113, 100050102})
  void missingStoryAnchorNeverFallsBackToTheGenericMapSpawn(int id) throws Exception {
    setup(id);
    var destination = GameData.getMapEntranceExcelMap().get(entry);
    GameData.getFloorInfo(destination.getPlaneID(), destination.getFloorID()).getGroups()
        .get(group).getAnchorList().clear();
    var scene = f.actor.getScene();
    var before = f.gson.toJson(f.actor.getGameplay());
    int saves = f.actor.saves;
    f.enter(entry, 0, Retcode.SCENE_ENTRY_ID_NOT_MATCH);
    assertFalse(MissionSceneEntrances.resumeOnLogin(f.actor, MissionSceneEntrances.captureLogin(f.actor)));
    assertSame(scene, f.actor.getScene());
    assertEquals(before, f.gson.toJson(f.actor.getGameplay()));
    assertEquals(saves, f.actor.saves);
  }

  @ParameterizedTest @ValueSource(ints = {100040113, 100050102})
  void interruptedStoryTransferCannotSkipItsPreviousDialogue(int id) throws Exception {
    setup(id);
    int previous = sub.getTakeParamIntList()[0];
    f.actor.getGameplay().getSubMissions().put(previous, new QuestState(previous));
    var scene = f.actor.getScene();
    var before = f.gson.toJson(f.actor.getGameplay());
    assertFalse(MissionSceneEntrances.resumeOnLogin(f.actor, MissionSceneEntrances.captureLogin(f.actor)));
    assertSame(scene, f.actor.getScene());
    assertEquals(before, f.gson.toJson(f.actor.getGameplay()));
  }

  @ParameterizedTest @ValueSource(ints = {100040113, 100050102})
  void failedStoryLocationSaveKeepsThePendingArrivalAndCanRetry(int id) throws Exception {
    setup(id);
    var origin = MissionSceneEntrances.captureLogin(f.actor);
    int oldFloor = f.actor.getFloorId();
    var before = f.gson.toJson(f.actor.getGameplay());
    f.actor.failSave = true;
    assertThrows(IllegalStateException.class, () -> MissionSceneEntrances.resumeOnLogin(f.actor, origin));
    assertEquals(oldFloor, f.actor.getFloorId());
    assertEquals(oldFloor, f.actor.getScene().getFloorId());
    assertEquals(before, f.gson.toJson(f.actor.getGameplay()));
    f.actor.failSave = false;
    assertTrue(MissionSceneEntrances.resumeOnLogin(f.actor, origin));
    arrived();
  }
}
