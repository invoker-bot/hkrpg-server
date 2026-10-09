package org.hkrpg.server.gameplay;

import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.challenge.ChallengeInstance;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;

public final class ChallengeLeave {
  public static Retcode leave(Player player) {
    synchronized (player) {
      var scene = player.getScene();
      if (scene == null
          || scene.getPlaneType() != PlaneType.Challenge
          || !(player.getInstance() instanceof ChallengeInstance challenge)
          || challenge.getExcel() == null) return Retcode.CHALLENGE_NOT_DOING;
      var entrance = GameData.getMapEntranceExcelMap().get(challenge.getExcel().getMapEntranceID());
      if (entrance == null
          || player.getEntryId() != entrance.getId()
          || player.getPlaneId() != entrance.getPlaneID()
          || player.getFloorId() != entrance.getFloorID()) return Retcode.CHALLENGE_NOT_DOING;

      player.forceQuitBattle();
      int exit =
          scene.getLeaveEntryId() == 0 ? GameConstants.CHALLENGE_ENTRANCE : scene.getLeaveEntryId();
      int previousExtra = player.getCurrentLineup().getExtraLineupType();
      player.getLineupManager().setCurrentExtraLineup(0, false);
      if (!player.enterScene(exit, 0, true)) {
        player.getLineupManager().setCurrentExtraLineup(previousExtra, false);
        return Retcode.FAIL;
      }
      player.setInstance(null);
      return Retcode.SUCC;
    }
  }

  private ChallengeLeave() {}
}
