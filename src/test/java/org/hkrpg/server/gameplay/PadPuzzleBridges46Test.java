package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Original storage-zone puzzle and bridge placements; mission history is an isolated fixture. */
class PadPuzzleBridges46Test {
  final StoryMechanisms46Test f = new StoryMechanisms46Test();

  @BeforeEach void setup() throws Exception {
    f.prepare();
    JsonObject records;
    try (var reader = new InputStreamReader(
        getClass().getResourceAsStream("/fixtures/pad-puzzle-bridges-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    for (var entry : records.entrySet())
      if (entry.getKey().contains("/SharedRuntimeGroup/") || entry.getKey().endsWith("P20004_F20004001.json"))
        f.records.add(entry.getKey(), entry.getValue());
    for (var row : records.getAsJsonArray("ExcelOutput/MazePlane.json"))
      f.records.getAsJsonArray("ExcelOutput/MazePlane.json").add(row);
    f.oldFloors.put("P20004_F20004001", GameData.getFloorInfos().get("P20004_F20004001"));
    for (var raw : records.getAsJsonArray("ExcelOutput/MazeProp.json")) {
      var row = raw.getAsJsonObject().deepCopy();
      if (row.has("PropName") && row.get("PropName").isJsonObject())
        row.add("PropName", row.getAsJsonObject("PropName").get("Hash"));
      var prop = ResourceLoader.getGson().fromJson(row, PropExcel.class);
      prop.onLoad();
      GameData.getPropExcelMap().add(prop);
    }
    var prerequisite = new QuestState(1000401);
    prerequisite.advance(1, 1, 100);
    f.player.getGameplay().getMainMissions().put(1000401, prerequisite);
    f.scene();
  }

  @AfterEach void cleanup() { f.cleanup(); }

  @ParameterizedTest @CsvSource({"26,300039", "32,300040", "33,300041"})
  void completingTheOriginalPuzzleConnectsOnlyItsBridgeAndSurvivesReload(int group, int bridge)
      throws Exception {
    assertEquals(PropState.Closed, f.prop(3, bridge).getState());
    assertEquals(Retcode.SUCC, f.use(group, 300002, 1010));
    assertEquals(PropState.Open, f.prop(group, 300002).getState());
    assertEquals(PropState.Open, f.prop(3, bridge).getState());
    int saves = f.player.saves;
    assertEquals(Retcode.SUCC, f.use(3, bridge, 1010));
    assertEquals(saves, f.player.saves, "Late original finish callback is idempotent");
    for (int other : new int[]{300039, 300040, 300041})
      if (other != bridge) assertEquals(PropState.Closed, f.prop(3, other).getState());
    f.reload();
    assertEquals(PropState.Open, f.prop(group, 300002).getState());
    assertEquals(PropState.Open, f.prop(3, bridge).getState());
  }

  private void savedPuzzleOnly(String source) {
    savedPuzzleOnly(20002, 26, 300002, source);
  }

  private void savedPuzzleOnly(int plane, int group, int instance, String source) {
    var record = new ScenePropState.Snapshot(plane, plane * 1000 + 1, 0, 0, group, instance, 100,
        PropState.Open.getVal(), source, "");
    f.player.getGameplay().scenePropStates(f.player.getGameplay().getScenePropStates().with(List.of(record)));
  }

  @Test void existingCompletedPuzzleRestoresItsMissingBridgeWithoutChangingOtherProgress()
      throws Exception {
    var old = f.f.gson.fromJson(f.f.gson.toJson(f.info(26, 300002)),
        emu.lunarcore.data.config.PropInfo.class);
    old.setValueSource(null);
    var oldSource = PropTimelines.source(old);
    assertEquals("a18f51e673a504ff139f0baefff925f115369baf335d04e8c3601c62c39f5edd", oldSource);
    savedPuzzleOnly(oldSource);
    var before = f.f.gson.toJson(f.player.getGameplay());
    f.reload();
    assertEquals(PropState.Open, f.prop(26, 300002).getState());
    assertEquals(PropState.Open, f.prop(3, 300039).getState());
    assertEquals(PropState.Closed, f.prop(3, 300040).getState());
    assertEquals(before, f.f.gson.toJson(f.player.getGameplay()));
  }

  @Test void forgedOrUnfinishedPuzzleRecordCannotOpenTheBridge() throws Exception {
    savedPuzzleOnly("0".repeat(64));
    f.reload();
    assertEquals(PropState.Closed, f.prop(26, 300002).getState());
    assertEquals(PropState.Closed, f.prop(3, 300039).getState());
  }

  @Test void changedBindingCannotReuseTheLegacyReceipt() throws Exception {
    var old = f.f.gson.fromJson(f.f.gson.toJson(f.info(26, 300002)),
        emu.lunarcore.data.config.PropInfo.class);
    old.setValueSource(null);
    savedPuzzleOnly(PropTimelines.source(old));
    var puzzle = f.records.getAsJsonObject("Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G26.json")
        .getAsJsonArray("PropList").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row -> row.get("ID").getAsInt() == 300002).findFirst().orElseThrow();
    puzzle.getAsJsonObject("ValueSource").getAsJsonArray("Values").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(row -> row.get("Key").getAsString().equals("Bridge"))
        .findFirst().orElseThrow().addProperty("Value", "3,300040");
    f.reload();
    assertEquals(PropState.Closed, f.prop(26, 300002).getState());
    assertEquals(PropState.Closed, f.prop(3, 300039).getState());
    assertEquals(PropState.Closed, f.prop(3, 300040).getState());
  }

  @Test void temporarilyUnloadedBridgeIsRestoredFromTheSavedCompletion() throws Exception {
    f.player.scene.getLoadedGroups().remove(3);
    assertEquals(Retcode.SUCC, f.use(26, 300002, 1010));
    assertEquals(PropState.Open, f.prop(26, 300002).getState());
    f.reload();
    assertEquals(PropState.Open, f.prop(3, 300039).getState());
  }

  @Test void anUnloadedPuzzleGroupDoesNotEraseItsPreviouslyCompletedBridge() throws Exception {
    savedPuzzleOnly(PropTimelines.source(f.info(26, 300002)));
    f.player.getGameplay().getMainMissions().put(1000401, new QuestState(1000401));
    f.reload();
    assertFalse(f.player.scene.getLoadedGroups().contains(26));
    assertEquals(PropState.Open, f.prop(3, 300039).getState());
  }

  private void supplyZone() {
    f.planeId = 20003;
    f.scene();
  }

  private void acceptRepeat(int main, int prerequisite) {
    f.player.getGameplay().getMainMissions().put(main, new QuestState(main));
    if (prerequisite != 0) {
      var definition = new MissionInfo.SubMissionInfo();
      definition.setId(prerequisite);
      definition.setMainMissionID(prerequisite / 100);
      GameplayDefinitions.subs.put(prerequisite, definition);
      f.f.completed(prerequisite);
    }
  }

  @ParameterizedTest @CsvSource({
      "2000302,0,32,41", "2000303,200030301,41,42", "2000304,200030401,42,43"})
  void nextBoardHidesThePreviousBridgeOnMissionRefreshAndReload(
      int main, int prerequisite, int previousBoard, int nextBoard) throws Exception {
    supplyZone();
    var previous = f.info(previousBoard, 300002);
    savedPuzzleOnly(20003, previousBoard, 300002, PropTimelines.source(previous));
    var bridge = f.info(1, 300025);
    var states = f.player.getGameplay().getScenePropStates().with(List.of(
        new ScenePropState.Snapshot(20003, 20003001, 0, 0, 1, 300025, bridge.getPropID(),
            PropState.Open.getVal(), PropTimelines.source(bridge), "")));
    f.player.getGameplay().scenePropStates(states);
    f.construct();
    assertEquals(PropState.Open, f.prop(1, 300025).getState());
    f.player.scene.toProto();
    f.player.packets.clear();

    acceptRepeat(main, prerequisite);
    f.player.scene.refreshMissionGroups();

    assertEquals(PropState.Closed, f.prop(nextBoard, 300002).getState());
    assertEquals(PropState.Closed, f.prop(1, 300025).getState(), "Previous bridge covers the new tiles");
    assertEquals(List.of(PropState.Closed.getVal()), bridgeRefreshes(1, 300025));
    f.player.packets.clear();
    int saves = f.player.saves;
    assertEquals(Retcode.SUCC, f.use(1, 300025, 1010), "The bridge callback may arrive on its own");
    assertEquals(PropState.Closed, f.prop(1, 300025).getState());
    assertEquals(saves, f.player.saves);
    f.player.scene.refreshMissionGroups();
    assertTrue(bridgeRefreshes(1, 300025).isEmpty());
    assertSame(states, f.player.getGameplay().getScenePropStates());

    f.reload();
    assertEquals(PropState.Closed, f.prop(1, 300025).getState());
    assertEquals(PropState.Closed, f.prop(nextBoard, 300002).getState());
    assertEquals(PropState.Open.getVal(), f.player.getGameplay().getScenePropStates()
        .get(PropTimelines.key(f.player.scene, previousBoard, 300002)).getState());

    f.player.getGameplay().getCancelledMissions().add(main);
    f.player.scene.refreshMissionGroups();
    assertEquals(PropState.Open, f.prop(1, 300025).getState());
  }

  @Test void pendingRepeatResetAdvancesToTheOriginalPuzzleObjective() {
    supplyZone();
    assertEquals(Retcode.SUCC, f.use(32, 300002, 1010));
    assertEquals(PropState.Open, f.prop(1, 300025).getState());
    loadSecondRoundObjectives();
    acceptRepeat(2000302, 0);

    f.player.getMissionManager().refresh();

    assertTrue(MissionStateRecords.subCompleted(f.player, 200030200));
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(200030201).getPhase());
    assertEquals(PropState.Closed, f.prop(1, 300025).getState());
    assertEquals(PropState.Closed, f.prop(41, 300002).getState());
  }

  static void loadSecondRoundObjectives() {
    var gson = ResourceLoader.getGson();
    var reset = JsonParser.parseString("""
        {"ID":200030200,"MainMissionID":2000302,
         "MissionJsonPath":"Config/Level/Mission/2000301/Mission_200030200.json",
         "LevelPlaneID":20003,"LevelFloorID":20003001,"TakeType":"Auto",
         "MazePlaneID":20003,"MazeFloorID":20003001,"FinishType":"PropState",
         "ParamType":"Equal","ParamInt1":1,"ParamInt2":300025,"ParamItemList":[],"Progress":1,
         "WayPointType":"Prop","WayPointFloorID":20003001,"WayPointGroupID":11,"WayPointEntityID":300002}
        """);
    var graph = JsonParser.parseString("""
        {"OnInitSequece":[],"OnStartSequece":[{"TaskList":[
         {"$type":"RPG.GameCore.ChangePropState"}]}],"Type":"Mission"}
        """);
    var stage = gson.fromJson(reset, MissionInfo.SubMissionInfo.class);
    var task = gson.fromJson(graph, MissionTaskInfo.class);
    task.retainSourceDefinition(graph);
    stage.setTask(task);
    GameplayDefinitions.subs.put(stage.getId(), stage);
    var objective = gson.fromJson("""
        {"ID":200030201,"MainMissionID":2000302,"TakeType":"AnySequence","TakeParamIntList":[200030200],
         "LevelPlaneID":20003,"LevelFloorID":20003001,"MazePlaneID":20003,"MazeFloorID":20003001,
         "FinishType":"PropState","ParamType":"Equal","ParamInt1":18,"ParamInt2":300010,"ParamInt3":12,
         "ParamItemList":[],"Progress":1,"WayPointType":"Prop","WayPointFloorID":20003001,
         "WayPointGroupID":11,"WayPointEntityID":300002}
        """, MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(objective.getId(), objective);
  }

  @ParameterizedTest @CsvSource({
      "20002,32,300002,3,300040", "20002,33,300002,3,300041",
      "20003,32,300002,1,300025", "20003,41,300002,1,300025",
      "20003,42,300002,1,300025", "20003,43,300002,1,300025",
      "20004,5,300006,5,300001"})
  void eachOtherAuditedPlacementRecoversItsOwnLegacyCompletion(
      int plane, int group, int instance, int bridgeGroup, int bridge) throws Exception {
    f.planeId = plane;
    f.scene();
    var old = f.f.gson.fromJson(f.f.gson.toJson(f.info(group, instance)),
        emu.lunarcore.data.config.PropInfo.class);
    old.setValueSource(null);
    savedPuzzleOnly(plane, group, instance, PropTimelines.source(old));
    var before = f.f.gson.toJson(f.player.getGameplay());
    f.reload();
    assertEquals(PropState.Open, f.prop(bridgeGroup, bridge).getState());
    assertEquals(before, f.f.gson.toJson(f.player.getGameplay()));
  }

  @Test void completedRoadSurvivesTwoH2DatabaseRestarts(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    assertEquals(Retcode.SUCC, f.use(26, 300002, 1010));
    for (int cycle = 0; cycle < 3; cycle++) {
      var server = new de.bwaldvogel.mongo.MongoServer(
          new de.bwaldvogel.mongo.backend.h2.H2Backend(directory.resolve("player.mv").toString()));
      var address = server.bind();
      try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
        var codecs = org.bson.codecs.configuration.CodecRegistries.fromCodecs(
            new emu.lunarcore.database.codecs.IntSetCodec(), new emu.lunarcore.database.codecs.IntListCodec(),
            new emu.lunarcore.database.codecs.Int2IntMapCodec());
        var options = dev.morphia.mapping.MapperOptions.builder().storeEmpties(true).storeNulls(false)
            .codecProvider(codecs).build();
        var store = dev.morphia.Morphia.createDatastore(client, "pad_bridge", options);
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store.getMapper().map(emu.lunarcore.game.player.Player.class, QuestState.class, emu.lunarcore.util.Position.class);
        if (cycle == 0) {
          var document = new emu.lunarcore.game.player.Player();
          MissionTrialProvisioning46Test.set(document, "uid", 19998533);
          MissionTrialProvisioning46Test.set(document, "gameplay", f.player.getGameplay());
          store.save(document);
        } else {
          var loaded = java.util.Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
          MissionTrialProvisioning46Test.set(f.player, "gameplay", loaded.getGameplay());
          f.scene();
          assertEquals(PropState.Open, f.prop(26, 300002).getState());
          assertEquals(PropState.Open, f.prop(3, 300039).getState());
          assertEquals(PropState.Closed, f.prop(3, 300040).getState());
        }
      } finally { server.shutdownNow(); }
    }
  }

  @Test void chestGroupSourceComparisonRetainsTheSamePadBinding(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    f.planeId = 20004;
    f.scene();
    var path = "Config/LevelOutput/SharedRuntimeGroup/Groups_P20004_F20004001/LevelGroup_P20004_F20004001_G5.json";
    var file = directory.resolve(path);
    java.nio.file.Files.createDirectories(file.getParent());
    java.nio.file.Files.writeString(file, f.f.gson.toJson(f.records.get(path)));
    var expected = MapChestSources.group(directory, path, 20004001, 5);
    assertNotNull(expected, "Original chest group must remain verifiable");
    var puzzle = expected.getPropList().stream().filter(p -> p.getID() == 300006).findFirst().orElseThrow();
    assertEquals(PropTimelines.source(f.info(5, 300006)), PropTimelines.source(puzzle));
    var wrongFloor = MapChestSources.group(directory, path, 20003001, 5);
    var wrongPuzzle = wrongFloor.getPropList().stream().filter(p -> p.getID() == 300006).findFirst().orElseThrow();
    assertNotEquals(PropTimelines.source(puzzle), PropTimelines.source(wrongPuzzle));
    var restored = MapChestSources.group(directory, path, 20004001, 5);
    var restoredPuzzle = restored.getPropList().stream().filter(p -> p.getID() == 300006).findFirst().orElseThrow();
    assertEquals(PropTimelines.source(puzzle), PropTimelines.source(restoredPuzzle));
  }

  @Test void completedBridgeIsSentInTheNative46SceneRefresh() throws Exception {
    var scene = f.player.scene;
    scene.toProto(); // Initial scene has been sent; subsequent state changes must notify it.
    f.player.packets.clear();
    assertEquals(Retcode.SUCC, f.use(26, 300002, 1010));
    assertEquals(List.of(PropState.Open.getVal()), bridgeRefreshes(3, 300039));
  }

  private List<Integer> bridgeRefreshes(int groupId, int instance) throws Exception {
    var protocol = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    var states = new java.util.ArrayList<Integer>();
    for (var packet : f.player.packets) {
      if (packet.getCmdId() != emu.lunarcore.server.packet.CmdId.SceneGroupRefreshScNotify) continue;
      var wire = protocol.outbound(packet.getCmdId(), packet.getData().toByteArray());
      var message = protocol.clientSchema().parse("SceneGroupRefreshScNotify", wire.body());
      for (var rawGroup : (List<?>) field(message, "group_refresh_list")) {
        var group = (com.google.protobuf.DynamicMessage) rawGroup;
        if (!Integer.valueOf(groupId).equals(field(group, "group_id"))) continue;
        for (var rawEntity : (List<?>) field(group, "refresh_entity")) {
          var added = (com.google.protobuf.DynamicMessage) field(
              (com.google.protobuf.DynamicMessage) rawEntity, "add_entity");
          if (!Integer.valueOf(instance).equals(field(added, "inst_id"))) continue;
          var prop = (com.google.protobuf.DynamicMessage) field(added, "prop");
          states.add((Integer) field(prop, "prop_state"));
        }
      }
    }
    return states;
  }

  private static Object field(com.google.protobuf.DynamicMessage message, String name) {
    var descriptor = message.getDescriptorForType().findFieldByName(name);
    assertNotNull(descriptor, name);
    return message.getField(descriptor);
  }

  @Test void saveFailureLeavesBothOriginalActorsAndTheJournalUnchanged() {
    var before = f.f.gson.toJson(f.player.getGameplay());
    f.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, f.use(26, 300002, 1010));
    assertEquals(PropState.Closed, f.prop(26, 300002).getState());
    assertEquals(PropState.Closed, f.prop(3, 300039).getState());
    assertEquals(before, f.f.gson.toJson(f.player.getGameplay()));
  }
}
