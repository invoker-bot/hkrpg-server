package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** Native talent fields are matched to the pinned local CN 4.6 codec and consumers. */
public final class RogueTalent46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    boolean query = command == session.getProtocol().clientCommand("GetRogueTalentInfoCsReq");
    if (!query && command != session.getProtocol().clientCommand("EnableRogueTalentCsReq"))
      return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    String name = query ? "GetRogueTalentInfoCsReq" : "EnableRogueTalentCsReq";
    var request = session.getProtocol().clientSchema().parse(name, bytes);
    synchronized (session.getPlayer()) {
      boolean valid =
          request.getUnknownFields().asMap().isEmpty()
              && (query ? request.getAllFields().isEmpty() : only(request, "CKHCLKOALKE"));
      var result =
          !valid
              ? Retcode.REQ_PARA_INVALID
              : query
                  ? Retcode.SUCC
                  : RogueTalents.enable(session.getPlayer(), number(request, "CKHCLKOALKE"));
      var response = builder(session, name.replace("CsReq", "ScRsp"));
      set(response, "retcode", result.getVal());
      // The client consumes the list even on error, so always return the authoritative snapshot.
      set(response, "JCOBJAMPOAL", snapshot(session));
      session.sendClientPacket(name.replace("CsReq", "ScRsp"), response.build().toByteArray());
      return true;
    }
  }

  static DynamicMessage snapshot(GameSession session) {
    var player = session.getPlayer();
    var list = builder(session, "FDBDLBAPDBE");
    new TreeMap<>(GameplayDefinitions.rogueTalents)
        .forEach(
            (id, talent) -> {
              var row = builder(session, "OKPIPFKONMG");
              set(row, "CKHCLKOALKE", id);
              var status = row.getDescriptorForType().findFieldByName("status");
              row.setField(
                  status,
                  status
                      .getEnumType()
                      .findValueByName(
                          "RogueTalentStatus_"
                              + switch (RogueTalents.status(player, id)) {
                                case LOCK -> "Lock";
                                case UNLOCK -> "Unlock";
                                case ENABLE -> "Enable";
                              }));
              if (talent.UnlockIDList() != null)
                for (Integer unlock : new LinkedHashSet<>(talent.UnlockIDList())) {
                  if (unlock == null || unlock <= 0) continue;
                  var condition = builder(session, "GKFGIMIBBHF");
                  set(condition, "MMFGAJNCAOA", unlock);
                  set(condition, "progress", RogueUnlocks.talentProgress(player, unlock).orElse(0));
                  set(condition, "finish", RogueUnlocks.open(player, unlock));
                  add(row, "LDHIJAHNEGK", condition.build());
                }
              add(list, "BLHJIPBHHFO", row.build());
            });
    return list.build();
  }

  public static void syncCurrency(Player player) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    var session = player.getSession();
    var info = builder(session, "GAOBHBLFPEC");
    // df47250 passes +1c to item 32; 1e3f8281 serializes that member as field 2.
    set(info, "OFHBBKOKANF", player.getTalentPoints());
    set(info, "GBIEAMKLONK", RogueCurrencies.balance(player, 33));
    set(info, "DBHOIDLKMAB", RogueCurrencies.balance(player, 34));
    set(info, "LKHAIEIDAJL", player.getGameplay().getRogue().getCoins());
    var notify = builder(session, "SyncRogueVirtualItemInfoScNotify");
    set(notify, "NIKDPODBIEF", info.build());
    session.sendClientPacket("SyncRogueVirtualItemInfoScNotify", notify.build().toByteArray());
  }

  private RogueTalent46() {}
}
