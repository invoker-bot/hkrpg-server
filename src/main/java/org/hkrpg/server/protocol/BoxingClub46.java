package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/**
 * Native source-bound boxing operations. A response is never proof of complete activity gameplay.
 */
public final class BoxingClub46 {
  private static final List<String> REQUESTS =
      List.of(
          "GetBoxingClubInfoCsReq",
          "MatchBoxingClubOpponentCsReq",
          "StartBoxingClubBattleCsReq",
          "GiveUpBoxingClubChallengeCsReq",
          "ChooseBoxingClubResonanceCsReq",
          "ChooseBoxingClubStageOptionalBuffCsReq",
          "SetBoxingClubResonanceCsReq");

  public static boolean handle(GameSession s, int command, byte[] bytes)
      throws java.io.IOException {
    if (!s.getProtocol().experimental()) return false;
    String name =
        REQUESTS.stream()
            .filter(n -> s.getProtocol().clientCommand(n) == command)
            .findFirst()
            .orElse(null);
    if (name == null) return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    synchronized (s.getPlayer()) {
      var p = s.getPlayer();
      var schema = s.getProtocol().clientSchema();
      var req = wireValid(schema.message(name), bytes, 0) ? schema.parse(name, bytes) : null;
      String responseName = name.replace("CsReq", "ScRsp");
      var response = builder(s, responseName);
      int code = Retcode.REQ_PARA_INVALID.getVal();
      var catalog = BoxingClubCatalog.load(Path.of(LunarCore.getConfig().getResourceDir()));
      if (name.equals("GetBoxingClubInfoCsReq")) {
        if (req != null && bytes.length == 0 && catalog != null && validState(p)) {
          code = 0;
          for (var c : catalog.challenges().values())
            add(response, "challenge_list", progress(s, catalog, c.id()));
          var second =
              BoxingClubResonanceCatalog.load(Path.of(LunarCore.getConfig().getResourceDir()));
          if (second != null)
            for (var c : second.challenges().values())
              add(response, "challenge_list", progress(s, catalog, c.id()));
        }
      } else if (name.equals("MatchBoxingClubOpponentCsReq")) {
        int id = req == null ? 0 : number(req, "challenge_id");
        if (req != null && only(req, "challenge_id", "avatar_list", "OOJOMKDMBBM")) {
          var roster = roster(req);
          if (roster != null)
            code = BoxingClubManager.matchMembers(p, id, roster, Instant.now()).getVal();
        }
        if (code == 0) set(response, "OCDLPDDMCLP", progress(s, catalog, id));
      } else if (name.equals("StartBoxingClubBattleCsReq")) {
        int id = req == null ? 0 : number(req, "challenge_id");
        if (req != null && only(req, "challenge_id")) {
          emu.lunarcore.game.battle.Battle battle;
          if (id >= 6) {
            var entry = BoxingClubResonanceManager.start(p, id, Instant.now());
            code = entry.retcode().getVal();
            battle = entry.battle();
          } else {
            var entry = BoxingClubManager.start(p, id, Instant.now());
            code = entry.retcode().getVal();
            battle = entry.battle();
          }
          if (battle != null) {
            set(response, "challenge_id", id);
            set(
                response,
                "battle_info",
                schema.parse(
                    "SceneBattleInfo",
                    s.getProtocol()
                        .translate("SceneBattleInfo", battle.toProto().toByteArray(), true)));
          }
        }
      } else if (name.equals("GiveUpBoxingClubChallengeCsReq")) {
        int id = req == null ? 0 : number(req, "challenge_id");
        // The extra boolean's behavior remains unverified; only its absent/false form is accepted.
        if (req != null
            && only(req, "challenge_id", "KEAAGAIAHLA")
            && !((Boolean) value(req, "KEAAGAIAHLA")))
          code =
              (id >= 6 ? BoxingClubResonanceManager.giveUp(p, id) : BoxingClubManager.giveUp(p, id))
                  .getVal();
        if (code == 0 && catalog != null) set(response, "OCDLPDDMCLP", progress(s, catalog, id));
      } else if (name.equals("SetBoxingClubResonanceCsReq")) {
        int id = req == null ? 0 : number(req, "challenge_id");
        if (req != null && only(req, "challenge_id", "OOJOMKDMBBM")) {
          var members = roster(req);
          if (members != null)
            code = BoxingClubResonanceManager.seed(p, id, members, Instant.now()).getVal();
        }
        if (code == 0 && catalog != null) set(response, "OCDLPDDMCLP", progress(s, catalog, id));
      } else if (name.equals("ChooseBoxingClubResonanceCsReq")
          || name.equals("ChooseBoxingClubStageOptionalBuffCsReq")) {
        int id = req == null ? 0 : number(req, "challenge_id");
        boolean resonance = name.equals("ChooseBoxingClubResonanceCsReq");
        String field = resonance ? "HODLKBMKMCF" : "MFKAOHKPIPE";
        if (req != null && only(req, "challenge_id", field)) {
          int buff = number(req, field);
          code =
              (resonance
                      ? BoxingClubResonanceManager.resonance(p, id, buff, Instant.now())
                      : BoxingClubResonanceManager.optional(p, id, buff, Instant.now()))
                  .getVal();
        }
        if (code == 0 && catalog != null) set(response, "OCDLPDDMCLP", progress(s, catalog, id));
      }
      if (code != 0)
        BoxingClubRejections.record(s, name, req == null
            || req.getDescriptorForType().findFieldByName("challenge_id") == null
                ? 0 : number(req, "challenge_id"), code, req != null);
      set(response, "retcode", code);
      s.sendClientPacket(responseName, response.build().toByteArray());
      return true;
    }
  }

  private static boolean validState(Player p) {
    try {
      var state = p.getGameplay().getBoxingClub();
      return (state.getActive() == null && state.getCompleted().isEmpty()
              || BoxingClubManager.valid(p))
          && BoxingClubResonanceManager.valid(p);
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static List<BoxingClubManager.Member> roster(DynamicMessage req) {
    var legacy = new ArrayList<Integer>();
    if (req.getDescriptorForType().findFieldByName("avatar_list") != null)
      for (var id : (List<?>) value(req, "avatar_list")) legacy.add((Integer) id);
    var modern = new ArrayList<BoxingClubManager.Member>();
    for (var object : (List<?>) value(req, "OOJOMKDMBBM")) {
      var member = (DynamicMessage) object;
      var type = (Descriptors.EnumValueDescriptor) value(member, "avatar_type");
      if (!only(member, "avatar_id", "avatar_type")) return null;
      int kind =
          switch (type.getName()) {
            case "AvatarType_AvatarFormalType" -> 1;
            case "AvatarType_AvatarTrialType" -> 3;
            default -> 0;
          };
      if (kind == 0) return null;
      modern.add(new BoxingClubManager.Member(number(member, "avatar_id"), kind));
    }
    if (!legacy.isEmpty()
        && !modern.isEmpty()
        && !legacy.equals(modern.stream().map(BoxingClubManager.Member::id).toList())) return null;
    var selected =
        modern.isEmpty()
            ? legacy.stream().map(id -> new BoxingClubManager.Member(id, 1)).toList()
            : modern;
    return selected.isEmpty()
            || selected.size() > 4
            || selected.stream().anyMatch(m -> m.id() <= 0)
            || selected.stream().map(BoxingClubManager.Member::id).distinct().count()
                != selected.size()
        ? null
        : List.copyOf(selected);
  }

  private static DynamicMessage progress(GameSession s, BoxingClubCatalog catalog, int id) {
    if (id >= 6) return resonanceProgress(s, id);
    var row = builder(s, "CIEEKOEECHB");
    set(row, "challenge_id", id);
    var state = s.getPlayer().getGameplay().getBoxingClub();
    var best = state.getCompleted().get(id);
    if (best != null) {
      set(row, "LFFBKJBJJDD", best.rounds());
      set(row, "MLOGJKEOCPP", new RewardLedger(s.getPlayer()).granted("boxing:first:" + id));
    }
    var run = state.getActive();
    if (run != null && run.challenge() == id) {
      set(row, "IFHHFBIJOFC", run.group());
      set(row, "stage_index", run.index());
      set(row, "EAFICCLCLLA", run.rounds());
      for (int buff : run.buffs(catalog)) add(row, "EJEFDFEFOMH", buff);
      for (var actor : run.members()) {
        int avatar = actor.id();
        add(row, "avatar_list", avatar);
        var member = builder(s, "FLLJIHLMKDI");
        set(member, "avatar_id", avatar);
        var field = member.getDescriptorForType().findFieldByName("avatar_type");
        member.setField(
            field,
            field
                .getEnumType()
                .findValueByName(
                    actor.type() == 3
                        ? "AvatarType_AvatarTrialType"
                        : "AvatarType_AvatarFormalType"));
        add(row, "OOJOMKDMBBM", member.build());
      }
    }
    return row.build();
  }

  private static DynamicMessage resonanceProgress(GameSession s, int id) {
    var row = builder(s, "CIEEKOEECHB");
    set(row, "challenge_id", id);
    var state = s.getPlayer().getGameplay().getBoxingClubResonance();
    var best = state.getCompleted().get(id);
    if (best != null) {
      set(row, "LFFBKJBJJDD", best.rounds());
      set(row, "MLOGJKEOCPP", new RewardLedger(s.getPlayer()).granted("boxing:second:" + id));
    }
    var run = state.getActive();
    if (run != null && run.challenge() == id) {
      set(row, "IFHHFBIJOFC", run.group());
      set(row, "HODLKBMKMCF", run.resonance());
      set(row, "stage_index", run.index());
      set(row, "EAFICCLCLLA", run.rounds());
      for (int buff : run.options()) add(row, "EJEFDFEFOMH", buff);
      for (var actor : run.members()) {
        add(row, "avatar_list", actor.id());
        var member = builder(s, "FLLJIHLMKDI");
        set(member, "avatar_id", actor.id());
        var type = member.getDescriptorForType().findFieldByName("avatar_type");
        member.setField(
            type,
            type.getEnumType()
                .findValueByName(
                    actor.type() == 3
                        ? "AvatarType_AvatarTrialType"
                        : "AvatarType_AvatarFormalType"));
        add(row, "OOJOMKDMBBM", member.build());
      }
    }
    return row.build();
  }

  public static void update(Player p, int id) {
    synchronized (p) {
      var s = activeSession(p);
      var catalog = BoxingClubCatalog.load(Path.of(LunarCore.getConfig().getResourceDir()));
      if (s == null || catalog == null || !validState(p)) return;
      var n = builder(s, "BoxingClubChallengeUpdateScNotify");
      set(n, "OCDLPDDMCLP", progress(s, catalog, id));
      s.sendClientPacket("BoxingClubChallengeUpdateScNotify", n.build().toByteArray());
    }
  }

  public static void reward(
      Player p, int id, int rounds, List<emu.lunarcore.game.inventory.GameItem> rewards) {
    synchronized (p) {
      var s = activeSession(p);
      if (s == null || !validState(p)) return;
      var n = builder(s, "BoxingClubRewardScNotify");
      set(n, "challenge_id", id);
      set(n, "EAFICCLCLLA", rounds);
      set(n, "is_win", true);
      set(n, "reward", items(s, rewards));
      s.sendClientPacket("BoxingClubRewardScNotify", n.build().toByteArray());
    }
  }

  private static GameSession activeSession(Player p) {
    var s = p.getSession();
    return s != null
            && s.getPlayer() == p
            && s.getState() == SessionState.ACTIVE
            && s.getProtocol().experimental()
        ? s
        : null;
  }

  /** Reject raw integer aliases and duplicate scalars; legacy avatar IDs may be packed. */
  private static boolean wireValid(Descriptors.Descriptor type, byte[] bytes, int depth) {
    if (depth > 2 || bytes.length > 16384) return false;
    var input = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    int repeated = 0;
    try {
      int tag;
      while ((tag = input.readTag()) != 0) {
        var f = type.findFieldByNumber(tag >>> 3);
        if (f == null || !f.isRepeated() && !seen.add(f.getNumber())) return false;
        int wire = tag & 7;
        if (f.isRepeated() && ++repeated > 8) return false;
        if (f.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
          if (wire != 2 || !wireValid(f.getMessageType(), input.readByteArray(), depth + 1))
            return false;
        } else if (f.isRepeated() && wire == 2) {
          var packed = CodedInputStream.newInstance(input.readByteArray());
          int n = 0;
          while (!packed.isAtEnd())
            if (++n > 4 || Long.compareUnsigned(packed.readUInt64(), Integer.MAX_VALUE) > 0)
              return false;
        } else {
          if (wire != 0) return false;
          long v = input.readUInt64();
          if (Long.compareUnsigned(v, Integer.MAX_VALUE) > 0
              || f.getJavaType() == Descriptors.FieldDescriptor.JavaType.BOOLEAN && v > 1
              || f.getJavaType() == Descriptors.FieldDescriptor.JavaType.ENUM
                  && f.getEnumType().findValueByNumber((int) v) == null) return false;
        }
      }
      return true;
    } catch (java.io.IOException invalid) {
      return false;
    }
  }

  private BoxingClub46() {}
}
