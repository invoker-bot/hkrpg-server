package org.hkrpg.server.protocol;

import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.proto.RankUpAvatarCsReqOuterClass.RankUpAvatarCsReq;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.AvatarMilestones;
import org.hkrpg.server.gameplay.RewardLedger;

/** Pinned CN4.6 308/368 and 317/338; every rejected mutation receives its typed response. */
final class AvatarMilestones46 {
  static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || (command != 308 && command != 317)) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    String name = command == 308 ? "RankUpAvatar" : "TakePromotionReward";
    var result = new RewardLedger.Claim(Retcode.REQ_PARA_INVALID,List.of());
    try {
      var protocol = session.getProtocol();
      var type = protocol.clientSchema().message(name + "CsReq");
      if (bytes != null && bytes.length <= 16384 && Parkour46.wireValid(type,bytes,0)) {
        if (command == 308) {
          var request = RankUpAvatarCsReq.parseFrom(protocol.inbound(command,bytes).body());
          var costs = new ArrayList<ItemParam>();
          boolean valid = request.getRank() >= 0 && request.getRank() <= 100;
          for (var item : request.getItemCostList().getItemList()) {
            valid &= item.hasPileItem();
            costs.add(new ItemParam(item));
          }
          if (valid && !costs.isEmpty()) result = new RewardLedger.Claim(
              AvatarMilestones.activateRank(session.getPlayer(),request.getAvatarId(),costs),List.of());
        } else {
          var request = protocol.clientSchema().parse(name + "CsReq",bytes);
          result = AvatarMilestones.claimPromotion(session.getPlayer(),Gameplay46.number(request,"base_avatar_id"),
              Gameplay46.number(request,"promotion"));
        }
      }
    } catch (IOException | IllegalArgumentException invalid) {
      result = new RewardLedger.Claim(Retcode.REQ_PARA_INVALID,List.of());
    } catch (RuntimeException failed) {
      emu.lunarcore.LunarCore.getLogger().warn("Avatar milestone {} failed for player {}",name,session.getPlayer().getUid(),failed);
      result = new RewardLedger.Claim(Retcode.SYSTEM_BUSY,List.of());
    }
    var response = Gameplay46.builder(session,name + "ScRsp");
    Gameplay46.set(response,command == 308 ? "PLDKENJLKME" : "retcode",result.retcode().getVal());
    if (command == 317 && result.retcode() == Retcode.SUCC) {
      var items = ItemList.newInstance(); result.items().forEach(item -> items.addItemList(item.toProto()));
      Gameplay46.set(response,"reward_list",session.getProtocol().clientSchema().parse("ItemList",
          session.getProtocol().translate("ItemList",items.toByteArray(),true)));
    }
    session.sendClientPacket(name + "ScRsp",response.build().toByteArray());
    return true;
  }
  private AvatarMilestones46() {}
}
