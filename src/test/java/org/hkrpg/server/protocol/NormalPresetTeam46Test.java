package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.AvatarUpgrade46Test.scalar;
import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.server.packet.Retcode;
import java.util.List;
import org.junit.jupiter.api.*;

/** A saved normal team can be edited without activating it, then selected and restored. */
class NormalPresetTeam46Test {
  final FormalLineup46Test fixture = new FormalLineup46Test();

  @BeforeEach
  void prepare() {
    fixture.prepare();
    fixture.session.actor.getLineupManager().getLineups()[1] =
        new PlayerLineup(fixture.session.actor, 1) {
          @Override public void save() { fixture.session.actor.lineupSaves++; }
        };
  }

  @AfterEach
  void cleanup() { fixture.cleanup(); }

  int switchTo(byte[] request) throws Exception {
    assertTrue(VersionedPacketHandler.handle(fixture.session, 764, request));
    assertEquals("SwitchLineupIndexScRsp", fixture.session.responseName);
    return (Integer) Gameplay46.value(fixture.session.response, "retcode");
  }

  @Test
  void editAnInactiveTeamThenSwitchBetweenBothTeams() throws Exception {
    var req = fixture.request(1209, MissionRaid46Test.AVATAR);
    Gameplay46.set(req, "index", 1);
    Gameplay46.set(req, "leader_slot", 1);
    assertEquals(0, fixture.code(req));
    assertEquals(List.of(MissionRaid46Test.AVATAR), fixture.session.actor.getCurrentLineup().getAvatars());
    assertEquals(0, fixture.session.actor.sceneSyncs);
    assertEquals(List.of(1209, MissionRaid46Test.AVATAR), fixture.session.actor.teams.getLineups()[1].getAvatars());
    assertEquals(0, switchTo(scalar(8, 1)));
    assertEquals(1, fixture.session.actor.teams.getCurrentIndex());
    assertEquals(1, fixture.session.actor.getCurrentLineup().getLeader());
    assertEquals(0, switchTo(scalar(8, 1)), "same-team retry is idempotent");
    assertEquals(0, switchTo(new byte[0]));
    assertEquals(0, fixture.session.actor.teams.getCurrentIndex());
    assertEquals(List.of(MissionRaid46Test.AVATAR), fixture.session.actor.getCurrentLineup().getAvatars());
  }

  @Test
  void emptyInvalidAndBattleSwitchesReturnErrorsWithoutChangingTheActiveTeam() throws Exception {
    assertNotEquals(0, switchTo(scalar(8, 1)));
    assertNotEquals(0, switchTo(scalar(8, 9)));
    assertNotEquals(0, switchTo(org.hkrpg.server.protocol.AvatarUpgrade46Test.join(scalar(8, 0), scalar(8, 1))));
    fixture.session.actor.teams.getLineups()[1].getAvatars().add(1209);
    fixture.session.actor.battle = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), switchTo(scalar(8, 1)));
    assertEquals(0, fixture.session.actor.teams.getCurrentIndex());
  }

  @Test
  void anInactiveTeamCanBeClearedButTheCurrentTeamCannot() throws Exception {
    fixture.session.actor.teams.getLineups()[1].getAvatars().add(1209);
    var req = fixture.request();
    Gameplay46.set(req, "index", 1);
    assertEquals(0, fixture.code(req));
    assertTrue(fixture.session.actor.teams.getLineups()[1].getAvatars().isEmpty());
    assertNotEquals(0, fixture.code(fixture.request()));
    assertEquals(List.of(MissionRaid46Test.AVATAR), fixture.session.actor.getCurrentLineup().getAvatars());
  }

  @Test
  void aFailedSaveDoesNotLeaveTheClientWaitingOrSwitchTheInMemoryTeam() throws Exception {
    fixture.session.actor.teams.getLineups()[1].getAvatars().add(1209);
    fixture.session.actor.failPlayerSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), switchTo(scalar(8, 1)));
    assertEquals(0, fixture.session.actor.teams.getCurrentIndex());
    assertEquals(0, fixture.session.actor.sceneSyncs);
    assertEquals(List.of(MissionRaid46Test.AVATAR), fixture.session.actor.getCurrentLineup().getAvatars());
  }
}
