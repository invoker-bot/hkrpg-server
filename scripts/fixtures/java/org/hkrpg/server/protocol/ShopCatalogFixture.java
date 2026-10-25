package org.hkrpg.server.protocol;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.*;
import emu.lunarcore.game.shop.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Complete provided catalog audit in disposable in-memory players; never connects to production. */
public class ShopCatalogFixture {
  static final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  static class Resident extends Player {
    int notifications;
    final PlayerUnlockData unlocks = new PlayerUnlockData(this) { public void save() {} };
    public PlayerUnlockData getUnlocks() { return unlocks; }
    public String getName() { return "Shop catalog fixture"; }
    public void save() {}
    public void sendPacket(emu.lunarcore.server.packet.BasePacket p) {
      protocol.outbound(p.getCmdId(),p.getData()==null ? new byte[0] : p.getData().toByteArray());
      notifications++;
    }
    public boolean addAvatar(GameAvatar a) {
      a.setOwner(this); getAvatars().getAvatars().put(a.getAvatarId(),a); return true;
    }
  }
  public static void main(String[] args) throws Exception {
    LunarCore.getConfig().resourceDir = Path.of(args[0]).toAbsolutePath().toString();
    ResourceLoader.loadResources();
    var now = Instant.parse("2026-10-25T12:00:00Z");
    var rows = new ArrayList<Map<String,Object>>();
    int passed = 0;
    for (var shop : GameData.getShopExcelMap().values()) for (var goods : shop.getGoods().values()) {
      var result = new TreeMap<String,Object>();
      result.put("shop",shop.getId()); result.put("goods",goods.getId());
      int id = goods.itemAt(now); result.put("item",id);
      try {
        var p = new Resident();
        p.getUnlocks().getPhoneThemes().remove(id);
        for (var cost : goods.getCostList())
          if (cost.getCount() > 0 && !p.getInventory().addItem(cost.getId(),cost.getCount()))
            throw new IllegalStateException("Cannot stock currency " + cost.getId());
        var choices = GameData.getItemGiftPackExcelMap().stream().filter(g -> g.getID()==id)
            .sorted(Comparator.comparingInt(emu.lunarcore.data.excel.ItemGiftPackExcel::getGroupID))
            .map(g -> g.getRewardList().getFirst()).toList();
        var bought = new ShopService(null).buy(p,shop.getId(),goods.getId(),1,id,choices,now);
        if (bought.code() != emu.lunarcore.server.packet.Retcode.SUCC)
          throw new IllegalStateException("Buy rejected: " + bought.code());
        if (bought.items().isEmpty() || p.getGameplay().getShopPurchases().getPending()!=null)
          throw new IllegalStateException("Incomplete delivery");
        for (var cost : goods.getCostList()) if (cost.getCount()>0 && p.getInventory().hasItem(cost.getId(),cost.getCount())) {
          boolean alsoReward = bought.items().stream().anyMatch(i -> i.getItemId()==cost.getId());
          if (!alsoReward) throw new IllegalStateException("Currency not consumed: " + cost.getId());
        }
        result.put("notificationsEncoded",p.notifications);
        result.put("passed",true); passed++;
      } catch (Throwable e) { result.put("passed",false); result.put("error",e.toString()); }
      rows.add(result);
    }
    var report = Map.of("passed",passed==rows.size(),"total",rows.size(),"successful",passed,"rows",rows,
        "productionModified",false,"clientAccepted",false);
    Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report));
    System.out.println("SHOP_CATALOG="+passed+"/"+rows.size());
    if (passed!=rows.size()) System.exit(1);
  }
}
