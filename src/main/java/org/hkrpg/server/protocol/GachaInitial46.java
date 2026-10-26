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
import org.hkrpg.server.GachaWebRoutes;

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
    return snapshot(protocol, player, service, System.currentTimeMillis() / 1000L);
  }

  static DynamicMessage snapshot(ProtocolBridge protocol, Player player, GachaService service, long now) {
    var schema = protocol.clientSchema();
    var response = DynamicMessage.newBuilder(schema.message("GetGachaInfoScRsp"));
    synchronized (service) {
      synchronized (player) {
        for (var banner : new TreeMap<>(service.getGachaBanners()).values()) {
          if (!banner.isActive(now)) continue;
          var pool = service.getPool(banner);
          if (!pool.canPull()) continue;
          var info = DynamicMessage.newBuilder(schema.message("GachaInfo"));
          set(info, "gacha_id", banner.getId());
          // Fixed client's native SyncGachaPoolData consumes detail/history at wire 6/12.
          set(info, "detail_url", GachaWebRoutes.detailUrl(banner.getId()));
          set(info, "history_url", GachaWebRoutes.historyUrl(banner.getId()));
          if (banner.getGachaType() != GachaType.Normal) {
            set(info, "begin_time", (long) banner.getBeginTime());
            set(info, "end_time", (long) banner.getEndTime());
          }
          // Verified against the fixed CN 4.6 client's WriteTo + SyncGachaPoolData:
          // wire 5 -> PrizeItemDatas (headline); wire 10 -> DetailItemDatas (card previews).
          // Imported descriptor names are misleading here. Wire 7 belongs to selection data,
          // not an avatar-event marker. See docs/gacha-banner-display-4.6.md.
          int[] featured = service.getFeatured5(banner, pool);
          for (int id : featured) add(info, "ONIHOMCELJK", id);
          if (pool.permanent()) {
            // Permanent pools retain their existing five-star preview subset, not the full pool.
            for (int id : featured) add(info, "prize_item_list", id);
          } else {
            for (int id : pool.up4()) add(info, "prize_item_list", id);
          }
          if (banner.getId() == 1001) {
            var ceiling = DynamicMessage.newBuilder(schema.message("GachaCeiling"));
            set(ceiling, "ceiling_num", player.getGachaInfo().getCeilingNum());
            set(ceiling, "is_claimed", player.getGachaInfo().isCeilingClaimed(player));
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
