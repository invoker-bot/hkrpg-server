package org.hkrpg.server.protocol;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;

/** Return an empty local statistics set without pretending that recommendation data exists. */
public final class AllRecommendation46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 2426) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var schema = session.getProtocol().clientSchema();
    var response = Gameplay46.builder(session, "GetBigDataAllRecommendScRsp");
    var typeField = response.getDescriptorForType().findFieldByName("big_data_recommend_type");
    var descriptor = schema.message("GetBigDataAllRecommendCsReq");
    int kind = 0;
    boolean valid = Parkour46.wireValid(descriptor, bytes, 0);
    if (valid) {
      var request = schema.parse("GetBigDataAllRecommendCsReq", bytes);
      valid = request.getUnknownFields().asMap().isEmpty()
          && Gameplay46.only(request, "big_data_recommend_type");
      kind = ((EnumValueDescriptor) Gameplay46.value(request, "big_data_recommend_type")).getNumber();
    }
    valid &= kind >= 3 && kind <= 8;
    if (kind != 0) {
      var type = typeField.getEnumType().findValueByNumber(kind);
      if (type != null) response.setField(typeField, type);
    }
    if (valid) {
      String container = kind == 3 ? "relic_avatar" : kind == 4 ? "avatar_relic" : "KHCMLINMGEN";
      var field = response.getDescriptorForType().findFieldByName(container);
      response.setField(field, DynamicMessage.newBuilder(field.getMessageType()).build());
    } else Gameplay46.set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
    session.sendClientPacket("GetBigDataAllRecommendScRsp", response.build().toByteArray());
    return true;
  }

  private AllRecommendation46() {}
}
