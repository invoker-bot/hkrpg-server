package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Original later-map groups; only histories and explicitly named mutations are synthetic. */
class DormantSceneFollowup46Test {
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, FloorInfo> floors = new HashMap<>();
  final Map<Integer, MazePlaneExcel> planes = new HashMap<>();
  final Map<Integer, Map<Integer, GroupInfo>> groups = new HashMap<>();
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  final List<PropExcel> oldProps = new ArrayList<>();
  final List<NpcExcel> oldNpcs = new ArrayList<>();
  JsonObject records;
  Actor player;

  static class Actor extends CheapRoomDreamTest46Test.Actor {
    int floor;
    public int getPlaneId() { return floor / 1000; }
    public int getFloorId() { return floor; }
  }

  @BeforeEach
  void prepare() throws Exception {
    oldFloors.putAll(GameData.getFloorInfos());
    oldProps.addAll(GameData.getPropExcelMap().values());
    oldNpcs.addAll(GameData.getNpcExcelMap().values());
    try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/dormant-scene-followup-4.6.json")), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    for (var entry : records.entrySet()) for (var row : entry.getValue().getAsJsonArray()) {
      String path = entry.getKey();
      if (path.contains("/SharedRuntimeGroup/")) {
        var match = java.util.regex.Pattern.compile(".*_F([0-9]+)_G([0-9]+)\\.json").matcher(path);
        assertTrue(match.matches());
        int floor = Integer.parseInt(match.group(1)), id = Integer.parseInt(match.group(2));
        var group = gson.fromJson(row, GroupInfo.class);
        group.setId(id);
        groups.computeIfAbsent(floor, unused -> new HashMap<>()).put(id, group);
      } else if (path.contains("/RuntimeFloor/")) {
        var floor = gson.fromJson(row, FloorInfo.class);
        floors.put(floor.getFloorID(), floor);
      } else switch (path) {
        case "ExcelOutput/MazePlane.json" -> {
          var plane = gson.fromJson(row, MazePlaneExcel.class);
          planes.put(plane.getId(), plane);
        }
        case "ExcelOutput/MazeProp.json" -> {
          var prop = gson.fromJson(row, PropExcel.class);
          prop.onLoad();
          GameData.getPropExcelMap().add(prop);
        }
        case "ExcelOutput/NPCData.json" ->
            GameData.getNpcExcelMap().add(gson.fromJson(row, NpcExcel.class));
        default -> fail("Unexpected fixture: " + path);
      }
    }
    for (var floor : floors.values()) {
      var definitions = floor.getSimpleGroupList();
      for (int i = 0; i < definitions.size(); i++) {
        var group = groups.get(floor.getFloorID()).get(definitions.get(i).getID());
        if (group != null) floor.registerGroup(i, group);
      }
      floor.onLoad();
      GameData.getFloorInfos().put("P" + floor.getFloorID() / 1000 + "_F" + floor.getFloorID(), floor);
    }
    player = new Actor();
  }

  @AfterEach
  void restore() {
    GameData.getFloorInfos().clear();
    GameData.getFloorInfos().putAll(oldFloors);
    GameData.getPropExcelMap().values().clear();
    oldProps.forEach(GameData.getPropExcelMap()::add);
    GameData.getNpcExcelMap().values().clear();
    oldNpcs.forEach(GameData.getNpcExcelMap()::add);
  }

  Scene scene(int floor) {
    player.floor = floor;
    player.scene = new Scene(player, planes.get(floor / 1000), floor);
    return player.scene;
  }

  void absent(Scene scene, int id) {
    assertFalse(scene.getLoadedGroups().contains(id), "Dormant group registered: " + id);
    assertTrue(scene.getEntities().values().stream().noneMatch(e -> e.getGroupId() == id));
  }

  @ParameterizedTest
  @CsvSource({"10002001,11,4", "10101003,4,1", "10101004,15,1", "10202004,4,1", "80112001,11,1"})
  void originalDormantTestsCannotSpawnOnEntryDirectLoadRefreshOrReentry(int floor, int id, int keep)
      throws Exception {
    for (int i = 0; i < 2; i++) {
      var scene = scene(floor);
      absent(scene, id);
      assertTrue(scene.getLoadedGroups().contains(keep), "Normal room group must remain");
      assertFalse(player.getMissionManager().shouldLoadGroup(groups.get(floor).get(id), scene));
      scene.loadGroup(id);
      scene.loadGroup(groups.get(floor).get(id));
      scene.refreshMissionGroups();
      absent(scene, id);
      var bridge = ProtocolBridge.forVersion("4.6.0");
      var wire = bridge.clientSchema().parse("SceneInfo",
          bridge.translate("SceneInfo", scene.toProto().toByteArray(), true));
      for (var raw : (List<?>) wire.getField(wire.getDescriptorForType().findFieldByName("entity_group_list"))) {
        var group = (DynamicMessage) raw;
        assertNotEquals(id, group.getField(group.getDescriptorForType().findFieldByName("group_id")));
      }
    }
    assertEquals(0, player.saves);
    assertTrue(player.getGameplay().getSubMissions().isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"10002001,11", "10101003,4", "10101004,15", "10202004,4", "80112001,11"})
  void refreshRetiresOldTestRegistrationsAndPublishesWholeGroupUnload(int floor, int id) {
    var scene = scene(floor);
    var group = groups.get(floor).get(id);
    scene.getLoadedGroups().add(id); // Synthetic state left by the previous loader.
    for (var info : group.getPropList())
      scene.addEntity(scene.getEntityLoader().loadProp(scene, group, info), false);
    for (var info : group.getNPCList())
      scene.addEntity(scene.getEntityLoader().loadNpc(scene, group, info), false);
    scene.toProto();
    player.packets.clear();
    scene.refreshMissionGroups();
    absent(scene, id);
    boolean unloaded = false;
    for (var packet : player.packets)
      if (packet.getData() instanceof emu.lunarcore.proto.SceneGroupRefreshScNotifyOuterClass
          .SceneGroupRefreshScNotify refresh)
        for (var update : refresh.getGroupRefreshInfo())
          if (update.getGroupId() == id && update.getGroupRefreshTypeValue() == 2) unloaded = true;
    assertTrue(unloaded);
    int count = player.packets.size();
    scene.refreshMissionGroups();
    assertEquals(count, player.packets.size());
    assertEquals(0, player.saves);
  }

  @ParameterizedTest
  @CsvSource({"10002001,11", "10101003,4", "10101004,15", "10202004,4", "80112001,11"})
  void anExplicitlyChangedSourceIsNotSilentlyMatchedToTheFrozenTest(int floor, int id) {
    String path = "Config/LevelOutput/SharedRuntimeGroup/Groups_P" + floor / 1000 + "_F" + floor
        + "/LevelGroup_P" + floor / 1000 + "_F" + floor + "_G" + id + ".json";
    var raw = records.getAsJsonArray(path).get(0).getAsJsonObject().deepCopy();
    raw.addProperty("LoadOnInitial", true); // Explicit synthetic source mutation.
    var changed = gson.fromJson(raw, GroupInfo.class);
    changed.setId(id);
    assertTrue(DebugGroupConditions.allowed(changed, scene(floor)));
  }

  @ParameterizedTest
  @CsvSource({"10102003,15", "10102004,1", "20004001,41", "20413001,44", "20001001,36"})
  void originalTestNamedNpcElevatorAndPuzzleGroupsKeepTheirExistingRules(int floor, int id) {
    var scope = scene(10101004);
    assertFalse(DebugGroupConditions.tracks(groups.get(floor).get(id), scope));
    assertTrue(DebugGroupConditions.allowed(groups.get(floor).get(id), scope));
  }

  @Test
  void authoredAtmosphereCannotBeForcedBeforeItsPrerequisiteOrAfterRevocation() {
    int floor = 10301001, id = 226, goal = 103040228;
    var scene = scene(floor);
    absent(scene, id);
    scene.loadGroup(id);
    absent(scene, id);
    var finished = new QuestState(goal);
    finished.advance(1, 1, 100);
    player.getGameplay().getSubMissions().put(goal, finished); // Synthetic completion history.
    scene.refreshMissionGroups();
    assertTrue(scene.getLoadedGroups().contains(id));
    player.getGameplay().getCancelledSubs().add(goal);
    scene.refreshMissionGroups();
    absent(scene, id);
    scene.loadGroup(id);
    absent(scene, id);
    assertEquals(0, player.saves);
  }
}
