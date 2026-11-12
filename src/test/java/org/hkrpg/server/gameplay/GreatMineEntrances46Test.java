package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.ClientNpcOwnership46Test.completed;
import static org.hkrpg.server.gameplay.ClientNpcOwnership46Test.field;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.data.excel.NpcExcel;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.hkrpg.server.protocol.SceneMap46;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GreatMineEntrances46Test {
  private static final int PLANE = 20121, FLOOR = 20121001, ENTRY = 2012101;
  private static final int MAIN = 1010501, EARLY = 101050106, WHEEL = 101050118;
  private static final int BEFORE_CART = 101050107, MAIN_CART = 101050113;
  private static final int CART = 11, RIFT = 92, RAID = 4012102, MAP_ID = 1408;
  private static final String FLOOR_KEY = "P20121_F20121001";
  private final Gson gson = ResourceLoader.getGson();
  private final Map<Integer, PropExcel> previousProps = new HashMap<>();
  private final Map<Integer, NpcExcel> previousNpcs = new HashMap<>();
  private final Map<Integer, MissionInfo.SubMissionInfo> previousObjectives = new HashMap<>();
  private final Map<Long, GameplayDefinitions.Raid> previousRaids = new HashMap<>();
  private final ClientNpcOwnership46Test.Actor player = new ClientNpcOwnership46Test.Actor() {
    @Override public int getFloorId() { return FLOOR; }
    @Override public int getPlaneId() { return PLANE; }
    @Override public int getEntryId() { return ENTRY; }
  };
  private JsonObject groups;
  private FloorInfo previousFloor;
  private MazePlaneExcel previousPlane;
  private MapEntranceExcel previousEntry;
  private MazePlaneExcel plane;
  private MapEntranceExcel entry;

  @BeforeEach
  void prepare() throws Exception {
    try (var input = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/great-mine-entrances-4.6.json")), StandardCharsets.UTF_8)) {
      var fixture = JsonParser.parseReader(input).getAsJsonObject();
      groups = fixture.getAsJsonObject("groups");
      for (var raw : fixture.getAsJsonArray("props")) {
        var prop = gson.fromJson(raw, PropExcel.class);
        prop.onLoad();
        previousProps.put(prop.getId(), GameData.getPropExcelMap().get(prop.getId()));
        GameData.getPropExcelMap().add(prop);
      }
      var npcIds = new java.util.HashSet<>(List.of(11204, 11103, 11102));
      for (var source : groups.entrySet())
        for (var npc : source.getValue().getAsJsonObject().getAsJsonArray("NPCList"))
          npcIds.add(npc.getAsJsonObject().get("NPCID").getAsInt());
      for (int id : npcIds) {
        previousNpcs.put(id, GameData.getNpcExcelMap().get(id));
        GameData.getNpcExcelMap().add(gson.fromJson("{\"ID\":" + id + "}", NpcExcel.class));
      }
      for (var raw : fixture.getAsJsonArray("objectives")) {
        var objective = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
        previousObjectives.put(objective.getId(), GameplayDefinitions.subs.put(objective.getId(), objective));
      }
    }
    previousFloor = GameData.getFloorInfos().get(FLOOR_KEY);
    previousPlane = GameData.getMazePlaneExcelMap().get(PLANE);
    previousEntry = GameData.getMapEntranceExcelMap().get(ENTRY);
    plane = gson.fromJson("""
        {"PlaneID":20121,"PlaneType":"Maze","WorldID":201}
        """, MazePlaneExcel.class);
    entry = gson.fromJson("""
        {"ID":2012101,"PlaneID":20121,"FloorID":20121001,"EntranceType":"Mission"}
        """, MapEntranceExcel.class);
    GameData.getMazePlaneExcelMap().add(plane);
    GameData.getMapEntranceExcelMap().add(entry);
    GameplayDefinitions.raids.entrySet().removeIf(row -> {
      if (row.getValue().RaidID() != RAID) return false;
      previousRaids.put(row.getKey(), row.getValue());
      return true;
    });
    player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    player.getGameplay().getSubMissions().put(EARLY, new QuestState(EARLY));
  }

  @AfterEach
  void restore() {
    if (previousFloor == null) GameData.getFloorInfos().remove(FLOOR_KEY);
    else GameData.getFloorInfos().put(FLOOR_KEY, previousFloor);
    GameData.getMazePlaneExcelMap().keySet().remove(PLANE);
    if (previousPlane != null) GameData.getMazePlaneExcelMap().add(previousPlane);
    GameData.getMapEntranceExcelMap().keySet().remove(ENTRY);
    if (previousEntry != null) GameData.getMapEntranceExcelMap().add(previousEntry);
    previousProps.forEach((id, prop) -> {
      GameData.getPropExcelMap().keySet().remove(id);
      if (prop != null) GameData.getPropExcelMap().add(prop);
    });
    previousNpcs.forEach((id, npc) -> {
      GameData.getNpcExcelMap().keySet().remove(id);
      if (npc != null) GameData.getNpcExcelMap().add(npc);
    });
    previousObjectives.forEach((id, objective) -> {
      if (objective == null) GameplayDefinitions.subs.remove(id);
      else GameplayDefinitions.subs.put(id, objective);
    });
    GameplayDefinitions.raids.values().removeIf(raid -> raid.RaidID() == RAID);
    GameplayDefinitions.raids.putAll(previousRaids);
  }

  @Test
  void earlyStoryOmitsAllThreeObsoleteCartPrompts() throws Exception {
    enter();
    player.scene.loadGroup(CART);
    assertTrue(props(CART).isEmpty());
    assertTrue(nativeProps(CART).isEmpty());
    assertTrue(props(53).isEmpty());
    assertTrue(props(67).isEmpty());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(EARLY).getPhase());
    assertNull(player.getMissionManager().sub(WHEEL));
  }

  @Test
  void mainStoryBackgroundGroupsKeepLogicalActorsWithoutDuplicatePublication() throws Exception {
    enter();
    for (int group : List.of(4, 15)) {
      var actors = player.scene.getEntities().values().stream()
          .filter(entity -> entity.getGroupId() == group).toList();
      assertFalse(actors.isEmpty());
      assertEquals(group == 4 ? 21 : 4, props(group).size());
      assertTrue(nativeProps(group).isEmpty());
      for (var actor : actors) {
        for (boolean deleting : List.of(false, true)) {
          var update = new PacketSceneGroupRefreshScNotify(player.scene,
              deleting ? null : actor, deleting ? actor : null).build();
          assertTrue(((List<?>) field(nativePacket("SceneGroupRefreshScNotify", update),
              "group_refresh_list")).isEmpty());
        }
      }
    }
    var gates = (DynamicMessage) field(sceneInfo(), "scene_mission_info");
    assertTrue(((List<?>) field(gates, "sub_mission_status_list")).stream()
        .map(DynamicMessage.class::cast).anyMatch(row -> field(row, "id").equals(EARLY)
            && field(row, "status").toString().equals("MissionStatus_MissionDoing")));
    player.getGameplay().getSubMissions().put(EARLY, completed(EARLY));
    player.getGameplay().getSubMissions().put(MAIN_CART, new QuestState(MAIN_CART));
    player.scene.refreshMissionGroups();
    assertTrue(props(15).isEmpty());
    assertEquals(21, props(4).size());
  }

  @Test
  void modifiedBackgroundGroupKeepsOperatorPublication() throws Exception {
    prop(4, 0).addProperty("PosX", 12.5);
    enter();
    assertEquals(21, nativeProps(4).size());
  }

  @Test
  void robotEncounterKeepsLogicalActorsButPublishesNoDuplicateNpcOrTrigger() throws Exception {
    robotBattleDoing();
    enter();
    var actors = player.scene.getEntities().values().stream()
        .filter(entity -> entity.getGroupId() == 8).toList();
    assertEquals(4, actors.size(), "Original robot NPC and three triggers must exist logically");
    assertTrue(nativeGroup(8).isEmpty(), "The native scene already creates this robot");
    for (var actor : actors) {
      for (boolean deleting : List.of(false, true)) {
        var packet = new PacketSceneGroupRefreshScNotify(player.scene,
            deleting ? null : actor, deleting ? actor : null).build();
        assertTrue(((List<?>) field(nativePacket("SceneGroupRefreshScNotify", packet),
            "group_refresh_list")).isEmpty());
      }
    }
    assertRobotStatus("MissionStatus_MissionDoing");
  }

  @Test
  void completedRobotCannotReturnThroughExplicitGroupReloadOrReentry() throws Exception {
    robotBattleDoing();
    enter();
    assertTrue(player.scene.getLoadedGroups().contains(8));
    player.getGameplay().getSubMissions().put(101050114, completed(101050114));
    player.getGameplay().getSubMissions().put(101050112, new QuestState(101050112));
    player.scene.refreshMissionGroups();
    assertRobotAbsent();
    assertRobotStatus("MissionStatus_MissionFinish");

    player.scene.loadGroup(8);
    assertRobotAbsent();
    enter();
    player.scene.loadGroup(8);
    assertRobotAbsent();
    assertRobotStatus("MissionStatus_MissionFinish");
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(101050112).getPhase());
    assertNull(player.getMissionManager().sub(101050110));
    assertNull(player.getMissionManager().sub(101050111));
  }

  @Test
  void robotCannotLoadBeforeItsOriginalMissionGate() throws Exception {
    enter();
    player.scene.loadGroup(8);
    assertRobotAbsent();
  }

  @Test
  void uncompletedRobotBattleKeepsItsActorsAcrossRefreshAndReentry() throws Exception {
    robotBattleDoing();
    enter();
    player.scene.refreshMissionGroups();
    assertTrue(player.scene.getLoadedGroups().contains(8));
    enter();
    assertTrue(player.scene.getLoadedGroups().contains(8));
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(101050114).getPhase());
    assertNull(player.getMissionManager().sub(101050112));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void modifiedRobotSourceAndLegacyProtocolKeepTheirPublication(boolean legacy) throws Exception {
    robotBattleDoing();
    if (legacy) player.wire = ProtocolBridge.forVersion("4.2.0");
    else groups.getAsJsonObject("8").getAsJsonArray("NPCList").get(0)
        .getAsJsonObject().addProperty("PosX", 238.5);
    enter();
    assertFalse(player.scene.getEntities().values().stream()
        .filter(entity -> entity.getGroupId() == 8).toList().isEmpty());
    if (legacy) {
      boolean published = false;
      for (var group : player.scene.toProto().getEntityGroupList())
        if (group.getGroupId() == 8) published = true;
      assertTrue(published);
    } else assertFalse(nativeGroup(8).isEmpty());
  }

  private void robotBattleDoing() {
    player.getGameplay().getSubMissions().remove(EARLY);
    player.getGameplay().getSubMissions().put(101050109, completed(101050109));
    player.getGameplay().getSubMissions().put(101050123, completed(101050123));
    player.getGameplay().getSubMissions().put(101050114, new QuestState(101050114));
  }

  private void assertRobotAbsent() throws Exception {
    assertFalse(player.scene.getLoadedGroups().contains(8));
    assertTrue(player.scene.getEntities().values().stream()
        .noneMatch(entity -> entity.getGroupId() == 8));
    assertTrue(nativeGroup(8).isEmpty());
  }

  private void assertRobotStatus(String expected) throws Exception {
    var gates = (DynamicMessage) field(sceneInfo(), "scene_mission_info");
    var rows = ((List<?>) field(gates, "sub_mission_status_list")).stream()
        .map(DynamicMessage.class::cast).filter(row -> field(row, "id").equals(101050114)).toList();
    assertEquals(1, rows.size());
    assertEquals(expected, field(rows.getFirst(), "status").toString());
  }

  private List<DynamicMessage> nativeGroup(int group) throws Exception {
    return ((List<?>) field(sceneInfo(), "entity_group_list")).stream()
        .map(DynamicMessage.class::cast).filter(row -> field(row, "group_id").equals(group)).toList();
  }

  @Test
  void mainStoryCartAndItsActorsArePublishedOnlyByTheClient() throws Exception {
    player.getGameplay().getSubMissions().put(EARLY, completed(EARLY));
    player.getGameplay().getSubMissions().put(BEFORE_CART, completed(BEFORE_CART));
    var cart = new QuestState(MAIN_CART);
    player.getGameplay().getSubMissions().put(MAIN_CART, cart);
    enter();
    for (int group : List.of(16, 19)) {
      assertTrue(props(group).contains(300001));
      assertTrue(nativeProps(group).isEmpty());
      var prop = player.scene.getEntitiesByGroup(EntityProp.class, group).getFirst();
      var add = new PacketSceneGroupRefreshScNotify(player.scene, prop, null).build();
      assertTrue(((List<?>) field(nativePacket("SceneGroupRefreshScNotify", add), "group_refresh_list")).isEmpty());
    }
    var gates = (DynamicMessage) field(sceneInfo(), "scene_mission_info");
    var subs = (List<?>) field(gates, "sub_mission_status_list");
    assertTrue(subs.stream().map(DynamicMessage.class::cast)
        .anyMatch(row -> field(row, "id").equals(MAIN_CART)
            && field(row, "status").toString().equals("MissionStatus_MissionDoing")));
    assertSame(cart, player.getMissionManager().sub(MAIN_CART));
    assertNull(player.getMissionManager().sub(101050125));

    player.getGameplay().getSubMissions().put(MAIN_CART, completed(MAIN_CART));
    player.scene.refreshMissionGroups();
    assertTrue(props(19).isEmpty());
    assertTrue(props(16).contains(300001));
  }

  @Test
  void riftWithoutARaidIsAbsentFromTheSceneEvenOnExplicitGroupLoad() throws Exception {
    enter();
    player.scene.loadGroup(RIFT);
    assertTrue(props(RIFT).isEmpty());
    assertTrue(nativeProps(RIFT).isEmpty());
  }

  @Test
  void riftWithoutARaidIsAbsentFromTheMapAndCannotUseAnOldUnlock() throws Exception {
    player.getGameplay().getStoryValues().put("prop-state:20121:20121001:92:300003",
        PropState.CheckPointEnable.getVal());
    enter();
    assertRiftAbsentFromMap();
    player.scene = null;
    assertRiftAbsentFromMap();
    assertFalse(SceneAnchors.unlocked(player, entry).contains(MAP_ID));
  }

  private void assertRiftAbsentFromMap() throws Exception {
    var replies = new java.util.ArrayList<DynamicMessage>();
    var session = new GameSession(null) {
      @Override public emu.lunarcore.game.player.Player getPlayer() { return player; }
      @Override public ProtocolBridge getProtocol() { return player.wire; }
      @Override public void sendClientPacket(String name, byte[] bytes) {
        assertEquals("GetSceneMapInfoScRsp", name);
        replies.add(assertDoesNotThrow(() -> player.wire.clientSchema().parse(name, bytes)));
      }
    };
    session.setState(SessionState.ACTIVE);
    var schema = player.wire.clientSchema();
    var identifier = DynamicMessage.newBuilder(schema.message("SceneIdentifier"));
    identifier.setField(identifier.getDescriptorForType().findFieldByName("floor_id"), FLOOR);
    var request = DynamicMessage.newBuilder(schema.message("GetSceneMapInfoCsReq"));
    request.addRepeatedField(request.getDescriptorForType().findFieldByName("scene_identifiers"),
        identifier.build());
    assertTrue(SceneMap46.handle(session, player.wire.clientCommand("GetSceneMapInfoCsReq"),
        request.build().toByteArray()));
    assertEquals(0, field(replies.getFirst(), "retcode"));
    var map = (DynamicMessage) ((List<?>) field(replies.getFirst(), "scene_map_info")).getFirst();
    for (String name : List.of("maze_group_list", "maze_prop_list")) {
      assertTrue(((List<?>) field(map, name)).stream().map(DynamicMessage.class::cast)
          .noneMatch(group -> field(group, "group_id").equals(RIFT)));
    }
    assertFalse(((List<?>) field(map, "unlock_teleport_list")).contains(MAP_ID));
  }

  @ParameterizedTest
  @ValueSource(ints = {101050126, WHEEL})
  void originalQuestPropIsLoadedAtItsOwnStageAndPublishedByTheClient(int objective) throws Exception {
    int group = objective == WHEEL ? 53 : 67;
    player.getGameplay().getSubMissions().put(objective, new QuestState(objective));
    enter();
    assertTrue(props(group).contains(300002));
    assertTrue(nativeProps(group).isEmpty());
    var gates = (DynamicMessage) field(sceneInfo(), "scene_mission_info");
    assertTrue(((List<?>) field(gates, "sub_mission_status_list")).stream()
        .map(DynamicMessage.class::cast)
        .anyMatch(row -> field(row, "id").equals(objective)
            && field(row, "status").toString().equals("MissionStatus_MissionDoing")));
    var prop = player.scene.getEntitiesByGroup(EntityProp.class, group).getFirst();
    var refresh = nativePacket("SceneGroupRefreshScNotify",
        new PacketSceneGroupRefreshScNotify(player.scene, prop, null).build());
    assertTrue(((List<?>) field(refresh, "group_refresh_list")).isEmpty());

    player.getGameplay().getSubMissions().put(objective, completed(objective));
    player.scene.refreshMissionGroups();
    assertTrue(props(group).isEmpty());
    assertTrue(nativeProps(group).isEmpty());
    assertFalse(props(CART).contains(300003));
  }

  @Test
  void otherRiftOnTheSameFloorKeepsItsOriginalQuestGate() throws Exception {
    enter();
    assertTrue(props(95).isEmpty());
    player.getGameplay().getMainMissions().put(4020103, new QuestState(4020103));
    enter();
    assertEquals(List.of(300002), nativeProps(95));
  }

  @Test
  void unchangedRiftWithAnExplicitRaidDefinitionKeepsItsResourcePlacement() throws Exception {
    var raid = new GameplayDefinitions.Raid(RAID, 1, "TreasureChallenge", "Player",
        "Default", List.of(), List.of(), List.of(), List.of(), false);
    GameplayDefinitions.raids.put(raid.key(), raid);
    enter();
    assertEquals(List.of(300003), nativeProps(RIFT));
  }

  @Test
  void operatorModifiedResourcesAndLegacyProtocolKeepTheirPlacement() throws Exception {
    prop(RIFT, 0).addProperty("RaidID", 41003);
    prop(CART, 2).addProperty("InitLevelGraph", "Config/Level/Mission/1010501/Drop/Drop_1010501_3.json");
    enter();
    assertTrue(nativeProps(RIFT).contains(300003));
    assertTrue(nativeProps(CART).contains(300003));

    prop(RIFT, 0).addProperty("RaidID", RAID);
    prop(CART, 2).addProperty("InitLevelGraph", "Config/Level/Mission/1010501/Drop/Drop_1010501_4.json");
    player.wire = ProtocolBridge.forVersion("4.2.0");
    enter();
    assertTrue(props(RIFT).contains(300003));
    assertTrue(props(CART).contains(300003));
  }

  private JsonObject prop(int group, int index) {
    return groups.getAsJsonObject(Integer.toString(group)).getAsJsonArray("PropList")
        .get(index).getAsJsonObject();
  }

  private void enter() {
    var floor = gson.fromJson("""
        {"FloorID":20121001,"GroupInstanceList":[{"ID":11},{"ID":16},{"ID":19},{"ID":53},{"ID":67},{"ID":92},{"ID":95},{"ID":4},{"ID":15},{"ID":8}],
         "DimensionList":[{"ID":0,"GroupIndexList":[0,1,2,3,4,5,6,7,8,9]}]}
        """, FloorInfo.class);
    for (int index : floor.getReferencedGroupIndices()) {
      int id = floor.getSimpleGroupList().get(index).getID();
      var group = gson.fromJson(groups.getAsJsonObject(Integer.toString(id)), GroupInfo.class);
      group.setId(id);
      floor.registerGroup(index, group);
    }
    floor.onLoad();
    GameData.getFloorInfos().put(FLOOR_KEY, floor);
    player.scene = new Scene(player, plane, FLOOR);
    player.scene.setEntryId(ENTRY);
  }

  private List<Integer> props(int group) {
    return player.scene.getEntitiesByGroup(EntityProp.class, group).stream()
        .map(EntityProp::getInstId).sorted().toList();
  }

  private DynamicMessage sceneInfo() throws Exception {
    var core = GetCurSceneInfoScRsp.newInstance().setScene(player.scene.toProto());
    return (DynamicMessage) field(nativePacket("GetCurSceneInfoScRsp",
        PacketCodec.encode(CmdId.GetCurSceneInfoScRsp, new byte[0], core.toByteArray())), "scene");
  }

  private List<Integer> nativeProps(int group) throws Exception {
    return ((List<?>) field(sceneInfo(), "entity_group_list")).stream().map(DynamicMessage.class::cast)
        .filter(row -> field(row, "group_id").equals(group))
        .flatMap(row -> ((List<?>) field(row, "entity_list")).stream())
        .map(DynamicMessage.class::cast)
        .filter(row -> row.hasField(row.getDescriptorForType().findFieldByName("prop")))
        .map(row -> (Integer) field((DynamicMessage) row, "inst_id")).sorted().toList();
  }

  private DynamicMessage nativePacket(String name, byte[] core) throws Exception {
    var packet = frame(player.wire.outboundFrames(core, player));
    assertEquals(player.wire.clientCommand(name), packet.cmdId());
    return player.wire.clientSchema().parse(name, packet.body());
  }

  private static PacketCodec.Frame frame(byte[] bytes) {
    var input = Unpooled.wrappedBuffer(bytes);
    try {
      var packet = Objects.requireNonNull(PacketCodec.decodeNext(input));
      assertFalse(input.isReadable());
      return packet;
    } finally {
      input.release();
    }
  }
}
