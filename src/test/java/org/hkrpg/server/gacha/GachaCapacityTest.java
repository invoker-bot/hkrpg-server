package org.hkrpg.server.gacha;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.gacha.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.inventory.tabs.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.DoGachaScRspOuterClass.DoGachaScRsp;
import emu.lunarcore.server.packet.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real inventory delivery in a small isolated bag; tickets and pity must survive rejected draws. */
class GachaCapacityTest {
    GachaRarityResources resources;
    final Map<Integer, ItemExcel> previous = new HashMap<>();

    @BeforeEach void resources() throws Exception {
        resources = new GachaRarityResources();
        for (int id : List.of(101, 102, 251, 252)) {
            previous.put(id, GameData.getItemExcelMap().get(id));
            GameData.getItemExcelMap().add(new Gson().fromJson("{\"ID\":" + id
                + ",\"ItemMainType\":\"Material\",\"PileLimit\":999999}", ItemExcel.class));
        }
    }

    @AfterEach void restore() {
        previous.forEach((id, row) -> {
            if (row == null) GameData.getItemExcelMap().keySet().remove(id);
            else GameData.getItemExcelMap().add(row);
        });
        resources.close();
    }

    static class Resident extends Player {
        final PlayerGachaInfo history = new PlayerGachaInfo();
        DoGachaScRsp response;
        @Override public PlayerGachaInfo getGachaInfo() { return history; }
        @Override public void save() {}
        @Override public void sendPacket(BasePacket packet) {
            if (packet.getData() instanceof DoGachaScRsp data) response = data;
        }
    }

    GachaBanner banner(GachaType type) {
        return new GachaBanner().createBanner(199968, type, 0, Integer.MAX_VALUE,
            type == GachaType.AvatarUp ? new int[] {1102}
                : type == GachaType.WeaponUp ? new int[] {23000} : new int[0],
            type == GachaType.AvatarUp ? new int[] {1002}
                : type == GachaType.WeaponUp ? new int[] {21000} : new int[0]);
    }

    GachaService service(GachaType type) {
        var service = new GachaService(null) {
            @Override public void watch() {}
            @Override public int randomRange(int min, int max) {
                return max == 10000 ? 5000 : max == 100 ? 100 : max == 2 ? 2 : min;
            }
            @Override public int getRandom(int[] values) { return values[0]; }
        };
        service.replaceBanners(List.of(banner(type)));
        return service;
    }

    Resident player(int capacity, GachaType type) {
        var player = new Resident();
        player.getInventory().createTab(InventoryTabType.EQUIPMENT, new EquipInventoryTab(capacity));
        assertTrue(player.getInventory().addItem(type.getCostItem(), 100));
        return player;
    }

    int tickets(Resident player, GachaType type) {
        return player.getInventory().getMaterialByItemId(type.getCostItem()).getCount();
    }

    @ParameterizedTest @EnumSource(value = GachaType.class, names = {"Normal", "Newbie", "AvatarUp", "WeaponUp"})
    void tenPullRequiresRoomForTheWholeBatchBeforeTicketsOrPityChange(GachaType type) {
        var service = service(type);
        for (int capacity : new int[] {1, 9}) {
            var player = player(capacity, type);
            String before = new Gson().toJson(player.history);
            service.doPulls(player, 199968, 10);
            assertNotNull(player.response);
            assertEquals(Retcode.EQUIPMENT_EXCEED_LIMIT.getVal(), player.response.getRetcode());
            assertEquals(100, tickets(player, type));
            assertEquals(before, new Gson().toJson(player.history));
            assertEquals(0, player.getInventory().getTab(InventoryTabType.EQUIPMENT).getSize());
            assertEquals(0, player.response.getGachaItemList().length());
        }
    }

    @Test void tenFreeSlotsReceiveEveryReportedConeAndCannotBeOverdrawn() {
        var service = service(GachaType.WeaponUp);
        var player = player(10, GachaType.WeaponUp);
        service.doPulls(player, 199968, 10);
        assertEquals(0, player.response.getRetcode());
        assertEquals(90, tickets(player, GachaType.WeaponUp));
        assertEquals(10, player.response.getGachaItemList().length());
        assertEquals(10, player.getInventory().getTab(InventoryTabType.EQUIPMENT).getSize());
        var before = new Gson().toJson(player.history);
        service.doPulls(player, 199968, 1);
        assertEquals(Retcode.EQUIPMENT_EXCEED_LIMIT.getVal(), player.response.getRetcode());
        assertEquals(90, tickets(player, GachaType.WeaponUp));
        assertEquals(before, new Gson().toJson(player.history));
    }

    @Test void oneFreeSlotStillAllowsASinglePull() {
        var service = service(GachaType.WeaponUp);
        var player = player(1, GachaType.WeaponUp);
        service.doPulls(player, 199968, 1);
        assertEquals(0, player.response.getRetcode());
        assertEquals(99, tickets(player, GachaType.WeaponUp));
        assertEquals(1, player.getInventory().getTab(InventoryTabType.EQUIPMENT).getSize());
    }
}
