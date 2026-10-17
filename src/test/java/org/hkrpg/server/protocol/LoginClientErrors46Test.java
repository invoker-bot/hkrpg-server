package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.GetFriendLoginInfoScRspOuterClass.GetFriendLoginInfoScRsp;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.SessionState;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regressions for the installed client's 2026-10-18 initialization errors. */
class LoginClientErrors46Test {
  private static JsonArray catalog(String name) throws Exception {
    try (var input = LoginClientErrors46Test.class.getResourceAsStream(
        "/gameplay/login-initial-defaults-4.6.json")) {
      assertNotNull(input, "Pinned initial catalog rows must travel with the tests");
      return new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8),
          JsonObject.class).getAsJsonArray(name);
    }
  }

  private static DynamicMessage child(DynamicMessage parent, int number) {
    var field = parent.getDescriptorForType().findFieldByNumber(number);
    assertNotNull(field);
    assertTrue(parent.hasField(field), "Native callback requires " + field.getFullName());
    return (DynamicMessage) parent.getField(field);
  }

  private static DynamicMessage query(int command, String type) throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    var before = new Gson().toJson(session.player.getGameplay());
    assertTrue(VersionedPacketHandler.handle(session, command, new byte[0]));
    assertEquals(1, session.nativePackets.size());
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
    return session.bridge.clientSchema().parse(type, session.nativePackets.getFirst().body());
  }

  @Test void rogueTournamentRequiresTheDivisionSnapshotEvenWithoutAnActiveRun() throws Exception {
    // Native _OnCmdRogueTournQueryScRsp e002bda/e002c26 unconditionally reads this child.
    var reply = query(6029, "RogueTournQueryScRsp");
    child(child(reply, 1), 13);
    assertFalse(reply.hasField(reply.getDescriptorForType().findFieldByNumber(5)),
        "No active tournament run is created during login");
    LoginBootstrapNested46Test.noInventedProgress(reply);
  }

  @Test void trainPartyTurnReferenceResolvesToAnActualFirstProgressRow() throws Exception {
    // MKDPFLPICJB.MergeFrom: wire 6 -> +0x30; Sync passes it to _SetCurTurnID.
    var data = child(query(8034, "TrainPartyGetDataScRsp"), 7);
    assertEquals(1, data.getField(data.getDescriptorForType().findFieldByNumber(6)));
    var rows = catalog("TrainPartyProgress");
    assertTrue(rows.asList().stream().anyMatch(row -> row.getAsJsonObject().get("ProgressID").getAsInt() == 1));
    assertEquals(0, InitialData46Test.field(data, "record_id"));
    assertFalse(data.hasField(data.getDescriptorForType().findFieldByNumber(15)));
  }

  @Test void gridFightInitialDivisionAndSeasonReferencesResolveWithoutGrantingProgress() throws Exception {
    var info = child(child(query(8680, "GridFightGetDataScRsp"), 11), 11);
    assertEquals(1, InitialData46Test.field(info, "division_id"));
    assertEquals(1, InitialData46Test.field(info, "season"));
    assertEquals(4, InitialData46Test.field(info, "sub_season"));
    var rows = catalog("GridFightDivisionInfo");
    assertTrue(rows.asList().stream().anyMatch(row -> row.getAsJsonObject().get("ID").getAsInt() == 1
        && row.getAsJsonObject().get("SeasonID").getAsInt() == 1
        && !row.getAsJsonObject().has("DivisionLevel")));
    var constants = catalog("GridFightConstCommon");
    for (var entry : List.of(java.util.Map.entry("GridFight_Season_ID", 1),
        java.util.Map.entry("GridFight_Sub_Season_ID", 4))) {
      assertTrue(constants.asList().stream().map(row -> row.getAsJsonObject()).anyMatch(row ->
          row.get("ConstValueName").getAsString().equals(entry.getKey())
              && row.getAsJsonObject("Value").get("IntValue").getAsInt() == entry.getValue()));
    }
    assertEquals(0, InitialData46Test.field(info, "tutorial_completion"));
    assertEquals(0, InitialData46Test.field(info, "weekly_progress"));
  }

  @Test void aRealNonemptyFriendSnapshotSurvivesTheLeadingUnderscoreRename() throws Exception {
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var core = GetFriendLoginInfoScRsp.newInstance().addFriendUidList(10001).addFriendUidList(10002);
    var packet = bridge.outbound(CmdId.GetFriendLoginInfoScRsp, core.toByteArray());
    var reply = bridge.clientSchema().parse("GetFriendLoginInfoScRsp", packet.body());
    assertEquals(List.of(10001, 10002), InitialData46Test.field(reply, "_friend_uid_list"));
    assertArrayEquals(core.toByteArray(), ProtocolBridge.forVersion("4.2.0")
        .outbound(CmdId.GetFriendLoginInfoScRsp, core.toByteArray()).body());
  }

  @Test void tournamentBootstrapDoesNotExposeDataBeforeAuthentication() throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    session.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(session, 6029, new byte[0]));
    assertTrue(session.nativePackets.isEmpty());
  }
}
