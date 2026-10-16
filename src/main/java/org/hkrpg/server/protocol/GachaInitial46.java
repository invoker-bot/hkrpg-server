package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.gacha.GachaService;
import emu.lunarcore.game.gacha.GachaType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.TreeMap;

/** Read-only banner snapshot; pulling, selection and payment retain their existing limitations. */
final class GachaInitial46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("GetGachaInfoCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var response = bytes.length == 0
        ? snapshot(session.getProtocol(), session.getPlayer(), session.getServer().getGachaService())
        : DynamicMessage.newBuilder(session.getProtocol().clientSchema().message("GetGachaInfoScRsp"))
            .setField(session.getProtocol().clientSchema().message("GetGachaInfoScRsp").findFieldByName("retcode"),
                Retcode.REQ_PARA_INVALID.getVal()).build();
    session.sendClientPacket("GetGachaInfoScRsp", response.toByteArray());
    return true;
  }

  static DynamicMessage snapshot(ProtocolBridge protocol, Player player, GachaService service) {
    var schema = protocol.clientSchema();
    var response = DynamicMessage.newBuilder(schema.message("GetGachaInfoScRsp"));
    synchronized (service) {
      synchronized (player) {
        for (var banner : new TreeMap<>(service.getGachaBanners()).values()) {
          var info = DynamicMessage.newBuilder(schema.message("GachaInfo"));
          set(info, "gacha_id", banner.getId());
          if (banner.getGachaType() != GachaType.Normal) {
            set(info, "begin_time", (long) banner.getBeginTime());
            set(info, "end_time", (long) banner.getEndTime());
          }
          // Honey fe00bdb Common/Data/Custom/BannersConfig.ToInfo separates five-star display
          // items (10), four-star rate-up items (5), and the avatar-event detail marker (7).
          // The old up_info list combines pools and must not be renamed to any of those fields.
          int[] featured = banner.getId() == 1001 ? service.getDefaultFeaturedIds() : banner.getRateUpItems5();
          if (featured != null) for (int id : featured) add(info, "prize_item_list", id);
          if (banner.getGachaType() != GachaType.Normal && banner.getRateUpItems4() != null)
            for (int id : banner.getRateUpItems4()) add(info, "ONIHOMCELJK", id);
          if (banner.getGachaType() == GachaType.AvatarUp) add(info, "item_detail_list", 11);
          if (banner.getId() == 1001) {
            var ceiling = DynamicMessage.newBuilder(schema.message("GachaCeiling"));
            set(ceiling, "ceiling_num", player.getGachaInfo().getCeilingNum());
            set(ceiling, "is_claimed", player.getGachaInfo().isCeilingClaimed());
            for (int id : service.getYellowAvatars()) {
              var avatar = player.getAvatarById(id);
              var row = DynamicMessage.newBuilder(schema.message("GachaCeilingAvatar"));
              set(row, "avatar_id", id);
              set(row, "repeated_cnt", avatar == null ? 0 : avatar.getRank());
              add(ceiling, "avatar_list", row.build());
            }
            set(info, "gacha_ceiling", ceiling.build());
          }
          add(response, "gacha_info_list", info.build());
        }
      }
    }
    return response.build();
  }

  private GachaInitial46() {}
}
