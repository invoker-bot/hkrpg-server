package emu.lunarcore.server.packet.send;

import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.proto.ComposeSelectedRelicScRspOuterClass.ComposeSelectedRelicScRsp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import java.util.Collection;

public class PacketComposeSelectedRelicScRsp extends BasePacket {

  public PacketComposeSelectedRelicScRsp(int composeId, Collection<GameItem> returnList) {
    super(CmdId.ComposeSelectedRelicScRsp);

    var data = ComposeSelectedRelicScRsp.newInstance().setComposeId(composeId);

    if (returnList != null) {
      for (var item : returnList) {
        data.getMutableReturnItemList().addItemList(item.toProto());
      }
    } else {
      data.setRetcode(Retcode.REQ_PARA_INVALID.getVal());
      data.getMutableReturnItemList();
    }

    this.setData(data);
  }
}
