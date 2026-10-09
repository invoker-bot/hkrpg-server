package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.StoryLineDefinitions;

/** Native story status; unknown trial-change fields are deliberately omitted. */
public final class StoryLine46 {
  public static DynamicMessage info(ProtocolSchema schema, Player player, boolean notify) {
    synchronized (player) {
      var type = schema.message(notify ? "StoryLineInfoScNotify" : "GetStoryLineInfoScRsp");
      var result = DynamicMessage.newBuilder(type);
      var state = player.getGameplay().getStoryLines();
      if (!notify && state.isTransitionPending()) {
        result.setField(type.findFieldByName("retcode"), Retcode.SYSTEM_BUSY.getVal());
        return result.build();
      }
      result.setField(type.findFieldByName("cur_story_line_id"), state.getCurrentId());
      // CN JEEOPLLKEEH: +0x18 uses codec 0x1a and feeds _RefreshStoryLineStatus;
      // +0x10 uses codec 0x5a and feeds SetTrialAvatarIDs. Honey's two names are reversed.
      var unfinished =
          notify
              ? type.findFieldByNumber(3)
              : type.findFieldByName("unfinished_story_line_id_list");
      if (notify) result.addRepeatedField(unfinished, 0);
      state.getRuns().keySet().stream()
          .sorted()
          .filter(id -> StoryLineDefinitions.canEnter(player, id))
          .forEach(id -> result.addRepeatedField(unfinished, id));
      var run = player.getStoryLineManager().current();
      var trials =
          notify ? type.findFieldByNumber(11) : type.findFieldByName("trial_avatar_id_list");
      if (run != null)
        run.getAvailableTrials().stream()
            .sorted()
            .forEach(id -> result.addRepeatedField(trials, id));
      return result.build();
    }
  }

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("GetStoryLineInfoCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    // The pinned 4.6 request has no fields. Reject extra data rather than ignoring an operation.
    if (bytes.length != 0) throw new ProtocolException("Unexpected story query fields");
    session.sendClientPacket(
        "GetStoryLineInfoScRsp",
        info(session.getProtocol().clientSchema(), session.getPlayer(), false).toByteArray());
    return true;
  }

  public static void sync(GameSession session) {
    if (!session.getProtocol().experimental()) return;
    session.sendClientPacket(
        "StoryLineInfoScNotify",
        info(session.getProtocol().clientSchema(), session.getPlayer(), true).toByteArray());
  }

  public static void changing(Player player) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    sync(player.getSession());
    player.sendPacket(
        new emu.lunarcore.server.packet.send.PacketSyncLineupNotify(player.getCurrentLineup()));
  }

  public static void changed(Player player) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    var notify = Gameplay46.builder(player.getSession(), "ChangeStoryLineFinishScNotify");
    int id = player.getGameplay().getStoryLines().getCurrentId();
    Gameplay46.set(notify, "cur_story_line_id", id);
    var action = notify.getDescriptorForType().findFieldByName("action");
    notify.setField(action, action.getEnumType().findValueByNumber(id == 0 ? 0 : 1));
    player
        .getSession()
        .sendClientPacket("ChangeStoryLineFinishScNotify", notify.build().toByteArray());
  }

  private StoryLine46() {}
}
