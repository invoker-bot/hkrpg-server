package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.util.Position;
import java.util.*;
import org.junit.jupiter.api.*;

class SceneDimensionTransitionTest {
  static final int PLANE = 199935, FLOOR = 19993501, ENTRY = 19993501, OTHER = 19993502;
  final Gson gson = new Gson();
  Map<String, FloorInfo> oldFloors;
  List<MapEntranceExcel> oldEntrances;
  MazePlaneExcel plane;

  static class Visitor extends Player {
    final PlayerLineup lineup = new PlayerLineup(this, 0);
    Scene current;
    int moves;

    public PlayerLineup getCurrentLineup() {
      return lineup;
    }

    public Scene getScene() {
      return current;
    }

    public void save() {}

    public boolean loadScene(
        int plane, int floor, int entry, Position pos, Position rot, boolean send) {
      moves++;
      return true;
    }
  }

  @BeforeEach
  void prepare() {
    oldFloors = new HashMap<>(GameData.getFloorInfos());
    oldEntrances = new ArrayList<>(GameData.getMapEntranceExcelMap().values());
    plane = gson.fromJson("{\"PlaneID\":199935,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    for (int id : List.of(ENTRY, OTHER)) {
      var floor =
          gson.fromJson(
              "{\"FloorID\":"
                  + id
                  + ",\"DimensionList\":[{\"ID\":0,\"GroupIndexList\":[0]},{\"ID\":4,\"GroupIndexList\":[1]}],\"GroupInstanceList\":[{\"ID\":8},{\"ID\":9}]}",
              FloorInfo.class);
      for (int groupId : List.of(8, 9)) {
        var group =
            gson.fromJson(
                "{\"GroupName\":\"Anchor"
                    + " dimension\",\"LoadSide\":\"Server\",\"PropList\":[],\"AnchorList\":[{\"ID\":1}]}",
                GroupInfo.class);
        group.setId(groupId);
        floor.getGroups().put(groupId, group);
      }
      GameData.getFloorInfos().put("P" + PLANE + "_F" + id, floor);
      GameData.getMapEntranceExcelMap()
          .add(
              gson.fromJson(
                  "{\"ID\":"
                      + id
                      + ",\"PlaneID\":"
                      + PLANE
                      + ",\"FloorID\":"
                      + id
                      + ",\"StartGroupID\":8,\"StartAnchorID\":1}",
                  MapEntranceExcel.class));
    }
  }

  @AfterEach
  void restore() {
    GameData.getFloorInfos().clear();
    GameData.getFloorInfos().putAll(oldFloors);
    GameData.getMapEntranceExcelMap().keySet().clear();
    oldEntrances.forEach(GameData.getMapEntranceExcelMap()::add);
  }

  @Test
  void ordinaryStoryActionsCannotTeleportIntoAnAnchorFromAnAlternateDimension() {
    var player = new Visitor();
    player.current = new Scene(player, plane, FLOOR);
    assertTrue(player.hasSceneAnchor(ENTRY, 8, 1));
    assertFalse(player.hasSceneAnchor(ENTRY, 9, 1));
    assertFalse(player.enterSceneAtAnchor(ENTRY, 9, 1, false));
    assertEquals(0, player.moves);
    assertTrue(player.enterSceneAtAnchor(ENTRY, 8, 1, false));
    assertEquals(1, player.moves);
  }

  @Test
  void anExplicitCurrentDimensionOnlyAcceptsItsOwnAnchorsOnThatFloor() {
    var player = new Visitor();
    player.current = new Scene(player, plane, FLOOR, 4);
    assertTrue(player.hasSceneAnchor(ENTRY, 9, 1));
    assertFalse(player.hasSceneAnchor(ENTRY, 8, 1));
    assertFalse(player.enterSceneAtAnchor(ENTRY, 8, 1, false));
    assertTrue(player.enterSceneAtAnchor(ENTRY, 9, 1, false));
    assertEquals(1, player.moves);
  }

  @Test
  void aDefaultEntranceCannotBypassItsCurrentDimensionsAnchorMembership() {
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":19993501,\"PlaneID\":199935,\"FloorID\":19993501,\"StartGroupID\":9,\"StartAnchorID\":1}",
                MapEntranceExcel.class));
    var player = new Visitor();
    player.current = new Scene(player, plane, FLOOR);
    assertFalse(player.enterScene(ENTRY, 0, false));
    assertEquals(0, player.moves);
    player.current = new Scene(player, plane, FLOOR, 4);
    assertTrue(player.enterScene(ENTRY, 0, false));
    assertEquals(1, player.moves);
  }

  @Test
  void anotherFloorDoesNotInheritAStoryDimensionWithoutAnAuthorizedStoryTransition() {
    var player = new Visitor();
    player.current = new Scene(player, plane, FLOOR, 4);
    assertTrue(player.hasSceneAnchor(OTHER, 8, 1));
    assertFalse(player.hasSceneAnchor(OTHER, 9, 1));
    assertFalse(player.enterSceneAtAnchor(OTHER, 9, 1, false));
    assertEquals(0, player.moves);
  }
}
