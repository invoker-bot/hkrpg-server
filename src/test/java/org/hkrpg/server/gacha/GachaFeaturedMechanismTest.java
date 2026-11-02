package org.hkrpg.server.gacha;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.gacha.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Public Warp rules: five-star fallback includes UP; four-star misses exclude UP. */
class GachaFeaturedMechanismTest {
    GachaRarityResources resources;
    @BeforeEach void setup() throws Exception { resources = new GachaRarityResources(); }
    @AfterEach void cleanup() { resources.close(); }

    static class Choices extends GachaDrawRarityTest.DrawService {
        int itemIndex, itemType = 1;
        @Override public int randomRange(int min, int max) {
            return min == 1 && max == 2 ? itemType : super.randomRange(min, max);
        }
        @Override public int getRandom(int[] values) { return values[Math.floorMod(itemIndex, values.length)]; }
    }

    GachaBanner banner(GachaType type) {
        return new GachaBanner().createBanner(type == GachaType.AvatarUp ? 2001 : 3001, type,
                0, Integer.MAX_VALUE, type == GachaType.AvatarUp ? new int[]{1102} : new int[]{23001},
                type == GachaType.AvatarUp ? new int[]{1001,1002,1008} : new int[]{21000,21001,21002});
    }

    int draw(Choices service, GachaDrawRarityTest.Resident player, GachaBanner banner) {
        int before = player.awarded.size();
        service.doPulls(player, banner.getId(), 1);
        assertNotNull(player.response);
        assertEquals(0, player.response.getRetcode());
        assertEquals(before + 1, player.awarded.size());
        return player.awarded.getLast();
    }

    void exactFiveStarShare(GachaType type, int expectedUpAwards) {
        var service = new Choices();
        var banner = banner(type);
        service.getGachaBanners().put(banner.getId(), banner);
        var player = new GachaDrawRarityTest.Resident();
        var history = player.history.getBannerInfo(type);
        // Enumerate 100 first-stage choices x all 8 possible five-star fallback choices.
        // Each draw starts without an UP guarantee. No Monte Carlo tolerance is used.
        int upAwards = 0;
        for (int event = 1; event <= 100; event++) for (int item = 0; item < 8; item++) {
            service.eventRoll = event;
            service.itemIndex = item;
            history.setFailedFeaturedItemPulls(0);
            boolean up = draw(service, player, banner) == banner.getRateUpItems5()[0];
            if (up) upAwards++;
            assertEquals(up ? 0 : 1, history.getFailedFeaturedItemPulls());
        }
        assertEquals(expectedUpAwards, upAwards);
    }

    @Test void characterFiveStarsIncludeTheExtraUniformFallbackChance() {
        exactFiveStarShare(GachaType.AvatarUp, 450); // 50% + 50% / 8 = 56.25%.
    }

    @Test void lightConeFiveStarsIncludeTheExtraUniformFallbackChance() {
        exactFiveStarShare(GachaType.WeaponUp, 625); // 75% + 25% / 8 = 78.125%.
    }

    void exactFourStarShare(GachaType type, int expectedPercent) {
        var service = new Choices(); service.roll = 10000;
        var banner = banner(type);
        service.getGachaBanners().put(banner.getId(), banner);
        var player = new GachaDrawRarityTest.Resident();
        var history = player.history.getBannerInfo(type);
        var up = service.getPool(banner).up4();
        int upAwards = 0;
        // Exercise every first-stage outcome and both fallback types, including positions
        // that used to contain featured items in the original character/light-cone lists.
        for (int event = 1; event <= 100; event++) for (int itemType = 1; itemType <= 2; itemType++) {
            for (int item = 0; item < 38; item++) {
                service.eventRoll = event; service.itemType = itemType; service.itemIndex = item;
                history.setPity5(0); history.setFailedFeatured4Pulls(0);
                boolean wonUp = up.contains(draw(service, player, banner));
                if (wonUp) upAwards++;
                assertEquals(event <= expectedPercent, wonUp, "Four-star fallback must not award UP");
                assertEquals(wonUp ? 0 : 1, history.getFailedFeatured4Pulls());
            }
        }
        assertEquals(expectedPercent * 2 * 38, upAwards);
    }

    @Test void characterFourStarUpShareIsExactlyFiftyPercentWithoutGuarantee() {
        exactFourStarShare(GachaType.AvatarUp, 50);
    }

    @Test void lightConeFourStarUpShareIsExactlySeventyFivePercentWithoutGuarantee() {
        exactFourStarShare(GachaType.WeaponUp, 75);
    }

    @Test void allFirstStageOutcomesRespectBothFeaturedGuarantees() {
        for (var type : List.of(GachaType.AvatarUp, GachaType.WeaponUp)) for (int stars : new int[]{4,5}) {
            var service = new Choices(); service.roll = stars == 5 ? 1 : 10000;
            var banner = banner(type); service.getGachaBanners().put(banner.getId(), banner);
            var player = new GachaDrawRarityTest.Resident();
            var history = player.history.getBannerInfo(type);
            var up = stars == 5 ? service.getPool(banner).up5() : service.getPool(banner).up4();
            for (int event = 1; event <= 100; event++) {
                service.eventRoll = event; history.setPity5(0);
                if (stars == 5) history.setFailedFeaturedItemPulls(1); else history.setFailedFeatured4Pulls(1);
                assertTrue(up.contains(draw(service, player, banner)));
                assertEquals(0, stars == 5 ? history.getFailedFeaturedItemPulls() : history.getFailedFeatured4Pulls());
            }
        }
    }

    @Test void fiveStarFallbackDeduplicatesAnUpAlreadyInTheStandardCatalogue() {
        var service = new Choices(); service.eventRoll = 100;
        var banner = new GachaBanner().createBanner(2001, GachaType.AvatarUp, 0, Integer.MAX_VALUE,
                new int[]{1003}, new int[]{1001});
        service.getGachaBanners().put(2001, banner);
        var observed = new HashSet<Integer>();
        for (int item = 0; item < 7; item++) {
            service.itemIndex = item;
            observed.add(draw(service, new GachaDrawRarityTest.Resident(), banner));
        }
        assertEquals(7, observed.size()); assertTrue(observed.contains(1003));
    }

    @Test void fiveStarHardPityAndResetHoldForBothEventTypes() {
        for (var type : List.of(GachaType.AvatarUp, GachaType.WeaponUp)) {
            var service = new Choices(); service.roll = 10000;
            var banner = banner(type); service.getGachaBanners().put(banner.getId(), banner);
            var player = new GachaDrawRarityTest.Resident();
            int limit = type == GachaType.WeaponUp ? 80 : 90;
            for (int pull = 1; pull <= limit; pull++) {
                int stars = GameData.getItemExcelMap().get(draw(service, player, banner)).getRarityNum();
                assertEquals(pull == limit, stars == 5, type + " pull " + pull);
            }
            assertEquals(0, player.history.getBannerInfo(type).getPity5());
        }
    }

    @Test void tenthPullGuaranteesFourOrHigherAcrossSeparateRequests() {
        for (var type : List.of(GachaType.AvatarUp, GachaType.WeaponUp)) {
            var service = new Choices(); service.roll = 1000;
            var banner = banner(type); service.getGachaBanners().put(banner.getId(), banner);
            var player = new GachaDrawRarityTest.Resident();
            for (int pull = 1; pull <= 10; pull++) {
                assertEquals(pull == 10 ? 4 : 3,
                        GameData.getItemExcelMap().get(draw(service, player, banner)).getRarityNum());
            }
            assertEquals(0, player.history.getBannerInfo(type).getPity4());
        }
    }

    @Test void fiveStarPriorityDoesNotConsumeThePendingFourStarFeaturedGuarantee() {
        var service = new Choices(); service.roll = 5000;
        var banner = banner(GachaType.AvatarUp); service.getGachaBanners().put(banner.getId(), banner);
        var player = new GachaDrawRarityTest.Resident();
        var history = player.history.getBannerInfo(GachaType.AvatarUp);
        history.setPity5(89); history.setPity4(9); history.setFailedFeatured4Pulls(1);
        assertEquals(5, GameData.getItemExcelMap().get(draw(service, player, banner)).getRarityNum());
        assertEquals(1, history.getFailedFeatured4Pulls());
        assertEquals(10, history.getPity4());
        assertTrue(service.getPool(banner).up4().contains(draw(service, player, banner)));
        assertEquals(0, history.getFailedFeatured4Pulls()); assertEquals(0, history.getPity4());
        assertEquals(1, history.getFailedFeaturedItemPulls());
    }

    @Test void fiveStarFeaturedGuaranteeSurvivesReloadAndParallelBannerSwitch() {
        var service = new Choices();
        var first = banner(GachaType.AvatarUp); service.getGachaBanners().put(first.getId(), first);
        var player = new GachaDrawRarityTest.Resident();
        assertFalse(service.getPool(first).up5().contains(draw(service, player, first)));
        var json = new Gson();
        var saved = json.fromJson(json.toJson(player.history), PlayerGachaInfo.class);
        var reloaded = new GachaDrawRarityTest.Resident() {
            @Override public PlayerGachaInfo getGachaInfo() { return saved; }
        };
        var second = new GachaBanner().createBanner(2002, GachaType.AvatarUp, 0, Integer.MAX_VALUE,
                new int[]{1204}, new int[]{1002});
        service.getGachaBanners().put(second.getId(), second);
        assertEquals(1204, draw(service, reloaded, second));
        assertEquals(0, saved.getBannerInfo(GachaType.AvatarUp).getFailedFeaturedItemPulls());
        assertEquals(0, saved.getBannerInfo(GachaType.WeaponUp).getFailedFeaturedItemPulls());
        assertEquals(0, saved.getBannerInfo(GachaType.Normal).getPity5());
    }
}
