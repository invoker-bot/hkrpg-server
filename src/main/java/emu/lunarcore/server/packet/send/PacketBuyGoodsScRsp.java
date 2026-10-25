package emu.lunarcore.server.packet.send;

import java.util.List;

import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.proto.BuyGoodsCsReqOuterClass.BuyGoodsCsReq;
import emu.lunarcore.proto.BuyGoodsScRspOuterClass.BuyGoodsScRsp;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketBuyGoodsScRsp extends BasePacket {

    public PacketBuyGoodsScRsp(BuyGoodsCsReq req, List<GameItem> items) {
        this(req, new emu.lunarcore.game.shop.ShopService.Purchase(
                items == null ? emu.lunarcore.server.packet.Retcode.FAIL : emu.lunarcore.server.packet.Retcode.SUCC,
                items, req.getGoodsNum()));
    }

    public PacketBuyGoodsScRsp(BuyGoodsCsReq req, emu.lunarcore.game.shop.ShopService.Purchase result) {
        super(CmdId.BuyGoodsScRsp);

        var data = BuyGoodsScRsp.newInstance().setShopId(req.getShopId()).setGoodsId(req.getGoodsId())
                .setRetcode(result.code().getVal());
        var items = result.items();

        if (result.code() == emu.lunarcore.server.packet.Retcode.SUCC && items != null) {
            ItemList returnItems = ItemList.newInstance();
            items.stream().map(GameItem::toProto).forEach(returnItems::addItemList);

            data.setShopId(req.getShopId());
            data.setGoodsId(req.getGoodsId());
            data.setGoodsBuyTimes(result.bought());
            data.setReturnItemList(returnItems);
        }

        this.setData(data);
    }
}
