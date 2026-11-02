package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original train G2/300001/2215 must be resolvable before the player enters the train. */
class TrainMapAnchor46Test {
  static final int PLANE = 10000, FLOOR = 10000000, ENTRY = 1000001;
  final Gson gson = emu.lunarcore.data.ResourceLoader.getGson();
  final Map<Integer, MapEntranceExcel> oldEntries = new HashMap<>();
  MazePlaneExcel oldPlane;
  PropExcel oldProp;
  FloorInfo oldFloor;
  JsonObject source;
  FloorInfo floor;
  Scene current;
  boolean onTrain;
  Gameplay46HandlerTest.Session session;

  @BeforeEach
  void prepare() throws Exception {
    source = read("train-map-anchor-4.6.json").getAsJsonObject();
    oldPlane = GameData.getMazePlaneExcelMap().get(PLANE);
    oldProp = GameData.getPropExcelMap().get(110);
    oldFloor = GameData.getFloorInfo(PLANE, FLOOR);
    GameData.getMazePlaneExcelMap().add(gson.fromJson(source.get("plane"), MazePlaneExcel.class));
    var prop = gson.fromJson(source.get("prop"), PropExcel.class);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
    for (var value : read("scene-map-train-entrances-4.6.json").getAsJsonArray()) {
      var entry = gson.fromJson(value, MapEntranceExcel.class);
      oldEntries.put(entry.getId(), GameData.getMapEntranceExcelMap().get(entry.getId()));
      GameData.getMapEntranceExcelMap().add(entry);
    }
    install(source.getAsJsonObject("group").deepCopy(), false);
    var actor = new Player() {
      final PlayerLineup team = new PlayerLineup(this, 0);
      final emu.lunarcore.util.Position pos = new emu.lunarcore.util.Position();
      final emu.lunarcore.util.Position rot = new emu.lunarcore.util.Position();
      public int getPlaneId() { return onTrain ? PLANE : 10101; }
      public int getFloorId() { return onTrain ? FLOOR : 10101001; }
      public int getEntryId() { return onTrain ? ENTRY : 101010102; }
      public Scene getScene() { return current; }
      public emu.lunarcore.util.Position getPos() { return pos; }
      public emu.lunarcore.util.Position getRot() { return rot; }
      public PlayerLineup getCurrentLineup() {
        assertTrue(onTrain, "A remote map query must not construct a scene or touch avatars");
        if (team.size() == 0) team.getAvatars().add(999999);
        return team;
      }
      public void save() { fail("A map query must not save or advance the player"); }
      public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {}
    };
    session = new Gameplay46HandlerTest.Session() {
      public Player getPlayer() { return actor; }
    };
  }

  JsonElement read(String name) throws Exception {
    try (var reader = new InputStreamReader(Objects.requireNonNull(
        getClass().getResourceAsStream("/fixtures/" + name)), StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader);
    }
  }

  void install(JsonObject definition, boolean otherDimension) {
    floor = gson.fromJson("""
        {"FloorID":10000000,"StartAnchorID":1,"LayerToAreaMask":[264],
         "GroupInstanceList":[{"ID":2}],
         "DimensionList":[{"ID":0,"GroupIndexList":[0]}]}
        """, FloorInfo.class);
    if (otherDimension) {
      floor = gson.fromJson("""
          {"FloorID":10000000,"StartAnchorID":1,"GroupInstanceList":[{"ID":2}],
           "DimensionList":[{"ID":0,"GroupIndexList":[]},{"ID":1,"GroupIndexList":[0]}]}
          """, FloorInfo.class);
    }
    var group = gson.fromJson(definition, GroupInfo.class);
    group.setId(2);
    floor.registerGroup(0, group);
    floor.onLoad();
    GameData.getFloorInfos().put("P10000_F10000000", floor);
  }

  @AfterEach
  void restore() {
    oldEntries.forEach((id, value) -> {
      if (value == null) GameData.getMapEntranceExcelMap().keySet().remove(id);
      else GameData.getMapEntranceExcelMap().add(value);
    });
    if (oldPlane == null) GameData.getMazePlaneExcelMap().keySet().remove(PLANE);
    else GameData.getMazePlaneExcelMap().add(oldPlane);
    if (oldProp == null) GameData.getPropExcelMap().keySet().remove(110);
    else GameData.getPropExcelMap().add(oldProp);
    if (oldFloor == null) GameData.getFloorInfos().remove("P10000_F10000000");
    else GameData.getFloorInfos().put("P10000_F10000000", oldFloor);
  }

  DynamicMessage query() throws Exception {
    var request = Gameplay46.builder(session, "GetSceneMapInfoCsReq");
    var identifier = Gameplay46.builder(session, "SceneIdentifier");
    Gameplay46.set(identifier, "floor_id", FLOOR);
    Gameplay46.add(request, "scene_identifiers", identifier.build());
    assertTrue(VersionedPacketHandler.handle(session,
        session.getProtocol().clientCommand("GetSceneMapInfoCsReq"), request.build().toByteArray()));
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    var rows = (List<?>) Gameplay46.value(session.response, "scene_map_info");
    assertEquals(1, rows.size());
    return (DynamicMessage) rows.getFirst();
  }

  Set<Integer> groups(DynamicMessage map) {
    var result = new TreeSet<Integer>();
    for (var group : (List<?>) Gameplay46.value(map, "maze_group_list"))
      assertTrue(result.add(Gameplay46.number((DynamicMessage) group, "group_id")));
    return result;
  }

  @Test
  void remoteTrainMapContainsTheOriginalEnabledAnchorAndItsServerGroup() throws Exception {
    var player = session.getPlayer();
    String before = gson.toJson(player.getGameplay());
    var map = query();
    assertEquals(ENTRY, Gameplay46.number(map, "entry_id"));
    assertEquals(List.of(2215), Gameplay46.value(map, "unlock_teleport_list"));
    assertEquals(Set.of(2), groups(map),
        "The cross-map client filters anchor definitions by MazeGroup membership");
    var props = (List<?>) Gameplay46.value(map, "maze_prop_list");
    assertEquals(1, props.size());
    var prop = (DynamicMessage) props.getFirst();
    assertEquals(2, Gameplay46.number(prop, "group_id"));
    assertEquals(300001, Gameplay46.number(prop, "config_id"));
    assertEquals(PropState.CheckPointEnable.getVal(), Gameplay46.number(prop, "state"));
    assertEquals(List.of(), Gameplay46.value(map, "lighten_section_list"));
    assertNull(current);
    assertEquals(101010102, player.getEntryId());
    assertEquals(before, gson.toJson(player.getGameplay()));
  }

  @Test
  void currentMapIncludesALoadedAnchorGroupWithoutGroupProperties() throws Exception {
    onTrain = true;
    current = new Scene(session.getPlayer(), GameData.getMazePlaneExcelMap().get(PLANE), FLOOR);
    assertTrue(current.getLoadedGroups().contains(2));
    assertFalse(current.getGroups().containsKey(2), "The original anchor has no group properties");
    assertEquals(Set.of(2), groups(query()));
  }

  @Test
  void aSavedDisabledAnchorRemainsDisabledDespiteItsAuthoredEnabledDefault() throws Exception {
    session.getPlayer().getGameplay().getStoryValues().put(
        "prop-state:10000:10000000:2:300001", PropState.CheckPointDisable.getVal());
    var map = query();
    assertEquals(Set.of(2), groups(map));
    assertEquals(List.of(), Gameplay46.value(map, "unlock_teleport_list"));
    var prop = (DynamicMessage) ((List<?>) Gameplay46.value(map, "maze_prop_list")).getFirst();
    assertEquals(PropState.CheckPointDisable.getVal(), Gameplay46.number(prop, "state"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"client", "deferred", "mission", "atmosphere", "owner", "load",
      "unload", "saved-value", "value-source", "dimension", "deleted", "client-prop", "anchor"})
  void remoteQueriesDoNotInventGroupsOrAnchorsWhoseAvailabilityIsUnproven(String variant)
      throws Exception {
    var group = source.getAsJsonObject("group").deepCopy();
    var prop = group.getAsJsonArray("PropList").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(p -> p.get("ID").getAsInt() == 300001)
        .findFirst().orElseThrow();
    switch (variant) {
      case "client" -> group.addProperty("LoadSide", "Client");
      case "deferred" -> group.addProperty("LoadOnInitial", false);
      case "mission" -> group.addProperty("Category", "Mission");
      case "atmosphere" -> group.addProperty("Category", "Atmosphere");
      case "owner" -> group.addProperty("OwnerMainMissionID", 4050005);
      case "load" -> group.add("LoadCondition", JsonParser.parseString(
          "{\"Conditions\":[{\"ID\":405000502,\"Phase\":\"Finish\"}]}"));
      case "unload" -> group.add("UnloadCondition", JsonParser.parseString(
          "{\"Conditions\":[{\"ID\":405000502,\"Phase\":\"Finish\"}]}"));
      case "saved-value" -> group.add("SavedValueCondition", JsonParser.parseString(
          "{\"Conditions\":[{\"SavedValueName\":\"Utility_IsDebug\",\"Value\":1}]}"));
      case "value-source" -> group.add("ValueSource", JsonParser.parseString("{\"GroupID\":99}"));
      case "dimension" -> { /* Installed in a separate story dimension below. */ }
      case "deleted" -> prop.addProperty("IsDelete", true);
      case "client-prop" -> prop.addProperty("IsClientOnly", true);
      case "anchor" -> prop.addProperty("AnchorID", 999);
      default -> fail(variant);
    }
    install(group, variant.equals("dimension"));
    var map = query();
    assertEquals(Set.of(), groups(map));
    assertEquals(List.of(), Gameplay46.value(map, "unlock_teleport_list"));
    assertEquals(List.of(), Gameplay46.value(map, "maze_prop_list"));
    assertNull(current);
  }
}
