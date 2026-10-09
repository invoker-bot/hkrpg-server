package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.Unpooled;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class TrainWorldScene46Test {
  static final int WORLD = 199966501, BASE = 100;
  final SceneDimension46Test fixture = new SceneDimension46Test();
  final Gson gson = new Gson();

  @BeforeEach
  void setup() {
    fixture.prepare();
    GameplayDefinitions.worlds.put(WORLD, new GameplayDefinitions.World(WORLD));
    fixture.actor.getGameplay().getStoryValues().put("train:world", WORLD);
    train();
  }

  void train() {
    fixture.current =
        new Scene(
            fixture.actor,
            gson.fromJson(
                "{\"PlaneID\":199936,\"PlaneType\":\"Train\",\"WorldID\":100}",
                MazePlaneExcel.class),
            SceneDimension46Test.FLOOR,
            4);
    fixture.current.setEntryId(SceneDimension46Test.ENTRY);
  }

  @AfterEach
  void cleanup() {
    fixture.restore();
    GameplayDefinitions.worlds.remove(WORLD);
  }

  int fallback() {
    return fixture.actor.getWorldId();
  }

  @Test
  void currentTrainSceneSerializesTheValidatedSavedWorldAlongsideItsActualDimension()
      throws Exception {
    assertEquals(WORLD, fixture.current.getWorldId());
    assertEquals(WORLD, fixture.current.toProto().getWorldId());
    var original = fixture.nativeBody();
    var nativeScene =
        fixture.scene(
            Scene46.enrich(
                fixture.bridge.clientSchema(), "GetCurSceneInfoScRsp", original, fixture.actor));
    assertEquals(WORLD, Gameplay46.number(nativeScene, "world_id"));
    assertEquals(4, Gameplay46.number(nativeScene, "dimension_id"));
    assertEquals(SceneDimension46Test.FLOOR, Gameplay46.number(nativeScene, "floor_id"));
    var wire = com.google.protobuf.UnknownFieldSet.parseFrom(nativeScene.toByteArray());
    assertEquals(WORLD, wire.getField(3).getVarintList().getFirst().intValue());
  }

  @Test
  void aWorldChangeUpdatesAnAlreadyLoadedTrainSceneAndSurvivesRebuildingIt() {
    fixture.actor.getGameplay().getStoryValues().remove("train:world");
    train();
    assertEquals(fallback(), fixture.current.getWorldId());
    fixture.actor.getGameplay().getStoryValues().put("train:world", WORLD);
    assertEquals(WORLD, fixture.current.toProto().getWorldId());
    train();
    assertEquals(WORLD, fixture.current.toProto().getWorldId());
  }

  @Test
  void townAndMazeScenesKeepTheirOwnResourceWorld() {
    for (String type : new String[] {"Town", "Maze"}) {
      fixture.current =
          new Scene(
              fixture.actor,
              gson.fromJson(
                  "{\"PlaneID\":199936,\"PlaneType\":\"" + type + "\",\"WorldID\":100}",
                  MazePlaneExcel.class),
              SceneDimension46Test.FLOOR,
              4);
      assertEquals(BASE, fixture.current.getWorldId());
      assertEquals(BASE, fixture.current.toProto().getWorldId());
    }
  }

  @Test
  void missingOrInvalidSavedWorldFallsBackWithoutDeletingTheStoredSelection() {
    for (int world : new int[] {0, -1, WORLD + 1}) {
      fixture.actor.getGameplay().getStoryValues().put("train:world", world);
      assertEquals(fallback(), fixture.current.getWorldId());
      assertEquals(world, fixture.actor.getGameplay().getStoryValues().get("train:world"));
    }
    fixture.actor.getGameplay().getStoryValues().put("train:world", WORLD);
    GameplayDefinitions.worlds.put(WORLD, new GameplayDefinitions.World(WORLD + 1));
    assertEquals(fallback(), fixture.current.getWorldId());
    assertEquals(WORLD, fixture.actor.getGameplay().getStoryValues().get("train:world"));
  }

  @Test
  void normalPacketFramesCarryTheStoredTrainWorldThroughTheExistingConversion() throws Exception {
    byte[] header = {1, 2, 3};
    byte[] core =
        GetCurSceneInfoScRsp.newInstance().setScene(fixture.current.toProto()).toByteArray();
    byte[] frame = PacketCodec.encode(CmdId.GetCurSceneInfoScRsp, header, core);
    var input = Unpooled.wrappedBuffer(fixture.bridge.outboundFrames(frame, fixture.actor));
    try {
      var decoded = PacketCodec.decodeNext(input);
      assertEquals(1456, decoded.cmdId());
      assertArrayEquals(header, decoded.header());
      DynamicMessage scene = fixture.scene(decoded.body());
      assertEquals(WORLD, Gameplay46.number(scene, "world_id"));
      assertEquals(4, Gameplay46.number(scene, "dimension_id"));
    } finally {
      input.release();
    }
    assertArrayEquals(
        frame, ProtocolBridge.forVersion("4.2.0").outboundFrames(frame, fixture.actor));
  }
}
