package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.send.PacketEnterSceneByServerScNotify;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Full original boxing placements and actual outbound packets; histories are explicit fixtures. */
class BoxingClubEntrance46Test {
  static final String FLOOR = "P10102_F10102004";
  final Gson gson = ResourceLoader.getGson();
  final List<NpcExcel> oldNpcs = new ArrayList<>();
  final List<PropExcel> oldProps = new ArrayList<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final Actor actor = new Actor();
  FloorInfo floor, oldFloor;
  MazePlaneExcel plane;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    final List<BasePacket> packets = new ArrayList<>();
    final ProtocolBridge wire = ProtocolBridge.forVersion("4.6.0");
    final GameSession session = new GameSession(null) {
      public ProtocolBridge getProtocol() { return wire; }
    };
    Scene scene;
    int saves;

    Actor() { teams.getCurrentLineup().getAvatars().add(0); }
    public LineupManager getLineupManager() { return teams; }
    public GameSession getSession() { return session; }
    public Scene getScene() { return scene; }
    public int getPlaneId() { return 10102; }
    public int getFloorId() { return 10102004; }
    public int getEntryId() { return 1010204; }
    public void sendPacket(BasePacket packet) { packets.add(packet); }
    public void save() { saves++; }
  }

  @BeforeEach
  void prepare() throws Exception {
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    oldNpcs.addAll(GameData.getNpcExcelMap().values());
    oldProps.addAll(GameData.getPropExcelMap().values());
    JsonObject fixture;
    try (var input = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/boxing-club-entrance-4.6.json")), StandardCharsets.UTF_8)) {
      fixture = JsonParser.parseReader(input).getAsJsonObject();
    }
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
    var tables = fixture.getAsJsonObject("tables");
    plane = gson.fromJson(tables.getAsJsonObject("MazePlane").getAsJsonArray("rows").get(0), MazePlaneExcel.class);
    for (var row : tables.getAsJsonObject("NPCData").getAsJsonArray("rows"))
      GameData.getNpcExcelMap().add(gson.fromJson(row, NpcExcel.class));
    for (var row : tables.getAsJsonObject("MazeProp").getAsJsonArray("rows")) {
      var prop = gson.fromJson(row, PropExcel.class);
      prop.onLoad();
      GameData.getPropExcelMap().add(prop);
    }
    var files = fixture.getAsJsonObject("files");
    for (var entry : files.entrySet()) {
      if (!entry.getKey().endsWith(".partial.json")) continue;
      for (var row : entry.getValue().getAsJsonObject().getAsJsonObject("content").getAsJsonArray("SubMissionList")) {
        var sub = gson.fromJson(row, MissionInfo.SubMissionInfo.class);
        oldSubs.put(sub.getId(), GameplayDefinitions.subs.put(sub.getId(), sub));
      }
    }
    floor = gson.fromJson(files.getAsJsonObject("Config/LevelOutput/RuntimeFloor/" + FLOOR + ".json")
        .get("content"), FloorInfo.class);
    for (int i : floor.getReferencedGroupIndices()) {
      var simple = floor.getSimpleGroupList().get(i);
      var row = files.getAsJsonObject(simple.getGroupPath());
      if (row == null) continue;
      var group = gson.fromJson(row.get("content"), GroupInfo.class);
      group.setId(simple.getID());
      floor.registerGroup(i, group);
    }
    floor.onLoad();
    GameData.getFloorInfos().put(FLOOR, floor);
  }

  @AfterEach
  void restore() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    GameData.getNpcExcelMap().values().clear();
    oldNpcs.forEach(GameData.getNpcExcelMap()::add);
    GameData.getPropExcelMap().values().clear();
    oldProps.forEach(GameData.getPropExcelMap()::add);
    oldSubs.forEach((id, sub) -> {
      GameplayDefinitions.subs.remove(id);
      if (sub != null) GameplayDefinitions.subs.put(id, sub);
    });
  }

  void complete(int id) {
    var state = new QuestState(id);
    assertTrue(state.advance(1, 1, 1700000000));
    actor.getGameplay().getMainMissions().put(id, state);
  }

  void buildScene() {
    actor.scene = new Scene(actor, plane, actor.getFloorId());
    actor.scene.setEntryId(actor.getEntryId());
  }

  DynamicMessage nativePacket(String name, byte[] core) throws Exception {
    var input = Unpooled.wrappedBuffer(actor.wire.outboundFrames(core, actor));
    try {
      var frame = PacketCodec.decodeNext(input);
      assertNotNull(frame);
      assertFalse(input.isReadable());
      assertEquals(actor.wire.clientCommand(name), frame.cmdId());
      return actor.wire.clientSchema().parse(name, frame.body());
    } finally { input.release(); }
  }

  static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  DynamicMessage snapshot(String kind) throws Exception {
    var packet = kind.equals("entry")
        ? nativePacket("EnterSceneByServerScNotify", new PacketEnterSceneByServerScNotify(actor).build())
        : nativePacket("GetCurSceneInfoScRsp", PacketCodec.encode(CmdId.GetCurSceneInfoScRsp,
            new byte[0], GetCurSceneInfoScRsp.newInstance().setScene(actor.scene.toProto()).toByteArray()));
    return (DynamicMessage) field(packet, "scene");
  }

  static List<DynamicMessage> entities(DynamicMessage message) {
    var result = new ArrayList<DynamicMessage>();
    if (message.getDescriptorForType().getName().equals("SceneEntityInfo")) result.add(message);
    for (var item : message.getAllFields().entrySet()) {
      if (item.getKey().getJavaType() != com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE) continue;
      if (item.getKey().isRepeated()) for (var row : (List<?>) item.getValue())
        result.addAll(entities((DynamicMessage) row));
      else result.addAll(entities((DynamicMessage) item.getValue()));
    }
    return result;
  }

  static List<DynamicMessage> npcs(DynamicMessage message) {
    return entities(message).stream().filter(e -> e.hasField(e.getDescriptorForType().findFieldByName("npc"))).toList();
  }

  void assertEntrance(DynamicMessage message) {
    assertTrue(entities(message).stream().noneMatch(e -> Objects.equals(24, field(e, "group_id"))),
        "Both the original trigger and NPC must load on the client, without duplicate server copies");
    assertTrue(((List<?>) field((DynamicMessage) field(message, "scene_mission_info"),
        "finished_main_mission_id_list")).contains(2010720));
    assertEquals(1, actor.scene.getEntitiesByGroup(EntityNpc.class, 24).size());
    assertEquals(1, actor.scene.getEntitiesByGroup(EntityProp.class, 24).size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"entry", "query"})
  void unlockedEntranceKeepsTheOriginalNpcAndTriggerLocal(String kind) throws Exception {
    complete(2010720);
    buildScene();
    assertEntrance(snapshot(kind));
    assertEquals(0, actor.saves);
  }

  @Test
  void finishingThePrerequisiteKeepsRefreshAndRemovalOnTheLocalRoute() throws Exception {
    actor.getGameplay().getMainMissions().put(2010701, new QuestState(2010701));
    buildScene();
    snapshot("query");
    assertFalse(actor.scene.getLoadedGroups().contains(24));
    actor.packets.clear();
    complete(2010720);
    actor.scene.refreshMissionGroups();
    var published = new ArrayList<DynamicMessage>();
    for (var packet : actor.packets)
      published.addAll(npcs(nativePacket("SceneGroupRefreshScNotify", packet.build())));
    assertTrue(published.isEmpty(), "Refreshing the original local group must not duplicate its NPC");
    var npc = actor.scene.getEntitiesByGroup(EntityNpc.class, 24).getFirst();
    actor.scene.removeEntity(actor.scene.getEntitiesByGroup(EntityProp.class, 24).getFirst());
    actor.packets.clear();
    actor.scene.removeEntity(npc);
    for (var packet : actor.packets) {
      var removal = nativePacket("SceneGroupRefreshScNotify", packet.build());
      assertTrue(((List<?>) field(removal, "group_refresh_list")).isEmpty());
    }
  }

  @Test
  void lockedEntranceDoesNotSpawnAndTheNpcOnlyStoryGroupStaysLocal() throws Exception {
    actor.getGameplay().getMainMissions().put(2010701, new QuestState(2010701));
    buildScene();
    assertFalse(actor.scene.getLoadedGroups().contains(24));
    assertEquals(1, actor.scene.getEntitiesByGroup(EntityNpc.class, 5).size());
    assertTrue(npcs(snapshot("query")).isEmpty(), "The client-owned story NPC must not be duplicated");
    assertFalse(actor.getGameplay().getMainMissions().containsKey(2010720));
    assertEquals(0, actor.saves);
  }

  @Test
  void reentryKeepsOneOriginalNpcWithoutChangingSavedProgress() throws Exception {
    complete(2010720);
    String before = gson.toJson(actor.getGameplay());
    buildScene();
    assertEntrance(snapshot("query"));
    buildScene();
    assertEntrance(snapshot("entry"));
    assertEquals(before, gson.toJson(actor.getGameplay()));
    assertEquals(0, actor.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"entry", "query"})
  void sceneEntryAndQueryIncludeDrDigsCurrentDialogueGate(String kind) throws Exception {
    actor.getGameplay().getMainMissions().put(2010701, new QuestState(2010701));
    actor.getGameplay().getSubMissions().put(201070102, new QuestState(201070102));
    buildScene();
    assertEquals(1, actor.scene.getEntitiesByGroup(EntityNpc.class, 5).size());
    var scene = snapshot(kind);
    var gates = (DynamicMessage) field(scene, "scene_mission_info");
    var rows = ((List<?>) field(gates, "sub_mission_status_list")).stream()
        .map(DynamicMessage.class::cast).filter(row -> Objects.equals(201070102, field(row, "id"))).toList();
    assertEquals(1, rows.size(), "The NPC's EnableCondition was omitted from the local scene gates");
    assertEquals(0, field(rows.getFirst(), "progress"));
    assertEquals("MissionStatus_MissionDoing", ((com.google.protobuf.Descriptors.EnumValueDescriptor)
        field(rows.getFirst(), "status")).getName());
    assertEquals(0, actor.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"cancelled", "wrongIdentity", "retired"})
  void dialogueProjectionNeverReopensCancelledCorruptOrRetiredObjectives(String damage) throws Exception {
    actor.getGameplay().getMainMissions().put(2010701, new QuestState(2010701));
    actor.getGameplay().getSubMissions().put(201070102, new QuestState(201070102));
    switch (damage) {
      case "cancelled" -> actor.getGameplay().getCancelledSubs().add(201070102);
      case "wrongIdentity" -> actor.getGameplay().getSubMissions().put(201070102, new QuestState(201070103));
      case "retired" -> complete(2010701);
      default -> fail("Unknown fixture");
    }
    buildScene();
    var gates = (DynamicMessage) field(snapshot("query"), "scene_mission_info");
    assertTrue(((List<?>) field(gates, "sub_mission_status_list")).stream()
        .map(DynamicMessage.class::cast).noneMatch(row -> Objects.equals(201070102, field(row, "id"))));
    assertEquals(0, actor.saves);
  }

  @Test
  void readingNativeDialogueGatesPreservesTheExistingTypedResourceFingerprint() {
    var actual = floor.getGroupInfoByIndex(4);
    assertEquals(5, actual.getId());
    var dialogue = actual.getNPCList().getFirst().getDialog();
    assertNotNull(dialogue.getEnableCondition());
    assertEquals(7, dialogue.getEnableCondition().getConditions().length);
    var serialized = gson.toJsonTree(actual).getAsJsonObject();
    var npc = serialized.getAsJsonArray("NPCList").get(0).getAsJsonObject();
    assertFalse(npc.getAsJsonObject("Dialog").has("EnableCondition"),
        "Runtime dialogue gates must not invalidate existing source-bound task fingerprints");
    var ordinary = new Gson().fromJson(gson.toJson(actual), GroupInfo.class);
    assertEquals(TimelineActivityRules.fingerprint(new Gson().toJsonTree(ordinary)),
        TimelineActivityRules.fingerprint(new Gson().toJsonTree(actual)));
  }
}
