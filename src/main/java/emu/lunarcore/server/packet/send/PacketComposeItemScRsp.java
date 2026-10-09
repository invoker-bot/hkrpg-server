package emu.lunarcore.server.packet.send;

import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.proto.ComposeItemScRspOuterClass.ComposeItemScRsp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import java.util.Collection;

public class PacketComposeItemScRsp extends BasePacket {

  public PacketComposeItemScRsp(int composeId, int count, Collection<GameItem> returnList) {
    super(CmdId.ComposeItemScRsp);

    var data = ComposeItemScRsp.newInstance().setComposeId(composeId).setCount(count);

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
