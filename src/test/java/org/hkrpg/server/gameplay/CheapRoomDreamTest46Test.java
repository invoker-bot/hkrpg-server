package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.BasePacket;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;

/** Original cheap-room placements; player histories and named mutations are synthetic. */
class CheapRoomDreamTest46Test {
  static final String FLOOR = "P10102_F10102002";
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, GroupInfo> groups = new HashMap<>();
  final List<PropExcel> oldProps = new ArrayList<>();
  final List<NpcExcel> oldNpcs = new ArrayList<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
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
    public int getPlaneId() { return 10102; }
    public int getFloorId() { return 10102002; }
    public void save() { saves++; }
    public void sendPacket(BasePacket packet) { packets.add(packet); }
  }

  @BeforeEach
  void prepare() throws Exception {
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    oldProps.addAll(GameData.getPropExcelMap().values());
    oldNpcs.addAll(GameData.getNpcExcelMap().values());
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldSubs.putAll(GameplayDefinitions.subs);
    try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/cheap-room-dream-test-4.6.json")), StandardCharsets.UTF_8)) {
      var records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
      for (var entry : records.entrySet()) for (var row : entry.getValue().getAsJsonArray()) {
        String path = entry.getKey();
        if (path.contains("/SharedRuntimeGroup/")) {
          int id = Integer.parseInt(path.replaceAll(".*_G([0-9]+)\\.json", "$1"));
          var group = gson.fromJson(row, GroupInfo.class);
          group.setId(id);
          groups.put(id, group);
        } else switch (path) {
          case "Config/LevelOutput/RuntimeFloor/P10102_F10102002.json" ->
              floor = gson.fromJson(row, FloorInfo.class);
          case "ExcelOutput/MazePlane.json" -> plane = gson.fromJson(row, MazePlaneExcel.class);
          case "ExcelOutput/MazeProp.json" -> {
            var prop = gson.fromJson(row, PropExcel.class);
            prop.onLoad();
            GameData.getPropExcelMap().add(prop);
          }
          case "ExcelOutput/NPCData.json" ->
              GameData.getNpcExcelMap().add(gson.fromJson(row, NpcExcel.class));
          case "ExcelOutput/MainMission.json" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "Config/Level/Mission/1010601/MissionInfo_1010601.partial.json" -> {
            var sub = gson.fromJson(row, MissionInfo.SubMissionInfo.class);
            GameplayDefinitions.subs.put(sub.getId(), sub);
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
    GameData.getNpcExcelMap().values().clear();
    oldNpcs.forEach(GameData.getNpcExcelMap()::add);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
  }

  Scene scene() {
    player.scene = new Scene(player, plane, 10102002);
    return player.scene;
  }

  void absent(Scene scene) {
    assertFalse(scene.getLoadedGroups().contains(16), "Dormant DreamTest was registered");
    assertTrue(scene.getEntities().values().stream().noneMatch(e -> e.getGroupId() == 16),
        "Dormant barrels or test NPC leaked into the room");
  }

  @Test
  void enteringTheOriginalRoomDoesNotSpawnDreamTestBarrelsOrItsNpc() {
    assertEquals(3, groups.get(16).getPropList().stream()
        .filter(p -> p.getPropID() == 101017).count());
    assertEquals(2, groups.get(16).getNPCList().size());
    var scene = scene();
    absent(scene);
    assertEquals(1, scene.getEntities().values().stream().filter(e -> e.getGroupId() == 2).count(),
        "The original Boulder Town exit must remain");
    assertEquals(0, player.saves);
  }

  @Test
  void directLoadsAndMissionRefreshCannotReintroduceTheDormantTestGroup() {
    var scene = scene();
    assertFalse(player.getMissionManager().shouldLoadGroup(groups.get(16), scene));
    scene.loadGroup(16);
    scene.loadGroup(groups.get(16));
    scene.refreshMissionGroups();
    absent(scene);
  }

  @Test
  void reentryAndNativeSceneSnapshotsPreserveTheActiveBedInteractionAndExit() throws Exception {
    // Synthetic accepted history; the objective and placements are original records.
    player.getGameplay().getMainMissions().put(1010601, new QuestState(1010601));
    player.getGameplay().getSubMissions().put(101060104, new QuestState(101060104));
    player.getGameplay().getStoryValues().put("unrelated-progress", 7);
    var history = Map.copyOf(player.getGameplay().getStoryValues());
    var bridge = ProtocolBridge.forVersion("4.6.0");
    for (int i = 0; i < 2; i++) {
      var scene = scene();
      absent(scene);
      assertTrue(scene.getEntities().values().stream().anyMatch(e -> e.getGroupId() == 9
          && e.getInstId() == 300001 && e instanceof EntityProp prop && prop.getPropId() == 100));
      var nativeScene = bridge.clientSchema().parse("SceneInfo",
          bridge.translate("SceneInfo", scene.toProto().toByteArray(), true));
      var published = new HashSet<Integer>();
      for (var raw : (List<?>) field(nativeScene, "entity_group_list"))
        published.add((Integer) field((DynamicMessage) raw, "group_id"));
      assertFalse(published.contains(16));
      assertTrue(published.containsAll(Set.of(2, 9)));
    }
    assertEquals(history, player.getGameplay().getStoryValues());
    assertEquals(0, player.saves);
    assertTrue(player.packets.isEmpty());
  }

  @Test
  void matchingNamesAndOrdinaryBarrelModelsAreNotABlacklist() {
    var normal = gson.fromJson("""
        {"$type":"RPG.GameCore.RtLevelGroupInfo","GroupName":"DreamTest",
         "LoadOnInitial":true,"PropList":[{"PropID":101017,"ID":300010}]}
        """, GroupInfo.class);
    normal.setId(2);
    var scene = scene();
    assertTrue(DebugGroupConditions.allowed(normal, scene));
    var barrel = scene.getEntityLoader().loadProp(scene, normal, normal.getPropList().getFirst());
    assertNotNull(barrel, "Normal destructible barrels still load");
    assertEquals(101017, barrel.getPropId());
  }

  @Test
  void refreshUnloadsAnAlreadyRegisteredTestGroupWithItsEntities() {
    var scene = scene();
    // Synthetic stale registration represents a room constructed by the previous loader.
    scene.getLoadedGroups().add(16);
    var group = groups.get(16);
    for (var info : group.getPropList())
      scene.addEntity(scene.getEntityLoader().loadProp(scene, group, info), false);
    scene.toProto();
    player.packets.clear();
    scene.refreshMissionGroups();
    absent(scene);
    boolean unloaded = false;
    for (var packet : player.packets)
      if (packet.getData() instanceof
          emu.lunarcore.proto.SceneGroupRefreshScNotifyOuterClass.SceneGroupRefreshScNotify refresh)
        for (var update : refresh.getGroupRefreshInfo())
          if (update.getGroupId() == 16 && update.getGroupRefreshType() ==
              emu.lunarcore.proto.SceneGroupRefreshTypeOuterClass.SceneGroupRefreshType
                  .SCENE_GROUP_REFRESH_TYPE_UNLOAD) unloaded = true;
    assertTrue(unloaded, "Stale group must receive the normal whole-group unload notification");
    assertEquals(0, player.saves);
  }

  @Test
  void legacyAndChangedSourceRecordsKeepTheirExistingLoadingRules() {
    var raw = gson.toJsonTree(groups.get(16)).getAsJsonObject();
    raw.addProperty("$type", "RPG.GameCore.RtLevelGroupInfo");
    raw.addProperty("AreaAnchorName", "LevelArea_P10102_F10102002_G16");
    raw.addProperty("LoadOnInitial", true); // Explicit synthetic source mutation.
    var changed = gson.fromJson(raw, GroupInfo.class);
    changed.setId(16);
    var legacy = new Gson().fromJson(raw, GroupInfo.class);
    legacy.setId(16);
    var scene = scene();
    assertTrue(DebugGroupConditions.allowed(changed, scene));
    assertTrue(DebugGroupConditions.allowed(legacy, scene));
  }

  @Test
  void repeatedRejectionsLogTheSceneAndGroupOnceAndSummarizeRepeats() {
    GameDiagnostics.flush(true);
    var logger = (Logger) LunarCore.getLogger();
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    try {
      var scene = scene();
      scene.loadGroup(16);
      scene.loadGroup(groups.get(16));
      var messages = capture.list.stream().map(ILoggingEvent::getFormattedMessage)
          .filter(message -> message.contains("reason=dormant-debug-group")).toList();
      assertEquals(1, messages.size());
      assertTrue(messages.getFirst().contains("plane=10102"));
      assertTrue(messages.getFirst().contains("floor=10102002"));
      assertTrue(messages.getFirst().contains("group=16"));
      GameDiagnostics.flush(true);
      assertTrue(capture.list.stream().map(ILoggingEvent::getFormattedMessage)
          .anyMatch(message -> message.contains("Diagnostic repeats:")
              && message.contains("dormant-debug-group") && message.contains("repeated=2")));
    } finally {
      logger.detachAppender(capture);
      capture.stop();
      GameDiagnostics.flush(true);
    }
  }

  Object field(DynamicMessage row, String name) {
    return row.getField(row.getDescriptorForType().findFieldByName(name));
  }
}
