package emu.lunarcore.server.packet.send;

import java.util.List;

import emu.lunarcore.game.gacha.GachaBanner;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.DoGachaScRspOuterClass.DoGachaScRsp;
import emu.lunarcore.proto.GachaItemOuterClass.GachaItem;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;

public class PacketDoGachaScRsp extends BasePacket {

    public PacketDoGachaScRsp() {
        this(Retcode.FAIL);
    }

    public PacketDoGachaScRsp(Retcode retcode) {
        super(CmdId.DoGachaScRsp);

        this.setData(DoGachaScRsp.newInstance().setRetcode(retcode.getVal()));
    }

    public PacketDoGachaScRsp(Player player, GachaBanner banner, int num, List<GachaItem> items) {
        super(CmdId.DoGachaScRsp);

        var data = DoGachaScRsp.newInstance()
                .setGachaNum(num)
                .setGachaId(banner.getId());

        // CN4.6 has no verified equivalent of this legacy field. Keep the counter in
        // PlayerGachaInfo and the native GachaCeiling query; do not guess an obfuscated ID.
        if (player.getSession() == null || !player.getSession().getProtocol().experimental()) {
            data.setCeilingNum(player.getGachaInfo().getCeilingNum());
        }

        for (GachaItem item : items) {
            data.addGachaItemList(item);
        }

        this.setData(data);
    }
}
