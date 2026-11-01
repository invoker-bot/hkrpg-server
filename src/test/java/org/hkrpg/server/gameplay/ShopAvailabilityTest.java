package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.shop.ShopAvailability;
import emu.lunarcore.game.shop.ShopAvailability.GoodsKey;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShopAvailabilityTest {
  @TempDir Path directory;
  final Gson gson = new Gson();
  final int shopId = 199869;
  final GoodsKey first = new GoodsKey(shopId,199869001), second = new GoodsKey(shopId,199869002);
  ShopExcel previous;

  @BeforeEach void catalog() {
    previous = GameData.getShopExcelMap().get(shopId);
    var shop = gson.fromJson("{\"ShopID\":"+shopId+",\"ShopType\":1}",ShopExcel.class);
    GameData.getShopExcelMap().add(shop);
    for (var key : List.of(first,second)) {
      var goods = gson.fromJson("{\"ShopID\":"+shopId+",\"GoodsID\":"+key.goodsId()
          +",\"ItemID\":101,\"ItemCount\":1,\"CurrencyList\":[2],\"CurrencyCostList\":[100]}",ShopGoodsExcel.class);
      goods.onLoad();
    }
  }

  @AfterEach void restore() {
    if (previous == null) GameData.getShopExcelMap().keySet().remove(shopId);
    else GameData.getShopExcelMap().add(previous);
  }

  String request() { return UUID.randomUUID().toString(); }
  Path file() { return directory.resolve("ShopAvailability.json"); }

  @Test void readsAndNoopListingNeverInstallOrOverwriteDefaults() throws Exception {
    var service = new ShopAvailability(directory);
    assertTrue(service.isListed(first.shopId(),first.goodsId()));
    assertEquals(0,service.setListed(List.of(first),true,request()));
    assertFalse(Files.exists(file()));assertFalse(Files.exists(directory.resolve("gm-shop-backups")));
  }

  @Test void atomicBatchSurvivesRestartAndDoesNotRemoveResourceRows() throws Exception {
    var service = new ShopAvailability(directory);
    String id = request();
    assertEquals(2,service.setListed(List.of(first,second),false,id));
    assertEquals(2,GameData.getShopExcelMap().get(shopId).getGoods().size());
    var restarted = new ShopAvailability(directory);
    assertFalse(restarted.isListed(first.shopId(),first.goodsId()));
    assertFalse(restarted.isListed(second.shopId(),second.goodsId()));
    assertTrue(restarted.isListed(shopId+1,first.goodsId()));
    var backup = JsonParser.parseString(Files.readString(directory.resolve("gm-shop-backups/ShopAvailability-"+id+".json"))).getAsJsonObject();
    assertEquals(0,backup.getAsJsonArray("disabledGoods").size());
    assertEquals(1,restarted.setListed(List.of(first),true,request()));
    assertTrue(new ShopAvailability(directory).isListed(first.shopId(),first.goodsId()));
    assertFalse(restarted.isListed(second.shopId(),second.goodsId()));
  }

  @Test void aMissingTargetRejectsTheEntireBatchBeforePersistence() {
    var service = new ShopAvailability(directory);
    assertThrows(IllegalArgumentException.class,()->service.setListed(
        List.of(first,new GoodsKey(shopId+1,second.goodsId())),false,request()));
    assertTrue(service.isListed(first.shopId(),first.goodsId()));assertFalse(Files.exists(file()));
  }

  @Test void backupFailureCannotPublishAnAcknowledgedChange() throws Exception {
    var service = new ShopAvailability(directory);
    Files.writeString(directory.resolve("gm-shop-backups"),"blocked");
    assertThrows(IOException.class,()->service.setListed(List.of(first),false,request()));
    assertFalse(Files.exists(file()));assertTrue(service.isListed(first.shopId(),first.goodsId()));
  }

  @Test void corruptExternalChangesDoNotReplaceTheLastValidMemoryOrFile() throws Exception {
    var service = new ShopAvailability(directory);
    service.setListed(List.of(first),false,request());
    Files.writeString(file(),"{broken");
    assertThrows(IOException.class,()->service.setListed(List.of(first),true,request()));
    assertFalse(service.isListed(first.shopId(),first.goodsId()));assertEquals("{broken",Files.readString(file()));
  }

  @Test void validExternalChangesAndOperatorFieldsAreMergedInsteadOfOverwritten() throws Exception {
    String original = "{\"version\":1,\"operatorNote\":\"保留\",\"disabledGoods\":[{\"shopId\":199869,\"goodsId\":199869002}]}";
    Files.writeString(file(),original);
    var service = new ShopAvailability(directory);
    String id = request();service.setListed(List.of(first),false,id);
    var stored = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
    assertEquals("保留",stored.get("operatorNote").getAsString());
    assertEquals(2,stored.getAsJsonArray("disabledGoods").size());
    assertEquals(original,Files.readString(directory.resolve("gm-shop-backups/ShopAvailability-"+id+".json")));
  }

  @Test void noopPublishesValidDiskStateWithoutCreatingAnotherBackup() throws Exception {
    var service = new ShopAvailability(directory);service.setListed(List.of(first),false,request());
    Files.writeString(file(),"{\"version\":1,\"disabledGoods\":[]}");
    assertEquals(0,service.setListed(List.of(first),true,request()));
    assertTrue(service.isListed(first.shopId(),first.goodsId()));
    try (var backups = Files.list(directory.resolve("gm-shop-backups"))) { assertEquals(1,backups.count()); }
  }

  @ParameterizedTest
  @ValueSource(strings={"null","{}","{\"version\":2,\"disabledGoods\":[]}",
      "{\"version\":1,\"disabledGoods\":[null]}",
      "{\"version\":1,\"disabledGoods\":[{\"shopId\":1.5,\"goodsId\":1}]}",
      "{\"version\":1,\"disabledGoods\":[{\"shopId\":1,\"goodsId\":0}]}",
      "{\"version\":1,\"disabledGoods\":[{\"shopId\":1,\"goodsId\":1},{\"shopId\":1,\"goodsId\":1}]}"})
  void invalidSavedConfigurationIsNotSilentlyReplacedWithAllListed(String json) throws Exception {
    Files.writeString(file(),json);
    assertThrows(IllegalStateException.class,()->new ShopAvailability(directory));
    assertEquals(json,Files.readString(file()));
  }
}
