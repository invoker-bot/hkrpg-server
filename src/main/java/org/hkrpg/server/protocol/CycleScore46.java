package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;

/** Native cycle query/claims, matched to the pinned 4.6 codec and CycleScoreService. */
public final class CycleScore46 {
  private static final List<String> REQUESTS =
      List.of(
          "CycleScoreRewardGetDataCsReq",
          "CycleScoreRewardTakeRewardCsReq",
          "CycleScoreRewardDestroyExceedRewardCsReq");

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    String name =
        REQUESTS.stream()
            .filter(n -> session.getProtocol().clientCommand(n) == command)
            .findFirst()
            .orElse(null);
    if (name == null) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var req = session.getProtocol().clientSchema().parse(name, bytes);
    synchronized (session.getPlayer()) {
      String responseName = name.replace("CsReq", "ScRsp");
      var response = builder(session, responseName);
      Retcode code = Retcode.REQ_PARA_INVALID;
      if (req.getUnknownFields().asMap().isEmpty()) {
        if (name.equals("CycleScoreRewardGetDataCsReq") && req.getAllFields().isEmpty()) {
          session.getPlayer().getCycleScoreManager().settleSaved();
          code =
              session.getPlayer().getCycleScoreManager().snapshot().isPresent()
                  ? Retcode.SUCC
                  : Retcode.REWARD_CONFIG_NOT_EXIST;
        } else if (name.equals("CycleScoreRewardTakeRewardCsReq") && only(req, "LLJHJADNDKB")) {
          @SuppressWarnings("unchecked")
          var ranks = (List<Integer>) value(req, "LLJHJADNDKB");
          var result = session.getPlayer().getCycleScoreManager().claim(ranks);
          code = result.retcode();
          result.exceeded().forEach(id -> add(response, "NGKOFMBBGNL", id));
          var reward = ItemList.newInstance();
          result.items().forEach(item -> reward.addItemList(item.toProto()));
          set(
              response,
              "ENGHMIAMPLG",
              session
                  .getProtocol()
                  .clientSchema()
                  .parse(
                      "ItemList",
                      session.getProtocol().translate("ItemList", reward.toByteArray(), true)));
        } else if (name.equals("CycleScoreRewardDestroyExceedRewardCsReq")
            && only(req, "BHNEOLKFNAM")) {
          @SuppressWarnings("unchecked")
          var ranks = (List<Integer>) value(req, "BHNEOLKFNAM");
          code = session.getPlayer().getCycleScoreManager().discard(ranks);
        }
      }
      set(response, "retcode", code.getVal());
      // Callbacks synchronise this state before their retcode handling.
      set(response, "NFEDANFBIBE", snapshot(session));
      session.sendClientPacket(responseName, response.build().toByteArray());
      return true;
    }
  }

  private static DynamicMessage snapshot(GameSession session) {
    var result = builder(session, "GAAMBBFPCHB");
    session
        .getPlayer()
        .getCycleScoreManager()
        .snapshot()
        .ifPresent(
            s -> {
              set(result, "start_time", s.start());
              set(result, "end_time", s.end());
              set(result, "PMJGAJGIFOA", s.score());
              s.taken().forEach(rank -> add(result, "OPLNKCOLCKJ", rank));
            });
    return result.build();
  }

  public static void sync(Player player) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    var session = player.getSession();
    var notify = builder(session, "CycleScoreRewardUpdateInfoScNotify");
    set(notify, "NFEDANFBIBE", snapshot(session));
    session.sendClientPacket("CycleScoreRewardUpdateInfoScNotify", notify.build().toByteArray());
  }

  private CycleScore46() {}
}
