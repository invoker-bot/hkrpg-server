package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.server.packet.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DiceModify46Test {
  @TempDir Path root;
  final DiceCombat46Test fixture = new DiceCombat46Test();
  static final int PERMIT = 150093, DEFAULT = 264518, OTHER = 264403;
  static final List<Integer> PHYSICAL = List.of(264101, 264101, 264201, 264201, 264301, 264518);

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    try (var in = getClass().getResourceAsStream("/gameplay/activity-dice-const-4.6.json")) {
      Files.write(root.resolve("ExcelOutput/ActivityDiceConstValueCommo.json"), in.readAllBytes());
    }
    fixture.table(
        "ActivityDiceAvatarConfig",
        "[{\"DiceAvatarID\":264001,\"Rare\":3,\"DiceIDPerRare\":[264101,264201,264301,264518],\"DiceCountPerRare\":[2,2,1,1]}]");
    fixture.table(
        "ActivityDiceConfig",
        "[{\"DKFDAEFMFHJ\":264101,\"ACAALPMLBFL\":\"Blue\"},{\"DKFDAEFMFHJ\":264201,\"ACAALPMLBFL\":\"Purple\"},{\"DKFDAEFMFHJ\":264301,\"ACAALPMLBFL\":\"Oriange\"},{\"DKFDAEFMFHJ\":264518,\"ACAALPMLBFL\":\"Colorful\"},{\"DKFDAEFMFHJ\":264403,\"ACAALPMLBFL\":\"Colorful\"}]");
    for (int id : List.of(PERMIT, 264201, 264301, DEFAULT, OTHER)) {
      fixture.oldItems.put(id, GameData.getItemExcelMap().get(id));
      GameData.getItemExcelMap()
          .add(
              new Gson()
                  .fromJson(
                      "{\"ID\":"
                          + id
                          + ",\"ItemMainType\":\""
                          + (id == PERMIT ? "Mission" : "Material")
                          + "\",\"ItemSubType\":\""
                          + (id == PERMIT ? "Mission" : "DiceCombatDice")
                          + "\",\"PileLimit\":99999}",
                      ItemExcel.class));
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.restore();
  }

  DiceCombat46Test.Session owned(boolean permit, boolean special) {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, 264001, 1);
    if (permit) fixture.stock(s, PERMIT, 1);
    s.player
        .getGameplay()
        .getDiceCombat()
        .getAvatars()
        .put(264001, new GameplayState.DiceAvatar(264001, 1790000000, PHYSICAL));
    if (special) {
      fixture.stock(s, OTHER, 1);
      s.player.getGameplay().getDiceCombat().getDice().put(OTHER, 1790000001L);
    }
    return s;
  }

  DynamicMessage modify(DiceCombat46Test.Session s, int avatar, int die) throws Exception {
    var b = Gameplay46.builder(s, "DiceCombatModifyAvatarDiceCsReq");
    Gameplay46.set(b, "avatar_id", avatar);
    Gameplay46.set(b, "LLPCNBJIMCN", die);
    assertTrue(
        VersionedPacketHandler.handle(s, 9286, b.build().toByteArray()),
        "Modify must be dispatched");
    assertEquals("ModifyAvatarDiceRsp", s.responseName);
    return s.response;
  }

  List<Integer> deck(DiceCombat46Test.Session s) {
    return s.player.getGameplay().getDiceCombat().getAvatars().get(264001).getDice();
  }

  @Test
  void anOwnedColorfulDieReplacesOnlyTheFourthRaritySlotWithoutConsumingItems() throws Exception {
    var s = owned(true, true);
    var m = modify(s, 264001, OTHER);
    assertEquals(0, Gameplay46.number(m, "retcode"));
    assertEquals(List.of(264101, 264101, 264201, 264201, 264301, OTHER), deck(s));
    var a = (DynamicMessage) Gameplay46.value(m, "avatar");
    assertEquals(List.of(264101, 264201, 264301, OTHER), Gameplay46.value(a, "dice_id_list"));
    assertEquals(1790000000L, Gameplay46.value(a, "unlock_time"));
    assertEquals(1, Gameplay46.number(a, "level"));
    assertTrue(s.player.getInventory().hasItem(PERMIT, 1));
    assertTrue(s.player.getInventory().hasItem(OTHER, 1));
    assertEquals(1, s.saves);
  }

  @Test
  void restoringTheAvatarDefaultDoesNotRequireASeparateDefaultDieItem() throws Exception {
    var s = owned(true, true);
    modify(s, 264001, OTHER);
    assertEquals(0, Gameplay46.number(modify(s, 264001, DEFAULT), "retcode"));
    assertEquals(PHYSICAL, deck(s));
    assertFalse(s.player.getInventory().hasItem(DEFAULT, 1));
  }

  @Test
  void withoutTheChangePermissionTheOwnedDieCannotBeEquipped() throws Exception {
    var s = owned(false, true);
    assertEquals(
        Retcode.ITEM_NOT_ENOUGH.getVal(), Gameplay46.number(modify(s, 264001, OTHER), "retcode"));
    assertEquals(PHYSICAL, deck(s));
    assertEquals(0, s.saves);
  }

  @Test
  void anUnownedSpecialOrAnOrdinaryRarityCannotBeEquipped() throws Exception {
    var s = owned(true, false);
    fixture.stock(s, 264201, 1);
    for (int id : List.of(OTHER, 264201, 999999)) {
      assertNotEquals(0, Gameplay46.number(modify(s, 264001, id), "retcode"));
      assertEquals(PHYSICAL, deck(s));
      assertEquals(0, s.saves);
    }
  }

  @Test
  void recordedSpecialWithoutRealInventoryAndInventoryWithoutAcquisitionTimeAreRejected()
      throws Exception {
    var s = owned(true, true);
    s.player.getInventory().getMaterialByItemId(OTHER).setCount(0);
    assertNotEquals(0, Gameplay46.number(modify(s, 264001, OTHER), "retcode"));
    var t = owned(true, true);
    t.player.getGameplay().getDiceCombat().getDice().clear();
    assertNotEquals(0, Gameplay46.number(modify(t, 264001, OTHER), "retcode"));
    assertEquals(0, t.saves);
  }

  @Test
  void anAvatarWithoutTheFourthRarityCannotGainAnExtraSlot() throws Exception {
    fixture.table(
        "ActivityDiceAvatarConfig",
        "[{\"DiceAvatarID\":264001,\"Rare\":1,\"DiceIDPerRare\":[264101,264201,0,0],\"DiceCountPerRare\":[2,2,0,0]}]");
    var s = owned(true, true);
    s.player
        .getGameplay()
        .getDiceCombat()
        .getAvatars()
        .put(
            264001,
            new GameplayState.DiceAvatar(
                264001, 1790000000, List.of(264101, 264101, 264201, 264201)));
    assertNotEquals(0, Gameplay46.number(modify(s, 264001, OTHER), "retcode"));
    assertEquals(4, deck(s).size());
    assertEquals(0, s.saves);
  }

  @Test
  void identicalSelectionIsIdempotentAndConcurrentChangesDoNotMultiplyItems() throws Exception {
    var s = owned(true, true);
    var b = Gameplay46.builder(s, "DiceCombatModifyAvatarDiceCsReq");
    Gameplay46.set(b, "avatar_id", 264001);
    Gameplay46.set(b, "LLPCNBJIMCN", OTHER);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(() -> VersionedPacketHandler.handle(s, 9286, b.build().toByteArray())));
      for (var f : futures) assertTrue(f.get());
    }
    assertEquals(1, s.saves);
    assertEquals(1, s.player.getInventory().getMaterialByItemId(OTHER).getCount());
    assertEquals(1, s.player.getInventory().getMaterialByItemId(PERMIT).getCount());
  }

  @Test
  void closedActivityRejectsChangeAndLeavesEquipmentIntact() throws Exception {
    var s = owned(true, true);
    GameplayDefinitions.windows.clear();
    assertNotEquals(0, Gameplay46.number(modify(s, 264001, OTHER), "retcode"));
    assertEquals(PHYSICAL, deck(s));
    assertEquals(0, s.saves);
  }

  @Test
  void wrongFieldNumbersDuplicateScalarWideIntegerAndUnknownTagsRejectBeforeSave()
      throws Exception {
    var s = owned(true, true);
    for (byte[] b :
        List.of(
            new byte[] {8, 1},
            new byte[] {64, 1, 64, 1},
            new byte[] {64, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 31},
            new byte[] {66, 0},
            new byte[4097])) {
      assertTrue(VersionedPacketHandler.handle(s, 9286, b));
      assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
      assertEquals(PHYSICAL, deck(s));
      assertEquals(0, s.saves);
    }
  }

  @Test
  void inactiveSessionDoesNotSendAResponseOrChangeTheDeck() throws Exception {
    var s = owned(true, true);
    s.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(s, 9286, new byte[0]));
    assertNull(s.response);
    assertEquals(PHYSICAL, deck(s));
  }

  @Test
  void equipmentSaveFailureRestoresTheOldDeckBeforeAcknowledging() throws Exception {
    var s = owned(true, true);
    s.interruptDiceEquipment = true;
    assertThrows(IllegalStateException.class, () -> modify(s, 264001, OTHER));
    assertNull(s.response);
    assertEquals(PHYSICAL, deck(s));
    assertTrue(s.player.getInventory().hasItem(PERMIT, 1));
    assertTrue(s.player.getInventory().hasItem(OTHER, 1));
    assertEquals(0, Gameplay46.number(modify(s, 264001, OTHER), "retcode"));
    assertEquals(OTHER, deck(s).getLast());
  }

  @Test
  void allCopiesOfTheFourthRarityChangeTogether() throws Exception {
    fixture.table(
        "ActivityDiceAvatarConfig",
        "[{\"DiceAvatarID\":264001,\"Rare\":3,\"DiceIDPerRare\":[264101,264201,264301,264518],\"DiceCountPerRare\":[2,2,1,2]}]");
    var s = owned(true, true);
    s.player
        .getGameplay()
        .getDiceCombat()
        .getAvatars()
        .put(
            264001,
            new GameplayState.DiceAvatar(
                264001,
                1790000000,
                List.of(264101, 264101, 264201, 264201, 264301, DEFAULT, DEFAULT)));
    assertEquals(0, Gameplay46.number(modify(s, 264001, OTHER), "retcode"));
    assertEquals(List.of(264101, 264101, 264201, 264201, 264301, OTHER, OTHER), deck(s));
    assertEquals(
        List.of(264101, 264201, 264301, OTHER),
        Gameplay46.value((DynamicMessage) Gameplay46.value(s.response, "avatar"), "dice_id_list"));
  }

  @Test
  void actualBattleCannotChangeItsAvatarDice() throws Exception {
    var s = owned(true, true);
    var battle =
        new emu.lunarcore.game.battle.Battle(
            s.player, null, (emu.lunarcore.game.battle.BattleStage) null, false);
    var f = s.player.getClass().getSuperclass().getDeclaredField("battle");
    f.setAccessible(true);
    f.set(s.player, battle);
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(modify(s, 264001, OTHER), "retcode"));
    assertEquals(PHYSICAL, deck(s));
    assertEquals(0, s.saves);
  }

  @Test
  void unpaidUpgradeReceiptBlocksEquipmentWithoutConsumingThePermission() throws Exception {
    var s = owned(true, true);
    var receipt = new GameplayState.DiceUpgrade(264001, 3, 1, 1790000000, 264998, 60);
    var f = GameplayState.DiceCombat.class.getDeclaredField("upgrades");
    f.setAccessible(true);
    f.set(s.player.getGameplay().getDiceCombat(), new ArrayList<>(List.of(receipt)));
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(modify(s, 264001, OTHER), "retcode"));
    assertEquals(PHYSICAL, deck(s));
    assertTrue(s.player.getInventory().hasItem(PERMIT, 1));
    assertEquals(0, s.saves);
  }

  @Test
  void specialEquipmentAndAcquisitionIdentityActuallySurviveMorphiaReload() throws Exception {
    var s = owned(true, true);
    modify(s, 264001, OTHER);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var a = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + a.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "dice-modify");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var doc = new emu.lunarcore.game.player.Player();
      var uid = doc.getClass().getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(doc, 123456787);
      var state = doc.getClass().getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(doc, s.player.getGameplay());
      store.save(doc);
      state.set(s.player, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      assertEquals(OTHER, deck(s).getLast());
      assertEquals(
          1790000000L, s.player.getGameplay().getDiceCombat().getAvatars().get(264001).getTime());
      assertEquals(0, Gameplay46.number(modify(s, 264001, DEFAULT), "retcode"));
      assertEquals(PHYSICAL, deck(s));
      assertEquals(2, s.saves);
    } finally {
      mongo.shutdownNow();
    }
  }
}
