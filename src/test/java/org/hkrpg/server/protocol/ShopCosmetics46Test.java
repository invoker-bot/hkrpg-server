package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.AvatarUpgrade46Test.scalar;
import static org.hkrpg.server.protocol.InitialData46Test.field;
import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.data.excel.AvatarSkinExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.server.packet.BasePacket;
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
    final java.util.List<BasePacket> notifications = new java.util.ArrayList<>();
    final Player resident = new Player() {
      final PlayerUnlockData unlocks = new PlayerUnlockData(this) { public void save() {} };
      public PlayerUnlockData getUnlocks() { return unlocks; }
      public String getSignature() { return ""; }
      public void save() {}
      public void sendPacket(BasePacket packet) { notifications.add(packet); }
    };
    public Player getPlayer() { return resident; }
  }
  @Test void unownedSkinReceivesAnExplicitFailureReply() throws Exception {
    var s = new Session();
    assertTrue(VersionedPacketHandler.handle(s, 378,
        AvatarUpgrade46Test.join(scalar(3, 1141501), scalar(11, 1415))));
    assertEquals("DressAvatarSkinScRsp", s.responseName);
    assertEquals(1222, field(s.response, "PLDKENJLKME"));
    assertTrue(s.resident.getUnlocks().getAvatarSkins().isEmpty());
  }
  @Test void takingOffSkinFromMissingAvatarReceivesAnExplicitFailureReply() throws Exception {
    var s = new Session();
    assertTrue(VersionedPacketHandler.handle(s, 319, scalar(1, 1415)));
    assertEquals("TakeOffAvatarSkinScRsp", s.responseName);
    assertEquals(1200, field(s.response, "PLDKENJLKME"));
  }
  @Test void threeOwnedSkinsEquipSynchronizeAndTakeOffForTheirActualCharacters() throws Exception {
    var s = new Session();
    for (int[] pair : List.of(new int[]{1141501,1415},new int[]{1150501,1505},new int[]{1140901,1409})) {
      int skin = pair[0], id = pair[1];
      var prior = GameData.getAvatarSkinExcelMap().get(skin);
      try {
        GameData.getAvatarSkinExcelMap().add(new Gson().fromJson("{\"ID\":"+skin+",\"AvatarID\":"+id+"}", AvatarSkinExcel.class));
        var excel = new Gson().fromJson("{\"AvatarID\":"+id+"}", AvatarExcel.class);
        var saves = new java.util.concurrent.atomic.AtomicInteger();
        var avatar = new GameAvatar(excel) { public void save() { saves.incrementAndGet(); } };
        avatar.setOwner(s.resident);
        s.resident.getAvatars().getAvatars().put(id, avatar);
        s.resident.getUnlocks().getAvatarSkins().add(skin);
        assertTrue(VersionedPacketHandler.handle(s,378,AvatarUpgrade46Test.join(scalar(3,skin),scalar(11,id))));
        assertEquals("DressAvatarSkinScRsp",s.responseName);
        assertEquals(0,field(s.response,"PLDKENJLKME"));
        assertEquals(skin,avatar.getData().getSkinId());
        assertEquals(skin,avatar.toPathInfoProto().getAvatarSkin());
        assertEquals(1,saves.get());
        assertFalse(s.notifications.isEmpty());
        var sent = s.notifications.getLast();
        var sync = s.getProtocol().clientSchema().parse("PlayerSyncScNotify",
            s.getProtocol().translate("PlayerSyncScNotify",sent.getData().toByteArray(),true));
        var avatarSync = (com.google.protobuf.DynamicMessage) field(sync,"avatar_sync");
        var path = InitialData46Test.first(avatarSync,"avatar_path_data_info_list");
        assertEquals(id,field(path,"avatar_id"));
        assertEquals(skin,field(path,"dressed_skin_id"));
        assertTrue(VersionedPacketHandler.handle(s,319,scalar(1,id)));
        assertEquals(0,field(s.response,"PLDKENJLKME"));
        assertEquals(0,avatar.getData().getSkinId());
        assertEquals(2,saves.get());
      } finally {
        if (prior == null) GameData.getAvatarSkinExcelMap().keySet().remove(skin); else GameData.getAvatarSkinExcelMap().add(prior);
      }
    }
  }
  @Test void ownedSkinCannotBeEquippedOnAnotherCharacter() throws Exception {
    var s = new Session();
    var prior = GameData.getAvatarSkinExcelMap().get(1141501);
    try {
      GameData.getAvatarSkinExcelMap().add(new Gson().fromJson("{\"ID\":1141501,\"AvatarID\":1415}", AvatarSkinExcel.class));
      var avatar = new GameAvatar(new Gson().fromJson("{\"AvatarID\":1409}",AvatarExcel.class));
      s.resident.getAvatars().getAvatars().put(1409,avatar);
      s.resident.getUnlocks().getAvatarSkins().add(1141501);
      VersionedPacketHandler.handle(s,378,AvatarUpgrade46Test.join(scalar(3,1141501),scalar(11,1409)));
      assertEquals(1225,field(s.response,"PLDKENJLKME"));
      assertEquals(0,avatar.getData().getSkinId());
    } finally { if (prior == null) GameData.getAvatarSkinExcelMap().keySet().remove(1141501); else GameData.getAvatarSkinExcelMap().add(prior); }
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
