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
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.send.PacketEnterSceneByServerScNotify;
import emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original prop-only groups, exercised through the 4.6 scene publication path. */
class ClientInvestigationGroups46Test {
  private static final int PROP = 120, SUPPLY = 20003001, FISSURE_GROUP = 106;
  private static final int STATION_GATE = 1000401, HOTEL = 10101007, HOTEL_GATE = 2011402;
  private final Gson gson = ResourceLoader.getGson();
  private ClientNpcOwnership46Test.Actor player;
  private PropExcel oldProp;
  private FloorInfo oldFloor;
  private String floorKey;
  private JsonObject sites;

  @BeforeEach
  void prepare() throws Exception {
    try (var input = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/client-investigation-groups-4.6.json")), StandardCharsets.UTF_8)) {
      var fixture = JsonParser.parseReader(input).getAsJsonObject();
      sites = fixture.getAsJsonObject("sites");
      var raw = fixture.getAsJsonObject("prop");
      raw.addProperty("PropName", raw.getAsJsonObject("PropName").get("Hash").getAsLong());
      var prop = gson.fromJson(raw, PropExcel.class);
      prop.onLoad();
      oldProp = GameData.getPropExcelMap().get(PROP);
      GameData.getPropExcelMap().add(prop);
    }
  }

  @AfterEach
  void restore() {
    if (floorKey != null) {
      if (oldFloor == null) GameData.getFloorInfos().remove(floorKey);
      else GameData.getFloorInfos().put(floorKey, oldFloor);
    }
    GameData.getPropExcelMap().keySet().remove(PROP);
    if (oldProp != null) GameData.getPropExcelMap().add(oldProp);
  }

  @ParameterizedTest
  @ValueSource(ints = {10101002, 10101004, 10101005, 10101007, 20002001, SUPPLY})
  void allVerifiedGroupsKeepLogicalPropsAndLeavePublicationToTheClient(int floorId)
      throws Exception {
    var site = site(floorId);
    int groupId = site.get("groupId").getAsInt();
    enter(floorId, groupId, site.getAsJsonObject("group"), true);
    var props = player.scene.getEntitiesByGroup(EntityProp.class, groupId);
    assertEquals(site.getAsJsonArray("active").asList().stream().map(v -> v.getAsInt()).sorted().toList(),
        props.stream().map(EntityProp::getInstId).sorted().toList());

    var entry = nativePacket("EnterSceneByServerScNotify",
        new PacketEnterSceneByServerScNotify(player).build());
    assertFalse(publishes((DynamicMessage) field(entry, "scene"), groupId));
    assertFalse(publishes(snapshot(), groupId));
  }

  @Test
  void stationPrerequisiteIsSynchronizedBeforeAndAfterTheGroupLoads() throws Exception {
    enter(SUPPLY, FISSURE_GROUP, site(SUPPLY).getAsJsonObject("group"), false);
    assertFalse(player.scene.getLoadedGroups().contains(FISSURE_GROUP));
    assertEquals(List.of(STATION_GATE), field(gates(snapshot()), "unfinished_main_mission_id_list"));

    player.getGameplay().getMainMissions().put(STATION_GATE, completed(STATION_GATE));
    player.scene.refreshMissionGroups();
    assertTrue(player.scene.getLoadedGroups().contains(FISSURE_GROUP));
    assertEquals(List.of(STATION_GATE), field(gates(snapshot()), "finished_main_mission_id_list"));
    assertFalse(publishes(snapshot(), FISSURE_GROUP));
  }

  @Test
  void hotelGroupKeepsItsOriginalUnloadGate() throws Exception {
    int group = site(HOTEL).get("groupId").getAsInt();
    enter(HOTEL, group, site(HOTEL).getAsJsonObject("group"), true);
    assertTrue(player.scene.getLoadedGroups().contains(group));

    player.getGameplay().getMainMissions().put(HOTEL_GATE, new QuestState(HOTEL_GATE));
    player.scene.refreshMissionGroups();
    assertFalse(player.scene.getLoadedGroups().contains(group));
    var scene = snapshot();
    assertFalse(publishes(scene, group));
    assertEquals(List.of(HOTEL_GATE), field(gates(scene), "unfinished_main_mission_id_list"));
  }

  @Test
  void refreshDoesNotPublishLocalAddsDeletesOrAnUnload() throws Exception {
    enter(SUPPLY, FISSURE_GROUP, site(SUPPLY).getAsJsonObject("group"), true);
    var prop = player.scene.getEntitiesByGroup(EntityProp.class, FISSURE_GROUP).getFirst();
    for (var packet : List.of(
        new PacketSceneGroupRefreshScNotify(player.scene, prop, null),
        new PacketSceneGroupRefreshScNotify(player.scene, null, prop),
        new PacketSceneGroupRefreshScNotify(player.scene, FISSURE_GROUP))) {
      var update = nativePacket("SceneGroupRefreshScNotify", packet.build());
      assertTrue(((List<?>) field(update, "group_refresh_list")).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void explicitServerAndChangedResourcesKeepPublishing(boolean explicitServer) throws Exception {
    var row = site(SUPPLY).getAsJsonObject("group").deepCopy();
    if (explicitServer) row.addProperty("LoadSide", "Server");
    else row.getAsJsonArray("PropList").get(0).getAsJsonObject().addProperty("PosX", 43);
    enter(SUPPLY, FISSURE_GROUP, row, true);
    assertTrue(publishes(snapshot(), FISSURE_GROUP));
  }

  @Test
  void theSameResourceUnderADifferentFloorKeepsItsCurrentPublication() throws Exception {
    enter(SUPPLY + 1, FISSURE_GROUP, site(SUPPLY).getAsJsonObject("group"), true);
    assertTrue(publishes(snapshot(), FISSURE_GROUP));
  }

  private JsonObject site(int floorId) {
    return sites.getAsJsonObject(Integer.toString(floorId));
  }

  private void enter(int floorId, int groupId, JsonObject row, boolean stationDone) {
    player = new ClientNpcOwnership46Test.Actor() {
      @Override public int getFloorId() { return floorId; }
      @Override public int getPlaneId() { return floorId / 1000; }
      @Override public int getEntryId() { return getPlaneId() * 100 + 1; }
    };
    if (stationDone) {
      player.getGameplay().getMainMissions().put(STATION_GATE, completed(STATION_GATE));
    }
    var floor = gson.fromJson("""
        {"FloorID":%d,"GroupInstanceList":[{"ID":%d}],"DimensionList":[{"ID":0,"GroupIndexList":[0]}]}
        """.formatted(floorId, groupId), FloorInfo.class);
    var group = gson.fromJson(row, GroupInfo.class);
    group.setId(groupId);
    floor.registerGroup(0, group);
    floor.onLoad();
    floorKey = "P" + player.getPlaneId() + "_F" + floorId;
    oldFloor = GameData.getFloorInfos().put(floorKey, floor);
    var plane = gson.fromJson("""
        {"PlaneID":%d,"PlaneType":"Maze","WorldID":101}
        """.formatted(player.getPlaneId()), MazePlaneExcel.class);
    player.scene = new Scene(player, plane, floorId);
    player.scene.setEntryId(player.getEntryId());
  }

  private DynamicMessage snapshot() throws Exception {
    var core = GetCurSceneInfoScRsp.newInstance().setScene(player.scene.toProto());
    var packet = PacketCodec.encode(CmdId.GetCurSceneInfoScRsp, new byte[0], core.toByteArray());
    return (DynamicMessage) field(nativePacket("GetCurSceneInfoScRsp", packet), "scene");
  }

  private DynamicMessage nativePacket(String name, byte[] core) throws Exception {
    var input = Unpooled.wrappedBuffer(player.wire.outboundFrames(core, player));
    try {
      var frame = Objects.requireNonNull(PacketCodec.decodeNext(input));
      assertEquals(player.wire.clientCommand(name), frame.cmdId());
      assertFalse(input.isReadable());
      return player.wire.clientSchema().parse(name, frame.body());
    } finally {
      input.release();
    }
  }

  private static boolean publishes(DynamicMessage scene, int group) {
    return ((List<?>) field(scene, "entity_group_list")).stream().map(DynamicMessage.class::cast)
        .anyMatch(row -> field(row, "group_id").equals(group));
  }

  private static DynamicMessage gates(DynamicMessage scene) {
    return (DynamicMessage) field(scene, "scene_mission_info");
  }
}
