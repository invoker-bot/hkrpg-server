package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.AvatarUpgrade46Test.scalar;
import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Empty local statistics still need a native response matching the client's requested kind. */
class AllRecommendation46Test {
  @ParameterizedTest
  @ValueSource(ints = {3, 4, 5, 6, 7, 8, 9})
  void allRecommendationQueryCompletesWithoutInventingStatistics(int kind) throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    assertTrue(VersionedPacketHandler.handle(session, 2426, scalar(9, kind)));
    assertEquals("GetBigDataAllRecommendScRsp", session.responseName);
    var response = session.response;
    var descriptor = response.getDescriptorForType();
    assertEquals(0, response.getField(descriptor.findFieldByName("retcode")));
    assertEquals(kind, ((EnumValueDescriptor) response.getField(descriptor.findFieldByName("big_data_recommend_type"))).getNumber());
    String container = kind == 3 ? "relic_avatar" : kind == 4 ? "avatar_relic" : "KHCMLINMGEN";
    var field = descriptor.findFieldByName(container);
    assertTrue(response.hasField(field));
    assertTrue(((com.google.protobuf.DynamicMessage) response.getField(field)).getAllFields().isEmpty());
  }

  @Test
  void malformedAndUnknownKindsGetAnErrorWithoutDisconnectingOrReturningFakeData() throws Exception {
    for (byte[] bytes : java.util.List.of(new byte[] {72}, scalar(9, 11),
        org.hkrpg.server.protocol.AvatarUpgrade46Test.join(scalar(9, 3), scalar(9, 4)), scalar(15, 1))) {
      var session = new Gameplay46HandlerTest.Session();
      assertTrue(VersionedPacketHandler.handle(session, 2426, bytes));
      assertEquals("GetBigDataAllRecommendScRsp", session.responseName);
      var response = session.response;
      assertEquals(5, response.getField(response.getDescriptorForType().findFieldByName("retcode")));
      assertFalse(response.hasField(response.getDescriptorForType().findFieldByName("relic_avatar")));
      assertFalse(response.hasField(response.getDescriptorForType().findFieldByName("avatar_relic")));
      assertFalse(response.hasField(response.getDescriptorForType().findFieldByName("KHCMLINMGEN")));
    }
  }
}
