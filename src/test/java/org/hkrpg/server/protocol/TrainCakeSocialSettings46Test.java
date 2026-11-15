package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/** Room permissions must be owned by the player and survive an explicit update. */
class TrainCakeSocialSettings46Test {
  static byte[] updateBody(QuickStaminaSettings46Test.Session s,
      boolean allowStrangers, boolean allowEntry, boolean allowBox) {
    var social = builder(s, "JPGACKPLGCD");
    set(social, "KALACJBAPOA", allowStrangers);
    set(social, "FOKJIPGMKAN", allowEntry);
    set(social, "IBLGOCFBOCO", allowBox);
    var setting = builder(s, "UpdatePlayerSetting");
    set(setting, "CMFCOOEEECB", social.build());
    var req = builder(s, "UpdatePlayerSettingCsReq");
    set(req, "player_setting", setting.build());
    return req.build().toByteArray();
  }

  static DynamicMessage social(DynamicMessage response) {
    var setting = (DynamicMessage) value(response, "player_setting");
    var field = setting.getDescriptorForType().findFieldByName("CMFCOOEEECB");
    assertTrue(setting.hasField(field), "The selected setting must be echoed, even when all flags are false");
    return (DynamicMessage) setting.getField(field);
  }

  static void flags(DynamicMessage social, boolean strangers, boolean entry, boolean box) {
    assertEquals(strangers, value(social, "KALACJBAPOA"));
    assertEquals(entry, value(social, "FOKJIPGMKAN"));
    assertEquals(box, value(social, "IBLGOCFBOCO"));
  }

  @Test void explicitPermissionIsSavedEchoedAndReadBackOnLogin() throws Exception {
    var s = new QuickStaminaSettings46Test.Session();
    var response = QuickStaminaSettings46Test.update(s, updateBody(s, true, true, false));
    assertEquals(0, number(response, "retcode"));
    flags(social(response), true, true, false);
    var basic = QuickStaminaSettings46Test.query(s);
    var field = basic.getDescriptorForType().findFieldByNumber(1800);
    assertTrue(basic.hasField(field));
    flags((DynamicMessage) basic.getField(field), true, true, false);
    assertEquals(1, s.actor().saves);
    assertEquals(0, number(QuickStaminaSettings46Test.update(s, updateBody(s, true, true, false)), "retcode"));
    assertEquals(1, s.actor().saves, "Retries should not write again");
  }

  @Test void rejectingAWriteRetainsAndEchoesTheActualPermissions() throws Exception {
    var s = new QuickStaminaSettings46Test.Session();
    assertEquals(0, number(QuickStaminaSettings46Test.update(s, updateBody(s, false, true, false)), "retcode"));
    s.actor().failSave = true;
    var response = QuickStaminaSettings46Test.update(s, updateBody(s, true, true, true));
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(), number(response, "retcode"));
    flags(social(response), false, true, false);
    var basic = QuickStaminaSettings46Test.query(s);
    flags((DynamicMessage) basic.getField(basic.getDescriptorForType().findFieldByNumber(1800)),
        false, true, false);
    assertEquals(1, s.actor().saves);
  }

  @Test void closingEveryPermissionStillSelectsTheSocialOneof() throws Exception {
    var s = new QuickStaminaSettings46Test.Session();
    QuickStaminaSettings46Test.update(s, updateBody(s, true, true, true));
    var response = QuickStaminaSettings46Test.update(s, updateBody(s, false, false, false));
    assertEquals(0, number(response, "retcode"));
    flags(social(s.getProtocol().clientSchema().parse("UpdatePlayerSettingScRsp", response.toByteArray())),
        false, false, false);
    assertEquals(2, s.actor().saves);
  }

  static byte[] rawSocial(byte[] fields) throws Exception {
    var nestedBytes = new ByteArrayOutputStream();
    var nested = CodedOutputStream.newInstance(nestedBytes);
    nested.writeByteArray(1660, fields); nested.flush();
    var outerBytes = new ByteArrayOutputStream();
    var outer = CodedOutputStream.newInstance(outerBytes);
    outer.writeByteArray(1, nestedBytes.toByteArray()); outer.flush();
    return outerBytes.toByteArray();
  }

  @Test void duplicateUnknownAndNonBooleanPermissionFieldsAreRejected() throws Exception {
    for (byte[] bad : new byte[][] {{8, 1, 8, 0}, {8, 2}, {32, 1}, {16}, {24, (byte) 255}}) {
      var s = new QuickStaminaSettings46Test.Session();
      var response = QuickStaminaSettings46Test.update(s, rawSocial(bad));
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), number(response, "retcode"));
      assertEquals(0, s.actor().saves);
      var basic = QuickStaminaSettings46Test.query(s);
      assertFalse(basic.hasField(basic.getDescriptorForType().findFieldByNumber(1800)));
    }
  }

  @Test void permissionsSurviveDatabaseReloadAndLegacyDocumentsRemainUnspecified() throws Exception {
    var s = new QuickStaminaSettings46Test.Session();
    assertEquals(0, number(QuickStaminaSettings46Test.update(s, updateBody(s, true, true, false)), "retcode"));
    var mongo = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "train-cake-permissions");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var player = QuickStaminaSettings46Test.JSON.fromJson(s.actor().saved, Player.class);
      var uid = Player.class.getDeclaredField("uid"); uid.setAccessible(true); uid.setInt(player, 19996992);
      store.save(player);
      var restored = new QuickStaminaSettings46Test.Session(); restored.owner = store.find(Player.class).first();
      var basic = QuickStaminaSettings46Test.query(restored);
      flags((DynamicMessage) basic.getField(basic.getDescriptorForType().findFieldByNumber(1800)),
          true, true, false);
      store.getCollection(Player.class).updateOne(new org.bson.Document("_id", 19996992),
          new org.bson.Document("$unset", new org.bson.Document("gameplay.trainCakeSocialSettings", "")));
      var legacy = new QuickStaminaSettings46Test.Session(); legacy.owner = store.find(Player.class).first();
      basic = QuickStaminaSettings46Test.query(legacy);
      assertFalse(basic.hasField(basic.getDescriptorForType().findFieldByNumber(1800)));
    } finally { mongo.shutdownNow(); }
  }
}
