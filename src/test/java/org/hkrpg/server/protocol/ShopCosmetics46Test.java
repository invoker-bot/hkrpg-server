package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.AvatarUpgrade46Test.scalar;
import static org.hkrpg.server.protocol.InitialData46Test.field;
import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.player.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShopCosmetics46Test {
  @Test void purchasedAvatarSkinNotificationAndDressRequestUseNativeSkinField() throws Exception {
    var protocol = ProtocolBridge.forVersion("4.6.0");
    var packet = new emu.lunarcore.server.packet.send.PacketUnlockAvatarSkinScNotify(1131001);
    var notification = protocol.clientSchema().parse("UnlockAvatarSkinScNotify",
        protocol.translate("UnlockAvatarSkinScNotify",packet.getData().toByteArray(),true));
    assertEquals(1131001,field(notification,"skin_id"));
    var request = protocol.inbound(378,AvatarUpgrade46Test.join(scalar(3,1131001),scalar(11,1310)));
    var decoded = emu.lunarcore.proto.DressAvatarSkinCsReqOuterClass.DressAvatarSkinCsReq.parseFrom(request.body());
    assertEquals(1131001,decoded.getAvatarSkinId());
    assertEquals(1310,decoded.getAvatarId());
  }
  static class Session extends ProgressionGates46Test.CheckedSession {
    final Player resident = new Player() {
      final PlayerUnlockData unlocks = new PlayerUnlockData(this) { public void save() {} };
      public PlayerUnlockData getUnlocks() { return unlocks; }
      public String getSignature() { return ""; }
      public void save() {}
    };
    public Player getPlayer() { return resident; }
  }
  @Test void cardsRequireOwnershipAndBoardQueryReportsPurchasedCard() throws Exception {
    int id = 253004;
    var prior = GameData.getItemExcelMap().get(id);
    try {
      GameData.getItemExcelMap().add(new Gson().fromJson("{\"ID\":253004,\"ItemMainType\":\"Usable\",\"ItemSubType\":\"PersonalCard\",\"PileLimit\":1}",ItemExcel.class));
      var s = new Session();
      assertTrue(VersionedPacketHandler.handle(s,2808,scalar(1,id)));
      assertNotEquals(0,field(s.response,"retcode"));
      assertTrue(s.resident.getInventory().addItem(id,1));
      assertTrue(VersionedPacketHandler.handle(s,2808,scalar(1,id)));
      assertEquals(0,field(s.response,"retcode"));
      assertEquals(id,field(s.response,"current_personal_card_id"));
      assertTrue(VersionedPacketHandler.handle(s,2834,new byte[0]));
      assertEquals(List.of(253000,id),field(s.response,"unlocked_personal_card_list"));
      assertEquals(id,field(s.response,"current_personal_card_id"));
    } finally { if (prior == null) GameData.getItemExcelMap().keySet().remove(id); else GameData.getItemExcelMap().add(prior); }
  }
  @Test void pamSkinsNeedOwnershipAndUseTheNativeSelectionReply() throws Exception {
    int id = 252002;
    var prior = GameData.getItemExcelMap().get(id);
    try {
      GameData.getItemExcelMap().add(new Gson().fromJson("{\"ID\":252002,\"ItemMainType\":\"Usable\",\"ItemSubType\":\"PamSkin\",\"PileLimit\":1}",ItemExcel.class));
      var s = new Session();
      VersionedPacketHandler.handle(s,8137,scalar(1,id));
      assertNotEquals(0,field(s.response,"retcode"));
      assertTrue(s.resident.getInventory().addItem(id,1));
      VersionedPacketHandler.handle(s,8137,scalar(1,id));
      assertEquals(0,field(s.response,"retcode"));
      assertEquals(id,field(s.response,"cur_skin"));
      assertEquals(id,field(s.response,"set_skin"));
      assertEquals(java.util.Set.of(252000,id),ShopCosmetics46.pamSkins(s.resident));
    } finally { if (prior == null) GameData.getItemExcelMap().keySet().remove(id); else GameData.getItemExcelMap().add(prior); }
  }
}
