package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.List;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseLineup46Test {
  final UniverseDownload46Test fixture = new UniverseDownload46Test();

  static class NativeSession extends UniverseDownload46Test.Session {
    int command;
    byte[] payload;

    public void sendClientPacket(int command, byte[] payload) {
      this.command = command;
      this.payload = payload;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.setup();
    var owned = fixture.s.explorer.getAvatarById(UniverseDownload46Test.AVATAR);
    fixture.s = new NativeSession();
    owned.setOwner(fixture.s.explorer);
    fixture.s.explorer.getAvatars().getAvatars().put(UniverseDownload46Test.AVATAR, owned);
    fixture.begin();
    assertEquals(
        Retcode.SUCC,
        RogueDownload.pick(
            player(), List.of(UniverseDownload46Test.AVATAR), UniverseDownload46Test.ENTITY));
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
  }

  Player player() {
    return fixture.s.explorer;
  }

  @Test
  void pendingChoiceCannotReplaceTheTeamAndTheManagerReportsFailure() {
    var p = player();
    var run = p.getGameplay().getRogue();
    run.getMiracleOffers().add(4);
    assertFalse(p.getLineupManager().replaceLineup(0, 2, List.of(UniverseDownload46Test.AVATAR)));
    assertEquals(List.of(199961), run.getRoster());
    assertEquals(5000, run.getVitals().get(UniverseDownload46Test.AVATAR).getSp());
  }

  @Test
  void pausedAndTerminalRunsCannotBeChangedThroughACachedLineup() {
    var p = player();
    var run = p.getGameplay().getRogue();
    var lineup = p.getUniverseManager().lineup();
    run.setInside(false);
    lineup.replace(List.of(UniverseDownload46Test.AVATAR));
    assertEquals(List.of(199961), run.getRoster());
    run.setInside(true);
    run.finish(GameplayState.RogueRun.Phase.FAILED);
    lineup.replace(List.of(UniverseDownload46Test.AVATAR));
    assertEquals(List.of(199961), run.getRoster());
  }

  @Test
  void invalidMembersDoNotReportSuccessOrRemoveTheExistingRoster() {
    var p = player();
    for (var ids :
        List.of(
            List.of(199960),
            List.of(UniverseDownload46Test.AVATAR, UniverseDownload46Test.AVATAR),
            List.<Integer>of())) assertFalse(p.getLineupManager().replaceLineup(0, 2, ids));
    assertEquals(List.of(199961), p.getGameplay().getRogue().getRoster());
  }

  @Test
  void deployingADownloadedAvatarPreservesEveryIndependentVitalAndNormalTeam() {
    var p = player();
    var run = p.getGameplay().getRogue();
    run.getVitals().get(199961).setHp(2300);
    run.getVitals().get(UniverseDownload46Test.AVATAR).setSp(900);
    assertTrue(
        p.getLineupManager().replaceLineup(0, 2, List.of(UniverseDownload46Test.AVATAR, 199961)));
    assertEquals(List.of(UniverseDownload46Test.AVATAR, 199961), run.getRoster());
    assertEquals(2300, run.getVitals().get(199961).getHp());
    assertEquals(900, run.getVitals().get(UniverseDownload46Test.AVATAR).getSp());
    assertEquals(List.of(199961), p.getLineupManager().getLineupByIndex(0).getAvatars());
  }

  com.google.protobuf.DynamicMessage.Builder request() {
    var req = Gameplay46.builder(fixture.s, "ReplaceLineupCsReq");
    var type = req.getDescriptorForType().findFieldByName("extra_lineup_type");
    req.setField(type, type.getEnumType().findValueByNumber(2));
    Gameplay46.set(req, "plane_id", player().getPlaneId());
    return req;
  }

  void slot(com.google.protobuf.DynamicMessage.Builder req, int id, int slot, int assist) {
    var row =
        com.google.protobuf.DynamicMessage.newBuilder(
            req.getDescriptorForType().findFieldByName("lineup_slot_list").getMessageType());
    Gameplay46.set(row, "id", id);
    Gameplay46.set(row, "slot", slot);
    Gameplay46.set(row, "assist_uid", assist);
    var type = row.getDescriptorForType().findFieldByName("avatar_type");
    row.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
    Gameplay46.add(req, "lineup_slot_list", row.build());
  }

  int code(com.google.protobuf.DynamicMessage.Builder req) throws Exception {
    assertTrue(VersionedPacketHandler.handle(fixture.s, 704, req.build().toByteArray()));
    var s = (NativeSession) fixture.s;
    assertEquals(719, s.command);
    var wire = com.google.protobuf.UnknownFieldSet.parseFrom(s.payload);
    assertFalse(
        wire.hasField(6), "719 is Replace response, not the simplified Join field6 definition");
    return wire.hasField(9) ? wire.getField(9).getVarintList().getFirst().intValue() : 0;
  }

  @Test
  void nativeReplacementOrdersSlotsAndHonoursLeaderWithoutRefreshingHealth() throws Exception {
    var req = request();
    slot(req, 199961, 1, 0);
    slot(req, UniverseDownload46Test.AVATAR, 0, 0);
    Gameplay46.set(req, "leader_slot", 1);
    player().getGameplay().getRogue().getVitals().get(199961).setHp(2100);
    assertEquals(0, code(req));
    assertEquals(
        List.of(UniverseDownload46Test.AVATAR, 199961),
        player().getGameplay().getRogue().getRoster());
    assertEquals(1, player().getCurrentLineup().getLeader());
    assertEquals(2100, player().getGameplay().getRogue().getVitals().get(199961).getHp());
  }

  @Test
  void nativeInvalidSlotsAssistsAndUnknownContextReturnTheActualReplaceRetcode() throws Exception {
    for (int bad : List.of(0, 1, 2)) {
      var req = request();
      slot(req, UniverseDownload46Test.AVATAR, bad == 0 ? 3 : 0, bad == 1 ? 123 : 0);
      if (bad == 2) Gameplay46.set(req, "game_story_line_id", 12);
      assertNotEquals(0, code(req));
    }
    assertEquals(List.of(199961), player().getGameplay().getRogue().getRoster());
  }

  @Test
  void battleAndOldRunViewsCannotEditTheCurrentExploration() {
    var run = player().getGameplay().getRogue();
    var busy =
        new Player() {
          public GameplayState getGameplay() {
            return player().getGameplay();
          }

          public boolean isInBattle() {
            return true;
          }
        };
    assertEquals(
        Retcode.ROGUE_STATUS_NOT_MATCH,
        new RogueLineup(busy, run).tryReplace(List.of(UniverseDownload46Test.AVATAR), 0));
    var old = player().getUniverseManager().lineup();
    player().getGameplay().setRogue(new GameplayState().getRogue());
    old.replace(List.of(UniverseDownload46Test.AVATAR));
    assertEquals(List.of(199961), run.getRoster());
  }

  @Test
  void theCurrentRosterCanBeResubmittedWithoutAliasingAndTheLeaderFollowsTheAvatar() {
    var run = player().getGameplay().getRogue();
    var lineup = player().getUniverseManager().lineup();
    lineup.replace(run.getRoster());
    assertEquals(List.of(199961), run.getRoster());
    lineup.replace(List.of(UniverseDownload46Test.AVATAR, 199961));
    assertEquals(1, lineup.getLeader());
    assertEquals(5000, run.getVitals().get(UniverseDownload46Test.AVATAR).getSp());
  }

  @Test
  void unsignedLeaderOverflowVirtualFlagsAndUnknownFieldsCannotChangeTheTeam() throws Exception {
    for (int variant : List.of(0, 1, 2)) {
      var req = request();
      slot(req, UniverseDownload46Test.AVATAR, 0, 0);
      if (variant == 0) Gameplay46.set(req, "leader_slot", -1);
      if (variant == 1) Gameplay46.set(req, "_is_virtual", true);
      if (variant == 2)
        req.setUnknownFields(
            com.google.protobuf.UnknownFieldSet.newBuilder()
                .addField(
                    199,
                    com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                .build());
      assertNotEquals(0, code(req));
    }
    assertEquals(List.of(199961), player().getGameplay().getRogue().getRoster());
  }
}
