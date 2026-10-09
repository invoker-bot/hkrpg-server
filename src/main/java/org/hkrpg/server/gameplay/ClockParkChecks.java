package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Original checkpoint conditions; accepting CRLF checkout bytes does not change any table value.
 */
public final class ClockParkChecks {
  public static final String SOURCE =
      "4886a29bbb9195e93a8b3196bdc6b2aa697707baab819902c29449796202fd71";

  private record Check(String type, int a, int b, int c) {}

  private final Map<Integer, Check> checks;

  private ClockParkChecks(Map<Integer, Check> checks) {
    this.checks = Map.copyOf(checks);
  }

  public static ClockParkChecks load(Path root) {
    try {
      var path = root.resolve("ExcelOutput/ClockParkCheckPoint.json");
      if (Files.size(path) <= 0 || Files.size(path) > 65536) return null;
      String text = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
      if (!SOURCE.equals(
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(text.getBytes(StandardCharsets.UTF_8))))) return null;
      var checks = new HashMap<Integer, Check>();
      for (var value : JsonParser.parseString(text).getAsJsonArray()) {
        var row = value.getAsJsonObject();
        int id = row.get("CheckPointID").getAsInt();
        String type = row.get("CheckPointType").getAsString();
        int a = number(row, "CheckParam1"),
            b = number(row, "CheckParam2"),
            c = number(row, "CheckParam3");
        if (id <= 0
            || a < 0
            || b < 0
            || c < 0
            || !Set.of("AttrGreaterEqual", "AttrSumGreaterEqual").contains(type)
            || type.equals("AttrSumGreaterEqual") && (a == 0 || b != 0 || c != 0)
            || checks.putIfAbsent(id, new Check(type, a, b, c)) != null) return null;
      }
      return checks.size() == 17 ? new ClockParkChecks(checks) : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  private static int number(JsonObject row, String field) {
    return row.has(field) ? row.get(field).getAsInt() : 0;
  }

  public boolean passed(int id, int a, int b, int c) {
    var check = checks.get(id);
    if (check == null || a < 0 || b < 0 || c < 0)
      throw new IllegalArgumentException("Invalid checkpoint attributes");
    return check.type.equals("AttrGreaterEqual")
        ? a >= check.a && b >= check.b && c >= check.c
        : (long) a + b + c >= check.a;
  }
}
