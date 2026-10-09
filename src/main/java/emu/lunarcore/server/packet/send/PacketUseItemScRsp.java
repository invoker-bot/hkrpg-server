package emu.lunarcore.server.packet.send;

import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.proto.UseItemScRspOuterClass.UseItemScRsp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import java.util.List;

public class PacketUseItemScRsp extends BasePacket {

  public PacketUseItemScRsp(int itemId, int itemCount, List<GameItem> returnItems) {
    this(
        itemId,
        returnItems == null ? 0 : itemCount,
        returnItems == null ? Retcode.ITEM_INVALID_USE : Retcode.SUCC,
        returnItems);
  }

  public PacketUseItemScRsp(
      int itemId, int itemCount, Retcode retcode, List<GameItem> returnItems) {
    super(CmdId.UseItemScRsp);

    var itemList = ItemList.newInstance();

    if (returnItems != null && returnItems.size() > 0) {
      for (var item : returnItems) {
        itemList.addItemList(item.toProto());
      }
    }

    var data =
        UseItemScRsp.newInstance()
            .setRetcode(retcode.getVal())
            .setUseItemId(itemId)
            .setUseItemCount(itemCount)
            .setReturnData(itemList);

    this.setData(data);
  }
}
