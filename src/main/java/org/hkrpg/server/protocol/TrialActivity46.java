package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.trial.TrialInstance;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import org.hkrpg.server.gameplay.TrialRun;

/** Native trial entry, confirmed completion query and frozen one-time rewards. */
public final class TrialActivity46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    boolean start = command == session.getProtocol().clientCommand("StartTrialActivityCsReq");
    boolean query = command == session.getProtocol().clientCommand("GetTrialActivityDataCsReq");
    boolean claim = command == session.getProtocol().clientCommand("TakeTrialActivityRewardCsReq");
    if (!start
        && !query
        && !claim
        && command != session.getProtocol().clientCommand("LeaveTrialActivityCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    if (query || claim) {
      rewardRequest(session, bytes, claim);
      return true;
    }
    String name = start ? "StartTrialActivityCsReq" : "LeaveTrialActivityCsReq";
    var type = session.getProtocol().clientSchema().message(name);
    boolean valid = Parkour46.wireValid(type, bytes, 0);
    var request = valid ? session.getProtocol().clientSchema().parse(name, bytes) : null;
    int stage = valid ? Gameplay46.number(request, "stage_id") : 0;
    Retcode code = Retcode.REQ_PARA_INVALID;
    var player = session.getPlayer();
    synchronized (player) {
      if (valid && stage > 0) {
        if (start) {
          if (player.isInBattle()
              || player.getInstance() != null
              || player.getGameplay().getStoryLines().isTransitionPending())
            code = Retcode.SYSTEM_BUSY;
          else
            code =
                !org.hkrpg.server.gameplay.TrialActivityCatalog.available(
                            player, stage, java.time.Instant.now())
                        || player.getTrialManager().startTrial(stage) == null
                    ? Retcode.REQ_PARA_INVALID
                    : Retcode.SUCC;
        } else code = player.getTrialManager().leaveTrial(stage);
      }
      var response =
          Gameplay46.builder(
              session, start ? "StartTrialActivityScRsp" : "LeaveTrialActivityScRsp");
      Gameplay46.set(response, "retcode", code.getVal());
      if (valid && stage > 0) Gameplay46.set(response, "stage_id", stage);
      session.sendClientPacket(
          start ? "StartTrialActivityScRsp" : "LeaveTrialActivityScRsp",
          response.build().toByteArray());
    }
    return true;
  }

  private static void rewardRequest(GameSession session, byte[] bytes, boolean claim)
      throws IOException {
    String requestName = claim ? "TakeTrialActivityRewardCsReq" : "GetTrialActivityDataCsReq";
    var type = session.getProtocol().clientSchema().message(requestName);
    boolean valid = Parkour46.wireValid(type, bytes, 0);
    var request = valid ? session.getProtocol().clientSchema().parse(requestName, bytes) : null;
    var player = session.getPlayer();
    synchronized (player) {
      var response =
          Gameplay46.builder(
              session, claim ? "TakeTrialActivityRewardScRsp" : "GetTrialActivityDataScRsp");
      Retcode code = valid ? Retcode.SUCC : Retcode.REQ_PARA_INVALID;
      if (claim) {
        int stage = valid ? Gameplay46.number(request, "stage_id") : 0;
        var result =
            valid && stage > 0 ? org.hkrpg.server.gameplay.TrialRewards.claim(player, stage) : null;
        code = result == null ? Retcode.REQ_PARA_INVALID : result.retcode();
        if (valid && stage > 0) Gameplay46.set(response, "stage_id", stage);
        if (result != null && !result.items().isEmpty())
          Gameplay46.set(response, "reward", Gameplay46.items(session, result.items()));
      } else if (valid) {
        var trial = player.getInstance(TrialInstance.class);
        if (trial != null) Gameplay46.set(response, "activity_stage_id", trial.getStageId());
        for (int stage :
            new java.util.TreeSet<>(player.getGameplay().getTrialCompletions().keySet())) {
          if (stage <= 0) continue;
          Gameplay46.add(response, "trial_activity_info_list", activityInfo(session, player, stage));
        }
      }
      Gameplay46.set(response, "retcode", code.getVal());
      session.sendClientPacket(
          claim ? "TakeTrialActivityRewardScRsp" : "GetTrialActivityDataScRsp",
          response.build().toByteArray());
    }
  }

  private static DynamicMessage activityInfo(GameSession session, Player player, int stage) {
    var info = Gameplay46.builder(session, "TrialActivityInfo");
    Gameplay46.set(info, "stage_id", stage);
    Gameplay46.set(
        info,
        "taken_reward",
        player.getRewardLedger().granted(org.hkrpg.server.gameplay.TrialRewards.key(stage)));
    return info.build();
  }

  /** The native module updates its passed-stage cache from 2676, before its detail page refreshes. */
  public static void changed(Player player, int stage) {
    var session = player.getSession();
    if (session == null
        || !session.getProtocol().experimental()
        || session.getState() != SessionState.ACTIVE) return;
    synchronized (player) {
      if (stage <= 0 || player.getGameplay().getTrialCompletions().get(stage) == null) return;
      var notification = Gameplay46.builder(session, "TrialActivityDataChangeScNotify");
      Gameplay46.set(notification, "trial_activity_info", activityInfo(session, player, stage));
      session.sendClientPacket("TrialActivityDataChangeScNotify", notification.build().toByteArray());
    }
  }

  /** Restore the native RoleTrialInstance before the saved trial scene opens its detail page. */
  public static void sync(GameSession session) {
    if (!session.getProtocol().experimental() || session.getState() != SessionState.ACTIVE) return;
    var player = session.getPlayer();
    synchronized (player) {
      var trial = player.getInstance(TrialInstance.class);
      var run = player.getGameplay().getTrialRun();
      if (trial == null || trial.isFinished() || run == null || trial.getRun() != run
          || run.getPhase() != TrialRun.Phase.ACTIVE) return;
      // GetTrialActivityDataScRsp only populates reward history in the pinned client. Its
      // current stage field does not construct RoleTrialInstance; status NONE on 2646 does.
      notifyStatus(session, trial.getStageId(), 0);
    }
  }

  public static void finished(GameSession session, int stage) {
    notifyStatus(session, stage, 1);
  }

  private static void notifyStatus(GameSession session, int stage, int state) {
    if (!session.getProtocol().experimental() || stage <= 0)
      throw new ProtocolException("Invalid native trial status");
    var type = session.getProtocol().clientSchema().message("CurTrialActivityScNotify");
    var status = type.findFieldByNumber(10);
    var body =
        DynamicMessage.newBuilder(type)
            .setField(type.findFieldByNumber(1), stage)
            .setField(status, status.getEnumType().findValueByNumber(state))
            .build();
    session.sendClientPacket("CurTrialActivityScNotify", body.toByteArray());
  }

  public static void finished(Player player, TrialInstance trial) {
    var session = player.getSession();
    if (session != null && session.getProtocol().experimental()) {
      if (session.getState() == SessionState.ACTIVE) finished(session, trial.getStageId());
      return;
    }
    player.sendPacket(new emu.lunarcore.server.packet.send.PacketCurTrialActivityScNotify(trial));
  }

  private TrialActivity46() {}
}
