package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class SceneAnchor46Test {
  static final int PLANE = 199921, FLOOR = 199921001, ENTRY = 1999211, PROP = 199921;
  final Gson gson = new Gson();
  Gameplay46HandlerTest.Session session;
  FloorInfo floor;
  MapEntranceExcel oldEntry;
  PropExcel oldProp;
  MazePlaneExcel oldPlane;

  @BeforeEach
  void setup() throws Exception {
    oldEntry = GameData.getMapEntranceExcelMap().get(ENTRY);
    oldProp = GameData.getPropExcelMap().get(PROP);
    oldPlane = GameData.getMazePlaneExcelMap().get(PLANE);
    var plane =
        gson.fromJson("{\"PlaneID\":" + PLANE + ",\"PlaneType\":\"Maze\"}", MazePlaneExcel.class);
    GameData.getMazePlaneExcelMap().add(plane);
    var excel =
        gson.fromJson(
            "{\"ID\":"
                + PROP
                + ",\"PropType\":\"PROP_SPRING\",\"PropStateList\":[\"CheckPointDisable\",\"CheckPointEnable\"]}",
            PropExcel.class);
    excel.onLoad();
    GameData.getPropExcelMap().add(excel);
    var entry =
        gson.fromJson(
            "{\"ID\":"
                + ENTRY
                + ",\"PlaneID\":"
                + PLANE
                + ",\"FloorID\":"
                + FLOOR
                + ",\"StartGroupID\":8,\"StartAnchorID\":1}",
            MapEntranceExcel.class);
    GameData.getMapEntranceExcelMap().add(entry);
    floor = gson.fromJson("{\"FloorID\":" + FLOOR + "}", FloorInfo.class);
    var group =
        gson.fromJson(
            "{\"GroupName\":\"Anchor\",\"LoadSide\":\"Server\",\"AnchorList\":[{\"ID\":1}],\"PropList\":["
                + "{\"ID\":300001,\"PropID\":"
                + PROP
                + ",\"MappingInfoID\":19992101,\"AnchorGroupID\":8,\"AnchorID\":1,\"State\":\"CheckPointDisable\"},"
                + "{\"ID\":300002,\"PropID\":"
                + PROP
                + ",\"MappingInfoID\":19992102,\"AnchorGroupID\":8,\"AnchorID\":1,\"State\":\"CheckPointEnable\"}]}",
            GroupInfo.class);
    group.setId(8);
    floor.getGroups().put(8, group);
    floor.onLoad();
    GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR, floor);
    var actor =
        new Player() {
          final PlayerLineup team = new PlayerLineup(this, 0);
          final emu.lunarcore.util.Position position = new emu.lunarcore.util.Position();
          final emu.lunarcore.util.Position rotation = new emu.lunarcore.util.Position();

          public emu.lunarcore.util.Position getPos() {
            return position;
          }

          public emu.lunarcore.util.Position getRot() {
            return rotation;
          }

          Scene current;

          public int getPlaneId() {
            return PLANE;
          }

          public int getFloorId() {
            return FLOOR;
          }

          public int getEntryId() {
            return ENTRY;
          }

          public PlayerLineup getCurrentLineup() {
            if (team.size() == 0) team.getAvatars().add(999999);
            return team;
          }

          public void save() {}

          public void sendPacket(emu.lunarcore.server.packet.BasePacket p) {}

          public Scene getScene() {
            return current == null
                ? (current =
                    new Scene(
                        this,
                        gson.fromJson(
                            "{\"PlaneID\":" + PLANE + ",\"PlaneType\":\"Maze\"}",
                            MazePlaneExcel.class),
                        FLOOR))
                : current;
          }
        };
    session =
        new Gameplay46HandlerTest.Session() {
          public Player getPlayer() {
            return actor;
          }
        };
    var savedScene = Player.class.getDeclaredField("scene");
    savedScene.setAccessible(true);
    savedScene.set(actor, actor.getScene());
  }

  @AfterEach
  void cleanup() {
    if (oldEntry == null) GameData.getMapEntranceExcelMap().keySet().remove(ENTRY);
    else GameData.getMapEntranceExcelMap().add(oldEntry);
    if (oldProp == null) GameData.getPropExcelMap().keySet().remove(PROP);
    else GameData.getPropExcelMap().add(oldProp);
    if (oldPlane == null) GameData.getMazePlaneExcelMap().keySet().remove(PLANE);
    else GameData.getMazePlaneExcelMap().add(oldPlane);
    GameData.getFloorInfos().remove("P" + PLANE + "_F" + FLOOR);
  }

  void queryUnlocks() throws Exception {
    var req = Gameplay46.builder(session, "GetUnlockTeleportCsReq");
    Gameplay46.add(req, "entry_id_list", ENTRY);
    assertTrue(VersionedPacketHandler.handle(session, 1450, req.build().toByteArray()));
  }

  MissionInfo.SubMissionInfo action(int instance) {
    return gson.fromJson(
        "{\"ID\":19992109,\"FinishActionList\":[{\"FinishActionType\":\"ActivateAnchor\",\"FinishActionPara\":["
            + ENTRY
            + ",8,"
            + instance
            + "]}]}",
        MissionInfo.SubMissionInfo.class);
  }

  @Test
  void configuredDefaultsAndPlayerActivationAreDistinctAndMapReloadRestoresTheAnchor()
      throws Exception {
    assertEquals(
        PropState.CheckPointDisable, floor.getGroups().get(8).getPropList().getFirst().getState());
    queryUnlocks();
    assertEquals(List.of(19992102), Gameplay46.value(session.response, "unlocked_teleport_list"));
    assertEquals(Retcode.SUCC, new StoryActions(session.getPlayer()).execute(action(300001)));
    queryUnlocks();
    assertEquals(
        Set.of(19992101, 19992102),
        new HashSet<>((List<?>) Gameplay46.value(session.response, "unlocked_teleport_list")));
    assertEquals(
        PropState.CheckPointDisable, floor.getGroups().get(8).getPropList().getFirst().getState());
    var restored = new Scene(session.getPlayer(), session.getPlayer().getScene().getExcel(), FLOOR);
    assertTrue(
        restored.getEntities().values().stream()
            .filter(EntityProp.class::isInstance)
            .map(EntityProp.class::cast)
            .allMatch(p -> p.getState() == PropState.CheckPointEnable));
    assertEquals(Retcode.SUCC, new StoryActions(session.getPlayer()).execute(action(300001)));
  }

  @Test
  void anInvalidAnchorPreventsEarlierActionsAndLockedOrMissingTeleportIdsNeverFallBack() {
    var bad =
        gson.fromJson(
            "{\"ID\":19992108,\"FinishActionList\":["
                + "{\"FinishActionType\":\"SetCustomValue\",\"FinishActionParaString\":[\"Before\",\"1\"]},"
                + "{\"FinishActionType\":\"ActivateAnchor\",\"FinishActionPara\":["
                + ENTRY
                + ",8,999]}]}",
            MissionInfo.SubMissionInfo.class);
    assertEquals(
        Retcode.MISSION_FINISH_WAY_NOT_MATCH, new StoryActions(session.getPlayer()).execute(bad));
    assertTrue(session.getPlayer().getGameplay().getStoryValues().isEmpty());
    assertFalse(session.getPlayer().enterScene(ENTRY, 19992101, false));
    assertFalse(session.getPlayer().enterScene(ENTRY, 123456, false));
  }

  @Test
  void nativeMapQueryReportsActualAnchorStatesWithoutInventingExploration() throws Exception {
    var req = Gameplay46.builder(session, "GetSceneMapInfoCsReq");
    var ids = Gameplay46.builder(session, "SceneIdentifier");
    Gameplay46.set(ids, "floor_id", FLOOR);
    Gameplay46.add(req, "scene_identifiers", ids.build());
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("GetSceneMapInfoCsReq"),
            req.build().toByteArray()));
    var rows = (List<?>) Gameplay46.value(session.response, "scene_map_info");
    assertEquals(1, rows.size());
    var map = (com.google.protobuf.DynamicMessage) rows.getFirst();
    assertEquals(List.of(19992102), Gameplay46.value(map, "unlock_teleport_list"));
    assertEquals(List.of(), Gameplay46.value(map, "lighten_section_list"));
    var states = (List<?>) Gameplay46.value(map, "maze_prop_list");
    assertEquals(2, states.size());
    assertEquals(
        PropState.CheckPointDisable.getVal(),
        Gameplay46.number((com.google.protobuf.DynamicMessage) states.getFirst(), "state"));
  }

  @Test
  void nativeTeleportReturnsFailureForLockedMissingOrUnknownSceneFields() throws Exception {
    for (int id : List.of(19992101, 123456)) {
      var req = Gameplay46.builder(session, "EnterSceneCsReq");
      Gameplay46.set(req, "entry_id", ENTRY);
      Gameplay46.set(req, "teleport_id", id);
      assertTrue(
          VersionedPacketHandler.handle(
              session,
              session.getProtocol().clientCommand("EnterSceneCsReq"),
              req.build().toByteArray()));
      assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
    }
    var req = Gameplay46.builder(session, "EnterSceneCsReq");
    Gameplay46.set(req, "entry_id", ENTRY);
    Gameplay46.set(req, "FBPKOFOIOLC", true);
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("EnterSceneCsReq"),
            req.build().toByteArray()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    Gameplay46.set(req, "FBPKOFOIOLC", false);
    Gameplay46.set(req, "teleport_id", 19992102);
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("EnterSceneCsReq"),
            req.build().toByteArray()));
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    assertEquals(
        FLOOR,
        Gameplay46.number(
            (com.google.protobuf.DynamicMessage)
                Gameplay46.value(session.response, "scene_identifier"),
            "floor_id"));
  }
}
