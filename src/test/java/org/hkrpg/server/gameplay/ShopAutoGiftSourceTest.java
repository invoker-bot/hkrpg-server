package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.shop.ShopService;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

/** Uses fixed source rows rather than an operator's live resources or player data. */
class ShopAutoGiftSourceTest {
  private static final Gson GSON = ResourceLoader.getGson();
  private static JsonObject source;
  private static final Map<Integer, ItemExcel> ITEMS = new HashMap<>();
  private static final Map<Integer, ItemUseExcel> USES = new HashMap<>();
  private static final Map<Integer, RewardExcel> REWARDS = new HashMap<>();

  @BeforeAll static void loadSource() {
    try (var input = Objects.requireNonNull(ShopAutoGiftSourceTest.class
        .getResourceAsStream("/fixtures/shop-auto-gifts-4.6.json"));
        var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      source = JsonParser.parseReader(reader).getAsJsonObject();
    } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", source.get("sourceCommit").getAsString());
    for (var row : source.getAsJsonArray("items")) {
      var item = GSON.fromJson(row, ItemExcel.class);
      ITEMS.put(item.getId(), GameData.getItemExcelMap().get(item.getId()));
      GameData.getItemExcelMap().add(item);
    }
    for (var row : source.getAsJsonArray("uses")) {
      var use = GSON.fromJson(row, ItemUseExcel.class);
      USES.put(use.getId(), GameData.getItemUseExcelMap().get(use.getId()));
      GameData.getItemUseExcelMap().add(use);
    }
    for (var row : source.getAsJsonArray("rewards")) {
      var reward = GSON.fromJson(row, RewardExcel.class);
      reward.onLoad();
      REWARDS.put(reward.getId(), GameData.getRewardExcelMap().get(reward.getId()));
      GameData.getRewardExcelMap().add(reward);
    }
  }

  @AfterAll static void restore() {
    ITEMS.forEach((id, item) -> {
      if (item == null) GameData.getItemExcelMap().keySet().remove(id);
      else GameData.getItemExcelMap().add(item);
    });
    USES.forEach((id, use) -> {
      if (use == null) GameData.getItemUseExcelMap().keySet().remove(id);
      else GameData.getItemUseExcelMap().add(use);
    });
    REWARDS.forEach((id, reward) -> {
      if (reward == null) GameData.getRewardExcelMap().keySet().remove(id);
      else GameData.getRewardExcelMap().add(reward);
    });
  }

  @Test void allSourceShopRowsExpandTheirExactFixedAutoUseRewards() {
    var goods = source.getAsJsonArray("goods");
    assertEquals(74, goods.size());
    assertEquals(12, ITEMS.size());
    var sourceRewards = new HashMap<Integer, JsonObject>();
    for (var value : source.getAsJsonArray("rewards")) {
      var row = value.getAsJsonObject();
      sourceRewards.put(row.get("RewardID").getAsInt(), row);
    }
    for (var value : goods) {
      var row = value.getAsJsonObject();
      var definition = GSON.fromJson(row, ShopGoodsExcel.class);
      int item = row.get("ItemID").getAsInt();
      int count = row.get("ItemCount").getAsInt();
      var use = GameData.getItemUseExcelMap().get(item);
      assertNotNull(use);
      assertTrue(use.isIsAutoUse());
      var expected = new TreeMap<Integer, Integer>();
      for (int rewardId : use.getUseParam()) {
        var reward = Objects.requireNonNull(sourceRewards.get(rewardId));
        if (reward.has("Hcoin")) expected.merge(1, reward.get("Hcoin").getAsInt() * count, Math::addExact);
        for (int n = 1; n <= 6; n++) {
          if (reward.has("ItemID_" + n))
            expected.merge(reward.get("ItemID_" + n).getAsInt(),
                reward.get("Count_" + n).getAsInt() * count, Math::addExact);
        }
      }
      assertEquals(expected, ShopService.rewards(definition, item, 1, List.of()),
          "goods " + row.get("GoodsID").getAsInt());
    }
  }
}
