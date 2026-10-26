package org.hkrpg.server.gacha;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.avatar.AvatarStorage;
import emu.lunarcore.game.enums.ItemRarity;
import emu.lunarcore.game.gacha.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.DoGachaScRspOuterClass.DoGachaScRsp;
import emu.lunarcore.server.packet.BasePacket;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;

class GachaDrawRarityTest {
  GachaRarityResources resources;
  @BeforeEach void setup() throws Exception { resources = new GachaRarityResources(); }
  @AfterEach void cleanup() { resources.close(); }

  static class DrawService extends GachaService {
    int roll = 1, eventRoll = 100;
    DrawService() { super(null); }
    @Override public void watch() {}
    @Override public int randomRange(int min, int max) { return max == 10000 ? roll : max == 100 ? eventRoll : min; }
    @Override public int getRandom(int[] values) { return values[0]; }
  }

  static class Resident extends Player {
    final PlayerGachaInfo history = new PlayerGachaInfo();
    final List<Integer> awarded = new ArrayList<>();
    final AvatarStorage avatars = new AvatarStorage(this);
    final Inventory inventory = new Inventory(this) {
      @Override public GameItem getMaterialByItemId(int id) {
        return id == 101 || id == 102 ? new GameItem(new Gson().fromJson("{\"ID\":"+id+",\"PileLimit\":999}", ItemExcel.class), 100) : null;
      }
      @Override public boolean removeItem(GameItem item, int count) { if (denyCharge) return false; charged += count; return true; }
      @Override public boolean addItem(GameItem item) { awarded.add(item.getItemId()); return true; }
      @Override public boolean addItem(int id, int count) { return true; }
    };
    int charged;
    boolean denyCharge;
    DoGachaScRsp response;
    @Override public Inventory getInventory() { return inventory; }
    @Override public AvatarStorage getAvatars() { return avatars; }
    @Override public PlayerGachaInfo getGachaInfo() { return history; }
    @Override public void save() {}
    @Override public void sendPacket(BasePacket packet) { if (packet.getData() instanceof DoGachaScRsp data) response = data; }
  }

  GachaBanner standard() throws Exception {
    try (var in = getClass().getResourceAsStream("/defaults/Banners.json")) {
      var rows = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
      return new Gson().fromJson(rows.asList().stream().filter(r -> r.getAsJsonObject().get("id").getAsInt() == 1001).findFirst().orElseThrow(), GachaBanner.class);
    }
  }

  void assertAward(Resident player, int stars) {
    assertEquals(1, player.charged);
    assertEquals(1, player.awarded.size());
    int id = player.awarded.getFirst();
    assertEquals(stars, GameData.getItemExcelMap().get(id).getRarityNum(), "wrong rarity awarded: " + id);
    assertEquals(id, player.response.getGachaItemList().get(0).getGachaItem().getItemId());
  }

  @Test void defaultStandardFiveStarRollCannotAwardMarchSeventhOrDanHeng() throws Exception {
    var service = new DrawService(); var player = new Resident();
    service.getGachaBanners().put(1001, standard()); service.eventRoll = 1;
    service.doPulls(player, 1001, 1); assertAward(player, 5);
    assertEquals(0, player.history.getStandardBanner().getPity5());
  }

  @Test void defaultStandardFourStarRollCannotAwardTheFiveStarDisplayItems() throws Exception {
    var service = new DrawService(); var player = new Resident();
    service.getGachaBanners().put(1001, standard()); service.roll = 10000;
    service.doPulls(player, 1001, 1); assertAward(player, 4);
    assertEquals(0, player.history.getStandardBanner().getPity4());
  }

  @Test void hardFiveStarPityStillAwardsAnActualFiveStar() throws Exception {
    var service = new DrawService(); var player = new Resident();
    service.getGachaBanners().put(1001, standard()); service.roll = 5000; service.eventRoll = 1;
    player.history.getStandardBanner().setPity5(89);
    service.doPulls(player, 1001, 1); assertAward(player, 5);
  }

  @Test void hardFourStarPityStillAwardsAnActualFourStar() throws Exception {
    var service = new DrawService(); var player = new Resident();
    service.getGachaBanners().put(1001, standard()); service.roll = 5000;
    player.history.getStandardBanner().setPity4(9);
    service.doPulls(player, 1001, 1); assertAward(player, 4);
  }

  @Test void standardPoolMustNotConsumeAnEventFeaturedGuarantee() throws Exception {
    var service = new DrawService(); var player = new Resident();
    service.getGachaBanners().put(1001, standard());
    player.history.getStandardBanner().setFailedFeaturedItemPulls(1);
    service.doPulls(player, 1001, 1);
    assertEquals(1, player.history.getStandardBanner().getFailedFeaturedItemPulls());
  }

  @Test void beginnerAndEventPullsDoNotIncreaseThePermanentBannerSelector() throws Exception {
    for (var type : List.of(GachaType.Newbie, GachaType.AvatarUp, GachaType.WeaponUp)) {
      var service = new DrawService(); var player = new Resident();
      GachaBanner banner = type == GachaType.Newbie
          ? new GachaBanner().createBanner(4001, type, 0, Integer.MAX_VALUE, new int[0], new int[0])
          : type == GachaType.AvatarUp
              ? new GachaBanner().createBanner(2001, type, 0, Integer.MAX_VALUE, new int[]{1102}, new int[]{1002}, 100)
              : new GachaBanner().createBanner(3001, type, 0, Integer.MAX_VALUE, new int[]{23000}, new int[]{21000}, 100);
      service.getGachaBanners().put(banner.getId(), banner);
      service.doPulls(player, banner.getId(), 1);
      assertEquals(1, player.charged, "The test must actually perform the draw");
      assertEquals(0, player.history.getCeilingNum(), type.toString());
    }
  }

  @Test void failedTicketDebitDoesNotIncreaseProgressOrAwardItems() throws Exception {
    var service = new DrawService(); var player = new Resident();
    service.getGachaBanners().put(1001, standard());
    player.denyCharge = true;
    service.doPulls(player, 1001, 1);
    assertEquals(0, player.history.getCeilingNum());
    assertTrue(player.awarded.isEmpty());
    assertNotNull(player.response);
    assertNotEquals(0, player.response.getRetcode());
  }

  @Test void eventWithAFourStarInTheFiveStarSlotIsRejectedBeforePaymentOrPity() {
    var service = new DrawService(); var player = new Resident();
    service.getGachaBanners().put(2001, new GachaBanner().createBanner(2001, GachaType.AvatarUp, 0, Integer.MAX_VALUE, new int[]{1001}, new int[]{1002}, 100));
    var before = new Gson().toJson(player.history);
    service.doPulls(player, 2001, 1);
    assertEquals(0, player.charged); assertTrue(player.awarded.isEmpty());
    assertEquals(before, new Gson().toJson(player.history));
    assertNotEquals(0, player.response.getRetcode());
  }

  @Test void eventWithAFiveStarInTheFourStarSlotIsRejectedBeforePaymentOrPity() {
    var service = new DrawService(); var player = new Resident(); service.roll = 10000;
    service.getGachaBanners().put(2001, new GachaBanner().createBanner(2001, GachaType.AvatarUp, 0, Integer.MAX_VALUE, new int[]{1102}, new int[]{1003}, 100));
    var before = new Gson().toJson(player.history);
    service.doPulls(player, 2001, 1);
    assertEquals(0, player.charged); assertTrue(player.awarded.isEmpty());
    assertEquals(before, new Gson().toJson(player.history));
    assertNotEquals(0, player.response.getRetcode());
  }

  @Test void validEventFiveStarGuaranteeAndFourStarUpAwardTheCorrectTier() {
    for (int roll : new int[]{1,10000}) {
      var service = new DrawService(); var player = new Resident(); service.roll = roll;
      service.getGachaBanners().put(2001, new GachaBanner().createBanner(2001, GachaType.AvatarUp, 0, Integer.MAX_VALUE, new int[]{1102}, new int[]{1002}, 100));
      service.doPulls(player, 2001, 1); assertAward(player, roll == 1 ? 5 : 4);
      assertEquals(roll == 1 ? 1102 : 1002, player.awarded.getFirst());
    }
  }

  @Test void standardTenPullProducesTenCorrectTierAwardsAndSpendsExactlyTenTickets() throws Exception {
    for (int roll : new int[]{1,10000,5000}) {
      var service = new DrawService(); var player = new Resident(); service.roll = roll;
      service.getGachaBanners().put(1001, standard()); service.doPulls(player, 1001, 10);
      assertEquals(10, player.charged); assertEquals(10, player.awarded.size());
      assertEquals(10, player.response.getGachaItemList().length());
      if (roll == 1 || roll == 10000) assertTrue(player.awarded.stream().allMatch(id -> GameData.getItemExcelMap().get(id).getRarityNum() == (roll == 1 ? 5 : 4)));
      else assertTrue(player.awarded.stream().allMatch(id -> Set.of(3,4).contains(GameData.getItemExcelMap().get(id).getRarityNum())));
    }
  }
}
