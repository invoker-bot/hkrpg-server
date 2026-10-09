package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.LocalLegendBattles;
import org.hkrpg.server.gameplay.LocalLegendCatalog;
import org.hkrpg.server.gameplay.LocalLegendParty;

/** Original activity entry, query and saved target-bit notification. */
public final class LocalLegend46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    boolean enter = command == session.getProtocol().clientCommand("EnterLocalLegendLevelCsReq");
    if (!enter && command != session.getProtocol().clientCommand("GetLocalLegendDataCsReq"))
      return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    if (enter) return enter(session, bytes);
    synchronized (session.getPlayer()) {
      var response = builder(session, "GetLocalLegendDataScRsp");
      var history = session.getPlayer().getGameplay().getLocalLegendProgress();
      var schema = session.getProtocol().clientSchema();
      int code =
          !Parkour46.wireValid(schema.message("GetLocalLegendDataCsReq"), bytes, 0)
              ? Retcode.REQ_PARA_INVALID.getVal()
              : !history.valid() ? Retcode.PLAYER_DATA_ERROR.getVal() : 0;
      set(response, "retcode", code);
      if (code == 0)
        for (var level : LocalLegendCatalog.levels())
          add(response, "level_data_list", progress(session, level));
      session.sendClientPacket("GetLocalLegendDataScRsp", response.build().toByteArray());
    }
    return true;
  }

  private static boolean enter(GameSession session, byte[] bytes) throws IOException {
    synchronized (session.getPlayer()) {
      var schema = session.getProtocol().clientSchema();
      var type = schema.message("EnterLocalLegendLevelCsReq");
      var request =
          Parkour46.wireValid(type, bytes, 0)
              ? schema.parse("EnterLocalLegendLevelCsReq", bytes)
              : null;
      int group = request == null ? 0 : number(request, "group_id");
      int difficulty = request == null ? 0 : number(request, "DBKHHCGGKNN");
      var response = builder(session, "EnterLocalLegendLevelScRsp");
      var entry = new LocalLegendBattles.Entry(Retcode.REQ_PARA_INVALID, null);
      if (request != null && only(request, "group_id", "DBKHHCGGKNN", "avatar_list")) {
        var values = (List<?>) value(request, "avatar_list");
        if (!values.isEmpty() && values.size() <= 4) {
          var roster = new ArrayList<LocalLegendParty.Member>();
          for (var value : values) {
            var member = (DynamicMessage) value;
            var avatarType =
                (com.google.protobuf.Descriptors.EnumValueDescriptor) value(member, "avatar_type");
            int typeNumber =
                switch (avatarType.getName()) {
                  case "AvatarType_AvatarFormalType" -> 1;
                  case "AvatarType_AvatarTrialType" -> 3;
                  default -> 0;
                };
            roster.add(new LocalLegendParty.Member(number(member, "avatar_id"), typeNumber));
          }
          entry =
              LocalLegendBattles.start(
                  session.getPlayer(), group, difficulty, roster, java.time.Instant.now());
        }
      }
      set(response, "retcode", entry.retcode().getVal());
      if (entry.battle() != null) {
        set(response, "group_id", group);
        set(response, "DBKHHCGGKNN", difficulty);
        set(
            response,
            "battle_info",
            schema.parse(
                "SceneBattleInfo",
                session
                    .getProtocol()
                    .translate("SceneBattleInfo", entry.battle().toProto().toByteArray(), true)));
      }
      session.sendClientPacket("EnterLocalLegendLevelScRsp", response.build().toByteArray());
      return true;
    }
  }

  private static DynamicMessage progress(GameSession session, LocalLegendCatalog.Level level) {
    var row = builder(session, "JJEFLBCLBFN");
    set(row, "group_id", level.group());
    set(row, "DBKHHCGGKNN", level.difficulty());
    set(row, "FFGFINDLMCF", session.getPlayer().getGameplay().getLocalLegendProgress().mask(level));
    return row.build();
  }

  /** Caller publishes only after accepted battle progress has been saved. */
  public static void notify(Player player, LocalLegendCatalog.Level level) {
    synchronized (player) {
      var session = player.getSession();
      if (session == null
          || session.getState() != SessionState.ACTIVE
          || session.getPlayer() != player
          || !session.getProtocol().experimental()
          || level == null
          || LocalLegendCatalog.level(level.group(), level.difficulty()) != level
          || !player.getGameplay().getLocalLegendProgress().valid()) return;
      var response = builder(session, "LocalLegendDataChangeNotify");
      set(response, "level_data", progress(session, level));
      session.sendClientPacket("LocalLegendDataChangeNotify", response.build().toByteArray());
    }
  }

  private LocalLegend46() {}
}
