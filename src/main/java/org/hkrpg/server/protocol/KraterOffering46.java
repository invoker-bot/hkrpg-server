package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.*;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** The existing 4.6 offering schema, with its original world-shop NPC verification contract. */
public final class KraterOffering46 {
  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental()) return false;
    String name = null;
    for (String request :
        List.of("GetOfferingInfoCsReq", "SubmitOfferingItemCsReq", "TakeOfferingRewardCsReq"))
      if (command == s.getProtocol().clientCommand(request)) name = request;
    if (name == null) return false;
    DynamicMessage req;
    try {
      req = s.getProtocol().clientSchema().parse(name, bytes);
    } catch (IOException malformed) {
      return false;
    }
    var ids =
        name.equals("GetOfferingInfoCsReq")
            ? integers(req, "offering_id_list")
            : List.of(number(req, "offering_id"));
    if (!ids.contains(6)) return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    synchronized (s.getPlayer()) {
      var code =
          Offering46.wireValid(req.getDescriptorForType(), bytes)
              ? Retcode.SUCC
              : Retcode.REQ_PARA_INVALID;
      var out = builder(s, name.replace("CsReq", "ScRsp"));
      if (name.equals("GetOfferingInfoCsReq")) {
        if (ids.size() > 2
            || new HashSet<>(ids).size() != ids.size()
            || ids.stream().anyMatch(id -> id != 6 && id != 12)) code = Retcode.REQ_PARA_INVALID;
        var info = KraterOfferings.snapshot(s.getPlayer());
        if (code == Retcode.SUCC) code = info.code();
        if (code == Retcode.SUCC && ids.contains(12)) {
          if (OfferingCatalog.read() == null) code = Retcode.REWARD_CONFIG_NOT_EXIST;
          else if (!Offerings.valid(s.getPlayer())) code = Retcode.PLAYER_DATA_ERROR;
        }
        if (code == Retcode.SUCC)
          for (int id : ids)
            add(
                out,
                "offering_info_list",
                id == 6 ? snapshot(s, info) : Offering46.snapshot(s, 12));
      } else {
        var result = new KraterOfferings.Result(code, List.of());
        if (code == Retcode.SUCC)
          result =
              name.equals("SubmitOfferingItemCsReq")
                  ? KraterOfferings.submit(s.getPlayer(), number(req, "interacted_prop_entity_id"))
                  : KraterOfferings.claim(
                      s.getPlayer(),
                      number(req, "interacted_prop_entity_id"),
                      integers(req, "take_reward_level_list"));
        code = result.code();
        if (code == Retcode.SUCC) {
          var info = KraterOfferings.snapshot(s.getPlayer());
          code = info.code();
          if (code == Retcode.SUCC) {
            set(out, "offering_info", snapshot(s, info));
            if (name.equals("TakeOfferingRewardCsReq"))
              set(out, "reward", items(s, result.items()));
          }
        }
      }
      set(out, "retcode", code.getVal());
      s.sendClientPacket(name.replace("CsReq", "ScRsp"), out.build().toByteArray());
    }
    return true;
  }

  private static List<Integer> integers(DynamicMessage req, String key) {
    return ((List<?>) value(req, key)).stream().map(v -> (Integer) v).toList();
  }

  private static DynamicMessage snapshot(GameSession s, KraterOfferings.Snapshot info) {
    var out = builder(s, "OfferingInfo");
    set(out, "offering_id", 6);
    set(out, "offering_level", info.level());
    set(out, "level_exp", info.exp());
    set(out, "total_exp", info.total());
    set(
        out,
        "offering_state",
        out.getDescriptorForType()
            .findFieldByName("offering_state")
            .getEnumType()
            .findValueByNumber(info.open() ? 2 : 1));
    info.taken().forEach(level -> add(out, "has_taken_reward_id_list", level));
    return out.build();
  }

  private KraterOffering46() {}
}
