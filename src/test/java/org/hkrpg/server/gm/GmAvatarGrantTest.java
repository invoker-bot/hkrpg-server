package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.BasePacket;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.Test;

class GmAvatarGrantTest {
  @Test
  void appliesAndPersistsEidolonsAfterBindingThePlayersExistingPath() throws Exception {
    var gson = new Gson();
    var item = gson.fromJson("{\"ID\":1001,\"ItemMainType\":\"AvatarCard\"}", ItemExcel.class);
    var excel =
        gson.fromJson("{\"AvatarID\":1001,\"MaxRank\":6,\"MaxPromotion\":6}", AvatarExcel.class);
    var oldItem = GameData.getItemExcelMap().get(1001);
    var oldAvatar = GameData.getAvatarExcelMap().get(1001);
    GameData.getItemExcelMap().add(item);
    GameData.getAvatarExcelMap().add(excel);
    try {
      var savedRank = new AtomicInteger(-1);
      var actor =
          new GameAvatar(excel) {
            @Override
            public void save() {
              savedRank.set(getRank());
            }
          };
      var player =
          new Player() {
            @Override
            public boolean addAvatar(GameAvatar avatar) {
              avatar.setOwner(this);
              // The same rebinding used by AvatarStorage for default multiple-path actors.
              avatar.setMultiPath(new AvatarMultiPath(this, excel));
              avatar.save();
              return true;
            }

            @Override
            public void sendPacket(BasePacket packet) {}
          };
      var backend = new LiveGmBackend(id -> actor);
      var method = LiveGmBackend.class.getDeclaredMethod("grant", Player.class, Operation.class);
      method.setAccessible(true);
      var op =
          new Operation(
              UUID.randomUUID().toString(),
              "grant",
              10001,
              1001,
              1,
              20,
              2,
              null,
              null,
              null,
              null,
              null,
              null);
      var result = (Result) method.invoke(backend, player, op);
      assertEquals("completed", result.state());
      assertEquals(20, actor.getLevel());
      assertEquals(
          2, actor.getRank(), "Binding the stored path must not discard the requested eidolons");
      assertEquals(2, savedRank.get(), "Final path properties must be saved after rebinding");
    } finally {
      if (oldItem == null) GameData.getItemExcelMap().keySet().remove(1001);
      else GameData.getItemExcelMap().add(oldItem);
      if (oldAvatar == null) GameData.getAvatarExcelMap().keySet().remove(1001);
      else GameData.getAvatarExcelMap().add(oldAvatar);
    }
  }
}
