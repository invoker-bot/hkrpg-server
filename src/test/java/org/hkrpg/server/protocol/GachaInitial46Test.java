package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.gacha.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.List;
import org.junit.jupiter.api.Test;

class GachaInitial46Test {
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
    assertEquals(List.of(1501), InitialData46Test.field(info, "prize_item_list"));
    assertEquals(List.of(1001, 1002), InitialData46Test.field(info, "ONIHOMCELJK"));
    assertEquals(List.of(11), InitialData46Test.field(info, "item_detail_list"));
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
    assertEquals(List.of(), InitialData46Test.field(info, "ONIHOMCELJK"));
    assertEquals(137, history.getCeilingNum());
  }

  @Test void absentBannersReturnAnEmptyCatalog() {
    var response = GachaInitial46.snapshot(protocol, player, service);
    assertEquals(List.of(), InitialData46Test.field(response, "gacha_info_list"));
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
}
