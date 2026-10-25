package emu.lunarcore.server.packet.send;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.GetPamSkinDataScRspOuterClass.GetPamSkinDataScRsp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketGetPamSkinDataScRsp extends BasePacket {

    public PacketGetPamSkinDataScRsp(Player player) {
        super(CmdId.GetPamSkinDataScRsp);

        // ConstValuePamSkin.Default_PamSkin_ID is 252000. Older records may contain the
        // inherited truncated default 25200. Normalize only the response; queries do not save
        // or overwrite an existing selection.
        int currentSkin = player.getCurrentPamSkinId();
        if (currentSkin == 0 || currentSkin == 25200) currentSkin = 252000;

        var pamSkinsList = org.hkrpg.server.protocol.ShopCosmetics46.pamSkins(player);

        var data = GetPamSkinDataScRsp.newInstance()
                .addAllUnlockedPamSkins(pamSkinsList.stream().mapToInt(Integer::intValue).toArray())
                .setCurrentPamSkinId(currentSkin);

        this.setData(data);
    }
}
