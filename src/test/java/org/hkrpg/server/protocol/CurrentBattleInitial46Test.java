package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.proto.SceneBattleInfoOuterClass.SceneBattleInfo;
import emu.lunarcore.server.packet.SessionState;
import org.junit.jupiter.api.Test;

class CurrentBattleInitial46Test {
  @Test void noActiveBattleIsASuccessfulReadWithoutCreatingBattleOrProgress() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    var before = new Gson().toJson(session.player.getGameplay());
    session.request("GetCurBattleInfoCsReq");
    assertEquals("GetCurBattleInfoScRsp", session.responseName);
    assertEquals(0, InitialData46Test.field(session.response, "retcode"),
        "An ordinary initial query with no active battle must not display generic failure");
    assertNull(session.player.getBattle());
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
  }

  @Test void emptySnapshotIncludesTheContainerReadByNativeAdventureModule() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    session.request("GetCurBattleInfoCsReq");
    var field = session.response.getDescriptorForType().findFieldByName("battle_info");
    assertTrue(session.response.hasField(field),
        "CN4.6 AdventureModule dereferences battle_info on a successful response");
    var battle = (DynamicMessage) session.response.getField(field);
    assertTrue(battle.getAllFields().isEmpty(), "No battle, stage, enemy or reward may be invented");
    assertEquals(0, InitialData46Test.field(battle, "battle_id"));
    assertEquals(0, InitialData46Test.field(battle, "stage_id"));
    assertTrue(session.response.getUnknownFields().asMap().isEmpty());
  }

  @Test void existingBattleStillReturnsItsSnapshotAndKeepsItsIdentity() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    var stage = new Gson().fromJson("{\"StageID\":100101}", StageExcel.class);
    var lineup = new emu.lunarcore.game.player.lineup.PlayerLineup(session.player, 0);
    var battle = new Battle(session.player, lineup, stage, false) {
      @Override public SceneBattleInfo toProto() {
        return SceneBattleInfo.newInstance().setBattleId(42).setStageId(100101).setLogicRandomSeed(123);
      }
    };
    session.player.setBattle(battle);
    session.request("GetCurBattleInfoCsReq");
    assertEquals(0, InitialData46Test.field(session.response, "retcode"));
    var info = (DynamicMessage) InitialData46Test.field(session.response, "battle_info");
    assertEquals(42, InitialData46Test.field(info, "battle_id"));
    assertEquals(100101, InitialData46Test.field(info, "stage_id"));
    assertEquals(123, InitialData46Test.field(info, "logic_random_seed"));
    assertSame(battle, session.player.getBattle());
  }

  @Test void unauthenticatedQueryDoesNotExposeBattleState() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    session.setState(SessionState.WAITING_FOR_LOGIN);
    session.request("GetCurBattleInfoCsReq");
    assertNull(session.response);
  }

  @Test void malformedQueryDoesNotBecomeAnEmptySuccessfulSnapshot() {
    var session = new Gameplay46HandlerTest.Session();
    assertThrows(ProtocolException.class, () -> VersionedPacketHandler.handle(session,
        session.getProtocol().clientCommand("GetCurBattleInfoCsReq"), new byte[] {8, 1}));
    assertNull(session.response);
    assertNull(session.player.getBattle());
  }
}
