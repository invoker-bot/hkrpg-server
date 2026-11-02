package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.inventory.tabs.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlayerLevelRewardsTest {
  final Gson gson = new Gson();
  final List<Runnable> restore = new ArrayList<>();

  <T extends GameResource> void install(ExcelMap<T> map,T row) {
    var old = map.get(row.getId());
    restore.add(() -> { map.keySet().remove(row.getId()); if (old != null) map.add(old); });
    map.add(row);
  }

  @BeforeEach void resources() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/player-level-rewards-4.6.json")) {
      var fixture = JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
      for (var f : fixture.getAsJsonArray("files")) {
        String path = f.getAsJsonObject().get("path").getAsString();
        for (var value : f.getAsJsonObject().getAsJsonArray("rows")) {
          var row = value.getAsJsonObject().getAsJsonObject("row").deepCopy();
          for (String name : List.of("ItemName","EquipmentName","RelicName"))
            if (row.has(name) && row.get(name).isJsonObject()) row.add(name,row.getAsJsonObject(name).get("Hash"));
          if (path.endsWith("PlayerLevelConfig.json")) install(GameData.getPlayerLevelExcelMap(),gson.fromJson(row,PlayerLevelExcel.class));
          else if (path.endsWith("RewardData.json")) {
            var reward = gson.fromJson(row,RewardExcel.class); reward.onLoad(); install(GameData.getRewardExcelMap(),reward);
          } else if (path.contains("ItemConfig")) install(GameData.getItemExcelMap(),gson.fromJson(row,ItemExcel.class));
          else if (path.endsWith("EquipmentConfig.json")) install(GameData.getEquipExcelMap(),gson.fromJson(row,EquipmentExcel.class));
          else if (path.endsWith("RelicConfig.json")) install(GameData.getRelicExcelMap(),gson.fromJson(row,RelicExcel.class));
        }
      }
    }
  }
  @AfterEach void cleanup() { Collections.reverse(restore); restore.forEach(Runnable::run); }

  static class TestPlayer extends Player {
    int saves, failAt, grants;
    boolean partial, throwGrant;
    ItemParamMap received;
    final List<GameplayState> saved = new ArrayList<>();
    // Delivery is a deliberate test double. Real inventory and H2 are checked by the native probe.
    final Inventory inventory = new Inventory(this) {
      @Override public GrantResult grantItems(ItemParamMap amounts,boolean hint) {
        assertEquals(1,saves);
        assertFalse(getGameplay().getRewards().values().iterator().next().isGranted());
        grants++;
        if (throwGrant) throw new IllegalStateException("injected delivery failure");
        received = new ItemParamMap(); received.add(amounts);
        var items = new ArrayList<GameItem>();
        for (var entry : amounts) items.add(new GameItem(entry.getIntKey(),entry.getIntValue()));
        return new GrantResult(items,!partial);
      }
    };
    @Override public Inventory getInventory() { return inventory; }
    @Override public void sendPacket(BasePacket packet) {}
    @Override public void save() {
      if (++saves == failAt) throw new IllegalStateException("injected save failure");
      var json = new Gson(); saved.add(json.fromJson(json.toJson(getGameplay()),GameplayState.class));
    }
  }

  TestPlayer player(int level) { var p = new TestPlayer(); p.setLevel(level); return p; }
  GameItem stock(TestPlayer p,int id,int count) {
    var item = new GameItem(id,count); item.setOwner(p);
    p.getInventory().getItems().put(item.getInternalUid(),item);
    p.getInventory().getTab(InventoryTabType.MATERIAL).onAddItem(item);
    return item;
  }

  @Test void screenshotRewardUsesOriginalAmountsAndPersistsBeforeAndAfterDelivery() {
    var p = player(3);
    assertEquals(100003,GameData.getPlayerLevelExcelMap().get(3).getLevelRewardID());
    var result = PlayerLevelRewards.claim(p,3);
    assertEquals(Retcode.SUCC,result.retcode());
    assertEquals(50,p.received.get(1)); assertEquals(10000,p.received.get(2)); assertEquals(5,p.received.get(211));
    assertEquals(3,result.items().size()); assertEquals(2,p.saves);
    assertFalse(p.saved.getFirst().getRewards().get(PlayerLevelRewards.key(3)).isGranted());
    assertTrue(p.saved.getLast().getRewards().get(PlayerLevelRewards.key(3)).isGranted());
    assertEquals(List.of(3),PlayerLevelRewards.taken(p));
    assertEquals(Retcode.LEVEL_REWARD_HAS_TAKEN,PlayerLevelRewards.claim(p,3).retcode());
    assertEquals(1,p.grants);
  }

  @ParameterizedTest @ValueSource(ints={-1,0,1,4,70,Integer.MAX_VALUE})
  void ineligibleLevelDoesNotGrantOrReserve(int level) {
    var p = player(3);
    assertEquals(Retcode.LEVEL_REWARD_LEVEL_ERROR,PlayerLevelRewards.claim(p,level).retcode());
    assertEquals(0,p.grants); assertEquals(0,p.saves); assertTrue(p.getGameplay().getRewards().isEmpty());
  }

  @Test void previousLevelsMayBeClaimedWithoutClaimingEveryEarlierLevel() {
    var p = player(20);
    assertEquals(Retcode.SUCC,PlayerLevelRewards.claim(p,3).retcode());
    assertEquals(List.of(3),PlayerLevelRewards.taken(p));
  }

  @ParameterizedTest @ValueSource(strings={"missing","empty","unknown-item","negative"})
  void invalidRewardConfigurationCannotConsumeTheClaim(String mode) {
    var reward = GameData.getRewardExcelMap().get(100003);
    if (mode.equals("missing")) GameData.getRewardExcelMap().keySet().remove(100003);
    else if (mode.equals("empty")) reward.getRewards().clear();
    else reward.getRewards().add(mode.equals("unknown-item") ? 1999999999 : 211,mode.equals("negative") ? -100 : 1);
    var p = player(3);
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST,PlayerLevelRewards.claim(p,3).retcode());
    assertTrue(p.getGameplay().getRewards().isEmpty()); assertEquals(0,p.grants);
  }

  @ParameterizedTest @ValueSource(ints={1,2})
  void walletOverflowDoesNotPartiallyPayAnotherItem(int currency) throws Exception {
    var p = player(3);
    var balance = Player.class.getDeclaredField(currency == 1 ? "hcoin" : "scoin");
    balance.setAccessible(true); balance.setInt(p,999999999);
    assertEquals(Retcode.ITEM_REWARD_EXCEED_LIMIT,PlayerLevelRewards.claim(p,3).retcode());
    assertEquals(0,p.grants); assertTrue(p.getGameplay().getRewards().isEmpty());
  }

  @Test void fullMaterialStackCanRetryAfterSpaceIsAvailable() {
    var p = player(3); var books = stock(p,211,99999);
    assertEquals(Retcode.ITEM_REWARD_EXCEED_LIMIT,PlayerLevelRewards.claim(p,3).retcode());
    assertTrue(p.getGameplay().getRewards().isEmpty());
    books.setCount(99994);
    assertEquals(Retcode.SUCC,PlayerLevelRewards.claim(p,3).retcode());
  }

  @ParameterizedTest @ValueSource(ints={3,12,17,20})
  void fullRelevantInventoryRejectsBeforeAnyDelivery(int level) {
    var p = player(level);
    var type = level == 17 ? InventoryTabType.RELIC : level == 20 ? InventoryTabType.EQUIPMENT : InventoryTabType.MATERIAL;
    p.getInventory().createTab(type,new EquipInventoryTab(0));
    assertEquals(Retcode.ITEM_REWARD_EXCEED_LIMIT,PlayerLevelRewards.claim(p,level).retcode());
    assertTrue(p.getGameplay().getRewards().isEmpty()); assertEquals(0,p.grants);
  }

  @Test void interruptedDeliveryIsExcludedFromTakenListAndCannotAutomaticallyRepeat() {
    var p = player(3); p.partial = true;
    assertEquals(Retcode.PLAYER_DATA_ERROR,PlayerLevelRewards.claim(p,3).retcode());
    assertTrue(PlayerLevelRewards.taken(p).isEmpty());
    p.partial = false;
    assertEquals(Retcode.SYSTEM_BUSY,PlayerLevelRewards.claim(p,3).retcode());
    assertEquals(1,p.grants);
  }

  @Test void failedReservationMayRetryBecauseNothingWasDelivered() {
    var p = player(3); p.failAt = 1;
    assertThrows(IllegalStateException.class,()->PlayerLevelRewards.claim(p,3));
    assertTrue(p.getGameplay().getRewards().isEmpty()); assertEquals(0,p.grants);
    p.failAt = 0; p.saves = 0;
    assertEquals(Retcode.SUCC,PlayerLevelRewards.claim(p,3).retcode());
  }

  @Test void finalSaveFailureKeepsDeliveryPendingAndBlocksRepeat() {
    var p = player(3); p.failAt = 2;
    assertThrows(IllegalStateException.class,()->PlayerLevelRewards.claim(p,3));
    assertTrue(PlayerLevelRewards.taken(p).isEmpty());
    p.failAt = 0;
    assertEquals(Retcode.SYSTEM_BUSY,PlayerLevelRewards.claim(p,3).retcode());
    assertEquals(1,p.grants);
  }

  @Test void thrownDeliveryRetainsPendingReceipt() {
    var p = player(3); p.throwGrant = true;
    assertThrows(IllegalStateException.class,()->PlayerLevelRewards.claim(p,3));
    assertEquals(Retcode.SYSTEM_BUSY,PlayerLevelRewards.claim(p,3).retcode());
    assertTrue(PlayerLevelRewards.taken(p).isEmpty()); assertEquals(1,p.grants);
  }

  @Test void simultaneousButtonPressesCanOnlyGrantOnce() throws Exception {
    var p = player(3);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var results = executor.<Retcode>invokeAll(List.of(() -> PlayerLevelRewards.claim(p,3).retcode(),
          () -> PlayerLevelRewards.claim(p,3).retcode()));
      var codes = new HashSet<Retcode>(); for (var result : results) codes.add(result.get());
      assertEquals(Set.of(Retcode.SUCC,Retcode.LEVEL_REWARD_HAS_TAKEN),codes);
    }
    assertEquals(1,p.grants);
  }
}
