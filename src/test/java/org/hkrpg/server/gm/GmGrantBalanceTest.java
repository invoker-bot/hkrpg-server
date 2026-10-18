package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.BasePacket;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.Test;

class GmGrantBalanceTest {
  @Test
  void cappedCurrencyReportsActualDeltaInsteadOfRequestedAmount() throws Exception {
    var excel = new Gson().fromJson("{\"ID\":1,\"ItemMainType\":\"Virtual\"}", ItemExcel.class);
    var previous = GameData.getItemExcelMap().get(1);
    GameData.getItemExcelMap().add(excel);
    try {
      var player =
          new Player() {
            private final Inventory delivery =
                new Inventory(this) {
                  @Override
                  public List<GameItem> addItems(List<GameItem> items, boolean showHint) {
                    for (var item : items) getPlayer().addHCoin(item.getCount());
                    return items;
                  }
                };

            @Override
            public Inventory getInventory() {
              return delivery;
            }

            @Override
            public String getName() {
              return "GM balance test";
            }

            @Override
            public void sendPacket(BasePacket packet) {}
          };
      var balance = Player.class.getDeclaredField("hcoin");
      balance.setAccessible(true);
      balance.setInt(player, Integer.MAX_VALUE - 2);
      var grant = LiveGmBackend.class.getDeclaredMethod("grant", Player.class, Operation.class);
      grant.setAccessible(true);
      var op =
          new Operation(
              UUID.randomUUID().toString(),
              "grant",
              10001,
              1,
              10,
              0,
              0,
              null,
              null,
              null,
              null,
              null,
              null);
      var result = (Result) grant.invoke(new LiveGmBackend(), player, op);
      assertEquals(Integer.MAX_VALUE, player.getHcoin());
      assertEquals("partial", result.state());
      assertTrue(result.output().getFirst().contains("实际发放 2"), result.output().toString());
    } finally {
      if (previous == null) GameData.getItemExcelMap().keySet().remove(1);
      else GameData.getItemExcelMap().add(previous);
    }
  }
}
