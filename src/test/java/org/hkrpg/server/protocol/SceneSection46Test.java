package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.*;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.SessionState;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SceneSection46Test {
  static class Actor extends Player {
    final LineupManager lineups = new LineupManager(this);
    Scene scene;

    @Override
    public LineupManager getLineupManager() {
      return lineups;
    }

    @Override
    public Scene getScene() {
      return scene;
    }

    @Override
    public void save() {
      fail("A section acknowledgement must not save player state");
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Actor actor = new Actor();

    Session() {
      actor.scene =
          new Scene(
              actor,
              new Gson()
                  .fromJson("{\"PlaneID\":199996,\"PlaneType\":\"Maze\"}", MazePlaneExcel.class),
              199996001);
    }

    @Override
    public Player getPlayer() {
      return actor;
    }
  }

  byte[] request(Session s, long section, long runtime) throws Exception {
    var out = new ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(out);
    wire.writeUInt64(10, section);
    wire.writeUInt64(13, runtime);
    wire.flush();
    return out.toByteArray();
  }

  int send(Session s, byte[] bytes) throws Exception {
    assertTrue(
        VersionedPacketHandler.handle(s, 1481, bytes), "Native section requests need a response");
    assertEquals("EnterSectionScRsp", s.responseName);
    assertEquals(1451, s.getProtocol().clientCommand(s.responseName));
    assertTrue(s.response.getUnknownFields().asMap().isEmpty());
    assertEquals(11, s.response.getDescriptorForType().findFieldByName("retcode").getNumber());
    return Gameplay46.number(s.response, "retcode");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 100})
  void acknowledgementIsRepeatableAndDoesNotUnlockScenesOrCompleteMissions(int section)
      throws Exception {
    var s = new Session();
    var before = new Gson().toJson(s.actor.getGameplay());
    var scene = s.actor.scene;
    var bytes = request(s, section, scene.getRuntimeId());
    assertEquals(0, send(s, bytes));
    assertEquals(0, send(s, bytes));
    assertEquals(before, new Gson().toJson(s.actor.getGameplay()));
    assertSame(scene, s.actor.scene);
    assertNull(s.actor.getBattle());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"stale", "missing-scene", "duplicate", "unknown", "overflow", "malformed"})
  void invalidOrStaleSectionStillReceivesAnExplicitFailure(String mode) throws Exception {
    var s = new Session();
    var bytes =
        request(
            s,
            mode.equals("overflow") ? (1L << 32) + 1 : 1,
            s.actor.scene.getRuntimeId() + (mode.equals("stale") ? 1 : 0));
    if (mode.equals("missing-scene")) s.actor.scene = null;
    if (mode.equals("duplicate") || mode.equals("unknown")) {
      var out = new ByteArrayOutputStream();
      out.write(bytes);
      var wire = CodedOutputStream.newInstance(out);
      wire.writeUInt32(mode.equals("duplicate") ? 10 : 14, 1);
      wire.flush();
      bytes = out.toByteArray();
    }
    if (mode.equals("malformed")) bytes = new byte[] {(byte) 0x80};
    assertNotEquals(0, send(s, bytes));
  }

  @Test
  void unauthenticatedSectionDoesNotExposeSceneState() throws Exception {
    var s = new Session();
    s.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(s, 1481, request(s, 1, s.actor.scene.getRuntimeId())));
    assertNull(s.response);
  }
}
