package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class ItemUseTest {
  static final int FOOD = 19993101,
      DATA = 19993102,
      A = 19993103,
      B = 19993104,
      REWARD = 19993105,
      GOODS = 19993106;
  final Gson gson = new Gson();

  static class Consumer extends Player {
    final Inventory inventory = new Inventory(this);
    final LineupManager teams = new LineupManager(this);
    Inventory actual = inventory;
    boolean battle;
    boolean failAfterDebit;
    int refreshes;

    public Inventory getInventory() {
      return actual;
    }

    public LineupManager getLineupManager() {
      return teams;
    }

    public boolean isInBattle() {
      return battle;
    }

    public void save() {
      if (failAfterDebit
          && getGameplay().getLastItemUse() != null
          && "DEBITED".equals(getGameplay().getLastItemUse().getPhase()))
        throw new IllegalStateException("Simulated player save interruption");
    }

    public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {
      if (packet instanceof emu.lunarcore.server.packet.send.PacketSyncLineupNotify) refreshes++;
    }

    GameAvatar actor(int id, int hp, int sp) {
      var avatar =
          new GameAvatar() {
            public int getAvatarId() {
              return id;
            }

            public void save() {}
          };
      getAvatars().getAvatars().put(id, avatar);
      getCurrentLineup().getAvatars().add(id);
      avatar.setCurrentHp(getCurrentLineup(), hp);
      avatar.setCurrentSp(getCurrentLineup(), sp);
      return avatar;
    }
  }

  @AfterEach
  void reset() {
    for (int id : List.of(FOOD, GOODS)) GameData.getItemExcelMap().keySet().remove(id);
    for (int id : List.of(FOOD, DATA)) GameData.getItemUseExcelMap().keySet().remove(id);
    GameData.getRewardExcelMap().keySet().remove(REWARD);
  }

  Consumer stocked(String method, String config, int count) {
    var p = new Consumer();
    var item =
        gson.fromJson(
            "{\"ID\":"
                + FOOD
                + ",\"ItemMainType\":\"Usable\","
                + "\"ItemSubType\":\"Food\",\"PileLimit\":99,\"UseMethod\":\""
                + method
                + "\"}",
            ItemExcel.class);
    GameData.getItemExcelMap().add(item);
    GameData.getItemUseExcelMap()
        .add(
            gson.fromJson(
                "{\"UseDataID\":" + FOOD + ",\"UseMultipleMax\":1," + config + "}",
                ItemUseExcel.class));
    assertTrue(
        p.inventory.addItem(
            new GameItem(item, count) {
              public void save() {}
            }));
    return p;
  }

  int count(Consumer p) {
    var item = p.inventory.getMaterialByItemId(FOOD);
    return item == null ? 0 : item.getCount();
  }

  @Test
  void energyUsesActualSpAndPowerPercentageInsteadOfHp() {
    var p =
        stocked(
            "TeamSpecificFoodBenefit",
            "\"UseTargetType\":\"Alive\",\"PreviewPowerPercent\":0.3",
            3);
    var a = p.actor(A, 8000, 1000);
    assertNotNull(p.inventory.useItem(FOOD, 1, A));
    assertEquals(4000, a.getCurrentSp(p.getCurrentLineup()));
    assertEquals(8000, a.getCurrentHp(p.getCurrentLineup()));
    assertEquals(2, count(p));
    assertEquals(1, p.refreshes);
  }

  @Test
  void aMissingOrOffTeamTargetAndTooManyUsesCannotConsumeFood() {
    var p =
        stocked(
            "TeamSpecificFoodBenefit",
            "\"UseTargetType\":\"Alive\",\"PreviewPowerPercent\":0.3",
            4);
    var a = p.actor(A, 8000, 1000);
    p.actor(B, 8000, 1000);
    p.getCurrentLineup().getAvatars().remove(Integer.valueOf(B));
    for (int id : List.of(0, B, B + 1)) assertNull(p.inventory.useItem(FOOD, 1, id));
    assertNull(p.inventory.useItem(FOOD, 2, A));
    assertEquals(4, count(p));
    assertEquals(1000, a.getCurrentSp(p.getCurrentLineup()));
  }

  @Test
  void reviveFoodOnlyAcceptsAnActuallyDeadCurrentTeamMember() {
    var p =
        stocked(
            "TeamSpecificFoodBenefit",
            "\"UseTargetType\":\"Dead\",\"PreviewHPRecoveryPercent\":0.1",
            2);
    var a = p.actor(A, 7000, 900);
    assertNull(p.inventory.useItem(FOOD, 1, A));
    assertEquals(7000, a.getCurrentHp(p.getCurrentLineup()));
    assertEquals(2, count(p));
    a.setCurrentHp(p.getCurrentLineup(), 0);
    assertNotNull(p.inventory.useItem(FOOD, 1, A));
    assertEquals(1000, a.getCurrentHp(p.getCurrentLineup()));
    assertEquals(900, a.getCurrentSp(p.getCurrentLineup()));
    assertNull(p.inventory.useItem(FOOD, 1, A));
    assertEquals(1, count(p));
  }

  @Test
  void healingFoodCannotReviveOrConsumeAtFullHealth() {
    var p =
        stocked(
            "TeamSpecificFoodBenefit",
            "\"UseTargetType\":\"Alive\",\"PreviewHPRecoveryPercent\":0.45,\"IsCheckHP\":true",
            3);
    var a = p.actor(A, 0, 100);
    assertNull(p.inventory.useItem(FOOD, 1, A));
    a.setCurrentHp(p.getCurrentLineup(), 10000);
    assertNull(p.inventory.useItem(FOOD, 1, A));
    assertEquals(3, count(p));
    a.setCurrentHp(p.getCurrentLineup(), 8000);
    assertNotNull(p.inventory.useItem(FOOD, 1, A));
    assertEquals(10000, a.getCurrentHp(p.getCurrentLineup()));
  }

  @Test
  void unsupportedFlatHealingAndEmptyEffectsCannotConsumeAnything() {
    for (String c :
        List.of(
            "\"PreviewHPRecoveryValue\":150,\"PreviewHPRecoveryPercent\":0.15",
            "\"PreviewPowerPercent\":2",
            "\"PreviewHPRecoveryPercent\":-2",
            "\"ConsumeType\":3")) {
      var p = stocked("TeamSpecificFoodBenefit", c, 2);
      var a = p.actor(A, 5000, 1000);
      assertNull(p.inventory.useItem(FOOD, 1, A));
      assertEquals(5000, a.getCurrentHp(p.getCurrentLineup()));
      assertEquals(1000, a.getCurrentSp(p.getCurrentLineup()));
      assertEquals(2, count(p));
    }
  }

  @Test
  void battleAndIndependentChallengeLineupsCannotUseOrdinaryFood() {
    var p = stocked("TeamSpecificFoodBenefit", "\"PreviewPowerPercent\":0.3", 2);
    p.actor(A, 5000, 1000);
    p.battle = true;
    assertNull(p.inventory.useItem(FOOD, 1, A));
    p.battle = false;
    p.getLineupManager().setCurrentExtraLineup(2, false);
    assertNull(p.inventory.useItem(FOOD, 1, A));
    assertEquals(2, count(p));
  }

  @Test
  void explicitUseDataReferenceIsFollowedAndUnreferencedDataCannotReplaceIt() {
    var p = stocked("TeamSpecificFoodBenefit", "\"PreviewPowerPercent\":0.3", 2);
    p.actor(A, 5000, 1000);
    var item = p.inventory.getMaterialByItemId(FOOD);
    var excel =
        gson.fromJson(
            "{\"ID\":"
                + FOOD
                + ",\"ItemMainType\":\"Usable\",\"ItemSubType\":\"Food\","
                + "\"UseMethod\":\"TeamSpecificFoodBenefit\",\"UseDataID\":"
                + DATA
                + ",\"PileLimit\":99}",
            ItemExcel.class);
    item.setExcel(excel);
    assertNull(p.inventory.useItem(FOOD, 1, A));
    assertEquals(2, count(p));
    GameData.getItemUseExcelMap()
        .add(
            gson.fromJson(
                "{\"UseDataID\":" + DATA + ",\"UseMultipleMax\":1,\"PreviewPowerPercent\":0.5}",
                ItemUseExcel.class));
    assertNotNull(p.inventory.useItem(FOOD, 1, A));
    assertEquals(6000, p.getAvatarById(A).getCurrentSp(p.getCurrentLineup()));
  }

  @Test
  void invalidExternalFoodTargetCannotBeIgnoredBeforeConsumption() {
    var p = stocked("ExternalSystemFoodBenefit", "\"PreviewPowerPercent\":0.3", 2);
    p.actor(A, 5000, 1000);
    assertNull(p.inventory.useItem(FOOD, 1, B));
    assertEquals(2, count(p));
  }

  @Test
  void missingFixedRewardAndOverfullRewardAreRejectedBeforeDebit() {
    var p = stocked("FixedRewardGift", "\"UseParam\":[" + REWARD + "]", 2);
    assertNull(p.inventory.useItem(FOOD, 1, 0));
    assertEquals(2, count(p));
    reward(3);
    var item =
        new GameItem(GameData.getItemExcelMap().get(GOODS), 9) {
          public void save() {}
        };
    assertTrue(p.inventory.addItem(item));
    assertNull(p.inventory.useItem(FOOD, 1, 0));
    assertEquals(2, count(p));
    assertEquals(9, item.getCount());
  }

  void reward(int number) {
    GameData.getItemExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":" + GOODS + ",\"ItemMainType\":\"Material\",\"PileLimit\":10}",
                ItemExcel.class));
    var reward =
        gson.fromJson(
            "{\"RewardID\":" + REWARD + ",\"ItemID_1\":" + GOODS + ",\"Count_1\":" + number + "}",
            RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
  }

  @Test
  void aFixedGiftReturnsOnlyItsActualGrantAndConsumesOnce() {
    var p = stocked("FixedRewardGift", "\"UseParam\":[" + REWARD + "]", 2);
    reward(3);
    assertTrue(
        p.inventory.addItem(
            new GameItem(GameData.getItemExcelMap().get(GOODS), 2) {
              public void save() {}
            }));
    var result = p.inventory.useItem(FOOD, 1, 0);
    assertNotNull(result);
    assertEquals(1, result.size());
    assertEquals(3, result.getFirst().getCount());
    assertEquals(5, p.inventory.getMaterialByItemId(GOODS).getCount());
    assertEquals(1, count(p));
  }

  @Test
  void concurrentLastFoodUsesCannotRepeatTheRecovery() throws Exception {
    var p = stocked("TeamSpecificFoodBenefit", "\"PreviewPowerPercent\":0.3", 1);
    var a = p.actor(A, 8000, 1000);
    try (var pool = Executors.newFixedThreadPool(6)) {
      var results = new ArrayList<Future<List<GameItem>>>();
      for (int i = 0; i < 12; i++) results.add(pool.submit(() -> p.inventory.useItem(FOOD, 1, A)));
      int success = 0;
      for (var r : results) if (r.get(10, TimeUnit.SECONDS) != null) success++;
      assertEquals(1, success);
    }
    assertEquals(4000, a.getCurrentSp(p.getCurrentLineup()));
    assertEquals(0, count(p));
  }

  @Test
  void teamWideEnergyExcludesDeadMembersAndTechniquePointsAreSavedOnce() {
    var p =
        stocked(
            "ExternalSystemFoodBenefit", "\"PreviewPowerPercent\":0.3,\"PreviewSkillPoint\":2", 2);
    var a = p.actor(A, 5000, 1000);
    var b = p.actor(B, 0, 3000);
    p.getCurrentLineup().setMp(1);
    assertNotNull(p.inventory.useItem(FOOD, 1, 0));
    assertEquals(4000, a.getCurrentSp(p.getCurrentLineup()));
    assertEquals(3000, b.getCurrentSp(p.getCurrentLineup()));
    assertEquals(0, b.getCurrentHp(p.getCurrentLineup()));
    assertEquals(3, p.getCurrentLineup().getMp());
    assertEquals("COMPLETE", p.getGameplay().getLastItemUse().getPhase());
    assertEquals(1, count(p));
  }

  @Test
  void negativeHealthFoodUsesTheActualTargetAndPreservesTheExistingMinimum() {
    var p =
        stocked(
            "TeamSpecificFoodBenefit",
            "\"UseTargetType\":\"Alive\",\"PreviewHPRecoveryPercent\":-0.99",
            2);
    var a = p.actor(A, 5000, 1000);
    assertNotNull(p.inventory.useItem(FOOD, 1, A));
    assertEquals(100, a.getCurrentHp(p.getCurrentLineup()));
    assertEquals(1000, a.getCurrentSp(p.getCurrentLineup()));
    assertNull(p.inventory.useItem(FOOD, 1, A));
    assertEquals(1, count(p));
  }

  @Test
  void duplicateRewardIdsAndIntegerOverflowAreCheckedAsOneGiftPlan() {
    var p = stocked("FixedRewardGift", "\"UseParam\":[" + REWARD + "," + REWARD + "]", 2);
    reward(6);
    assertNull(p.inventory.useItem(FOOD, 1, 0));
    assertEquals(2, count(p));
    reward(Integer.MAX_VALUE);
    assertNull(p.inventory.useItem(FOOD, 1, 0));
    assertEquals(2, count(p));
    assertNull(p.getGameplay().getLastItemUse());
  }

  @Test
  void anInterruptedDebitRetainsAReceiptAndNeverAppliesOrRetriesItsEffect() {
    var p = stocked("TeamSpecificFoodBenefit", "\"PreviewPowerPercent\":0.3", 2);
    var a = p.actor(A, 5000, 1000);
    p.failAfterDebit = true;
    assertThrows(IllegalStateException.class, () -> p.inventory.useItem(FOOD, 1, A));
    assertEquals(1, count(p));
    assertEquals(1000, a.getCurrentSp(p.getCurrentLineup()));
    assertEquals("DEBITED", p.getGameplay().getLastItemUse().getPhase());
    p.failAfterDebit = false;
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY,
        new ConsumableUse(p).use(FOOD, 1, A).retcode());
    assertEquals(1, count(p));
  }

  @Test
  void aPartialGrantRecordsActualItemsAndCannotBeRetriedOrReportedAsSuccess() {
    var p = stocked("FixedRewardGift", "\"UseParam\":[" + REWARD + "]", 2);
    reward(3);
    assertTrue(
        p.inventory.addItem(
            new GameItem(GameData.getItemExcelMap().get(GOODS), 2) {
              public void save() {}
            }));
    p.actual =
        new Inventory(p) {
          public GameItem getMaterialByItemId(int id) {
            return p.inventory.getMaterialByItemId(id);
          }

          public boolean consumeForUse(GameItem item, int count, Runnable effect) {
            return p.inventory.consumeForUse(item, count, effect);
          }

          public GrantResult grantItems(ItemParamMap map, boolean hint) {
            var partial = new ItemParamMap();
            partial.add(GOODS, 1);
            return new GrantResult(p.inventory.grantItems(partial, hint).items(), false);
          }
        };
    var result = new ConsumableUse(p).use(FOOD, 1, 0);
    assertEquals(emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR, result.retcode());
    assertEquals(1, result.consumed());
    assertEquals(1, result.items().getFirst().getCount());
    assertEquals("PARTIAL", p.getGameplay().getLastItemUse().getPhase());
    assertEquals(Map.of(GOODS, 3), p.getGameplay().getLastItemUse().getRewards());
    assertEquals(Map.of(GOODS, 1), p.getGameplay().getLastItemUse().getReceived());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY,
        new ConsumableUse(p).use(FOOD, 1, 0).retcode());
    assertEquals(1, count(p));
    assertEquals(3, p.inventory.getMaterialByItemId(GOODS).getCount());
  }

  @Test
  void aReservedGiftRoundTripsThroughMorphiaAndAnOldPlayerHasNoPhantomReceipt() throws Exception {
    var p = stocked("FixedRewardGift", "\"UseParam\":[" + REWARD + "]", 2);
    p.getGameplay().reserveItemUse(FOOD, 1, 0, Map.of(GOODS, 3));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "itemuse");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var persisted = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(persisted, 19993107);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(persisted, p.getGameplay());
      store.save(persisted);
      var restored = store.find(Player.class).first();
      gameplay.set(p, restored.getGameplay());
      assertEquals(Map.of(GOODS, 3), p.getGameplay().getLastItemUse().getRewards());
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY,
          new ConsumableUse(p).use(FOOD, 1, 0).retcode());
      assertEquals(2, count(p));
      assertNull(new Player().getGameplay().getLastItemUse());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void aRealUsableGiftIsStoredAsAStackAndItsUseReturnsOnlyTheRewardDelta() {
    var p = stocked("FixedRewardGift", "\"UseParam\":[" + REWARD + "]", 1);
    reward(3);
    var real =
        gson.fromJson(
            "{\"ID\":"
                + FOOD
                + ",\"ItemMainType\":\"Usable\",\"ItemSubType\":\"Gift\","
                + "\"PileLimit\":10,\"UseMethod\":\"FixedRewardGift\"}",
            ItemExcel.class);
    var gift =
        new GameItem(real, 2) {
          public void save() {}
        };
    var recipient = new Consumer();
    assertTrue(recipient.inventory.addItem(gift));
    assertEquals(2, recipient.inventory.getMaterialByItemId(FOOD).getCount());
    assertTrue(
        recipient.inventory.addItem(
            new GameItem(GameData.getItemExcelMap().get(GOODS), 2) {
              public void save() {}
            }));
    var result = recipient.inventory.useItem(FOOD, 1, 0);
    assertNotNull(result);
    assertEquals(3, result.getFirst().getCount());
    assertEquals(5, recipient.inventory.getMaterialByItemId(GOODS).getCount());
    assertEquals(1, recipient.inventory.getMaterialByItemId(FOOD).getCount());
    var invalid = new Consumer();
    var malformed =
        new GameItem(real, 1) {
          public void save() {}
        };
    malformed.setCount(11);
    assertFalse(invalid.inventory.addItem(malformed));
    assertNull(invalid.inventory.getMaterialByItemId(FOOD));
  }
}
