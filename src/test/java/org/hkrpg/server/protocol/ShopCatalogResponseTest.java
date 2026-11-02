package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.ShopExcel;
import emu.lunarcore.data.excel.ShopGoodsExcel;
import emu.lunarcore.server.packet.send.PacketGetShopListScRsp;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.UUID;
import emu.lunarcore.game.shop.ShopAvailability;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Shop IDs in a reply must come from the selected catalog, including the legacy wire path. */
class ShopCatalogResponseTest {
  @TempDir Path directory;
  List<ShopExcel> original;

  @BeforeEach
  void isolateCatalog() {
    original = new ArrayList<>(GameData.getShopExcelMap().values());
    GameData.getShopExcelMap().values().clear();
  }

  @AfterEach
  void restoreCatalog() {
    GameData.getShopExcelMap().values().clear();
    original.forEach(GameData.getShopExcelMap()::add);
  }

  @ParameterizedTest
  @ValueSource(strings = {"4.2.0", "4.6.0"})
  void unlistedGoodsAndEmptyShopsAreHiddenOnBothProtocolsWithoutMutatingResources(String version) throws Exception {
    shop(101, 1, 10001);shop(102, 1, 10002);shop(201, 2, 20001);
    var availability = new ShopAvailability(directory);
    availability.setListed(List.of(new ShopAvailability.GoodsKey(101,10001)),false,UUID.randomUUID().toString());
    var packet = new PacketGetShopListScRsp(null,1,availability);
    var bridge = ProtocolBridge.forVersion(version);
    var wire = bridge.outbound(packet.getCmdId(),packet.getData().toByteArray());
    var rows = shops(bridge.clientSchema().parse("GetShopListScRsp",wire.body()));
    assertEquals(List.of(102),rows.stream().map(s -> field(s,"shop_id")).toList());
    assertEquals(1,GameData.getShopExcelMap().get(101).getGoods().size());
    availability.setListed(List.of(new ShopAvailability.GoodsKey(101,10001)),true,UUID.randomUUID().toString());
    packet = new PacketGetShopListScRsp(null,1,availability);
    wire = bridge.outbound(packet.getCmdId(),packet.getData().toByteArray());
    assertEquals(2,shops(bridge.clientSchema().parse("GetShopListScRsp",wire.body())).size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"4.2.0", "4.6.0"})
  void anEmptyCatalogCannotInventARechargeShop(String version) throws Exception {
    var response = response(version, 1);
    assertEquals(1, field(response, "shop_type"));
    assertEquals(List.of(), shops(response));
    assertEquals(0, GameData.getShopExcelMap().size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"4.2.0", "4.6.0"})
  void onlyMatchingConfiguredShopsWithGoodsAreReturned(String version) throws Exception {
    shop(101, 1, 10001);
    shop(201, 2, 20001);
    shop(102, 1, 0);
    var rows = shops(response(version, 1));
    assertEquals(List.of(101), rows.stream().map(s -> field(s, "shop_id")).toList());
    var goods = messages(rows.getFirst(), "goods_list");
    assertEquals(1, goods.size());
    assertEquals(10001, field(goods.getFirst(), "goods_id"));
    assertEquals(10101, field(goods.getFirst(), "item_id"));
    assertEquals(3, GameData.getShopExcelMap().size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"4.2.0", "4.6.0"})
  void anExplicitlyConfigured988FollowsTheSameCatalogRules(String version) throws Exception {
    shop(988, 2, 98801);
    assertEquals(List.of(), shops(response(version, 1)));
    var rows = shops(response(version, 2));
    assertEquals(List.of(988), rows.stream().map(s -> field(s, "shop_id")).toList());
    assertEquals(98801, field(messages(rows.getFirst(), "goods_list").getFirst(), "goods_id"));
  }

  private static void shop(int id, int type, int goodsId) {
    var shop = ResourceLoader.getGson().fromJson(
        "{\"ShopID\":" + id + ",\"ShopType\":" + type + "}", ShopExcel.class);
    GameData.getShopExcelMap().add(shop);
    if (goodsId == 0) return;
    var goods = ResourceLoader.getGson().fromJson(
        "{\"GoodsID\":" + goodsId + ",\"ShopID\":" + id
            + ",\"ItemID\":10101,\"ItemCount\":1,\"CurrencyList\":[2],\"CurrencyCostList\":[10]}",
        ShopGoodsExcel.class);
    goods.onLoad();
  }

  private static DynamicMessage response(String version, int type) throws Exception {
    var packet = new PacketGetShopListScRsp(type);
    var bridge = ProtocolBridge.forVersion(version);
    var wire = bridge.outbound(packet.getCmdId(), packet.getData().toByteArray());
    assertEquals(version.equals("4.6.0") ? 1525 : 1510, wire.cmdId());
    return bridge.clientSchema().parse("GetShopListScRsp", wire.body());
  }

  private static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  @SuppressWarnings("unchecked")
  private static List<DynamicMessage> messages(DynamicMessage message, String name) {
    return (List<DynamicMessage>) field(message, name);
  }

  private static List<DynamicMessage> shops(DynamicMessage response) {
    return messages(response, "shop_list");
  }
}
