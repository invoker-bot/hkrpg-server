package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.hkrpg.server.gameplay.FreeDreamCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Original price tiers must reach all four local recommendation categories. */
class RecommendCashGift46Test {
  @BeforeAll static void catalog() {
    assertTrue(FreeDreamCatalog.load(Path.of("resources/4.6")));
    assertTrue(org.hkrpg.server.gameplay.RecommendGiftCatalog.load(Path.of("resources/4.6")));
  }

  @Test void recommendationCategoriesAppearAlongsideTheExistingFreeDreamOffers() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    session.player.setSession(session);
    session.request("QueryProductInfoCsReq");
    @SuppressWarnings("unchecked")
    var products = (List<DynamicMessage>) InitialData46Test.field(session.response, "product_list");
    var tiers = products.stream().collect(Collectors.toMap(
        p -> (String) InitialData46Test.field(p, "product_id"),
        p -> (String) InitialData46Test.field(p, "price_tier")));
    assertEquals("Local_Free", tiers.get("rpgchncoin60tier1"));
    assertEquals(Map.of("rpgchnyear3tier1", "Tier_1", "rpgchnyear3tier5", "Tier_5",
        "rpgchnticket3tier15", "Tier_15", "rpgchnyear4tiercny100", "Alternate_Tier_A"),
        tiers.entrySet().stream().filter(e -> !e.getValue().equals("Local_Free"))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    var expected=Map.of("rpgchnyear3tier1", List.of(15,1,2), "rpgchnyear3tier5", List.of(16,1,3),
        "rpgchnticket3tier15", List.of(22,2,1), "rpgchnyear4tiercny100", List.of(25,1,1));
    for (var product : products) {
      var id=(String) InitialData46Test.field(product,"product_id");
      if (!expected.containsKey(id)) continue;
      var kind=(com.google.protobuf.Descriptors.EnumValueDescriptor) InitialData46Test.field(product,"gift_type");
      assertEquals(expected.get(id),List.of(kind.getNumber(),InitialData46Test.field(product,"max_buy_times"),
          InitialData46Test.field(product,"gift_version")));
      assertEquals(0,InitialData46Test.field(product,"buy_times"));
    }
    assertEquals(0, session.player.getMcoin(), "Viewing the catalog does not grant currency");
  }
  @Test void anUnpreparedGiftClaimIsRejectedWithoutGrantingAnything() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    session.player.setSession(session);
    var d = session.getProtocol().clientSchema().message("CancelAutoConversionCacheCsReq");
    var request = DynamicMessage.newBuilder(d);
    request.addRepeatedField(d.findFieldByName("OKFMBDEECCH"), "rpgchnyear3tier1");
    var type = d.findFieldByName("type");
    request.setField(type, type.getEnumType().findValueByNumber(2));
    assertTrue(VersionedPacketHandler.handle(session, 4183, request.build().toByteArray()));
    assertNotEquals(0, InitialData46Test.field(session.response, "retcode"));
    assertEquals(0, session.player.getMcoin());
    assertTrue(session.player.getGameplay().getRewards().isEmpty());
  }

  @Test void giftAcknowledgementOnlyConsumesItsOriginalPriceTier() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    session.player.setSession(session);
    session.request("QueryProductInfoCsReq");
    String original = session.localDreamOfferRequest("rpgchnyear3tier5");
    session.markLocalRechargeNotice("rpgchnyear3tier5");
    assertTrue(VersionedPacketHandler.handle(session, 4183, request(session, "Local_Free")));
    assertNotEquals(0, InitialData46Test.field(session.response, "retcode"));
    assertEquals(original, session.localDreamOfferRequest("rpgchnyear3tier5"));
    assertTrue(VersionedPacketHandler.handle(session, 4183, request(session, "Tier_5")));
    assertEquals(0, InitialData46Test.field(session.response, "retcode"));
    assertNotEquals(original, session.localDreamOfferRequest("rpgchnyear3tier5"));
    assertFalse(VersionedPacketHandler.handle(session, 4183, request(session, "Tier_5")));
  }

  @Test void giftQueriesDoNotInventAReceiptAndUnknownDailyClaimIsRejected() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    session.player.setSession(session);
    session.request("GetRechargeGiftInfoCsReq");
    assertEquals(0, InitialData46Test.field(session.response, "retcode"));
    assertEquals(List.of(), InitialData46Test.field(session.response, "OFIHOCCHNCN"));
    var d=session.getProtocol().clientSchema().message("TakeRechargeGiftRewardCsReq");
    var request=DynamicMessage.newBuilder(d).setField(d.findFieldByName("gift_type"),16).build();
    assertTrue(VersionedPacketHandler.handle(session, 8377, request.toByteArray()));
    assertNotEquals(0, InitialData46Test.field(session.response, "retcode"));
    assertTrue(session.player.getGameplay().getRewards().isEmpty());
  }
  private static byte[] request(Gameplay46HandlerTest.Session session, String id) {
    var d=session.getProtocol().clientSchema().message("CancelAutoConversionCacheCsReq");
    var b=DynamicMessage.newBuilder(d);
    b.addRepeatedField(d.findFieldByName("OKFMBDEECCH"),id);
    var type=d.findFieldByName("type");b.setField(type,type.getEnumType().findValueByNumber(2));
    return b.build().toByteArray();
  }

}
