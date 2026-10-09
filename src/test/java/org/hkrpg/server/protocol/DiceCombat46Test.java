package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DiceCombat46Test {
  @TempDir Path root;
  Object oldConfig;
  java.lang.reflect.Field configField;
  List<GameplayDefinitions.Window> oldWindows;
  Map<Integer, ItemExcel> oldItems = new HashMap<>();
  ActivityPanelExcel oldPanel;
  static final int AVATAR = 264001, DICE = 264101, COIN = 264996, GATE = 804010015;

  @BeforeEach
  void setup() throws Exception {
    configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    oldConfig = configField.get(null);
    var config = new Config();
    config.resourceDir = root.toString();
    configField.set(null, config);
    oldWindows = new ArrayList<>(GameplayDefinitions.windows);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(50073, 5007301, 0, 4102444800L));
    oldPanel = GameData.getActivityPanelExcelMap().get(50073);
    GameData.getActivityPanelExcelMap()
        .add(
            new Gson()
                .fromJson(
                    "{\"PanelID\":50073,\"UnlockConditions\":\"\"}", ActivityPanelExcel.class));
    for (int id : List.of(AVATAR, DICE, COIN)) {
      oldItems.put(id, GameData.getItemExcelMap().get(id));
      GameData.getItemExcelMap()
          .add(
              new Gson()
                  .fromJson(
                      "{\"ID\":" + id + ",\"ItemMainType\":\"Material\",\"PileLimit\":99}",
                      ItemExcel.class));
    }
    Files.createDirectories(root.resolve("ExcelOutput"));
    table(
        "ActivityDiceConstValueCommo",
        "[{\"ConstValueName\":\"Dice_Activity_ID\",\"Value\":{\"IntValue\":50073}}]");
    table(
        "ActivityConfig",
        "[{\"ActivityID\":50073,\"ActivityPanelID\":50073,\"ActivityModuleIDList\":[5007301]}]");
    table(
        "ActivityDiceAvatarConfig",
        "[{\"DiceAvatarID\":264001,\"Rare\":1,\"DiceIDPerRare\":[264101,0],\"DiceCountPerRare\":[2,0]}]");
    table("ActivityDiceConfig", "[{\"DKFDAEFMFHJ\":264101}]");
    table("ActivityDiceShopConfig", "[{\"DiceShopID\":1,\"GoodsList\":[1101,1201,1202,1203]}]");
    table(
        "ActivityDiceShopGoodsConfig",
        """
[{"DiceShopGoodsID":1101,"ItemID":264001,"ItemCost":[{"ItemID":264996,"ItemNum":3}],"UnlockCondition":[]},
 {"DiceShopGoodsID":1201,"ItemID":264101,"ItemCost":[{"ItemID":264996,"ItemNum":2}],"UnlockCondition":[{"Type":"FinishSubMission","Param":"804010015"}]},
 {"DiceShopGoodsID":1202,"ItemID":264101,"ItemCost":[{"ItemID":264996,"ItemNum":2}],"UnlockCondition":[{"Type":"OfferingLevel","Param":"12-5"}]},
 {"DiceShopGoodsID":1203,"ItemID":264101,"ItemCost":[{"ItemID":264996,"ItemNum":2}],"UnlockCondition":[{"Type":"Unknown","Param":"1"}]}]
""");
  }

  void table(String name, String data) throws Exception {
    Files.writeString(root.resolve("ExcelOutput/" + name + ".json"), data);
  }

  @AfterEach
  void restore() throws Exception {
    configField.set(null, oldConfig);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(oldWindows);
    if (oldPanel == null) GameData.getActivityPanelExcelMap().keySet().remove(50073);
    else GameData.getActivityPanelExcelMap().add(oldPanel);
    oldItems.forEach(
        (id, value) -> {
          if (value == null) GameData.getItemExcelMap().keySet().remove((int) id);
          else GameData.getItemExcelMap().add(value);
        });
  }

  static class Session extends GameSession {
    int saves;
    boolean interruptCompletion;
    boolean interruptUpgradeReservation, interruptUpgradeCompletion;
    boolean interruptDiceEquipment;
    boolean interruptPveReservation, interruptPveRewardReservation, interruptPveApplied;
    final Player player =
        new Player() {
          public void save() {
            saves++;
            var pve = getGameplay().getDiceCombat().getPveWins();
            var pveReward = getGameplay().getRewards().get("dice-pve:1001");
            if (!pve.isEmpty()
                && (interruptPveReservation && pveReward == null
                    || interruptPveRewardReservation && pveReward != null && !pveReward.isGranted()
                    || interruptPveApplied
                        && pve.values().stream().anyMatch(GameplayState.DicePveWin::isApplied))) {
              interruptPveReservation = interruptPveRewardReservation = interruptPveApplied = false;
              throw new IllegalStateException("simulated PVE save interruption");
            }
            if (interruptDiceEquipment) {
              interruptDiceEquipment = false;
              throw new IllegalStateException("simulated dice equipment save interruption");
            }
            var upgrades = getGameplay().getDiceCombat().getUpgrades();
            if (!upgrades.isEmpty()
                && (interruptUpgradeReservation && !upgrades.getLast().isPaid()
                    || interruptUpgradeCompletion && upgrades.getLast().isPaid())) {
              interruptUpgradeReservation = false;
              interruptUpgradeCompletion = false;
              throw new IllegalStateException("simulated upgrade save interruption");
            }
            if (interruptCompletion
                && getGameplay().getDiceCombat().getPurchases().values().stream()
                    .anyMatch(GameplayState.DicePurchase::isGranted)) {
              interruptCompletion = false;
              throw new IllegalStateException("simulated completion-save interruption");
            }
          }

          public void sendPacket(BasePacket packet) {}
        };
    String responseName;
    DynamicMessage response;
    final List<String> messages = new ArrayList<>();

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    public void send(BasePacket packet) {}

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        messages.add(name);
        responseName = name;
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (java.io.IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  DynamicMessage invoke(Session s, String name, Map<String, Object> fields) throws Exception {
    var b = Gameplay46.builder(s, name);
    fields.forEach((key, value) -> Gameplay46.set(b, key, value));
    assertTrue(
        VersionedPacketHandler.handle(
            s, s.getProtocol().clientCommand(name), b.build().toByteArray()));
    return s.response;
  }

  int buy(Session s, int shop, int good) throws Exception {
    return Gameplay46.number(
        invoke(s, "BuyShopGoodCsReq", Map.of("KDMNGCLJDOM", good, "MCDNNFACGPE", shop)), "retcode");
  }

  void stock(Session s, int item, int count) {
    assertTrue(
        s.player
            .getInventory()
            .addItem(
                new GameItem(GameData.getItemExcelMap().get(item), count) {
                  public void save() {}
                }));
  }

  @Test
  void successfulAvatarPurchaseDebitsOnceAndReturnsOwnedBaseAvatar() throws Exception {
    var s = new Session();
    stock(s, COIN, 10);
    assertEquals(0, buy(s, 1, 1101));
    assertEquals(7, s.player.getInventory().getMaterialByItemId(COIN).getCount());
    assertTrue(s.player.getInventory().hasItem(AVATAR, 1));
    assertTrue(s.messages.contains("AddItemRsp"));
    assertEquals(Retcode.BUY_TIMES_LIMIT.getVal(), buy(s, 1, 1101));
    assertEquals(7, s.player.getInventory().getMaterialByItemId(COIN).getCount());
    var q = invoke(s, "GetDiceCombatSystemDataCsReq", Map.of());
    var avatars = (List<?>) Gameplay46.value(q, "avatar_list");
    assertEquals(1, avatars.size());
    var avatar = (DynamicMessage) avatars.getFirst();
    assertEquals(AVATAR, Gameplay46.number(avatar, "dice_avatar_id"));
    assertEquals(1, Gameplay46.number(avatar, "level"));
    assertEquals(List.of(DICE, 0), Gameplay46.value(avatar, "dice_id_list"));
    assertTrue((long) Gameplay46.value(avatar, "unlock_time") > 0);
    assertTrue(((List<?>) Gameplay46.value(q, "OHFIIBEIPJE")).isEmpty());
  }

  @Test
  void insufficientFundsDoNotGrantOrReserveAPurchase() throws Exception {
    var s = new Session();
    stock(s, COIN, 2);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), buy(s, 1, 1101));
    assertFalse(s.player.getInventory().hasItem(AVATAR, 1));
    assertEquals(0, s.saves);
    stock(s, COIN, 1);
    assertEquals(0, buy(s, 1, 1101));
  }

  @Test
  void lockedGoodsStayAbsentAndSubMissionCompletionUnlocksOnlyItsGoods() throws Exception {
    var s = new Session();
    stock(s, COIN, 10);
    assertNotEquals(0, buy(s, 1, 1201));
    assertNotEquals(0, buy(s, 1, 1202));
    assertNotEquals(0, buy(s, 1, 1203));
    var done = new emu.lunarcore.game.quest.QuestState(GATE);
    done.advance(1, 1, 1);
    s.player.getGameplay().getSubMissions().put(GATE, done);
    assertEquals(0, buy(s, 1, 1201));
    assertTrue(s.player.getInventory().hasItem(DICE, 1));
    assertNotEquals(0, buy(s, 1, 1202));
    assertNotEquals(0, buy(s, 1, 1203));
    assertEquals(8, s.player.getInventory().getMaterialByItemId(COIN).getCount());
  }

  @Test
  void anInactiveOrWrongModuleWindowDoesNotOpenHistoricalGoods() throws Exception {
    var s = new Session();
    stock(s, COIN, 10);
    GameplayDefinitions.windows.clear();
    assertEquals(Retcode.SHOP_NOT_OPEN.getVal(), buy(s, 1, 1101));
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(50073, 5007302, 0, 4102444800L));
    assertEquals(Retcode.SHOP_NOT_OPEN.getVal(), buy(s, 1, 1101));
    assertEquals(10, s.player.getInventory().getMaterialByItemId(COIN).getCount());
    assertEquals(0, s.saves);
  }

  @Test
  void aGoodCannotBeBoughtFromAnotherShop() throws Exception {
    var s = new Session();
    stock(s, COIN, 10);
    assertNotEquals(0, buy(s, 2, 1101));
    assertEquals(10, s.player.getInventory().getMaterialByItemId(COIN).getCount());
    assertEquals(0, s.saves);
  }

  @Test
  void queriesExposeTheConfiguredPriceAndTheRealPurchasedLimit() throws Exception {
    var s = new Session();
    stock(s, COIN, 10);
    var response = invoke(s, "GetDiceCombatShopDataCsReq", Map.of());
    var shops = (List<?>) Gameplay46.value(response, "OPNFOBJOPCB");
    assertEquals(1, shops.size());
    var rows = (List<?>) Gameplay46.value((DynamicMessage) shops.getFirst(), "CNFFGBMPCHD");
    assertEquals(1, rows.size());
    var good = (DynamicMessage) rows.getFirst();
    assertEquals(1101, Gameplay46.number(good, "KDMNGCLJDOM"));
    assertEquals(false, Gameplay46.value(good, "DOFNEAPHMKH"));
    var cost = (DynamicMessage) Gameplay46.value(good, "cost");
    var entries = (List<?>) Gameplay46.value(cost, "item_list");
    assertEquals(1, entries.size());
    var pile = (DynamicMessage) Gameplay46.value((DynamicMessage) entries.getFirst(), "pile_item");
    assertEquals(COIN, Gameplay46.number(pile, "item_id"));
    assertEquals(3, Gameplay46.number(pile, "item_num"));
    assertEquals(0, s.saves);
    assertEquals(0, buy(s, 1, 1101));
    response = invoke(s, "GetDiceCombatShopDataCsReq", Map.of());
    good =
        (DynamicMessage)
            ((List<?>)
                    Gameplay46.value(
                        (DynamicMessage)
                            ((List<?>) Gameplay46.value(response, "OPNFOBJOPCB")).getFirst(),
                        "CNFFGBMPCHD"))
                .getFirst();
    assertEquals(true, Gameplay46.value(good, "DOFNEAPHMKH"));
  }

  @Test
  void theActivityWindowUsesInclusiveBeginAndExclusiveEnd() {
    var s = new Session();
    stock(s, COIN, 10);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(50073, 5007301, 100, 200));
    var catalog = DiceCombatCatalog.read();
    assertNotNull(catalog);
    assertFalse(catalog.available(s.player, java.time.Instant.ofEpochSecond(99)));
    assertTrue(catalog.available(s.player, java.time.Instant.ofEpochSecond(100)));
    assertTrue(catalog.available(s.player, java.time.Instant.ofEpochSecond(199)));
    assertFalse(catalog.available(s.player, java.time.Instant.ofEpochSecond(200)));
  }

  @Test
  void aCancelledFinishedMissionDoesNotUnlockGoods() throws Exception {
    var s = new Session();
    stock(s, COIN, 10);
    var done = new emu.lunarcore.game.quest.QuestState(GATE);
    done.advance(1, 1, 1);
    s.player.getGameplay().getSubMissions().put(GATE, done);
    s.player.getGameplay().getCancelledSubs().add(GATE);
    assertNotEquals(0, buy(s, 1, 1201));
    assertEquals(10, s.player.getInventory().getMaterialByItemId(COIN).getCount());
  }

  @Test
  void eightConcurrentPurchasesHaveOneDebitAndOneUnlock() throws Exception {
    var s = new Session();
    stock(s, COIN, 10);
    var catalog = DiceCombatCatalog.read();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var futures = new ArrayList<java.util.concurrent.Future<Retcode>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return DiceCombatShop.buy(s.player, catalog, 1, 1101, java.time.Instant.now())
                      .code();
                }));
      start.countDown();
      int success = 0;
      for (var future : futures) {
        var code = future.get();
        if (code == Retcode.SUCC) success++;
        else assertEquals(Retcode.BUY_TIMES_LIMIT, code);
      }
      assertEquals(1, success);
    }
    assertEquals(7, s.player.getInventory().getMaterialByItemId(COIN).getCount());
    assertEquals(1, s.player.getInventory().getMaterialByItemId(AVATAR).getCount());
  }

  @Test
  void aMissingOrDuplicateCatalogCannotExposePartialGoodsOrAcceptPayment() throws Exception {
    var s = new Session();
    stock(s, COIN, 10);
    table(
        "ActivityDiceShopConfig", "[{\"DiceShopID\":1,\"GoodsList\":[1101,1101,1201,1202,1203]}]");
    assertNull(DiceCombatCatalog.read());
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), buy(s, 1, 1101));
    Files.delete(root.resolve("ExcelOutput/ActivityDiceConfig.json"));
    assertEquals(
        Retcode.REWARD_CONFIG_NOT_EXIST.getVal(),
        Gameplay46.number(invoke(s, "GetDiceCombatSystemDataCsReq", Map.of()), "retcode"));
    assertEquals(10, s.player.getInventory().getMaterialByItemId(COIN).getCount());
    assertEquals(0, s.saves);
  }

  @Test
  void malformedOwnedAvatarsCannotBeSilentlyOmittedFromASuccessfulQuery() throws Exception {
    var s = new Session();
    s.player
        .getGameplay()
        .getDiceCombat()
        .getAvatars()
        .put(AVATAR, new GameplayState.DiceAvatar(AVATAR, 10, List.of(999999)));
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        Gameplay46.number(invoke(s, "GetDiceCombatSystemDataCsReq", Map.of()), "retcode"));
    assertEquals(0, s.saves);
  }

  @Test
  void aReceiptWithNoDeliveredUnlockCannotAuthorizeAnotherPurchase() throws Exception {
    var s = new Session();
    stock(s, COIN, 10);
    var receipt = new GameplayState.DicePurchase(1, 1101, AVATAR, 10, Map.of(COIN, 3));
    receipt.complete();
    s.player.getGameplay().getDiceCombat().getPurchases().put(1101, receipt);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), buy(s, 1, 1101));
    assertEquals(10, s.player.getInventory().getMaterialByItemId(COIN).getCount());
  }

  @Test
  void aPendingPurchaseCannotBeRetriedOrDisguisedAsAGrantedUnlock() throws Exception {
    var s = new Session();
    stock(s, COIN, 7);
    s.player
        .getGameplay()
        .getDiceCombat()
        .getPurchases()
        .put(1101, new GameplayState.DicePurchase(1, 1101, AVATAR, 10, Map.of(COIN, 3)));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), buy(s, 1, 1101));
    assertFalse(s.player.getInventory().hasItem(AVATAR, 1));
    assertEquals(7, s.player.getInventory().getMaterialByItemId(COIN).getCount());
    var q = invoke(s, "GetDiceCombatSystemDataCsReq", Map.of());
    assertTrue(((List<?>) Gameplay46.value(q, "avatar_list")).isEmpty());
  }

  @Test
  void interruptionAfterDeliveryDoesNotPermitASecondDebit() throws Exception {
    var s = new Session();
    stock(s, COIN, 10);
    s.interruptCompletion = true;
    assertThrows(IllegalStateException.class, () -> buy(s, 1, 1101));
    assertEquals(7, s.player.getInventory().getMaterialByItemId(COIN).getCount());
    assertEquals(1, s.player.getInventory().getMaterialByItemId(AVATAR).getCount());
    assertEquals(Retcode.BUY_TIMES_LIMIT.getVal(), buy(s, 1, 1101));
    assertEquals(7, s.player.getInventory().getMaterialByItemId(COIN).getCount());
  }

  @Test
  void actualMorphiaLoadsPreserveThePurchaseTheAvatarAndAPendingDebit() throws Exception {
    var s = new Session();
    stock(s, COIN, 10);
    assertEquals(0, buy(s, 1, 1101));
    var owned = s.player.getGameplay().getDiceCombat().getAvatars().get(AVATAR);
    long time = owned.getTime();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "dice-combat");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456788);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.player.getGameplay());
      store.save(document);
      gameplay.set(s.player, store.find(Player.class).first().getGameplay());
      assertEquals(Retcode.BUY_TIMES_LIMIT.getVal(), buy(s, 1, 1101));
      var restored = s.player.getGameplay().getDiceCombat().getAvatars().get(AVATAR);
      assertEquals(time, restored.getTime());
      assertEquals(List.of(DICE, DICE), restored.getDice());
      assertEquals(1, restored.getLevel());
      assertEquals(
          Map.of(COIN, 3),
          s.player.getGameplay().getDiceCombat().getPurchases().get(1101).getCosts());
      s.player
          .getGameplay()
          .getDiceCombat()
          .getPurchases()
          .put(1201, new GameplayState.DicePurchase(1, 1201, DICE, 11, Map.of(COIN, 2)));
      gameplay.set(document, s.player.getGameplay());
      store.save(document);
      gameplay.set(s.player, store.find(Player.class).first().getGameplay());
      var done = new emu.lunarcore.game.quest.QuestState(GATE);
      done.advance(1, 1, 1);
      s.player.getGameplay().getSubMissions().put(GATE, done);
      assertEquals(Retcode.SYSTEM_BUSY.getVal(), buy(s, 1, 1201));
      assertFalse(s.player.getInventory().hasItem(DICE, 1));
      assertEquals(7, s.player.getInventory().getMaterialByItemId(COIN).getCount());
    } finally {
      mongo.shutdownNow();
    }
  }

  void request(String request, int command, String response, byte[] bytes, boolean success)
      throws Exception {
    var s = new Session();
    assertEquals(command, s.getProtocol().clientCommand(request));
    assertTrue(
        VersionedPacketHandler.handle(s, command, bytes),
        "The native DiceCombat request must be dispatched");
    assertEquals(response, s.responseName);
    assertEquals(success, Gameplay46.number(s.response, "retcode") == 0);
    assertEquals(0, s.saves, "A query or rejected purchase cannot create progress");
  }

  @Test
  void systemQueryUsesNativeResponseAndDoesNotGrantCompletedHistory() throws Exception {
    request(
        "GetDiceCombatSystemDataCsReq", 9294, "GetDiceCombatSystemDataScRsp", new byte[0], true);
  }

  @Test
  void systemResponseResolvesToTheNativeCommandInTheActualSender() throws Exception {
    var s = new Session();
    invoke(s, "GetDiceCombatSystemDataCsReq", Map.of());
    assertEquals(9266, s.getProtocol().clientCommand(s.responseName));
  }

  @Test
  void shopResponseResolvesToTheNativeCommandInTheActualSender() throws Exception {
    var s = new Session();
    invoke(s, "GetDiceCombatShopDataCsReq", Map.of());
    assertEquals(9283, s.getProtocol().clientCommand(s.responseName));
  }

  @Test
  void shopQueryUsesNativeResponseAndDoesNotGrantItems() throws Exception {
    request("GetDiceCombatShopDataCsReq", 9279, "GetDiceCombatShopDataScRsp", new byte[0], true);
  }

  @Test
  void anUnknownShopPurchaseIsRejectedThroughTheNativeResponse() throws Exception {
    request("BuyShopGoodCsReq", 9298, "BuyShopGoodScRsp", new byte[] {32, 1, 48, 127}, false);
  }

  @Test
  void unknownQueryFieldsCannotCreateACompletedSnapshot() throws Exception {
    request(
        "GetDiceCombatSystemDataCsReq",
        9294,
        "GetDiceCombatSystemDataScRsp",
        new byte[] {8, 1},
        false);
  }

  @Test
  void unknownShopQueryFieldsAreRejected() throws Exception {
    request(
        "GetDiceCombatShopDataCsReq", 9279, "GetDiceCombatShopDataScRsp", new byte[] {8, 1}, false);
  }

  @Test
  void duplicatePurchaseFieldsAreRejectedBeforePayment() throws Exception {
    request("BuyShopGoodCsReq", 9298, "BuyShopGoodScRsp", new byte[] {32, 1, 32, 2, 48, 1}, false);
  }

  @Test
  void oversizedUnsignedShopIdsCannotWrapToAValidShop() throws Exception {
    request(
        "BuyShopGoodCsReq",
        9298,
        "BuyShopGoodScRsp",
        new byte[] {32, 1, 48, -127, -128, -128, -128, 16},
        false);
  }
}
