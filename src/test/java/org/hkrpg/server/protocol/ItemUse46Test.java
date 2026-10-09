package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.server.packet.*;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.*;

class ItemUse46Test {
  static final int ITEM = 19993001, AVATAR = 19993002;

  static class Consumer extends Player {
    final Inventory inventory = new Inventory(this);
    final LineupManager teams = new LineupManager(this);

    public Inventory getInventory() {
      return inventory;
    }

    public LineupManager getLineupManager() {
      return teams;
    }

    public void save() {}

    public void sendPacket(BasePacket p) {}
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Consumer consumer = new Consumer();

    public Player getPlayer() {
      return consumer;
    }

    public void send(BasePacket p) {
      var wire = getProtocol().outbound(p.getCmdId(), p.getData().toByteArray());
      sendClientPacket("UseItemScRsp", wire.body());
    }
  }

  @AfterEach
  void reset() {
    GameData.getItemExcelMap().keySet().remove(ITEM);
    GameData.getItemUseExcelMap().keySet().remove(ITEM);
  }

  Session session() {
    var s = new Session();
    var gson = new Gson();
    var item =
        gson.fromJson(
            "{\"ID\":"
                + ITEM
                + ",\"ItemMainType\":\"Usable\","
                + "\"ItemSubType\":\"Food\",\"PileLimit\":99,\"UseMethod\":\"TeamSpecificFoodBenefit\"}",
            ItemExcel.class);
    GameData.getItemExcelMap().add(item);
    GameData.getItemUseExcelMap()
        .add(
            gson.fromJson(
                "{\"UseDataID\":"
                    + ITEM
                    + ",\"UseMultipleMax\":1,\"UseTargetType\":\"Alive\",\"PreviewPowerPercent\":0.3}",
                ItemUseExcel.class));
    s.consumer.inventory.addItem(
        new GameItem(item, 2) {
          public void save() {}
        });
    var a =
        new GameAvatar() {
          public int getAvatarId() {
            return AVATAR;
          }

          public void save() {}
        };
    s.consumer.getAvatars().getAvatars().put(AVATAR, a);
    s.consumer.getCurrentLineup().getAvatars().add(AVATAR);
    a.setCurrentHp(s.consumer.getCurrentLineup(), 5000);
    a.setCurrentSp(s.consumer.getCurrentLineup(), 1000);
    return s;
  }

  DynamicMessage.Builder request(Session s, int id) {
    var b = Gameplay46.builder(s, "UseItemCsReq");
    Gameplay46.set(b, "use_item_id", id);
    Gameplay46.set(b, "use_item_count", 1);
    Gameplay46.set(b, "base_avatar_id", AVATAR);
    var type = b.getDescriptorForType().findFieldByName("KANPPCMBJHG");
    b.setField(type, type.getEnumType().findValueByNumber(1));
    return b;
  }

  void submit(Session s, byte[] bytes) throws Exception {
    assertTrue(VersionedPacketHandler.handle(s, 558, bytes));
    assertEquals("UseItemScRsp", s.responseName);
  }

  @Test
  void nativeFoodSuccessCarriesActualCountAndItsFailureCarriesZeroCount() throws Exception {
    var s = session();
    submit(s, request(s, ITEM).build().toByteArray());
    assertEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(ITEM, InitialData46Test.field(s.response, "use_item_id"));
    assertEquals(1, InitialData46Test.field(s.response, "use_item_count"));
    assertEquals(
        4000, s.consumer.getAvatarById(AVATAR).getCurrentSp(s.consumer.getCurrentLineup()));
    submit(s, request(s, ITEM + 1).build().toByteArray());
    assertEquals(Retcode.ITEM_NOT_EXIST.getVal(), InitialData46Test.field(s.response, "retcode"));
    assertEquals(0, InitialData46Test.field(s.response, "use_item_count"));
  }

  @Test
  void unsupportedAvatarKindsOptionalRewardsAndBooleanBranchCannotConsume() throws Exception {
    for (String field : new String[] {"KANPPCMBJHG", "optional_reward_id", "DGEKFCHEAPO"}) {
      var s = session();
      var b = request(s, ITEM);
      var f = b.getDescriptorForType().findFieldByName(field);
      b.setField(
          f,
          field.equals("KANPPCMBJHG")
              ? f.getEnumType().findValueByNumber(2)
              : field.equals("DGEKFCHEAPO") ? true : 1);
      submit(s, b.build().toByteArray());
      assertNotEquals(0, InitialData46Test.field(s.response, "retcode"));
      assertEquals(2, s.consumer.inventory.getMaterialByItemId(ITEM).getCount());
    }
  }

  @Test
  void oversizedAliasesDuplicateScalarsAndUnknownFieldsCannotConsume() throws Exception {
    var s = session();
    for (int field : new int[] {9, 12, 14, 15}) {
      var out = new ByteArrayOutputStream();
      var c = com.google.protobuf.CodedOutputStream.newInstance(out);
      c.writeUInt64(field, 0x100000000L + (field == 12 ? ITEM : field == 14 ? AVATAR : 1));
      c.flush();
      submit(s, out.toByteArray());
      assertNotEquals(0, InitialData46Test.field(s.response, "retcode"));
    }
    var out = new ByteArrayOutputStream();
    out.write(request(s, ITEM).build().toByteArray());
    var c = com.google.protobuf.CodedOutputStream.newInstance(out);
    c.writeUInt32(12, ITEM);
    c.flush();
    submit(s, out.toByteArray());
    assertNotEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(2, s.consumer.inventory.getMaterialByItemId(ITEM).getCount());
  }

  @Test
  void legacyHandlerNoLongerReportsSuccessForMissingItems() throws Exception {
    var s = session();
    var req =
        emu.lunarcore.proto.UseItemCsReqOuterClass.UseItemCsReq.newInstance()
            .setUseItemId(ITEM + 1)
            .setUseItemCount(1)
            .setBaseAvatarId(AVATAR);
    new emu.lunarcore.server.packet.recv.HandlerUseItemCsReq().handle(s, req.toByteArray());
    assertNotEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(0, InitialData46Test.field(s.response, "use_item_count"));
  }
}
