package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.gacha.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.send.PacketDoGachaScRsp;
import emu.lunarcore.proto.DoGachaScRspOuterClass.DoGachaScRsp;
import emu.lunarcore.proto.GachaItemOuterClass.GachaItem;
import emu.lunarcore.proto.ItemOuterClass.Item;
import java.util.List;
import org.junit.jupiter.api.Test;

class GachaDrawProtocol46Test {
  static class Session extends GameSession {
    final ProtocolBridge protocol;
    Session(String version) { super(null); protocol = ProtocolBridge.forVersion(version); }
    @Override public ProtocolBridge getProtocol() { return protocol; }
  }

  Player player(String version) {
    var history = new PlayerGachaInfo(); history.addCeilingNum(23);
    var session = new Session(version);
    return new Player() {
      @Override public PlayerGachaInfo getGachaInfo() { return history; }
      @Override public GameSession getSession() { return session; }
    };
  }

  @Test void successful46DrawCanEncodeItsRewardAfterNonzeroStandardCeilingProgress() throws Exception {
    var player = player("4.6.0");
    var banner = new GachaBanner().createBanner(1001,GachaType.Normal,0,0,new int[0],new int[0]);
    var item = GachaItem.newInstance().setGachaItem(Item.newInstance().setItemId(1003).setNum(1));
    var packet = new PacketDoGachaScRsp(player,banner,1,List.of(item));
    var protocol = player.getSession().getProtocol();
    var converted = assertDoesNotThrow(() -> protocol.outbound(CmdId.DoGachaScRsp,packet.getData().toByteArray()));
    var response = protocol.clientSchema().parse("DoGachaScRsp",converted.body());
    assertEquals(1001,InitialData46Test.field(response,"gacha_id"));
    assertEquals(1,((List<?>)InitialData46Test.field(response,"gacha_item_list")).size());
    assertEquals(23,player.getGachaInfo().getCeilingNum());
  }

  @Test void legacyDrawResponseRetainsItsCeilingCounter() {
    var player = player("4.2.0");
    var banner = new GachaBanner().createBanner(1001,GachaType.Normal,0,0,new int[0],new int[0]);
    var response = (DoGachaScRsp)new PacketDoGachaScRsp(player,banner,1,List.of()).getData();
    assertEquals(23,response.getCeilingNum());
  }
}
