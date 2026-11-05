package org.hkrpg.server.protocol;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;

/** The pinned native live consumer updates quests in place and emits status-change event 0x648. */
public final class QuestSync46 {
  public static boolean sync(Player player) {
    synchronized (player) {
      var session = player.getSession();
      if (session == null || !session.getProtocol().experimental()) return false;
      if (session.getState() != SessionState.ACTIVE) return true;
      var protocol = session.getProtocol();
      var notify = Gameplay46.builder(session, "PlayerSyncScNotify");
      for (var state : player.getQuestManager().states()) {
        var bytes = protocol.translate("Quest", state.toProto().toByteArray(), true);
        try {
          Gameplay46.add(notify, "quest_list", protocol.clientSchema().parse("Quest", bytes));
        } catch (IOException invalidInternalQuest) {
          throw new IllegalStateException("Cannot serialize a saved quest update", invalidInternalQuest);
        }
      }
      // A query reply clears the client cache and emits only 0x647; it is not a live update.
      session.sendClientPacket("PlayerSyncScNotify", notify.build().toByteArray());
      return true;
    }
  }

  private QuestSync46() {}
}
