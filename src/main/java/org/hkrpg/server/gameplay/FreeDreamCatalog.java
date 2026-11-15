package org.hkrpg.server.gameplay;

import com.google.gson.JsonParser;
import emu.lunarcore.LunarCore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The six local CN coin offers, enabled only when the fixed source data matches. */
public final class FreeDreamCatalog {
  public static final String FREE_TIER = "Local_Free";
  public record Offer(String productId, String originalTier, int base, int normalBonus) {
    public int amount() { return Math.addExact(base, normalBonus); }
  }
  private static final List<Offer> EXPECTED = List.of(
      new Offer("rpgchncoin60tier1", "Tier_1", 60, 0),
      new Offer("rpgchncoin300tier5", "Tier_5", 300, 30),
      new Offer("rpgchncoin980tier15", "Tier_15", 980, 110),
      new Offer("rpgchncoin1980tier30", "Tier_30", 1980, 260),
      new Offer("rpgchncoin3280tier50", "Tier_50", 3280, 600),
      new Offer("rpgchncoin6480tier60", "Tier_60", 6480, 1600));
  private static volatile List<Offer> offers = List.of();

  public static synchronized boolean load(Path root) {
    offers = List.of();
    try {
      var raw = Files.readString(root.resolve("ExcelOutput/RechargeConfig.json"), StandardCharsets.UTF_8);
      var values = JsonParser.parseString(raw).getAsJsonArray();
      var seen = new java.util.HashMap<String, com.google.gson.JsonObject>();
      for (var value : values) {
        var row = value.getAsJsonObject();
        String id = row.get("ProductID").getAsString();
        if (EXPECTED.stream().anyMatch(entry -> entry.productId().equals(id))
            && seen.putIfAbsent(id, row) != null) throw new IllegalArgumentException("duplicate product");
      }
      for (var expected : EXPECTED) {
        var row = seen.get(expected.productId());
        if (row == null || row.get("GiftType").getAsInt() != 1
            || !row.get("TierID").getAsString().equals(expected.originalTier())
            || row.get("FirstCharge").getAsInt() != expected.base()
            || (row.has("NormalCharge") ? row.get("NormalCharge").getAsInt() : 0) != expected.normalBonus())
          throw new IllegalArgumentException("product source mismatch");
      }
      offers = EXPECTED;
      return true;
    } catch (Exception failure) {
      LunarCore.getLogger().warn("Free dream catalog skipped: reason=resource-mismatch");
      return false;
    }
  }

  public static List<Offer> offers() { return offers; }
  public static Offer find(String id) {
    return offers.stream().filter(offer -> offer.productId().equals(id)).findFirst().orElse(null);
  }
  private FreeDreamCatalog() {}
}
