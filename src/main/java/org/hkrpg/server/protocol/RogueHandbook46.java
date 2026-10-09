package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** Native consumers prove field 2 contains miracles; the frozen semantic label is misleading. */
public final class RogueHandbook46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("GetRogueHandbookDataCsReq"))
      return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var response = builder(session, "GetRogueHandbookDataScRsp");
      if (bytes.length != 0) set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
      else if (!valid(session.getPlayer()))
        set(response, "retcode", Retcode.PLAYER_DATA_ERROR.getVal());
      else set(response, "HAMKPLCBEBG", snapshot(session));
      session.sendClientPacket("GetRogueHandbookDataScRsp", response.build().toByteArray());
    }
    return true;
  }

  private static boolean valid(Player player) {
    return player.getGameplay().getRogueHandbook() != null
        && player.getGameplay().getRogueHandbook().valid()
        && RogueHandbookRewards.validReceipts(player)
        && player.getGameplay().getRogueCollectedBuffs() != null;
  }

  private static List<Integer> buffs(Player player) {
    return player.getGameplay().getRogueCollectedBuffs().stream()
        .filter(Objects::nonNull)
        .filter(
            id ->
                id > 0
                    && GameData.getMazeBuffExcelMap().get(id, 1) != null
                    && GameData.getMazeBuffExcelMap().get(id, 1).getID() == id
                    && GameData.getMazeBuffExcelMap().get(id, 1).getLv() == 1
                    && GameplayDefinitions.blessings.stream()
                        .anyMatch(
                            row ->
                                row != null
                                    && row.MazeBuffID() == id
                                    && row.MazeBuffLevel() == 1
                                    && row.RogueVersion() == 1))
        .sorted()
        .toList();
  }

  private static List<Integer> miracles(Player player) {
    return player.getGameplay().getRogueHandbook().getMiracles().stream()
        .filter(
            id -> {
              var row = RogueHandbookCatalog.miracle(id);
              return row != null && row.MiracleHandbookID() == id;
            })
        .sorted()
        .toList();
  }

  private static DynamicMessage buff(GameSession session, int id) {
    var row = builder(session, "BPHIKPCNBHM");
    set(row, "maze_buff_id", id);
    return row.build();
  }

  private static DynamicMessage miracle(GameSession session, int id) {
    var row = builder(session, "CIGDBCKOFBN");
    set(row, "NPNMLPNBHDP", id);
    set(row, "ALAJJMKOMML", RogueHandbookRewards.taken(session.getPlayer(), id));
    return row.build();
  }

  private static DynamicMessage snapshot(GameSession session) {
    var data = builder(session, "NHGJEIHKKHB");
    for (int id : buffs(session.getPlayer())) add(data, "buff_list", buff(session, id));
    for (int id : miracles(session.getPlayer())) add(data, "MAJGMMCOCIG", miracle(session, id));
    return data.build();
  }

  public static void sync(Player player) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    synchronized (player) {
      if (!valid(player)) return;
      var session = player.getSession();
      var data = builder(session, "SyncRogueHandbookDataUpdateScNotify");
      for (int id : buffs(player)) add(data, "IPNAAGCMNLB", buff(session, id));
      for (int id : miracles(player)) add(data, "BFDFABHMCNE", miracle(session, id));
      if (!data.getAllFields().isEmpty())
        session.sendClientPacket("SyncRogueHandbookDataUpdateScNotify", data.build().toByteArray());
    }
  }

  private RogueHandbook46() {}
}
