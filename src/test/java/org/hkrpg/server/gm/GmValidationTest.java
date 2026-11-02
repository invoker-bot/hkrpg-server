package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.Test;

class GmValidationTest {
  @Test void activityWindowNeedsGlobalScopeAndEitherWholeDaysOrCompleteProtocolTimes() {
    String base="\"action\":\"activity-open\",\"activityId\":30013";
    for(var fields:List.of(",\"durationDays\":0",",\"durationDays\":366",",\"uid\":1",
        ",\"beginTime\":1",",\"endTime\":2",",\"beginTime\":-1,\"endTime\":2",
        ",\"beginTime\":2,\"endTime\":2",",\"beginTime\":2,\"endTime\":1",
        ",\"beginTime\":4294967294,\"endTime\":4294967296",
        ",\"beginTime\":1,\"endTime\":31622402",
        ",\"beginTime\":1,\"endTime\":2,\"durationDays\":1"))
      assertThrows(GmError.class,()->GmValidation.validate(parse(base+fields)),fields);
    for(var fields:List.of("",",\"durationDays\":365",",\"beginTime\":4294967294,\"endTime\":4294967295"))
      assertDoesNotThrow(()->GmValidation.validate(parse(base+fields)));
    for(var action:List.of("activity-open","activity-stop","activity-enable"))
      assertThrows(GmError.class,()->GmValidation.validate(parse("\"action\":\""+action+"\"")));
  }
  static Operation parse(String fields) {
    return new Gson()
        .fromJson("{\"requestId\":\"" + UUID.randomUUID() + "\"," + fields + "}", Operation.class);
  }

  @Test
  void shopOperationsRejectEmptyDuplicateInvalidAndOversizedTargets() {
    for (var fields : List.of(
        "\"shopGoods\":[]", "\"shopGoods\":[null]", "\"shopGoods\":[{}]",
        "\"shopGoods\":[{\"shopId\":101,\"goodsId\":-1}]",
        "\"shopGoods\":[{\"shopId\":101,\"goodsId\":1},{\"shopId\":101,\"goodsId\":1}]",
        "\"shopGoods\":[" + String.join(",", Collections.nCopies(501,"{\"shopId\":101,\"goodsId\":1}")) + "]"))
      assertThrows(GmError.class, () -> GmValidation.validate(parse("\"action\":\"shop-unlist\"," + fields)));
    assertThrows(GmError.class, () -> GmValidation.validate(parse("\"action\":\"shop-list\"")));
    assertDoesNotThrow(() -> GmValidation.validate(parse(
        "\"action\":\"shop-list\",\"shopGoods\":[{\"shopId\":101,\"goodsId\":1},{\"shopId\":102,\"goodsId\":1}]")));
  }

  @Test
  void rejectsMissingTargetsAmountsAndCommandTargetAmbiguity() {
    for (var fields :
        List.of(
            "\"action\":\"grant\",\"uid\":10001,\"itemId\":101,\"amount\":0",
            "\"action\":\"heal\",\"uid\":0",
            "\"action\":\"grant\",\"uid\":10001,\"itemId\":101,\"amount\":1000001",
            "\"action\":\"command\",\"command\":\"heal @10001\"",
            "\"action\":\"command\",\"command\":\"heal\\nstop\"",
            "\"action\":\"mail\",\"uid\":10001,\"title\":\"t\",\"sender\":\"s\",\"content\":\"c\",\"attachments\":[{\"itemId\":101,\"amount\":1},{\"itemId\":101,\"amount\":2}]"))
      assertThrows(GmError.class, () -> GmValidation.validate(parse(fields)), fields);
    assertDoesNotThrow(
        () ->
            GmValidation.validate(
                parse("\"action\":\"grant\",\"uid\":10001,\"itemId\":101,\"amount\":37")));
  }

}
