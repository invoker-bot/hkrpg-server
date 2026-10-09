package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.*;
import emu.lunarcore.server.packet.CmdId;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InitialData46Test {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    static Object field(DynamicMessage message, String name) { return message.getField(message.getDescriptorForType().findFieldByName(name)); }
    static DynamicMessage first(DynamicMessage message, String name) { return (DynamicMessage) ((List<?>) field(message, name)).getFirst(); }

    @Test void avatarPathsPreserveRewardsSkillsSkinsAndEquippedRelics() throws Exception {
        var core = GetAvatarDataScRspOuterClass.GetAvatarDataScRsp.newInstance().setIsGetAll(true).addOwnedSkinList(1101);
        core.addAvatarList(AvatarOuterClass.Avatar.newInstance().setBaseAvatarId(8001).setChangedAvatarType(8005)
            .setLevel(30).setFirstMetTimestamp(1700000000L).addTakenRewards(2));
        var path = AvatarPathInfoOuterClass.AvatarPathInfo.newInstance().setAvatarId(8005).setRank(2)
            .setAvatarSkin(1101).setEnhanceId(1).setPathEquipmentId(101);
        path.addSkilltreeList(AvatarPathSkillTreeOuterClass.AvatarPathSkillTree.newInstance().setAnchorPointId(800501).setLevel(4));
        path.addEquipRelicList(EquipRelicOuterClass.EquipRelic.newInstance().setSlot(3).setRelicUniqueId(102));
        core.addAvatarPathInfoList(path);
        core.getMutablePlayerOutfitData().addOutfitIdList(220000);
        var result = bridge.outbound(CmdId.GetAvatarDataScRsp, core.toByteArray());
        var wire = bridge.clientSchema().parse("GetAvatarDataScRsp", result.body());
        var avatar = first(wire, "avatar_list");
        assertEquals(8005, field(avatar, "cur_multi_path_avatar_type"));
        assertEquals(1700000000L, field(avatar, "first_met_time_stamp"));
        assertEquals(List.of(2), field(avatar, "has_taken_promotion_reward_list"));
        var convertedPath = first(wire, "avatar_path_data_info_list");
        assertEquals(1101, field(convertedPath, "dressed_skin_id"));
        assertEquals(800501, field(first(convertedPath, "avatar_path_skill_tree"), "point_id"));
        assertEquals(3, field(first(convertedPath, "equip_relic_list"), "type"));
        assertEquals(List.of(1101), field(wire, "skin_list"));
        assertEquals(List.of(220000), field((DynamicMessage) field(wire, "DHKFCDAGHDM"), "EAKOLIJOEPA"));
    }

    @Test void conflictingSceneStateIsRejectedAndLegacyStateRemainsUntouched() {
        var scene = SceneInfoOuterClass.SceneInfo.newInstance().setFloorId(10001001);
        scene.addGroupStateList(SceneGroupStateOuterClass.SceneGroupState.newInstance().setGroupId(20).setState(1));
        scene.addGroupStateList(SceneGroupStateOuterClass.SceneGroupState.newInstance().setGroupId(20).setState(3));
        var core = GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp.newInstance().setScene(scene);
        assertThrows(ProtocolException.class, () -> bridge.outbound(CmdId.GetCurSceneInfoScRsp, core.toByteArray()));
        assertArrayEquals(core.toByteArray(), ProtocolBridge.forVersion("4.2.0").outbound(CmdId.GetCurSceneInfoScRsp, core.toByteArray()).body());
    }

    @Test void equipmentOwnershipIsPreservedInTheBagAndSync() throws Exception {
        var equipment = EquipmentOuterClass.Equipment.newInstance().setUniqueId(101).setTid(20001).setEquipAvatarId(8005);
        var relic = RelicOuterClass.Relic.newInstance().setUniqueId(102).setTid(61001).setEquipAvatarId(8005);
        var core = GetBagScRspOuterClass.GetBagScRsp.newInstance().addEquipmentList(equipment).addRelicList(relic);
        var wire = bridge.clientSchema().parse("GetBagScRsp", bridge.outbound(CmdId.GetBagScRsp, core.toByteArray()).body());
        assertEquals(8005, field(first(wire, "equipment_list"), "dress_avatar_id"));
        assertEquals(8005, field(first(wire, "relic_list"), "dress_avatar_id"));
        var sync = PlayerSyncScNotifyOuterClass.PlayerSyncScNotify.newInstance().addEquipmentList(equipment);
        var update = bridge.clientSchema().parse("PlayerSyncScNotify", bridge.outbound(CmdId.PlayerSyncScNotify, sync.toByteArray()).body());
        assertEquals(8005, field(first(update, "equipment_list"), "dress_avatar_id"));
    }

    @Test void sceneGroupStateAndSignedPropertiesSurviveTheNewRepresentation() throws Exception {
        var scene = SceneInfoOuterClass.SceneInfo.newInstance().setFloorId(10001001).setPlaneId(10001).setEntryId(1000101);
        scene.getMutableSceneIdentifier().setFloorId(10001001);
        scene.addFloorSavedValueMap(SceneInfoOuterClass.SceneInfo.FloorSavedValueMapEntry.newInstance().setKey("GateOpen").setValue(-1));
        scene.addEntityGroupList(SceneEntityGroupInfoOuterClass.SceneEntityGroupInfo.newInstance().setGroupId(20)
            .addGroupPropertyMap(SceneEntityGroupInfoOuterClass.SceneEntityGroupInfo.GroupPropertyMapEntry.newInstance().setKey("Phase").setValue(-2)));
        scene.addGroupStateList(SceneGroupStateOuterClass.SceneGroupState.newInstance().setGroupId(20).setState(3).setIsDefault(true));
        scene.addGroupStateList(SceneGroupStateOuterClass.SceneGroupState.newInstance().setGroupId(21).setState(1).setIsDefault(true));
        var core = GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp.newInstance().setScene(scene);
        var wire = bridge.clientSchema().parse("GetCurSceneInfoScRsp", bridge.outbound(CmdId.GetCurSceneInfoScRsp, core.toByteArray()).body());
        var snapshot = (DynamicMessage) field(wire, "scene");
        assertEquals(3, field(first(snapshot, "entity_group_list"), "state"));
        assertEquals(-2, field(first(first(snapshot, "entity_group_list"), "BANBECDCDHG"), "value"));
        assertEquals(-1, field(first(snapshot, "floor_saved_data"), "value"));
        assertEquals(2, ((List<?>) field(snapshot, "entity_group_list")).size());
    }
}
