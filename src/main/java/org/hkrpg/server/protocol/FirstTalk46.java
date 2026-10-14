package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;

/** Native intro dialogue reports, verified against the local CN 4.6 sender and wire tags. */
public final class FirstTalk46 {
  private static final Set<String> REQUESTS =
      Set.of("GetFirstTalkByPerformanceNpcCsReq", "FinishFirstTalkByPerformanceNpcCsReq");

  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental()) return false;
    String name =
        REQUESTS.stream()
            .filter(n -> s.getProtocol().clientCommand(n) == command)
            .findFirst()
            .orElse(null);
    if (name == null) return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    var req = s.getProtocol().clientSchema().parse(name, bytes);
    if (!req.getUnknownFields().asMap().isEmpty())
      throw new ProtocolException("Unknown first talk fields");
    var player = s.getPlayer();
    synchronized (player) {
      if (name.equals("GetFirstTalkByPerformanceNpcCsReq")) {
        @SuppressWarnings("unchecked")
        var ids = (List<Integer>) value(req, "performance_id_list");
        var b = builder(s, "GetFirstTalkByPerformanceNpcScRsp");
        if (!only(req, "performance_id_list")
            || ids.size() > 100
            || ids.stream().anyMatch(id -> id <= 0))
          set(b, "retcode", Retcode.REQ_PARA_INVALID.getVal());
        else
          for (int id : new LinkedHashSet<>(ids)) {
            var row = builder(s, "NpcMeetByPerformanceStatus");
            set(row, "performance_id", id);
            set(row, "is_meet", player.getMissionManager().hasSeenFirstTalk(id));
            add(b, "npc_meet_status_list", row.build());
          }
        s.sendClientPacket("GetFirstTalkByPerformanceNpcScRsp", b.build().toByteArray());
      } else {
        int id = number(req, "performance_id");
        var code =
            id > 0 && only(req, "performance_id") && Parkour46.wireValid(req.getDescriptorForType(), bytes, 0)
                ? player.getMissionManager().finishFirstTalkPerformance(id)
                : Retcode.REQ_PARA_INVALID;
        // Authored mission rewards are emitted through SubMissionRewardScNotify; no independent
        // first-talk reward is inferred from a performance ID.
        send(
            s,
            "FinishFirstTalkByPerformanceNpcScRsp",
            Map.of("retcode", code.getVal(), "performance_id", id));
      }
    }
    return true;
  }

  private FirstTalk46() {}
}
