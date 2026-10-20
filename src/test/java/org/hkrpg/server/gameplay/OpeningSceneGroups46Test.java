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
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.BasePacket;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real Base Zone resources; only player histories and unknown-condition mutations are fixtures. */
class OpeningSceneGroups46Test {
  static final String FLOOR = "P20001_F20001001";
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, GroupInfo> groups = new HashMap<>();
  final List<PropExcel> oldProps = new ArrayList<>();
  final List<NpcMonsterExcel> oldMonsters = new ArrayList<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  FloorInfo oldFloor, floor;
  MazePlaneExcel plane;
  Actor player;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    Scene scene;
    int saves;

    Actor() {
      // The legacy scene serializer requires a lineup slot, but this test needs no avatar.
      teams.getCurrentLineup().getAvatars().add(0);
    }

    public LineupManager getLineupManager() {
      return teams;
    }

    public GameAvatar getAvatarById(int id) {
      return null;
    }

    public Scene getScene() {
      return scene;
    }

    public int getPlaneId() {
      return 20001;
    }

    public int getFloorId() {
      return 20001001;
    }

    public void save() {
      saves++;
    }

    public void sendPacket(BasePacket packet) {}
  }

  @BeforeEach
  void prepare() throws Exception {
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    oldProps.addAll(GameData.getPropExcelMap().values());
    oldMonsters.addAll(GameData.getNpcMonsterExcelMap().values());
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldSubs.putAll(GameplayDefinitions.subs);
    try (var reader =
        new java.io.InputStreamReader(
            getClass().getResourceAsStream("/fixtures/opening-scene-groups-4.6.json"),
            java.nio.charset.StandardCharsets.UTF_8)) {
      var records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
      for (var entry : records.entrySet())
        for (var row : entry.getValue().getAsJsonArray()) {
          String path = entry.getKey();
          if (path.contains("/SharedRuntimeGroup/")) {
            int id = Integer.parseInt(path.replaceAll(".*_G([0-9]+)\\.json", "$1"));
            var group = gson.fromJson(row, GroupInfo.class);
            group.setId(id);
            groups.put(id, group);
          } else
            switch (path) {
              case "Config/LevelOutput/RuntimeFloor/P20001_F20001001.json" ->
                  floor = gson.fromJson(row, FloorInfo.class);
              case "ExcelOutput/MazePlane.json" -> plane = gson.fromJson(row, MazePlaneExcel.class);
              case "ExcelOutput/MazeProp.json" -> {
                var prop = gson.fromJson(row, PropExcel.class);
                prop.onLoad();
                GameData.getPropExcelMap().add(prop);
              }
              case "ExcelOutput/NPCMonsterData.json" ->
                  GameData.getNpcMonsterExcelMap().add(gson.fromJson(row, NpcMonsterExcel.class));
              case "ExcelOutput/MainMission.json" ->
                  GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
              case "Config/Level/Mission/1000101/MissionInfo_1000101.partial.json" -> {
                var sub = gson.fromJson(row, MissionInfo.SubMissionInfo.class);
                GameplayDefinitions.subs.put(sub.getId(), sub);
              }
              default -> fail("Unexpected fixture: " + path);
            }
        }
    }
    groups.forEach((id, group) -> floor.getGroups().put(id.intValue(), group));
    floor.onLoad();
    GameData.getFloorInfos().put(FLOOR, floor);
    player = new Actor();
    player.getGameplay().getMainMissions().put(1000101, new QuestState(1000101));
  }

  @AfterEach
  void restore() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    GameData.getPropExcelMap().values().clear();
    oldProps.forEach(GameData.getPropExcelMap()::add);
    GameData.getNpcMonsterExcelMap().values().clear();
    oldMonsters.forEach(GameData.getNpcMonsterExcelMap()::add);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
  }

  Scene scene() {
    player.scene = new Scene(player, plane, 20001001);
    return player.scene;
  }

  void finish(int id) {
    var state = new QuestState(id);
    state.advance(1, 1, 100);
    player.getGameplay().getMainMissions().put(id, state);
  }

  List<GameEntity> entities(Scene scene, int group) {
    return scene.getEntities().values().stream().filter(e -> e.getGroupId() == group).toList();
  }

  List<DynamicMessage> nativeEntities(Scene scene, int groupId) throws Exception {
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var view =
        bridge
            .clientSchema()
            .parse("SceneInfo", bridge.translate("SceneInfo", scene.toProto().toByteArray(), true));
    var result = new ArrayList<DynamicMessage>();
    for (var raw : (List<?>) field(view, "entity_group_list")) {
      var group = (DynamicMessage) raw;
      if ((Integer) field(group, "group_id") != groupId) continue;
      for (var entity : (List<?>) field(group, "entity_list")) result.add((DynamicMessage) entity);
    }
    return result;
  }

  Object field(DynamicMessage row, String name) {
    return row.getField(row.getDescriptorForType().findFieldByName(name));
  }

  @Test
  void freshOpeningHidesTheOriginalShadowAndLaterTreasuresButKeepsOpeningEffects()
      throws Exception {
    var scene = scene();
    assertNull(groups.get(93).getCategory(), "The original group omits Category");
    assertEquals(4010127, groups.get(93).getLoadCondition().getConditions()[0].getID());
    assertTrue(entities(scene, 93).isEmpty(), "Locked Stagnant Shadow was spawned");
    assertTrue(entities(scene, 6).isEmpty(), "Post-opening treasures were spawned");
    assertFalse(entities(scene, 52).isEmpty(), "Opening door effects must remain");
    assertFalse(entities(scene, 88).isEmpty(), "Opening investigation props must remain");
    assertTrue(scene.getLoadedGroups().contains(2), "Common anchors must remain");
    assertTrue(nativeEntities(scene, 93).isEmpty());
    assertEquals(0, player.saves, "Visibility cannot rewrite player progress");
  }

  @Test
  void directGroupLoadCannotBypassTheOriginalMissionGate() {
    var scene = scene();
    scene.loadGroup(93);
    scene.loadGroup(groups.get(6));
    assertTrue(entities(scene, 93).isEmpty());
    assertFalse(scene.getLoadedGroups().contains(93));
    assertTrue(entities(scene, 6).isEmpty());
  }

  @Test
  void unlockedShadowAppearsOnceOnRefreshAndSurvivesSceneReconstruction() throws Exception {
    var scene = scene();
    assertTrue(entities(scene, 93).isEmpty());
    finish(4010127);
    scene.refreshMissionGroups();
    scene.refreshMissionGroups();
    assertEquals(2, entities(scene, 93).size());
    assertEquals(1, scene.getEntitiesByGroup(EntityMonster.class, 93).size());
    assertEquals(
        1101, scene.getEntitiesByGroup(EntityMonster.class, 93).getFirst().getFarmElementId());
    assertEquals(702, scene.getEntitiesByGroup(EntityProp.class, 93).getFirst().getPropId());
    assertEquals(2, nativeEntities(scene, 93).size());
    assertEquals(2, entities(scene(), 93).size());
  }

  @Test
  void unlockingOnePlayerDoesNotChangeTheSharedResourcesOrAnotherPlayersOpening() {
    finish(4010127);
    finish(1000401);
    var unlocked = scene();
    assertEquals(2, entities(unlocked, 93).size());
    assertFalse(entities(unlocked, 6).isEmpty());
    player = new Actor();
    var fresh = scene();
    assertTrue(entities(fresh, 93).isEmpty());
    assertTrue(entities(fresh, 6).isEmpty());
    assertEquals(2, entities(unlocked, 93).size());
    assertEquals(4010127, groups.get(93).getLoadCondition().getConditions()[0].getID());
  }

  @Test
  void ordinaryWorldGateDoesNotOverrideInstanceGroupSelection() throws Exception {
    DailyMissionAcceptance46Test.set(plane, "planeType", emu.lunarcore.game.enums.PlaneType.Raid);
    var instance = scene();
    assertTrue(instance.getLoadedGroups().contains(93));
    assertFalse(entities(instance, 93).isEmpty());
  }

  @Test
  void emptyOrdinaryConditionPlaceholdersKeepTheExistingInitialLoadBehavior() throws Exception {
    // Several original normal groups serialize both unused sets as {"Conditions":[]}.
    var empty = gson.fromJson("{\"Conditions\":[]}", LoadConditionInfo.class);
    DailyMissionAcceptance46Test.set(groups.get(93), "LoadCondition", empty);
    DailyMissionAcceptance46Test.set(groups.get(93), "UnloadCondition", empty);
    assertEquals(2, entities(scene(), 93).size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"Books", "Bug", "AngryBlock", "ShelfDoor"})
  void taskRefreshAndDirectLoadPreserveTheExistingGroupExclusions(String name) throws Exception {
    DailyMissionAcceptance46Test.set(groups.get(93), "GroupName", name);
    var scene = scene();
    assertTrue(entities(scene, 93).isEmpty());
    finish(4010127);
    scene.refreshMissionGroups();
    scene.loadGroup(93);
    assertTrue(entities(scene, 93).isEmpty());
    assertFalse(scene.getLoadedGroups().contains(93));
  }

  @Test
  void changingTheGateRemovesBothEntitiesAndStaleActivationFails() throws Exception {
    finish(4010127);
    var scene = scene();
    assertEquals(2, entities(scene, 93).size());
    int entity = scene.getEntitiesByGroup(EntityMonster.class, 93).getFirst().getEntityId();
    player.getGameplay().getCancelledMissions().add(4010127);
    scene.refreshMissionGroups();
    assertTrue(entities(scene, 93).isEmpty());
    assertNull(scene.getEntityById(entity));
    assertFalse(scene.activateFarmElement(entity, 0));
    assertTrue(nativeEntities(scene, 93).isEmpty());
  }

  @Test
  void ordinaryOpeningEffectsUnloadWhenTheirAuthoredObjectiveFinishes() throws Exception {
    var scene = scene();
    assertFalse(entities(scene, 52).isEmpty());
    assertFalse(entities(scene, 88).isEmpty());
    var sub = new QuestState(100010105);
    sub.advance(1, 1, 100);
    player.getGameplay().getSubMissions().put(sub.getQuestId(), sub);
    scene.refreshMissionGroups();
    assertTrue(entities(scene, 52).isEmpty());
    assertFalse(entities(scene, 88).isEmpty());
    finish(1000101);
    scene.refreshMissionGroups();
    assertTrue(entities(scene, 88).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"doing", "cancelled", "zeroTime", "wrongId", "overGoal"})
  void anIncompleteOrInvalidMissionRecordDoesNotUnlockTheShadow(String mutation) throws Exception {
    finish(4010127);
    var state = player.getGameplay().getMainMissions().get(4010127);
    switch (mutation) {
      case "doing" -> player.getGameplay().getMainMissions().put(4010127, new QuestState(4010127));
      case "cancelled" -> player.getGameplay().getCancelledMissions().add(4010127);
      case "zeroTime" -> DailyMissionAcceptance46Test.set(state, "finishTime", 0L);
      case "wrongId" -> DailyMissionAcceptance46Test.set(state, "questId", 1000101);
      case "overGoal" -> DailyMissionAcceptance46Test.set(state, "progress", 2);
    }
    assertTrue(entities(scene(), 93).isEmpty());
  }

  @Test
  void explicitNormalCategoryStillHonorsConditionsAndUnknownUnloadCannotOpenIt() throws Exception {
    DailyMissionAcceptance46Test.set(groups.get(93), "Category", GroupInfo.GroupCategory.Normal);
    assertTrue(entities(scene(), 93).isEmpty());
    finish(4010127);
    DailyMissionAcceptance46Test.set(
        groups.get(93),
        "UnloadCondition",
        gson.fromJson(
            "{\"Conditions\":[{\"ID\":1000101,\"Phase\":\"Unknown\"}]}", LoadConditionInfo.class));
    assertTrue(entities(scene(), 93).isEmpty());
  }

  @Test
  void delayedLoadWaitsForReentryAndDelayedUnloadKeepsExistingEntities() throws Exception {
    // Mutated flags exercise the same authored condition format used by other ordinary groups.
    DailyMissionAcceptance46Test.set(groups.get(93).getLoadCondition(), "DelayToLevelReload", true);
    var scene = scene();
    nativeEntities(scene, 93); // Initial scene was sent.
    finish(4010127);
    scene.refreshMissionGroups();
    assertTrue(entities(scene, 93).isEmpty());
    scene = scene();
    assertEquals(2, entities(scene, 93).size());
    DailyMissionAcceptance46Test.set(
        groups.get(93),
        "UnloadCondition",
        gson.fromJson(
            "{\"Conditions\":[{\"ID\":1000401,\"Phase\":\"Finish\"}],\"DelayToLevelReload\":true}",
            LoadConditionInfo.class));
    finish(1000401);
    scene.refreshMissionGroups();
    assertEquals(2, entities(scene, 93).size());
    assertTrue(entities(scene(), 93).isEmpty());
  }
}
