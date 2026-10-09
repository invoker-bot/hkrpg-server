package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.hkrpg.server.gameplay.GameplayState;
import org.junit.jupiter.api.Test;

class Universe46Test {
  static class OnlineSession extends Gameplay46HandlerTest.Session {
    final List<DynamicMessage> notifications = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    final Player online =
        new Player() {
          public void save() {}

          public boolean isOnline() {
            return true;
          }

          public GameSession getSession() {
            return OnlineSession.this;
          }
        };

    public Player getPlayer() {
      return online;
    }

    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      names.add(name);
      notifications.add(response);
    }
  }

  @Test
  void choosingBlessingPublishesOneActualStateDeltaAndThenUnlocksAssignedSuccessor()
      throws Exception {
    var s = new OnlineSession();
    var notifications = s.notifications;
    var names = s.names;
    int mapId = 199987;
    GameplayDefinitions.maps.put(
        mapId,
        List.of(
            new GameplayDefinitions.Site(mapId, 1, true, List.of(2), 1),
            new GameplayDefinitions.Site(mapId, 2, false, List.of(), 1)));
    var run = new GameplayState.RogueRun(100, mapId, Map.of(1, 11, 2, 22));
    run.enter(1, 11);
    run.clear();
    run.offer(List.of(100005));
    s.getPlayer().getGameplay().setRogue(run);
    try {
      assertEquals("RogueRoomStatus_Lock", roomStatus(s, 2));
      var choice = Gameplay46.builder(s, "BPLNKHGAILK");
      Gameplay46.set(choice, "BAOFIGFFMLN", 100005);
      var request = Gameplay46.builder(s, "OFEDNNJIJFL");
      Gameplay46.set(request, "MPLGEEGOAMC", run.getSelectionId());
      Gameplay46.set(request, "DLKIKBIDJDB", choice.build());
      assertTrue(VersionedPacketHandler.handle(s, 5676, request.build().toByteArray()));
      assertEquals(1, Collections.frequency(names, "SyncRogueCommonActionResultScNotify"));
      var delta = notifications.get(names.indexOf("SyncRogueCommonActionResultScNotify"));
      assertEquals(101, InitialData46Test.field(delta, "DLOJFEEHLIJ"));
      var action = InitialData46Test.first(delta, "CLGGKLNPLCK");
      assertEquals(run.getSelectionId(), InitialData46Test.field(action, "queue_position"));
      var detail = (DynamicMessage) InitialData46Test.field(action, "detail");
      var buff = (DynamicMessage) InitialData46Test.field(detail, "IFOOEPKGGAG");
      assertEquals(100005, InitialData46Test.field(buff, "buff_id"));
      assertEquals(1, InitialData46Test.field(buff, "HAGDKKBAAKK"));
      assertEquals("RogueRoomStatus_Unlock", roomStatus(s, 2));
      assertEquals(2, Collections.frequency(names, "SyncRogueMapRoomScNotify"));
      var mapDelta = notifications.get(names.lastIndexOf("SyncRogueMapRoomScNotify"));
      assertEquals(mapId, InitialData46Test.field(mapDelta, "map_id"));
      var row = (DynamicMessage) InitialData46Test.field(mapDelta, "HNEANMNJHDB");
      assertEquals(22, InitialData46Test.field(row, "room_id"));
      assertEquals(2, InitialData46Test.field(row, "MAJKFIMPOOF"));
      assertTrue(VersionedPacketHandler.handle(s, 5676, request.build().toByteArray()));
      assertEquals(1, Collections.frequency(names, "SyncRogueCommonActionResultScNotify"));
      assertEquals(Retcode.SUCC, s.getPlayer().getUniverseManager().abandon());
      assertEquals("RogueRoomStatus_Lock", roomStatus(s, 2));
      assertEquals(4, Collections.frequency(names, "SyncRogueMapRoomScNotify"));
      mapDelta = notifications.get(names.lastIndexOf("SyncRogueMapRoomScNotify"));
      row = (DynamicMessage) InitialData46Test.field(mapDelta, "HNEANMNJHDB");
      assertEquals("RogueRoomStatus_Lock", InitialData46Test.field(row, "CAGDNHCOCKM").toString());
    } finally {
      GameplayDefinitions.maps.remove(mapId);
    }
  }

  private String roomStatus(Gameplay46HandlerTest.Session session, int site) {
    var map = Universe46.mapSnapshot(session);
    return ((List<?>) InitialData46Test.field(map, "LLNICHDDIAN"))
        .stream()
            .map(DynamicMessage.class::cast)
            .filter(room -> InitialData46Test.field(room, "MAJKFIMPOOF").equals(site))
            .map(room -> InitialData46Test.field(room, "CAGDNHCOCKM").toString())
            .findFirst()
            .orElseThrow();
  }

  @Test
  void catalogUsesOneActiveResourceSeasonAndNeverBypassesUnknownUnlockScripts() throws Exception {
    var season =
        new GameplayDefinitions.RogueSeason(
            19997, 1, List.of(19997010, 19997020), "2020-01-01 04:00:00", "2099-01-01 03:59:59");
    GameplayDefinitions.rogueSeasons.add(season);
    GameplayDefinitions.areas.put(19997010, new GameplayDefinitions.Area(19997010, 1, 1, 0, 0));
    GameplayDefinitions.areas.put(19997020, new GameplayDefinitions.Area(19997020, 2, 1, 0, 12001));
    try {
      assertSame(season, Universe46.activeSeason(java.time.Instant.parse("2026-10-01T00:00:00Z")));
      assertNull(Universe46.activeSeason(java.time.Instant.parse("2019-12-31T19:59:59Z")));
      assertNull(Universe46.activeSeason(java.time.Instant.parse("2098-12-31T20:00:00Z")));
      var s = new Gameplay46HandlerTest.Session();
      s.request("GetRogueInfoCsReq");
      assertEquals(0, InitialData46Test.field(s.response, "retcode"));
      var game = (DynamicMessage) InitialData46Test.field(s.response, "rogue_game_info");
      assertFalse(game.hasField(game.getDescriptorForType().findFieldByName("CFDIKLBINHK")));
      var basic = (DynamicMessage) InitialData46Test.field(game, "LFLCDNGALME");
      var areaList = (DynamicMessage) InitialData46Test.field(basic, "ACLKMFCDMED");
      var rows = (List<?>) InitialData46Test.field(areaList, "OJGOAOPLMLB");
      assertEquals(2, rows.size());
      assertEquals(
          "RogueAreaStatus_Unlock",
          InitialData46Test.field((DynamicMessage) rows.get(0), "area_status").toString());
      assertEquals(
          "RogueAreaStatus_Lock",
          InitialData46Test.field((DynamicMessage) rows.get(1), "area_status").toString());
      assertTrue(s.player.getGameplay().getRewards().isEmpty());
      var run = new GameplayState.RogueRun(19997010, 101, Map.of(1, 11, 2, 22));
      run.enter(1, 11);
      s.player.getGameplay().setRogue(run);
      assertEquals(1, reachedCount(s, 19997010));
      run.clear();
      run.enter(2, 22);
      assertEquals(2, reachedCount(s, 19997010));
      run.clear();
      assertEquals(2, reachedCount(s, 19997010));
    } finally {
      GameplayDefinitions.rogueSeasons.remove(season);
      GameplayDefinitions.areas.remove(19997010);
      GameplayDefinitions.areas.remove(19997020);
    }
  }

  private int reachedCount(Gameplay46HandlerTest.Session s, int areaId) throws Exception {
    s.request("GetRogueInfoCsReq");
    var game = (DynamicMessage) InitialData46Test.field(s.response, "rogue_game_info");
    var basic = (DynamicMessage) InitialData46Test.field(game, "LFLCDNGALME");
    var catalog = (DynamicMessage) InitialData46Test.field(basic, "ACLKMFCDMED");
    var rows = (List<?>) InitialData46Test.field(catalog, "OJGOAOPLMLB");
    return rows.stream()
        .map(DynamicMessage.class::cast)
        .filter(area -> InitialData46Test.field(area, "area_id").equals(areaId))
        .map(area -> (Integer) InitialData46Test.field(area, "KNDHMNBIIDF"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void mapSnapshotReportsStoredRoomsPathsAndBlessingsWithoutFinishingOrGranting() throws Exception {
    var s = new Gameplay46HandlerTest.Session();
    int areaId = 19997500, mapId = 199975;
    GameplayDefinitions.areas.put(areaId, new GameplayDefinitions.Area(areaId, 1, 1, 0, 0));
    GameplayDefinitions.maps.put(
        mapId,
        List.of(
            new GameplayDefinitions.Site(mapId, 1, true, List.of(2), 1),
            new GameplayDefinitions.Site(mapId, 2, false, List.of(3), 1),
            new GameplayDefinitions.Site(mapId, 3, false, List.of(), 1)));
    var run = new GameplayState.RogueRun(areaId, mapId, Map.of(1, 11, 2, 22, 3, 33));
    run.enter(1, 11);
    run.clear();
    run.enter(2, 22);
    run.getBlessings().put(100001, 2);
    s.player.getGameplay().setRogue(run);
    try {
      s.request("GetRogueInfoCsReq");
      var game = (DynamicMessage) InitialData46Test.field(s.response, "rogue_game_info");
      var current = (DynamicMessage) InitialData46Test.field(game, "CFDIKLBINHK");
      assertEquals("RogueStatus_Doing", InitialData46Test.field(current, "status").toString());
      var map = (DynamicMessage) InitialData46Test.field(current, "COOOMDKGODF");
      assertEquals(2, InitialData46Test.field(map, "ANHDBMOPGDC"));
      assertEquals(22, InitialData46Test.field(map, "HPKMGKPPELC"));
      assertEquals(mapId, InitialData46Test.field(map, "map_id"));
      var rooms = (List<?>) InitialData46Test.field(map, "LLNICHDDIAN");
      assertEquals(3, rooms.size());
      assertEquals(
          "RogueRoomStatus_Finish",
          InitialData46Test.field((DynamicMessage) rooms.get(0), "CAGDNHCOCKM").toString());
      assertEquals(
          "RogueRoomStatus_Play",
          InitialData46Test.field((DynamicMessage) rooms.get(1), "CAGDNHCOCKM").toString());
      assertEquals(
          "RogueRoomStatus_Lock",
          InitialData46Test.field((DynamicMessage) rooms.get(2), "CAGDNHCOCKM").toString());
      var buffs = (DynamicMessage) InitialData46Test.field(current, "HABJHGPFGEB");
      var first = InitialData46Test.first(buffs, "CHCMEKAFKAF");
      assertEquals(100001, InitialData46Test.field(first, "buff_id"));
      assertEquals(2, InitialData46Test.field(first, "level"));
      assertEquals(Set.of(1), run.getCleared());
      assertTrue(s.player.getGameplay().getRewards().isEmpty());
      assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run.getPhase());
    } finally {
      GameplayDefinitions.areas.remove(areaId);
      GameplayDefinitions.maps.remove(mapId);
    }
  }

  @Test
  void localClientChoiceCommandUsesStoredOfferAndDistinctSuccessFailureQueueFields()
      throws Exception {
    var s = new Gameplay46HandlerTest.Session();
    var run = new GameplayState.RogueRun(100, 1, Map.of(1, 2));
    run.enter(1, 2);
    run.clear();
    run.offer(List.of(100001, 100002));
    s.player.getGameplay().setRogue(run);
    int batch = run.getSelectionId();
    var choice = Gameplay46.builder(s, "BPLNKHGAILK");
    Gameplay46.set(choice, "BAOFIGFFMLN", 100002);
    var request = Gameplay46.builder(s, "OFEDNNJIJFL");
    Gameplay46.set(request, "MPLGEEGOAMC", batch);
    Gameplay46.set(request, "DLKIKBIDJDB", choice.build());
    assertTrue(VersionedPacketHandler.handle(s, 5676, request.build().toByteArray()));
    assertEquals("HandleRogueCommonPendingActionScRsp", s.responseName);
    assertEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(batch, InitialData46Test.field(s.response, "queue_position"));
    assertEquals(Map.of(100002, 1), run.getBlessings());
    assertTrue(run.getOffers().isEmpty());
    assertTrue(VersionedPacketHandler.handle(s, 5676, request.build().toByteArray()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(s.response, "retcode"));
    assertEquals(batch, InitialData46Test.field(s.response, "BFNEPPCNOOI"));
    assertEquals(0, InitialData46Test.field(s.response, "queue_position"));
  }

  @Test
  void notificationUsesPinnedNestedChoiceBranchAndActualOfferLevel() {
    var s = new Gameplay46HandlerTest.Session();
    var run = new GameplayState.RogueRun(100, 1, Map.of(1, 2));
    run.enter(1, 2);
    run.offer(List.of(100005, 100006));
    s.player.getGameplay().setRogue(run);
    var notify = Universe46.pending(s);
    assertEquals(101, InitialData46Test.field(notify, "DLOJFEEHLIJ"));
    var action = (DynamicMessage) InitialData46Test.field(notify, "action");
    assertEquals(run.getSelectionId(), InitialData46Test.field(action, "queue_position"));
    var detail = (DynamicMessage) InitialData46Test.field(action, "detail");
    var offer = (DynamicMessage) InitialData46Test.field(detail, "PCHGEGFBKLF");
    var first = InitialData46Test.first(offer, "LEOFNINDJDB");
    assertEquals(100005, InitialData46Test.field(first, "buff_id"));
    assertEquals(1, InitialData46Test.field(first, "HAGDKKBAAKK"));
    assertEquals(false, InitialData46Test.field(offer, "can_roll"));
  }

  @Test
  void unauthenticatedAndUnsupportedBranchesNeverConsumeTheStoredOffer() throws Exception {
    var s = new Gameplay46HandlerTest.Session();
    var run = new GameplayState.RogueRun(100, 1, Map.of(1, 2));
    run.enter(1, 2);
    run.offer(List.of(100005));
    s.player.getGameplay().setRogue(run);
    var request = Gameplay46.builder(s, "OFEDNNJIJFL");
    Gameplay46.set(request, "MPLGEEGOAMC", run.getSelectionId());
    s.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(s, 5676, request.build().toByteArray()));
    assertNull(s.response);
    s.setState(SessionState.ACTIVE);
    assertTrue(VersionedPacketHandler.handle(s, 5676, request.build().toByteArray()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(s.response, "retcode"));
    assertEquals(List.of(100005), run.getOffers());
    assertTrue(run.getBlessings().isEmpty());
  }
}
