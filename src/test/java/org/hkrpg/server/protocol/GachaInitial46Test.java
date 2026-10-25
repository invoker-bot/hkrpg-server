package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.CodedInputStream;
import emu.lunarcore.game.gacha.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.hkrpg.server.gacha.GachaRarityResources;

class GachaInitial46Test {
  GachaRarityResources resources;
  @BeforeEach void loadRarities() throws Exception { resources = new GachaRarityResources(); }
  @AfterEach void restoreResources() { resources.close(); }
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final GachaService service = new GachaService(null) { @Override public void watch() {} };
  final PlayerGachaInfo history = new PlayerGachaInfo();
  final Player player = new Player() {
    @Override public PlayerGachaInfo getGachaInfo() { return history; }
    @Override public emu.lunarcore.game.avatar.GameAvatar getAvatarById(int id) { return null; }
    @Override public void save() { fail("A query must not save or grant anything"); }
  };

  @Test void eventBannerKeepsSeparateRarityPoolsAndTheOriginalWindow() {
    service.getGachaBanners().put(2001, new GachaBanner().createBanner(2001, GachaType.AvatarUp,
        100, 200, new int[] {1501}, new int[] {1001, 1002}));
    var before = new Gson().toJson(history);
    var info = InitialData46Test.first(GachaInitial46.snapshot(protocol, player, service, 150), "gacha_info_list");
    assertEquals(2001, InitialData46Test.field(info, "gacha_id"));
    assertEquals(100L, InitialData46Test.field(info, "begin_time"));
    assertEquals(200L, InitialData46Test.field(info, "end_time"));
    assertEquals(List.of(1501), InitialData46Test.field(info, "ONIHOMCELJK"));
    assertEquals(List.of(1001, 1002), InitialData46Test.field(info, "prize_item_list"));
    assertEquals(List.of(), InitialData46Test.field(info, "item_detail_list"));
    assertEquals(before, new Gson().toJson(history));
  }

  @Test void standardBannerUsesActualCeilingProgressAndDoesNotInventAClaim() {
    history.addCeilingNum(137);
    service.getGachaBanners().put(1001, new GachaBanner().createBanner(1001, GachaType.Normal,
        0, 0, new int[0], new int[0]));
    var info = InitialData46Test.first(GachaInitial46.snapshot(protocol, player, service), "gacha_info_list");
    var ceiling = (DynamicMessage) InitialData46Test.field(info, "gacha_ceiling");
    assertEquals(137, InitialData46Test.field(ceiling, "ceiling_num"));
    assertEquals(false, InitialData46Test.field(ceiling, "is_claimed"));
    assertEquals(service.getYellowAvatars().length, ((List<?>) InitialData46Test.field(ceiling, "avatar_list")).size());
    assertEquals(List.of(23002, 1003, 1101, 1104, 23000, 23003),
        InitialData46Test.field(info, "ONIHOMCELJK"));
    assertEquals(137, history.getCeilingNum());
  }

  @Test void absentBannersReturnAnEmptyCatalog() {
    var response = GachaInitial46.snapshot(protocol, player, service);
    assertEquals(List.of(), InitialData46Test.field(response, "gacha_info_list"));
  }

  @Test void legacyPermanentCatalogueDisplaysOnlyActualFiveStarsAndInvalidEventIsHidden() throws Exception {
    try (var input = getClass().getResourceAsStream("/defaults/Banners.json")) {
      var rows = new Gson().fromJson(new java.io.InputStreamReader(java.util.Objects.requireNonNull(input)), GachaBanner[].class);
      var normal = java.util.Arrays.stream(rows).filter(b -> b.getId() == 1001).findFirst().orElseThrow();
      service.replaceBanners(List.of(normal));
      var info = InitialData46Test.first(GachaInitial46.snapshot(protocol,player,service),"gacha_info_list");
      var featured = (List<?>) InitialData46Test.field(info,"prize_item_list");
      assertEquals(6,featured.size());
      assertTrue(featured.stream().allMatch(id -> emu.lunarcore.data.GameData.getItemExcelMap().get((Integer)id).getRarityNum() == 5));
      service.getGachaBanners().put(2001,new GachaBanner().createBanner(2001,GachaType.AvatarUp,0,Integer.MAX_VALUE,new int[]{1001},new int[]{1002}));
      assertEquals(1,((List<?>) InitialData46Test.field(GachaInitial46.snapshot(protocol,player,service),"gacha_info_list")).size());
    }
  }

  @Test void version46QueryHonorsTheExactStartAndEndOfARerun() {
    service.getGachaBanners().put(2001, new GachaBanner().createBanner(2001,GachaType.AvatarUp,
        100,200,new int[]{1102},new int[]{1105,1106,1109}));
    assertEquals(List.of(),InitialData46Test.field(GachaInitial46.snapshot(protocol,player,service,99),"gacha_info_list"));
    assertEquals(1,((List<?>)InitialData46Test.field(GachaInitial46.snapshot(protocol,player,service,100),"gacha_info_list")).size());
    assertEquals(List.of(),InitialData46Test.field(GachaInitial46.snapshot(protocol,player,service,200),"gacha_info_list"));
  }

  @Test void invalidRequestsAndUnauthenticatedQueriesCannotReadTheBannerService() throws Exception {
    var session = new LoginFlow46Test.Session();
    session.setState(SessionState.ACTIVE);
    assertTrue(VersionedPacketHandler.handle(session, 1934, new byte[] {8, 1}));
    var response = protocol.clientSchema().parse("GetGachaInfoScRsp", session.lastClientBody);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(response, "retcode"));
    session.clientBodies.clear();
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session, 1934, new byte[0]));
    assertTrue(session.clientBodies.isEmpty());
  }

  @Test void seeleBannerSendsSeeleAsTheHeadlineAndThreeRealCompanionCards() throws Exception {
    assertClientDisplay(2001, GachaType.AvatarUp, new int[] {1102}, new int[] {1105, 1106, 1109});
  }

  @Test void lightConeBannerUsesTheSameVerifiedWireSlotsWithoutAvatarMarkers() throws Exception {
    assertClientDisplay(3001, GachaType.WeaponUp, new int[] {23001}, new int[] {21001, 21005, 21000});
  }

  @Test void multipleFeaturedAvatarsKeepTheirConfiguredOrder() throws Exception {
    assertClientDisplay(2001, GachaType.AvatarUp, new int[] {1102, 1204}, new int[] {1109, 1106, 1105});
  }

  @Test void permanentAndNewbieKeepTheirExistingFiveStarCardPreview() throws Exception {
    for (var type : List.of(GachaType.Normal, GachaType.Newbie)) {
      service.getGachaBanners().clear();
      var banner = new GachaBanner().createBanner(type == GachaType.Normal ? 1001 : 4001,
          type, 0, 0, new int[0], new int[0]);
      service.getGachaBanners().put(banner.getId(), banner);
      var info = InitialData46Test.first(GachaInitial46.snapshot(protocol, player, service), "gacha_info_list");
      var expected = List.of(23002, 1003, 1101, 1104, 23000, 23003);
      assertEquals(expected, wireIds(info, 5), type.toString());
      assertEquals(expected, wireIds(info, 10), type.toString());
      assertEquals(List.of(), wireIds(info, 7));
    }
  }

  private void assertClientDisplay(int id, GachaType type, int[] featured, int[] companions) throws Exception {
    var banner = new GachaBanner().createBanner(id, type, 100, 200, featured, companions);
    service.getGachaBanners().put(id, banner);
    var before = new Gson().toJson(banner) + new Gson().toJson(history);
    var pool = service.getPool(banner);
    var info = InitialData46Test.first(GachaInitial46.snapshot(protocol, player, service, 150), "gacha_info_list");
    // Independently verified from this client's WriteTo + SyncGachaPoolData, not schema names:
    // field 5 -> PrizeItemDatas; field 10 -> DetailItemDatas; field 7 -> selection metadata.
    assertEquals(pool.up5(), wireIds(info, 5), "Headline must match the actual featured draw pool");
    assertEquals(pool.up4(), wireIds(info, 10), "Companion cards must match the actual four-star UP pool");
    assertEquals(List.of(), wireIds(info, 7), "Do not fabricate selection metadata");
    assertEquals(before, new Gson().toJson(banner) + new Gson().toJson(history));
  }

  private static List<Integer> wireIds(DynamicMessage info, int field) throws Exception {
    var values = new java.util.ArrayList<Integer>();
    var input = CodedInputStream.newInstance(info.toByteArray());
    for (int tag; (tag = input.readTag()) != 0;) {
      if ((tag >>> 3) != field) input.skipField(tag);
      else if ((tag & 7) == 2) {
        int limit = input.pushLimit(input.readRawVarint32());
        while (input.getBytesUntilLimit() > 0) values.add(input.readUInt32());
        input.popLimit(limit);
      } else {
        assertEquals(0, tag & 7);
        values.add(input.readUInt32());
      }
    }
    return values;
  }
}
