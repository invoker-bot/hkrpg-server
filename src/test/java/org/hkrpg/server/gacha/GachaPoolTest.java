package org.hkrpg.server.gacha;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.gacha.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;

class GachaPoolTest {
  GachaRarityResources resources;
  final GachaService service = new GachaService(null) { @Override public void watch() {} };
  @BeforeEach void setup() throws Exception { resources = new GachaRarityResources(); }
  @AfterEach void cleanup() { resources.close(); }

  GachaBanner banner(GachaType type, int[] five, int[] four) {
    return new GachaBanner().createBanner(99999, type, 0, Integer.MAX_VALUE, five, four);
  }

  void tiers(GachaPool pool) {
    stars(pool.fiveStars(), 5); stars(pool.fourStars(), 4); stars(pool.base3(), 3);
  }

  void stars(List<Integer> group, int rarity) {
    assertFalse(group.isEmpty());
    assertTrue(group.stream().allMatch(id -> GameData.getItemExcelMap().get(id).getRarityNum() == rarity));
    assertEquals(group.size(), new HashSet<>(group).size());
  }

  @Test void legacyMixedCatalogueHasExactlyFourteenFiveStarsAndThirtyFourFourStars() throws Exception {
    try (var in = getClass().getResourceAsStream("/defaults/Banners.json")) {
      var rows = new Gson().fromJson(new InputStreamReader(Objects.requireNonNull(in)), GachaBanner[].class);
      var normal = Arrays.stream(rows).filter(row -> row.getId() == 1001).findFirst().orElseThrow();
      var raw = new Gson().toJson(normal); var pool = service.getPool(normal);
      assertTrue(pool.canPull(), pool.issues().toString()); tiers(pool);
      assertEquals(14, pool.fiveStars().size()); assertEquals(34, pool.fourStars().size());
      assertTrue(pool.up5().isEmpty()); assertTrue(pool.up4().isEmpty());
      assertEquals(raw, new Gson().toJson(normal));
      assertTrue(Arrays.stream(service.getFeatured5(normal, pool)).allMatch(pool.fiveStars()::contains));
    }
  }

  @Test void emptyPermanentConfigurationUsesTheExistingResourceCheckedBasePools() {
    var pool = service.getPool(banner(GachaType.Normal, new int[0], new int[0]));
    assertTrue(pool.canPull(), pool.issues().toString()); tiers(pool);
    assertEquals(14, pool.fiveStars().size()); assertEquals(38, pool.fourStars().size());
  }

  @Test void customAndNewbieMembershipIsReclassifiedWithoutAddingUnconfiguredPeers() {
    for (var type : List.of(GachaType.Normal, GachaType.Newbie)) {
      var pool = service.getPool(banner(type, new int[]{1001,1003,21000}, new int[]{23002,1001}));
      assertTrue(pool.canPull(), pool.issues().toString());
      assertEquals(List.of(1003,23002), pool.fiveStars());
      assertEquals(List.of(1001,21000), pool.fourStars()); tiers(pool);
    }
  }

  @Test void eventPoolsRejectWrongRarityUnknownItemsAndWrongFeaturedType() {
    for (var b : List.of(banner(GachaType.AvatarUp,new int[]{1001},new int[]{1002}),
        banner(GachaType.AvatarUp,new int[]{1102},new int[]{1003}),
        banner(GachaType.AvatarUp,new int[]{999998},new int[]{1002}),
        banner(GachaType.AvatarUp,new int[]{23002},new int[]{1002}),
        banner(GachaType.WeaponUp,new int[]{1102},new int[]{21000}))) {
      var pool = service.getPool(b);
      assertFalse(pool.canPull()); assertFalse(pool.issues().isEmpty());
      tiers(pool);
    }
  }

  @Test void validEventCandidatesAndFallbacksKeepTheirRealRarity() {
    for (var b : List.of(banner(GachaType.AvatarUp,new int[]{1102},new int[]{1001,1002}),
        banner(GachaType.WeaponUp,new int[]{23001},new int[]{21000}))) {
      var pool = service.getPool(b); assertTrue(pool.canPull(), pool.issues().toString()); tiers(pool);
      assertTrue(pool.fiveStars().containsAll(pool.up5())); assertTrue(pool.fourStars().containsAll(pool.up4()));
    }
  }

  @Test void missingResourcesCannotBeSilentlyDroppedAfterPayment() {
    var item = GameData.getItemExcelMap().get(20000);
    try {
      GameData.getItemExcelMap().keySet().remove(20000);
      assertFalse(service.getPool(banner(GachaType.Normal,new int[]{1003},new int[]{1001})).canPull());
    } finally { GameData.getItemExcelMap().add(item); }
  }
}
