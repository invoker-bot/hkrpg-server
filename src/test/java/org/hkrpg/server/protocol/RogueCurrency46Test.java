package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.Test;

class RogueCurrency46Test {
  static class Session extends Gameplay46HandlerTest.Session {
    final List<String> names = new ArrayList<>();
    final List<DynamicMessage> messages = new ArrayList<>();
    final Player resident =
        new Player() {
          final Inventory inventory = new Inventory(this);

          public Inventory getInventory() {
            return inventory;
          }

          public void save() {}

          public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {}

          public boolean isOnline() {
            return true;
          }

          public emu.lunarcore.server.game.GameSession getSession() {
            return Session.this;
          }
        };

    Session() {
      resident.setNickname("周期奖励测试");
    }

    public Player getPlayer() {
      return resident;
    }

    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      names.add(name);
      messages.add(response);
    }
  }

  void cycle(Session s, int score) {
    GameplayDefinitions.cycleConstants.put(
        "CycleScore_Refresh",
        com.google.gson.JsonParser.parseString("{\"IntValue\":7}").getAsJsonObject());
    GameplayDefinitions.cycleConstants.put(
        "CycleScore_StartDate",
        com.google.gson.JsonParser.parseString("{\"StringValue\":\"2026-02-09 04:00:00\"}")
            .getAsJsonObject());
    GameplayDefinitions.cycleConstants.put(
        "CycleScore_DestroyItemList",
        com.google.gson.JsonParser.parseString("{\"ArrayValue\":[{\"IntValue\":110}]}")
            .getAsJsonObject());
    s.resident
        .getCycleScoreManager()
        .setClock(
            java.time.Clock.fixed(
                java.time.Instant.parse("2026-02-08T20:00:00Z"), java.time.ZoneOffset.UTC));
    var window = s.resident.getCycleScoreManager().window().orElseThrow();
    var week = new GameplayState.CycleWeek(window.start(), window.end());
    assertTrue(week.credit(score));
    s.resident.getGameplay().getCycleWeeks().put(window.key(), week);
  }

  void request(Session s, String name, int... ranks) throws Exception {
    var request = Gameplay46.builder(s, name);
    for (int rank : ranks)
      Gameplay46.add(request, name.contains("Destroy") ? "BHNEOLKFNAM" : "LLJHJADNDKB", rank);
    assertTrue(
        VersionedPacketHandler.handle(
            s, s.getProtocol().clientCommand(name), request.build().toByteArray()));
  }

  void bonds(Session s, int stock) {
    var excel =
        new Gson()
            .fromJson(
                "{\"ID\":110,\"ItemMainType\":\"Material\",\"PileLimit\":1000}", ItemExcel.class);
    GameData.getItemExcelMap().add(excel);
    if (stock > 0)
      assertTrue(
          s.resident
              .getInventory()
              .addItem(
                  new GameItem(excel, stock) {
                    public void save() {}
                  }));
    var reward =
        new Gson()
            .fromJson("{\"RewardID\":323005,\"ItemID_1\":110,\"Count_1\":50}", RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
    GameplayDefinitions.cycleRewards.put(5, new GameplayDefinitions.CycleReward(5, 9000, 323005));
  }

  @Test
  void everyPinned46CycleRankPaysItsActualCurrencyOrMaterialAndClosesOnlyOnce() throws Exception {
    // Minimal numeric fixtures from resources.lock.json commit
    // 724b139d8c9c32d12552eb95745a4fee72bfe48b.
    int[][] awards = {
      {1, 34, 4},
      {2, 1, 120},
      {3, 235, 30},
      {4, 101, 1},
      {5, 110, 50},
      {6, 2, 300000},
      {7, 1, 105},
      {8, 233, 21},
      {9, 503, 1},
      {10, 241, 1}
    };
    var oldItems = new HashMap<Integer, ItemExcel>();
    var oldRewards = new HashMap<Integer, RewardExcel>();
    var oldRanks = new HashMap<>(GameplayDefinitions.cycleRewards);
    try {
      var s = new Session();
      cycle(s, 18000);
      var gson = new Gson();
      GameplayDefinitions.cycleRewards.clear();
      for (var award : awards) {
        int rank = award[0], item = award[1], quantity = award[2], rewardId = 323000 + rank;
        if (!oldItems.containsKey(item)) {
          oldItems.put(item, GameData.getItemExcelMap().get(item));
          boolean virtual = item == 1 || item == 2 || item == 34;
          var excel =
              gson.fromJson(
                  "{\"ID\":"
                      + item
                      + ",\"ItemMainType\":\""
                      + (virtual ? "Virtual" : "Material")
                      + "\",\"PileLimit\":"
                      + (item == 110 ? 1000 : virtual ? 999999999 : 99999)
                      + "}",
                  ItemExcel.class);
          GameData.getItemExcelMap().add(excel);
          if (!virtual)
            assertTrue(
                s.resident
                    .getInventory()
                    .addItem(
                        new GameItem(excel, 1) {
                          public void save() {}
                        }));
        }
        oldRewards.put(rewardId, GameData.getRewardExcelMap().get(rewardId));
        var reward =
            gson.fromJson(
                "{\"RewardID\":"
                    + rewardId
                    + ",\"ItemID_1\":"
                    + item
                    + ",\"Count_1\":"
                    + quantity
                    + "}",
                RewardExcel.class);
        reward.onLoad();
        GameData.getRewardExcelMap().add(reward);
        GameplayDefinitions.cycleRewards.put(
            rank, new GameplayDefinitions.CycleReward(rank, rank * 1800, rewardId));
      }
      request(s, "CycleScoreRewardTakeRewardCsReq", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
      assertEquals(0, Gameplay46.number(s.response, "retcode"));
      assertTrue(((List<?>) Gameplay46.value(s.response, "NGKOFMBBGNL")).isEmpty());
      var data = (DynamicMessage) Gameplay46.value(s.response, "NFEDANFBIBE");
      assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), Gameplay46.value(data, "OPLNKCOLCKJ"));
      var received = (DynamicMessage) Gameplay46.value(s.response, "ENGHMIAMPLG");
      var quantities = new HashMap<Integer, Integer>();
      for (Object value : (List<?>) Gameplay46.value(received, "item_list")) {
        var item = (DynamicMessage) value;
        quantities.merge(
            Gameplay46.number(item, "item_id"), Gameplay46.number(item, "num"), Integer::sum);
      }
      assertEquals(
          Map.of(34, 4, 1, 225, 235, 30, 101, 1, 110, 50, 2, 300000, 233, 21, 503, 1, 241, 1),
          quantities);
      assertEquals(4, RogueCurrencies.balance(s.resident, 34));
      assertEquals(225, s.resident.getHcoin());
      assertEquals(300000, s.resident.getScoin());
      request(s, "CycleScoreRewardTakeRewardCsReq", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
      assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
      assertEquals(4, RogueCurrencies.balance(s.resident, 34));
      assertEquals(51, s.resident.getInventory().getMaterialByItemId(110).getCount());
    } finally {
      oldItems.forEach(
          (id, item) -> {
            if (item == null) GameData.getItemExcelMap().keySet().remove(id);
            else GameData.getItemExcelMap().add(item);
          });
      oldRewards.forEach(
          (id, item) -> {
            if (item == null) GameData.getRewardExcelMap().keySet().remove(id);
            else GameData.getRewardExcelMap().add(item);
          });
      GameplayDefinitions.cycleRewards.clear();
      GameplayDefinitions.cycleRewards.putAll(oldRanks);
    }
  }

  @Test
  void exceedingSingleBondsRewardWaitsForExplicitDiscardWithoutDeletingOwnedInventory()
      throws Exception {
    var oldItem = GameData.getItemExcelMap().get(110);
    var oldReward = GameData.getRewardExcelMap().get(323005);
    var oldRank = GameplayDefinitions.cycleRewards.get(5);
    try {
      var s = new Session();
      cycle(s, 9000);
      bonds(s, 999);
      request(s, "CycleScoreRewardTakeRewardCsReq", 5);
      assertEquals(0, Gameplay46.number(s.response, "retcode"));
      assertEquals(List.of(5), Gameplay46.value(s.response, "NGKOFMBBGNL"));
      assertEquals(999, s.resident.getInventory().getMaterialByItemId(110).getCount());
      assertTrue(s.resident.getGameplay().getRewards().isEmpty());
      request(s, "CycleScoreRewardDestroyExceedRewardCsReq", 5);
      assertEquals(0, Gameplay46.number(s.response, "retcode"));
      assertEquals(
          List.of(5),
          Gameplay46.value(
              (DynamicMessage) Gameplay46.value(s.response, "NFEDANFBIBE"), "OPLNKCOLCKJ"));
      assertEquals(999, s.resident.getInventory().getMaterialByItemId(110).getCount());
      request(s, "CycleScoreRewardTakeRewardCsReq", 5);
      assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
      assertTrue(((List<?>) Gameplay46.value(s.response, "NGKOFMBBGNL")).isEmpty());
      request(s, "CycleScoreRewardDestroyExceedRewardCsReq", 5);
      assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
      assertEquals(999, s.resident.getInventory().getMaterialByItemId(110).getCount());
    } finally {
      if (oldItem == null) GameData.getItemExcelMap().keySet().remove(110);
      else GameData.getItemExcelMap().add(oldItem);
      if (oldReward == null) GameData.getRewardExcelMap().keySet().remove(323005);
      else GameData.getRewardExcelMap().add(oldReward);
      if (oldRank == null) GameplayDefinitions.cycleRewards.remove(5);
      else GameplayDefinitions.cycleRewards.put(5, oldRank);
    }
  }

  @Test
  void discardRequiresAFrozenOverLimitOfferAndCannotConsumeAnAwardThatNowFits() throws Exception {
    var s = new Session();
    cycle(s, 9000);
    bonds(s, 999);
    request(s, "CycleScoreRewardDestroyExceedRewardCsReq", 5);
    assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
    assertTrue(s.resident.getGameplay().getRewards().isEmpty());
    request(s, "CycleScoreRewardTakeRewardCsReq", 5);
    assertEquals(List.of(5), Gameplay46.value(s.response, "NGKOFMBBGNL"));
    assertTrue(
        s.resident
            .getInventory()
            .removeItem(s.resident.getInventory().getMaterialByItemId(110), 100));
    request(s, "CycleScoreRewardDestroyExceedRewardCsReq", 5);
    assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
    assertEquals(899, s.resident.getInventory().getMaterialByItemId(110).getCount());
    request(s, "CycleScoreRewardTakeRewardCsReq", 5);
    assertEquals(0, Gameplay46.number(s.response, "retcode"));
    assertTrue(((List<?>) Gameplay46.value(s.response, "NGKOFMBBGNL")).isEmpty());
    assertEquals(949, s.resident.getInventory().getMaterialByItemId(110).getCount());
  }

  @Test
  void realFirstCycleRewardGrantsItem34AndCurrencyNotificationsKeep32And33Distinct()
      throws Exception {
    var gson = new Gson();
    var old33 = GameData.getItemExcelMap().get(33);
    var old34 = GameData.getItemExcelMap().get(34);
    var oldReward = GameData.getRewardExcelMap().get(323001);
    try {
      for (int id : List.of(33, 34))
        GameData.getItemExcelMap()
            .add(
                gson.fromJson(
                    "{\"ID\":" + id + ",\"ItemMainType\":\"Virtual\",\"PileLimit\":999999999}",
                    ItemExcel.class));
      var reward =
          gson.fromJson("{\"RewardID\":323001,\"ItemID_1\":34,\"Count_1\":4}", RewardExcel.class);
      reward.onLoad();
      GameData.getRewardExcelMap().add(reward);
      var s = new Session();
      var paid = s.resident.getRewardLedger().claim("actual-46-cycle-rank-1", 323001);
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, paid.retcode());
      assertEquals(4, paid.items().getFirst().getCount());
      assertTrue(s.resident.getInventory().hasItem(34, 4));
      assertFalse(s.resident.getInventory().hasItem(33, 1));
      assertEquals(0, s.resident.getTalentPoints());
      var notify = (DynamicMessage) Gameplay46.value(s.messages.getLast(), "NIKDPODBIEF");
      assertEquals(4, Gameplay46.number(notify, "DBHOIDLKMAB"));
      assertEquals(0, Gameplay46.number(notify, "GBIEAMKLONK"));
      assertEquals(0, Gameplay46.number(notify, "OFHBBKOKANF"));
      assertTrue(s.resident.getInventory().addItem(33, 6));
      s.request("GetRogueInfoCsReq");
      var basic =
          (DynamicMessage)
              Gameplay46.value(
                  (DynamicMessage) Gameplay46.value(s.response, "rogue_game_info"), "LFLCDNGALME");
      var currency = (DynamicMessage) Gameplay46.value(basic, "NIKDPODBIEF");
      assertEquals(6, Gameplay46.number(currency, "CICAENDEDFE"));
      assertEquals(4, Gameplay46.number(currency, "PDGBEJHNHHE"));
      assertNotEquals(
          0,
          s.resident.getRewardLedger().claim("actual-46-cycle-rank-1", 323001).retcode().getVal());
      assertFalse(s.resident.getInventory().addItem(34, 999999999));
      assertTrue(s.resident.getInventory().hasItem(34, 4));
      var mongo =
          new de.bwaldvogel.mongo.MongoServer(
              new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
      var address = mongo.bind();
      try (var client =
          com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
        var store = dev.morphia.Morphia.createDatastore(client, "rogue-currency-restore");
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
        var document = new Player();
        var uid = Player.class.getDeclaredField("uid");
        uid.setAccessible(true);
        uid.setInt(document, 123456783);
        var gameplay = Player.class.getDeclaredField("gameplay");
        gameplay.setAccessible(true);
        gameplay.set(document, s.resident.getGameplay());
        store.save(document);
        var restored = store.find(Player.class).first();
        assertEquals(6, RogueCurrencies.balance(restored, 33));
        assertEquals(4, RogueCurrencies.balance(restored, 34));
        assertEquals(
            emu.lunarcore.server.packet.Retcode.QUEST_REWARD_ALREADY_TAKEN,
            restored.getRewardLedger().claim("actual-46-cycle-rank-1", 323001).retcode());
      } finally {
        mongo.shutdownNow();
      }
    } finally {
      if (old33 == null) GameData.getItemExcelMap().keySet().remove(33);
      else GameData.getItemExcelMap().add(old33);
      if (old34 == null) GameData.getItemExcelMap().keySet().remove(34);
      else GameData.getItemExcelMap().add(old34);
      if (oldReward == null) GameData.getRewardExcelMap().keySet().remove(323001);
      else GameData.getRewardExcelMap().add(oldReward);
    }
  }
}
