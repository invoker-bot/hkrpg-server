package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.server.packet.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DiceUpgrade46Test {
  @TempDir Path root;
  final DiceCombat46Test fixture = new DiceCombat46Test();
  static final int DUST = 264998;

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    fixture.oldItems.put(DUST, GameData.getItemExcelMap().get(DUST));
    GameData.getItemExcelMap()
        .add(
            new Gson()
                .fromJson(
                    "{\"ID\":264998,\"ItemMainType\":\"Material\",\"PileLimit\":99999}",
                    ItemExcel.class));
    try (var in = getClass().getResourceAsStream("/gameplay/activity-dice-const-4.6.json")) {
      assertNotNull(in);
      Files.write(root.resolve("ExcelOutput/ActivityDiceConstValueCommo.json"), in.readAllBytes());
    }
    card(3);
  }

  void card(int rarity) throws Exception {
    fixture.table(
        "ActivityDiceAvatarConfig",
        "[{\"DiceAvatarID\":264001,\"Rare\":"
            + rarity
            + ",\"DiceIDPerRare\":[264101,0,0,0],\"DiceCountPerRare\":[2,0,0,0]}]");
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.restore();
  }

  DiceCombat46Test.Session owned(int dust) {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, DiceCombat46Test.AVATAR, 1);
    if (dust > 0) fixture.stock(s, DUST, dust);
    s.player
        .getGameplay()
        .getDiceCombat()
        .getAvatars()
        .put(
            DiceCombat46Test.AVATAR,
            new GameplayState.DiceAvatar(
                DiceCombat46Test.AVATAR,
                1790000000L,
                List.of(DiceCombat46Test.DICE, DiceCombat46Test.DICE)));
    return s;
  }

  DynamicMessage upgrade(DiceCombat46Test.Session s, int id) throws Exception {
    var req = Gameplay46.builder(s, "DiceCombatUpgradeAvatarCsReq");
    Gameplay46.set(req, "KOEOEHIMOEI", id);
    assertTrue(
        VersionedPacketHandler.handle(
            s,
            s.getProtocol().clientCommand("DiceCombatUpgradeAvatarCsReq"),
            req.build().toByteArray()),
        "Upgrade must be dispatched");
    assertEquals("UpgradeAvatarRsp", s.responseName);
    return s.response;
  }

  int level(DiceCombat46Test.Session s) {
    return s.player
        .getGameplay()
        .getDiceCombat()
        .getAvatars()
        .get(DiceCombat46Test.AVATAR)
        .getLevel();
  }

  int dust(DiceCombat46Test.Session s) {
    var item = s.player.getInventory().getMaterialByItemId(DUST);
    return item == null ? 0 : item.getCount();
  }

  @Test
  void aRealUpgradeDebitsSixtyDustAndReturnsLevelTwoWithRaritySlots() throws Exception {
    var s = owned(1000);
    var m = upgrade(s, DiceCombat46Test.AVATAR);
    assertEquals(0, Gameplay46.number(m, "retcode"));
    assertEquals(940, dust(s));
    assertEquals(2, level(s));
    var a = (DynamicMessage) Gameplay46.value(m, "avatar");
    assertEquals(List.of(264101, 0, 0, 0), Gameplay46.value(a, "dice_id_list"));
    assertEquals(1790000000L, Gameplay46.value(a, "unlock_time"));
    assertEquals(2, Gameplay46.number(a, "level"));
  }

  @Test
  void rarityThreeCanUpgradeAgainForOneHundredEightyAndStopsAtThree() throws Exception {
    var s = owned(1000);
    assertEquals(0, Gameplay46.number(upgrade(s, DiceCombat46Test.AVATAR), "retcode"));
    assertEquals(0, Gameplay46.number(upgrade(s, DiceCombat46Test.AVATAR), "retcode"));
    assertEquals(760, dust(s));
    assertEquals(3, level(s));
    assertNotEquals(0, Gameplay46.number(upgrade(s, DiceCombat46Test.AVATAR), "retcode"));
    assertEquals(760, dust(s));
  }

  @Test
  void rarityOneAndTwoStopAtLevelTwo() throws Exception {
    for (int rare : List.of(1, 2)) {
      card(rare);
      var s = owned(240);
      assertEquals(0, Gameplay46.number(upgrade(s, DiceCombat46Test.AVATAR), "retcode"));
      assertNotEquals(0, Gameplay46.number(upgrade(s, DiceCombat46Test.AVATAR), "retcode"));
      assertEquals(2, level(s));
      assertEquals(180, dust(s));
    }
  }

  @Test
  void insufficientDustDoesNotSaveProgressOrReserveADebit() throws Exception {
    var s = owned(59);
    assertEquals(
        Retcode.ITEM_NOT_ENOUGH.getVal(),
        Gameplay46.number(upgrade(s, DiceCombat46Test.AVATAR), "retcode"));
    assertEquals(59, dust(s));
    assertEquals(1, level(s));
    assertEquals(0, s.saves);
  }

  @Test
  void anUnownedAvatarCannotBeManufacturedByUpgrade() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, DUST, 1000);
    assertNotEquals(0, Gameplay46.number(upgrade(s, DiceCombat46Test.AVATAR), "retcode"));
    assertTrue(s.player.getGameplay().getDiceCombat().getAvatars().isEmpty());
    assertEquals(1000, dust(s));
    assertEquals(0, s.saves);
  }

  @Test
  void closedActivityRejectsWithoutPayment() throws Exception {
    var s = owned(1000);
    GameplayDefinitions.windows.clear();
    assertNotEquals(0, Gameplay46.number(upgrade(s, DiceCombat46Test.AVATAR), "retcode"));
    assertEquals(1000, dust(s));
    assertEquals(0, s.saves);
  }

  @Test
  void unverifiedResourceIdentityCannotUseTheRecoveredPriceMapping() throws Exception {
    var s = owned(1000);
    Files.writeString(
        root.resolve("ExcelOutput/ActivityDiceConstValueCommo.json"),
        " ",
        StandardOpenOption.APPEND);
    assertEquals(
        Retcode.REWARD_CONFIG_NOT_EXIST.getVal(),
        Gameplay46.number(upgrade(s, DiceCombat46Test.AVATAR), "retcode"));
    assertEquals(1000, dust(s));
    assertEquals(0, s.saves);
  }

  @Test
  void unknownFieldsDuplicateScalarAndWideIntegersAreRejectedBeforeDebit() throws Exception {
    var s = owned(1000);
    for (byte[] b :
        List.of(
            new byte[] {8, 1},
            new byte[] {104, 1, 104, 1},
            new byte[] {104, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 31},
            new byte[] {106, 0},
            new byte[4097])) {
      assertTrue(VersionedPacketHandler.handle(s, 9292, b));
      assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
      assertEquals(1000, dust(s));
      assertEquals(0, s.saves);
    }
  }

  @Test
  void eightConcurrentRarityOneUpgradesOnlyPayForTheOneAvailableLevel() throws Exception {
    card(1);
    var s = owned(1000);
    var req = Gameplay46.builder(s, "DiceCombatUpgradeAvatarCsReq");
    Gameplay46.set(req, "KOEOEHIMOEI", DiceCombat46Test.AVATAR);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 8; i++)
        tasks.add(
            pool.submit(() -> VersionedPacketHandler.handle(s, 9292, req.build().toByteArray())));
      for (var f : tasks) assertTrue(f.get());
    }
    assertEquals(940, dust(s));
    assertEquals(2, level(s));
  }

  @Test
  void inactiveSessionDoesNotAcknowledgeOrUpgrade() throws Exception {
    var s = owned(1000);
    s.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(s, 9292, new byte[0]));
    assertNull(s.response);
    assertEquals(1000, dust(s));
    assertEquals(1, level(s));
  }

  @Test
  void anInventoryWriteInterruptionRetainsTheReservationAndCannotDebitAgain() throws Exception {
    var s = owned(0);
    var item =
        new GameItem(GameData.getItemExcelMap().get(DUST), 1000) {
          public void save() {
            if (getCount() < 1000) throw new IllegalStateException("inventory write interrupted");
          }
        };
    assertTrue(s.player.getInventory().addItem(item));
    assertThrows(IllegalStateException.class, () -> upgrade(s, DiceCombat46Test.AVATAR));
    assertEquals(1000, dust(s), "A rejected item write must preserve the owned dust");
    assertEquals(1, level(s));
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(),
        Gameplay46.number(upgrade(s, DiceCombat46Test.AVATAR), "retcode"));
    assertEquals(1000, dust(s));
  }

  @Test
  void actualBattleRejectsAnUpgradeBeforeAnyReservation() throws Exception {
    var s = owned(1000);
    var battle =
        new emu.lunarcore.game.battle.Battle(
            s.player, null, (emu.lunarcore.game.battle.BattleStage) null, false);
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("battle");
    field.setAccessible(true);
    field.set(s.player, battle);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(upgrade(s, 264001), "retcode"));
    assertEquals(1000, dust(s));
    assertEquals(0, s.saves);
  }

  @Test
  void aProgressRecordWithoutItsActualInventoryCardCannotUpgrade() throws Exception {
    var s = owned(1000);
    s.player.getInventory().getMaterialByItemId(264001).setCount(0);
    assertNotEquals(0, Gameplay46.number(upgrade(s, 264001), "retcode"));
    assertEquals(1000, dust(s));
    assertEquals(0, s.saves);
  }

  @Test
  void reservationSaveFailureStopsBeforeInventoryWritesAndBlocksRetry() throws Exception {
    var s = owned(1000);
    s.interruptUpgradeReservation = true;
    assertThrows(IllegalStateException.class, () -> upgrade(s, 264001));
    assertEquals(1000, dust(s));
    assertEquals(1, level(s));
    assertEquals(1, s.player.getGameplay().getDiceCombat().getUpgrades().size());
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(upgrade(s, 264001), "retcode"));
    assertEquals(1000, dust(s));
  }

  @Test
  void completionSaveFailureDoesNotPublishTheUncommittedLevelOrDebitAgain() throws Exception {
    var s = owned(1000);
    s.interruptUpgradeCompletion = true;
    assertThrows(IllegalStateException.class, () -> upgrade(s, 264001));
    assertNull(s.response);
    assertEquals(940, dust(s));
    assertEquals(1, level(s));
    assertFalse(s.player.getGameplay().getDiceCombat().getUpgrades().getFirst().isPaid());
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(upgrade(s, 264001), "retcode"));
    assertEquals(940, dust(s));
  }

  @Test
  void corruptedFrozenPriceOrOwnershipTimeCannotBeUsedToJustifyALevel() throws Exception {
    for (String fieldName : List.of("amount", "acquisitionTime")) {
      var s = owned(1000);
      upgrade(s, 264001);
      var receipt = s.player.getGameplay().getDiceCombat().getUpgrades().getFirst();
      var f = receipt.getClass().getDeclaredField(fieldName);
      f.setAccessible(true);
      if (fieldName.equals("amount")) f.setInt(receipt, 1);
      else f.setLong(receipt, receipt.getAcquisitionTime() + 1);
      assertEquals(
          Retcode.PLAYER_DATA_ERROR.getVal(), Gameplay46.number(upgrade(s, 264001), "retcode"));
      assertEquals(940, dust(s));
      assertEquals(2, level(s));
    }
  }

  @Test
  void paidAndPendingUpgradeReceiptsActuallySurviveTwoMorphiaLoads() throws Exception {
    var s = owned(1000);
    upgrade(s, 264001);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var a = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + a.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "dice-upgrade");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var doc = new emu.lunarcore.game.player.Player();
      var uid = doc.getClass().getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(doc, 123456786);
      var state = doc.getClass().getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(doc, s.player.getGameplay());
      store.save(doc);
      state.set(s.player, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      assertEquals(2, level(s));
      assertEquals(60, s.player.getGameplay().getDiceCombat().getUpgrades().getFirst().getAmount());
      s.interruptUpgradeCompletion = true;
      assertThrows(IllegalStateException.class, () -> upgrade(s, 264001));
      state.set(doc, s.player.getGameplay());
      store.save(doc);
      state.set(s.player, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      assertEquals(2, level(s));
      assertEquals(180, s.player.getGameplay().getDiceCombat().getUpgrades().getLast().getAmount());
      assertEquals(Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(upgrade(s, 264001), "retcode"));
      assertEquals(760, dust(s));
    } finally {
      mongo.shutdownNow();
    }
  }
}
