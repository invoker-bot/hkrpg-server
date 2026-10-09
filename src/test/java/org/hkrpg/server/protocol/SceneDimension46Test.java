package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.*;

class SceneDimension46Test {
  static final int PLANE = 199936, FLOOR = 19993601, ENTRY = 19993601;
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
  final Gson gson = new Gson();
  FloorInfo oldFloor;
  Player actor;
  Scene current;

  @BeforeEach
  void prepare() {
    var floor =
        gson.fromJson(
            "{\"FloorID\":19993601,\"DimensionList\":[{\"ID\":0,\"GroupIndexList\":[]},{\"ID\":4,\"GroupIndexList\":[]}],\"GroupInstanceList\":[]}",
            FloorInfo.class);
    oldFloor = GameData.getFloorInfos().put("P199936_F19993601", floor);
    actor =
        new Player() {
          final PlayerLineup lineup = new PlayerLineup(this, 0);

          {
            lineup.getAvatars().add(1);
          }

          public PlayerLineup getCurrentLineup() {
            return lineup;
          }

          public Scene getScene() {
            return current;
          }

          public void save() {}
        };
    current =
        new Scene(
            actor,
            gson.fromJson("{\"PlaneID\":199936,\"PlaneType\":\"Town\"}", MazePlaneExcel.class),
            FLOOR,
            4);
    current.setEntryId(ENTRY);
  }

  @AfterEach
  void restore() {
    if (oldFloor == null) GameData.getFloorInfos().remove("P199936_F19993601");
    else GameData.getFloorInfos().put("P199936_F19993601", oldFloor);
  }

  byte[] nativeBody() {
    return bridge
        .outbound(
            CmdId.GetCurSceneInfoScRsp,
            GetCurSceneInfoScRsp.newInstance().setScene(current.toProto()).toByteArray())
        .body();
  }

  DynamicMessage scene(byte[] body) throws Exception {
    var wrapper = bridge.clientSchema().parse("GetCurSceneInfoScRsp", body);
    return (DynamicMessage)
        wrapper.getField(wrapper.getDescriptorForType().findFieldByName("scene"));
  }

  @Test
  void actualSelectedDimensionIsSerializedInNativeField1225() throws Exception {
    var original = nativeBody();
    assertEquals(0, Gameplay46.number(scene(original), "dimension_id"));
    var enriched = Scene46.enrich(bridge.clientSchema(), "GetCurSceneInfoScRsp", original, actor);
    var selected = scene(enriched);
    assertEquals(4, Gameplay46.number(selected, "dimension_id"));
    assertEquals(PLANE, Gameplay46.number(selected, "plane_id"));
    assertEquals(FLOOR, Gameplay46.number(selected, "floor_id"));
    assertEquals(ENTRY, Gameplay46.number(selected, "entry_id"));
    assertEquals(
        0,
        Gameplay46.number(selected, "game_story_line_id"),
        "the dimension must not invent an active story line");
    var wire = CodedInputStream.newInstance(selected.toByteArray());
    boolean found = false;
    while (!wire.isAtEnd()) {
      int tag = wire.readTag();
      if (tag == (1225 << 3)) {
        assertEquals(4, wire.readUInt32());
        found = true;
      } else wire.skipField(tag);
    }
    assertTrue(found);
  }

  @Test
  void queuedSnapshotsForOtherPlanesFloorsOrEntrancesKeepTheirOwnBytes() throws Exception {
    var source = bridge.clientSchema().parse("GetCurSceneInfoScRsp", nativeBody());
    var field = source.getDescriptorForType().findFieldByName("scene");
    for (String identity : new String[] {"plane_id", "floor_id", "entry_id"}) {
      var other = ((DynamicMessage) source.getField(field)).toBuilder();
      Gameplay46.set(other, identity, 1);
      var bytes = source.toBuilder().setField(field, other.build()).build().toByteArray();
      assertArrayEquals(
          bytes, Scene46.enrich(bridge.clientSchema(), "GetCurSceneInfoScRsp", bytes, actor));
    }
  }

  @Test
  void normalPacketFramesAlsoReceiveTheDimensionAndLegacyFramesRemainUnchanged() throws Exception {
    byte[] header = new byte[] {1, 2, 3};
    var core = GetCurSceneInfoScRsp.newInstance().setScene(current.toProto()).toByteArray();
    var frame = PacketCodec.encode(CmdId.GetCurSceneInfoScRsp, header, core);
    var input = Unpooled.wrappedBuffer(bridge.outboundFrames(frame, actor));
    try {
      var decoded = PacketCodec.decodeNext(input);
      assertEquals(1456, decoded.cmdId());
      assertArrayEquals(header, decoded.header());
      assertEquals(4, Gameplay46.number(scene(decoded.body()), "dimension_id"));
    } finally {
      input.release();
    }
    assertArrayEquals(frame, ProtocolBridge.forVersion("4.2.0").outboundFrames(frame, actor));
  }

  @Test
  void malformedSceneBytesAreRejectedBeforeSendingASelectedDimension() {
    assertThrows(
        ProtocolException.class,
        () ->
            Scene46.enrich(
                bridge.clientSchema(),
                "GetCurSceneInfoScRsp",
                new byte[] {0x52, 0x01, (byte) 0x80},
                actor));
    assertArrayEquals(
        new byte[0], Scene46.enrich(bridge.clientSchema(), "PlayerLoginScRsp", new byte[0], actor));
  }
}
