package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QuestRewardBatchSyncTest {
  static final class Session extends GameSession {
    int snapshots, legacySnapshots;
    DynamicMessage response, questUpdate;
    final Player player =
        new Player() {
          @Override
          public void save() {}

          @Override
          public GameSession getSession() {
            return Session.this;
          }

          @Override
          public void sendPacket(BasePacket packet) {
            Session.this.send(packet);
          }
        };

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    @Override
    public Player getPlayer() {
      return player;
    }

    @Override
    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    @Override
    public void send(BasePacket packet) {
      if (packet.getCmdId() == CmdId.GetQuestDataScRsp) legacySnapshots++;
    }

    @Override
    public void sendClientPacket(String name, byte[] bytes) {
      try {
        var message = getProtocol().clientSchema().parse(name, bytes);
        if (name.equals("PlayerSyncScNotify")) {
          snapshots++;
          questUpdate = message;
        } else if (name.equals("TakeQuestRewardScRsp")) response = message;
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }

  @Test
  void twentyClaimsAndRetriesEmitOneFinalSnapshotPerRequest() throws Exception {
    var gson = new Gson();
    var ids = java.util.stream.IntStream.range(1999980000, 1999980020).boxed().toList();
    try {
      for (int id : ids) {
        GameData.getQuestExcelMap()
            .add(
                gson.fromJson(
                    "{\"QuestID\":"
                        + id
                        + ",\"QuestType\":1,\"FinishWayID\":"
                        + id
                        + ",\"UnlockType\":\"AutoUnlock\"}",
                    QuestExcel.class));
        GameData.getFinishWayExcelMap()
            .add(
                gson.fromJson(
                    "{\"ID\":" + id + ",\"FinishType\":\"AutoFinish\",\"Progress\":1}",
                    FinishWayExcel.class));
      }
      var session = new Session();
      session.player.getQuestManager().refreshUnlocks();
      var request =
          DynamicMessage.newBuilder(
              session.getProtocol().clientSchema().message("TakeQuestRewardCsReq"));
      var field = request.getDescriptorForType().findFieldByName("ODJIJFJCHHB");
      ids.forEach(id -> request.addRepeatedField(field, id));
      for (int attempt = 0; attempt < 2; attempt++) {
        session.snapshots = 0;
        assertTrue(VersionedPacketHandler.handle(session, 905, request.build().toByteArray()));
        assertEquals(
            1,
            session.snapshots,
            "A batch must emit one final native update after the individual claims");
        assertEquals(
            0,
            session.legacySnapshots,
            "4.6 must not replace the quest cache with a query response");
        var fieldList = session.questUpdate.getDescriptorForType().findFieldByName("quest_list");
        var rows = (List<?>) session.questUpdate.getField(fieldList);
        var statuses = new HashMap<Integer, Integer>();
        for (var raw : rows) {
          var row = (DynamicMessage) raw;
          int id = (Integer) row.getField(row.getDescriptorForType().findFieldByName("id"));
          var status =
              (com.google.protobuf.Descriptors.EnumValueDescriptor)
                  row.getField(row.getDescriptorForType().findFieldByName("status"));
          statuses.put(id, status.getNumber());
        }
        assertTrue(
            ids.stream().allMatch(id -> Objects.equals(statuses.get(id), 3)),
            "The native update must carry each claimed quest as closed");
        assertTrue(
            ids.stream()
                .allMatch(
                    id ->
                        session.player.getQuestStates().get(id).getPhase()
                            == QuestState.Phase.CLOSED));
        assertEquals(
            attempt == 0 ? ids : List.of(),
            session.response.getField(
                session.response.getDescriptorForType().findFieldByName("succ_quest_id_list")));
      }
    } finally {
      ids.forEach(
          id -> {
            GameData.getQuestExcelMap().keySet().remove(id);
            GameData.getFinishWayExcelMap().keySet().remove(id);
          });
    }
  }
}
