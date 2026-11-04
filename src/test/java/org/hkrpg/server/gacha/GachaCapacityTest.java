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
import org.junit.jupiter.params.provider.ValueSource;

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
        @Override public boolean addAvatar(emu.lunarcore.game.avatar.GameAvatar avatar) {
            if (getAvatars().hasAvatar(avatar.getAvatarId())) return false;
            getAvatars().getAvatars().put(avatar.getAvatarId(), avatar);
            return true;
        }
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
        return service(type, 5000, 100);
    }

    GachaService service(GachaType type, int roll, int event) {
        var service = new GachaService(null) {
            @Override public void watch() {}
            @Override public int randomRange(int min, int max) {
                return max == 10000 ? roll : max == 100 ? event : max == 2 ? 2 : min;
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

    void unchangedAfterRewardRejection(Resident player, GachaType type, String history) {
        assertNotNull(player.response);
        assertEquals(Retcode.ITEM_REWARD_EXCEED_LIMIT.getVal(), player.response.getRetcode());
        assertEquals(100, tickets(player, type));
        assertEquals(history, new Gson().toJson(player.history));
        assertEquals(0, player.getInventory().getTab(InventoryTabType.EQUIPMENT).getSize());
        assertEquals(0, player.response.getGachaItemList().length());
    }

    @ParameterizedTest @ValueSource(ints = {251, 252})
    void missingBonusStackNeedsAMaterialSlotBeforeSpending(int token) {
        var player = new Resident();
        player.getInventory().createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(1));
        assertTrue(player.getInventory().addItem(102, 100));
        if (token == 252) player.history.getBannerInfo(GachaType.WeaponUp).setPity4(9);
        var history = new Gson().toJson(player.history);
        service(GachaType.WeaponUp).doPulls(player, 199968, 1);
        unchangedAfterRewardRejection(player, GachaType.WeaponUp, history);
        assertNull(player.getInventory().getMaterialByItemId(token));
    }

    @ParameterizedTest @ValueSource(ints = {251, 252})
    void nearlyFullBonusStackCannotSilentlyClampTheReward(int token) {
        var player = player(10, GachaType.WeaponUp);
        assertTrue(player.getInventory().addItem(token, 999995));
        if (token == 252) player.history.getBannerInfo(GachaType.WeaponUp).setPity4(9);
        var history = new Gson().toJson(player.history);
        service(GachaType.WeaponUp).doPulls(player, 199968, 1);
        unchangedAfterRewardRejection(player, GachaType.WeaponUp, history);
        assertEquals(999995, player.getInventory().getMaterialByItemId(token).getCount());
    }

    @Test void tenPullNeedsSpaceForBothKindsOfBonusTogether() {
        var player = new Resident();
        player.getInventory().createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(2));
        assertTrue(player.getInventory().addItem(102, 100));
        var history = new Gson().toJson(player.history);
        service(GachaType.WeaponUp).doPulls(player, 199968, 10);
        unchangedAfterRewardRejection(player, GachaType.WeaponUp, history);
        assertNull(player.getInventory().getMaterialByItemId(251));
        assertNull(player.getInventory().getMaterialByItemId(252));
    }

    @Test void duplicateCharacterNeedsSpaceForItsEidolonAsWellAsStarlight() {
        int shard = 11002;
        previous.put(shard, GameData.getItemExcelMap().get(shard));
        GameData.getItemExcelMap().add(new Gson().fromJson(
            "{\"ID\":11002,\"ItemMainType\":\"Material\",\"ItemSubType\":\"Eidolon\",\"PileLimit\":999}", ItemExcel.class));
        var player = new Resident();
        player.getInventory().createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(2));
        assertTrue(player.getInventory().addItem(102, 100));
        assertTrue(player.getInventory().addItem(252, 1));
        player.getAvatars().getAvatars().put(1002,
            new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(1002)));
        player.history.getBannerInfo(GachaType.AvatarUp).setFailedFeatured4Pulls(1);
        player.history.getBannerInfo(GachaType.AvatarUp).setPity4(9);
        var history = new Gson().toJson(player.history);
        service(GachaType.AvatarUp).doPulls(player, 199968, 1);
        unchangedAfterRewardRejection(player, GachaType.AvatarUp, history);
        assertNull(player.getInventory().getMaterialByItemId(shard));
        assertEquals(1, player.getInventory().getMaterialByItemId(252).getCount());
    }

    @ParameterizedTest @EnumSource(value = GachaType.class, names = {"Normal", "Newbie", "AvatarUp", "WeaponUp"})
    void rejectedBatchPreservesTheSelectorBeginnerAndFeaturedCounters(GachaType type) {
        var player = new Resident();
        player.getInventory().createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(1));
        assertTrue(player.getInventory().addItem(type.getCostItem(), 100));
        if (type == GachaType.Newbie) for (int i = 0; i < 40; i++) player.history.recordNewbieDraw(false);
        player.history.getBannerInfo(type).setPity5(20);
        player.history.getBannerInfo(type).setFailedFeaturedItemPulls(1);
        player.history.getBannerInfo(type).setFailedFeatured4Pulls(1);
        var history = new Gson().toJson(player.history);
        service(type).doPulls(player, 199968, 10);
        unchangedAfterRewardRejection(player, type, history);
        assertTrue(player.getAvatars().getAvatars().isEmpty());
    }

    @Test void spendingTheLastTicketReleasesASlotForTheBonus() {
        var player = new Resident();
        player.getInventory().createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(1));
        assertTrue(player.getInventory().addItem(102, 1));
        service(GachaType.WeaponUp).doPulls(player, 199968, 1);
        assertEquals(0, player.response.getRetcode());
        assertNull(player.getInventory().getMaterialByItemId(102));
        assertEquals(20, player.getInventory().getMaterialByItemId(251).getCount());
        assertEquals(1, player.getInventory().getTab(InventoryTabType.EQUIPMENT).getSize());
    }

    @Test void anExistingBonusStackDoesNotRequireAnotherMaterialSlot() {
        var player = new Resident();
        player.getInventory().createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(3));
        assertTrue(player.getInventory().addItem(102, 100));
        assertTrue(player.getInventory().addItem(251, 999979));
        assertTrue(player.getInventory().addItem(252, 1));
        service(GachaType.WeaponUp).doPulls(player, 199968, 1);
        assertEquals(0, player.response.getRetcode());
        assertEquals(99, tickets(player, GachaType.WeaponUp));
        assertEquals(999999, player.getInventory().getMaterialByItemId(251).getCount());
    }

    @Test void tenPullChecksTheSumOfRepeatedBonusesAgainstThePileLimit() {
        var player = player(10, GachaType.WeaponUp);
        assertTrue(player.getInventory().addItem(251, 999830));
        var history = new Gson().toJson(player.history);
        service(GachaType.WeaponUp).doPulls(player, 199968, 10);
        unchangedAfterRewardRejection(player, GachaType.WeaponUp, history);
        assertEquals(999830, player.getInventory().getMaterialByItemId(251).getCount());
    }

    @Test void duplicatedCharacterBatchSwitchesToExcessRewardsAtSixTotalEidolons() {
        var player = player(10, GachaType.AvatarUp);
        var avatar = new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(1002));
        avatar.setRank(4);
        assertTrue(player.addAvatar(avatar));
        assertTrue(player.getInventory().addItem(11002, 1));
        service(GachaType.AvatarUp, 10000, 1).doPulls(player, 199968, 10);
        assertEquals(0, player.response.getRetcode());
        assertEquals(90, tickets(player, GachaType.AvatarUp));
        assertEquals(2, player.getInventory().getMaterialByItemId(11002).getCount());
        assertEquals(188, player.getInventory().getMaterialByItemId(252).getCount());
        int transferred = 0;
        for (var row : player.response.getGachaItemList()) transferred += row.getTransferItemList().getItemList().length();
        assertEquals(1, transferred);
    }

    @ParameterizedTest @ValueSource(ints = {1, 10000})
    void aNewCharacterRepeatedWithinTheBatchHasOnlyOneNewFlagAndSixEidolons(int roll) {
        var player = player(10, GachaType.AvatarUp);
        int avatar = roll == 1 ? 1102 : 1002;
        service(GachaType.AvatarUp, roll, 1).doPulls(player, 199968, 10);
        assertEquals(0, player.response.getRetcode());
        assertEquals(1, player.getAvatars().getAvatarCount());
        assertEquals(6, player.getInventory().getMaterialByItemId(avatar + 10000).getCount());
        assertEquals(roll == 1 ? 540 : 108, player.getInventory().getMaterialByItemId(252).getCount());
        int newCount = 0, transferred = 0;
        for (var row : player.response.getGachaItemList()) {
            if (row.getIsNew()) newCount++;
            transferred += row.getTransferItemList().getItemList().length();
        }
        assertEquals(1, newCount);
        assertEquals(6, transferred);
    }

    @ParameterizedTest @ValueSource(ints = {251, 252})
    void missingBonusDefinitionRejectsTheDrawBeforePayment(int token) {
        var player = player(10, GachaType.WeaponUp);
        if (token == 252) player.history.getBannerInfo(GachaType.WeaponUp).setPity4(9);
        GameData.getItemExcelMap().keySet().remove(token);
        var history = new Gson().toJson(player.history);
        service(GachaType.WeaponUp).doPulls(player, 199968, 1);
        unchangedAfterRewardRejection(player, GachaType.WeaponUp, history);
    }

    @Test void newlyCreatedBonusesAlsoNeedItemIdentifiers() throws Exception {
        var player = player(10, GachaType.WeaponUp);
        var counter = Inventory.class.getDeclaredField("nextInternalUid");
        counter.setAccessible(true);
        counter.setInt(player.getInventory(), Integer.MAX_VALUE - 1);
        var history = new Gson().toJson(player.history);
        service(GachaType.WeaponUp).doPulls(player, 199968, 1);
        unchangedAfterRewardRejection(player, GachaType.WeaponUp, history);
        assertEquals(Integer.MAX_VALUE - 1, counter.getInt(player.getInventory()));
    }

    @ParameterizedTest @ValueSource(ints = {251, 252})
    void aFullBagCannotFilterDrawRaritiesByKeepingOnlyOneBonusStack(int keptToken) {
        var player = new Resident();
        player.getInventory().createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(2));
        assertTrue(player.getInventory().addItem(102, 100));
        assertTrue(player.getInventory().addItem(keptToken, 1));
        var randomCalls = new java.util.concurrent.atomic.AtomicInteger();
        var service = new GachaService(null) {
            @Override public void watch() {}
            @Override public int randomRange(int min, int max) {
                randomCalls.incrementAndGet();
                return max == 10000 ? keptToken == 251 ? 5000 : 10000 : min;
            }
            @Override public int getRandom(int[] values) { return values[0]; }
        };
        service.replaceBanners(List.of(banner(GachaType.WeaponUp)));
        var history = new Gson().toJson(player.history);
        service.doPulls(player, 199968, 1);
        unchangedAfterRewardRejection(player, GachaType.WeaponUp, history);
        assertEquals(0, randomCalls.get(), "Capacity rejection must precede every random choice");
    }
}
