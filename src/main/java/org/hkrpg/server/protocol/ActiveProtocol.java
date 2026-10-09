package org.hkrpg.server.protocol;

import emu.lunarcore.LunarCore;

public final class ActiveProtocol {
    private static final class Holder {
        private static final ProtocolBridge BRIDGE = ProtocolBridge.forVersion(LunarCore.getConfig().protocol.wireVersion);
    }
    public static ProtocolBridge get() { return Holder.BRIDGE; }
    private ActiveProtocol() {}
}
