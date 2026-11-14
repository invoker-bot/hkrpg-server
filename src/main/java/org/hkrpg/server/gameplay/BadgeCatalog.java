package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.enums.ItemSubType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;

/** Exact 4.6 badge/item mappings; only durably held badge items count as earned. */
public final class BadgeCatalog {
  // The pinned native BadgeModule._IsPermanent treats values >= 0xffffffff as permanent.
  public static final long PERMANENT = 0xffffffffL;
  private record Definition(int itemId, String type) {}
  private final Map<Integer, Definition> byBadge;
  private final Map<Integer, Definition> byItem;
  private final Path resourceRoot;

  private BadgeCatalog(Map<Integer, Definition> byBadge, Path resourceRoot) {
    this.byBadge = Map.copyOf(byBadge);
    this.resourceRoot = resourceRoot;
    var items = new HashMap<Integer, Definition>();
    byBadge.values().forEach(def -> items.put(def.itemId(), def));
    this.byItem = Map.copyOf(items);
  }

  public static BadgeCatalog load() throws Exception {
    var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
    if (Files.isDirectory(root.resolve("4.6"))) root = root.resolve("4.6");
    var dir = root.resolve("ExcelOutput");
    var badges = JsonParser.parseString(Files.readString(dir.resolve("BadgeConfig.json"),
        StandardCharsets.UTF_8)).getAsJsonArray();
    var items = JsonParser.parseString(Files.readString(dir.resolve("ItemConfigBadge.json"),
        StandardCharsets.UTF_8)).getAsJsonArray();
    var definitions = new HashMap<Integer, JsonObject>();
    for (var raw : items) {
      var row = raw.getAsJsonObject();
      int id = row.get("ID").getAsInt();
      if (id <= 0 || definitions.putIfAbsent(id, row) != null
          || !row.get("ItemMainType").getAsString().equals("Usable")
          || !row.get("ItemSubType").getAsString().equals("Badge")
          || row.get("PileLimit").getAsInt() != 1)
        throw new IllegalArgumentException("invalid badge item definition");
    }
    var mapping = new TreeMap<Integer, Definition>();
    var usedItems = new HashSet<Integer>();
    for (var raw : badges) {
      var row = raw.getAsJsonObject();
      int badge = row.get("BadgeID").getAsInt(), item = row.get("ItemID").getAsInt();
      String type = row.get("Type").getAsString();
      if (badge <= 0 || item <= 0 || !definitions.containsKey(item)
          || !Set.of("Memory", "Story", "Boss", "Peak", "Activity").contains(type)
          || mapping.putIfAbsent(badge, new Definition(item, type)) != null || !usedItems.add(item))
        throw new IllegalArgumentException("invalid badge mapping");
    }
    if (mapping.isEmpty() || !usedItems.equals(definitions.keySet()))
      throw new IllegalArgumentException("incomplete badge mappings");
    return new BadgeCatalog(mapping, dir);
  }

  public boolean contains(int badge) { return byBadge.containsKey(badge); }

  /** Only activity reward items have a local no-expiry policy; timed types require an actual deadline. */
  public boolean prepareGrant(GameItem item) throws Exception {
    var definition = byItem.get(item.getItemId());
    if (definition == null) return false;
    Long expiry = item.getBadgeExpireTime();
    if (expiry == null && definition.type().equals("Activity") && knownUntimedReward(item.getItemId())) {
      expiry = PERMANENT;
      item.setBadgeExpireTime(expiry);
    }
    return active(expiry);
  }

  private boolean knownUntimedReward(int item) throws Exception {
    int reward = switch (item) { case 230900 -> 3187022; case 230901 -> 3188006; default -> 0; };
    int quest = switch (item) { case 230900 -> 6088075; case 230901 -> 6078007; default -> 0; };
    if (reward == 0) return false;
    JsonObject rewardRow = null, questRow = null;
    var rewards = JsonParser.parseString(Files.readString(resourceRoot.resolve("RewardData.json"),
        StandardCharsets.UTF_8)).getAsJsonArray();
    for (var raw : rewards) {
      var row = raw.getAsJsonObject();
      if (!row.has("RewardID") || row.get("RewardID").getAsInt() != reward) continue;
      if (rewardRow != null) return false;
      rewardRow = row;
    }
    var quests = JsonParser.parseString(Files.readString(resourceRoot.resolve("QuestData.json"),
        StandardCharsets.UTF_8)).getAsJsonArray();
    for (var raw : quests) {
      var row = raw.getAsJsonObject();
      if (!row.has("QuestID") || row.get("QuestID").getAsInt() != quest) continue;
      if (questRow != null) return false;
      questRow = row;
    }
    return rewardRow != null && rewardRow.keySet().equals(Set.of("RewardID", "ItemID_1", "Count_1"))
        && rewardRow.get("ItemID_1").getAsInt() == item && rewardRow.get("Count_1").getAsInt() == 1
        && questRow != null && questRow.get("RewardID").getAsInt() == reward
        && questRow.get("QuestType").getAsInt() == 3;
  }

  private static boolean active(Long expiry) {
    return expiry != null && expiry > 0 && expiry <= PERMANENT
        && (expiry == PERMANENT || expiry > Instant.now().getEpochSecond());
  }

  public Map<Integer, Long> owned(Player player) {
    var result = new TreeMap<Integer, Long>();
    if (player.getUid() <= 0) return result;
    var inventory = player.getInventory();
    for (var entry : byBadge.entrySet()) {
      int id = entry.getValue().itemId();
      var item = inventory.getMaterialByItemId(id);
      var definition = GameData.getItemExcelMap().get(id);
      if (item == null) continue;
      if (definition == null || item.getExcel() != definition || definition.getItemMainType() != ItemMainType.Usable
          || definition.getItemSubType() != ItemSubType.Badge || definition.getPileLimit() != 1
          || item.getItemId() != id || item.getOwnerUid() != player.getUid()
          || item.getInternalUid() <= 0 || inventory.getItemByUid(item.getInternalUid()) != item
          || item.getCount() != 1) {
        diagnostic(player, entry.getKey(), "badge-item-invalid");
        continue;
      }
      Long expiry = item.getBadgeExpireTime();
      if (expiry == null) diagnostic(player, entry.getKey(), "badge-expiry-unknown");
      else if (expiry <= 0 || expiry > PERMANENT) diagnostic(player, entry.getKey(), "badge-expiry-invalid");
      else if (active(expiry)) result.put(entry.getKey(), expiry);
    }
    return Collections.unmodifiableMap(result);
  }

  private static void diagnostic(Player player, int badge, String reason) {
    GameDiagnostics.outcome(player.getSession(), "GetBadgeInfoCsReq", badge,
        Retcode.PLAYER_DATA_ERROR, reason, "");
  }
}
