package org.hkrpg.server.protocol;

import emu.lunarcore.Config;
import emu.lunarcore.HotfixData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Gateway46Test {
    @Test void gatewayUses46FieldNumbersAndKcp() throws Exception {
        var config = new Config();
        config.gameServer.publicAddress = "127.0.0.2";
        config.gameServer.publicPort = 24000;
        var hotfix = new HotfixData();
        hotfix.assetBundleUrl = "https://example.invalid/local/46";
        var wire = ProtocolBridge.forVersion("4.6.0").clientSchema().parse("GateServer", Gateway46.create(config, hotfix));
        var type = wire.getDescriptorForType();
        assertEquals(13, type.findFieldByName("port").getNumber());
        assertEquals(24000, wire.getField(type.findFieldByName("port")));
        assertEquals("127.0.0.2", wire.getField(type.findFieldByName("ip")));
        assertEquals(false, wire.getField(type.findFieldByName("use_tcp")));
        assertEquals(hotfix.assetBundleUrl, wire.getField(type.findFieldByName("asset_bundle_url")));
    }
}
