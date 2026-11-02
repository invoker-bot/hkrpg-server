package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Native setting380 is IsFarmStageAutoAddStamina; basic-info returns it in field711. */
class QuickStaminaSettings46Test {
  static final Gson JSON = new Gson();
  static final byte[] ENABLE = HexFormat.of().parseHex("0a03e01701");
  static final byte[] DISABLE = HexFormat.of().parseHex("0a03e01700");

  static class Actor extends Player {
    int saves, notifications;
    String saved;
    boolean failSave;
    @Override public void save() {
      if (failSave) throw new IllegalStateException("setting save failed");
      saves++;
      saved = JSON.toJson(this, Player.class);
    }
    @Override public void sendPacket(BasePacket packet) { notifications++; }
  }

  static class Session extends GameSession {
    Player owner = new Actor();
    String version = "4.6.0", responseName;
    DynamicMessage response;
    Session() { super(null); setState(SessionState.ACTIVE); }
    @Override public Player getPlayer() { return owner; }
    @Override public ProtocolBridge getProtocol() { return ProtocolBridge.forVersion(version); }
    @Override public void sendClientPacket(String name, byte[] bytes) {
      try {
        responseName = name;
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
    @Override public void send(BasePacket packet) {
      var converted = getProtocol().outbound(packet.getCmdId(), packet.getData().toByteArray());
      assertEquals(getProtocol().clientCommand("ReserveStaminaExchangeScRsp"), converted.cmdId());
      sendClientPacket("ReserveStaminaExchangeScRsp", converted.body());
    }
    Actor actor() { return (Actor) owner; }
  }

  static DynamicMessage update(Session s, byte[] body) throws Exception {
    s.response = null;
    assertTrue(VersionedPacketHandler.handle(s, 45, body),
        "UpdatePlayerSetting must get its native reply instead of leaving the UI pending");
    assertEquals("UpdatePlayerSettingScRsp", s.responseName);
    assertEquals(18, s.getProtocol().clientCommand(s.responseName));
    assertNotNull(s.response);
    assertTrue(s.response.getUnknownFields().asMap().isEmpty());
    var setting = s.response.getDescriptorForType().findFieldByName("player_setting");
    assertTrue(s.response.hasField(setting), "The native callback dereferences its setting container");
    return s.response;
  }

  static DynamicMessage query(Session s) throws Exception {
    assertTrue(VersionedPacketHandler.handle(s, 80, new byte[0]));
    assertEquals("GetBasicInfoScRsp", s.responseName);
    assertEquals(0, number(s.response, "retcode"));
    var settings = (DynamicMessage) value(s.response, "player_setting_info");
    assertTrue(settings.hasField(settings.getDescriptorForType().findFieldByNumber(13)));
    return settings;
  }

  static boolean enabled(Session s) throws Exception {
    var settings = query(s);
    return (Boolean) settings.getField(settings.getDescriptorForType().findFieldByNumber(711));
  }

  static void echoed(DynamicMessage response, boolean expected) {
    assertEquals(0, number(response, "retcode"));
    var setting = (DynamicMessage) value(response, "player_setting");
    var field = setting.getDescriptorForType().findFieldByNumber(380);
    assertEquals(1, setting.getAllFields().size());
    assertTrue(setting.hasField(field), "A false oneof selection must remain explicit on the wire");
    assertEquals(expected, setting.getField(field));
  }

  @Test void enablingTheActualNativeToggleGetsAReplyAndReadback() throws Exception {
    var s = new Session();
    assertFalse(enabled(s));
    echoed(update(s, ENABLE), true);
    assertTrue(enabled(s));
    assertEquals(1, s.actor().saves);
  }

  @Test void disablingRetainsTheSelectedFalseOneofAndPersistsIt() throws Exception {
    var s = new Session();
    echoed(update(s, ENABLE), true);
    var off = update(s, DISABLE);
    echoed(off, false);
    var wire = s.getProtocol().clientSchema().parse("UpdatePlayerSettingScRsp", off.toByteArray());
    echoed(wire, false);
    assertFalse(enabled(s));
    assertEquals(2, s.actor().saves);
  }

  @Test void repeatedClicksAreAcknowledgedWithoutRepeatedWrites() throws Exception {
    var s = new Session();
    echoed(update(s, ENABLE), true);
    echoed(update(s, ENABLE), true);
    assertEquals(1, s.actor().saves);
    echoed(update(s, DISABLE), false);
    echoed(update(s, DISABLE), false);
    assertEquals(2, s.actor().saves);
  }

  @Test void saveFailureKeepsTheOldPreferenceAndReturnsTheActualState() throws Exception {
    var s = new Session();
    s.actor().failSave = true;
    var rsp = update(s, ENABLE);
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(), number(rsp, "retcode"));
    var setting = (DynamicMessage) value(rsp, "player_setting");
    assertEquals(false, value(setting, "HGPFFGKNPFB"));
    assertFalse(enabled(s));
    assertEquals(0, s.actor().saves);
    s.actor().failSave = false;
    echoed(update(s, ENABLE), true);
    assertTrue(enabled(s));
  }

  @Test void preferencesCannotExchangeStaminaOrChangeInventoryProgressOrOtherSettings() throws Exception {
    var s = new Session();
    var before = query(s);
    var gameplay = JSON.toJson(s.owner.getGameplay());
    int stamina = s.owner.getStamina(), jade = s.owner.getHcoin();
    double reserve = s.owner.getStaminaReserve();
    echoed(update(s, ENABLE), true);
    var after = query(s).toBuilder();
    after.clearField(after.getDescriptorForType().findFieldByNumber(711));
    assertEquals(before, after.build());
    assertEquals(gameplay, JSON.toJson(s.owner.getGameplay()));
    assertEquals(stamina, s.owner.getStamina());
    assertEquals(reserve, s.owner.getStaminaReserve());
    assertEquals(jade, s.owner.getHcoin());
    assertEquals(0, s.actor().notifications);
  }

  @Test void malformedUnknownDuplicateWideAndDifferentOptionsGetAnErrorReplyWithoutMutation() throws Exception {
    for (String raw : List.of("", "0a00", "80", "0a03e017", "0a03e01702",
        "0a07e0178180808010", "0a06e01701e01700", "0a03e017010a03e01700",
        "0a03e81701", "0a021001", "0a05e017016801", "0a03e017011001")) {
      var s = new Session();
      var rsp = update(s, HexFormat.of().parseHex(raw));
      assertNotEquals(0, number(rsp, "retcode"), raw);
      assertFalse(enabled(s), raw);
      assertEquals(0, s.actor().saves, raw);
    }
  }

  @Test void unauthenticatedAndLegacyRequestsCannotUpdateSettings() throws Exception {
    var s = new Session();
    s.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(Profile46.handle(s, 45, ENABLE));
    assertNull(s.response);
    assertEquals(0, s.actor().saves);
    s.setState(SessionState.ACTIVE);
    s.version = "4.2.0";
    assertFalse(Profile46.handle(s, 45, ENABLE));
    assertFalse(Profile46.handle(s, 44, ENABLE));
    assertNull(s.response);
  }

  @Test void actualReserveExchangeRemainsSeparateAndUsesItsRealBalance() throws Exception {
    var s = new Session();
    var stamina = Player.class.getDeclaredField("stamina"); stamina.setAccessible(true);
    var reserve = Player.class.getDeclaredField("staminaReserve"); reserve.setAccessible(true);
    stamina.setInt(s.owner, 5); reserve.setDouble(s.owner, 12);
    echoed(update(s, ENABLE), true);
    assertEquals(5, s.owner.getStamina());
    assertEquals(12, s.owner.getStaminaReserve());
    for (int amount : new int[] {5, 99}) {
      var type = s.getProtocol().clientSchema().message("ReserveStaminaExchangeCsReq");
      var request = DynamicMessage.newBuilder(type).setField(type.findFieldByName("num"), amount).build();
      assertFalse(VersionedPacketHandler.handle(s, 28, request.toByteArray()));
      var translated = s.getProtocol().inbound(28, request.toByteArray());
      assertEquals(emu.lunarcore.server.packet.CmdId.ReserveStaminaExchangeCsReq, translated.cmdId());
      new emu.lunarcore.server.packet.recv.HandlerReserveStaminaExchangeCsReq().handle(s, translated.body());
      assertEquals(amount == 5 ? 0 : 1, number(s.response, "retcode"));
      assertEquals(amount == 5 ? 5 : 0, number(s.response, "num"));
      assertEquals(10, s.owner.getStamina());
      assertEquals(7, s.owner.getStaminaReserve());
    }
    assertTrue(enabled(s));
  }

  @Test void preferencesBelongToThePlayerAndSurviveActualMorphiaReloadAndOldDocuments() throws Exception {
    var s = new Session();
    echoed(update(s, ENABLE), true);
    assertFalse(enabled(new Session()));
    var mongo = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "quick-stamina-settings");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var player = JSON.fromJson(s.actor().saved, Player.class);
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(player, 19996991);
      for (int round = 0; round < 2; round++) {
        store.save(player);
        player = store.find(Player.class).first();
        var restored = new Session(); restored.owner = player;
        assertTrue(enabled(restored));
      }
      store.getCollection(Player.class).updateOne(new org.bson.Document("_id", 19996991),
          new org.bson.Document("$unset", new org.bson.Document("quickStaminaRecovery", "")));
      var legacy = new Session(); legacy.owner = store.find(Player.class).first();
      assertFalse(enabled(legacy));
    } finally { mongo.shutdownNow(); }
  }
}
