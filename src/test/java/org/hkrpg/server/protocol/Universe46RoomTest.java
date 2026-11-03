package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.SceneInfoOuterClass.SceneInfo;
import emu.lunarcore.server.packet.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class Universe46RoomTest {
  static final int MAP = 199986, ROOM1 = 19998601, ROOM2 = 19998602;

  static class RoomSession extends Gameplay46HandlerTest.Session {
    final RoomPlayer player = new RoomPlayer();

    static class RoomPlayer extends Player {
      final PlayerLineup lineup =
          new PlayerLineup(this, 0) {
            public int getMp() {
              return 5;
            }
          };
      int entry = 11;
      int enteredGroup, enteredAnchor;
      boolean rejectScene;

      public void save() {}

      public PlayerLineup getCurrentLineup() {
        return lineup;
      }

      public int getEntryId() {
        return entry;
      }

      public Scene getScene() {
        var plane =
            new Gson()
                .fromJson("{\"PlaneID\":199986,\"PlaneType\":\"Rogue\"}", MazePlaneExcel.class);
        return new Scene(this, plane, 199986001) {
          public SceneInfo toProto() {
            return SceneInfo.newInstance().setPlaneId(MAP).setFloorId(199986001).setEntryId(entry);
          }
        };
      }

      public boolean enterScene(int id, int teleport, boolean send) {
        if (rejectScene) return false;
        entry = id;
        return true;
      }

      @Override public boolean enterSceneAtAnchor(int id, int group, int anchor, boolean send) {
        enteredGroup = group; enteredAnchor = anchor;
        return enterScene(id, 0, send);
      }
    }

    public Player getPlayer() {
      return player;
    }

    DynamicMessage enter(int site, int room) throws Exception {
      var request = Gameplay46.builder(this, "EnterRogueMapRoomCsReq");
      Gameplay46.set(request, "MAJKFIMPOOF", site);
      Gameplay46.set(request, "room_id", room);
      assertTrue(
          VersionedPacketHandler.handle(
              this,
              getProtocol().clientCommand("EnterRogueMapRoomCsReq"),
              request.build().toByteArray()));
      assertEquals("EnterRogueMapRoomScRsp", responseName);
      return response;
    }
  }

  @Test
  void nativeRoomSceneFailureRollsBackWithoutChangingProgressOrRewards() throws Exception {
    var session = new RoomSession();
    var run = new GameplayState.RogueRun(100, MAP, Map.of(1, ROOM1, 2, ROOM2));
    run.enter(1, ROOM1);
    run.clear();
    session.player.getGameplay().setRogue(run);
    session.player.rejectScene = true;
    assertEquals(
        Retcode.ROGUE_AREA_INVALID.getVal(),
        InitialData46Test.field(session.enter(2, ROOM2), "retcode"));
    assertEquals(1, run.getSiteId());
    assertEquals(ROOM1, run.getRoomId());
    assertEquals(11, session.player.getEntryId());
    assertEquals(Set.of(1), run.getCleared());
    assertTrue(session.player.getGameplay().getRewards().isEmpty());
  }

  @BeforeEach
  void setup() {
    GameplayDefinitions.maps.put(
        MAP,
        List.of(
            new GameplayDefinitions.Site(MAP, 1, true, List.of(2), 1),
            new GameplayDefinitions.Site(MAP, 2, false, List.of(), 1)));
    GameplayDefinitions.rooms.put(ROOM1, new GameplayDefinitions.Room(ROOM1, 1, 11, 1));
    GameplayDefinitions.rooms.put(ROOM2, new GameplayDefinitions.Room(ROOM2, 1, 22, 2));
  }

  @AfterEach
  void reset() {
    GameplayDefinitions.maps.remove(MAP);
    GameplayDefinitions.rooms.remove(ROOM1);
    GameplayDefinitions.rooms.remove(ROOM2);
  }

  @Test
  void nativeRoomEntryRequiresAssignedRoomClearedPredecessorAndCompletedChoice() throws Exception {
    var session = new RoomSession();
    var run = new GameplayState.RogueRun(100, MAP, Map.of(1, ROOM1, 2, ROOM2));
    run.enter(1, ROOM1);
    session.player.getGameplay().setRogue(run);
    assertEquals(
        Retcode.ROGUE_STATUS_NOT_MATCH.getVal(),
        InitialData46Test.field(session.enter(2, ROOM2), "retcode"));
    run.clear();
    assertEquals(
        Retcode.ROGUE_AREA_INVALID.getVal(),
        InitialData46Test.field(session.enter(2, ROOM1), "retcode"));
    run.offer(List.of(100001));
    assertEquals(
        Retcode.ROGUE_STATUS_NOT_MATCH.getVal(),
        InitialData46Test.field(session.enter(2, ROOM2), "retcode"));
    assertEquals(1, run.getSiteId());
    assertEquals(11, session.player.getEntryId());
    run.select(100001);
    var response = session.enter(2, ROOM2);
    assertEquals(0, InitialData46Test.field(response, "retcode"));
    assertEquals(2, InitialData46Test.field(response, "ANHDBMOPGDC"));
    assertEquals(
        22,
        InitialData46Test.field(
            (DynamicMessage) InitialData46Test.field(response, "scene"), "entry_id"));
    assertEquals(2, run.getSiteId());
    assertEquals(ROOM2, run.getRoomId());
    assertEquals(2, session.player.enteredGroup);
    assertEquals(1, session.player.enteredAnchor);
    assertEquals(Set.of(1), run.getCleared());
    assertTrue(session.player.getGameplay().getRewards().isEmpty());
    assertEquals(
        Retcode.ROGUE_STATUS_NOT_MATCH.getVal(),
        InitialData46Test.field(session.enter(2, ROOM2), "retcode"));
  }
}
