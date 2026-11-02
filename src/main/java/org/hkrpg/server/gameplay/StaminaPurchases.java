package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.Set;

/** Verified original jade-only sale table; malformed or missing resources cannot grant power. */
public final class StaminaPurchases {
  public static final int DAILY_LIMIT = 8;
  public record Offer(int price, int stamina, int storageLimit) {}
  public record Result(Retcode retcode, int added, int price, int times) {}

  public static Result failure(Retcode retcode, int times) {
    return new Result(retcode, 0, 0, Math.max(0, times));
  }

  public static Offer offer(int times) {
    if (times < 1 || times > DAILY_LIMIT) return null;
    try {
      var power = GameData.getItemExcelMap().get(11);
      if (power == null || power.getId() != 11 || power.getPileLimit() <= 0
          || GameData.getItemExcelMap().get(1) == null) return null;
      var file = Path.of(LunarCore.getConfig().getResourceDir(), "ExcelOutput", "StaminaSaleConfig.json");
      if (Files.size(file) > 65536) return null;
      var rows = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
      if (rows.size() != DAILY_LIMIT) return null;
      var offers = new Offer[DAILY_LIMIT];
      for (var raw : rows) {
        var row = raw.getAsJsonObject();
        if (!row.keySet().equals(Set.of("Times", "Price", "ToStamina"))) return null;
        int ordinal = integer(row.get("Times")), stamina = integer(row.get("ToStamina"));
        var price = row.getAsJsonObject("Price");
        if (ordinal < 1 || ordinal > DAILY_LIMIT || offers[ordinal - 1] != null
            || !price.keySet().equals(Set.of("1"))) return null;
        int jade = integer(price.get("1"));
        if (jade <= 0 || stamina <= 0 || stamina > power.getPileLimit()) return null;
        offers[ordinal - 1] = new Offer(jade, stamina, power.getPileLimit());
      }
      return offers[times - 1];
    } catch (java.io.IOException | RuntimeException invalid) {
      return null;
    }
  }

  private static int integer(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("An integer sale value is required");
    return value.getAsBigDecimal().intValueExact();
  }
  private StaminaPurchases() {}
}
