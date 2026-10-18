package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GmValidationTest {
  static Operation parse(String fields) {
    return new Gson()
        .fromJson("{\"requestId\":\"" + UUID.randomUUID() + "\"," + fields + "}", Operation.class);
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

  @Test
  void createsUniquePersistentKeyAndDoesNotReplaceInvalidOperatorFiles(@TempDir Path directory)
      throws Exception {
    new GmAuth(directory, Clock.systemUTC());
    var path = directory.resolve("gm-console.key");
    var key = Files.readString(path);
    assertTrue(key.matches("[A-Za-z0-9_-]{43}"));
    new GmAuth(directory, Clock.systemUTC());
    assertEquals(key, Files.readString(path));
    Files.writeString(path, "invalid-existing-operator-key");
    assertThrows(java.io.IOException.class, () -> new GmAuth(directory, Clock.systemUTC()));
    assertEquals("invalid-existing-operator-key", Files.readString(path));
  }
}
