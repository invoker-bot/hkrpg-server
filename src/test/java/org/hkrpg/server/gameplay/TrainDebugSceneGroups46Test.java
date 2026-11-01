package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.BasePacket;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original debug gates and ordinary props; only the player histories/gate mutations are synthetic. */
class TrainDebugSceneGroups46Test {
  static final String FLOOR = "P10000_F10000003", FLAG = "Utility_IsDebug";
  static final String KEY = "floor:10000003:" + FLAG;
  static final Set<Integer> DEBUG = Set.of(2, 25, 28, 44, 79);
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, GroupInfo> groups = new HashMap<>();
  final List<PropExcel> oldProps = new ArrayList<>();
  FloorInfo floor, oldFloor;
  MazePlaneExcel plane;
  Actor player;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    final List<BasePacket> packets = new ArrayList<>();
    Scene scene;
    int saves;

    Actor() { teams.getCurrentLineup().getAvatars().add(0); }
    public LineupManager getLineupManager() { return teams; }
    public GameAvatar getAvatarById(int id) { return null; }
    public Scene getScene() { return scene; }
    public int getPlaneId() { return 10000; }
    public int getFloorId() { return 10000003; }
    public void save() { saves++; }
    public void sendPacket(BasePacket packet) { packets.add(packet); }
  }

  @BeforeEach
  void prepare() throws Exception {
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    oldProps.addAll(GameData.getPropExcelMap().values());
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/train-debug-scene-groups-4.6.json"), StandardCharsets.UTF_8)) {
      var records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
      for (var entry : records.entrySet()) for (var row : entry.getValue().getAsJsonArray()) {
        String path = entry.getKey();
        if (path.contains("/SharedRuntimeGroup/")) {
          int id = Integer.parseInt(path.replaceAll(".*_G([0-9]+)\\.json", "$1"));
          var group = gson.fromJson(row, GroupInfo.class);
          group.setId(id);
          groups.put(id, group);
        } else switch (path) {
          case "Config/LevelOutput/RuntimeFloor/P10000_F10000003.json" ->
              floor = gson.fromJson(row, FloorInfo.class);
          case "ExcelOutput/MazePlane.json" -> plane = gson.fromJson(row, MazePlaneExcel.class);
          case "ExcelOutput/MazeProp.json" -> {
            var prop = gson.fromJson(row, PropExcel.class);
            prop.onLoad();
            GameData.getPropExcelMap().add(prop);
          }
          default -> fail("Unexpected fixture: " + path);
        }
      }
    }
    var definitions = floor.getSimpleGroupList();
    for (int i = 0; i < definitions.size(); i++) {
      var group = groups.get(definitions.get(i).getID());
      if (group != null) floor.registerGroup(i, group);
    }
    floor.onLoad();
    GameData.getFloorInfos().put(FLOOR, floor);
    player = new Actor();
  }

  @AfterEach
  void restore() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    GameData.getPropExcelMap().values().clear();
    oldProps.forEach(GameData.getPropExcelMap()::add);
  }

  Scene scene(int dimension) {
    player.scene = new Scene(player, plane, 10000003, dimension);
    return player.scene;
  }

  void absent(Scene scene, int group) {
    assertFalse(scene.getLoadedGroups().contains(group), "Debug group registered: " + group);
    assertTrue(scene.getEntities().values().stream().noneMatch(e -> e.getGroupId() == group));
  }

  DynamicMessage nativeScene(Scene scene) throws Exception {
    var bridge = ProtocolBridge.forVersion("4.6.0");
    return bridge.clientSchema().parse("SceneInfo",
        bridge.translate("SceneInfo", scene.toProto().toByteArray(), true));
  }

  Object field(DynamicMessage row, String name) {
    return row.getField(row.getDescriptorForType().findFieldByName(name));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1002, 10001, 1004, 11001, 1005})
  void normalTrainEntryNeverSpawnsTheOriginalDebugGroupsInAnyAuthoredDimension(int dimension)
      throws Exception {
    var scene = scene(dimension);
    for (int group : DEBUG) absent(scene, group);
    var nativeScene = nativeScene(scene);
    for (var raw : (List<?>) field(nativeScene, "entity_group_list"))
      assertFalse(DEBUG.contains((Integer) field((DynamicMessage) raw, "group_id")),
          "Debug prop leaked into the native 4.6 scene");
    assertEquals(0, player.saves);
    assertTrue(player.getGameplay().getStoryValues().isEmpty());
  }

  @Test
  void originalTeleportGraphIsDebugGatedWhileOrdinaryEmptyPropsRemainAvailable() {
    assertEquals(2, groups.get(2).getPropList().size());
    for (var prop : groups.get(2).getPropList()) {
      assertEquals(100, prop.getPropID());
      assertEquals("Config/Level/DesignerBackup/Test_Prop_Teleport.json", prop.getInitLevelGraph());
    }
    assertEquals(0, floor.getExtraDatas(0).stream().filter(v -> FLAG.equals(v.getName()))
        .findFirst().orElseThrow().getDefaultValue());
    var scene = scene(0);
    absent(scene, 2);
    assertEquals(3, scene.getEntities().values().stream().filter(e -> e.getGroupId() == 26).count(),
        "Ordinary train teleports and their empty prop definitions must remain");
    assertTrue(scene.getLoadedGroups().contains(121), "Ordinary blocker group must remain");
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 25, 28, 44, 79})
  void explicitGroupLoadAndMissionAdmissionCannotBypassTheAuthoredDebugGate(int group) {
    var scene = scene(0);
    assertFalse(player.getMissionManager().shouldLoadGroup(groups.get(group), scene));
    scene.loadGroup(group);
    scene.loadGroup(groups.get(group));
    absent(scene, group);
  }

  @Test
  void refreshRetiresAGroupWhenItsSavedDebugFlagIsClearedAndDoesNotReloadIt() {
    player.getGameplay().getStoryValues().put(KEY, 1); // Synthetic debug history only.
    var scene = scene(0);
    for (int group : DEBUG) assertTrue(scene.getLoadedGroups().contains(group));
    player.getGameplay().getStoryValues().put(KEY, 0);
    scene.toProto(); // Mark the scene sent, so unload notifications use the normal path.
    scene.refreshMissionGroups();
    for (int group : DEBUG) absent(scene, group);
    for (int group : DEBUG) scene.loadGroup(group);
    scene.refreshMissionGroups();
    for (int group : DEBUG) absent(scene, group);
    var unloaded = new ArrayList<Integer>();
    for (var packet : player.packets)
      if (packet.getData() instanceof
          emu.lunarcore.proto.SceneGroupRefreshScNotifyOuterClass.SceneGroupRefreshScNotify refresh)
        for (var group : refresh.getGroupRefreshInfo())
          if (group.getGroupRefreshType() == emu.lunarcore.proto.SceneGroupRefreshTypeOuterClass
              .SceneGroupRefreshType.SCENE_GROUP_REFRESH_TYPE_UNLOAD)
            unloaded.add(group.getGroupId());
    assertEquals(5, unloaded.size());
    assertEquals(DEBUG, Set.copyOf(unloaded));
    assertEquals(0, player.saves);
    assertEquals(0, player.getGameplay().getStoryValues().get(KEY));
  }

  @Test
  void rebuildingTheSceneKeepsNormalFlagsAndProgressWithoutEnablingDebug() throws Exception {
    player.getGameplay().getStoryValues().put("floor:10000003:Build_HasBed", 1);
    var before = Map.copyOf(player.getGameplay().getStoryValues());
    for (int i = 0; i < 2; i++) {
      var scene = scene(0);
      for (int group : DEBUG) absent(scene, group);
      var nativeScene = nativeScene(scene);
      boolean found = false;
      for (var raw : (List<?>) field(nativeScene, "floor_saved_data")) {
        var entry = (DynamicMessage) raw;
        if (FLAG.equals(field(entry, "key"))) {
          assertEquals(0, field(entry, "value"));
          found = true;
        }
      }
      assertTrue(found, "The authored debug default must reach the client");
      assertEquals(before, player.getGameplay().getStoryValues());
    }
    assertEquals(0, player.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"And", "Or"})
  void anEnrolledDebugGateKeepsAuthoredAndOrSemanticsForDeclaredSiblingValues(String operation)
      throws Exception {
    var condition = JsonParser.parseString("{\"Operation\":\"" + operation
        + "\",\"Conditions\":[{\"SavedValueName\":\"Utility_IsDebug\",\"Value\":1},"
        + "{\"SavedValueName\":\"Onboarded\"}]}");
    DailyMissionAcceptance46Test.set(groups.get(2), "SavedValueCondition", condition);
    var scene = scene(0);
    assertEquals(operation.equals("Or"), scene.getLoadedGroups().contains(2));
    assertEquals(0, player.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"1\"", "0.5", "null", "true", "2147483648"})
  void invalidDebugExpectationsCannotEnableTestObjects(String value) throws Exception {
    var condition = JsonParser.parseString("{\"Conditions\":[{\"SavedValueName\":"
        + "\"Utility_IsDebug\",\"Value\":" + value + "}]}");
    DailyMissionAcceptance46Test.set(groups.get(2), "SavedValueCondition", condition);
    absent(scene(0), 2);
    assertEquals(0, player.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 2, 2147483647})
  void invalidSavedDebugFlagsCannotEnableTestObjects(int value) {
    player.getGameplay().getStoryValues().put(KEY, value);
    absent(scene(0), 2);
    assertEquals(value, player.getGameplay().getStoryValues().get(KEY));
    assertEquals(0, player.saves);
  }
}
