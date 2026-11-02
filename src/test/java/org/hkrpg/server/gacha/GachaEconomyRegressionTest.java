package org.hkrpg.server.gacha;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.gacha.*;
import org.junit.jupiter.api.*;

/** Draw tests use an isolated fake player and never debit an operator account. */
class GachaEconomyRegressionTest {
    private GachaRarityResources resources;
    @BeforeEach void setup() throws Exception { resources = new GachaRarityResources(); }
    @AfterEach void cleanup() { resources.close(); }

    private GachaBanner beginner() {
        return new GachaBanner().createBanner(4001, GachaType.Newbie, 0, 0, new int[0], new int[0]);
    }

    @Test void beginnerDiscountAndPityAreIndependentOfStandard() {
        var service = new GachaDrawRarityTest.DrawService();
        var player = new GachaDrawRarityTest.Resident();
        service.roll = 5000;
        player.history.getBannerInfo(GachaType.Normal).setPity5(88);
        service.getGachaBanners().put(4001, beginner());
        service.doPulls(player, 4001, 10);
        assertNotNull(player.response);
        assertEquals(0, player.response.getRetcode());
        assertEquals(8, player.charged);
        assertEquals(10, player.awarded.size());
        assertEquals(88, player.history.getBannerInfo(GachaType.Normal).getPity5());
        assertEquals(0, player.history.getCeilingNum());
    }

    @Test void beginnerHasFiftyDrawLimitAndGuaranteedCharacter() {
        var service = new GachaDrawRarityTest.DrawService();
        var player = new GachaDrawRarityTest.Resident();
        service.roll = 5000;
        service.getGachaBanners().put(4001, beginner());
        for (int i = 0; i < 5; i++) service.doPulls(player, 4001, 10);
        assertEquals(40, player.charged);
        assertEquals(50, player.awarded.size());
        assertTrue(player.awarded.stream().anyMatch(id -> GameData.getItemExcelMap().get(id).getRarityNum() == 5
                && GameData.getItemExcelMap().get(id).getItemMainType() == ItemMainType.AvatarCard));
        service.doPulls(player, 4001, 10);
        assertNotEquals(0, player.response.getRetcode());
        assertEquals(40, player.charged);
        assertEquals(50, player.awarded.size());
    }

    @Test void earlyBeginnerFiveStarDoesNotForceAnotherAtFifty() {
        var service = new GachaDrawRarityTest.DrawService();
        var player = new GachaDrawRarityTest.Resident();
        service.getGachaBanners().put(4001, beginner());
        service.roll = 1;
        service.doPulls(player, 4001, 10);
        assertTrue(player.history.isNewbieFiveStarObtained());
        service.roll = 5000;
        for (int i = 0; i < 4; i++) service.doPulls(player, 4001, 10);
        assertNotEquals(5, GameData.getItemExcelMap().get(player.awarded.getLast()).getRarityNum());
        var restored = new Gson().fromJson(new Gson().toJson(player.history), PlayerGachaInfo.class);
        assertEquals(50, restored.getNewbieDrawCount());
        assertTrue(restored.isNewbieFiveStarObtained());
    }

    @Test void beginnerRejectsSinglePullWithoutDebiting() {
        var service = new GachaDrawRarityTest.DrawService();
        var player = new GachaDrawRarityTest.Resident();
        service.getGachaBanners().put(4001, beginner());
        service.doPulls(player, 4001, 1);
        assertNotNull(player.response);
        assertNotEquals(0, player.response.getRetcode());
        assertEquals(0, player.charged);
        assertEquals(0, player.history.getNewbieDrawCount());
        assertTrue(player.awarded.isEmpty());
    }

    @Test void omittedWeaponRateUsesSeventyFiveButExplicitOperatorRateIsPreserved() {
        var missing = new Gson().fromJson("{\"gachaType\":\"WeaponUp\"}", GachaBanner.class);
        var explicit = new Gson().fromJson("{\"gachaType\":\"WeaponUp\",\"eventChance\":50}", GachaBanner.class);
        assertEquals(75, missing.getEventChance());
        assertEquals(50, explicit.getEventChance());
    }

    @Test void weaponBannerHasItsOwnRatesAndHardPity() {
        var service = new GachaDrawRarityTest.DrawService();
        var player = new GachaDrawRarityTest.Resident();
        service.getGachaBanners().put(3001, new GachaBanner().createBanner(3001, GachaType.WeaponUp,
                0, Integer.MAX_VALUE, new int[]{23000}, new int[]{21000}));
        service.roll = 80;
        service.doPulls(player, 3001, 1);
        assertEquals(5, GameData.getItemExcelMap().get(player.awarded.getFirst()).getRarityNum());
        assertEquals(75, service.getGachaBanners().get(3001).getEventChance());
        service.roll = 5000;
        player.history.getBannerInfo(GachaType.WeaponUp).setPity5(79);
        service.doPulls(player, 3001, 1);
        assertEquals(5, GameData.getItemExcelMap().get(player.awarded.getLast()).getRarityNum());
    }

    @Test void eventBaseRarityBoundariesMatchTheDisplayedRates() {
        for (var type : new GachaType[]{GachaType.AvatarUp, GachaType.WeaponUp}) {
            var service = new GachaDrawRarityTest.DrawService();
            boolean weapon = type == GachaType.WeaponUp;
            int fiveThreshold = weapon ? 80 : 60;
            int fourThreshold = weapon ? 9340 : 9490;
            int bannerId = weapon ? 3001 : 2001;
            service.getGachaBanners().put(bannerId, new GachaBanner().createBanner(bannerId, type,
                    0, Integer.MAX_VALUE, weapon ? new int[]{23000} : new int[]{1102},
                    weapon ? new int[]{21000} : new int[]{1002}));
            for (int[] sample : new int[][]{{fiveThreshold, 5}, {fiveThreshold + 1, 3},
                    {fourThreshold, 3}, {fourThreshold + 1, 4}}) {
                var player = new GachaDrawRarityTest.Resident();
                service.roll = sample[0];
                service.doPulls(player, bannerId, 1);
                assertEquals(sample[1], GameData.getItemExcelMap().get(player.awarded.getFirst()).getRarityNum(),
                        type + " roll " + sample[0]);
            }
        }
    }

    @Test void fourStarFeaturedGuaranteeSurvivesReload() {
        var service = new GachaDrawRarityTest.DrawService();
        var player = new GachaDrawRarityTest.Resident();
        var banner = new GachaBanner().createBanner(2001, GachaType.AvatarUp, 0, Integer.MAX_VALUE,
                new int[]{1102}, new int[]{1002}, 50);
        service.getGachaBanners().put(2001, banner);
        service.roll = 10000; service.eventRoll = 100;
        service.doPulls(player, 2001, 1);
        assertNotEquals(1002, player.awarded.getFirst());
        var saved = new Gson().fromJson(new Gson().toJson(player.history), PlayerGachaInfo.class);
        assertNotNull(saved.getBannerInfo(GachaType.AvatarUp));
        var reloaded = new GachaDrawRarityTest.Resident() {
            @Override public PlayerGachaInfo getGachaInfo() { return saved; }
        };
        service.doPulls(reloaded, 2001, 1);
        assertEquals(1002, reloaded.awarded.getFirst());
    }

    @Test void eventFourStarFallbackIncludesCharactersAndCones() {
        var service = new GachaDrawRarityTest.DrawService();
        for (var type : new GachaType[]{GachaType.AvatarUp, GachaType.WeaponUp}) {
            var banner = new GachaBanner().createBanner(2, type, 0, Integer.MAX_VALUE,
                    type == GachaType.AvatarUp ? new int[]{1102} : new int[]{23000},
                    type == GachaType.AvatarUp ? new int[]{1002} : new int[]{21000});
            var candidates = service.getPool(banner).base4();
            assertTrue(candidates.stream().anyMatch(id -> GameData.getItemExcelMap().get(id).getItemMainType() == ItemMainType.AvatarCard));
            assertTrue(candidates.stream().anyMatch(id -> GameData.getItemExcelMap().get(id).getItemMainType() == ItemMainType.Equipment));
        }
    }

    @Test void fiveStarFallbackCanAwardUpButFourStarFallbackMustArmItsGuarantee() {
        var service = new GachaDrawRarityTest.DrawService();
        service.eventRoll = 100;
        var banner = new GachaBanner().createBanner(2001, GachaType.AvatarUp, 0, Integer.MAX_VALUE,
                new int[]{1003}, new int[]{1001}, 50);
        service.getGachaBanners().put(2001, banner);
        for (int roll : new int[]{1, 10000}) {
            service.roll = roll;
            var player = new GachaDrawRarityTest.Resident();
            service.doPulls(player, 2001, 1);
            var history = player.history.getBannerInfo(GachaType.AvatarUp);
            if (roll == 1) {
                assertEquals(1003, player.awarded.getFirst());
                assertEquals(0, history.getFailedFeaturedItemPulls());
            } else {
                assertNotEquals(1001, player.awarded.getFirst());
                assertEquals(1, history.getFailedFeatured4Pulls());
            }
        }
    }
}
