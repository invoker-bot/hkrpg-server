package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.gacha.GachaService;
import emu.lunarcore.game.gacha.GachaType;
import emu.lunarcore.game.player.Player;
import java.io.IOException;

/** Pinned CN 4.6 DoGachaScRsp field 4 updates NewbieGachaPoolData.DoGachaTime. */
final class GachaDraw46 {
  static byte[] enrich(ProtocolSchema schema, String name, byte[] body, Player player) {
    if (!"DoGachaScRsp".equals(name) || player == null || player.getSession() == null) return body;
    return enrich(schema, body, player, player.getServer().getGachaService());
  }

  static byte[] enrich(ProtocolSchema schema, byte[] body, Player player, GachaService service) {
    if (service == null) return body;
    try {
      var response = DynamicMessage.parseFrom(schema.message("DoGachaScRsp"), body);
      var type = response.getDescriptorForType();
      var idField = type.findFieldByName("gacha_id");
      var retcode = type.findFieldByName("retcode");
      if (idField == null || retcode == null || ((Number) response.getField(retcode)).intValue() != 0) return body;
      int id = ((Number) response.getField(idField)).intValue();
      var banner = service.getBannerSnapshot().stream().filter(row -> row.getId() == id).findFirst().orElse(null);
      if (banner == null || banner.getGachaType() != GachaType.Newbie) return body;
      // _CmdDoGachaScRsp: wire 4 -> +0x2c -> newbie +0x90; wire 6 is the ordinary counter.
      return response.toBuilder().setField(type.findFieldByNumber(4), player.getGachaInfo().getNewbieDrawCount())
          .build().toByteArray();
    } catch (IOException invalid) { throw new ProtocolException("Malformed gacha draw response"); }
  }

  private GachaDraw46() {}
}
