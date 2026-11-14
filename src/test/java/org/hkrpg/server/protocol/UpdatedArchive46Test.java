package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.EquipmentExcel;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The display-wall flow requests an updated archive after opening the selector. */
class UpdatedArchive46Test {
  static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
    var f = owner.getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  static DynamicMessage query(PlayerReturnInitial46Test.Session s, byte[] request) throws Exception {
    assertTrue(VersionedPacketHandler.handle(s, 2305, request));
    var packet = s.nativePackets.getLast();
    assertEquals(2375, packet.cmdId());
    return s.bridge.clientSchema().parse("GetUpdatedArchiveDataScRsp", packet.body());
  }

  @Test void emptyArchiveHasTheRequiredContainerAndDoesNotInventProgress() throws Exception {
    var s = new PlayerReturnInitial46Test.Session();
    var before = new Gson().toJson(s.player.getGameplay());
    var response = query(s, new byte[0]);
    assertEquals(0, field(response, "retcode"));
    assertTrue(response.hasField(response.getDescriptorForType().findFieldByName("archive_data")));
    var data = (DynamicMessage) field(response, "archive_data");
    assertTrue(data.getAllFields().isEmpty());
    assertTrue(data.getUnknownFields().asMap().isEmpty());
    assertEquals(before, new Gson().toJson(s.player.getGameplay()));
    assertEquals(2375, s.bridge.clientResponseCommand(2305));
  }

  @Test void actualOwnedAvatarsAndLightConesAreReportedOnceAndOtherOwnersExcluded()
      throws Exception {
    var s = new PlayerReturnInitial46Test.Session();
    set(s.player, Player.class, "uid", 10001);
    var other = new Player();
    set(other, Player.class, "uid", 10002);
    var avatar = new GameAvatar();
    set(avatar, GameAvatar.class, "avatarId", 1202);
    avatar.setOwner(s.player);
    s.player.getAvatars().getAvatars().put(1202, avatar);
    var foreign = new GameAvatar();
    set(foreign, GameAvatar.class, "avatarId", 1203);
    foreign.setOwner(other);
    s.player.getAvatars().getAvatars().put(1203, foreign);

    var gson = new Gson();
    var definition = gson.fromJson("{\"ID\":20000,\"ItemMainType\":\"Equipment\"}", ItemExcel.class);
    definition.setEquipmentExcel(gson.fromJson("{\"EquipmentID\":20000}", EquipmentExcel.class));
    var previous = GameData.getItemExcelMap().get(20000);
    GameData.getItemExcelMap().add(definition);
    try {
      for (int i = 0; i < 2; i++) {
        var item = new GameItem(definition);
        item.setOwner(s.player);
        s.player.getInventory().getItems().put(item.getInternalUid(), item);
      }
      var foreignItem = new GameItem(definition);
      foreignItem.setOwner(other);
      s.player.getInventory().getItems().put(99, foreignItem);
      var data = (DynamicMessage) field(query(s, new byte[0]), "archive_data");
      assertEquals(List.of(1202), field(data, "archive_avatar_id_list"));
      assertEquals(List.of(20000), field(data, "archive_equipment_id_list"));
      assertEquals(List.of(), field(data, "kill_monster_list"));
      assertEquals(List.of(), field(data, "relic_list"));
    } finally {
      GameData.getItemExcelMap().keySet().remove(20000);
      if (previous != null) GameData.getItemExcelMap().add(previous);
    }
  }

  @Test void malformedOrUnexpectedFieldsAreRejectedWithoutChangingState() throws Exception {
    for (byte[] request : List.of(new byte[] {8, 1}, new byte[] {(byte) 0x80})) {
      var s = new PlayerReturnInitial46Test.Session();
      var before = new Gson().toJson(s.player.getGameplay());
      var response = query(s, request);
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(response, "retcode"));
      assertEquals(before, new Gson().toJson(s.player.getGameplay()));
    }
  }

  @Test void inactiveSessionDoesNotReceiveAnArchive() throws Exception {
    var s = new PlayerReturnInitial46Test.Session();
    s.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(s, 2305, new byte[0]));
    assertTrue(s.nativePackets.isEmpty());
  }
}
