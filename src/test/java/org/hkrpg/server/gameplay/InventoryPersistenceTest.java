package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.PlayerSyncScNotifyOuterClass.PlayerSyncScNotify;
import emu.lunarcore.server.packet.BasePacket;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real inventory indexes and notifications, with a rejected item write at the storage boundary. */
class InventoryPersistenceTest {
    final List<BasePacket> packets = new ArrayList<>();
    final Player player = new Player() {
        @Override public void save() {}
        @Override public void sendPacket(BasePacket packet) { packets.add(packet); }
    };
    final ItemExcel material = new Gson().fromJson(
        "{\"ID\":199968001,\"ItemMainType\":\"Material\",\"PileLimit\":999}", ItemExcel.class);
    final ItemExcel equipment = new Gson().fromJson(
        "{\"ID\":199968002,\"ItemMainType\":\"Equipment\"}", ItemExcel.class);
    final Map<Integer, ItemExcel> previous = new HashMap<>();

    @BeforeEach void resources() {
        for (var row : List.of(material, equipment)) {
            previous.put(row.getId(), GameData.getItemExcelMap().get(row.getId()));
            GameData.getItemExcelMap().add(row);
        }
    }

    @AfterEach void restore() {
        previous.forEach((id, row) -> {
            if (row == null) GameData.getItemExcelMap().keySet().remove(id);
            else GameData.getItemExcelMap().add(row);
        });
    }

    static class StoredItem extends GameItem {
        boolean fail;
        StoredItem(ItemExcel excel, int count) { super(excel, count); }
        @Override public void save() {
            if (fail) throw new IllegalStateException("Isolated rejected inventory write");
        }
    }

    StoredItem stock(ItemExcel excel, int count) {
        var item = new StoredItem(excel, count);
        assertTrue(player.getInventory().addItem(item));
        packets.clear();
        return item;
    }

    void intact(GameItem item, int count) {
        assertEquals(count, item.getCount());
        assertSame(item, player.getInventory().getItemByUid(item.getInternalUid()));
        var tab = player.getInventory().getTabByItemType(item.getItemMainType());
        assertTrue(java.util.stream.StreamSupport.stream(tab.spliterator(), false)
            .anyMatch(value -> value == item));
        if (item.getItemMainType() == ItemMainType.Material)
            assertSame(item, player.getInventory().getMaterialByItemId(item.getItemId()));
    }

    @ParameterizedTest @ValueSource(ints = {1, 5})
    void rejectedMaterialWritePreservesTheCountAndBothIndexesAndAllowsRetry(int amount) {
        var item = stock(material, 5);
        item.fail = true;
        assertThrows(IllegalStateException.class, () -> player.getInventory().removeItem(item, amount));
        intact(item, 5);
        assertTrue(packets.isEmpty(), "An unconfirmed debit must not be published");
        item.fail = false;
        assertTrue(player.getInventory().removeItem(item, amount));
        assertEquals(5 - amount, item.getCount());
    }

    @Test void rejectedEquipmentDeletionKeepsTheUniqueItemAndItsTab() {
        var item = stock(equipment, 1);
        item.fail = true;
        assertThrows(IllegalStateException.class, () -> player.getInventory().removeUniqueItem(item.getInternalUid(), 1));
        intact(item, 1);
        assertTrue(packets.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectedNewItemWriteCannotLeaveAnUnsavedItemInTheBag(boolean unique) {
        var item = new StoredItem(unique ? equipment : material, unique ? 1 : 5);
        item.fail = true;
        assertThrows(IllegalStateException.class, () -> player.getInventory().addItem(item));
        assertNull(player.getInventory().getItemByUid(item.getInternalUid()));
        assertEquals(0, player.getInventory().getTabByItemType(item.getItemMainType()).getSize());
        assertTrue(packets.isEmpty());
        item.fail = false;
        assertTrue(player.getInventory().addItem(item));
        intact(item, unique ? 1 : 5);
    }

    @Test void rejectedStackIncreaseCannotInventMaterialsAndRetryAddsOnlyOnce() {
        var item = stock(material, 5);
        item.fail = true;
        assertThrows(IllegalStateException.class, () -> player.getInventory().addItem(new StoredItem(material, 2)));
        intact(item, 5);
        assertTrue(packets.isEmpty());
        item.fail = false;
        assertTrue(player.getInventory().addItem(new StoredItem(material, 2)));
        intact(item, 7);
    }

    @Test void grantBatchPublishesEarlierConfirmedItemsWhenALaterWriteFails() {
        var first = new StoredItem(material, 5);
        var failed = new StoredItem(equipment, 1);
        failed.fail = true;
        assertThrows(IllegalStateException.class, () -> player.getInventory().addItems(List.of(first, failed), true));
        intact(first, 5);
        var syncs = packets.stream().map(BasePacket::getData)
            .filter(PlayerSyncScNotify.class::isInstance).map(PlayerSyncScNotify.class::cast).toList();
        assertEquals(1, syncs.size());
        assertEquals(1, syncs.getFirst().getMaterialList().length());
        assertEquals(0, syncs.getFirst().getEquipmentList().length());
        assertNull(player.getInventory().getItemByUid(failed.getInternalUid()));
        assertEquals(0, player.getInventory().getTabByItemType(ItemMainType.Equipment).getSize());
    }

    @Test void parameterBatchPublishesEarlierConfirmedDebitsWhenALaterWriteFails() {
        var first = stock(material, 5);
        var failed = stock(equipment, 1);
        failed.fail = true;
        assertThrows(IllegalStateException.class, () -> player.getInventory().removeItemsByParams(List.of(
            new ItemParam(material.getId(), 2),
            new ItemParam(ItemParam.ItemParamType.UNIQUE, failed.getInternalUid(), 1))));
        intact(first, 3);
        var syncs = packets.stream().map(BasePacket::getData)
            .filter(PlayerSyncScNotify.class::isInstance).map(PlayerSyncScNotify.class::cast).toList();
        assertEquals(1, syncs.size());
        assertEquals(1, syncs.getFirst().getMaterialList().length());
        assertEquals(3, syncs.getFirst().getMaterialList().get(0).getNum());
        assertEquals(0, syncs.getFirst().getDelEquipmentList().length());
        intact(failed, 1);
    }
}
