package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Persistence failures occur before progress or delivery; the same bound result can retry. */
class BoxingClubSettlementRetry46Test {
  @TempDir Path root;
  final BoxingClubRounds46Test fixture = new BoxingClubRounds46Test();

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3})
  void anUncommittedResultCanRetryOnTheSameBattleAfterSavingRecovers(int previousWins)
      throws Exception {
    fixture.match(1, 1001);
    for (int i = 0; i < previousWins; i++) {
      var battle = fixture.start();
      assertSame(battle, fixture.finish(battle, BattleEndStatus.BATTLE_END_WIN, 1));
    }
    var current = fixture.start();
    var before = fixture.session.actor.getGameplay().getBoxingClub();
    int saves = fixture.session.actor.saves;
    fixture.session.actor.failSave = true;
    assertThrows(
        IllegalStateException.class,
        () -> fixture.finish(current, BattleEndStatus.BATTLE_END_WIN, 1));
    assertSame(before, fixture.session.actor.getGameplay().getBoxingClub());
    assertSame(current, fixture.session.actor.getBattle());
    assertEquals(previousWins, before.getActive().index());
    assertEquals(saves, fixture.session.actor.saves);
    assertEquals(0, fixture.session.actor.grants);
    fixture.session.actor.failSave = false;
    assertSame(
        current,
        fixture.finish(current, BattleEndStatus.BATTLE_END_WIN, 1),
        "Uncommitted boxing settlement remains locked");
    assertNull(fixture.session.actor.getBattle());
    assertEquals(previousWins == 3 ? 1 : 0, fixture.session.actor.grants);
    assertNull(fixture.finish(current, BattleEndStatus.BATTLE_END_WIN, 1));
  }
}
