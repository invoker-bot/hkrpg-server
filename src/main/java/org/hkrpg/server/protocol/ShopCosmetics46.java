package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemSubType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.send.PacketGetPlayerBoardDataScRsp;
import java.io.IOException;
import java.util.TreeSet;

/** Owned shop collectibles, using the pinned 4.6 board and Pam skin fields. */
public final class ShopCosmetics46 {
  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (AvatarSkin46.handle(s, command, bytes)) return true;
    String request = switch (command) {
      case 2834 -> "GetPlayerBoardDataCsReq";
      case 2808 -> "SetPersonalCardCsReq";
      case 8137 -> "SelectPamSkinCsReq";
      default -> null;
    };
    if (request == null || !s.getProtocol().experimental()) return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    synchronized (s.getPlayer()) {
      var p = s.getPlayer();
      String response = request.replace("CsReq", "ScRsp");
      var out = builder(s, response);
      var schema = s.getProtocol().clientSchema();
      if (bytes == null || bytes.length > 128 || !Parkour46.wireValid(schema.message(request), bytes, 0))
        set(out, "retcode", Retcode.REQ_PARA_INVALID.getVal());
      else if (command == 2834) {
        var original = new PacketGetPlayerBoardDataScRsp(p);
        out.mergeFrom(s.getProtocol().translate(response, original.getData().toByteArray(), true));
        for (int id : personalCards(p)) add(out,"unlocked_personal_card_list",id);
        set(out,"current_personal_card_id",p.getGameplay().getShopPurchases().getPersonalCard());
      } else {
        var input = schema.parse(request,bytes);
        int id = (int) input.getField(input.getDescriptorForType().findFieldByNumber(1));
        boolean owned = command == 2808 ? personalCards(p).contains(id) : pamSkins(p).contains(id);
        if (!owned) set(out,"retcode",Retcode.ITEM_NOT_EXIST.getVal());
        else {
          if (command == 2808) p.getGameplay().getShopPurchases().personalCard(id);
          else p.setCurrentPamSkinId(id);
          p.save();
        }
        if (command == 2808) set(out,"current_personal_card_id",p.getGameplay().getShopPurchases().getPersonalCard());
        else {
          set(out,"set_skin",id);
          set(out,"cur_skin",currentPam(p));
        }
      }
      s.sendClientPacket(response,out.build().toByteArray());
    }
    return true;
  }

  public static TreeSet<Integer> personalCards(Player p) {
    var owned = new TreeSet<Integer>(); owned.add(253000);
    p.getInventory().getItems().values().stream()
        .filter(i -> i.getCount() > 0 && i.getExcel().getItemSubType() == ItemSubType.PersonalCard)
        .forEach(i -> owned.add(i.getItemId()));
    return owned;
  }

  public static int currentPam(Player p) {
    int id = p.getCurrentPamSkinId();
    return id == 0 || id == 25200 ? 252000 : id;
  }

  public static TreeSet<Integer> pamSkins(Player p) {
    var owned = new TreeSet<Integer>(); owned.add(252000);
    // Preserve the previously selected skin on existing operator saves.
    owned.add(currentPam(p));
    p.getInventory().getItems().values().stream()
        .filter(i -> i.getCount() > 0 && i.getExcel().getItemSubType() == ItemSubType.PamSkin)
        .forEach(i -> owned.add(i.getItemId()));
    return owned;
  }

  public static void purchased(Player p, java.util.List<emu.lunarcore.game.inventory.GameItem> items) {
    var s = p.getSession();
    if (s == null || !s.getProtocol().experimental()) return;
    for (var item : items) if (item.getExcel().getItemSubType() == ItemSubType.PamSkin) {
      var notify = builder(s,"UnlockPamSkinScNotify");
      set(notify,"pam_skin",item.getItemId());
      s.sendClientPacket("UnlockPamSkinScNotify",notify.build().toByteArray());
    }
  }
  private ShopCosmetics46() {}
}
