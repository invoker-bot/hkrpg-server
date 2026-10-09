package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.SceneInfoOuterClass.SceneInfo;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class Universe46LifecycleTest {
  static final int AREA = 19996, MAP = 1999601, ROOM = 19996001;

  static class ExplorerSession extends Gameplay46HandlerTest.Session {
    final UniverseLifecycleTest.Explorer explorer =
        new UniverseLifecycleTest.Explorer() {
          public Scene getScene() {
            var parent = super.getScene();
            return new Scene(this, parent.getExcel(), getFloorId()) {
              public SceneInfo toProto() {
                return SceneInfo.newInstance()
                    .setEntryId(getPlayer().getEntryId())
                    .setPlaneId(getPlayer().getPlaneId())
                    .setFloorId(getPlayer().getFloorId());
              }
            };
          }
        };

    public Player getPlayer() {
      return explorer;
    }

    DynamicMessage call(String name, Integer area) throws Exception {
      var req = Gameplay46.builder(this, name);
      if (area != null) Gameplay46.set(req, "area_id", area);
      assertTrue(
          VersionedPacketHandler.handle(
              this, getProtocol().clientCommand(name), req.build().toByteArray()));
      assertEquals(name.replace("CsReq", "ScRsp"), responseName);
      return response;
    }

    DynamicMessage start(List<Integer> ids, int aeon) throws Exception {
      var req = Gameplay46.builder(this, "StartRogueCsReq");
      Gameplay46.set(req, "area_id", AREA);
      Gameplay46.set(req, "AFEMBLFCCCH", aeon);
      ids.forEach(id -> Gameplay46.add(req, "NOEPBNMJFGL", id));
      assertTrue(
          VersionedPacketHandler.handle(
              this, getProtocol().clientCommand("StartRogueCsReq"), req.build().toByteArray()));
      assertEquals("StartRogueScRsp", responseName);
      return response;
    }
  }

  @BeforeEach
  void setup() {
    GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", "50");
    GameplayDefinitions.areas.put(AREA, new GameplayDefinitions.Area(AREA, 19996, 1, 0, 0));
    GameplayDefinitions.maps.put(
        MAP, List.of(new GameplayDefinitions.Site(MAP, 1, true, List.of(), 1)));
    GameplayDefinitions.rooms.put(ROOM, new GameplayDefinitions.Room(ROOM, 1, 12, 8));
    GameplayDefinitions.roomPool.put(1, List.of(ROOM));
    GameplayDefinitions.rogueSeasons.add(
        new GameplayDefinitions.RogueSeason(
            19996, 1, List.of(AREA), "2024-01-01 00:00:00", "2099-01-01 00:00:00"));
  }

  @AfterEach
  void reset() {
    GameplayDefinitions.rogueConstants.remove("Start_Rogue_Coin_Base");
    GameplayDefinitions.areas.remove(AREA);
    GameplayDefinitions.maps.remove(MAP);
    GameplayDefinitions.rooms.remove(ROOM);
    GameplayDefinitions.roomPool.remove(1);
    GameplayDefinitions.rogueSeasons.removeIf(s -> s.RogueSeason() == 19996);
  }

  @Test
  void nativeLeaveAndReenterUseTheSameRunAndReturnActualSceneAndDedicatedLineup() throws Exception {
    var session = new ExplorerSession();
    assertEquals(0, InitialData46Test.field(session.start(List.of(199961), 0), "retcode"));
    var run = session.explorer.getGameplay().getRogue();
    String id = run.getRunId();
    var left = session.call("LeaveRogueCsReq", null);
    assertEquals(0, InitialData46Test.field(left, "retcode"));
    assertEquals(
        11, InitialData46Test.field((DynamicMessage) Gameplay46.value(left, "scene"), "entry_id"));
    assertEquals(
        "ExtraLineupType_LineupNone",
        InitialData46Test.field(
                (DynamicMessage) Gameplay46.value(left, "lineup"), "extra_lineup_type")
            .toString());
    assertFalse(run.isInside());
    var entered = session.call("EnterRogueCsReq", AREA);
    assertEquals(0, InitialData46Test.field(entered, "retcode"));
    assertEquals(
        12,
        InitialData46Test.field((DynamicMessage) Gameplay46.value(entered, "scene"), "entry_id"));
    assertEquals(
        "ExtraLineupType_LineupRogue",
        InitialData46Test.field(
                (DynamicMessage) Gameplay46.value(entered, "lineup"), "extra_lineup_type")
            .toString());
    var team = (DynamicMessage) Gameplay46.value(entered, "lineup");
    assertEquals(
        199961, InitialData46Test.field(InitialData46Test.first(team, "avatar_list"), "id"));
    var game = (DynamicMessage) Gameplay46.value(entered, "rogue_game_info");
    var current = (DynamicMessage) Gameplay46.value(game, "CFDIKLBINHK");
    var downloaded = (DynamicMessage) Gameplay46.value(current, "EIOHFJMFOKF");
    assertEquals(List.of(199961), Gameplay46.value(downloaded, "NOEPBNMJFGL"));
    assertEquals(id, run.getRunId());
    assertTrue(run.isInside());
    assertEquals(
        Retcode.ROGUE_STATUS_NOT_MATCH.getVal(),
        InitialData46Test.field(session.call("QuitRogueCsReq", AREA), "retcode"));
    session.call("LeaveRogueCsReq", null);
    assertEquals(0, InitialData46Test.field(session.call("QuitRogueCsReq", AREA), "retcode"));
    assertEquals(GameplayState.RogueRun.Phase.ABANDONED, run.getPhase());
    assertEquals(
        Retcode.ROGUE_STATUS_NOT_MATCH.getVal(),
        InitialData46Test.field(session.call("EnterRogueCsReq", AREA), "retcode"));
    assertTrue(session.explorer.getGameplay().getRewards().isEmpty());
  }

  @Test
  void nativeStartRejectsUnownedDuplicateAndUnsupportedAeonBeforeCreatingAnyRun() throws Exception {
    var session = new ExplorerSession();
    assertNotEquals(0, InitialData46Test.field(session.start(List.of(199962), 0), "retcode"));
    assertNotEquals(
        0, InitialData46Test.field(session.start(List.of(199961, 199961), 0), "retcode"));
    assertNotEquals(0, InitialData46Test.field(session.start(List.of(199961), 10), "retcode"));
    assertEquals(
        GameplayState.RogueRun.Phase.IDLE, session.explorer.getGameplay().getRogue().getPhase());
    assertEquals(11, session.explorer.getEntryId());
    assertTrue(session.explorer.getGameplay().getRewards().isEmpty());
  }

  @Test
  void noActiveRunOrMismatchedAreaCannotManufactureAnEnterOrQuitSuccess() throws Exception {
    var session = new ExplorerSession();
    assertEquals(
        Retcode.ROGUE_STATUS_NOT_MATCH.getVal(),
        InitialData46Test.field(session.call("LeaveRogueCsReq", null), "retcode"));
    assertEquals(
        Retcode.ROGUE_STATUS_NOT_MATCH.getVal(),
        InitialData46Test.field(session.call("EnterRogueCsReq", AREA), "retcode"));
    assertEquals(Retcode.SUCC, session.explorer.getUniverseManager().start(AREA));
    assertEquals(
        Retcode.ROGUE_AREA_INVALID.getVal(),
        InitialData46Test.field(session.call("QuitRogueCsReq", AREA + 1), "retcode"));
    assertTrue(session.explorer.getGameplay().getRogue().isInside());
  }
}
