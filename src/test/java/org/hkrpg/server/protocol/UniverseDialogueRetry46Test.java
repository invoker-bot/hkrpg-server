package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A persisted event receipt acknowledges a UI retry without performing the reward again. */
class UniverseDialogueRetry46Test {
  @TempDir Path root;
  final UniverseDialogueRewards46Test f = new UniverseDialogueRewards46Test();
  @BeforeEach void setup() throws Exception { f.root = root; f.setup(); }
  @AfterEach void cleanup() { f.cleanup(); }

  RogueDialogueRewardState receipt() {
    return RogueDialogue46.active(f.fixture.session.explorer.scene).getFirst().binding().getRewardState();
  }

  int begin(int option, boolean acquired) throws Exception {
    int unique = f.unique();
    assertEquals(0, f.choose(unique, option));
    if (option == 14102 && acquired)
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
          f.fixture.session.explorer.getUniverseManager().selectBlessing(receipt().getBatch(), 612050));
    return unique;
  }

  void reload() throws Exception {
    f.fixture.session.explorer.state = f.fixture.gson.fromJson(f.snapshot(), GameplayState.class);
    f.fixture.reloadScene();
    f.unique();
  }

  int acquire(int batch, int buff) throws Exception {
    var s = f.fixture.session;
    var choice = Gameplay46.builder(s, "BPLNKHGAILK");
    Gameplay46.set(choice, "BAOFIGFFMLN", buff);
    var request = Gameplay46.builder(s, "OFEDNNJIJFL");
    Gameplay46.set(request, "MPLGEEGOAMC", batch);
    Gameplay46.set(request, "DLKIKBIDJDB", choice.build());
    assertTrue(VersionedPacketHandler.handle(s, 5676, request.build().toByteArray()));
    assertEquals("HandleRogueCommonPendingActionScRsp", s.responseName);
    int code = Gameplay46.number(s.response, "retcode");
    assertEquals(batch, Gameplay46.number(s.response, code == 0 ? "queue_position" : "BFNEPPCNOOI"));
    return code;
  }

  @ParameterizedTest @ValueSource(ints = {14101, 14102, 14103})
  void retryingTheSelectedOptionAcknowledgesItsSavedResultWithoutAnotherSaveOrDraw(int option)
      throws Exception {
    int unique = begin(option, false);
    var before = f.snapshot(); int saves = f.fixture.session.explorer.saves;
    for (int i = 0; i < 3; i++) assertEquals(0, f.choose(unique, option));
    assertEquals(before, f.snapshot());
    assertEquals(saves, f.fixture.session.explorer.saves);
    if (option == 14102) {
      assertEquals(List.of(612050), f.run().getOffers());
      assertTrue(f.run().getCleared().isEmpty());
    }
  }

  @ParameterizedTest @ValueSource(ints = {14101, 14102, 14103})
  void reopeningACompletedEventAfterReloadCanFinishTheOriginalChoice(int option) throws Exception {
    int unique = begin(option, true);
    reload();
    var before = f.snapshot(); int saves = f.fixture.session.explorer.saves;
    assertEquals(0, f.choose(unique, option));
    assertEquals(before, f.snapshot());
    assertEquals(saves, f.fixture.session.explorer.saves);
    assertNotEquals(0, f.choose(unique, option == 14101 ? 14103 : 14101));
    assertEquals(before, f.snapshot());
  }

  @Test void retryingTheAcquiredCardAfterReloadAcknowledgesTheExactReceiptWithoutAnotherGrant()
      throws Exception {
    begin(14102, true); int batch = receipt().getBatch();
    reload();
    var before = f.snapshot(); int saves = f.fixture.session.explorer.saves;
    for (int i = 0; i < 3; i++) assertEquals(0, acquire(batch, 612050));
    assertEquals(before, f.snapshot());
    assertEquals(Map.of(612050, 1), f.run().getBlessings());
    assertEquals(saves, f.fixture.session.explorer.saves);
  }

  @Test void confirmingAnOldCardCannotEraseANewerMenuOrDowngradeAnEnhancedBlessing() throws Exception {
    int unique = begin(14102, true), batch = receipt().getBatch();
    f.run().getBlessings().put(612050, 2);
    f.run().offer(List.of(612051));
    var before = f.snapshot();
    assertEquals(0, acquire(batch, 612050));
    assertEquals(0, f.choose(unique, 14102));
    assertEquals(before, f.snapshot());
  }

  @Test void simultaneousRetriesOnlyAcknowledgeTheOneSavedAcquisition() throws Exception {
    begin(14102, true); int batch = receipt().getBatch();
    var before = f.snapshot(); int saves = f.fixture.session.explorer.saves;
    try (var pool = Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<Future<emu.lunarcore.server.packet.Retcode>>();
      for (int i = 0; i < 6; i++) tasks.add(pool.submit(() ->
          f.fixture.session.explorer.getUniverseManager().selectBlessing(batch, 612050)));
      for (var result : tasks) assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, result.get());
    }
    assertEquals(before, f.snapshot());
    assertEquals(saves, f.fixture.session.explorer.saves);
  }

  @ParameterizedTest @ValueSource(strings = {"source", "runId", "leaseKey", "schema", "complete",
      "batch", "blessing", "cleared", "ownership", "collection", "paused", "battle"})
  void invalidOrDetachedReceiptsCannotTurnARejectedAcquisitionIntoSuccess(String kind) throws Exception {
    int unique = begin(14102, true), batch = receipt().getBatch();
    if (Set.of("source", "runId", "leaseKey", "schema", "complete", "batch", "blessing").contains(kind)) {
      var field = RogueDialogueRewardState.class.getDeclaredField(kind); field.setAccessible(true);
      if (field.getType() == String.class) field.set(receipt(), "invalid");
      else if (field.getType() == boolean.class) field.setBoolean(receipt(), false);
      else field.setInt(receipt(), -1);
    } else if (kind.equals("cleared")) f.run().getCleared().clear();
    else if (kind.equals("ownership")) f.run().getBlessings().clear();
    else if (kind.equals("collection")) f.fixture.session.explorer.state.getRogueCollectedBuffs().clear();
    else if (kind.equals("paused")) f.run().setInside(false);
    else {
      var field = emu.lunarcore.game.player.Player.class.getDeclaredField("battle");
      field.setAccessible(true);
      field.set(f.fixture.session.explorer, new emu.lunarcore.game.battle.Battle(
          f.fixture.session.explorer, null, (emu.lunarcore.game.battle.BattleStage) null, false));
    }
    var before = f.snapshot();
    assertNotEquals(0, acquire(batch, 612050));
    assertNotEquals(0, f.choose(unique, 14102));
    assertEquals(before, f.snapshot());
  }

  @Test void anotherCardBatchOrEventCannotBorrowTheCompletedReceipt() throws Exception {
    int unique = begin(14102, true), batch = receipt().getBatch();
    var before = f.snapshot();
    assertNotEquals(0, acquire(batch + 1, 612050));
    assertNotEquals(0, acquire(batch, 612051));
    assertNotEquals(0, f.choose(unique + 1, 14102));
    assertNotEquals(0, f.choose(unique, 14101));
    assertEquals(before, f.snapshot());
  }
}
