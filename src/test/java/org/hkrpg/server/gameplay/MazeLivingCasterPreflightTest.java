package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Both wire paths must reject a dead/invalid caster before any point or skill effect is applied.
 */
class MazeLivingCasterPreflightTest {
  @TempDir Path root;
  final MazeCastPreflightTest base = new MazeCastPreflightTest();

  @BeforeEach
  void setup() throws Exception {
    base.root = root;
    base.setup();
  }

  @AfterEach
  void cleanup() throws Exception {
    base.cleanup();
  }

  @ParameterizedTest
  @CsvSource({
    "false,0,0",
    "true,0,0",
    "false,1,0",
    "true,1,0",
    "false,0,-1",
    "true,0,-1",
    "false,1,-1",
    "true,1,-1"
  })
  void invalidLifeCannotCastOrConsumeAnySkillPoint(boolean nativePath, int index, int hp)
      throws Exception {
    var player = base.fixture.started(false);
    var caster = player.getCurrentLeaderAvatar();
    caster.setCurrentHp(player.getCurrentLineup(), hp);
    int normalized = Math.max(0, hp);
    assertEquals(normalized, caster.getCurrentHp(player.getCurrentLineup()));
    int mp = player.getCurrentLineup().getMp();
    var capture = base.cast(player, nativePath, index, caster.getEntityId());
    base.rejected(capture, nativePath, Retcode.REQ_PARA_INVALID);
    assertEquals(mp, player.getCurrentLineup().getMp());
    assertEquals(normalized, caster.getCurrentHp(player.getCurrentLineup()));
  }

  @ParameterizedTest
  @CsvSource({
    "false,0,1",
    "true,0,1",
    "false,1,1",
    "true,1,1",
    "false,0,10000",
    "true,0,10000",
    "false,1,10000",
    "true,1,10000",
    "false,0,10001",
    "true,0,10001",
    "false,1,10001",
    "true,1,10001"
  })
  void livingCasterStillUsesTheExistingBasicAndTechniquePaths(boolean nativePath, int index, int hp)
      throws Exception {
    var player = base.fixture.started(false);
    var caster = player.getCurrentLeaderAvatar();
    caster.setCurrentHp(player.getCurrentLineup(), hp);
    assertEquals(Math.min(10000, hp), caster.getCurrentHp(player.getCurrentLineup()));
    int mp = player.getCurrentLineup().getMp();
    var capture = base.cast(player, nativePath, index, caster.getEntityId());
    assertEquals(index, base.fixture.casts);
    assertEquals(mp - index, player.getCurrentLineup().getMp());
    assertEquals(nativePath, capture.attackAttempted);
    assertEquals(
        index == 1,
        capture.packets.stream()
            .anyMatch(p -> p.getCmdId() == CmdId.SceneCastSkillMpUpdateScNotify));
  }
}
