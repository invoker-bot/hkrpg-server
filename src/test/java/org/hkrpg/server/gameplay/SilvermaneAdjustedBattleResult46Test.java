package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.OpeningStageWin46Test.field;
import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.proto.PVEBattleResultCsReqOuterClass.PVEBattleResultCsReq;
import emu.lunarcore.server.packet.send.PacketPVEBattleResultScRsp;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The actual grouped difficulty route must retain its event even when world/stage changes. */
class SilvermaneAdjustedBattleResult46Test {
  @TempDir Path temp;
  final GroupedStageEventDifficulty46Test events = new GroupedStageEventDifficulty46Test();

  @BeforeEach void load() throws Exception { events.root = temp; events.prepare(); }
  @AfterEach void restore() throws Exception { events.restore(); }

  @ParameterizedTest
  @CsvSource({"20101011,0,0", "20101012,0,0", "20101011,1,1", "20101012,1,1"})
  void groupedDifficultySettlementEchoesTheActualEvent(int event, int world, int difficulty)
      throws Exception {
    var runtime = events.source.source;
    var actor = runtime.baseline.fixture.player;
    actor.world = world;
    actor.setSession(runtime.session);
    if (world > 0)
      assertEquals(0, RaidDifficultyAdjustment46Test.number(runtime.update(event, 2, difficulty), "retcode"));
    var battle = events.source.enter(event);
    assertInstanceOf(StageEventDifficultyBattle.class, battle);
    assertEquals(event * 10 + world - difficulty, battle.getStage().getId());
    var settled = runtime.service.finishBattle(actor, battle.getId(), battle.getStage().getId(),
        BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance(), List.of(), List.of());
    assertSame(battle, settled);
    var packet = new PacketPVEBattleResultScRsp(PVEBattleResultCsReq.newInstance(), settled);
    var wire = runtime.session.getProtocol().outbound(packet.getCmdId(), packet.getData().toByteArray());
    var reply = runtime.session.getProtocol().clientSchema().parse("PVEBattleResultScRsp", wire.body());
    assertEquals(0, field(reply, "retcode"));
    assertEquals(event, field(reply, "event_id"));
    assertEquals(battle.getStage().getId(), field(reply, "stage_id"));
    assertNull(actor.getBattle());
  }
}
