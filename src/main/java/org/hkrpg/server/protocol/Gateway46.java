package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.Config;
import emu.lunarcore.HotfixData;

public final class Gateway46 {
    public static byte[] create(Config config, HotfixData hotfix) {
        var type = ProtocolBridge.forVersion("4.6.0").clientSchema().message("GateServer");
        var builder = DynamicMessage.newBuilder(type);
        builder.setField(type.findFieldByName("region_name"), config.gameServer.id);
        builder.setField(type.findFieldByName("ip"), config.gameServer.getPublicAddress());
        builder.setField(type.findFieldByName("port"), config.gameServer.getPublicPort());
        builder.setField(type.findFieldByName("server_description"), config.gameServer.description);
        for (String flag : new String[] {"enable_design_data_bundle_version_update", "enable_video_bundle_version_update",
                "watermark_enable", "enable_upload_battle_log", "ftc_switch", "enable_save_replay_file",
                "android_middle_package_enable", "close_redeem_code", "ios_exam", "mtp_switch", "event_tracking_open",
                "forbid_recharge", "network_diagnostic", "NNFLHCGDGJM", "FMLPNNMJDIC"}) {
            builder.setField(type.findFieldByName(flag), true);
        }
        set(builder, "asset_bundle_url", hotfix.assetBundleUrl);
        set(builder, "ex_resource_url", hotfix.exResourceUrl);
        set(builder, "lua_url", hotfix.luaUrl);
        set(builder, "ifix_url", hotfix.ifixUrl);
        return builder.build().toByteArray();
    }
    private static void set(DynamicMessage.Builder builder, String name, String value) {
        if (value != null && !value.isBlank()) builder.setField(builder.getDescriptorForType().findFieldByName(name), value);
    }
    private Gateway46() {}
}
