package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** Command and shop field tags cross-checked against the installed CN 4.6 native client. */
public final class DiceCombat46 {
  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental()) return false;
    String name = null;
    for (var request :
        List.of("GetDiceCombatSystemDataCsReq", "GetDiceCombatShopDataCsReq", "BuyShopGoodCsReq"))
      if (command == s.getProtocol().clientCommand(request)) name = request;
    if (name == null) return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    synchronized (s.getPlayer()) {
      var code = Retcode.REQ_PARA_INVALID;
      DynamicMessage req = null;
      if (MatchThree46.wireValid(s.getProtocol().clientSchema().message(name), bytes, 0)) {
        req = s.getProtocol().clientSchema().parse(name, bytes);
        code = Retcode.SUCC;
      }
      var catalog = DiceCombatCatalog.read();
      if (code == Retcode.SUCC && catalog == null) code = Retcode.REWARD_CONFIG_NOT_EXIST;
      if (code == Retcode.SUCC
          && (!DiceCombatShop.valid(s.getPlayer(), catalog)
              || !DicePveMissionReports.valid(s.getPlayer()))) code = Retcode.PLAYER_DATA_ERROR;
      if (name.equals("GetDiceCombatSystemDataCsReq")) {
        var out = builder(s, "GetDiceCombatSystemDataScRsp");
        set(out, "retcode", code.getVal());
        if (code == Retcode.SUCC) system(s, catalog, out);
        s.sendClientPacket("GetDiceCombatSystemDataScRsp", out.build().toByteArray());
      } else if (name.equals("GetDiceCombatShopDataCsReq")) {
        var out = builder(s, "GetDiceCombatShopDataScRsp");
        set(out, "retcode", code.getVal());
        if (code == Retcode.SUCC) shops(s, catalog, out);
        s.sendClientPacket("GetDiceCombatShopDataScRsp", out.build().toByteArray());
      } else {
        if (code == Retcode.SUCC) {
          var result =
              DiceCombatShop.buy(
                  s.getPlayer(),
                  catalog,
                  number(req, "MCDNNFACGPE"),
                  number(req, "KDMNGCLJDOM"),
                  Instant.now());
          code = result.code();
          if (code == Retcode.SUCC) {
            var added = builder(s, "AddItemRsp");
            set(
                added,
                catalog.avatars().containsKey(result.item()) ? "avatar_id" : "CLEKAKLPNBP",
                result.item());
            set(added, "unlock_time", result.time());
            set(added, "reward", items(s, result.granted()));
            s.sendClientPacket("AddItemRsp", added.build().toByteArray());
          }
        }
        var out = builder(s, "BuyShopGoodScRsp");
        set(out, "retcode", code.getVal());
        if (code == Retcode.SUCC) shops(s, catalog, out);
        s.sendClientPacket("BuyShopGoodScRsp", out.build().toByteArray());
      }
    }
    return true;
  }

  static void system(GameSession s, DiceCombatCatalog catalog, DynamicMessage.Builder out) {
    var state = s.getPlayer().getGameplay().getDiceCombat();
    state.getPveWins().entrySet().stream()
        .filter(e -> e.getValue().isApplied())
        .map(java.util.Map.Entry::getKey)
        .sorted()
        .forEach(id -> add(out, "OHFIIBEIPJE", id));
    new TreeMap<>(state.getAvatars())
        .forEach(
            (id, owned) -> {
              if (owned == null
                  || owned.getId() != id
                  || owned.getLevel() < 1
                  || owned.getLevel() > 3
                  || owned.getTime() <= 0
                  || !catalog.avatars().containsKey(id)
                  || owned.getDice() == null
                  || owned.getDice().isEmpty()
                  || owned.getDice().stream().anyMatch(d -> !catalog.dice().contains(d))) return;
              add(out, "avatar_list", avatar(s, catalog, id));
            });
    new TreeMap<>(state.getDice())
        .forEach(
            (id, time) -> {
              if (!catalog.dice().contains(id) || time == null || time <= 0) return;
              var row = builder(s, "PKENAGKIECB");
              set(row, "CLEKAKLPNBP", id);
              set(row, "unlock_time", time);
              add(out, "KCNEKEGGAGG", row.build());
            });
  }

  static DynamicMessage avatar(GameSession s, DiceCombatCatalog catalog, int id) {
    var owned = s.getPlayer().getGameplay().getDiceCombat().getAvatars().get(id);
    var row = builder(s, "DiceCombatAvatar");
    set(row, "dice_avatar_id", id);
    set(row, "level", owned.getLevel());
    set(row, "unlock_time", owned.getTime());
    catalog.avatars().get(id).wireSlots(owned.getDice()).forEach(d -> add(row, "dice_id_list", d));
    return row.build();
  }

  static void shops(GameSession s, DiceCombatCatalog catalog, DynamicMessage.Builder out) {
    if (!catalog.available(s.getPlayer(), Instant.now())) return;
    new TreeMap<>(catalog.shops())
        .forEach(
            (id, shop) -> {
              var row = builder(s, "NJEBDKOJNGA");
              set(row, "shop_id", id);
              for (int goodId : shop.goods()) {
                var good = catalog.goods().get(goodId);
                if (!good.unlocked(s.getPlayer())) continue;
                var item = builder(s, "FLDKOELBPGA");
                set(item, "KDMNGCLJDOM", goodId);
                set(item, "item_id", good.item());
                var receipt =
                    s.getPlayer().getGameplay().getDiceCombat().getPurchases().get(goodId);
                set(
                    item,
                    "DOFNEAPHMKH",
                    receipt != null && receipt.isGranted()
                        || s.getPlayer().getInventory().hasItem(good.item(), 1));
                var cost = builder(s, "ItemCostData");
                new TreeMap<>(good.costs())
                    .forEach(
                        (currency, count) -> {
                          var pile = builder(s, "PileItem");
                          set(pile, "item_id", currency);
                          set(pile, "item_num", count);
                          var entry = builder(s, "ItemCost");
                          set(entry, "pile_item", pile.build());
                          add(cost, "item_list", entry.build());
                        });
                set(item, "cost", cost.build());
                add(row, "CNFFGBMPCHD", item.build());
              }
              add(out, "OPNFOBJOPCB", row.build());
            });
  }

  private DiceCombat46() {}
}
