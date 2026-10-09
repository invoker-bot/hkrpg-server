package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DiceSlot46Test {
  @TempDir Path root;
  final DiceCombat46Test fixture = new DiceCombat46Test();
  static final List<Integer> PHYSICAL = List.of(264101, 264101, 264201, 264201, 264301, 264518),
      SLOTS = List.of(264101, 264201, 264301, 264518);

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    fixture.table(
        "ActivityDiceConfig",
        "[{\"DKFDAEFMFHJ\":264101},{\"DKFDAEFMFHJ\":264201},{\"DKFDAEFMFHJ\":264301},{\"DKFDAEFMFHJ\":264518},{\"DKFDAEFMFHJ\":264403}]");
    fixture.table(
        "ActivityDiceAvatarConfig",
        "[{\"DiceAvatarID\":264001,\"Rare\":3,\"DiceIDPerRare\":[264101,264201,264301,264518],\"DiceCountPerRare\":[2,2,1,1]}]");
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.restore();
  }

  DiceCombat46Test.Session owned(List<Integer> physical) {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, DiceCombat46Test.AVATAR, 1);
    s.player
        .getGameplay()
        .getDiceCombat()
        .getAvatars()
        .put(
            DiceCombat46Test.AVATAR,
            new GameplayState.DiceAvatar(DiceCombat46Test.AVATAR, 1790000000L, physical));
    return s;
  }

  DynamicMessage query(DiceCombat46Test.Session s) throws Exception {
    return fixture.invoke(s, "GetDiceCombatSystemDataCsReq", Map.of());
  }

  List<?> slots(DynamicMessage m) {
    var rows = (List<?>) Gameplay46.value(m, "avatar_list");
    assertEquals(1, rows.size());
    return (List<?>) Gameplay46.value((DynamicMessage) rows.getFirst(), "dice_id_list");
  }

  @Test
  void nativeOwnedAvatarUsesOneIdPerRarityRatherThanRepeatedPhysicalDice() throws Exception {
    var s = owned(PHYSICAL);
    var m = query(s);
    assertEquals(0, Gameplay46.number(m, "retcode"));
    assertEquals(SLOTS, slots(m));
    assertEquals(
        PHYSICAL,
        s.player.getGameplay().getDiceCombat().getAvatars().get(DiceCombat46Test.AVATAR).getDice());
    assertEquals(0, s.saves);
  }

  @Test
  void absentRaritySlotsRemainExplicitZerosInsteadOfShiftingLaterRarities() throws Exception {
    fixture.table(
        "ActivityDiceAvatarConfig",
        "[{\"DiceAvatarID\":264001,\"DiceIDPerRare\":[264101,0,264301,0],\"DiceCountPerRare\":[2,0,1,0]}]");
    var s = owned(List.of(264101, 264101, 264301));
    assertEquals(List.of(264101, 0, 264301, 0), slots(query(s)));
    assertEquals(0, s.saves);
  }

  @Test
  void inconsistentPhysicalDiceInsideOneRarityCannotBePublishedAsValidAvatar() throws Exception {
    var s = owned(List.of(264101, 264201, 264201, 264201, 264301, 264518));
    assertNotEquals(0, Gameplay46.number(query(s), "retcode"));
    assertTrue(((List<?>) Gameplay46.value(s.response, "avatar_list")).isEmpty());
    assertEquals(0, s.saves);
  }

  @Test
  void missingOrExtraPhysicalCopiesRejectTheWholeSnapshot() throws Exception {
    for (var dice :
        List.of(
            PHYSICAL.subList(0, 5),
            List.of(264101, 264101, 264201, 264201, 264301, 264518, 264403))) {
      var s = owned(dice);
      assertNotEquals(0, Gameplay46.number(query(s), "retcode"));
      assertEquals(0, s.saves);
    }
  }

  @Test
  void aChangedSpecialSlotIsProjectedWithoutChangingFrozenAcquisitionDice() throws Exception {
    var s = owned(List.of(264101, 264101, 264201, 264201, 264301, 264403));
    assertEquals(List.of(264101, 264201, 264301, 264403), slots(query(s)));
    assertEquals(0, s.saves);
  }

  @Test
  void sourcePurchaseUsesAuthoredSlotsAndSavesPhysicalMultiplicitySeparately() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, DiceCombat46Test.COIN, 3);
    assertEquals(0, fixture.buy(s, 1, 1101));
    assertEquals(SLOTS, slots(query(s)));
    assertEquals(
        PHYSICAL,
        s.player.getGameplay().getDiceCombat().getAvatars().get(DiceCombat46Test.AVATAR).getDice());
  }

  @Test
  void actualMorphiaRestoreKeepsPhysicalStateAndNativeSlotOrdering() throws Exception {
    var s = owned(PHYSICAL);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var a = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + a.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "dice-slots");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var doc = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(doc, 123456785);
      var state = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(doc, s.player.getGameplay());
      store.save(doc);
      state.set(s.player, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      assertEquals(SLOTS, slots(query(s)));
      assertEquals(
          PHYSICAL,
          s.player
              .getGameplay()
              .getDiceCombat()
              .getAvatars()
              .get(DiceCombat46Test.AVATAR)
              .getDice());
    } finally {
      mongo.shutdownNow();
    }
  }
}
