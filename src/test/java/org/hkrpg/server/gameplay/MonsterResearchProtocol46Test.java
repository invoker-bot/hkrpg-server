package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.SessionState;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercise the native 4.6 talk request carrying the selected seventh-stage topic. */
class MonsterResearchProtocol46Test extends MonsterResearchDelivery46Test {
  static final class Session extends GameSession {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    final Player actor;
    DynamicMessage response;
    String responseName;
    Session(Player actor) { super(null); this.actor = actor; setState(SessionState.ACTIVE); }
    @Override public Player getPlayer() { return actor; }
    @Override public ProtocolBridge getProtocol() { return bridge; }
    @Override public void send(BasePacket packet) {}
    @Override public void sendClientPacket(String name, byte[] bytes) {
      try { responseName = name; response = bridge.clientSchema().parse(name, bytes); }
      catch (Exception failed) { throw new AssertionError(failed); }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 800017004})
  void picturedFirstStageUsesTheOriginalTalkKey(int reportedSub) throws Exception {
    int sub = accepted(8000170); var row = stock(8000170); var before = stocks();
    var session = new Session(player);
    var schema = session.bridge.clientSchema().message("FinishTalkMissionCsReq");
    var request = DynamicMessage.newBuilder(schema);
    request.setField(schema.findFieldByName("talk_str"), "Mission_800017004");
    if (reportedSub != 0) request.setField(schema.findFieldByName("sub_mission_id"), reportedSub);
    assertTrue(VersionedPacketHandler.handle(session,
        session.bridge.clientCommand("FinishTalkMissionCsReq"), request.build().toByteArray()));
    assertEquals("FinishTalkMissionScRsp", session.responseName);
    assertEquals(0, session.response.getField(session.response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(sub).getPhase());
    assertEquals(1, player.grants);
    row.costs().forEach((item, count) -> assertEquals(before.get(item) - count, stocks().get(item)));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 800017705})
  void selectedTopicTravelsThroughTheActualTalkRequest(int reportedSub) throws Exception {
    int sub = accepted(8000177); var row = stock(8000177); var before = stocks();
    var session = new Session(player);
    var schema = session.bridge.clientSchema().message("FinishTalkMissionCsReq");
    var request = DynamicMessage.newBuilder(schema);
    request.setField(schema.findFieldByName("talk_str"), "ResearchB_800017705");
    if (reportedSub != 0) request.setField(schema.findFieldByName("sub_mission_id"), reportedSub);
    assertTrue(VersionedPacketHandler.handle(session,
        session.bridge.clientCommand("FinishTalkMissionCsReq"), request.build().toByteArray()));
    assertEquals("FinishTalkMissionScRsp", session.responseName);
    assertEquals(0, session.response.getField(session.response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(sub).getPhase());
    assertEquals(1, player.grants);
    row.costs().forEach((item, count) -> assertEquals(before.get(item) - count, stocks().get(item)));
  }
}
