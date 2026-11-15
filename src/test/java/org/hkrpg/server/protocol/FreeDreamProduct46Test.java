package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.hkrpg.server.gameplay.FreeDreamCatalog;

/** Native 4.6 product requests must complete with the six local free coin offers. */
class FreeDreamProduct46Test {
  @BeforeAll static void loadCatalog() {
    assertTrue(FreeDreamCatalog.load(Path.of("resources/4.6")));
  }
  @Test
  void queryReturnsOnlyTheSixRealCnCoinProductsWithASeparateFreeTier() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    int before = session.player.getMcoin();
    session.request("QueryProductInfoCsReq");
    assertEquals("QueryProductInfoScRsp", session.responseName);
    assertEquals(0, InitialData46Test.field(session.response, "retcode"));
    @SuppressWarnings("unchecked")
    var products = (List<DynamicMessage>) InitialData46Test.field(session.response, "product_list");
    assertEquals(List.of("rpgchncoin60tier1", "rpgchncoin300tier5", "rpgchncoin980tier15",
            "rpgchncoin1980tier30", "rpgchncoin3280tier50", "rpgchncoin6480tier60"),
        products.stream().map(p -> InitialData46Test.field(p, "product_id")).toList());
    for (var product : products) {
      assertEquals("Local_Free", InitialData46Test.field(product, "price_tier"));
      assertEquals(1, ((com.google.protobuf.Descriptors.EnumValueDescriptor)
          InitialData46Test.field(product, "gift_type")).getNumber());
      assertEquals(false, InitialData46Test.field(product, "double_reward"));
    }
    assertEquals(before, session.player.getMcoin(), "Looking at the catalog must not grant currency");
  }

  @Test
  void malformedProductQueryDoesNotGrantOrReturnAProductCatalog() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    assertTrue(VersionedPacketHandler.handle(session, 96, new byte[] {8, 1}));
    assertEquals("QueryProductInfoScRsp", session.responseName);
    assertNotEquals(0, InitialData46Test.field(session.response, "retcode"));
    assertEquals(List.of(), InitialData46Test.field(session.response, "product_list"));
    assertEquals(0, session.player.getMcoin());
  }

  @Test
  void nativeLocalClaimIsBoundToTheSessionAndDeduplicatedUntilTheRewardIsClosed() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    session.player.setSession(session);
    var claim = localRechargeRequest(session, "rpgchncoin60tier1");
    assertTrue(VersionedPacketHandler.handle(session, 4183, claim), "An unprepared claim must be rejected explicitly");
    assertNotEquals(0, InitialData46Test.field(session.response, "retcode"));
    assertEquals(0, session.player.getMcoin());
    session.request("QueryProductInfoCsReq");
    assertTrue(VersionedPacketHandler.handle(session, 4183, claim));
    assertEquals("CancelAutoConversionCacheScRsp", session.responseName);
    assertEquals(0, InitialData46Test.field(session.response, "retcode"));
    assertEquals(60, session.player.getMcoin());
    assertTrue(VersionedPacketHandler.handle(session, 4183, claim));
    assertEquals(60, session.player.getMcoin(), "Network retry cannot grant twice");
    assertTrue(VersionedPacketHandler.handle(session, 4183, localRechargeRequest(session, "Local_Free")));
    assertEquals(0, InitialData46Test.field(session.response, "retcode"));
    assertTrue(VersionedPacketHandler.handle(session, 4183, claim));
    assertEquals(120, session.player.getMcoin(), "Closing the reward allows a later deliberate claim");
  }

  @Test
  void nativeLocalClaimCannotRunBeforeLoginOrTreatOtherProductsAsFree() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    session.player.setSession(session);
    session.request("QueryProductInfoCsReq");
    session.setState(emu.lunarcore.server.packet.SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(session, 4183, localRechargeRequest(session, "rpgchncoin60tier1")));
    assertEquals(0, session.player.getMcoin());
    session.setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    assertFalse(VersionedPacketHandler.handle(session, 4183, localRechargeRequest(session, "rpgchnmonthcardtier5")));
    assertEquals(0, session.player.getMcoin());
  }

  private static byte[] localRechargeRequest(Gameplay46HandlerTest.Session session, String id) {
    var descriptor = session.getProtocol().clientSchema().message("CancelAutoConversionCacheCsReq");
    var request = DynamicMessage.newBuilder(descriptor);
    request.addRepeatedField(descriptor.findFieldByName("OKFMBDEECCH"), id);
    var type = descriptor.findFieldByName("type");
    request.setField(type, type.getEnumType().findValueByNumber(2));
    return request.build().toByteArray();
  }

  @Test
  void closingTheLocalRechargeRewardAcknowledgesOnlyItsOwnConfirmedTier() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    session.player.setSession(session);
    session.request("QueryProductInfoCsReq");
    var originalRequest = session.localDreamOfferRequest("rpgchncoin60tier1");
    assertNotNull(originalRequest);
    session.request("QueryProductInfoCsReq");
    assertEquals(originalRequest, session.localDreamOfferRequest("rpgchncoin60tier1"));
    FreeDreamProduct46.granted(session.player, "rpgchncoin60tier1", 60);
    assertEquals("RechargeSuccNotify", session.responseName);
    var itemList = (DynamicMessage) InitialData46Test.field(session.response, "item_list");
    var item = InitialData46Test.first(itemList, "item_list");
    assertEquals(3, InitialData46Test.field(item, "item_id"));
    assertEquals(60, InitialData46Test.field(item, "num"));

    var descriptor = session.getProtocol().clientSchema().message("CancelAutoConversionCacheCsReq");
    var request = DynamicMessage.newBuilder(descriptor);
    request.addRepeatedField(descriptor.findFieldByName("OKFMBDEECCH"), "Local_Free");
    var type = descriptor.findFieldByName("type");
    request.setField(type, type.getEnumType().findValueByNumber(2));
    var incorrect = request.clone().setRepeatedField(descriptor.findFieldByName("OKFMBDEECCH"), 0, "Tier_1");
    assertTrue(VersionedPacketHandler.handle(session, 4183, incorrect.build().toByteArray()));
    assertNotEquals(0, InitialData46Test.field(session.response, "retcode"));
    assertEquals(originalRequest, session.localDreamOfferRequest("rpgchncoin60tier1"));
    assertTrue(VersionedPacketHandler.handle(session, 4183, request.build().toByteArray()));
    assertEquals("CancelAutoConversionCacheScRsp", session.responseName);
    assertEquals(0, InitialData46Test.field(session.response, "retcode"));
    assertNotEquals(originalRequest, session.localDreamOfferRequest("rpgchncoin60tier1"));
    assertFalse(VersionedPacketHandler.handle(session, 4183, request.build().toByteArray()),
        "A local receipt must not acknowledge unrelated later conversion requests");
  }
}
