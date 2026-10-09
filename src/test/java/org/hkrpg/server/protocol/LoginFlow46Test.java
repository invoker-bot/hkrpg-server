package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ContentPackageExcel;
import com.google.gson.Gson;
import emu.lunarcore.proto.PlayerBasicInfoOuterClass.PlayerBasicInfo;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.recv.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoginFlow46Test {
    static class Session extends GameSession {
        final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
        final List<Integer> sent = new ArrayList<>();
        final Map<String, byte[]> clientBodies = new HashMap<>();
        byte[] lastClientBody;
        final Player player = new Player() {
            @Override public PlayerBasicInfo toProto() { return PlayerBasicInfo.newInstance().setNickname("Test").setLevel(1); }
        };
        Session() { super(null); setState(SessionState.WAITING_FOR_LOGIN); }
        @Override public Player getPlayer() { return player; }
        @Override public ProtocolBridge getProtocol() { return bridge; }
        @Override public void send(BasePacket packet) {
            sent.add(bridge.outbound(packet.getCmdId(), packet.getData() == null ? new byte[0] : packet.getData().toByteArray()).cmdId());
        }
        @Override public void send(int id) { sent.add(bridge.outbound(id, new byte[0]).cmdId()); }
        @Override public void sendClientPacket(String name, byte[] bytes) {
            try { bridge.clientSchema().parse(name, bytes); } catch (Exception ex) { throw new AssertionError(ex); }
            sent.add(bridge.clientCommand(name));
            lastClientBody = bytes;
            clientBodies.put(name, bytes);
        }
    }
    @Test void contentCatalogIsInitialAndDoesNotClaimStoryCompletion() throws Exception {
        var excel = new Gson().fromJson("{\"ContentID\":200001}", ContentPackageExcel.class);
        var previous = GameData.getContentPackageExcelMap().get(excel.getId());
        GameData.getContentPackageExcelMap().add(excel);
        try {
            var session = new Session();
            session.setState(SessionState.ACTIVE);
            assertTrue(VersionedPacketHandler.handle(session, session.bridge.clientCommand("ContentPackageGetDataCsReq"), new byte[0]));
            var response = session.bridge.clientSchema().parse("ContentPackageGetDataScRsp", session.lastClientBody);
            var data = (DynamicMessage) InitialData46Test.field(response, "data");
            assertEquals(0, InitialData46Test.field(data, "cur_content_id"));
            var info = InitialData46Test.first(data, "content_package_list");
            assertEquals(200001, InitialData46Test.field(info, "content_id"));
            assertEquals("ContentPackageStatus_Init", InitialData46Test.field(info, "status").toString());
            assertThrows(ProtocolException.class, () -> VersionedPacketHandler.handle(session,
                session.bridge.clientCommand("ContentPackageGetDataCsReq"), new byte[] {8, 1}));
        } finally {
            GameData.getContentPackageExcelMap().keySet().remove(excel.getId());
            if (previous != null) GameData.getContentPackageExcelMap().add(previous);
        }
    }
    @Test void modernLoginHasNoLegacyMaxedBattlePassOrArchiveBurst() throws Exception {
        var session = new Session();
        new HandlerPlayerLoginCsReq().handle(session, new byte[0]);
        assertEquals(List.of(25, 7520, 6205, 593), session.sent);
        var quota = session.bridge.clientSchema().parse("ComposeLimitNumCompleteScNotify", session.clientBodies.get("ComposeLimitNumCompleteScNotify"));
        assertEquals(List.of(), quota.getField(quota.getDescriptorForType().findFieldByNumber(4)));
        var story = session.bridge.clientSchema().parse("StoryLineInfoScNotify", session.clientBodies.get("StoryLineInfoScNotify"));
        assertEquals(List.of(0), story.getField(story.getDescriptorForType().findFieldByNumber(3)));
        assertEquals(List.of(), story.getField(story.getDescriptorForType().findFieldByNumber(11)));
        assertEquals(0, story.getField(story.getDescriptorForType().findFieldByNumber(9)));
        assertEquals(SessionState.ACTIVE, session.getState());
        session.sent.clear();
        new HandlerPlayerLoginFinishCsReq().handle(session, new byte[0]);
        assertEquals(List.of(session.bridge.clientCommand("PlayerLoginFinishScRsp")), session.sent);
    }
    @Test void declaredAvatarSnapshotRequestCanReachTheFieldlessCoreHandler() {
        var bridge = ProtocolBridge.forVersion("4.6.0");
        var type = bridge.clientSchema().message("GetAvatarDataCsReq");
        var request = DynamicMessage.newBuilder(type).setField(type.findFieldByName("is_get_all"), true).build().toByteArray();
        assertEquals(CmdId.GetAvatarDataCsReq, bridge.inbound(bridge.clientCommand("GetAvatarDataCsReq"), request).cmdId());
    }
}
