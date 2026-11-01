package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.Test;

class GmValidationTest {
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
