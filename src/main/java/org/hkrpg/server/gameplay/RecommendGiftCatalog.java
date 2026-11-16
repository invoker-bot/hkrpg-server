package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local reissue of the four recommendation gift categories in the pinned client resources. */
public final class RecommendGiftCatalog {
  public record Offer(String productId, int type, String tier, int max, int version,
                      Map<Integer, Integer> initialRewards) {}
  private static final List<Offer> EXPECTED = List.of(
      new Offer("rpgchnyear3tier1", 15, "Tier_1", 1, 2, Map.of(102, 1, 101, 1, 2, 30000)),
      new Offer("rpgchnyear3tier5", 16, "Tier_5", 1, 3, Map.of(3, 320, 201, 2)),
      new Offer("rpgchnticket3tier15", 22, "Tier_15", 2, 1, Map.of(102, 10, 2, 100000)),
      new Offer("rpgchnyear4tiercny100", 25, "Alternate_Tier_A", 1, 1, Map.of(102, 1, 2, 10000)));
  private static volatile List<Offer> offers = List.of();
  public static final Map<Integer, Integer> DAILY_REWARDS = Map.of(102, 1, 201, 2);

  public static synchronized boolean load(Path root) {
    offers = List.of();
    try {
      var recharge = read(root, "RechargeConfig");
      var gifts = read(root, "RechargeGiftConfig");
      var data = read(root, "RechargeGiftData");
      var rewards = read(root, "RewardData");
      for (var offer : EXPECTED) {
        var row = find(recharge, "ProductID", offer.productId());
        if (number(row, "GiftType") != offer.type() || !row.get("TierID").getAsString().equals(offer.tier()))
          throw new IllegalArgumentException("product mismatch");
        var config = find(gifts, "GiftType", Integer.toString(offer.type()));
        int[] ids = switch (offer.type()) {
          case 15 -> new int[]{10010}; case 16 -> new int[]{10020,10022,10023,10024,10025};
          case 22 -> new int[]{10040}; case 25 -> new int[]{10070}; default -> throw new IllegalStateException();
        };
        var list = config.getAsJsonArray("GiftIDList");
        if (list.size() != ids.length) throw new IllegalArgumentException("gift list mismatch");
        for (int i=0; i<ids.length; i++) if (list.get(i).getAsInt()!=ids[i]) throw new IllegalArgumentException("gift list mismatch");
        var first = find(data, "GiftID", Integer.toString(ids[0]));
        var actual = reward(rewards, number(first, "RewardsPay"));
        reward(rewards, number(first, "RewardsFree")).forEach((id, amount) -> actual.merge(id, amount, Math::addExact));
        int coins = number(first,"McoinPay") + number(first,"McoinFree");
        if (coins != 0) actual.merge(3, coins, Math::addExact);
        if (!actual.equals(offer.initialRewards())) throw new IllegalArgumentException("reward mismatch");
        for (int i=1; i<ids.length; i++) {
          var daily = find(data, "GiftID", Integer.toString(ids[i]));
          if (number(daily,"Days") != i+1 || number(daily,"RewardsPay") != 0
              || number(daily,"McoinPay") != 0 || number(daily,"McoinFree") != 0
              || !reward(rewards, number(daily,"RewardsFree")).equals(DAILY_REWARDS))
            throw new IllegalArgumentException("daily reward mismatch");
        }
      }
      offers = EXPECTED;
      return true;
    } catch (Exception failure) {
      LunarCore.getLogger().warn("Recommendation gift catalog skipped: reason=resource-mismatch");
      return false;
    }
  }
  private static JsonArray read(Path root, String name) throws Exception {
    return JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/" + name + ".json"), StandardCharsets.UTF_8)).getAsJsonArray();
  }
  private static JsonObject find(JsonArray rows, String key, String value) {
    JsonObject match = null;
    for (var element : rows) {
      var row = element.getAsJsonObject();
      if (row.has(key) && row.get(key).getAsString().equals(value)) {
        if (match != null) throw new IllegalArgumentException("duplicate " + key);
        match = row;
      }
    }
    if (match == null) throw new IllegalArgumentException("missing " + key);
    return match;
  }
  private static int number(JsonObject row, String key) { return row.has(key) ? row.get(key).getAsInt() : 0; }
  private static Map<Integer,Integer> reward(JsonArray rows, int id) {
    var result = new HashMap<Integer,Integer>();
    if (id == 0) return result;
    var row = find(rows,"RewardID",Integer.toString(id));
    for (int i=1; i<=10; i++) {
      int item = number(row,"ItemID_"+i), count = number(row,"Count_"+i);
      if (item == 0 && count == 0) continue;
      if (item <= 0 || count <= 0) throw new IllegalArgumentException("invalid reward");
      result.merge(item,count,Math::addExact);
    }
    return result;
  }
  public static List<Offer> offers() { return offers; }
  public static Offer find(String id) { return offers.stream().filter(o -> o.productId().equals(id)).findFirst().orElse(null); }
  public static Offer byType(int type) { return offers.stream().filter(o -> o.type()==type).findFirst().orElse(null); }
  private RecommendGiftCatalog() {}
}
