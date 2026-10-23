package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.gacha.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.inventory.Inventory;
import org.junit.jupiter.api.Test;

class GmBannerWindowTest {
  private GachaService service() {
    return new GachaService(null) { @Override public void watch() {} };
  }

  @Test
  void expiredAndFutureBannersAreAbsentFromTheClientCatalog() {
    var service = service();
    int now = (int) (System.currentTimeMillis() / 1000);
    service.getGachaBanners().put(2001, new GachaBanner().createBanner(
        2001, GachaType.AvatarUp, now - 100, now, new int[]{1102}, new int[]{1105}));
    service.getGachaBanners().put(2002, new GachaBanner().createBanner(
        2002, GachaType.AvatarUp, now + 3600, now + 7200, new int[]{1204}, new int[]{1105}));
    assertEquals(0, service.toProto(null).getGachaInfoList().length());
  }

  @Test
  void expiredPullIsRejectedBeforeReadingOrChargingInventory() {
    var service = service();
    service.getGachaBanners().put(2001, new GachaBanner().createBanner(
        2001, GachaType.AvatarUp, 1, 2, new int[]{1102}, new int[]{1105}));
    var sent = new java.util.ArrayList<emu.lunarcore.server.packet.BasePacket>();
    Player player = new Player() {
      @Override public Inventory getInventory() { fail("Expired banners must not read inventory"); return null; }
      @Override public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) { sent.add(packet); }
    };
    service.doPulls(player, 2001, 1);
    assertEquals(1, sent.size());
  }
}
