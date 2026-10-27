package org.hkrpg.server.protocol;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;

/** Select an existing normal team without changing a story or battle-owned lineup. */
public final class LineupSwitch46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 764) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var player = session.getPlayer();
    synchronized (player) {
      Retcode code = Retcode.REQ_PARA_INVALID;
      int index = 0;
      var schema = session.getProtocol().clientSchema();
      var descriptor = schema.message("SwitchLineupIndexCsReq");
      if (Parkour46.wireValid(descriptor, bytes, 0)) {
        var request = schema.parse("SwitchLineupIndexCsReq", bytes);
        index = Gameplay46.number(request, "index");
        if (request.getUnknownFields().asMap().isEmpty() && Gameplay46.only(request, "index")) {
          var manager = player.getLineupManager();
          var teams = manager.getLineups();
          var current = player.getCurrentLineup();
          var g = player.getGameplay();
          if (player.isInBattle() || player.getInstance() != null
              || player.getMissionTrialManager().inside() || player.getRaidTrialManager().inside()
              || current == null || current.isExtraLineup()
              || g.getCurrentMissionRaid() != 0 || g.getTrialRun() != null || g.getRogue().isInside()
              || g.getStoryLines().getCurrentId() != 0 || g.getStoryLines().isTransitionPending()
              || g.getParkourVenue().inside() || g.getParkour().getActiveLevel() != 0
              || g.getRhythm().getActiveLevel() != 0) code = Retcode.SYSTEM_BUSY;
          else if (index >= 0 && index < teams.length && teams[index] != null
              && !teams[index].isExtraLineup() && teams[index].size() > 0 && player.getScene() != null) {
            if (index == manager.getCurrentIndex()) code = Retcode.SUCC;
            else {
              try {
                if (manager.switchLineup(index)) {
                  code = Retcode.SUCC;
                  var selected = manager.getCurrentLineup();
                  if (selected.isActiveSlot(selected.getLeader()))
                    player.getMissionManager().onTeamLeaderChange(selected.getAvatars().get(selected.getLeader()));
                }
              } catch (RuntimeException failure) {
                emu.lunarcore.LunarCore.getLogger().warn("Could not switch to normal team {}", index, failure);
                code = Retcode.SYSTEM_BUSY;
              }
            }
          }
        }
      }
      var response = Gameplay46.builder(session, "SwitchLineupIndexScRsp");
      Gameplay46.set(response, "index", index);
      Gameplay46.set(response, "retcode", code.getVal());
      session.sendClientPacket("SwitchLineupIndexScRsp", response.build().toByteArray());
    }
    return true;
  }

  private LineupSwitch46() {}
}
