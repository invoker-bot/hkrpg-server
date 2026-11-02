package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.GameConstants;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.friends.FriendList;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.AssistSimpleInfoOuterClass.AssistSimpleInfo;
import emu.lunarcore.proto.FriendApplyInfoOuterClass.FriendApplyInfo;
import emu.lunarcore.proto.FriendListInfoOuterClass.FriendListInfo;
import emu.lunarcore.proto.FriendOnlineStatusOuterClass.FriendOnlineStatus;
import emu.lunarcore.proto.GetFriendApplyListInfoScRspOuterClass.GetFriendApplyListInfoScRsp;
import emu.lunarcore.proto.GetFriendListInfoScRspOuterClass.GetFriendListInfoScRsp;
import emu.lunarcore.proto.PlatformTypeOuterClass.PlatformType;
import emu.lunarcore.proto.PlayerSimpleInfoOuterClass.PlayerSimpleInfo;
import emu.lunarcore.proto.PlayingStateOuterClass.PlayingState;
import emu.lunarcore.server.packet.CmdId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Regression for the real GetFriendListInfoCsReq rejection of FriendListInfo.simple_info. */
class FriendPage46Test {
  private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  private static Object field(DynamicMessage message, String name) {
    var descriptor = message.getDescriptorForType().findFieldByName(name);
    assertNotNull(descriptor, name);
    return message.getField(descriptor);
  }

  @SuppressWarnings("unchecked")
  private static List<DynamicMessage> rows(DynamicMessage message, String name) {
    return (List<DynamicMessage>) field(message, name);
  }

  private static PlayerSimpleInfo profile() {
    return PlayerSimpleInfo.newInstance()
        .setUid(10002).setNickname("好友测试").setSignature("测试签名").setLevel(42)
        .setHeadIcon(202001).setChatBubble(220000).setPersonalCard(210001)
        .setOnlineStatus(FriendOnlineStatus.FRIEND_ONLINE_STATUS_OFFLINE)
        .setPlatform(PlatformType.PC).setLogoutTime(1_793_500_123L)
        .setPlatformUuid("test-platform").setPlatformNick("test-name").setIsBanned(false)
        .addAssistInfoList(AssistSimpleInfo.newInstance()
            .setAvatarId(1001).setLevel(60).setPos(0).setDressedSkinId(1100101))
        .addAssistInfoList(AssistSimpleInfo.newInstance()
            .setAvatarId(1002).setLevel(50).setPos(1).setDressedSkinId(0));
  }

  private DynamicMessage outbound(String name, int command, byte[] body) throws Exception {
    var converted = bridge.outbound(command, body);
    assertEquals(bridge.clientCommand(name), converted.cmdId());
    var message = bridge.clientSchema().parse(name, converted.body());
    assertTrue(message.getUnknownFields().asMap().isEmpty());
    return message;
  }

  private static void assertProfile(DynamicMessage info) {
    assertEquals(10002, field(info, "uid"));
    assertEquals("好友测试", field(info, "nickname"));
    assertEquals("测试签名", field(info, "signature"));
    assertEquals(42, field(info, "level"));
    assertEquals(202001, field(info, "head_icon"));
    assertEquals(220000, field(info, "chat_bubble_id"));
    assertEquals(210001, field(info, "personal_card"));
    assertEquals(1_793_500_123L, field(info, "last_active_time"));
    assertEquals("FRIEND_ONLINE_STATUS_OFFLINE", field(info, "online_status").toString());
    assertEquals("PC", field(info, "platform").toString());
    assertEquals("test-platform", field(info, "platform_uuid"));
    assertEquals("test-name", field(info, "platform_nick"));
    var assists = rows(info, "assist_simple_info_list");
    assertEquals(2, assists.size());
    assertEquals(1001, field(assists.get(0), "avatar_id"));
    assertEquals(60, field(assists.get(0), "level"));
    assertEquals(0, field(assists.get(0), "pos"));
    assertEquals(1100101, field(assists.get(0), "dressed_skin_id"));
    assertEquals(1002, field(assists.get(1), "avatar_id"));
    assertEquals(1, field(assists.get(1), "pos"));
  }

  @Test void actualConsoleFriendPacketIsNonemptyEvenWithoutAnyPlayerFriendship() throws Exception {
    // Same packet builder and cache used by HandlerGetFriendListInfoCsReq.
    var friends = new FriendList(new Player());
    var packet = friends.getFriendListPacket();
    assertSame(packet, friends.getFriendListPacket());
    var reply = outbound("GetFriendListInfoScRsp", packet.getCmdId(), packet.getData().toByteArray());
    assertEquals(2925, bridge.clientCommand("GetFriendListInfoScRsp"));
    assertEquals(0, field(reply, "retcode"));
    assertEquals(1, rows(reply, "friend_list").size());
    var console = (DynamicMessage) field(rows(reply, "friend_list").getFirst(), "player_info");
    var config = LunarCore.getConfig().getServerOptions().getServerFriendInfo();
    assertEquals(GameConstants.SERVER_CONSOLE_UID, field(console, "uid"));
    assertEquals(config.getName(), field(console, "nickname"));
    assertEquals(config.getChatBubbleId(), field(console, "chat_bubble_id"));
    assertEquals(config.getDisplayAvatars().size(), rows(console, "assist_simple_info_list").size());
    assertTrue(friends.getFriends().isEmpty());
    assertTrue(friends.getPendingFriends().isEmpty());
  }

  @Test void populatedFriendListRetainsProfileRemarkMarkAndSemanticPlayingState() throws Exception {
    var source = GetFriendListInfoScRsp.newInstance().addFriendList(FriendListInfo.newInstance()
        .setSimpleInfo(profile()).setRemarkName("备注").setIsMarked(true)
        .setPlayingState(PlayingState.PLAYING_CHALLENGE_MEMORY));
    var bytes = source.toByteArray();
    var reply = outbound("GetFriendListInfoScRsp", CmdId.GetFriendListInfoScRsp, bytes);
    var friend = rows(reply, "friend_list").getFirst();
    assertEquals(4, friend.getDescriptorForType().findFieldByName("player_info").getNumber());
    assertProfile((DynamicMessage) field(friend, "player_info"));
    assertEquals("备注", field(friend, "remark_name"));
    assertEquals(true, field(friend, "is_marked"));
    assertEquals("PlayingState_PlayingChallengeMemory", field(friend, "playing_state").toString());
    assertArrayEquals(bytes, source.toByteArray());
  }

  @ParameterizedTest
  @CsvSource({"logout_time,last_active_time", "chat_bubble,chat_bubble_id", "assist_info_list,assist_simple_info_list"})
  void renamedProfileFieldsConvertIndependently(String oldName, String newName) throws Exception {
    var source = bridge.coreSchema().parse("PlayerSimpleInfo", profile().toByteArray());
    var descriptor = source.getDescriptorForType().findFieldByName(oldName);
    var only = DynamicMessage.newBuilder(source.getDescriptorForType())
        .setField(descriptor, source.getField(descriptor)).build();
    var converted = bridge.clientSchema().parse("PlayerSimpleInfo",
        bridge.translate("PlayerSimpleInfo", only.toByteArray(), true));
    var target = converted.getDescriptorForType().findFieldByName(newName);
    if (descriptor.isRepeated()) assertEquals(2, converted.getRepeatedFieldCount(target));
    else assertEquals(source.getField(descriptor), converted.getField(target));
  }

  @Test void incomingApplicationsRetainTheActualApplicantProfile() throws Exception {
    var source = GetFriendApplyListInfoScRsp.newInstance()
        .addFriendApplyList(FriendApplyInfo.newInstance().setSimpleInfo(profile()));
    var reply = outbound("GetFriendApplyListInfoScRsp", CmdId.GetFriendApplyListInfoScRsp,
        source.toByteArray());
    assertEquals(1, rows(reply, "receive_apply_list").size());
    assertProfile((DynamicMessage) field(rows(reply, "receive_apply_list").getFirst(), "player_info"));
    assertEquals(List.of(), field(reply, "send_apply_list"));
  }

  @Test void legacyFriendPacketRemainsByteForByteIdentical() {
    var source = GetFriendListInfoScRsp.newInstance()
        .addFriendList(FriendListInfo.newInstance().setSimpleInfo(profile())).toByteArray();
    var legacy = ProtocolBridge.forVersion("4.2.0").outbound(CmdId.GetFriendListInfoScRsp, source);
    assertEquals(CmdId.GetFriendListInfoScRsp, legacy.cmdId());
    assertArrayEquals(source, legacy.body());
  }

  @Test void unknownNestedFieldsStillFailClosed() throws Exception {
    var info = bridge.coreSchema().parse("PlayerSimpleInfo", new byte[] {(byte) 0x98, 0x06, 1});
    var source = DynamicMessage.newBuilder(bridge.coreSchema().message("GetFriendListInfoScRsp"));
    var list = source.getDescriptorForType().findFieldByName("friend_list");
    var row = DynamicMessage.newBuilder(list.getMessageType());
    row.setField(row.getDescriptorForType().findFieldByName("simple_info"), info);
    source.addRepeatedField(list, row.build());
    assertThrows(ProtocolException.class, () -> bridge.outbound(CmdId.GetFriendListInfoScRsp,
        source.build().toByteArray()));
  }
}
