package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Classic DiceCombat resources only; V2 stages and completion reports have separate contracts. */
public record DiceCombatCatalog(
    int activity,
    int panel,
    Set<Integer> modules,
    Map<Integer, Avatar> avatars,
    Set<Integer> dice,
    Map<Integer, String> colors,
    Map<Integer, Shop> shops,
    Map<Integer, Good> goods) {
  public record Avatar(
      int id,
      int rarity,
      List<Integer> initialDice,
      List<Integer> initialSlots,
      List<Integer> counts) {
    /** The native avatar model takes one ID per rarity and reads copy counts from its resource. */
    public List<Integer> wireSlots(List<Integer> physical) {
      if (physical == null || physical.size() != counts.stream().mapToInt(Integer::intValue).sum())
        return null;
      var slots = new ArrayList<Integer>();
      int cursor = 0;
      for (int count : counts) {
        if (count == 0) {
          slots.add(0);
          continue;
        }
        var die = physical.get(cursor);
        if (die == null || die <= 0) return null;
        for (int n = 0; n < count; n++) if (!die.equals(physical.get(cursor++))) return null;
        slots.add(die);
      }
      return List.copyOf(slots);
    }
  }

  public record Shop(int id, List<Integer> goods) {}

  public record Gate(String type, String parameter) {}

  public record Good(int id, int item, Map<Integer, Integer> costs, List<Gate> gates) {
    public boolean unlocked(Player player) {
      for (var gate : gates) {
        if ("OfferingLevel".equals(gate.type())) {
          if (!gate.parameter().matches("[1-9][0-9]{0,8}-[1-9][0-9]{0,8}")) return false;
          var parts = gate.parameter().split("-");
          if (!Offerings.unlocked(player, Integer.parseInt(parts[0]), Integer.parseInt(parts[1])))
            return false;
          continue;
        }
        if (!"FinishSubMission".equals(gate.type()) || !gate.parameter().matches("[1-9][0-9]{0,8}"))
          return false;
        int id = Integer.parseInt(gate.parameter());
        var state = player.getMissionManager().sub(id);
        if (state == null
            || state.getQuestId() != id
            || state.getPhase() == QuestState.Phase.DOING
            || player.getGameplay().getCancelledSubs().contains(id)) return false;
      }
      return true;
    }
  }

  public boolean available(Player player, Instant now) {
    return GameplayDefinitions.windows.stream()
        .anyMatch(
            w ->
                w.panelId() == panel
                    && modules.contains(w.moduleId())
                    && w.beginTime() >= 0
                    && w.beginTime() < w.endTime()
                    && w.beginTime() <= now.getEpochSecond()
                    && now.getEpochSecond() < w.endTime()
                    && ActivityAccess.unlocked(player, panel));
  }

  private static List<JsonObject> rows(Path root, String name) throws java.io.IOException {
    var json = JsonParser.parseString(Files.readString(root.resolve(name + ".json")));
    var values =
        json.isJsonArray()
            ? json.getAsJsonArray().asList()
            : json.getAsJsonObject().asMap().values();
    return values.stream().map(JsonElement::getAsJsonObject).toList();
  }

  private static int integer(JsonElement value, boolean zero) {
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsString().matches(zero ? "0|[1-9][0-9]*" : "[1-9][0-9]*"))
      throw new IllegalArgumentException("Invalid DiceCombat resource integer");
    return Integer.parseInt(value.getAsString());
  }

  private static int id(JsonObject row, String key) {
    return integer(row.get(key), false);
  }

  private static List<Integer> numbers(JsonObject row, String key, boolean zero) {
    return row.getAsJsonArray(key).asList().stream().map(e -> integer(e, zero)).toList();
  }

  /**
   * Read a complete immutable catalog. Missing or ambiguous resources cannot expose a partial shop.
   */
  public static DiceCombatCatalog read() {
    var root = Path.of(LunarCore.getConfig().getResourceDir(), "ExcelOutput");
    try {
      var constants = new HashMap<String, JsonObject>();
      for (var row : rows(root, "ActivityDiceConstValueCommo"))
        if (constants.putIfAbsent(
                row.get("ConstValueName").getAsString(), row.getAsJsonObject("Value"))
            != null) throw new IllegalArgumentException("Duplicate DiceCombat constant");
      int activity = id(constants.get("Dice_Activity_ID"), "IntValue");
      if (activity != 50073) return null;
      var relations =
          rows(root, "ActivityConfig").stream()
              .filter(r -> id(r, "ActivityID") == activity)
              .toList();
      if (relations.size() != 1) return null;
      int panel = id(relations.getFirst(), "ActivityPanelID");
      var moduleList = numbers(relations.getFirst(), "ActivityModuleIDList", false);
      if (panel != activity
          || moduleList.isEmpty()
          || new HashSet<>(moduleList).size() != moduleList.size()) return null;
      var dice = new HashSet<Integer>();
      var colors = new HashMap<Integer, String>();
      for (var row : rows(root, "ActivityDiceConfig")) {
        int die = id(row, "DKFDAEFMFHJ");
        if (!dice.add(die)) return null;
        if (row.has("ACAALPMLBFL")) colors.put(die, row.get("ACAALPMLBFL").getAsString());
      }
      var avatars = new HashMap<Integer, Avatar>();
      for (var row : rows(root, "ActivityDiceAvatarConfig")) {
        int avatar = id(row, "DiceAvatarID");
        var ids = numbers(row, "DiceIDPerRare", true);
        var counts = numbers(row, "DiceCountPerRare", true);
        if (ids.isEmpty() || ids.size() > 4 || ids.size() != counts.size()) return null;
        var initial = new ArrayList<Integer>();
        for (int i = 0; i < ids.size(); i++) {
          int count = counts.get(i), die = ids.get(i);
          if ((die == 0) != (count == 0) || count > 16 || die != 0 && !dice.contains(die))
            return null;
          for (int n = 0; n < count; n++) initial.add(die);
        }
        if (initial.isEmpty()
            || initial.size() > 32
            || dice.contains(avatar)
            || avatars.putIfAbsent(
                    avatar,
                    new Avatar(
                        avatar,
                        row.has("Rare") ? id(row, "Rare") : 0,
                        List.copyOf(initial),
                        List.copyOf(ids),
                        List.copyOf(counts)))
                != null) return null;
      }
      var goods = new HashMap<Integer, Good>();
      for (var row : rows(root, "ActivityDiceShopGoodsConfig")) {
        int good = id(row, "DiceShopGoodsID"), item = id(row, "ItemID");
        if (!avatars.containsKey(item) && !dice.contains(item)) return null;
        var costs = new TreeMap<Integer, Integer>();
        for (var cost : row.getAsJsonArray("ItemCost")) {
          int currency = id(cost.getAsJsonObject(), "ItemID"),
              quantity = id(cost.getAsJsonObject(), "ItemNum");
          if (currency == item || costs.putIfAbsent(currency, quantity) != null) return null;
        }
        if (costs.isEmpty() || costs.size() > 4) return null;
        var gates = new ArrayList<Gate>();
        for (var gate : row.getAsJsonArray("UnlockCondition"))
          gates.add(
              new Gate(
                  gate.getAsJsonObject().get("Type").getAsString(),
                  gate.getAsJsonObject().get("Param").getAsString()));
        if (goods.putIfAbsent(good, new Good(good, item, Map.copyOf(costs), List.copyOf(gates)))
            != null) return null;
      }
      var shops = new HashMap<Integer, Shop>();
      var membership = new HashSet<Integer>();
      for (var row : rows(root, "ActivityDiceShopConfig")) {
        int shop = id(row, "DiceShopID");
        var ids = numbers(row, "GoodsList", false);
        if (ids.isEmpty()
            || ids.stream().anyMatch(g -> !goods.containsKey(g) || !membership.add(g))
            || shops.putIfAbsent(shop, new Shop(shop, ids)) != null) return null;
      }
      if (shops.isEmpty() || !membership.equals(goods.keySet())) return null;
      return new DiceCombatCatalog(
          activity,
          panel,
          Set.copyOf(moduleList),
          Map.copyOf(avatars),
          Set.copyOf(dice),
          Map.copyOf(colors),
          Map.copyOf(shops),
          Map.copyOf(goods));
    } catch (java.io.IOException | RuntimeException invalid) {
      return null;
    }
  }
}
