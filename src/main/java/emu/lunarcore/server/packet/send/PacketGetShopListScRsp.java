package emu.lunarcore.server.packet.send;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ShopExcel;
import emu.lunarcore.game.shop.ShopAvailability;
import emu.lunarcore.proto.GetShopListScRspOuterClass.GetShopListScRsp;
import emu.lunarcore.proto.ShopOuterClass.Shop;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketGetShopListScRsp extends BasePacket {

    public PacketGetShopListScRsp(int shopType) {
        this(null, shopType);
    }

    public PacketGetShopListScRsp(emu.lunarcore.game.player.Player player, int shopType) {
        this(player, shopType, emu.lunarcore.LunarCore.getGameServer() == null ? new ShopAvailability()
                : emu.lunarcore.LunarCore.getGameServer().getShopService().getAvailability());
    }

    public PacketGetShopListScRsp(emu.lunarcore.game.player.Player player, int shopType,
            ShopAvailability availability) {
        super(CmdId.GetShopListScRsp);
        var now = java.time.Instant.now();
        var disabled = availability.snapshot();

        var data = GetShopListScRsp.newInstance()
                .setShopType(shopType);

        for (ShopExcel shopExcel : GameData.getShopExcelMap().values()) {
            if (shopExcel.getShopType() != shopType || shopExcel.getGoods().size() == 0) {
                continue;
            }

            Shop shop = Shop.newInstance()
                    .setShopId(shopExcel.getId())
                    .setCityLevel(1)
                    .setEndTime(Integer.MAX_VALUE);

            for (var goodsExcel : shopExcel.getGoods().values()) {
                if (disabled.contains(new ShopAvailability.GoodsKey(shopExcel.getId(), goodsExcel.getGoodsID()))) continue;
                var goods = goodsExcel.toProto().setItemId(goodsExcel.itemAt(now));
                if (player != null) goods.setBuyTimes(emu.lunarcore.game.shop.ShopService.bought(player, goodsExcel, now));
                shop.addGoodsList(goods);
            }

            if (shop.getGoodsList().length() > 0) data.addShopList(shop);
        }

        this.setData(data);
    }
}
