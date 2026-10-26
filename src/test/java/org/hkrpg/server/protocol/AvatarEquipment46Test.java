package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.GameConstants;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.avatar.AvatarMultiPath;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.AvatarOuterClass.Avatar;
import emu.lunarcore.proto.AvatarPathInfoOuterClass.AvatarPathInfo;
import emu.lunarcore.proto.GetAvatarDataScRspOuterClass.GetAvatarDataScRsp;
import emu.lunarcore.proto.PlayerSyncScNotifyOuterClass.PlayerSyncScNotify;
import emu.lunarcore.server.packet.CmdId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CN4.6 Avatar field7 is the active cone UID, separately from AvatarPathData field2. */
class AvatarEquipment46Test {
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  Avatar avatar(int base, int active) {
    return Avatar.newInstance().setBaseAvatarId(base).setChangedAvatarType(active).setLevel(12);
  }

  AvatarPathInfo path(int id, int uid) {
    return AvatarPathInfo.newInstance().setAvatarId(id).setPathEquipmentId(uid);
  }

  DynamicMessage wire(String name, int command, byte[] bytes) throws Exception {
    return bridge.clientSchema().parse(name, bridge.outbound(command, bytes).body());
  }

  @SuppressWarnings("unchecked")
  List<DynamicMessage> rows(DynamicMessage message, String field) {
    return (List<DynamicMessage>)
        message.getField(message.getDescriptorForType().findFieldByName(field));
  }

  DynamicMessage nested(DynamicMessage message, String field) {
    return (DynamicMessage) message.getField(message.getDescriptorForType().findFieldByName(field));
  }

  int number(DynamicMessage message, String field) {
    return (Integer) message.getField(message.getDescriptorForType().findFieldByName(field));
  }

  @ParameterizedTest
  @CsvSource({"1003,1003", "8001,8002", "8001,8004", "1001,1224"})
  void loginSnapshotPublishesTheCurrentConeInBothNativeLocations(int base, int active)
      throws Exception {
    var packet =
        GetAvatarDataScRsp.newInstance()
            .setIsGetAll(true)
            .addAvatarList(avatar(base, active))
            .addAvatarPathInfoList(path(active, 98));
    if (base != active) packet.addAvatarPathInfoList(path(base, 57));
    var snapshot = wire("GetAvatarDataScRsp", CmdId.GetAvatarDataScRsp, packet.toByteArray());
    var current = rows(snapshot, "avatar_list").getFirst();
    assertEquals(
        7, current.getDescriptorForType().findFieldByName("equipment_unique_id").getNumber());
    assertEquals(98, number(current, "equipment_unique_id"));
    assertEquals(active, number(current, "cur_multi_path_avatar_type"));
    assertEquals(
        98, number(rows(snapshot, "avatar_path_data_info_list").getFirst(), "path_equipment_id"));
  }

  @Test
  void equipAndSwapDeltasPublishEachAvatarsOwnCurrentCone() throws Exception {
    var packet = PlayerSyncScNotify.newInstance();
    packet
        .getMutableAvatarSync()
        .addAvatarList(avatar(8001, 8002))
        .addAvatarList(avatar(1003, 1003))
        .addAvatarPathInfoList(path(1003, 81))
        .addAvatarPathInfoList(path(8002, 98))
        .addAvatarPathInfoList(path(8001, 57));
    var sync =
        nested(
            wire("PlayerSyncScNotify", CmdId.PlayerSyncScNotify, packet.toByteArray()),
            "avatar_sync");
    assertEquals(98, number(rows(sync, "avatar_list").get(0), "equipment_unique_id"));
    assertEquals(81, number(rows(sync, "avatar_list").get(1), "equipment_unique_id"));
  }

  @Test
  void unequippedActivePathCannotBorrowAnInactivePathsCone() throws Exception {
    var packet = PlayerSyncScNotify.newInstance();
    packet
        .getMutableAvatarSync()
        .addAvatarList(avatar(8001, 8002))
        .addAvatarPathInfoList(path(8001, 57))
        .addAvatarPathInfoList(path(8002, 0));
    var sync =
        nested(
            wire("PlayerSyncScNotify", CmdId.PlayerSyncScNotify, packet.toByteArray()),
            "avatar_sync");
    assertEquals(0, number(rows(sync, "avatar_list").getFirst(), "equipment_unique_id"));
  }

  @Test
  void aMissingMatchingPathDoesNotInventAnEquipmentReference() throws Exception {
    var packet =
        GetAvatarDataScRsp.newInstance()
            .addAvatarList(avatar(8001, 8002))
            .addAvatarPathInfoList(path(8001, 57));
    var result = wire("GetAvatarDataScRsp", CmdId.GetAvatarDataScRsp, packet.toByteArray());
    assertEquals(0, number(rows(result, "avatar_list").getFirst(), "equipment_unique_id"));
  }

  @Test
  void pathOnlyDeltaDoesNotCreateOrChangeTheActiveAvatar() throws Exception {
    var packet = PlayerSyncScNotify.newInstance();
    packet.getMutableAvatarSync().addAvatarPathInfoList(path(8004, 57));
    var sync =
        nested(
            wire("PlayerSyncScNotify", CmdId.PlayerSyncScNotify, packet.toByteArray()),
            "avatar_sync");
    assertTrue(rows(sync, "avatar_list").isEmpty());
    assertEquals(
        57, number(rows(sync, "avatar_path_data_info_list").getFirst(), "path_equipment_id"));
  }

  @Test
  void legacyEquipmentPacketsKeepTheirOriginalBytes() {
    var packet = PlayerSyncScNotify.newInstance();
    packet
        .getMutableAvatarSync()
        .addAvatarList(avatar(8001, 8002))
        .addAvatarPathInfoList(path(8002, 98));
    var original = packet.toByteArray();
    assertArrayEquals(
        original,
        ProtocolBridge.forVersion("4.2.0").outbound(CmdId.PlayerSyncScNotify, original).body());
  }

  @Test
  void failedLoginSnapshotIsNotEnrichedWithEquipment() throws Exception {
    var packet =
        GetAvatarDataScRsp.newInstance()
            .setRetcode(1)
            .addAvatarList(avatar(1003, 1003))
            .addAvatarPathInfoList(path(1003, 98));
    var result = wire("GetAvatarDataScRsp", CmdId.GetAvatarDataScRsp, packet.toByteArray());
    assertEquals(0, number(rows(result, "avatar_list").getFirst(), "equipment_unique_id"));
  }

  AvatarMultiPath actualPath(Player owner, int id) {
    return new AvatarMultiPath(
        owner, new Gson().fromJson("{\"AvatarID\":" + id + "}", AvatarExcel.class));
  }

  GameItem actualCone(Player owner) {
    var cone =
        new GameItem(
            new Gson().fromJson("{\"ID\":20000,\"ItemMainType\":\"Equipment\"}", ItemExcel.class));
    cone.setOwner(owner);
    return cone;
  }

  @ParameterizedTest
  @CsvSource({"8001,8002", "1001,1224"})
  void activeMultiPathEquipmentMutationProducesARealAvatarAndPathDelta(int base, int active)
      throws Exception {
    var owner = new Player();
    var path = actualPath(owner, active);
    var avatar =
        new GameAvatar(new Gson().fromJson("{\"AvatarID\":" + base + "}", AvatarExcel.class));
    avatar.setMultiPath(path);
    var cone = actualCone(owner);
    path.getEquips().put(GameConstants.EQUIPMENT_SLOT_ID, cone);
    var packet = PlayerSyncScNotify.newInstance();
    path.onSync(packet);
    var sync =
        nested(
            wire("PlayerSyncScNotify", CmdId.PlayerSyncScNotify, packet.toByteArray()),
            "avatar_sync");
    assertEquals(1, rows(sync, "avatar_list").size());
    assertEquals(base, number(rows(sync, "avatar_list").getFirst(), "base_avatar_id"));
    assertEquals(
        active, number(rows(sync, "avatar_list").getFirst(), "cur_multi_path_avatar_type"));
    assertEquals(
        cone.getInternalUid(), number(rows(sync, "avatar_list").getFirst(), "equipment_unique_id"));
    assertEquals(active, number(rows(sync, "avatar_path_data_info_list").getFirst(), "avatar_id"));
  }

  @Test
  void inactiveMultiPathEquipmentMutationUpdatesOnlyItsOwnPath() throws Exception {
    var owner = new Player();
    var path = actualPath(owner, 8004);
    var cone = actualCone(owner);
    path.getEquips().put(GameConstants.EQUIPMENT_SLOT_ID, cone);
    var packet = PlayerSyncScNotify.newInstance();
    path.onSync(packet);
    var sync =
        nested(
            wire("PlayerSyncScNotify", CmdId.PlayerSyncScNotify, packet.toByteArray()),
            "avatar_sync");
    assertTrue(rows(sync, "avatar_list").isEmpty());
    assertEquals(1, rows(sync, "avatar_path_data_info_list").size());
    assertEquals(8004, number(rows(sync, "avatar_path_data_info_list").getFirst(), "avatar_id"));
    assertEquals(
        cone.getInternalUid(),
        number(rows(sync, "avatar_path_data_info_list").getFirst(), "path_equipment_id"));
  }

  @Test
  void activeMultiPathUnequipPublishesAnEmptySlotOnTheFormalAvatar() throws Exception {
    var owner = new Player();
    var path = actualPath(owner, 8002);
    var avatar = new GameAvatar(new Gson().fromJson("{\"AvatarID\":8001}", AvatarExcel.class));
    avatar.setMultiPath(path);
    var packet = PlayerSyncScNotify.newInstance();
    path.onSync(packet);
    var sync =
        nested(
            wire("PlayerSyncScNotify", CmdId.PlayerSyncScNotify, packet.toByteArray()),
            "avatar_sync");
    assertEquals(1, rows(sync, "avatar_list").size());
    assertEquals(8002, number(rows(sync, "avatar_list").getFirst(), "cur_multi_path_avatar_type"));
    assertEquals(0, number(rows(sync, "avatar_list").getFirst(), "equipment_unique_id"));
    assertEquals(
        0, number(rows(sync, "avatar_path_data_info_list").getFirst(), "path_equipment_id"));
  }
}
