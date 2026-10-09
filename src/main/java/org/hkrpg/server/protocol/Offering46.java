package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.*;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** Native CN 4.6 Offering tags and sends; currently the manual Dice menu entry (ID 12). */
public final class Offering46 {
  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental()) return false;
    String name = null;
    for (var request :
        List.of("GetOfferingInfoCsReq", "SubmitOfferingItemCsReq", "TakeOfferingRewardCsReq"))
      if (command == s.getProtocol().clientCommand(request)) name = request;
    if (name == null) return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    synchronized (s.getPlayer()) {
      var code = Retcode.REQ_PARA_INVALID;
      DynamicMessage req = null;
      if (wireValid(s.getProtocol().clientSchema().message(name), bytes)) {
        req = s.getProtocol().clientSchema().parse(name, bytes);
        code = Retcode.SUCC;
      }
      var catalog = OfferingCatalog.read();
      if (code == Retcode.SUCC && catalog == null) code = Retcode.REWARD_CONFIG_NOT_EXIST;
      if (code == Retcode.SUCC && !Offerings.valid(s.getPlayer())) code = Retcode.PLAYER_DATA_ERROR;
      if (name.equals("GetOfferingInfoCsReq")) {
        var out = builder(s, "GetOfferingInfoScRsp");
        if (code == Retcode.SUCC) {
          var ids = integers(req, "offering_id_list");
          if (ids.size() > 100
              || new HashSet<>(ids).size() != ids.size()
              || ids.stream().anyMatch(id -> id != 12)) code = Retcode.REQ_PARA_INVALID;
          else for (int id : ids) add(out, "offering_info_list", snapshot(s, id));
        }
        set(out, "retcode", code.getVal());
        s.sendClientPacket("GetOfferingInfoScRsp", out.build().toByteArray());
      } else {
        var result = new Offerings.Result(code, List.of());
        int id = req == null ? 0 : number(req, "offering_id");
        if (code == Retcode.SUCC)
          result =
              name.equals("SubmitOfferingItemCsReq")
                  ? Offerings.submit(
                      s.getPlayer(), catalog, id, number(req, "interacted_prop_entity_id"))
                  : Offerings.claim(
                      s.getPlayer(),
                      catalog,
                      id,
                      number(req, "interacted_prop_entity_id"),
                      integers(req, "take_reward_level_list"));
        String response = name.replace("CsReq", "ScRsp");
        var out = builder(s, response);
        set(out, "retcode", result.code().getVal());
        if (result.code() == Retcode.SUCC) {
          var info = snapshot(s, id);
          set(out, "offering_info", info);
          if (name.equals("TakeOfferingRewardCsReq")) {
            set(out, "reward", items(s, result.items()));
            var claims = s.getPlayer().getGameplay().getOfferings().get(id).getClaims();
            var claim = claims.getLast();
            var addedIds = new TreeSet<Integer>(claim.getAvatars().keySet());
            addedIds.addAll(claim.getDice().keySet());
            for (int item : addedIds) {
              var added = builder(s, "AddItemRsp");
              boolean avatar = claim.getAvatars().containsKey(item);
              set(added, avatar ? "avatar_id" : "CLEKAKLPNBP", item);
              set(
                  added,
                  "unlock_time",
                  avatar ? claim.getAvatars().get(item).getTime() : claim.getDice().get(item));
              set(
                  added,
                  "reward",
                  items(s, result.items().stream().filter(i -> i.getItemId() == item).toList()));
              s.sendClientPacket("AddItemRsp", added.build().toByteArray());
            }
          }
        }
        s.sendClientPacket(response, out.build().toByteArray());
      }
    }
    return true;
  }

  private static List<Integer> integers(DynamicMessage req, String field) {
    return ((List<?>) value(req, field)).stream().map(v -> (Integer) v).toList();
  }

  static DynamicMessage snapshot(GameSession s, int id) {
    var state = s.getPlayer().getGameplay().getOfferings().get(id);
    var b = builder(s, "OfferingInfo");
    set(b, "offering_id", id);
    var dice = DiceCombatCatalog.read();
    set(
        b,
        "offering_state",
        b.getDescriptorForType()
            .findFieldByName("offering_state")
            .getEnumType()
            .findValueByNumber(
                dice != null && dice.available(s.getPlayer(), Instant.now()) ? 2 : 1));
    if (state != null) {
      set(b, "offering_level", state.getLevel());
      set(b, "level_exp", state.getLevelExp());
      set(b, "total_exp", state.getTotalExp());
      Offerings.taken(state).forEach(level -> add(b, "has_taken_reward_id_list", level));
    }
    return b.build();
  }

  /** Packed and unpacked uint32 lists are both legal; truncation and duplicate scalars are not. */
  static boolean wireValid(com.google.protobuf.Descriptors.Descriptor descriptor, byte[] bytes) {
    if (bytes.length > 4096) return false;
    var in = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    int entries = 0;
    try {
      int tag;
      while ((tag = in.readTag()) != 0) {
        var field = descriptor.findFieldByNumber(tag >>> 3);
        int wire = tag & 7;
        if (field == null
            || field.getType() != com.google.protobuf.Descriptors.FieldDescriptor.Type.UINT32
            || !field.isRepeated() && !seen.add(field.getNumber())) return false;
        if (wire == 0) {
          if (++entries > 102 || Long.compareUnsigned(in.readUInt64(), 0xffffffffL) > 0)
            return false;
        } else if (wire == 2 && field.isRepeated()) {
          var packed = CodedInputStream.newInstance(in.readByteArray());
          while (!packed.isAtEnd())
            if (++entries > 102 || Long.compareUnsigned(packed.readUInt64(), 0xffffffffL) > 0)
              return false;
        } else return false;
      }
      return in.isAtEnd();
    } catch (IOException malformed) {
      return false;
    }
  }

  private Offering46() {}
}
