package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.BasePacket;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class LegacyEquipmentLoadTest {
    @Test void equalStoredAvatarIdsRebindTheRuntimeOwnerWithoutAnotherPersistentChange() {
        var gson = new Gson();
        var item = new GameItem(gson.fromJson(
            "{\"ID\":199968003,\"ItemMainType\":\"Equipment\"}", ItemExcel.class));
        var excel = gson.fromJson("{\"AvatarID\":199968004}", AvatarExcel.class);
        var id = new ObjectId();
        var first = new GameAvatar(excel, id);
        var reloaded = new GameAvatar(excel, new ObjectId(id.toHexString()));
        assertTrue(item.setEquipAvatar(first));
        assertFalse(item.setEquipAvatar(reloaded));
        assertSame(reloaded, item.getEquipAvatar());
        assertTrue(item.setEquipAvatar(null));
        assertFalse(item.setEquipAvatar(null));
    }

    @Test void clearingAnUnassignedItemIsAnIdempotentOperation() {
        var item = new GameItem(new Gson().fromJson(
            "{\"ID\":199968003,\"ItemMainType\":\"Equipment\"}", ItemExcel.class));
        assertFalse(item.setEquipAvatar(null));
        assertFalse(item.setEquipAvatar(null));
        assertNull(item.getEquipAvatarId());
    }

    @Test void missingLegacyAvatarDoesNotAbortInventoryLoadingOrLoseTheCone() throws Exception {
        int id = 199968003;
        var previous = GameData.getItemExcelMap().get(id);
        var excel = new Gson().fromJson(
            "{\"ID\":" + id + ",\"ItemMainType\":\"Equipment\"}", ItemExcel.class);
        GameData.getItemExcelMap().add(excel);
        try {
            var player = new Player() {
                @Override public void save() {}
                @Override public void sendPacket(BasePacket packet) {}
            };
            var item = new GameItem(excel) { @Override public void save() {} };
            item.setOwner(player);
            field(item, "id", new ObjectId());
            field(item, "equipAvatarExcelId", 199968999);
            assertTrue(player.getInventory().loadItem(item));
            assertSame(item, player.getInventory().getItemByUid(item.getInternalUid()));
            assertEquals(1, item.getCount());
            assertNull(item.getEquipAvatarId());
            assertNull(item.getEquipAvatar());
            assertEquals(0, item.getEquipAvatarExcelId());
            assertFalse(item.setEquipAvatar(null));
        } finally {
            if (previous == null) GameData.getItemExcelMap().keySet().remove(id);
            else GameData.getItemExcelMap().add(previous);
        }
    }

    private static void field(GameItem item, String name, Object value) throws Exception {
        var field = GameItem.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(item, value);
    }
}
