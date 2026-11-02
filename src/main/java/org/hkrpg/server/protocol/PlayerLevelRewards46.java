package org.hkrpg.server.protocol;

import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.List;
import org.hkrpg.server.gameplay.PlayerLevelRewards;
import org.hkrpg.server.gameplay.RewardLedger;

/** Pinned CN4.6 CmdPlayerType: claim 19/85, taken-list 78/4. */
final class PlayerLevelRewards46 {
  static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 19 && command != 78) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    String name = command == 19 ? "GetLevelReward" : "GetLevelRewardTakenList";
    int level = 0;
    var result = new RewardLedger.Claim(Retcode.REQ_PARA_INVALID, List.of());
    List<Integer> taken = List.of();
    try {
      var schema = session.getProtocol().clientSchema();
      if (bytes != null && Parkour46.wireValid(schema.message(name + "CsReq"), bytes, 0)) {
        var request = schema.parse(name + "CsReq", bytes);
        if (command == 19) {
          level = Gameplay46.number(request,"level");
          result = PlayerLevelRewards.claim(session.getPlayer(), level);
        } else {
          taken = PlayerLevelRewards.taken(session.getPlayer());
          result = new RewardLedger.Claim(Retcode.SUCC, List.of());
        }
      }
    } catch (IOException | IllegalArgumentException invalid) {
      result = new RewardLedger.Claim(Retcode.REQ_PARA_INVALID, List.of());
    } catch (RuntimeException failed) {
      emu.lunarcore.LunarCore.getLogger().warn("Player level reward failed for player {}",
          session.getPlayer().getUid(), failed);
      result = new RewardLedger.Claim(Retcode.SYSTEM_BUSY, List.of());
    }
    var response = Gameplay46.builder(session, name + "ScRsp");
    Gameplay46.set(response,"retcode",result.retcode().getVal());
    if (command == 19) {
      Gameplay46.set(response,"level",level);
      if (result.retcode() == Retcode.SUCC) {
        var items = ItemList.newInstance();
        result.items().forEach(item -> items.addItemList(item.toProto()));
        Gameplay46.set(response,"reward",session.getProtocol().clientSchema().parse("ItemList",
            session.getProtocol().translate("ItemList",items.toByteArray(),true)));
      }
    } else {
      var field = response.getDescriptorForType().findFieldByName("level_reward_taken_list");
      taken.forEach(value -> response.addRepeatedField(field,value));
    }
    session.sendClientPacket(name + "ScRsp",response.build().toByteArray());
    return true;
  }
  private PlayerLevelRewards46() {}
}
