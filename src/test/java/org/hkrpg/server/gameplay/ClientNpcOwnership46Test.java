package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.send.PacketEnterSceneByServerScNotify;
import emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original station NPC groups, the installed client's G18 record, and real outbound conversion. */
class ClientNpcOwnership46Test {
  static final String FLOOR = "P10001_F10001001";
  static final int ROCKY = 10106, BERNARD = 10403, SERVER_NPC = 10122;
  static final int ROCKY_GROUP = 18, IDLE_GROUP = 31, SERVER_GROUP = 97;
  static final int BERNARD_GROUP = 16, MONITOR_MISSION = 200011007;
  static final int MIXED_PROP = 781827;
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, JsonObject> groups = new HashMap<>();
  final Actor actor = new Actor();
  JsonObject source;
  FloorInfo oldFloor;
  List<NpcExcel> oldNpcs;
  List<PropExcel> oldProps;
  MissionInfo.SubMissionInfo oldControl;
  MissionInfo.SubMissionInfo oldMonitor;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    final List<BasePacket> packets = new ArrayList<>();
    ProtocolBridge wire = ProtocolBridge.forVersion("4.6.0");
    final GameSession session =
        new GameSession(null) {
          @Override
          public ProtocolBridge getProtocol() {
            return wire;
          }
        };
    Scene scene;

    Actor() {
      teams.getCurrentLineup().getAvatars().add(0);
    }

    @Override
    public LineupManager getLineupManager() {
      return teams;
    }

    @Override
    public GameSession getSession() {
      return session;
    }

    @Override
    public Scene getScene() {
      return scene;
    }

    @Override
    public int getPlaneId() {
      return 10001;
    }

    @Override
    public int getFloorId() {
      return 10001001;
    }

    @Override
    public int getEntryId() {
      return 1000102;
    }

    @Override
    public void sendPacket(BasePacket packet) {
      packets.add(packet);
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    try (var input =
        new InputStreamReader(
            Objects.requireNonNull(
                getClass().getResourceAsStream("/fixtures/client-npc-ownership-4.6.json")),
            StandardCharsets.UTF_8)) {
      source = JsonParser.parseReader(input).getAsJsonObject();
    }
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    oldNpcs = new ArrayList<>(GameData.getNpcExcelMap().values());
    oldProps = new ArrayList<>(GameData.getPropExcelMap().values());
    for (var row : source.getAsJsonArray("npcs"))
      GameData.getNpcExcelMap().add(gson.fromJson(row, NpcExcel.class));
    for (var row : source.getAsJsonObject("groups").entrySet())
      groups.put(Integer.parseInt(row.getKey()), row.getValue().getAsJsonObject());
    oldControl =
        GameplayDefinitions.subs.put(
            200070501,
            gson.fromJson(source.get("controlSubMission"), MissionInfo.SubMissionInfo.class));
    oldMonitor =
        GameplayDefinitions.subs.put(
            MONITOR_MISSION,
            gson.fromJson(source.get("bernardMonitorSubMission"), MissionInfo.SubMissionInfo.class));
    actor.getGameplay().getMainMissions().put(1000401, completed(1000401));
    actor.getGameplay().getMainMissions().put(2000705, new QuestState(2000705));
    actor.getGameplay().getSubMissions().put(200070501, completed(200070501));
  }

  @AfterEach
  void restore() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    GameData.getNpcExcelMap().values().clear();
    oldNpcs.forEach(GameData.getNpcExcelMap()::add);
    GameData.getPropExcelMap().values().clear();
    oldProps.forEach(GameData.getPropExcelMap()::add);
    if (oldControl == null) GameplayDefinitions.subs.remove(200070501);
    else GameplayDefinitions.subs.put(200070501, oldControl);
    if (oldMonitor == null) GameplayDefinitions.subs.remove(MONITOR_MISSION);
    else GameplayDefinitions.subs.put(MONITOR_MISSION, oldMonitor);
  }

  static QuestState completed(int id) {
    var result = new QuestState(id);
    assertTrue(result.advance(1, 1, 1700000000));
    return result;
  }

  void acceptRocky() {
    actor.getGameplay().getMainMissions().put(2000110, new QuestState(2000110));
  }

  void prepareBernardQuest() {
    groups.put(BERNARD_GROUP, source.getAsJsonObject("bernardGroup"));
    actor.getGameplay().getMainMissions().put(2000110, completed(2000110));
    actor.getGameplay().getMainMissions().put(2000111, new QuestState(2000111));
    actor.getGameplay().getMainMissions().put(2000119, new QuestState(2000119));
  }

  void buildScene() {
    var floor = gson.fromJson(source.get("floor"), FloorInfo.class);
    for (int index : floor.getReferencedGroupIndices()) {
      var simple = floor.getSimpleGroupList().get(index);
      var row = groups.get(simple.getID());
      if (row == null || simple.getGroupPath().contains("_D")) continue;
      var group = gson.fromJson(row, GroupInfo.class);
      group.setId(simple.getID());
      floor.registerGroup(index, group);
    }
    floor.onLoad();
    GameData.getFloorInfos().put(FLOOR, floor);
    actor.scene =
        new Scene(
            actor, gson.fromJson(source.get("plane"), MazePlaneExcel.class), actor.getFloorId());
    actor.scene.setEntryId(actor.getEntryId());
  }

  EntityNpc npc(int group, int id) {
    return actor.scene.getEntitiesByGroup(EntityNpc.class, group).stream()
        .filter(n -> n.getNpcId() == id)
        .findFirst()
        .orElseThrow();
  }

  static Object field(DynamicMessage message, String key) {
    return message.getField(message.getDescriptorForType().findFieldByName(key));
  }

  DynamicMessage nativePacket(String name, byte[] core) throws Exception {
    var bytes = actor.wire.outboundFrames(core, actor);
    var input = Unpooled.wrappedBuffer(bytes);
    try {
      var frame = PacketCodec.decodeNext(input);
      assertNotNull(frame);
      assertFalse(input.isReadable());
      assertEquals(actor.wire.clientCommand(name), frame.cmdId());
      var value = actor.wire.clientSchema().parse(name, frame.body());
      assertTrue(value.getUnknownFields().asMap().isEmpty());
      return value;
    } finally {
      input.release();
    }
  }

  byte[] coreSnapshot() {
    return PacketCodec.encode(
        CmdId.GetCurSceneInfoScRsp,
        new byte[0],
        GetCurSceneInfoScRsp.newInstance().setScene(actor.scene.toProto()).toByteArray());
  }

  DynamicMessage snapshot() throws Exception {
    return (DynamicMessage) field(nativePacket("GetCurSceneInfoScRsp", coreSnapshot()), "scene");
  }

  @Test
  void sceneEntryAndCurrentSceneCarryTheGatesOfBothRockyVersions() throws Exception {
    acceptRocky();
    buildScene();
    assertFalse(actor.scene.getLoadedGroups().contains(IDLE_GROUP));
    assertTrue(actor.scene.getLoadedGroups().contains(ROCKY_GROUP));

    var entered =
        nativePacket("EnterSceneByServerScNotify", new PacketEnterSceneByServerScNotify(actor).build());
    for (var scene : List.of(snapshot(), (DynamicMessage) field(entered, "scene"))) {
      var descriptor = scene.getDescriptorForType().findFieldByNumber(1284);
      assertTrue(scene.hasField(descriptor), "map entry did not supply legacy group mission state");
      var gates = (DynamicMessage) scene.getField(descriptor);
      assertEquals(
          List.of(1000401), gates.getField(gates.getDescriptorForType().findFieldByNumber(15)));
      assertEquals(
          List.of(2000110, 2000111), gates.getField(gates.getDescriptorForType().findFieldByNumber(3)));
      assertFalse(npcs(scene).contains(ROCKY));
    }
  }

  @Test
  void sceneStatusDoesNotReportACorruptCompletionOrReplaySubMissions() throws Exception {
    acceptRocky();
    actor.getGameplay().getMainMissions().put(1000401, completed(1000302));
    actor.getGameplay().getMainMissions().put(2000111, completed(2000111));
    buildScene();

    var gates = (DynamicMessage) field(snapshot(), "scene_mission_info");
    assertEquals(List.of(2000111), field(gates, "finished_main_mission_id_list"));
    assertEquals(List.of(2000110), field(gates, "unfinished_main_mission_id_list"));
    assertEquals(List.of(), field(gates, "sub_mission_status_list"));
  }

  @Test
  void sceneEntryAndCurrentSceneSupplyBernardsCompletedMonitorGate() throws Exception {
    prepareBernardQuest();
    actor.getGameplay().getSubMissions().put(MONITOR_MISSION, completed(MONITOR_MISSION));
    buildScene();
    assertEquals(400001, npc(BERNARD_GROUP, BERNARD).getInstId());

    var entered =
        nativePacket("EnterSceneByServerScNotify", new PacketEnterSceneByServerScNotify(actor).build());
    for (var scene : List.of(snapshot(), (DynamicMessage) field(entered, "scene"))) {
      assertFalse(npcs(scene).contains(BERNARD));
      assertMonitorGate(scene, 2, 1);
    }
  }

  @Test
  void sceneIncludesAnInProgressSubGateEvenWhenItsGroupIsUnloaded() throws Exception {
    prepareBernardQuest();
    actor.getGameplay().getSubMissions().put(MONITOR_MISSION, new QuestState(MONITOR_MISSION));
    buildScene();

    assertFalse(actor.scene.getLoadedGroups().contains(BERNARD_GROUP));
    assertMonitorGate(snapshot(), 1, 0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unstarted", "cancelled", "invalid", "missing-parent", "retired"})
  void sceneDoesNotPublishUnavailableSubMissionGates(String state) throws Exception {
    prepareBernardQuest();
    var saved = actor.getGameplay().getSubMissions();
    if (!state.equals("unstarted")) saved.put(MONITOR_MISSION, completed(MONITOR_MISSION));
    switch (state) {
      case "cancelled" -> actor.getGameplay().getCancelledSubs().add(MONITOR_MISSION);
      case "invalid" -> saved.put(MONITOR_MISSION, completed(200070501));
      case "missing-parent" -> actor.getGameplay().getMainMissions().remove(2000111);
      case "retired" -> {
        actor.getGameplay().getMainMissions().put(2000111, completed(2000111));
        saved.put(MONITOR_MISSION, new QuestState(MONITOR_MISSION));
      }
    }
    buildScene();

    assertFalse(actor.scene.getLoadedGroups().contains(BERNARD_GROUP));
    assertEquals(
        List.of(),
        field((DynamicMessage) field(snapshot(), "scene_mission_info"), "sub_mission_status_list"));
  }

  static void assertMonitorGate(DynamicMessage scene, int status, int progress) {
    var gates = (DynamicMessage) scene.getField(scene.getDescriptorForType().findFieldByNumber(1284));
    var rows = (List<?>) gates.getField(gates.getDescriptorForType().findFieldByNumber(7));
    assertEquals(1, rows.size(), "Only the sub-mission gate used by the floor should be sent");
    var row = (DynamicMessage) rows.getFirst();
    assertEquals(MONITOR_MISSION, field(row, "id"));
    assertEquals(progress, field(row, "progress"));
    assertEquals(status, ((EnumValueDescriptor) field(row, "status")).getNumber());
  }

  static List<Integer> npcs(DynamicMessage root) {
    var ids = new ArrayList<Integer>();
    if (root.getDescriptorForType().getName().equals("SceneEntityInfo")) {
      var npc = root.getDescriptorForType().findFieldByName("npc");
      if (root.hasField(npc))
        ids.add((Integer) field((DynamicMessage) root.getField(npc), "npc_id"));
    }
    for (var entry : root.getAllFields().entrySet()) {
      if (entry.getKey().getJavaType() != FieldDescriptor.JavaType.MESSAGE) continue;
      if (entry.getKey().isRepeated()) {
        for (var value : (List<?>) entry.getValue()) ids.addAll(npcs((DynamicMessage) value));
      } else ids.addAll(npcs((DynamicMessage) entry.getValue()));
    }
    return ids;
  }

  @Test
  void originalLocalRockyAndBernardDoNotArriveAgainAsServerEntities() throws Exception {
    var evidence = source.getAsJsonObject("nativeEvidence");
    var data = Base64.getDecoder().decode(evidence.get("group18RecordBase64").getAsString());
    assertEquals(
        evidence.get("group18RecordSHA256").getAsString(),
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
    var reader = CodedInputStream.newInstance(data);
    assertEquals(2, reader.readUInt32()); // Original runtime group variant.
    int bit = evidence.get("loadSideMaskBit").getAsInt();
    for (int i = 0; i < evidence.get("maskWords").getAsInt(); i++) {
      long mask = reader.readUInt64();
      if (i == bit / 64) assertEquals(0, mask & (1L << (bit % 64)));
    }
    assertFalse(groups.get(ROCKY_GROUP).has("LoadSide"));
    acceptRocky();
    buildScene();
    var view = snapshot();
    assertNotNull(npc(ROCKY_GROUP, ROCKY));
    assertNotNull(npc(ROCKY_GROUP, BERNARD));
    assertEquals(List.of(SERVER_NPC), npcs(view));
    assertTrue(
        ((List<?>) field(view, "entity_group_list"))
            .stream()
                .noneMatch(
                    g -> Objects.equals(ROCKY_GROUP, field((DynamicMessage) g, "group_id"))));
  }

  @Test
  void acceptingTheQuestReplacesTheLogicalActorsWithoutPublishingEitherLocalGroup()
      throws Exception {
    buildScene();
    snapshot();
    assertNotNull(npc(IDLE_GROUP, ROCKY));
    actor.packets.clear();
    acceptRocky();
    actor.scene.refreshMissionGroups();
    assertTrue(actor.scene.getEntitiesByGroup(EntityNpc.class, IDLE_GROUP).isEmpty());
    assertNotNull(npc(ROCKY_GROUP, ROCKY));
    for (var packet : actor.packets) {
      var value = nativePacket("SceneGroupRefreshScNotify", packet.build());
      assertTrue(((List<?>) field(value, "group_refresh_list")).isEmpty());
    }
    assertEquals(List.of(SERVER_NPC), npcs(snapshot()));
  }

  @Test
  void individualLocalNpcAddsAndDeletesAreSuppressedOnTheLive46Path() throws Exception {
    acceptRocky();
    buildScene();
    snapshot();
    var rocky = npc(ROCKY_GROUP, ROCKY);
    var explicitAdd = new PacketSceneGroupRefreshScNotify(actor.scene, rocky, null);
    assertTrue(npcs(nativePacket("SceneGroupRefreshScNotify", explicitAdd.build())).isEmpty());
    actor.packets.clear();
    actor.scene.removeEntity(rocky);
    assertTrue(actor.packets.isEmpty());
    var group = actor.scene.getResourceGroups().get(ROCKY_GROUP);
    var definition =
        group.getNPCList().stream().filter(n -> n.getNPCID() == ROCKY).findFirst().orElseThrow();
    actor.scene.addEntity(new EntityNpc(actor.scene, group, definition), true);
    assertTrue(actor.packets.isEmpty());
    assertNotNull(npc(ROCKY_GROUP, ROCKY));
    actor.scene.removeEntity(npc(SERVER_GROUP, SERVER_NPC));
    assertEquals(1, actor.packets.size(), "Server-owned actor removal must still be published");
  }

  @ParameterizedTest
  @ValueSource(strings = {"explicit-server", "legacy", "mission", "atmosphere", "dormant"})
  void unverifiedOwnershipDoesNotSuppressExistingServerActors(String shape) throws Exception {
    var raw = groups.get(ROCKY_GROUP);
    switch (shape) {
      case "explicit-server" -> raw.addProperty("LoadSide", "Server");
      case "legacy" -> raw.remove("$type");
      case "mission" -> raw.addProperty("Category", "Mission");
      case "atmosphere" -> raw.addProperty("Category", "Atmosphere");
      case "dormant" -> {
        raw.remove("LoadCondition");
        raw.remove("UnloadCondition");
        raw.addProperty("LoadOnInitial", false);
      }
      default -> fail(shape);
    }
    acceptRocky();
    buildScene();
    assertTrue(npcs(snapshot()).containsAll(List.of(ROCKY, BERNARD, SERVER_NPC)));
  }

  @Test
  void anInitiallyActiveOrdinaryLocalGroupAlsoKeepsOnlyItsLogicalActors() throws Exception {
    var raw = groups.get(ROCKY_GROUP);
    raw.remove("LoadCondition");
    raw.remove("UnloadCondition");
    raw.addProperty("LoadOnInitial", true);
    acceptRocky();
    buildScene();
    assertNotNull(npc(ROCKY_GROUP, ROCKY));
    assertEquals(List.of(SERVER_NPC), npcs(snapshot()));
  }

  @Test
  void sameNpcIdAtAServerCreatedPlacementIsNotFilteredByNameOrTemplate() throws Exception {
    acceptRocky();
    buildScene();
    var group = actor.scene.getResourceGroups().get(ROCKY_GROUP);
    var other =
        gson.fromJson("{\"NPCID\":10106,\"ID\":490001,\"PosX\":10,\"PosZ\":10}", NpcInfo.class);
    actor.scene.addEntity(new EntityNpc(actor.scene, group, other), false);
    var ids = npcs(snapshot());
    assertEquals(1, ids.stream().filter(id -> id == ROCKY).count());
    assertTrue(ids.contains(SERVER_NPC));
    assertFalse(ids.contains(BERNARD));
  }

  @Test
  void mixedGroupRetainsItsPropAndServerGroupState() throws Exception {
    var raw = groups.get(ROCKY_GROUP);
    raw.getAsJsonArray("PropList")
        .add(JsonParser.parseString("{\"ID\":300111,\"PropID\":" + MIXED_PROP + "}"));
    var prop =
        gson.fromJson(
            "{\"ID\":"
                + MIXED_PROP
                + ",\"PropType\":\"PROP_ORDINARY\",\"PropStateList\":[\"Closed\",\"Open\"]}",
            PropExcel.class);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
    acceptRocky();
    buildScene();
    assertTrue(actor.scene.setStoryGroupState(ROCKY_GROUP, 7));
    var view = snapshot();
    var group =
        ((List<?>) field(view, "entity_group_list"))
            .stream()
                .map(DynamicMessage.class::cast)
                .filter(g -> Objects.equals(ROCKY_GROUP, field(g, "group_id")))
                .findFirst()
                .orElseThrow();
    assertTrue(npcs(group).isEmpty());
    assertEquals(7, field(group, "state"));
    var entity = (DynamicMessage) ((List<?>) field(group, "entity_list")).getFirst();
    assertEquals(MIXED_PROP, field((DynamicMessage) field(entity, "prop"), "prop_id"));
  }

  @Test
  void old42PacketsRetainTheirExistingActorAndRemoval() {
    actor.wire = ProtocolBridge.forVersion("4.2.0");
    acceptRocky();
    buildScene();
    var bytes = coreSnapshot();
    assertArrayEquals(bytes, actor.wire.outboundFrames(bytes, actor));
    actor.packets.clear();
    actor.scene.removeEntity(npc(ROCKY_GROUP, ROCKY));
    assertEquals(1, actor.packets.size());
  }
}
