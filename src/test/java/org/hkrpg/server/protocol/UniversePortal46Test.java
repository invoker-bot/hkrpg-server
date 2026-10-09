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
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniversePortal46Test {
  static final int PLANE = 199924, FLOOR = 199924001, MAP = 19992401;
  static final int ENTRY = 1999241, ROOM = 19992401;
  final Gson gson = new Gson();
  final Map<Integer, PropExcel> priorProps = new HashMap<>();
  Player player;
  GameplayState.RogueRun run;

  @BeforeEach
  void setup() {
    for (int id : List.of(1000, 1021, 1022, 1023)) {
      priorProps.put(id, GameData.getPropExcelMap().get(id));
      var prop =
          gson.fromJson(
              "{\"ID\":"
                  + id
                  + ",\"PropType\":\"PROP_ROGUE_DOOR\",\"PropStateList\":[\"Closed\",\"Open\",\"Locked\"]}",
              PropExcel.class);
      prop.onLoad();
      GameData.getPropExcelMap().add(prop);
    }
    var floor = gson.fromJson("{\"FloorID\":" + FLOOR + "}", FloorInfo.class);
    var group =
        gson.fromJson(
            "{\"GroupName\":\"Portal\",\"PropList\":["
                + "{\"ID\":300001,\"Name\":\"Door1\",\"PropID\":1000},"
                + "{\"ID\":300002,\"Name\":\"Door2\",\"PropID\":1000}]}",
            GroupInfo.class);
    group.setId(8);
    floor.getGroups().put(8, group);
    GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR, floor);
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":" + ENTRY + ",\"PlaneID\":" + PLANE + ",\"FloorID\":" + FLOOR + "}",
                MapEntranceExcel.class));
    GameplayDefinitions.maps.put(
        MAP,
        List.of(
            new GameplayDefinitions.Site(MAP, 1, true, List.of(2, 3), 1),
            new GameplayDefinitions.Site(MAP, 2, false, List.of(), 5),
            new GameplayDefinitions.Site(MAP, 3, false, List.of(), 3)));
    GameplayDefinitions.rooms.put(ROOM, new GameplayDefinitions.Room(ROOM, 1, ENTRY, 8));
    GameplayDefinitions.rooms.put(
        ROOM + 1, new GameplayDefinitions.Room(ROOM + 1, 5, ENTRY + 1, 9));
    GameplayDefinitions.rooms.put(
        ROOM + 2, new GameplayDefinitions.Room(ROOM + 2, 3, ENTRY + 2, 10));
    player =
        new Player() {
          final PlayerLineup team = new PlayerLineup(this, 0);
          Scene current;

          public PlayerLineup getCurrentLineup() {
            if (team.size() == 0) team.getAvatars().add(999999);
            return team;
          }

          public int getEntryId() {
            return ENTRY;
          }

          public int getPlaneId() {
            return PLANE;
          }

          public int getFloorId() {
            return FLOOR;
          }

          public void save() {}

          public void sendPacket(emu.lunarcore.server.packet.BasePacket p) {}

          public Scene getScene() {
            if (current == null)
              current =
                  new Scene(
                      this,
                      gson.fromJson(
                          "{\"PlaneID\":" + PLANE + ",\"PlaneType\":\"Rogue\"}",
                          MazePlaneExcel.class),
                      FLOOR);
            return current;
          }
        };
    run = new GameplayState.RogueRun(100, MAP, Map.of(1, ROOM, 2, ROOM + 1, 3, ROOM + 2));
    run.enter(1, ROOM);
    run.setInside(true);
    player.getGameplay().setRogue(run);
  }

  @AfterEach
  void cleanup() {
    for (var entry : priorProps.entrySet()) {
      if (entry.getValue() == null) GameData.getPropExcelMap().keySet().remove(entry.getKey());
      else GameData.getPropExcelMap().add(entry.getValue());
    }
    GameData.getFloorInfos().remove("P" + PLANE + "_F" + FLOOR);
    GameData.getMapEntranceExcelMap().keySet().remove(ENTRY);
    GameplayDefinitions.maps.remove(MAP);
    for (int id : List.of(ROOM, ROOM + 1, ROOM + 2)) GameplayDefinitions.rooms.remove(id);
  }

  EntityProp portal(int instance) {
    return player.getScene().getEntities().values().stream()
        .filter(e -> e instanceof EntityProp && e.getInstId() == instance)
        .map(EntityProp.class::cast)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void reconnectingToATerminalRoomRestoresItsPropsAndDoesNotRespawnCombatMonsters() {
    GameplayDefinitions.maps.put(
        MAP, List.of(new GameplayDefinitions.Site(MAP, 1, true, List.of(), 1)));
    var group = GameData.getFloorInfo(PLANE, FLOOR).getGroups().get(8);
    GameplayDefinitions.rooms.put(
        ROOM, new GameplayDefinitions.Room(ROOM, 1, ENTRY, 8, Map.of(8, 199924)));
    int monsterRow = 1999241, npcId = 199924, stageId = 199924;
    GameplayDefinitions.rogueMonsters.put(
        monsterRow, new GameplayDefinitions.RogueMonster(monsterRow, npcId, stageId));
    GameData.getStageExcelMap()
        .add(gson.fromJson("{\"StageID\":" + stageId + "}", StageExcel.class));
    GameData.getNpcMonsterExcelMap()
        .add(gson.fromJson("{\"ID\":" + npcId + "}", NpcMonsterExcel.class));
    try {
      for (var outcome :
          List.of(GameplayState.RogueRun.Phase.FINISHED, GameplayState.RogueRun.Phase.FAILED)) {
        run.finish(outcome);
        var restored =
            new Scene(
                player,
                gson.fromJson(
                    "{\"PlaneID\":" + PLANE + ",\"PlaneType\":\"Rogue\"}", MazePlaneExcel.class),
                FLOOR);
        assertEquals(
            2,
            restored.getEntities().values().stream().filter(e -> e instanceof EntityProp).count());
        assertNull(
            new UniverseEntityLoader()
                .loadMonster(
                    restored,
                    group,
                    gson.fromJson("{\"ID\":1,\"Pos\":{},\"Rot\":{}}", MonsterInfo.class)));
      }
      run.setInside(false);
      var outside =
          new Scene(
              player,
              gson.fromJson(
                  "{\"PlaneID\":" + PLANE + ",\"PlaneType\":\"Rogue\"}", MazePlaneExcel.class),
              FLOOR);
      assertTrue(outside.getEntities().isEmpty(), "An outside run must not adopt a terminal room");
    } finally {
      GameplayDefinitions.rogueMonsters.remove(monsterRow);
      GameData.getStageExcelMap().keySet().remove(stageId);
      GameData.getNpcMonsterExcelMap().keySet().remove(npcId);
    }
  }

  @Test
  void staleFloorSnapshotsCannotBorrowReusedEntityIdsFromTheCurrentRoom() throws Exception {
    portal(300001);
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var legacy =
        emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp.newInstance()
            .setScene(player.getScene().toProto().setFloorId(FLOOR + 1));
    var plain =
        bridge
            .outbound(emu.lunarcore.server.packet.CmdId.GetCurSceneInfoScRsp, legacy.toByteArray())
            .body();
    assertArrayEquals(
        plain, Scene46.enrich(bridge.clientSchema(), "GetCurSceneInfoScRsp", plain, player));
  }

  @Test
  void nativeAndTranslatedSceneSnapshotsCarryOnlyTheAssignedPortalTargets() throws Exception {
    var first = portal(300001);
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var legacy =
        emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp.newInstance()
            .setScene(player.getScene().toProto());
    byte[] header = {8, 42};
    var frame =
        emu.lunarcore.server.packet.PacketCodec.encode(
            emu.lunarcore.server.packet.CmdId.GetCurSceneInfoScRsp, header, legacy.toByteArray());
    var input = io.netty.buffer.Unpooled.wrappedBuffer(bridge.outboundFrames(frame, player));
    try {
      var decoded = emu.lunarcore.server.packet.PacketCodec.decodeNext(input);
      assertArrayEquals(header, decoded.header());
      var snapshot = bridge.clientSchema().parse("GetCurSceneInfoScRsp", decoded.body());
      var scene = (com.google.protobuf.DynamicMessage) Gameplay46.value(snapshot, "scene");
      var groups = (List<?>) Gameplay46.value(scene, "entity_group_list");
      var group =
          groups.stream()
              .map(com.google.protobuf.DynamicMessage.class::cast)
              .filter(g -> Gameplay46.number(g, "group_id") == 8)
              .findFirst()
              .orElseThrow();
      var entity =
          ((List<?>) Gameplay46.value(group, "entity_list"))
              .stream()
                  .map(com.google.protobuf.DynamicMessage.class::cast)
                  .filter(e -> Gameplay46.number(e, "entity_id") == first.getEntityId())
                  .findFirst()
                  .orElseThrow();
      var prop = (com.google.protobuf.DynamicMessage) Gameplay46.value(entity, "prop");
      var extra = (com.google.protobuf.DynamicMessage) Gameplay46.value(prop, "extra_info");
      var destination =
          (com.google.protobuf.DynamicMessage) Gameplay46.value(extra, "rogue_game_info");
      assertEquals(ROOM + 1, Gameplay46.number(destination, "room_id"));
      assertEquals(2, Gameplay46.number(destination, "MAJKFIMPOOF"));
      assertEquals(3, destination.getDescriptorForType().findFieldByName("room_id").getNumber());
      assertEquals(
          12, destination.getDescriptorForType().findFieldByName("MAJKFIMPOOF").getNumber());
      assertEquals(5, extra.getDescriptorForType().findFieldByName("rogue_game_info").getNumber());
      assertEquals(PropState.Closed.getVal(), Gameplay46.number(prop, "prop_state"));
      assertArrayEquals(
          decoded.body(),
          Scene46.enrich(bridge.clientSchema(), "GetCurSceneInfoScRsp", decoded.body(), player));
      run.clear();
      var updated =
          bridge
              .clientSchema()
              .parse(
                  "GetCurSceneInfoScRsp",
                  Scene46.enrich(
                      bridge.clientSchema(), "GetCurSceneInfoScRsp", decoded.body(), player));
      assertFalse(Arrays.equals(decoded.body(), updated.toByteArray()));
      run.setInside(false);
      assertArrayEquals(
          decoded.body(),
          Scene46.enrich(bridge.clientSchema(), "GetCurSceneInfoScRsp", decoded.body(), player));
      assertArrayEquals(frame, ProtocolBridge.forVersion("4.2.0").outboundFrames(frame, player));
    } finally {
      input.release();
    }
  }

  @Test
  void unknownPortalNamesAndMissingAssignedDestinationsAreNotInvented() {
    var loader = new UniverseEntityLoader();
    var scene = player.getScene();
    var group = scene.getFloorInfo().getGroups().get(8);
    var unknown =
        gson.fromJson("{\"ID\":300003,\"Name\":\"UnknownDoor\",\"PropID\":1000}", PropInfo.class);
    assertNull(loader.loadProp(scene, group, unknown));
    var old = portal(300001);
    run.getRooms().remove(2);
    assertEquals(PropState.Closed, old.getState());
    assertNull(loader.loadProp(scene, group, group.getPropList().getFirst()));
    assertEquals(Set.of(), run.getCleared());
  }

  @Test
  void portalAppearanceAndAvailabilityFollowTheAssignedDestinationAndPendingReward() {
    var first = portal(300001);
    var second = portal(300002);
    assertEquals(1023, first.getPropId());
    assertEquals(1022, second.getPropId());
    assertEquals(PropState.Closed, first.getState());
    run.clear();
    assertEquals(PropState.Open, first.getState());
    run.offer(List.of(600000));
    assertEquals(PropState.Closed, first.getState());
    run.select(600000);
    assertEquals(PropState.Open, first.getState());
    run.offerMiracles(List.of(4));
    assertEquals(PropState.Closed, first.getState());
    run.getMiracleOffers().clear();
    run.setInside(false);
    assertEquals(PropState.Closed, first.getState());
    assertTrue(player.getGameplay().getRewards().isEmpty());
  }
}
