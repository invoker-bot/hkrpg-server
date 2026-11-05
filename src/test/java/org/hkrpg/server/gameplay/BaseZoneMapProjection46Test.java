package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.server.packet.send.PacketGetCurSceneInfoScRsp;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.hkrpg.server.protocol.Scene46;
import org.hkrpg.server.protocol.SceneMap46;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaseZoneMapProjection46Test {
  private static final int FLOOR = 20001001;

  private static class MapReader extends GameSession {
    private final Player player;
    private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    private DynamicMessage reply;

    MapReader(Player player) {
      super(null);
      this.player = player;
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() { return player; }
    public ProtocolBridge getProtocol() { return bridge; }

    public void sendClientPacket(String name, byte[] bytes) {
      assertEquals("GetSceneMapInfoScRsp", name);
      assertDoesNotThrow(() -> { reply = bridge.clientSchema().parse(name, bytes); });
    }

    DynamicMessage map(int floor) throws IOException {
      var schema = bridge.clientSchema();
      var id = DynamicMessage.newBuilder(schema.message("SceneIdentifier"));
      id.setField(id.getDescriptorForType().findFieldByName("floor_id"), floor);
      var request = DynamicMessage.newBuilder(schema.message("GetSceneMapInfoCsReq"));
      request.addRepeatedField(request.getDescriptorForType().findFieldByName("scene_identifiers"),
          id.build());
      reply = null;
      assertTrue(SceneMap46.handle(this, bridge.clientCommand("GetSceneMapInfoCsReq"),
          request.build().toByteArray()));
      assertEquals(0, field(reply, "retcode"));
      var maps = rows(reply, "scene_map_info");
      assertEquals(1, maps.size());
      return maps.getFirst();
    }

    DynamicMessage scene() throws IOException {
      var packet = new PacketGetCurSceneInfoScRsp(this);
      var wire = bridge.outbound(packet.getCmdId(), packet.getData().toByteArray());
      var schema = bridge.clientSchema();
      var body = Scene46.enrich(schema, "GetCurSceneInfoScRsp", wire.body(), player);
      return (DynamicMessage) field(schema.parse("GetCurSceneInfoScRsp", body), "scene");
    }
  }

  private static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  @SuppressWarnings("unchecked")
  private static List<DynamicMessage> rows(DynamicMessage message, String name) {
    return (List<DynamicMessage>) field(message, name);
  }

  @Test
  void remoteMonitoringRoomUsesItsSavedGateAndAnchorActivation() throws Exception {
    var fixture = new SpaceAnchorBoxActivationTest();
    fixture.setup();
    try {
      var player = fixture.player;
      var reader = new MapReader(player);
      String gate = "floor:20001001:SceneStateByMainMission";
      player.getGameplay().getStoryValues().put(gate, 1);
      assertFalse(((List<?>) field(reader.map(FLOOR), "unlock_teleport_list")).contains(2000102));
      player.onMove();
      assertTrue(fixture.unlocked());
      player.current = null;
      var remote = reader.map(FLOOR);
      assertEquals(List.of(2000101, 2000102), field(remote, "unlock_teleport_list"));
      assertEquals(List.of(18), rows(remote, "maze_group_list").stream()
          .map(group -> field(group, "group_id")).toList());
      assertTrue(rows(remote, "maze_prop_list").stream().anyMatch(p ->
          field(p, "config_id").equals(300002)
              && field(p, "state").equals(PropState.CheckPointEnable.getVal())));
      player.getGameplay().getStoryValues().put(gate, 0);
      var gated = reader.map(FLOOR);
      assertEquals(List.of(), field(gated, "unlock_teleport_list"));
      assertEquals(List.of(), rows(gated, "maze_group_list"));
      assertEquals(1, player.saves);
      assertNull(player.current);
    } finally {
      fixture.cleanup();
    }
  }

  @Test
  void guardChestAppearsInMapAndSceneReceiptsOnlyAfterOpening(@TempDir Path root) throws Exception {
    var fixture = new WorldMechanism46Test();
    fixture.root = root;
    fixture.load();
    try {
      var player = fixture.map.fixture.player;
      player.getCurrentLineup().getAvatars().add(1001);
      var reader = new MapReader(player);
      assertTrue(player.getScene().defeatMonster(fixture.monster(200001)));
      assertTrue(player.getScene().defeatMonster(fixture.monster(200002)));
      assertEquals(PropState.ChestClosed, fixture.map.chest(20001632).getState());
      assertEquals(List.of(), field(reader.map(FLOOR), "opened_chest_id_list"));
      assertEquals(List.of(), field(reader.scene(), "opened_chests_list"));
      assertEquals(Retcode.SUCC, fixture.map.open(20001632));
      fixture.reload();
      assertEquals(PropState.ChestUsed, fixture.map.chest(20001632).getState());
      int saves = player.saves;
      assertAll(
          () -> assertEquals(List.of(20001632), field(reader.map(FLOOR), "opened_chest_id_list")),
          () -> assertEquals(List.of(20001632), field(reader.scene(), "opened_chests_list")));
      fixture.map.enter(10101601);
      assertEquals(List.of(20001632), field(reader.map(FLOOR), "opened_chest_id_list"));
      assertEquals(List.of(), field(reader.scene(), "opened_chests_list"));
      assertEquals(saves, player.saves);
      assertEquals(1, player.grants);
    } finally {
      fixture.restore();
    }
  }
}
